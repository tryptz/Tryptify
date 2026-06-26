package tf.monochrome.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response shape for the trypt-hifi (Qobuz) instance, captured from a real
// /api/get-music?q=... call. Top-level envelope is { success, data: { query,
// albums?, tracks?, artists? } } where each section paginates its own items.
// Only the section relevant to the user's active tab is populated; the others
// are absent. We accept all three on the same envelope so a single parser
// works regardless of which tab was searched.
@Serializable
data class QobuzSearchEnvelope(
    val success: Boolean = false,
    val data: QobuzSearchData? = null,
)

@Serializable
data class QobuzSearchData(
    val query: String? = null,
    val albums: QobuzPaginated<QobuzAlbumItem>? = null,
    val tracks: QobuzPaginated<QobuzTrackItem>? = null,
    val artists: QobuzPaginated<QobuzArtistItem>? = null,
)

@Serializable
data class QobuzPaginated<T>(
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val items: List<T> = emptyList(),
)

@Serializable
data class QobuzAlbumItem(
    val id: String? = null,                // alphanumeric slug (e.g. "h8lxgr12m97wc")
    @SerialName("qobuz_id") val qobuzId: Long? = null,  // numeric id used by /api/download-music
    val title: String = "",
    val version: String? = null,
    val artist: QobuzArtistRef? = null,
    val artists: List<QobuzArtistRef> = emptyList(),
    val image: QobuzImage? = null,
    val duration: Int? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("release_date_original") val releaseDateOriginal: String? = null,
    @SerialName("parental_warning") val parentalWarning: Boolean = false,
    val downloadable: Boolean = true,
    val streamable: Boolean = true,
    val hires: Boolean = false,
    @SerialName("hires_streamable") val hiresStreamable: Boolean = false,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Double? = null,
    val genre: QobuzGenre? = null,
    val label: QobuzLabel? = null,
    val upc: String? = null,
    val url: String? = null,
    // Album-detail responses (/api/get-album?album_id=) embed the track list
    // here. Search responses leave it null — ignoreUnknownKeys drops the
    // detail-only fields like description, awards, track_ids, etc.
    val tracks: QobuzPaginated<QobuzTrackItem>? = null,
    val description: String? = null,
)

// Detail envelope — wraps the same QobuzAlbumItem shape (now with `tracks`
// populated) under the standard {success, data: …} response.
@Serializable
data class QobuzAlbumDetailEnvelope(
    val success: Boolean = false,
    val data: QobuzAlbumItem? = null,
)

// ---------------------------------------------------------------------------
// Artist-detail response shape (/api/get-artist?artist_id=<numeric>)
//
// Notably different from search/album responses:
//   - name is a nested object {display: "..."} instead of a plain string
//   - image is {portrait: {hash, format}} — URL has to be constructed:
//     https://static.qobuz.com/images/artists/covers/<size>/<hash>.<format>
//   - top_tracks is a flat array, NOT a paginated {items: [...]} structure
//   - track entries inside top_tracks use rights/physical_support/audio_info
//     wrappers instead of flat boolean fields
//   - similar_artists has {has_more, items: [...]} structure
@Serializable
data class QobuzArtistDetailEnvelope(
    val success: Boolean = false,
    val data: QobuzArtistDetailData? = null,
)

@Serializable
data class QobuzArtistDetailData(
    val artist: QobuzArtistDetail? = null,
)

@Serializable
data class QobuzArtistDetail(
    val id: Long? = null,
    val name: QobuzDisplayName? = null,
    @SerialName("artist_category") val artistCategory: String? = null,
    val biography: QobuzBiography? = null,
    val images: QobuzArtistImages? = null,
    @SerialName("similar_artists") val similarArtists: QobuzSimilarArtists? = null,
    @SerialName("top_tracks") val topTracks: List<QobuzArtistTopTrack> = emptyList(),
    // The artist's discography, grouped by release type ("album", "epSingle",
    // "live", "compilation", …). Each group's items share the QobuzAlbumItem
    // shape, so they map to domain Albums and become browsable on the artist
    // screen (which is how the full track catalogue is reached for Qobuz).
    val releases: List<QobuzReleaseGroup> = emptyList(),
)

@Serializable
data class QobuzReleaseGroup(
    val type: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<QobuzRelease> = emptyList(),
)

// Release items in the artist payload look like album items but with some
// fields in a DIFFERENT shape — notably `artist.name` is an object ({display})
// here vs a plain string on QobuzAlbumItem.artist. Reusing QobuzAlbumItem makes
// the strict decoder throw on that mismatch and fail the whole artist response,
// so we decode only the fields we need, with safe types.
@Serializable
data class QobuzRelease(
    val id: String? = null,                              // alphanumeric slug
    @SerialName("qobuz_id") val qobuzId: Long? = null,
    val title: String = "",
    val version: String? = null,
    val image: QobuzImage? = null,
    val duration: Int? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("release_type") val releaseType: String? = null,
    @SerialName("parental_warning") val parentalWarning: Boolean = false,
)

@Serializable
data class QobuzDisplayName(
    val display: String? = null,
)

@Serializable
data class QobuzBiography(
    val content: String? = null,
    val source: String? = null,
    val language: String? = null,
)

@Serializable
data class QobuzArtistImages(
    val portrait: QobuzImageHash? = null,
)

@Serializable
data class QobuzImageHash(
    val hash: String? = null,
    val format: String? = null,
)

@Serializable
data class QobuzSimilarArtists(
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<QobuzSimilarArtist> = emptyList(),
)

@Serializable
data class QobuzSimilarArtist(
    val id: Long? = null,
    val name: QobuzDisplayName? = null,
    val images: QobuzArtistImages? = null,
)

@Serializable
data class QobuzArtistTopTrack(
    val id: Long? = null,
    val isrc: String? = null,
    val title: String = "",
    val version: String? = null,
    val duration: Int? = null,
    @SerialName("parental_warning") val parentalWarning: Boolean = false,
    val composer: QobuzNamedRef? = null,
    val artist: QobuzNamedRef? = null,
    @SerialName("audio_info") val audioInfo: QobuzAudioSpec? = null,
    val rights: QobuzTrackRights? = null,
    @SerialName("physical_support") val physicalSupport: QobuzPhysicalSupport? = null,
    val album: QobuzAlbumItem? = null,
)

@Serializable
data class QobuzNamedRef(
    val id: Long? = null,
    val name: QobuzDisplayName? = null,
)

@Serializable
data class QobuzAudioSpec(
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int? = null,
    @SerialName("maximum_channel_count") val maximumChannelCount: Int? = null,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Double? = null,
)

@Serializable
data class QobuzTrackRights(
    val streamable: Boolean = false,
    @SerialName("hires_streamable") val hiresStreamable: Boolean = false,
    @SerialName("hires_purchasable") val hiresPurchasable: Boolean = false,
    val purchasable: Boolean = false,
    val downloadable: Boolean = false,
    val previewable: Boolean = false,
    val sampleable: Boolean = false,
)

@Serializable
data class QobuzPhysicalSupport(
    @SerialName("media_number") val mediaNumber: Int? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
)

@Serializable
data class QobuzTrackItem(
    val id: Long? = null,
    val title: String = "",
    val version: String? = null,
    val duration: Int? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("media_number") val mediaNumber: Int? = null,
    // Real response uses "performer" as a single object {id, name} for the
    // primary credited artist on a track. There's also "performers" (string)
    // and "composer" (single object) which we keep optional.
    val performer: QobuzPerson? = null,
    val composer: QobuzPerson? = null,
    val performers: String? = null,
    val album: QobuzAlbumItem? = null,
    @SerialName("audio_info") val audioInfo: QobuzTrackAudioInfo? = null,
    @SerialName("parental_warning") val parentalWarning: Boolean = false,
    val downloadable: Boolean = true,
    val streamable: Boolean = true,
    val hires: Boolean = false,
    @SerialName("hires_streamable") val hiresStreamable: Boolean = false,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Double? = null,
    val isrc: String? = null,
)

// Lightweight credit (track-level performer/composer). Response uses just
// {id, name} here, distinct from the richer QobuzArtistRef on albums.
@Serializable
data class QobuzPerson(
    val id: Long? = null,
    val name: String = "",
)

@Serializable
data class QobuzTrackAudioInfo(
    @SerialName("replaygain_track_gain") val replayGainTrackGain: Float? = null,
    @SerialName("replaygain_track_peak") val replayGainTrackPeak: Float? = null,
    @SerialName("replaygain_album_gain") val replayGainAlbumGain: Float? = null,
    @SerialName("replaygain_album_peak") val replayGainAlbumPeak: Float? = null,
)

@Serializable
data class QobuzArtistItem(
    val id: Long? = null,
    val name: String = "",
    val slug: String? = null,
    val image: QobuzImage? = null,
    val picture: String? = null,
    @SerialName("albums_count") val albumsCount: Int? = null,
)

@Serializable
data class QobuzArtistRef(
    val id: Long? = null,
    val name: String = "",
    val slug: String? = null,
    val image: QobuzImage? = null,
    val picture: String? = null,
    val roles: List<String> = emptyList(),
    @SerialName("albums_count") val albumsCount: Int? = null,
)

// Album covers: small/thumbnail/large/back. Artist images: small/medium/
// large/extralarge/mega. Both are accepted on the same type because
// ignoreUnknownKeys = true silently drops the missing fields per source.
@Serializable
data class QobuzImage(
    val small: String? = null,
    val thumbnail: String? = null,
    val large: String? = null,
    val back: String? = null,
    val medium: String? = null,
    val extralarge: String? = null,
    val mega: String? = null,
)

@Serializable
data class QobuzGenre(
    val id: Int? = null,
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
data class QobuzLabel(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
)

// Response shape for /api/download-music?track_id=...&quality=...
// Captured response is small (~0.2 KB) and contains a per-request HMAC-signed
// stream URL. Field name hasn't been confirmed yet, so the parser tries
// several common keys; this model covers the one most likely to match.
@Serializable
data class QobuzDownloadEnvelope(
    val success: Boolean = false,
    val data: QobuzDownloadData? = null,
    // Some hifi-api forks emit the URL at the top level instead of under data.
    val url: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
data class QobuzDownloadData(
    val url: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    val format: String? = null,
    val mime_type: String? = null,
    val sampling_rate: Double? = null,
    val bit_depth: Int? = null,
)
