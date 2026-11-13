package tf.monochrome.android.data.import_

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.R
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.repository.LocalMediaRepository.Companion.toUnifiedTrack
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.TrackSource
import tf.monochrome.android.player.UnifiedTrackRegistry
import tf.monochrome.android.util.safDocumentUriToPath
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports a picked `.m3u` / `.m3u8` file as a playlist of the listener's own
 * music (issue #155).
 *
 * The other imports look each song up in a streaming catalogue. An M3U names
 * files, so its songs are looked up in the local library instead
 * ([M3uLocalMatcher]), and the playlist is made of what was found, in the
 * file's order. Progress and the outcome go through [PlaylistImportService],
 * so the Library shows them as it shows a CSV import's.
 *
 * Runs here, not in the screen that started it: the dialog closes as the
 * import starts, and a view model's scope would stop it half written if the
 * Library were left.
 */
@Singleton
class M3uPlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaDao: LocalMediaDao,
    private val libraryRepository: LibraryRepository,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val importService: PlaylistImportService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Imports [uri] as a new playlist called [name]; blank takes the file's
     * own `#PLAYLIST` name, else the file's name. Ends in
     * [ImportProgress.Done] or [ImportProgress.Failed], never in neither.
     */
    fun import(uri: Uri, name: String, description: String?) {
        scope.launch {
            importService.reportFetching("M3U")
            try {
                run(uri, name, description)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Blank: the Library then says the file could not be read as a playlist.
                importService.reportFailure("")
            }
        }
    }

    private suspend fun run(uri: Uri, name: String, description: String?) {
        val (playlist, fileName) = withContext(Dispatchers.IO) {
            M3u.parse(M3u.decode(readBytes(uri))) to displayName(uri)
        }
        if (playlist.entries.isEmpty()) {
            importService.reportFailure(context.getString(R.string.import_m3u_empty))
            return
        }
        val library = localMediaDao.getAllTracksSnapshot()
        if (library.isEmpty()) {
            importService.reportFailure(context.getString(R.string.import_m3u_no_local_music))
            return
        }
        // The folder the file is in, for the paths written relative to it.
        // Only the system's own file picker says; a file from elsewhere is
        // matched by the ends of its paths.
        val playlistDir = safDocumentUriToPath(uri)?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        val outcome = matchAgainst(playlist.entries, library, playlistDir)
        if (outcome.found.isEmpty()) {
            importService.reportFailure(context.getString(R.string.import_m3u_none_found))
            return
        }

        val title = name.trim().ifEmpty {
            playlist.title ?: fileName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "M3U"
        }
        val playlistId = libraryRepository.createPlaylist(title, description?.trim()?.takeIf { it.isNotEmpty() })
        val tracks = outcome.found.map { row ->
            // Registered as a played local song is, so this run plays it
            // from that very file. (After a restart a playlist's local song
            // is found again by its tags, like every local song in one.)
            val unified = row.toUnifiedTrack()
            unified.toLegacyTrack().also { unifiedTrackRegistry.put(it.id, unified) }
        }
        // Filed as local outright: the registry forgets songs outside the
        // queue, and a row filed by guesswork could come out as TIDAL's.
        val added = libraryRepository.addTracksToPlaylist(playlistId, tracks, source = TrackSource.LOCAL)
        importService.reportDone(
            ImportProgress.Done(
                playlistId = playlistId,
                playlistName = title,
                matched = added,
                total = outcome.total,
                unmatched = outcome.missing,
            )
        )
    }

    /** As [CsvPlaylistParser] reads a file: whole, up to a cap, and not past it. */
    private fun readBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("unopened")
        return stream.use { input ->
            val buffered = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffered.write(chunk, 0, read)
                require(buffered.size() <= M3u.MAX_BYTES) { "too large" }
            }
            buffered.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index != -1) cursor.getString(index)?.let { return it }
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    /** What [matchAgainst] found. */
    internal class Outcome<T>(
        /** The library's rows for the songs found, in the playlist's order, each once. */
        val found: List<T>,
        /** The songs not found, as the import names them. */
        val missing: List<String>,
        /** Songs in the file, a song listed twice counted once: a playlist holds a song once. */
        val total: Int,
    )

    internal companion object {
        /**
         * Every entry looked up in [library]. A file the playlist lists more
         * than once is kept where it first comes and not counted again.
         */
        fun matchAgainst(
            entries: List<M3uEntry>,
            library: List<LocalTrackEntity>,
            playlistDir: String?,
        ): Outcome<LocalTrackEntity> {
            val rows = library.associateBy { it.id }
            val matcher = M3uLocalMatcher(
                library.map {
                    M3uLocalMatcher.LocalFile(
                        id = it.id,
                        path = it.filePath,
                        title = it.displayTitle,
                        artist = it.artist ?: it.albumArtist.orEmpty(),
                        durationSeconds = it.durationSeconds,
                    )
                },
                playlistDir,
            )
            val found = LinkedHashMap<Long, LocalTrackEntity>()
            val missing = ArrayList<String>()
            var repeats = 0
            for (entry in entries) {
                val row = matcher.match(entry)?.let { rows[it.id] }
                when {
                    row == null -> missing += entry.label
                    row.id in found -> repeats++
                    else -> found[row.id] = row
                }
            }
            return Outcome(found.values.toList(), missing, entries.size - repeats)
        }
    }
}
