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
        private const val MUSICBRAINZ_RELEASE_GROUP_URL = "https://musicbrainz.org/ws/2/release-group"

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

        /** MusicBrainz caps a search page at 100 results. */
        const val MUSICBRAINZ_PAGE = 100

        /**
         * MusicBrainz asks for at most one request a second and enforces it
         * with 503s. Paging a genre's artists is the only place here that
         * makes several calls in a row, so it is the only place that waits.
         */
        const val MUSICBRAINZ_PACE_MS = 1_100L
    }

    /**
     * The genre half of a windowed chart: which artists belong to a tag.
     *
     * Uses the search endpoint rather than a browse — `?tag=` is not a browse
     * parameter and silently returns nothing, which reads like "this genre has
     * no artists" instead of "that query was malformed".
     */
    suspend fun artistsForTag(
        tag: String,
        limit: Int = MUSICBRAINZ_PAGE,
        offset: Int = 0,
    ): List<String> = runCatching {
        val body = httpClient.get(MUSICBRAINZ_ARTIST_URL) {
            header("User-Agent", USER_AGENT)
            parameter("query", "tag:\"$tag\"")
            parameter("limit", limit.coerceIn(1, MUSICBRAINZ_PAGE))
            parameter("offset", offset)
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
     * What an artist is generally tagged as, most-applied first.
     *
     * The counterpart to [artistsForTag], and the one that actually separates a
     * genre's artists from the pop acts a crowd has sprinkled the genre's tag
     * onto. Asking "who is tagged hard techno" surfaces whoever the tag search
     * ranks highest and misses most working producers entirely; asking "what is
     * this artist tagged as" is answered from their own tag cloud, which is
     * dominated by what they actually are. FKA twigs comes back trip-hop and
     * dream pop, Klangkuenstler comes back techno and hard techno.
     */
    suspend fun artistTopTags(artist: String, apiKey: String): List<String> {
        if (apiKey.isBlank() || artist.isBlank()) return emptyList()
        return runCatching {
            val body = httpClient.get(LASTFM_API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("method", "artist.gettoptags")
                parameter("artist", artist)
                parameter("autocorrect", 1)
                parameter("api_key", apiKey)
                parameter("format", "json")
            }.bodyAsText()
            parseArtistTopTags(body)
        }.getOrDefault(emptyList())
    }

    /**
     * What Last.fm knows of an artist that the galaxy's planets use: the
     * opening of their bio, as plain text for a caption ([bioBlurb]), and
     * their MusicBrainz id, which is how their catalogue is counted
     * ([releaseGroupCount]). Null with no key or no such artist.
     */
    suspend fun artistInfo(artist: String, apiKey: String): ArtistInfo? {
        if (apiKey.isBlank() || artist.isBlank()) return null
        return runCatching {
            val body = httpClient.get(LASTFM_API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("method", "artist.getinfo")
                parameter("artist", artist)
                parameter("autocorrect", 1)
                parameter("api_key", apiKey)
                parameter("format", "json")
            }.bodyAsText()
            parseArtistInfo(body)
        }.getOrNull()
    }

    /**
     * How many release groups MusicBrainz lists for the artist [mbid] —
     * albums, EPs and singles, each counted once however many editions it
     * had: the size of their catalogue. Null when it cannot be asked.
     * One request; the caller paces these ([MUSICBRAINZ_PACE_MS]).
     */
    suspend fun releaseGroupCount(mbid: String): Int? = runCatching {
        val body = httpClient.get(MUSICBRAINZ_RELEASE_GROUP_URL) {
            header("User-Agent", USER_AGENT)
            parameter("artist", mbid)
            parameter("limit", 1)
            parameter("fmt", "json")
        }.bodyAsText()
        parseReleaseGroupCount(body)
    }.getOrNull()

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
                artworkUrl = track.image.orEmpty().lastOrNull { !it.text.isNullOrBlank() }?.text
                    ?.takeUnless(::isLastFmPlaceholder),
            )
        }

/**
 * An artist's tag cloud, strongest first, keeping only tags a real number of
 * people applied. Last.fm normalises counts to 100 for the top tag, so the
 * long tail below a few percent is one or two individuals and says nothing.
 */
internal fun parseArtistTopTags(body: String, minCount: Int = 3): List<String> =
    chartsJson.decodeFromString<LastFmArtistTags>(body).topTags?.tag.orEmpty()
        .filter { it.count >= minCount }
        .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }

/** An artist as Last.fm describes them: the opening of their bio, and their MusicBrainz id. */
data class ArtistInfo(val bio: String?, val mbid: String?)

internal fun parseArtistInfo(body: String): ArtistInfo? {
    val artist = chartsJson.decodeFromString<LastFmArtistInfo>(body).artist ?: return null
    return ArtistInfo(
        bio = artist.bio?.summary?.let { bioBlurb(it) },
        mbid = artist.mbid?.takeIf { it.isNotBlank() },
    )
}

internal fun parseReleaseGroupCount(body: String): Int? =
    chartsJson.decodeFromString<MusicBrainzReleaseGroups>(body).count

/**
 * Last.fm's bio summary as one or two whole sentences of plain text, at most
 * [maxChars]: its "Read more on Last.fm" link, the tags and the entities gone.
 * Null when nothing is left, or when it is a disambiguation note ("There are
 * multiple artists with this name") rather than anyone's story.
 */
internal fun bioBlurb(summary: String, maxChars: Int = BIO_CHARS): String? {
    val text = summary
        .replace(Regex("<a [^>]*>\\s*Read more on Last\\.fm\\s*</a>\\.?", RegexOption.IGNORE_CASE), " ")
        // Breaks and paragraphs part words; any other tag sits inside a sentence.
        .replace(Regex("<(br|p|/p|div|/div)\\b[^>]*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .replace(Regex(" ([,.;:!?])"), "$1")
        .trim()
    if (text.isEmpty()) return null
    if (text.startsWith("There are", ignoreCase = true) && text.contains("artists", ignoreCase = true)) return null
    val out = StringBuilder()
    for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
        if (out.isNotEmpty() && out.length + 1 + sentence.length > maxChars) break
        if (out.isNotEmpty()) out.append(' ')
        out.append(sentence)
        if (out.length >= maxChars) break
    }
    val blurb = out.toString()
    return if (blurb.length <= maxChars) blurb else blurb.take(maxChars - 1).trimEnd() + "…"
}

/** About two lines of a planet's caption. */
internal const val BIO_CHARS = 150

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
private data class LastFmArtistTags(@SerialName("toptags") val topTags: LastFmTagList? = null)

@Serializable
private data class LastFmTagList(val tag: List<LastFmTag> = emptyList())

@Serializable
private data class LastFmTag(val name: String? = null, val count: Int = 0)

@Serializable
private data class LastFmArtistInfo(val artist: LastFmArtistBioHolder? = null)

@Serializable
private data class LastFmArtistBioHolder(val bio: LastFmBio? = null, val mbid: String? = null)

@Serializable
private data class MusicBrainzReleaseGroups(@SerialName("release-group-count") val count: Int? = null)

@Serializable
private data class LastFmBio(val summary: String? = null)

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
