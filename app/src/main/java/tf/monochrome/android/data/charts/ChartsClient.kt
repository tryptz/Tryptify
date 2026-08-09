package tf.monochrome.android.data.charts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chart sources behind genre Top 100s.
 *
 * Two services, because neither one answers the whole question. ListenBrainz has
 * real, bounded time windows and publishes counted listens under CC0, but its
 * sitewide statistics have no genre dimension at all. Last.fm has a chart for
 * any tag you can name — deep enough to reach `hard techno` and `liquid funk` —
 * but no time dimension whatsoever: `tag.getTopTracks` takes only a tag, a limit
 * and a page. MusicBrainz supplies the missing genre→artist mapping that lets a
 * ListenBrainz window be narrowed to one genre.
 *
 * Every call fails soft to an empty result. A chart is an enrichment; a genre
 * screen that renders without one is degraded, but a genre screen that throws
 * because a third-party chart host had a bad minute is broken.
 */
@Singleton
class ChartsClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    private val json = chartsJson

    companion object {
        private const val LASTFM_API_URL = "https://ws.audioscrobbler.com/2.0/"
        private const val LISTENBRAINZ_STATS_URL =
            "https://api.listenbrainz.org/1/stats/sitewide/recordings"
        private const val MUSICBRAINZ_ARTIST_URL = "https://musicbrainz.org/ws/2/artist"

        /**
         * MusicBrainz asks every client to identify itself and throttles those
         * that don't. This is the contact string it wants, not decoration.
         */
        private const val USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"

        /**
         * How deep to pull the sitewide chart before filtering it to a genre.
         * The global top is dominated by a handful of very large artists, so a
         * shallow pull leaves nothing behind once a niche genre's filter runs;
         * 1000 is the most the endpoint will serve in one request.
         */
        const val SITEWIDE_DEPTH = 1000
    }

    /**
     * The genre half of a windowed chart: which artists belong to a tag.
     *
     * Uses the search endpoint rather than a browse — `?tag=` is not a browse
     * parameter and silently returns nothing, which reads like "this genre has
     * no artists" instead of "that query was malformed".
     */
    suspend fun artistsForTag(tag: String, limit: Int = 100): List<String> = runCatching {
        val body = httpClient.get(MUSICBRAINZ_ARTIST_URL) {
            header("User-Agent", USER_AGENT)
            parameter("query", "tag:\"$tag\"")
            parameter("limit", limit.coerceIn(1, 100))
            parameter("fmt", "json")
        }.bodyAsText()
        parseArtistsForTag(body)
    }.getOrDefault(emptyList())

    /**
     * A genre's Top [limit] by global scrobbles, with no time bound.
     *
     * Requires a Last.fm API key. Returns empty without one rather than pretending
     * — a caller that gets an empty list falls back to a windowed source, which is
     * the right behaviour for a listener who has not set a key up.
     */
    suspend fun tagTopTracks(tag: String, apiKey: String, limit: Int = 100): List<ChartEntry> {
        if (apiKey.isBlank()) return emptyList()
        return runCatching {
            val body = httpClient.get(LASTFM_API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("method", "tag.gettoptracks")
                parameter("tag", tag)
                parameter("limit", limit)
                parameter("api_key", apiKey)
                parameter("format", "json")
            }.bodyAsText()
            parseTagTopTracks(body)
        }.getOrDefault(emptyList())
    }

    /**
     * The windowed half: what the world actually played inside a real time range.
     *
     * No genre filter and no authentication — the caller narrows it with
     * [artistsForTag]. The returned window boundaries are the service's own, not
     * ones computed here, so the screen can state the range it is really showing.
     */
    suspend fun sitewideRecordings(
        window: ChartWindow,
        count: Int = SITEWIDE_DEPTH,
    ): SitewideChart = runCatching {
        val body = httpClient.get(LISTENBRAINZ_STATS_URL) {
            header("User-Agent", USER_AGENT)
            parameter("range", window.listenBrainzRange)
            parameter("count", count)
        }.bodyAsText()
        parseSitewide(body)
    }.getOrDefault(SitewideChart())
}

// --- parsing -----------------------------------------------------------------
//
// Split out from the client so the wire formats can be tested against recorded
// payloads without a network or an HTTP mock. These shapes are the part most
// likely to break silently: a renamed field turns into an empty chart, which
// looks exactly like a genre nobody listens to.

internal val chartsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

internal fun parseArtistsForTag(body: String): List<String> =
    chartsJson.decodeFromString<MusicBrainzArtistSearch>(body).artists
        .map { it.name }
        .filter { it.isNotBlank() }

internal fun parseTagTopTracks(body: String): List<ChartEntry> =
    chartsJson.decodeFromString<LastFmTagTracks>(body).tracks?.track.orEmpty()
        .mapIndexedNotNull { index, track ->
            val title = track.name?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val artist = track.artist?.name?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            ChartEntry(
                // Last.fm's own @attr.rank is 1-based but goes missing on some
                // tags; position in the response is the reliable fallback.
                rank = track.attr?.rank?.toIntOrNull() ?: (index + 1),
                title = title,
                artistName = artist,
                recordingMbid = track.mbid?.takeIf { it.isNotBlank() },
                artworkUrl = track.image.orEmpty().lastOrNull { !it.text.isNullOrBlank() }?.text,
            )
        }

internal fun parseSitewide(body: String): SitewideChart {
    val payload = chartsJson.decodeFromString<ListenBrainzStats>(body).payload
        ?: return SitewideChart()
    return SitewideChart(
        entries = payload.recordings.orEmpty().mapIndexedNotNull { index, rec ->
            val title = rec.trackName?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val artist = rec.artistName?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            ChartEntry(
                rank = index + 1,
                title = title,
                artistName = artist,
                listenCount = rec.listenCount,
                recordingMbid = rec.recordingMbid?.takeIf { it.isNotBlank() },
                artworkUrl = coverArtUrl(rec.caaReleaseMbid, rec.caaId),
            )
        },
        fromTs = payload.fromTs,
        toTs = payload.toTs,
    )
}

/**
 * Cover Art Archive thumbnail for a release, when ListenBrainz gave us the pair
 * of ids that addresses one. Both are required — an id without its release
 * resolves to nothing.
 */
private fun coverArtUrl(releaseMbid: String?, caaId: Long?): String? {
    if (releaseMbid.isNullOrBlank() || caaId == null) return null
    return "https://archive.org/download/mbid-$releaseMbid/mbid-$releaseMbid-${caaId}_thumb250.jpg"
}

/** A sitewide pull, with the real window the service reported for it. */
data class SitewideChart(
    val entries: List<ChartEntry> = emptyList(),
    val fromTs: Long? = null,
    val toTs: Long? = null,
)

// --- wire shapes -------------------------------------------------------------

@Serializable
private data class MusicBrainzArtistSearch(
    val artists: List<MusicBrainzArtist> = emptyList(),
)

@Serializable
private data class MusicBrainzArtist(
    val id: String = "",
    val name: String = "",
)

@Serializable
private data class LastFmTagTracks(val tracks: LastFmTrackList? = null)

@Serializable
private data class LastFmTrackList(val track: List<LastFmTrack> = emptyList())

@Serializable
private data class LastFmTrack(
    val name: String? = null,
    val mbid: String? = null,
    val artist: LastFmArtist? = null,
    val image: List<LastFmImage>? = null,
    @SerialName("@attr") val attr: LastFmAttr? = null,
)

@Serializable
private data class LastFmArtist(val name: String? = null, val mbid: String? = null)

@Serializable
private data class LastFmImage(@SerialName("#text") val text: String? = null, val size: String? = null)

@Serializable
private data class LastFmAttr(val rank: String? = null)

@Serializable
private data class ListenBrainzStats(val payload: ListenBrainzPayload? = null)

@Serializable
private data class ListenBrainzPayload(
    val recordings: List<ListenBrainzRecording>? = null,
    @SerialName("from_ts") val fromTs: Long? = null,
    @SerialName("to_ts") val toTs: Long? = null,
)

@Serializable
private data class ListenBrainzRecording(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("listen_count") val listenCount: Long = 0,
    @SerialName("caa_id") val caaId: Long? = null,
    @SerialName("caa_release_mbid") val caaReleaseMbid: String? = null,
)
