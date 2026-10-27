package tf.monochrome.android.data.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parts of the Subsonic API's JSON (`f=json`) this app reads.
 *
 * Only the ID3 browse (getArtists, getArtist, getAlbum, getAlbumList2,
 * search3), which every server since API 1.8.0 has. Every field is optional
 * with a default: servers differ in what they fill in, and the app's Json
 * ignores the rest (OpenSubsonic's extensions, Navidrome's own extras).
 */
@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String = "",
    val version: String? = null,
    /** OpenSubsonic: the server's name for itself, such as "navidrome". */
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    val albumList2: SubsonicAlbumList? = null,
    val album: SubsonicAlbum? = null,
    val artists: SubsonicArtistIndex? = null,
    val artist: SubsonicArtist? = null,
    val searchResult3: SubsonicSearchResult? = null,
    val randomSongs: SubsonicSongList? = null,
    val playlists: SubsonicPlaylists? = null,
    val playlist: SubsonicPlaylist? = null,
    val starred2: SubsonicStarred? = null,
    /** OpenSubsonic getLyricsBySongId. */
    val lyricsList: SubsonicLyricsList? = null,
    /** Classic getLyrics. */
    val lyrics: SubsonicPlainLyrics? = null,
)

@Serializable
data class SubsonicPlaylists(val playlist: List<SubsonicPlaylist> = emptyList())

/** A playlist as getPlaylists lists it, and with its songs ([entry]) as getPlaylist returns it. */
@Serializable
data class SubsonicPlaylist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicStarred(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)

@Serializable
data class SubsonicLyricsList(val structuredLyrics: List<SubsonicStructuredLyrics> = emptyList())

/**
 * One language's lyrics. [offset] is in milliseconds, positive meaning the
 * lines show sooner, as LRC's `[offset:]`.
 */
@Serializable
data class SubsonicStructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<SubsonicLyricLine> = emptyList(),
)

@Serializable
data class SubsonicLyricLine(val start: Long? = null, val value: String = "")

@Serializable
data class SubsonicPlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)

@Serializable
data class SubsonicError(val code: Int? = null, val message: String? = null)

@Serializable
data class SubsonicAlbumList(val album: List<SubsonicAlbum> = emptyList())

@Serializable
data class SubsonicSongList(val song: List<SubsonicSong> = emptyList())

@Serializable
data class SubsonicArtistIndex(val index: List<SubsonicIndexEntry> = emptyList())

@Serializable
data class SubsonicIndexEntry(val name: String? = null, val artist: List<SubsonicArtist> = emptyList())

@Serializable
data class SubsonicArtist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<SubsonicAlbum> = emptyList(),
)

@Serializable
data class SubsonicAlbum(
    val id: String,
    // getAlbum and getAlbumList2 say `name`; a few servers say `title` as well or instead.
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<SubsonicSong> = emptyList(),
) {
    val displayName: String get() = name ?: title ?: ""
}

@Serializable
data class SubsonicSong(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    // OpenSubsonic extensions; absent on plain Subsonic servers.
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val musicBrainzId: String? = null,
    /** True for a podcast or video entry, which this app does not play. */
    val isVideo: Boolean = false,
)

@Serializable
data class SubsonicSearchResult(
    val artist: List<SubsonicArtist> = emptyList(),
    val album: List<SubsonicAlbum> = emptyList(),
    val song: List<SubsonicSong> = emptyList(),
)
