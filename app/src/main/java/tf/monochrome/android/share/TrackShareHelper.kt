package tf.monochrome.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Track
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and dispatches an `Intent.ACTION_SEND` carrying the actual audio
 * file for a track so the user can hand the FLAC (or MP3) directly to any
 * app on the share sheet.
 *
 * Resolution order:
 *   1. DownloadedTrackEntity.filePath — the user-initiated download. If
 *      the path is a content:// URI (SAF folder) it's shared as-is. If
 *      it's an absolute file path, FileProvider wraps it.
 *   2. QobuzStreamCacheManager — for tracks that have been streamed
 *      through the cache-on-demand path. The cached file is always under
 *      the app's cache dir, so FileProvider always wraps it.
 *
 * If neither source has a file, the call is a no-op and a warning is
 * logged. We don't try to download on demand — surface a toast/snackbar
 * upstream if you want to nudge the user to download first.
 */
@Singleton
class TrackShareHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val qobuzCache: QobuzStreamCacheManager,
) {
    /** Suspends only on the DB lookup; the intent dispatch is fire-and-forget. */
    suspend fun shareTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        val downloaded = runCatching { downloadDao.getDownloadedTrack(track.id) }.getOrNull()
        if (downloaded != null) {
            return@withContext shareDownloadedTrack(downloaded)
        }
        // Already cached from a previous play? Try the qualities we save under
        // — LOSSLESS first, then HI_RES, since the cache key encodes (id,
        // quality) and we don't know which qualities the user has played at.
        for (quality in arrayOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES, AudioQuality.HIGH, AudioQuality.LOW)) {
            val cached = runCatching { qobuzCache.peekCached(track.id, quality) }.getOrNull()
            if (cached != null) {
                return@withContext shareLocalFile(cached, track.title, mimeFor(cached.name))
            }
        }
        // Download-on-demand: nothing local, so reach out to the Qobuz
        // instance and pull the file into the playback cache, then share
        // from there. This may take several seconds for a typical FLAC —
        // surface a Toast so the user knows the tap registered.
        // PlayerViewModel.shareTrack drives this from viewModelScope so
        // the fetch outlives the originating tap.
        toast("Preparing ${track.title} for share…")
        val fetched = runCatching {
            qobuzCache.getOrFetch(track.id, AudioQuality.LOSSLESS)
        }.getOrNull()
        if (fetched != null) {
            return@withContext shareLocalFile(fetched, track.title, mimeFor(fetched.name))
        }
        toast("No file available to share")
        Log.i(TAG, "shareTrack: no local file and download-on-demand failed for trackId=${track.id}")
        false
    }

    private suspend fun toast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareDownloadedTrack(entity: DownloadedTrackEntity): Boolean {
        val uri = downloadedTrackUri(entity) ?: return false
        return launchShare(uri, entity.title, mimeFor(entity.filePath))
    }

    private fun downloadedTrackUri(entity: DownloadedTrackEntity): Uri? {
        return runCatching {
            when {
                entity.filePath.startsWith("content://") -> entity.filePath.toUri()
                entity.filePath.startsWith("file://") -> entity.filePath.toUri()
                else -> {
                    val file = File(entity.filePath)
                    if (!file.exists()) return null
                    FileProvider.getUriForFile(context, fileProviderAuthority(), file)
                }
            }
        }.getOrNull()
    }

    private fun shareLocalFile(file: File, title: String, mimeType: String): Boolean {
        if (!file.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, fileProviderAuthority(), file)
        }.getOrNull() ?: return false
        return launchShare(uri, title, mimeType)
    }

    private fun launchShare(uri: Uri, title: String, mimeType: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share track").apply {
            // The share sheet is launched from a non-Activity context (the
            // ApplicationContext-scoped helper), so it needs NEW_TASK.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.onFailure { Log.w(TAG, "shareTrack: launch failed", it) }.getOrDefault(false)
    }

    private fun mimeFor(pathOrName: String): String {
        val lower = pathOrName.lowercase()
        return when {
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "audio/flac"
        }
    }

    private fun fileProviderAuthority(): String = "${context.packageName}.fileprovider"

    companion object {
        private const val TAG = "TrackShareHelper"
    }
}
