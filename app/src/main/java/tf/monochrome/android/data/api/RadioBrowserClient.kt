package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.RadioStation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * radio-browser.info — the open community directory of internet radio.
 *
 * Queried at play time rather than bundled, because a stream URL that worked when
 * the release was cut is not a URL that works today: the directory carries tens
 * of thousands of stations and a steady trickle of them go dark every week. What
 * the app ships is the *geography* (see `tools/build_world_radio.py`); what it
 * asks for here is what is actually on air right now.
 *
 * A city's stations are found the same three ways the build-time pass finds
 * them — the location lands in a different field depending on who filed the
 * entry — and the results are merged rather than ranked against each other:
 *
 *   state=   the usual home of a city name
 *   name=    city broadcasters that put it in their own name ("Radio Hamburg")
 *   tag=     everyone else
 *
 * Every call fails soft to an empty list. A city whose stations can't be reached
 * shows as "couldn't reach the directory", never as a city without radio — those
 * are different claims and the panel makes both.
 */
@Singleton
class RadioBrowserClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {

    /**
     * Live stations for a city, strongest first.
     *
     * Only stations the directory last checked successfully are returned: a
     * listing that is known to be broken is worse than a short list, because the
     * listener spends a tap and a timeout finding out.
     */
    suspend fun stations(city: String, countryCode: String): List<RadioStation> {
        if (city.isBlank()) return emptyList()

        val found = LinkedHashMap<String, RadioStation>()
        for (field in LOCATION_FIELDS) {
            val page = search(field to city, countryCode) ?: continue
            for (station in page) {
                val mapped = station.toDomain() ?: continue
                // First writer wins: the fields are tried in descending order of
                // how reliably they mean "this station is from here".
                found.putIfAbsent(mapped.uuid, mapped)
            }
        }
        return found.values.sortedByDescending { it.votes }
    }

    /**
     * Tell the directory a station was played.
     *
     * radio-browser asks clients to do this and builds its popularity ordering
     * out of it — the ordering this very client sorts by. Fire and forget: a
     * failed count is not worth a message, and must never delay playback.
     */
    suspend fun reportClick(stationUuid: String) {
        if (stationUuid.isBlank()) return
        withTimeoutOrNull(CLICK_TIMEOUT_MS) {
            runCatching {
                httpClient.get("$BASE_URL/json/url/$stationUuid") {
                    header("User-Agent", USER_AGENT)
                }
            }
        }
    }

    private suspend fun search(
        query: Pair<String, String>,
        countryCode: String,
    ): List<RadioBrowserStation>? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        runCatching {
            val response = httpClient.get("$BASE_URL/json/stations/search") {
                header("User-Agent", USER_AGENT)
                parameter(query.first, query.second)
                if (countryCode.isNotBlank()) parameter("countrycode", countryCode)
                parameter("hidebroken", true)
                parameter("limit", PAGE_LIMIT)
                parameter("order", "votes")
                parameter("reverse", true)
            }
            if (!response.status.isSuccess()) return@runCatching null
            json.decodeFromString<List<RadioBrowserStation>>(response.bodyAsText())
        }.getOrNull()
    }

    private companion object {
        // The round-robin host. Individual mirrors (de1, de2, at1) exist and are
        // what the docs suggest resolving to, but that requires a DNS SRV lookup
        // Android does not make easy; the round-robin name is the supported
        // simple path.
        const val BASE_URL = "https://all.api.radio-browser.info"
        const val USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"
        const val REQUEST_TIMEOUT_MS = 12_000L
        const val CLICK_TIMEOUT_MS = 4_000L
        const val PAGE_LIMIT = 60

        /** Fields a city name might be recorded in, most reliable first. */
        val LOCATION_FIELDS = listOf("state", "name", "tag")
    }
}

/**
 * radio-browser's station record, trimmed to what the panel and the player need.
 *
 * `url_resolved` rather than `url`: the directory has already followed the
 * redirects, and the raw field is frequently a redirector that ExoPlayer would
 * have to chase itself.
 */
@Serializable
private data class RadioBrowserStation(
    @SerialName("stationuuid") val stationUuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
    val hls: Int = 0,
    @SerialName("countrycode") val countryCode: String = "",
    @SerialName("lastcheckok") val lastCheckOk: Int = 0,
) {
    /**
     * Convert, or refuse.
     *
     * Refusing here is the whole point of this function. ExoPlayer can open
     * `http` and `https` and nothing else on this list: `icy://` and `mms://` are
     * different protocols, and a `.pls` or `.m3u` is a *playlist file* rather
     * than a stream, which would download and then play silence. Dropping them
     * now means a station that appears in the panel is a station that plays.
     */
    fun toDomain(): RadioStation? {
        if (lastCheckOk != 1 || stationUuid.isBlank() || name.isBlank()) return null

        val stream = urlResolved.ifBlank { url }.trim()
        val scheme = stream.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val path = stream.substringBefore('?').lowercase()
        if (path.endsWith(".pls") || path.endsWith(".m3u")) return null

        return RadioStation(
            uuid = stationUuid,
            name = name.trim(),
            url = stream,
            homepage = homepage.takeIf { it.startsWith("http") },
            // Https only, and never a favicon: Coil has no .ico decoder, and the
            // player's Discord presence refuses anything that isn't https, so an
            // unusable image is worse than none — it replaces a clean fallback
            // with a broken one.
            favicon = favicon.takeIf {
                it.startsWith("https://") && !it.lowercase().endsWith(".ico")
            },
            tags = tags,
            codec = codec.takeIf { it.isNotBlank() },
            bitrate = bitrate,
            votes = votes,
            isHls = hls == 1,
            countryCode = countryCode,
        )
    }
}
