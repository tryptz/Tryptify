package tf.monochrome.android.radio.planner

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioPlannerClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    suspend fun plan(request: RadioPlannerRequest): RadioPlan? {
        val baseUrl = BuildConfig.RADIO_PLANNER_URL.trim().trimEnd('/')
        val apiKey = BuildConfig.RADIO_PLANNER_API_KEY.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) return null

        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpClient.post("$baseUrl/api/radio/plan") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<RadioPlan>()
            }.onFailure { error ->
                Log.w(TAG, "Radio planner unavailable: ${error.safeMessage()}")
            }.getOrNull()?.takeIf { it.isUseful }
        }
    }

    suspend fun songList(request: RadioSongListRequest): RadioSongListResponse? {
        val baseUrl = BuildConfig.RADIO_PLANNER_URL.trim().trimEnd('/')
        val apiKey = BuildConfig.RADIO_PLANNER_API_KEY.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) return null

        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpClient.post("$baseUrl/api/radio/song-list") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<RadioSongListResponse>()
            }.onFailure { error ->
                Log.w(TAG, "Radio song-list tester unavailable: ${error.safeMessage()}")
            }.getOrNull()?.takeIf { it.songs.isNotEmpty() || it.message.isNotBlank() }
        }
    }

    private fun Throwable.safeMessage(): String =
        this::class.java.simpleName + (message?.take(180)?.let { ": $it" } ?: "")

    private companion object {
        const val TAG = "RadioPlanner"
        const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
