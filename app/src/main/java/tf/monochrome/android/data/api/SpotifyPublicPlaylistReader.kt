package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tf.monochrome.android.data.import_.CsvTrack
import javax.inject.Inject
import javax.inject.Singleton

/** A public playlist as Spotify's embed shows it, with no account involved. */
data class SpotifyPublicPlaylist(
    val name: String,
    val tracks: List<CsvTrack>,
    /** The playlist's full length when it is longer than what was read; else null. */
    val totalTracks: Int?,
    /** Its cover image, for the import preview. */
    val coverUrl: String? = null,
)

/** The embed could not find the playlist: private, deleted, or a wrong link. */
class SpotifyPlaylistUnavailableException : Exception("Spotify playlist not found")

/**
 * Reads a public Spotify playlist from its link, without signing in.
 *
 * Spotify's Web API serves a playlist's contents to an app like this one only
 * for playlists the signed-in user owns or collaborates on (the Feb 2026
 * restriction), so an arbitrary
 * link could not be imported without a CSV export from a third-party site.
 * The embed player — the page websites show a playlist in — is served to
 * anyone and carries the playlist's name and its tracks (title, artists,
 * length) in its `__NEXT_DATA__` JSON. It lists the first [EMBED_LIMIT] tracks;
 * when it lists exactly that many, the playlist's own page is read for its
 * `music:song_count`, so a longer playlist is reported as partly imported
 * rather than silently cut short. The embed's anonymous token is refused by
 * the Web API (429 QUOTA_EXCEEDED), so there is no reaching past that limit.
 */
@Singleton
class SpotifyPublicPlaylistReader @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun read(playlistId: String): SpotifyPublicPlaylist {
        val embed = httpClient.get("https://open.spotify.com/embed/playlist/$playlistId").bodyAsText()
        val parsed = SpotifyEmbed.parse(embed, json) ?: throw SpotifyPlaylistUnavailableException()
        val total = if (parsed.tracks.size >= SpotifyEmbed.EMBED_LIMIT) {
            runCatching {
                SpotifyEmbed.songCount(httpClient.get("https://open.spotify.com/playlist/$playlistId").bodyAsText())
            }.getOrNull()
        } else {
            null
        }
        return parsed.copy(totalTracks = total?.takeIf { it > parsed.tracks.size })
    }

    /**
     * The playlist id a `spotify.link` short link (Spotify's share sheet makes
     * them) points to, or null. The link answers with a redirect or with a
     * page that names the full URL; either way the id is in what comes back.
     */
    suspend fun resolveShortLink(url: String): String? = runCatching {
        val response = httpClient.get(url)
        SpotifyEmbed.playlistId(response.call.request.url.toString())
            ?: SpotifyEmbed.playlistId(response.bodyAsText())
    }.getOrNull()
}

/** Parsing for the embed page and links: plain functions, so they can be tested on saved pages. */
internal object SpotifyEmbed {

    /** How many tracks the embed lists at most. */
    const val EMBED_LIMIT = 100

    private val NEXT_DATA = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SONG_COUNT = Regex("""<meta\s+name="music:song_count"\s+content="(\d+)"""")

    // 22-char base62 id from open.spotify.com/playlist/{id}, /intl-xx/playlist/{id},
    // /embed/playlist/{id}, or spotify:playlist:{id}.
    private val PLAYLIST_ID = Regex("""playlist[/:]([A-Za-z0-9]{22})(?![A-Za-z0-9])""")
    private val SHORT_LINK = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/[A-Za-z0-9_-]+""")

    fun playlistId(text: String): String? = PLAYLIST_ID.find(text)?.groupValues?.get(1)

    /** A `spotify.link` short link in [text], to be resolved over the network. */
    fun shortLink(text: String): String? = SHORT_LINK.find(text)?.value

    /** Whether [text] holds something this can import from: a playlist link or a short link. */
    fun looksImportable(text: String): Boolean = playlistId(text) != null || shortLink(text) != null

    /**
     * The playlist in an embed page, or null when the page is Spotify's "not
     * found" (status 404, no data) or not an embed page at all. Only music
     * tracks are kept: a podcast episode in a playlist has nothing to match.
     */
    fun parse(html: String, json: Json): SpotifyPublicPlaylist? {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
        val pageProps = root.path("props", "pageProps") as? JsonObject ?: return null
        if (pageProps["status"]?.jsonPrimitive?.intOrNull == 404) return null
        val entity = pageProps.path("state", "data", "entity") as? JsonObject ?: return null
        val name = entity.string("name") ?: entity.string("title") ?: return null
        val list = entity["trackList"] as? JsonArray ?: return null
        val tracks = list.mapNotNull { item ->
            val track = item as? JsonObject ?: return@mapNotNull null
            val type = track.string("entityType")
            if (type != null && type != "track") return@mapNotNull null
            val title = track.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CsvTrack(
                title = title,
                artist = artists(track.string("subtitle").orEmpty()),
                // The embed does not say which album; matching goes by title and artist.
                album = "",
                durationMs = track["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
        val cover = ((entity["coverArt"] as? JsonObject)?.get("sources") as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonObject)?.string("url") }
        return SpotifyPublicPlaylist(name = name, tracks = tracks, totalTracks = null, coverUrl = cover)
    }

    /** The playlist's length from its page's `music:song_count`. */
    fun songCount(html: String): Int? = SONG_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The embed joins several artists with a comma and a no-break space
     * ("Gwen Stefani, Akon"); written back the way the CSV exports write
     * them, so the matching search sees plain names.
     */
    fun artists(subtitle: String): String =
        subtitle.split(',')
            .map { it.replace(' ', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    private fun JsonElement.path(vararg keys: String): JsonElement? {
        var at: JsonElement? = this
        for (key in keys) at = (at as? JsonObject)?.get(key) ?: return null
        return at
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/** For the Create playlist dialog's link preview, which is composed where no ViewModel is in reach. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SpotifyImportEntryPoint {
    fun spotifyPublicPlaylistReader(): SpotifyPublicPlaylistReader
}
