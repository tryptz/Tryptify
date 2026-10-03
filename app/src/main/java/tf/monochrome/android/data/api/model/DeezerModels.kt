package tf.monochrome.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The instance's /api/deezer/* layer (trypt-hifi lib/deezer.tsx) reshapes the
// public Deezer API into the same Qobuz envelopes, so search and album detail
// decode with QobuzSearchEnvelope / QobuzAlbumDetailEnvelope unchanged. Only
// the artist payload differs enough to need its own shape, below.

// GET /api/deezer/get-artist?artist_id=<n> -> { success, data: { artist } }.
//
// Unlike the Qobuz artist response:
//   - id is a string ("27"), not a number
//   - top_tracks are flat track items (the search/album track shape), not the
//     rights/audio_info-wrapped QobuzArtistTopTrack
//   - releases is an OBJECT keyed by bucket ("album", "live", "compilation",
//     "epSingle"), each { has_more, items: [album] }, not a list of groups.
//     Decoding it as the Qobuz list fails the whole response.
//   - there is no portrait hash; the artist's own pictures ride on each
//     release's `artist.image`, because the instance fills the release artist
//     from the full artist record.
@Serializable
data class DeezerArtistEnvelope(
    val success: Boolean = false,
    val data: DeezerArtistData? = null,
)

@Serializable
data class DeezerArtistData(
    val artist: DeezerArtistDetail? = null,
)

@Serializable
data class DeezerArtistDetail(
    val id: String? = null,
    val name: QobuzDisplayName? = null,
    @SerialName("top_tracks") val topTracks: List<QobuzTrackItem> = emptyList(),
    val releases: Map<String, DeezerReleaseBucket> = emptyMap(),
)

@Serializable
data class DeezerReleaseBucket(
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<QobuzAlbumItem> = emptyList(),
)

// GET /api/deezer/preview?track_id=<n> -> { success, data: { url } }, where url
// is a signed /api/file link (Range-capable) to the 30-second MP3.
@Serializable
data class DeezerPreviewEnvelope(
    val success: Boolean = false,
    val data: DeezerPreviewData? = null,
)

@Serializable
data class DeezerPreviewData(
    val url: String? = null,
)
