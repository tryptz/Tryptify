package tf.monochrome.android.data.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioQuality
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: HiFiApiClient,
    private val lrcLibClient: tf.monochrome.android.data.api.LrcLibClient,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val downloadDao: DownloadDao
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TRACK_TITLE = "track_title"
        const val KEY_ARTIST_NAME = "artist_name"
        const val KEY_ALBUM_TITLE = "album_title"
        const val KEY_ALBUM_COVER = "album_cover"
        const val KEY_DURATION = "duration"
        const val KEY_PROGRESS = "progress"
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L)
        if (trackId == -1L) return Result.failure()

        val trackTitle = inputData.getString(KEY_TRACK_TITLE) ?: "Unknown"
        val artistName = inputData.getString(KEY_ARTIST_NAME) ?: "Unknown Artist"
        val albumTitle = inputData.getString(KEY_ALBUM_TITLE)
        val albumCover = inputData.getString(KEY_ALBUM_COVER)
        val duration = inputData.getInt(KEY_DURATION, 0)

        return try {
            // Get download quality preference
            val quality = preferences.downloadQuality.first()

            // Get streaming URL from the dedicated TrypT HiFi (Qobuz) download instance
            val streamResponse = apiClient.getTrackStream(trackId, quality, forDownload = true)
            val streamUrl = streamResponse.streamUrl
                ?: return Result.failure()

            // Download audio bytes with progress
            setProgress(workDataOf(KEY_PROGRESS to 0.05f))
            val response = httpClient.get(streamUrl)
            if (!response.status.isSuccess()) return Result.failure()

            val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            var totalRead = 0L

            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                totalRead += read
                if (contentLength > 0) {
                    val progress = (totalRead.toFloat() / contentLength).coerceIn(0.05f, 0.95f)
                    setProgress(workDataOf(KEY_PROGRESS to progress))
                }
            }
            val audioData = output.toByteArray()

            // Detect what the backend actually delivered (not just what was
            // requested): the download instance can downgrade HI_RES→LOSSLESS,
            // and LOW/HIGH come back as MP3. Basing the extension/mime and the
            // stored record on the real bytes keeps lossy files from being
            // mislabelled .flac (breaks MediaStore + other players) and makes the
            // saved quality accurate.
            val customFolderUri = preferences.downloadFolderUri.first()
            val actualQuality = detectActualQuality(audioData, quality)
            val isFlac = actualQuality == AudioQuality.LOSSLESS || actualQuality == AudioQuality.HI_RES
            val fileExt = if (isFlac) "flac" else "mp3"
            val audioMime = if (isFlac) "audio/flac" else "audio/mpeg"
            val sanitizedTitle = "${artistName} - ${trackTitle}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val fileName = "$sanitizedTitle.$fileExt"
            val filePath: String

            if (customFolderUri != null) {
                // Save to user-selected folder via SAF
                val treeUri = customFolderUri.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.canWrite()) {
                    val existing = docFile.findFile(fileName)
                    existing?.delete()
                    val newFile = docFile.createFile(audioMime, sanitizedTitle)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                            out.write(audioData)
                        }
                        filePath = newFile.uri.toString()
                    } else {
                        // Fallback to internal
                        filePath = saveToInternal(trackId, fileExt, audioData)
                    }
                } else {
                    filePath = saveToInternal(trackId, fileExt, audioData)
                }
            } else {
                filePath = saveToInternal(trackId, fileExt, audioData)
            }

            // Save lyrics if enabled. TIDAL is preferred (best quality
            // synced LRC); LRCLib fills in for anything TIDAL 404s on,
            // which is most older / niche / non-Western catalog.
            val downloadLyricsEnabled = preferences.downloadLyrics.first()
            if (downloadLyricsEnabled) {
                try {
                    val lyrics = apiClient.getLyrics(trackId)
                        ?: lrcLibClient.lookup(
                            title = trackTitle,
                            artist = artistName,
                            album = albumTitle,
                            durationSeconds = duration.takeIf { it > 0 },
                        )
                    if (lyrics != null && lyrics.isSynced) {
                        val lrcContent = StringBuilder()
                        lyrics.lines.forEach { line ->
                            val minutes = line.timeMs / 1000 / 60
                            val seconds = (line.timeMs / 1000.0) % 60
                            val timeStr = String.format(Locale.US, "[%02d:%05.2f]", minutes, seconds)
                            lrcContent.append("$timeStr${line.text}\n")
                        }

                        val lrcFileName = "$sanitizedTitle.lrc"
                        if (customFolderUri != null) {
                            val treeUri = customFolderUri.toUri()
                            val docFile = DocumentFile.fromTreeUri(context, treeUri)
                            if (docFile != null && docFile.canWrite()) {
                                val existing = docFile.findFile(lrcFileName)
                                existing?.delete()
                                val lrcFile = docFile.createFile("text/plain", sanitizedTitle)
                                lrcFile?.let {
                                    context.contentResolver.openOutputStream(it.uri)?.use { out ->
                                        out.write(lrcContent.toString().toByteArray())
                                    }
                                }
                            }
                        } else {
                            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
                            val lrcFile = File(downloadsDir, "$trackId.lrc")
                            lrcFile.writeText(lrcContent.toString())
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Save the album art alongside the track. Two reasons:
            //   1. The system MediaScanner picks up `cover.jpg` /
            //      `albumart.jpg` in the same folder as audio files and
            //      attaches them as the album image automatically — that's
            //      what makes downloaded albums show their cover in the
            //      Local tab on a fresh install.
            //   2. Other Android players (and our own DownloadsScreen) can
            //      load the cover off-line.
            // Errors here are non-fatal — losing the cover shouldn't fail
            // the whole download.
            if (!albumCover.isNullOrBlank()) {
                runCatching { saveAlbumArt(albumCover, sanitizedTitle, customFolderUri) }
            }

            // Tell MediaStore about the new audio + cover so the Local tab
            // sees them without a manual rescan. Only meaningful when the
            // file is in shared storage (SAF folder); for app-private
            // downloads MediaStore won't index regardless.
            notifyMediaScanner(filePath)

            // Insert into database
            downloadDao.insertDownloadedTrack(
                DownloadedTrackEntity(
                    id = trackId,
                    title = trackTitle,
                    duration = duration,
                    artistName = artistName,
                    albumTitle = albumTitle,
                    albumCover = albumCover,
                    filePath = filePath,
                    quality = actualQuality.name,
                    sizeBytes = audioData.size.toLong(),
                    downloadedAt = System.currentTimeMillis()
                )
            )

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * Fetches the album cover URL and saves it both as `<sanitizedTitle>.jpg`
     * (per-track sidecar, matched by some MP3-style players) and as
     * `cover.jpg` in the same folder (the Android MediaScanner convention).
     * Skipped silently on network or SAF failure.
     */
    private suspend fun saveAlbumArt(
        coverUrl: String,
        sanitizedTitle: String,
        customFolderUri: String?,
    ) {
        val response = httpClient.get(coverUrl)
        if (!response.status.isSuccess()) return
        val bytes = response.readBytes()
        if (bytes.isEmpty()) return

        if (customFolderUri != null) {
            val treeUri = customFolderUri.toUri()
            val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return
            if (!docFile.canWrite()) return
            // Per-track sidecar.
            val perTrackName = "$sanitizedTitle.jpg"
            docFile.findFile(perTrackName)?.delete()
            docFile.createFile("image/jpeg", sanitizedTitle)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                notifyMediaScanner(file.uri.toString())
            }
            // Folder-level cover.jpg — MediaScanner reads this for the
            // album thumbnail without needing to embed the picture in
            // each FLAC's METADATA_BLOCK_PICTURE.
            if (docFile.findFile("cover.jpg") == null) {
                docFile.createFile("image/jpeg", "cover")?.let { file ->
                    context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                    notifyMediaScanner(file.uri.toString())
                }
            }
        } else {
            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            File(downloadsDir, "$sanitizedTitle.jpg").writeBytes(bytes)
            // App-private storage isn't MediaStore-visible, so cover.jpg
            // is purely for the in-app off-line cover lookup.
            val coverFile = File(downloadsDir, "cover.jpg")
            if (!coverFile.exists()) coverFile.writeBytes(bytes)
        }
    }

    /**
     * Triggers MediaScannerConnection.scanFile for files in shared storage
     * so the Local tab picks them up. content:// SAF URIs are mapped back
     * to their on-disk path via DocumentFile / DocumentsContract; raw file
     * paths are scanned directly. Failures are silent — the download
     * succeeded regardless.
     */
    private fun notifyMediaScanner(pathOrUri: String) {
        runCatching {
            val resolved = when {
                pathOrUri.startsWith("content://") -> {
                    // Best effort: we only need the path for MediaScanner,
                    // and not all SAF backings expose one. If we can't
                    // resolve, skip — the user's MediaStore cache will
                    // catch it on the next general scan.
                    null
                }
                pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
                else -> pathOrUri
            } ?: return
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(resolved),
                null,
                null,
            )
        }
    }

    /**
     * Infer the format actually delivered from the file bytes, so the saved record
     * reflects reality rather than the requested tier:
     *  - "fLaC" magic → FLAC; read bits-per-sample from STREAMINFO to tell
     *    HI_RES (≥24-bit) from LOSSLESS (16-bit), catching a HI_RES→LOSSLESS downgrade.
     *  - anything else → lossy (MP3) → report as HIGH.
     * Falls back to [requested] if the bytes are too short to classify.
     */
    private fun detectActualQuality(data: ByteArray, requested: AudioQuality): AudioQuality {
        if (data.size < 4) return requested
        val isFlac = data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()
        if (!isFlac) return AudioQuality.HIGH
        // STREAMINFO begins at byte 8 (4 magic + 4 block header). Bits-per-sample
        // is the 5-bit field straddling bytes 20 (low bit) and 21 (high 4 bits),
        // stored as value-1.
        if (data.size < 22) return AudioQuality.LOSSLESS
        val b20 = data[20].toInt() and 0xFF
        val b21 = data[21].toInt() and 0xFF
        val bitsPerSample = (((b20 and 0x01) shl 4) or (b21 shr 4)) + 1
        return if (bitsPerSample >= 24) AudioQuality.HI_RES else AudioQuality.LOSSLESS
    }

    private fun saveToInternal(trackId: Long, ext: String, data: ByteArray): String {
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, "$trackId.$ext")
        targetFile.writeBytes(data)
        return targetFile.absolutePath
    }
}
