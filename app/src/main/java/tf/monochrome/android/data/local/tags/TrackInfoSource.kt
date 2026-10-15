package tf.monochrome.android.data.local.tags

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file behind a song in a list, for its Track info: the path the screen
 * already knows (a file in the library), else its download, else a stream
 * already in the play cache. Never fetches: reading a song's tags must not
 * start a download of it, as Share file does when there is nothing local.
 */
@Singleton
class TrackInfoSource @Inject constructor(
    private val downloadDao: DownloadDao,
    private val qobuzCache: QobuzStreamCacheManager,
) {
    /** [track]'s file read, or null when there is none on this device. */
    suspend fun fileTags(track: Track, knownPath: String?): FileTags? = withContext(Dispatchers.IO) {
        knownPath?.let(::readable)?.let { return@withContext FileTagsReader.read(it) }
        runCatching { downloadDao.getDownloadedTrack(track.id) }.getOrNull()
            ?.filePath?.let(::readable)
            ?.let { return@withContext FileTagsReader.read(it) }
        for (quality in arrayOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.LOW)) {
            val cached = runCatching { qobuzCache.peekCached(track.id, quality) }.getOrNull() ?: continue
            return@withContext FileTagsReader.read(cached.path)
        }
        null
    }

    /** A path a File can open: not a content:// or other URI. */
    private fun readable(path: String): String? = when {
        path.startsWith("file://") -> path.removePrefix("file://")
        path.contains("://") -> null
        path.isBlank() -> null
        else -> path
    }
}

/** For the song menu, which is composed where no ViewModel is in reach. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackInfoEntryPoint {
    fun trackInfoSource(): TrackInfoSource
}
