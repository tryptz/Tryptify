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

            // Get streaming URL
            val streamResponse = apiClient.getTrackStream(trackId, quality)
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

            // Determine save location
            val customFolderUri = preferences.downloadFolderUri.first()
            val sanitizedTitle = "${artistName} - ${trackTitle}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val fileName = "$sanitizedTitle.flac"
            val filePath: String

            if (customFolderUri != null) {
                // Save to user-selected folder via SAF
                val treeUri = customFolderUri.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.canWrite()) {
                    val existing = docFile.findFile(fileName)
                    existing?.delete()
                    val newFile = docFile.createFile("audio/flac", sanitizedTitle)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                            out.write(audioData)
                        }
                        filePath = newFile.uri.toString()
                    } else {
                        // Fallback to internal
                        filePath = saveToInternal(trackId, fileName, audioData)
                    }
                } else {
                    filePath = saveToInternal(trackId, fileName, audioData)
                }
            } else {
                filePath = saveToInternal(trackId, fileName, audioData)
            }

            // Save lyrics if enabled
            val downloadLyricsEnabled = preferences.downloadLyrics.first()
            if (downloadLyricsEnabled) {
                try {
                    val lyrics = apiClient.getLyrics(trackId)
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
                    quality = quality.name,
                    sizeBytes = audioData.size.toLong(),
                    downloadedAt = System.currentTimeMillis()
                )
            )

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveToInternal(trackId: Long, fileName: String, data: ByteArray): String {
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, "$trackId.flac")
        targetFile.writeBytes(data)
        return targetFile.absolutePath
    }
}
