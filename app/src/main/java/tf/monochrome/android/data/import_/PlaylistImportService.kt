package tf.monochrome.android.data.import_

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tf.monochrome.android.domain.model.Track
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportProgress {
    data object Idle : ImportProgress

    /** Fetching track metadata from the source (Spotify API, CSV parse, ...). */
    data class Fetching(val source: String) : ImportProgress

    /** Matching fetched tracks against the streaming catalog. */
    data class Matching(val current: Int, val total: Int, val matched: Int) : ImportProgress

    data class Done(
        val playlistId: String,
        val playlistName: String,
        val matched: Int,
        val total: Int,
        /** "Title — Artist" strings for tracks with no catalog match. */
        val unmatched: List<String>,
        /**
         * The source playlist's full length when only part of it could be
         * read (Spotify's public embed lists the first 100); else null.
         */
        val sourceTotal: Int? = null,
    ) : ImportProgress

    data class Failed(val message: String) : ImportProgress
}

/**
 * Shared create-playlist → search-match → add-track pipeline used by both
 * the CSV (Exportify) and Spotify importers. Extracted from
 * LibraryViewModel.importCsvPlaylist and instrumented with an observable
 * [progress] StateFlow so any screen can render live matched/unmatched counts.
 */
@Singleton
class PlaylistImportService @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val musicRepository: MusicRepository,
) {
    private val _progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    fun reportFetching(source: String) {
        _progress.value = ImportProgress.Fetching(source)
    }

    fun reportFailure(message: String) {
        _progress.value = ImportProgress.Failed(message)
    }

    fun resetProgress() {
        _progress.value = ImportProgress.Idle
    }

    /**
     * Where an Exportify import runs: here, not in the screen that started it.
     * An Export All of many playlists takes minutes, and run in the screen's
     * view model it stopped half done when the screen was left.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Imports every playlist in an Exportify [message], one after another, in
     * the background. Ends in [ImportProgress.Done] for each playlist with
     * songs, or in [ImportProgress.Failed]: a file that can't be read, a
     * search that fails, or an export whose playlists are all empty, which
     * used to leave the import showing as running forever.
     */
    fun importExportify(message: ExportifyFiles.Message, strictAlbumMatch: Boolean) {
        scope.launch {
            reportFetching("Exportify")
            try {
                var imported = 0
                for (playlist in ExportifyFiles.playlists(message)) {
                    val parsed = PlaylistCsv.parse(playlist.csv, fallbackTitle = playlist.title)
                    if (parsed.tracks.isEmpty()) continue
                    importTracks(playlist.title, null, parsed.tracks, strictAlbumMatch)
                    imported++
                }
                if (imported == 0) reportFailure("Exportify's playlists have no songs in them.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e.message.orEmpty())
            }
        }
    }

    /**
     * Creates the playlist, then matches each track against the streaming
     * catalog and adds the best hit. Returns the terminal [ImportProgress.Done]
     * (also published on [progress]).
     */
    suspend fun importTracks(
        name: String,
        description: String?,
        tracks: List<CsvTrack>,
        strictAlbumMatch: Boolean,
        /** See [ImportProgress.Done.sourceTotal]. */
        sourceTotal: Int? = null,
    ): ImportProgress.Done {
        val playlistId = libraryRepository.createPlaylist(name, description)
        val unmatched = mutableListOf<String>()
        var matched = 0
        // Searched [MATCH_PARALLELISM] at a time. Matching is a network wait
        // per song, one or two searches each, so one at a time made a
        // 100-song playlist a hundred round trips back to back. The playlist
        // is still filled in its own order: each song is added once it and
        // every song before it have been searched, and the adds stay on this
        // coroutine, one by one.
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val found = java.util.concurrent.atomic.AtomicInteger(0)
        _progress.value = ImportProgress.Matching(current = 0, total = tracks.size, matched = 0)
        coroutineScope {
            val permits = Semaphore(MATCH_PARALLELISM)
            val searches = tracks.map { csvTrack ->
                async {
                    permits.withPermit { bestMatchFor(csvTrack, strictAlbumMatch) }.also { hit ->
                        if (hit != null) found.incrementAndGet()
                        val done = completed.incrementAndGet()
                        // Only forward: the searches finish in any order, so
                        // the one that counted 99 could publish after the one
                        // that counted 100, and the count went back to 99.
                        _progress.update { shown ->
                            nextMatching(shown, done, tracks.size, found.get())
                        }
                    }
                }
            }
            searches.forEachIndexed { index, search ->
                val bestMatch = search.await()
                if (bestMatch != null) {
                    libraryRepository.addTrackToPlaylist(playlistId, bestMatch)
                    matched++
                } else {
                    unmatched += "${tracks[index].title} — ${tracks[index].artist}"
                }
            }
        }

        val done = ImportProgress.Done(
            playlistId = playlistId,
            playlistName = name,
            matched = matched,
            total = tracks.size,
            unmatched = unmatched,
            sourceTotal = sourceTotal?.takeIf { it > tracks.size },
        )
        _progress.value = done
        return done
    }

    /** The catalog track [csvTrack] is, or null. */
    private suspend fun bestMatchFor(csvTrack: CsvTrack, strictAlbumMatch: Boolean): Track? {
        val query = "${csvTrack.title} ${csvTrack.artist}"
        // Qobuz first: searchQobuz registers every hit in QobuzIdRegistry,
        // which is what routes the stored id to Qobuz playback later.
        // Tidal is the per-track fallback when Qobuz has no result (or no
        // Qobuz instance is configured, in which case it returns nothing).
        val results = musicRepository.searchQobuz(query).getOrNull()?.tracks
            ?.takeIf { it.isNotEmpty() }
            ?: musicRepository.searchTracks(query).getOrNull().orEmpty()
        return if (strictAlbumMatch && csvTrack.album.isNotBlank()) {
            // Truly strict: if no result's album matches, leave the row
            // unmatched rather than substituting an arbitrary same-named
            // track from a different album. Titles are normalized (edition
            // suffixes, punctuation) so "(Remastered)" etc. don't cause
            // false misses.
            val wanted = normalizeAlbum(csvTrack.album)
            results.find { normalizeAlbum(it.album?.title.orEmpty()) == wanted }
        } else {
            results.firstOrNull()
        }
    }

    private companion object {
        /**
         * Searches in flight at once: enough to hide most of each round
         * trip, few enough not to flood the instance being searched.
         */
        const val MATCH_PARALLELISM = 6
    }

    /**
     * Normalize an album title for strict comparison: drop parenthetical/
     * bracketed qualifiers and common edition/remaster wording, strip
     * punctuation, and collapse whitespace so "Album (Remastered 2011)" and
     * "Album - Deluxe Edition" compare equal to "Album".
     */
    private fun normalizeAlbum(raw: String): String =
        raw.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(
                Regex("\\b(remaster(ed)?|deluxe|expanded|anniversary|edition|version|mono|stereo|bonus|track)\\b(\\s+\\d{2,4})?"),
                " "
            )
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}

/**
 * The matching progress to show once [done] of [total] searches have
 * finished: never behind what is [shown], since the searches publish in any
 * order. Any other state (a new import's first Matching, Done) is replaced.
 */
internal fun nextMatching(shown: ImportProgress, done: Int, total: Int, matched: Int): ImportProgress =
    if (shown is ImportProgress.Matching && shown.total == total && shown.current >= done) shown
    else ImportProgress.Matching(current = done, total = total, matched = matched)
