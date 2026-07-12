package tf.monochrome.android.radio

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin HTTP client for the optional Tryptify-Playlist radio planner
 * (`POST /api/radio/plan`). The planner is advisory: every failure path
 * returns a null/failed result and radio falls back to on-device heuristics.
 */
@Singleton
class RadioPlannerClient @Inject constructor(
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
) {
    // Own Json config: encodeDefaults so neutral weights are still sent
    // explicitly, ignoreUnknownKeys so server additions never break decoding.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** True when the planner is enabled and has both a URL and an API key. */
    suspend fun isConfigured(): Boolean {
        if (!preferences.radioPlannerEnabled.first()) return false
        val url = preferences.radioPlannerUrl.first()
        val key = preferences.radioPlannerApiKey.first()
        return url.isNotBlank() && !key.isNullOrBlank()
    }

    /**
     * Requests a plan. Returns null when the planner is unconfigured, times
     * out, or errors — callers treat null as "no hints, use fallback".
     */
    suspend fun plan(request: RadioPlanRequest): RadioPlanResponse? {
        if (!isConfigured()) return null
        val baseUrl = preferences.radioPlannerUrl.first().trimEnd('/')
        val apiKey = preferences.radioPlannerApiKey.first() ?: return null
        return try {
            withTimeout(PLAN_TIMEOUT_MS) {
                val response = postPlan(baseUrl, apiKey, json.encodeToString(request))
                if (response.isSuccess()) {
                    json.decodeFromString<RadioPlanResponse>(response.bodyAsText())
                } else if (response.status.value in 400..499) {
                    // A stricter server may reject the extended request shape
                    // (weights/history/metabrainz). Retry with the minimal
                    // seed-only body the service has always accepted.
                    Log.w(TAG, "plan rejected (${response.status}); retrying seed-only")
                    val minimal = postPlan(baseUrl, apiKey, json.encodeToString(mapOf("seed" to request.seed)))
                    if (minimal.isSuccess()) {
                        json.decodeFromString<RadioPlanResponse>(minimal.bodyAsText())
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "plan request failed: ${e.message}")
            null
        }
    }

    private suspend fun postPlan(baseUrl: String, apiKey: String, body: String): HttpResponse =
        httpClient.post("$baseUrl/api/radio/plan") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(body)
        }

    /** Fetches `/health` for the settings connection test. */
    suspend fun health(): Result<PlannerHealth> = runCatching {
        val baseUrl = preferences.radioPlannerUrl.first().trimEnd('/')
        require(baseUrl.isNotBlank()) { "Planner URL is not set" }
        withTimeout(HEALTH_TIMEOUT_MS) {
            val response = httpClient.get("$baseUrl/health")
            check(response.isSuccess()) { "HTTP ${response.status.value}" }
            json.decodeFromString<PlannerHealth>(response.bodyAsText())
        }
    }

    private fun HttpResponse.isSuccess() = status.isSuccess()

    companion object {
        private const val TAG = "RadioPlannerClient"
        // Must stay above the server's 18 s model-generation timeout so a slow
        // model run still returns hints instead of being abandoned mid-flight.
        private const val PLAN_TIMEOUT_MS = 25_000L
        private const val HEALTH_TIMEOUT_MS = 10_000L
    }
}
