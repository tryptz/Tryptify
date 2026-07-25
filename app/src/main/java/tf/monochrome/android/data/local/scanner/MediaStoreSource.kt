package tf.monochrome.android.data.local.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
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

    // ── Atmos video containers ───────────────────────────────────────────────
    // An .mp4 that carries a video stream lands in MediaStore's *video* table as
    // video/mp4, never in the audio table, so the query above cannot see it —
    // even when its audio track is Dolby Atmos and the player renders it fine.
    // Atmos music videos are the case that matters here, so video rows are
    // admitted only when the file actually carries an E-AC-3 track. Everything
    // else (screen recordings, camera clips) stays out of the library.

    private val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DURATION
    )

    // MediaFormat.MIMETYPE_AUDIO_EAC3_JOC is API 31; spell both out so this
    // compiles and behaves identically down to minSdk 26.
    private fun isEac3Mime(mime: String?): Boolean =
        mime == "audio/eac3" || mime == "audio/eac3-joc"

    /** Containers that can carry an E-AC-3 track — cheap filter before opening. */
    private fun mayCarryEac3(path: String): Boolean =
        when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v", "mov", "mkv", "ts", "m2ts", "mts" -> true
            else -> false
        }

    /**
     * The MIME of the file's E-AC-3 audio track, or null if it has none. Opens
     * the container with [MediaExtractor], which reads only the header/index,
     * not the media data. Returns null on any failure — an unreadable or DRM'd
     * video must skip quietly rather than abort the whole scan.
     */
    private fun eac3TrackMime(uri: Uri): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .asSequence()
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { isEac3Mime(it) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // Malformed containers can make the platform extractor over-allocate.
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Video-container files whose audio track is E-AC-3, mapped onto the same
     * [AudioFileInfo] the audio table produces. [sinceSeconds] restricts to rows
     * modified after that time (incremental scan); null scans everything.
     *
     * Deliberately NOT restricted to the library's folder roots: Atmos videos
     * are usually saved wherever the download landed rather than under a music
     * folder, and the E-AC-3 requirement is already a narrow enough filter.
     */
    private fun queryEac3Video(
        minDurationMs: Long,
        excludedPaths: Set<String>,
        sinceSeconds: Long? = null
    ): List<AudioFileInfo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val selection = buildString {
            append("${MediaStore.Video.Media.DURATION} >= ?")
            if (sinceSeconds != null) append(" AND ${MediaStore.Video.Media.DATE_MODIFIED} > ?")
        }
        val selectionArgs = if (sinceSeconds != null) {
            arrayOf(minDurationMs.toString(), sinceSeconds.toString())
        } else {
            arrayOf(minDurationMs.toString())
        }

        val results = mutableListOf<AudioFileInfo>()
        contentResolver.query(collection, videoProjection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    if (!mayCarryEac3(path)) continue
                    if (excludedPaths.any { path.startsWith(it) }) continue

                    val uri = Uri.withAppendedPath(collection, cursor.getLong(idCol).toString())
                    // The only expensive step, and it runs last so the cheap
                    // filters above have already thrown most candidates out.
                    val audioMime = eac3TrackMime(uri) ?: continue

                    results.add(
                        AudioFileInfo(
                            absolutePath = path,
                            displayName = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                            // Report the audio MIME, not the container's video/mp4,
                            // so downstream codec detection resolves E-AC-3.
                            mimeType = audioMime,
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

    fun queryAllAudio(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet(),
        folderRoots: Set<String> = emptySet()
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

                // Restrict to user-chosen library roots. Empty set = no
                // restriction (whole-device scan, the pre-onboarding default).
                if (!isUnderRoots(path, folderRoots)) continue

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

        results += queryEac3Video(minDurationMs, excludedPaths)
        return results
    }

    fun queryModifiedSince(
        sinceTimestamp: Long,
        minDurationMs: Long = 30_000,
        folderRoots: Set<String> = emptySet()
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
                if (!isUnderRoots(path, folderRoots)) continue
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

        results += queryEac3Video(minDurationMs, emptySet(), sinceSeconds)
        return results
    }

    /**
     * Count indexed audio tracks under [path]. Used by onboarding's folder
     * picker for the "Found N tracks in this folder" preview. Queries
     * MediaStore rather than walking the tree so the number matches what a
     * scan will actually import (the scanner is MediaStore-only — files the
     * media indexer hasn't seen yet won't be found by either).
     */
    fun countAudioUnderPath(path: String, minDurationMs: Long = 30_000): Int {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val escaped = escapeLikePattern(path.trimEnd('/'))
        val selection = buildString {
            append("${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
            append(" AND ${MediaStore.Audio.Media.IS_MUSIC} = 1")
        }
        val selectionArgs = arrayOf("$escaped/%", minDurationMs.toString())

        var count = 0
        contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.MIME_TYPE),
            selection, selectionArgs, null
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val filePath = cursor.getString(dataCol) ?: continue
                if (!isAudioMime(cursor.getString(mimeCol))) continue
                if (isExcludedExtension(filePath)) continue
                count++
            }
        }
        return count
    }

    companion object {
        /**
         * True when [path] falls under one of [roots] (or roots is empty =
         * unrestricted). A root matches its own path and descendants only —
         * the trailing '/' in the prefix check keeps /Music from also
         * matching /MusicVideos.
         */
        fun isUnderRoots(path: String, roots: Set<String>): Boolean {
            if (roots.isEmpty()) return true
            return roots.any { root ->
                val r = root.trimEnd('/')
                path == r || path.startsWith("$r/")
            }
        }

        /** Escape %, _ and \ for a LIKE pattern using '\' as the escape char. */
        fun escapeLikePattern(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
