package tf.monochrome.android.data.local.tags

import android.content.Context
import android.graphics.BitmapFactory
import android.annotation.SuppressLint
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import tf.monochrome.android.domain.model.AudioCodec
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class AudioTags(
    // Core identity
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,

    // Extended metadata
    val composer: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,

    // Audio properties
    val codec: AudioCodec = AudioCodec.UNKNOWN,
    val sampleRate: Int = 44100,
    val bitDepth: Int? = null,
    val bitRate: Int = 0,
    val channels: Int = 2,
    val durationSeconds: Int = 0,

    // Replay Gain (parsed from custom tags when available)
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,

    // Artwork
    val hasEmbeddedArt: Boolean = false,
    val artworkCacheKey: String? = null,

    // File info
    val filePath: String = "",
    val fileSizeBytes: Long = 0,
    val lastModified: Long = 0
)

@Singleton
class TagReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val artworkCacheDir: File by lazy {
        File(context.cacheDir, "artwork").also { it.mkdirs() }
    }

    suspend fun readTags(filePath: String): AudioTags {
        val file = File(filePath)
        if (!file.exists()) return AudioTags(filePath = filePath)

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            extractTags(retriever, file)
        } catch (e: Exception) {
            AudioTags(
                filePath = filePath,
                fileSizeBytes = file.length(),
                lastModified = file.lastModified(),
                codec = detectCodecFromExtension(filePath)
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun readTagsFromUri(uri: Uri): AudioTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            extractTagsFromRetriever(retriever, uri.toString(), 0, 0)
        } catch (e: Exception) {
            AudioTags()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractTags(retriever: MediaMetadataRetriever, file: File): AudioTags {
        return extractTagsFromRetriever(
            retriever,
            file.absolutePath,
            file.length(),
            file.lastModified()
        )
    }

    @SuppressLint("InlinedApi") // METADATA_KEY_SAMPLERATE/BITS_PER_SAMPLE: returns null on <31
    private fun extractTagsFromRetriever(
        retriever: MediaMetadataRetriever,
        filePath: String,
        fileSize: Long,
        lastModified: Long
    ): AudioTags {
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
        val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val bitRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        val numChannels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
        val sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
        val bitsPerSample = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)

        // Parse track number (handles "3/12" format)
        val trackInfo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        val (trackNumber, trackTotal) = parseTrackNumber(trackInfo)
        val discInfo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
        val (discNumber, discTotal) = parseTrackNumber(discInfo)

        // Determine codec
        val codec = detectCodec(mimeType, filePath)

        // Extract and cache artwork
        val artworkBytes = retriever.embeddedPicture
        val hasArt = artworkBytes != null
        val artworkCacheKey = if (hasArt && artworkBytes != null) {
            cacheArtwork(artworkBytes, filePath)
        } else null

        return AudioTags(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            year = parseYear(yearStr),
            trackNumber = trackNumber,
            trackTotal = trackTotal,
            discNumber = discNumber,
            discTotal = discTotal,
            composer = composer,
            codec = codec,
            sampleRate = sampleRateStr?.toIntOrNull() ?: 44100,
            bitDepth = bitsPerSample?.toIntOrNull(),
            bitRate = (bitRateStr?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() },
            channels = numChannels?.toIntOrNull() ?: 2,
            durationSeconds = (durationMs / 1000).toInt(),
            hasEmbeddedArt = hasArt,
            artworkCacheKey = artworkCacheKey,
            filePath = filePath,
            fileSizeBytes = fileSize,
            lastModified = lastModified
        )
    }

    private fun parseTrackNumber(raw: String?): Pair<Int?, Int?> {
        if (raw == null) return null to null
        return if (raw.contains('/')) {
            val parts = raw.split('/')
            parts[0].trim().toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
        } else {
            raw.trim().toIntOrNull() to null
        }
    }

    private fun parseYear(raw: String?): Int? {
        if (raw == null) return null
        // Handle full date strings like "2023-01-15"
        return raw.take(4).toIntOrNull()
    }

    private fun detectCodec(mimeType: String?, filePath: String): AudioCodec {
        return when {
            mimeType?.contains("flac") == true -> AudioCodec.FLAC
            mimeType?.contains("mpeg") == true -> AudioCodec.MP3
            mimeType?.contains("mp4a") == true || mimeType?.contains("aac") == true ||
                mimeType?.contains("mp4") == true -> AudioCodec.AAC
            mimeType?.contains("ogg") == true || mimeType?.contains("vorbis") == true -> AudioCodec.OGG_VORBIS
            mimeType?.contains("opus") == true -> AudioCodec.OPUS
            mimeType?.contains("wav") == true || mimeType?.contains("wave") == true -> AudioCodec.WAV
            mimeType?.contains("aiff") == true -> AudioCodec.AIFF
            mimeType?.contains("x-ms-wma") == true -> AudioCodec.WMA
            else -> detectCodecFromExtension(filePath)
        }
    }

    private fun detectCodecFromExtension(filePath: String): AudioCodec {
        return when (filePath.substringAfterLast('.').lowercase()) {
            "flac" -> AudioCodec.FLAC
            "mp3" -> AudioCodec.MP3
            "m4a", "aac" -> AudioCodec.AAC
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "wav" -> AudioCodec.WAV
            "aif", "aiff" -> AudioCodec.AIFF
            "ape" -> AudioCodec.APE
            "wma" -> AudioCodec.WMA
            else -> AudioCodec.UNKNOWN
        }
    }

    private fun cacheArtwork(artworkBytes: ByteArray, filePath: String): String {
        val hash = MessageDigest.getInstance("MD5")
            .digest(filePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cacheFile = File(artworkCacheDir, "$hash.jpg")

        if (!cacheFile.exists()) {
            try {
                FileOutputStream(cacheFile).use { it.write(artworkBytes) }
            } catch (_: Exception) {
                return filePath // fallback
            }
        }

        return cacheFile.absolutePath
    }
}
