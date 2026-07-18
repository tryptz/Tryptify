package tf.monochrome.android.data.import_

import kotlinx.coroutines.flow.MutableStateFlow
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
     * Creates the playlist, then matches each track against the streaming
     * catalog and adds the best hit. Returns the terminal [ImportProgress.Done]
     * (also published on [progress]).
     */
    suspend fun importTracks(
        name: String,
        description: String?,
        tracks: List<CsvTrack>,
        strictAlbumMatch: Boolean,
    ): ImportProgress.Done {
        val playlistId = libraryRepository.createPlaylist(name, description)
        val unmatched = mutableListOf<String>()
        var matched = 0

        tracks.forEachIndexed { index, csvTrack ->
            _progress.value = ImportProgress.Matching(
                current = index + 1,
                total = tracks.size,
                matched = matched,
            )

            val query = "${csvTrack.title} ${csvTrack.artist}"
            // Qobuz first: searchQobuz registers every hit in QobuzIdRegistry,
            // which is what routes the stored id to Qobuz playback later.
            // Tidal is the per-track fallback when Qobuz has no result (or no
            // Qobuz instance is configured, in which case it returns nothing).
            val results = musicRepository.searchQobuz(query).getOrNull()?.tracks
                ?.takeIf { it.isNotEmpty() }
                ?: musicRepository.searchTracks(query).getOrNull().orEmpty()

            val bestMatch = if (strictAlbumMatch && csvTrack.album.isNotBlank()) {
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

            if (bestMatch != null) {
                libraryRepository.addTrackToPlaylist(playlistId, bestMatch)
                matched++
            } else {
                unmatched += "${csvTrack.title} — ${csvTrack.artist}"
            }
        }

        val done = ImportProgress.Done(
            playlistId = playlistId,
            playlistName = name,
            matched = matched,
            total = tracks.size,
            unmatched = unmatched,
        )
        _progress.value = done
        return done
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
