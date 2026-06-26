package tf.monochrome.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SearchResponse(
    val items: List<SearchItem> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val totalNumberOfItems: Int? = null
)

@Serializable
data class SearchItem(
    val id: Long = 0,
    val title: String = "",
    val duration: Int = 0,
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val album: ApiAlbum? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val popularity: Int? = null,
    val type: String? = null,
    val streamStartDate: String? = null,
    // Album fields (when searching albums)
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    // Artist fields (when searching artists)
    val name: String? = null,
    val picture: String? = null,
    val artistTypes: List<String>? = null,
    // Playlist fields
    val uuid: String? = null,
    val description: String? = null,
    val creator: ApiPlaylistCreator? = null,
    val squareImage: String? = null,
    val image: String? = null
)

@Serializable
data class ApiTrack(
    val id: Long = 0,
    val title: String = "",
    val duration: Int = 0,
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val album: ApiAlbum? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val popularity: Int? = null,
    val type: String? = "track",
    val streamStartDate: String? = null,
    @SerialName("isUnavailable")
    val unavailable: Boolean? = null
)

@Serializable
data class ApiAlbum(
    val id: Long = 0,
    val title: String = "",
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    val explicit: Boolean = false,
    val type: String? = null,
    val duration: Int? = null
)

@Serializable
data class ApiArtist(
    val id: Long = 0,
    val name: String = "",
    val picture: String? = null,
    val artistTypes: List<String>? = null
)

@Serializable
data class ApiPlaylistCreator(
    val id: Long? = null,
    val name: String? = null
)

@Serializable
data class AlbumResponse(
    val id: Long = 0,
    val title: String = "",
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    val explicit: Boolean = false,
    val type: String? = null,
    val duration: Int? = null,
    val items: List<AlbumTrackItem>? = null,
    val tracks: AlbumTracksWrapper? = null
)

@Serializable
data class AlbumTracksWrapper(
    val items: List<AlbumTrackItem> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    val totalNumberOfItems: Int? = null
)

@Serializable
data class AlbumTrackItem(
    val id: Long = 0,
    val title: String = "",
    val duration: Int = 0,
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val popularity: Int? = null,
    val item: ApiTrack? = null // Some API versions wrap in item
)

@Serializable
data class ArtistResponse(
    val id: Long = 0,
    val name: String = "",
    val picture: String? = null,
    val artistTypes: List<String>? = null
)

@Serializable
data class ApiAlbumsWrapper(
    val items: List<ApiAlbum> = emptyList()
)

@Serializable
data class ApiTracksWrapper(
    val items: List<ApiTrack> = emptyList()
)

@Serializable
data class ArtistContentResponse(
    val items: List<ArtistContentItem>? = null,
    val albums: ApiAlbumsWrapper? = null,
    val topTracks: ApiTracksWrapper? = null
)

@Serializable
data class ArtistContentItem(
    val id: Long = 0,
    val title: String = "",
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val numberOfTracks: Int? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    val explicit: Boolean = false,
    val type: String? = null
)

@Serializable
data class PlaylistResponse(
    val uuid: String = "",
    val title: String = "",
    val description: String? = null,
    val numberOfTracks: Int? = null,
    val duration: Int? = null,
    val cover: String? = null,
    val squareImage: String? = null,
    val image: String? = null,
    val creator: ApiPlaylistCreator? = null,
    val items: List<PlaylistTrackItem>? = null,
    val tracks: List<PlaylistTrackItem>? = null
)

@Serializable
data class PlaylistTrackItem(
    val item: ApiTrack? = null,
    // Some responses have flat track data
    val id: Long? = null,
    val title: String? = null,
    val duration: Int? = null,
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist>? = null,
    val album: ApiAlbum? = null,
    val trackNumber: Int? = null,
    val audioQuality: String? = null,
    val explicit: Boolean? = null
)

@Serializable
data class TrackStreamResponse(
    val manifest: String? = null,
    val manifestMimeType: String? = null,
    val trackReplayGain: Double? = null,
    val trackPeakAmplitude: Double? = null,
    val albumReplayGain: Double? = null,
    val albumPeakAmplitude: Double? = null
)

@Serializable
data class TrackInfoResponse(
    val id: Long = 0,
    val title: String = "",
    val duration: Int = 0,
    val artist: ApiArtist? = null,
    val artists: List<ApiArtist> = emptyList(),
    val album: ApiAlbum? = null,
    val audioQuality: String? = null,
    val explicit: Boolean = false,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val isrc: String? = null
)

@Serializable
data class ManifestJson(
    val mimeType: String? = null,
    val urls: List<String> = emptyList(),
    val codecs: String? = null
)

@Serializable
data class RecommendationsResponse(
    val items: List<ApiTrack> = emptyList(),
    val limit: Int? = null
)

@Serializable
data class SimilarResponse(
    val items: List<JsonElement> = emptyList()
)

@Serializable
data class LyricsResponse(
    val subtitles: String? = null,
    val lyrics: String? = null,
    val isRightToLeft: Boolean = false
)

@Serializable
data class UptimeInstance(
    val url: String = "",
    val version: String? = null,
    val type: String? = null // "api" or "streaming"
)

@Serializable
data class UptimeResponse(
    val instances: List<UptimeInstance>? = null,
    val api: List<UptimeInstance>? = null,
    val streaming: List<UptimeInstance>? = null
)

// Wrapper for responses that may come as { data: ... } or directly
@Serializable
data class ApiWrapper<T>(
    val data: T? = null,
    val version: String? = null
)
