package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.auth.SpotifyAuthManager
import tf.monochrome.android.data.import_.CsvTrack
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when no Spotify account is connected (or the session expired). */
class SpotifyNotConnectedException :
    Exception("Spotify is not connected. Connect your Spotify account in Settings first.")

/** Thrown on 403 — restricted content or a non-allowlisted Development-mode account. */
class SpotifyForbiddenException(message: String) : Exception(message)

class SpotifyNotFoundException :
    Exception("Playlist not found — it may be private, deleted, or the URL is wrong.")

/**
 * Authenticated Spotify Web API client for playlist import. All requests
 * carry the PKCE user token from [SpotifyAuthManager]; 401s trigger one
 * forced refresh, 429s honor Retry-After.
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val authManager: SpotifyAuthManager,
) {

    suspend fun getCurrentUser(): Result<SpotifyUserProfile> = runCatching {
        json.decodeFromString<SpotifyUserProfile>(authedGet("$API_BASE/me").bodyAsText())
    }

    suspend fun getPlaylistMeta(playlistId: String): Result<SpotifyPlaylistMeta> = runCatching {
        val resp = authedGet("$API_BASE/playlists/$playlistId?fields=id,name,description")
        json.decodeFromString<SpotifyPlaylistMeta>(resp.bodyAsText())
    }

    /**
     * All tracks of a playlist, following pagination past the 100-item page
     * limit. Uses the /items endpoint introduced by the Feb 2026 Web API
     * migration (the old /tracks endpoint now returns 403 for every
     * Development-mode caller). Only works for playlists the user owns or
     * collaborates on — Spotify no longer serves other playlists' contents
     * to dev-mode apps.
     */
    suspend fun getPlaylistTracks(playlistId: String): Result<List<CsvTrack>> = runCatching {
        paginate { offset ->
            val fields = "items(item(name,duration_ms,is_local,type,artists(name),album(name))),next,total"
            val resp = authedGet(
                "$API_BASE/playlists/$playlistId/items?limit=100&offset=$offset&fields=$fields",
            )
            json.decodeFromString<SpotifyPagingObject<SpotifyPlaylistTrackItem>>(resp.bodyAsText())
        }.mapNotNull { it.trackOrItem.toCsvTrackOrNull() }
    }

    /** The connected user's playlists (owned + followed). */
    suspend fun getMyPlaylists(): Result<List<SpotifySimplePlaylist>> = runCatching {
        paginate { offset ->
            val resp = authedGet("$API_BASE/me/playlists?limit=50&offset=$offset")
            json.decodeFromString<SpotifyPagingObject<SpotifySimplePlaylist>>(resp.bodyAsText())
        }
    }

    /** The connected user's Liked Songs. */
    suspend fun getLikedSongs(): Result<List<CsvTrack>> = runCatching {
        paginate { offset ->
            val resp = authedGet("$API_BASE/me/tracks?limit=50&offset=$offset")
            json.decodeFromString<SpotifyPagingObject<SpotifySavedTrackItem>>(resp.bodyAsText())
        }.mapNotNull { it.track.toCsvTrackOrNull() }
    }

    /**
     * Follows Spotify's paging objects: fetch page at offset 0, then keep
     * bumping the offset by the page's item count while `next` is set.
     */
    private suspend fun <T> paginate(fetchPage: suspend (offset: Int) -> SpotifyPagingObject<T>): List<T> {
        val all = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = fetchPage(offset)
            all += page.items
            if (page.next == null || page.items.isEmpty()) break
            offset += page.items.size
            if (offset >= MAX_TRACKS) break // safety valve against runaway pagination
        }
        return all
    }

    private suspend fun authedGet(url: String): HttpResponse {
        var attempt = 0
        var forcedRefresh = false
        while (true) {
            val token = authManager.getValidAccessToken(forceRefresh = forcedRefresh)
                ?: throw SpotifyNotConnectedException()
            val response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
            }
            when (response.status.value) {
                in 200..299 -> return response
                401 -> {
                    // Token rejected despite local expiry check — force one refresh.
                    if (forcedRefresh) {
                        authManager.disconnect()
                        throw SpotifyNotConnectedException()
                    }
                    forcedRefresh = true
                }
                403 -> throw SpotifyForbiddenException(buildForbiddenMessage(url, response.bodyAsText()))
                404 -> throw SpotifyNotFoundException()
                429 -> {
                    if (attempt >= MAX_RETRIES) {
                        throw Exception("Spotify rate limit exceeded. Please try again in a minute.")
                    }
                    val retryAfterSec = response.headers["Retry-After"]?.toLongOrNull() ?: 2L
                    delay(retryAfterSec.coerceAtMost(MAX_RETRY_AFTER_SEC) * 1000)
                    attempt++
                }
                else -> {
                    val detail = runCatching {
                        json.decodeFromString<SpotifyErrorBody>(response.bodyAsText()).error?.message
                    }.getOrNull()
                    throw Exception("Spotify API error ${response.status.value}${detail?.let { ": $it" } ?: ""}")
                }
            }
        }
    }

    private fun buildForbiddenMessage(url: String, body: String): String {
        val detail = runCatching {
            json.decodeFromString<SpotifyErrorBody>(body).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val hint = if (url.contains("/playlists/")) {
            "Spotify only serves playlist contents for playlists you own or collaborate on " +
                "(Feb 2026 API restriction for Development-mode apps). Also check that your " +
                "Spotify account is allowlisted in the Developer Dashboard (User Management)."
        } else {
            "Check that your Spotify account is allowlisted in the Spotify Developer " +
                "Dashboard (User Management) — the app is in Development mode."
        }
        return buildString {
            append("Spotify denied access (403")
            detail?.let { append(": ").append(it) }
            append("). ")
            append(hint)
        }
    }

    private fun SpotifyTrack?.toCsvTrackOrNull(): CsvTrack? {
        if (this == null || isLocal || type != "track" || name.isBlank()) return null
        return CsvTrack(
            title = name,
            // Matches Exportify's "Artist Name(s)" semantics used by the CSV pipeline.
            artist = artists.joinToString(", ") { it.name },
            album = album?.name.orEmpty(),
            durationMs = durationMs,
        )
    }

    companion object {
        private const val API_BASE = "https://api.spotify.com/v1"
        private const val MAX_RETRIES = 3
        private const val MAX_RETRY_AFTER_SEC = 30L
        private const val MAX_TRACKS = 10_000
    }
}
