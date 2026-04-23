package tf.monochrome.android.data.local.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AudioFileInfo(
    val absolutePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val duration: Long,
    val uri: Uri
)

@Singleton
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    private val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DURATION
    )

    // Anything MediaStore classifies with an audio/* MIME type and IS_MUSIC=1
    // is kept. The previous 12-entry allowlist silently dropped DSD (audio/dsf),
    // Musepack (audio/x-musepack), TAK, WavPack (audio/x-wavpack), TrueHD,
    // Matroska-audio (audio/x-matroska), RealAudio, and any variant MIME a
    // particular device's media scanner produced. Codec identification still
    // happens downstream in TagReader from the MIME + extension; unknown codecs
    // fall through to AudioCodec.UNKNOWN rather than being dropped at scan time.
    private fun isAudioMime(mime: String?): Boolean {
        if (mime == null) return false
        val lower = mime.lowercase()
        if (!lower.startsWith("audio/")) return false
        // MIDI files (audio/midi, audio/x-midi, audio/mid) aren't real recordings
        // — they're synth instructions and the app can't play them. MediaStore
        // still indexes them as IS_MUSIC=1 so we need an explicit exclusion.
        if (lower == "audio/midi" || lower == "audio/x-midi" || lower == "audio/mid") return false
        return true
    }

    private fun isExcludedExtension(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "mid" || ext == "midi" || ext == "kar" || ext == "rmi"
    }

    fun queryAllAudio(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet()
    ): List<AudioFileInfo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val selection = buildString {
            append("${MediaStore.Audio.Media.DURATION} >= ?")
            append(" AND ${MediaStore.Audio.Media.IS_MUSIC} = 1")
        }
        val selectionArgs = arrayOf(minDurationMs.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val results = mutableListOf<AudioFileInfo>()

        contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: continue
                val id = cursor.getLong(idCol)

                // Filter by mime type
                if (!isAudioMime(mime)) continue

                // Skip MIDI-class files by extension too (some devices report
                // them with a non-midi MIME but the extension is always .mid
                // / .midi / .kar / .rmi).
                if (isExcludedExtension(path)) continue

                // Filter excluded paths
                if (excludedPaths.any { path.startsWith(it) }) continue

                val uri = Uri.withAppendedPath(collection, id.toString())

                results.add(
                    AudioFileInfo(
                        absolutePath = path,
                        displayName = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                        mimeType = mime,
                        sizeBytes = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol) * 1000, // seconds to millis
                        duration = cursor.getLong(durCol),
                        uri = uri
                    )
                )
            }
        }

        return results
    }

    fun queryModifiedSince(
        sinceTimestamp: Long,
        minDurationMs: Long = 30_000
    ): List<AudioFileInfo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val sinceSeconds = sinceTimestamp / 1000
        val selection = buildString {
            append("${MediaStore.Audio.Media.DATE_MODIFIED} > ?")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
            append(" AND ${MediaStore.Audio.Media.IS_MUSIC} = 1")
        }
        val selectionArgs = arrayOf(sinceSeconds.toString(), minDurationMs.toString())

        val results = mutableListOf<AudioFileInfo>()

        contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, id.toString())

                results.add(
                    AudioFileInfo(
                        absolutePath = path,
                        displayName = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                        mimeType = cursor.getString(mimeCol) ?: "",
                        sizeBytes = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol) * 1000,
                        duration = cursor.getLong(durCol),
                        uri = uri
                    )
                )
            }
        }

        return results
    }
}
