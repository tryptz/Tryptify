package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** What one server was found to serve, and why each other service didn't answer. */
data class ProbeResult(
    val url: String,
    val services: Set<ApiService>,
    /** Service -> one line saying why it didn't count. Only the ones that failed. */
    val reasons: Map<ApiService, String>,
)

/**
 * Asks a server which streaming services it answers for, by making each
 * service's own search request (see [ApiService.probePath]) at once.
 *
 * A probe is a real request rather than a guess from the URL or a version
 * string, because the thing that matters is whether search would work: a
 * TrypT HiFi server with no Apple developer token has the /api/apple routes,
 * and every one of them fails.
 */
@Singleton
class ApiServerProber @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun probe(url: String): ProbeResult = coroutineScope {
        val outcomes = ApiService.entries.map { service ->
            async { service to probeOne(url, service) }
        }.map { it.await() }
        ProbeResult(
            url = url,
            services = outcomes.filter { it.second == null }.map { it.first }.toSet(),
            reasons = outcomes.mapNotNull { (service, reason) -> reason?.let { service to it } }.toMap(),
        )
    }

    /** Null when [service] answered; otherwise why not. */
    private suspend fun probeOne(url: String, service: ApiService): String? {
        val response = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val res = httpClient.get(url + service.probePath)
                res.status.value to res.bodyAsText()
            }
        } ?: return "no answer within ${PROBE_TIMEOUT_MS / 1000} s"
        val (status, body) = response.getOrElse { e ->
            return "couldn't connect (${e.message?.take(60) ?: e::class.simpleName})"
        }
        return verdict(service, status, body, json)
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 10_000L

        /**
         * Whether a probe's response means the service is there: null if it
         * is, or a short reason if not.
         *
         * TIDAL (hifi-api) answers search with a JSON document and no envelope.
         * The TrypT HiFi routes wrap everything in {success, data|error}, and
         * report failures — a missing key, an unset token — as success:false,
         * which is not a working service however well-formed it is.
         */
        fun verdict(service: ApiService, status: Int, body: String, json: Json): String? {
            val first = body.firstOrNull { !it.isWhitespace() }
            if (first == '<') return "answered with a web page, not the API (HTTP $status)"
            val obj = if (first == '{') {
                runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            } else null

            // A TrypT HiFi error says what is wrong ("missing developer token"),
            // and usually arrives as a 400 — worth more than the status code.
            val success = obj?.get("success")?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            if (service != ApiService.TIDAL && success == false) {
                val message = obj["error"]
                    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                return "reported an error" + (message?.let { ": ${it.take(80)}" } ?: "")
            }
            if (status !in 200..299) return "HTTP $status"
            if (first != '{' && first != '[') return "didn't answer with JSON"
            if (service == ApiService.TIDAL) return null
            return when {
                obj == null -> "didn't answer with a JSON object"
                success == true -> null
                else -> "isn't a TrypT HiFi response"
            }
        }
    }
}
