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

    suspend fun readTags(
        filePath: String,
        folderArtCache: MutableMap<String, String?>? = null
    ): AudioTags {
        val file = File(filePath)
        if (!file.exists()) return AudioTags(filePath = filePath)

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            extractTags(retriever, file, folderArtCache)
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

    private fun extractTags(
        retriever: MediaMetadataRetriever,
        file: File,
        folderArtCache: MutableMap<String, String?>? = null
    ): AudioTags {
        return extractTagsFromRetriever(
            retriever,
            file.absolutePath,
            file.length(),
            file.lastModified(),
            folderArtCache
        )
    }

    @SuppressLint("InlinedApi") // METADATA_KEY_SAMPLERATE/BITS_PER_SAMPLE: returns null on <31
    private fun extractTagsFromRetriever(
        retriever: MediaMetadataRetriever,
        filePath: String,
        fileSize: Long,
        lastModified: Long,
        folderArtCache: MutableMap<String, String?>? = null
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

        // Extract and cache artwork. For MP4/M4A this reads the `covr` atom;
        // for FLAC/Vorbis/Opus this reads the METADATA_BLOCK_PICTURE/coverart;
        // for ID3v2 this reads APIC. If nothing is embedded, fall back to a
        // sidecar image in the track's folder (cover.jpg, folder.jpg, etc.).
        val artworkBytes = retriever.embeddedPicture
        val hasArt = artworkBytes != null
        val artworkCacheKey = when {
            hasArt && artworkBytes != null -> cacheArtwork(artworkBytes, filePath)
            // Per-track sidecar: an image next to the audio file with the
            // same stem (e.g. "song.flac" + "song.jpg"). yt-dlp, Bandcamp,
            // and rip workflows all produce this convention. Checked before
            // folder-level cover.* / albumart.* fallback.
            else -> findPerTrackSidecar(filePath)
                ?: findFolderCoverArt(filePath, folderArtCache)
        }
        val hasArtOrSidecar = artworkCacheKey != null

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
            hasEmbeddedArt = hasArtOrSidecar,
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
        // Opus and Vorbis share the Ogg container, so MediaMetadataRetriever often
        // reports both as "audio/ogg". ALAC and AAC share the MP4 container, so
        // both report as "audio/mp4" on some Android versions. Check explicit
        // codec MIMEs first, then fall back to the file extension.
        return when {
            mimeType?.contains("flac") == true -> AudioCodec.FLAC
            mimeType?.contains("mpeg") == true -> AudioCodec.MP3
            mimeType?.contains("alac") == true -> AudioCodec.ALAC
            mimeType?.contains("mp4a") == true || mimeType?.contains("aac") == true ||
                mimeType?.contains("mp4") == true -> {
                val fromExt = detectCodecFromExtension(filePath)
                if (fromExt == AudioCodec.ALAC) AudioCodec.ALAC else AudioCodec.AAC
            }
            mimeType?.contains("opus") == true -> AudioCodec.OPUS
            mimeType?.contains("vorbis") == true -> AudioCodec.OGG_VORBIS
            mimeType?.contains("ogg") == true -> {
                val fromExt = detectCodecFromExtension(filePath)
                if (fromExt == AudioCodec.OPUS) AudioCodec.OPUS else AudioCodec.OGG_VORBIS
            }
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
            "alac" -> AudioCodec.ALAC
            "m4a", "aac", "mp4" -> AudioCodec.AAC
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "wav" -> AudioCodec.WAV
            "aif", "aiff" -> AudioCodec.AIFF
            "ape" -> AudioCodec.APE
            "wma" -> AudioCodec.WMA
            else -> AudioCodec.UNKNOWN
        }
    }

    /**
     * Look for a sidecar cover art file in the track's folder. Matches the common
     * naming conventions (cover.*, folder.*, album.*, front.*, AlbumArt*.jpg) case-
     * insensitively, prefers larger images when multiple candidates exist, and
     * returns the file's absolute path (Coil will load it directly). Returns null
     * when `filePath` isn't a real filesystem path (e.g. a content:// URI) or the
     * folder has no recognisable image.
     *
     * Pass a [cache] keyed by parent directory to avoid re-listing the same folder
     * for every track in an album.
     */
    private fun findFolderCoverArt(
        filePath: String,
        cache: MutableMap<String, String?>? = null
    ): String? {
        val parent = File(filePath).parentFile ?: return null
        if (!parent.isDirectory) return null
        val parentKey = parent.absolutePath
        cache?.let { if (it.containsKey(parentKey)) return it[parentKey] }
        val resolved = scanFolderCoverArt(parent)
        cache?.put(parentKey, resolved)
        return resolved
    }

    /**
     * Match an image file in the same directory whose stem equals the audio
     * file's stem. Common naming convention from yt-dlp, Bandcamp, and
     * various TIDAL/Qobuz rip workflows.
     *
     * Uses direct File.exists() rather than listFiles() — under Android's
     * scoped storage (API 33+) listFiles() on /storage/emulated/0/...
     * returns null even with READ_MEDIA_AUDIO/READ_MEDIA_IMAGES granted,
     * but exists()/length() on a known indexed media path works. We pay
     * up to 8 stat() calls per art-less track (4 extensions × 2 cases) to
     * cover this.
     */
    private fun findPerTrackSidecar(filePath: String): String? {
        val file = File(filePath)
        val parent = file.parentFile ?: return null
        val stem = file.nameWithoutExtension
        for (ext in IMAGE_EXTENSIONS) {
            val lower = File(parent, "$stem.$ext")
            if (lower.exists()) return lower.absolutePath
            val upper = File(parent, "$stem.${ext.uppercase()}")
            if (upper.exists()) return upper.absolutePath
        }
        return null
    }

    private fun scanFolderCoverArt(parent: File): String? {
        val files = parent.listFiles() ?: return null
        val candidates = files.filter { f ->
            if (!f.isFile) return@filter false
            val name = f.name.lowercase()
            val ext = name.substringAfterLast('.', "")
            if (ext !in IMAGE_EXTENSIONS) return@filter false
            val stem = name.substringBeforeLast('.')
            stem in COVER_STEMS || COVER_STEM_PREFIXES.any { stem.startsWith(it) }
        }
        if (candidates.isEmpty()) return null
        // Prefer exact stem match over prefix match, then the largest file
        // (Windows Media Player writes both AlbumArt.jpg and AlbumArtSmall.jpg).
        return candidates
            .sortedWith(
                compareByDescending<File> { f ->
                    val stem = f.name.lowercase().substringBeforeLast('.')
                    if (stem in COVER_STEMS) 1 else 0
                }.thenByDescending { it.length() }
            )
            .first()
            .absolutePath
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_STEMS = setOf(
            "cover", "folder", "album", "albumart", "front", "artwork"
        )
        private val COVER_STEM_PREFIXES = setOf(
            "albumart" // AlbumArt_{GUID}_Large.jpg (WMP)
        )
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
