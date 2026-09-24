package tf.monochrome.android.data.spotify

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.server.request.queryString
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.data.auth.SpotifyAuthManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Spotify localhost instance.
 *
 * Every other catalog in this app (TIDAL, Qobuz, Apple) is a remote server the
 * user points the app at in Settings → Instances. Spotify is different: it is
 * the one catalog whose "instance" the app can host for itself. This class
 * starts an embedded HTTP server on 127.0.0.1 that mirrors the Spotify Web API
 * surface (`/v1/...`), proxying each request to `api.spotify.com` with the
 * app's own PKCE OAuth token attached. The Web API client then talks to
 * `http://127.0.0.1:<port>/<secret>/v1` like it would to any other instance.
 *
 * Why a proxy instead of calling api.spotify.com directly:
 *  - Uniform instance shape: the client code has exactly one way to talk to a
 *    catalog server, and Spotify now fits it — self-created, self-owned.
 *  - Token handling lives in exactly one place (the proxy injects the current
 *    PKCE access token into every request; refresh is SpotifyAuthManager's).
 *  - A single seam for caching, normalization or a future local catalog
 *    (librespot metadata) to be served without touching every client call site.
 *
 * The server binds to loopback only — nothing off-device can reach it — and
 * starts lazily on the first Spotify API call, so it never runs while Spotify
 * isn't being used.
 *
 * Loopback is not private, though: every app on the device can open
 * 127.0.0.1, and this server answers with the user's own Spotify token
 * attached. So every route sits under a random secret generated per process
 * (`/<secret>/v1/...`), which only this app's client is handed, and a request
 * without it gets a bare 404. Path segments are re-encoded and `.`/`..` are
 * refused, so a request cannot climb out of `/v1` upstream either.
 */
@Singleton
class SpotifyLocalInstance @Inject constructor(
    private val authManager: SpotifyAuthManager,
    private val httpClient: HttpClient,
) {
    private val startMutex = Mutex()

    @Volatile
    private var port: Int? = null

    /** The path prefix a request must carry; 256 bits, new every process. */
    private val secret: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** Whether the embedded instance is up. */
    val isRunning: Boolean get() = port != null

    /**
     * Base URL of the localhost Spotify instance (`http://127.0.0.1:<port>/<secret>/v1`),
     * starting the embedded server on first use. Cheap after the first call:
     * the mutex is only contended during startup.
     */
    suspend fun baseUrl(): String {
        ensureStarted()
        return "http://127.0.0.1:$port/$secret/v1"
    }

    private suspend fun ensureStarted() {
        port?.let { return }
        startMutex.withLock {
            port?.let { return }
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing {
                    // Local-only status probe (not proxied upstream).
                    get("/{secret}/v1/health") {
                        if (!authorised(call.parameters["secret"])) {
                            call.respondBytes(ByteArray(0), ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@get
                        }
                        call.respondBytes("ok".toByteArray(), ContentType.Text.Plain)
                    }

                    // Everything else under /v1 mirrors the Spotify Web API:
                    // same path, same query string, same status codes — with the
                    // app's PKCE token attached. Status/Retry-After pass through
                    // verbatim so the client's 401-refresh and 429-backoff logic
                    // keep working against the localhost instance unchanged.
                    get("/{secret}/v1/{path...}") {
                        val path = upstreamPath(call.parameters.getAll("path").orEmpty())
                        if (!authorised(call.parameters["secret"]) || path == null) {
                            call.respondBytes(ByteArray(0), ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@get
                        }
                        val token = authManager.getValidAccessToken()
                        if (token == null) {
                            call.respondBytes(
                                """{"error":{"message":"Spotify is not connected"}}""".toByteArray(),
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                            return@get
                        }
                        val query = call.request.queryString()
                        val url = buildString {
                            append(UPSTREAM_BASE)
                            append('/')
                            append(path)
                            if (!query.isNullOrBlank()) {
                                append('?')
                                append(query)
                            }
                        }
                        try {
                            val response: HttpResponse = httpClient.get(url) {
                                header("Authorization", "Bearer $token")
                            }
                            val bytes = response.readRawBytes()
                            response.headers["Retry-After"]?.let {
                                call.response.headers.append("Retry-After", it)
                            }
                            call.respondBytes(
                                bytes,
                                response.contentType() ?: ContentType.Application.Json,
                                response.status,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "spotify proxy $path failed", e)
                            call.respondBytes(
                                """{"error":{"message":"Spotify instance error: ${e.message}"}}"""
                                    .toByteArray(),
                                ContentType.Application.Json,
                                HttpStatusCode.BadGateway,
                            )
                        }
                    }
                }
            }
            try {
                server.start(wait = false)
                val resolved = server.engine.resolvedConnectors().first()
                port = resolved.port
                Log.i(TAG, "Spotify localhost instance up on 127.0.0.1:${resolved.port}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Spotify localhost instance", e)
                try {
                    server.stop(500, 1000)
                } catch (_: Exception) {
                }
                throw e
            }
        }
    }

    /** Constant-time, so the secret cannot be guessed a byte at a time. */
    private fun authorised(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), secret.toByteArray())

    internal companion object {
        const val TAG = "SpotifyLocalInstance"
        const val UPSTREAM_BASE = "https://api.spotify.com/v1"

        /**
         * The upstream path for the decoded [segments] after `/v1/`, each
         * re-encoded, or null if any would step outside `/v1` — `.`, `..` or
         * an empty segment — or there are none.
         */
        fun upstreamPath(segments: List<String>): String? {
            if (segments.isEmpty()) return null
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            return segments.joinToString("/") { it.encodeURLPathPart() }
        }
    }
}