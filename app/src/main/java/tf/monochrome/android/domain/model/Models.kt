package tf.monochrome.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val artist: Artist? = null,
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val popularity: Int? = null,
    val type: String = "track",
    val isUnavailable: Boolean? = null,
    val streamStartDate: String? = null
) {
    val displayArtist: String
        get() = artist?.name ?: artists.joinToString(", ") { it.name }

    val formattedDuration: String
        get() {
            val minutes = duration / 60
            val seconds = duration % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val coverUrl: String?
        get() = album?.coverUrl
}

@Serializable
data class Album(
    val id: Long,
    val title: String,
    val artist: Artist? = null,
    val artists: List<Artist> = emptyList(),
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    val explicit: Boolean = false,
    val type: String? = null, // ALBUM, EP, SINGLE
    val duration: Int? = null
) {
    val coverUrl: String?
        get() = cover?.let { buildCoverUrl(it, 640) }

    val thumbnailUrl: String?
        get() = cover?.let { buildCoverUrl(it, 320) }

    val releaseYear: String?
        get() = releaseDate?.take(4)

    val displayArtist: String
        get() = artist?.name ?: artists.joinToString(", ") { it.name }
}

@Serializable
data class Artist(
    val id: Long,
    val name: String,
    val picture: String? = null,
    val artistTypes: List<String>? = null
) {
    val pictureUrl: String?
        get() = picture?.let { buildCoverUrl(it, 480) }
}

@Serializable
data class Playlist(
    val uuid: String,
    val title: String,
    val description: String? = null,
    val numberOfTracks: Int? = null,
    val duration: Int? = null,
    val cover: String? = null,
    val creator: PlaylistCreator? = null,
    val tracks: List<Track> = emptyList()
) {
    val coverUrl: String?
        get() = cover?.let { buildCoverUrl(it, 640) }
}

@Serializable
data class PlaylistCreator(
    val id: Long? = null,
    val name: String? = null
)

data class AlbumDetail(
    val album: Album,
    val tracks: List<Track>
)

data class ArtistDetail(
    val artist: Artist,
    val topTracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val eps: List<Album> = emptyList(),
    val singles: List<Album> = emptyList(),
    val unreleasedTracks: List<Track> = emptyList(),
    val similarArtists: List<Artist> = emptyList()
)

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList()
)

data class TrackStream(
    val track: Track,
    val streamUrl: String,
    val isDash: Boolean = false,
    val replayGain: ReplayGainValues? = null
)

data class ReplayGainValues(
    val trackReplayGain: Double? = null,
    val trackPeakAmplitude: Double? = null,
    val albumReplayGain: Double? = null,
    val albumPeakAmplitude: Double? = null
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val isSynced: Boolean = false
)

@Serializable
data class VisualizerTag(
    val id: String,
    val label: String = id
)

@Serializable
data class VisualizerPreset(
    val id: String,
    val displayName: String,
    val filePath: String,
    val tags: List<VisualizerTag> = emptyList(),
    val intensity: Int = 50
)

enum class VisualizerEnginePhase {
    FALLBACK,
    INSTALLING,
    READY,
    ACTIVE,
    ERROR
}

data class VisualizerEngineStatus(
    val phase: VisualizerEnginePhase = VisualizerEnginePhase.FALLBACK,
    val nativeLibraryLoaded: Boolean = false,
    val assetVersion: String = "v1",
    val message: String = "Fallback visualizer active.",
    val assetRoot: String? = null
) {
    val isNativeReady: Boolean
        get() = nativeLibraryLoaded && (
            phase == VisualizerEnginePhase.READY || phase == VisualizerEnginePhase.ACTIVE
        )

    val badge: String
        get() = when (phase) {
            VisualizerEnginePhase.FALLBACK -> "fallback"
            VisualizerEnginePhase.INSTALLING -> "installing"
            VisualizerEnginePhase.READY -> "projectM ready"
            VisualizerEnginePhase.ACTIVE -> "projectM live"
            VisualizerEnginePhase.ERROR -> "projectM error"
        }
}

@Serializable
enum class AudioQuality(val apiValue: String, val displayName: String) {
    LOW("LOW", "Low (96 kbps)"),
    HIGH("HIGH", "High (320 kbps)"),
    LOSSLESS("LOSSLESS", "Lossless (FLAC)"),
    HI_RES("HI_RES_LOSSLESS", "Hi-Res (24-bit FLAC)")
}

enum class RepeatMode {
    OFF, ONE, ALL
}

enum class ReplayGainMode {
    OFF, TRACK, ALBUM
}

enum class NowPlayingViewMode(val displayName: String) {
    COVER_ART("Cover Art"),
    LYRICS("Lyrics"),
    QUEUE("Queue"),
    VISUALIZER("Visualizer")
}

fun buildCoverUrl(coverId: String, size: Int): String {
    if (coverId.contains("://")) return coverId
    // Local artwork path. File.toURI() emits `file:/path` (single slash) which
    // Coil 3 interprets as a malformed URI; library lists pass the raw path
    // and Coil's PathMapper resolves it correctly. Match that here.
    if (coverId.startsWith("/")) return coverId
    val formatted = coverId.replace("-", "/")
    return "https://resources.tidal.com/images/$formatted/${size}x${size}.jpg"
}

// ========== Unified Three-Source Models ==========

@Serializable
enum class SourceType { API, COLLECTION, LOCAL, QOBUZ }

@Serializable
enum class AudioCodec(val displayName: String) {
    FLAC("FLAC"),
    MP3("MP3"),
    AAC("AAC"),
    ALAC("ALAC"),
    OGG_VORBIS("OGG"),
    OPUS("Opus"),
    WAV("WAV"),
    AIFF("AIFF"),
    APE("APE"),
    WMA("WMA"),
    UNKNOWN("Unknown")
}

@Serializable
sealed class PlaybackSource {

    /** Stream from Hi-Fi API - requires network */
    @Serializable
    @SerialName("HiFiApi")
    data class HiFiApi(
        val tidalId: Long,
        val preferredQuality: AudioQuality = AudioQuality.LOSSLESS
    ) : PlaybackSource()

    /** Encrypted direct link from collection manifest */
    @Serializable
    @SerialName("CollectionDirect")
    data class CollectionDirect(
        val collectionId: String,
        val directLinks: List<CollectionDirectLink>,
        val encryptionKey: String,
        val fileHash: String,
        val preferredQuality: AudioQuality = AudioQuality.LOSSLESS
    ) : PlaybackSource()

    /** Local file on device - zero network, fastest path */
    @Serializable
    @SerialName("LocalFile")
    data class LocalFile(
        val filePath: String,
        val codec: AudioCodec,
        val sampleRate: Int,
        val bitDepth: Int? = null
    ) : PlaybackSource()

    /**
     * Qobuz (trypt-hifi) source — fetches the audio file via /api/download-music
     * on first play, parks it in the cache directory, and plays subsequent
     * times from local disk. The HMAC-signed file URL the backend returns
     * isn't suitable for long-running streams (signature is time-bounded), so
     * pre-fetching the whole file is more reliable than progressive streaming.
     */
    @Serializable
    @SerialName("QobuzCached")
    data class QobuzCached(
        val qobuzId: Long,
        val preferredQuality: AudioQuality = AudioQuality.LOSSLESS,
    ) : PlaybackSource()
}

@Serializable
data class CollectionDirectLink(
    val url: String,
    val quality: String
)

@Serializable
data class TrackLyrics(
    val basic: String? = null,
    val lrc: String? = null,
    val ttml: String? = null
)

@Serializable
data class UnifiedTrack(
    val id: String,
    val title: String,
    val durationSeconds: Int,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val explicit: Boolean = false,

    // Artist info
    val artistName: String,
    val artistNames: List<String> = emptyList(),
    val albumArtistName: String? = null,

    // Album info
    val albumTitle: String? = null,
    val albumId: String? = null,

    // Artwork
    val artworkUri: String? = null,

    // Audio quality
    val codec: AudioCodec? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitRate: Int? = null,
    val qualityTags: List<String>? = null,

    // Replay gain
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,
    val r128TrackGain: Int? = null,
    val r128AlbumGain: Int? = null,

    // Lyrics
    val lyrics: TrackLyrics? = null,

    // Identifiers for cross-source matching
    val isrc: String? = null,
    val musicBrainzTrackId: String? = null,

    // Source
    val source: PlaybackSource,
    val sourceType: SourceType
) {
    val displayArtist: String
        get() = artistName

    val formattedDuration: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val qualityBadge: String?
        get() = when {
            codec == AudioCodec.FLAC && (bitDepth ?: 16) >= 24 ->
                "FLAC ${bitDepth}/${(sampleRate ?: 44100) / 1000}"
            codec == AudioCodec.FLAC -> "FLAC"
            codec == AudioCodec.ALAC && (bitDepth ?: 16) >= 24 ->
                "ALAC ${bitDepth}/${(sampleRate ?: 44100) / 1000}"
            codec == AudioCodec.ALAC -> "ALAC"
            codec == AudioCodec.MP3 -> "MP3 ${bitRate ?: 320}"
            codec == AudioCodec.AAC -> "AAC ${bitRate ?: 256}"
            codec == AudioCodec.OPUS -> "Opus ${bitRate ?: 128}"
            codec == AudioCodec.OGG_VORBIS -> "OGG ${bitRate ?: 320}"
            qualityTags?.contains("HI_RES_LOSSLESS") == true -> "Hi-Res"
            qualityTags?.contains("LOSSLESS") == true -> "Lossless"
            qualityTags?.contains("HIGH") == true -> "High"
            else -> null
        }

    /** Convert to legacy Track model for backward compatibility */
    fun toLegacyTrack(): Track {
        val tidalId = when (val s = source) {
            is PlaybackSource.HiFiApi -> s.tidalId
            is PlaybackSource.QobuzCached -> s.qobuzId
            else -> id.hashCode().toLong()
        }
        // Fall back to the audio file path for local sources when the scan
        // didn't manage to cache an artwork JPG. AudioFileCoverFetcher
        // (Coil) extracts the embedded picture on demand from the file
        // itself, so the player shows the cover even on cache misses.
        val coverFallback = artworkUri ?: when (val s = source) {
            is PlaybackSource.LocalFile -> s.filePath
            else -> null
        }
        return Track(
            id = tidalId,
            title = title,
            duration = durationSeconds,
            artist = Artist(id = artistName.hashCode().toLong(), name = artistName),
            // Build an Album whenever we have a title OR a cover/fallback.
            // Sideloaded downloads have null albumTitle but a content:// cover
            // URI; without this guard the player drops the artwork entirely.
            album = if (albumTitle != null || coverFallback != null) {
                Album(
                    id = albumId?.hashCode()?.toLong() ?: tidalId,
                    title = albumTitle.orEmpty(),
                    cover = coverFallback
                )
            } else null,
            audioQuality = codec?.displayName,
            explicit = explicit,
            trackNumber = trackNumber,
            volumeNumber = discNumber
        )
    }
}

data class UnifiedAlbum(
    val id: String,
    val title: String,
    val artistName: String,
    val year: Int? = null,
    val trackCount: Int = 0,
    val totalDuration: Int = 0,
    val artworkUri: String? = null,
    val genres: List<String> = emptyList(),
    val sourceType: SourceType,
    val qualitySummary: String? = null
)

data class UnifiedArtist(
    val id: String,
    val name: String,
    val artworkUri: String? = null,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
    val bio: String? = null,
    val genres: List<String> = emptyList(),
    val sourceType: SourceType
)

// ========== EQ / AutoEQ Models ==========

enum class FilterType {
    PEAKING, LOWSHELF, HIGHSHELF
}

@Serializable
data class FrequencyPoint(
    val freq: Float,
    val gain: Float
)

@Serializable
data class EqBand(
    val id: Int,
    val type: FilterType = FilterType.PEAKING,
    val freq: Float,
    val gain: Float,
    val q: Float = 1.0f,
    val enabled: Boolean = true
)

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val bands: List<EqBand> = emptyList(),
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Set by EqRepository.toDomain when the stored bandsJson fails to decode.
    // Loading a corrupted preset would silently flatten the EQ; callers should
    // refuse to load it and surface the state instead.
    val isCorrupted: Boolean = false
)

@Serializable
data class EqTarget(
    val id: String,
    val label: String,
    val data: List<FrequencyPoint> = emptyList(),
    val filename: String = ""
)

@Serializable
data class Headphone(
    val id: String,
    val name: String,
    val type: String = "over-ear", // "over-ear", "in-ear", "earbud"
    val data: List<FrequencyPoint> = emptyList(),
    val measurements: List<AutoEqMeasurement> = emptyList()
)

data class AutoEqEntry(
    val name: String,
    val type: String,
    val measurements: List<AutoEqMeasurement> = emptyList()
)

@Serializable
data class AutoEqMeasurement(
    val source: String,
    val target: String,
    val path: String,
    val fileName: String,
    val rig: MeasurementRig = MeasurementRig.UNKNOWN,
    // Origin host for squig.link sources (e.g. "https://precog.squig.link");
    // empty for AutoEq sources where path is the GitHub repo subpath.
    val host: String = ""
)

/**
 * Acoustic measurement rig used to capture a headphone's frequency response.
 * Bucket label is what the UI shows in its filter chip; ordinal controls sort
 * order so industry-grade rigs (B&K 5128, GRAS) rise above community clones.
 */
@Serializable
enum class MeasurementRig(val label: String) {
    BK_5128("B&K 5128"),
    GRAS_43AG_7("GRAS 43AG-7"),
    GRAS_43AC_10("GRAS 43AC-10"),
    GRAS_45CA_10("GRAS 45CA-10"),
    IEC_711_CLONE("IEC 711 clone"),
    MINIDSP_EARS("MiniDSP EARS"),
    UNKNOWN("Unknown")
}
