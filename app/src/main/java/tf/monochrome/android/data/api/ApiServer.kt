package tf.monochrome.android.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A streaming service a server can answer for.
 *
 * [probePath] is the request that proves it: a real search the service's own
 * routes must answer with JSON. [TIDAL] is a hifi-api server's layout; the
 * other three are TrypT HiFi (trypt-hifi) routes, one server usually carrying
 * all three.
 */
enum class ApiService(val label: String, val probePath: String) {
    TIDAL("TIDAL", "/search/?s=test&limit=1"),
    QOBUZ("Qobuz", "/api/get-music?q=test&offset=0"),
    APPLE("Apple Music", "/api/apple/get-music?q=test&offset=0"),
    DEEZER("Deezer", "/api/deezer/get-music?q=test&offset=0"),

    /**
     * Stem separation for Trypt Mania's charts: a TryptMania Parser server
     * (github.com/tryptz/TryptMania-Parser), which separates a song on a GPU
     * and answers with the chart generator's envelopes. Not a catalog: it is
     * never searched, only asked to separate.
     */
    STEMS("Stem separation", "/api/stems/info"),

    /**
     * Trypt Mania's rooms (TryptMania-Parser's `multiplayer/` service, in
     * Rust): a host picks a chart, everyone with the song plays it from the
     * same moment, scores live, results shared. Its socket is `/ws` beside
     * this probe.
     */
    MULTIPLAYER("Multiplayer", "/api/multiplayer/info"),
}

/** What a TryptMania Parser server serves: Trypt Mania's charts and rooms, never a catalog. */
val TRYPT_MANIA_SERVICES: Set<ApiService> = setOf(ApiService.STEMS, ApiService.MULTIPLAYER)

/**
 * A server's services as saved: names, and a name this build no longer has
 * (`LYRICS`, the lyrics timing removed 2026-10-29) is dropped rather than
 * failing the whole list, which fell back to the legacy keys and lost every
 * server the listener had added.
 */
object ApiServiceSetSerializer : KSerializer<Set<ApiService>> {
    private val names = SetSerializer(String.serializer())
    override val descriptor: SerialDescriptor = names.descriptor
    override fun serialize(encoder: Encoder, value: Set<ApiService>) = names.serialize(encoder, value.mapTo(LinkedHashSet()) { it.name })
    override fun deserialize(decoder: Decoder): Set<ApiService> =
        names.deserialize(decoder).mapNotNullTo(LinkedHashSet()) { name -> ApiService.entries.firstOrNull { it.name == name } }
}

/**
 * One API the user added under Settings › Connections, and what it was found
 * to serve when it was last checked.
 *
 * Services are *detected*, never picked: the user gives a URL and the app asks
 * the server. That is what lets one list replace the old per-catalog fields and
 * the source-mode picker — a catalog is searched when some server serves it.
 */
@Serializable
data class ApiServer(
    val url: String,
    val services: @Serializable(with = ApiServiceSetSerializer::class) Set<ApiService> = emptySet(),
    /** When [services] was last established, epoch millis; 0 = never checked. */
    val checkedAt: Long = 0L,
    /**
     * For a stem separation server, what it separates on, as it says
     * (`cuda` or `cpu`): Trypt Mania's Fast mode goes to a GPU, Slow to a CPU.
     * Null when unknown — a server added before servers were asked.
     */
    val stemDevice: String? = null,
) {
    /** Whether this server separates on a GPU; null when it has not said. */
    val separatesOnGpu: Boolean? get() = stemDevice?.let { it == "cuda" }

    /**
     * Listed under Trypt Mania rather than Streaming: a server that serves only
     * Trypt Mania's services. One not checked yet, or that serves a catalog
     * too, is a streaming one.
     */
    val isTryptMania: Boolean
        get() = services.isNotEmpty() && services.all { it in TRYPT_MANIA_SERVICES }
}

object ApiServers {

    /**
     * The server that answers for [service]: the first in the list that serves
     * it. List order is the user's priority, so two servers offering the same
     * catalog never race — the upper one wins and the lower one is a spare.
     */
    fun serverFor(servers: List<ApiServer>, service: ApiService): ApiServer? =
        servers.firstOrNull { service in it.services }

    /**
     * A typed-in address as a base URL: scheme added when missing (https — a
     * phone on mobile data should not default to cleartext), surrounding
     * whitespace and trailing slashes dropped. Null for something that cannot
     * be a server address at all.
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        // Scheme first, before any slashes go: "https://" trimmed first would
        // read as a host called "https:".
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = withScheme.substringAfter("://").trimEnd('/')
        val host = rest.substringBefore('/').substringBefore('?')
        if (host.isEmpty() || host.endsWith(':') || host.none { it.isLetterOrDigit() }) return null
        return "$scheme://$rest"
    }

    /**
     * The list a user upgrading from the per-catalog fields starts with, so an
     * update never loses a working setup. The Qobuz field always pointed at a
     * TrypT HiFi server, which is also where Apple and Deezer were being asked
     * already; it is assumed to carry all three until its first check says
     * otherwise.
     */
    fun fromLegacy(tidalUrl: String?, qobuzUrl: String?, appleUrl: String?): List<ApiServer> {
        val out = mutableListOf<ApiServer>()
        fun add(raw: String?, services: Set<ApiService>) {
            val url = raw?.let(::normalizeUrl) ?: return
            val existing = out.indexOfFirst { it.url.equals(url, ignoreCase = true) }
            if (existing >= 0) out[existing] = out[existing].copy(services = out[existing].services + services)
            else out.add(ApiServer(url, services))
        }
        add(tidalUrl, setOf(ApiService.TIDAL))
        // A separate Apple URL used to win over the Qobuz server for Apple, so
        // it goes above it and keeps winning.
        add(appleUrl, setOf(ApiService.APPLE))
        add(qobuzUrl, setOf(ApiService.QOBUZ, ApiService.APPLE, ApiService.DEEZER))
        return out
    }
}
