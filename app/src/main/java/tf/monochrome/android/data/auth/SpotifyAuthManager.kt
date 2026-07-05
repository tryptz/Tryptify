package tf.monochrome.android.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.api.SpotifyAuthError
import tf.monochrome.android.data.api.SpotifyTokenResponse
import tf.monochrome.android.data.preferences.PreferencesManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify OAuth via Authorization Code + PKCE — the only grant that works
 * from a mobile app without a client secret or backend. Mirrors the
 * Custom-Tab + deep-link pattern used by [SupabaseAuthManager]:
 *
 *  1. [connect] opens accounts.spotify.com/authorize in a Custom Tab.
 *  2. Spotify redirects to tryptify://spotify-callback, which MainActivity
 *     routes to [handleCallback].
 *  3. The code is exchanged for access + refresh tokens, persisted in
 *     DataStore via [PreferencesManager].
 *  4. [getValidAccessToken] transparently refreshes on expiry. Spotify
 *     rotates refresh tokens for PKCE clients, so a rotated token in the
 *     refresh response must be persisted or the next refresh breaks.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    // PKCE verifier + state survive process death while the Custom Tab is
    // open (same rationale as SharedPrefsCodeVerifierCache for Supabase).
    private val pkcePrefs = context.getSharedPreferences("spotify_pkce", Context.MODE_PRIVATE)

    val isConnected: StateFlow<Boolean> = preferences.spotifyRefreshToken
        .map { !it.isNullOrBlank() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val connectedUserName: StateFlow<String?> = preferences.spotifyUserName
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Launch the Spotify consent page in a Custom Tab. */
    fun connect(activityContext: Context) {
        _errorMessage.value = null
        _isConnecting.value = true

        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        pkcePrefs.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()

        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            BASE64_URL_FLAGS,
        )

        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()

        try {
            CustomTabsIntent.Builder().build().launchUrl(activityContext, url)
        } catch (e: Exception) {
            _isConnecting.value = false
            _errorMessage.value = "Could not open browser: ${e.message}"
        }
    }

    /** Handle tryptify://spotify-callback?code=...&state=... from MainActivity.onNewIntent. */
    suspend fun handleCallback(uri: Uri) {
        try {
            val expectedState = pkcePrefs.getString(KEY_STATE, null)
            val verifier = pkcePrefs.getString(KEY_VERIFIER, null)

            uri.getQueryParameter("error")?.let { error ->
                _errorMessage.value = if (error == "access_denied") {
                    "Spotify authorization was cancelled."
                } else {
                    "Spotify authorization failed: $error"
                }
                return
            }

            val returnedState = uri.getQueryParameter("state")
            if (expectedState.isNullOrBlank() || returnedState != expectedState) {
                _errorMessage.value = "Spotify authorization failed: state mismatch. Please try again."
                return
            }
            val code = uri.getQueryParameter("code")
            if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
                _errorMessage.value = "Spotify authorization failed: missing code. Please try again."
                return
            }

            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
                    append("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                    append("code_verifier", verifier)
                },
            )
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                _errorMessage.value = mapTokenError(response.status.value, body)
                return
            }

            val tokens = json.decodeFromString<SpotifyTokenResponse>(body)
            val refresh = tokens.refreshToken
            if (refresh.isNullOrBlank()) {
                _errorMessage.value = "Spotify did not return a refresh token. Please try again."
                return
            }
            preferences.setSpotifyTokens(
                accessToken = tokens.accessToken,
                refreshToken = refresh,
                expiresAtMillis = System.currentTimeMillis() + tokens.expiresIn * 1000L,
            )
            pkcePrefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
            _errorMessage.value = null
            Log.d(TAG, "Spotify connected; token expires in ${tokens.expiresIn}s")
        } catch (e: Exception) {
            Log.e(TAG, "Spotify callback failed", e)
            _errorMessage.value = "Spotify connection failed: ${e.message}"
        } finally {
            _isConnecting.value = false
        }
    }

    /**
     * Returns a non-expired access token, refreshing if needed; null when
     * not connected or the refresh token was revoked (in which case local
     * state is cleared so the UI falls back to "Connect").
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = refreshMutex.withLock {
        val refreshToken = preferences.spotifyRefreshToken.first() ?: return null
        val expiresAt = preferences.spotifyTokenExpiresAt.first()
        val access = preferences.spotifyAccessToken.first()

        if (!forceRefresh && !access.isNullOrBlank() && expiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return access
        }

        return try {
            val response = httpClient.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                },
            )
            val body = response.bodyAsText()
            if (response.status.value !in 200..299) {
                Log.w(TAG, "Spotify token refresh failed (${response.status.value}): $body")
                if (response.status.value == 400) {
                    // invalid_grant — refresh token revoked/expired; force reconnect.
                    disconnect()
                }
                return null
            }
            val tokens = json.decodeFromString<SpotifyTokenResponse>(body)
            preferences.setSpotifyTokens(
                accessToken = tokens.accessToken,
                // Spotify rotates PKCE refresh tokens — persist the new one when present.
                refreshToken = tokens.refreshToken ?: refreshToken,
                expiresAtMillis = System.currentTimeMillis() + tokens.expiresIn * 1000L,
            )
            tokens.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token refresh error", e)
            null
        }
    }

    suspend fun setConnectedUserName(name: String?) {
        preferences.setSpotifyUserName(name)
    }

    /** Spotify has no token-revocation endpoint for PKCE; clearing local tokens is the disconnect. */
    suspend fun disconnect() {
        preferences.clearSpotifyTokens()
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun mapTokenError(status: Int, body: String): String {
        val parsed = runCatching { json.decodeFromString<SpotifyAuthError>(body) }.getOrNull()
        return when {
            status == 403 || body.contains("not be registered", ignoreCase = true) ->
                "This Spotify app is in Development mode — your Spotify account must be " +
                    "added to the allowlist in the Spotify Developer Dashboard (User Management)."
            parsed?.error == "invalid_grant" ->
                "Authorization expired. Please try connecting again."
            else ->
                "Spotify token exchange failed: ${parsed?.errorDescription ?: parsed?.error ?: "HTTP $status"}"
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, BASE64_URL_FLAGS)
    }

    companion object {
        private const val TAG = "SpotifyAuth"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val BASE64_URL_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_STATE = "state"
    }
}
