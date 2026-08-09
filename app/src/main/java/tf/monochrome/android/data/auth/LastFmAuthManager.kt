package tf.monochrome.android.data.auth

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.scrobbling.LastFmSigning
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connecting a Last.fm account, the way Last.fm intends it to be done.
 *
 * Before this, "connect" meant typing a **session key** into a text box. Session
 * keys are not something a person has: they come out of `auth.getSession`, an
 * API call this app could not make, so the only way to fill that box was to run
 * the handshake by hand outside the app. In practice nobody did, which meant
 * nobody ever scrobbled — a second reason on top of the fake shared secret the
 * service used to sign with.
 *
 * The real flow is the same three steps every OAuth-shaped handshake has, and
 * mirrors [SpotifyAuthManager] deliberately so there is one pattern here:
 *
 *  1. [connect] opens last.fm/api/auth in a Custom Tab, carrying `cb` — the
 *     callback that brings the browser back to this app.
 *  2. Last.fm redirects to `tryptify://lastfm-callback?token=…`, which
 *     MainActivity routes to [handleCallback].
 *  3. The token is traded for a session key via `auth.getSession`, signed with
 *     the shared secret, and the key is stored. Session keys do not expire, so
 *     unlike Spotify there is nothing to refresh — this runs once.
 *
 * The credentials it signs with are still the listener's own, registered at
 * last.fm/api/account/create. That is not an oversight: a shared secret shipped
 * inside an APK is extractable by anyone who unzips it, and a suspension for
 * whatever someone then signs with it would land on every install at once. The
 * callback flow removes the impossible step, not the account.
 */
@Singleton
class LastFmAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isConnected: StateFlow<Boolean> = preferences.lastFmEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val username: StateFlow<String?> = preferences.lastFmUsername
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** The address to paste into the Last.fm application's Callback URL field. */
    val callbackUrl: String get() = LastFmSigning.CALLBACK_URL

    /**
     * Send the listener to Last.fm's consent page.
     *
     * Checks the key *before* opening anything. Launching the browser without
     * one produces a Last.fm error page rather than a consent page, and an
     * error in a browser tab is the hardest possible place to explain what the
     * app needs — so the missing-credentials case is answered here instead.
     */
    fun connect(activityContext: Context) {
        scope.launch {
            _errorMessage.value = null
            val apiKey = runCatching { preferences.lastFmApiKey.first() }.getOrNull().orEmpty()
            val secret = runCatching { preferences.lastFmApiSecret.first() }.getOrNull().orEmpty()
            if (apiKey.isBlank() || secret.isBlank()) {
                _errorMessage.value =
                    "Add your Last.fm API key and shared secret first — see Your API key above."
                return@launch
            }
            _isConnecting.value = true
            // The Custom Tab has to be launched from the main thread; the
            // credential read above is DataStore and must not be.
            withContext(Dispatchers.Main) {
                try {
                    CustomTabsIntent.Builder().build()
                        .launchUrl(activityContext, Uri.parse(LastFmSigning.authorizeUrl(apiKey)))
                } catch (e: Exception) {
                    _isConnecting.value = false
                    _errorMessage.value = "Could not open browser: ${e.message}"
                }
            }
        }
    }

    /** Handle `tryptify://lastfm-callback?token=…` from MainActivity. */
    suspend fun handleCallback(uri: Uri) {
        try {
            val token = uri.getQueryParameter("token")
            if (token.isNullOrBlank()) {
                // Last.fm sends no error parameter when someone declines — the
                // callback simply arrives without a token, so this is both the
                // "said no" case and the malformed one.
                _errorMessage.value = "Last.fm didn't grant access. Try connecting again."
                return
            }

            val apiKey = preferences.lastFmApiKey.first()
            val secret = preferences.lastFmApiSecret.first()
            if (apiKey.isBlank() || secret.isBlank()) {
                _errorMessage.value = "Your Last.fm API key and secret are missing."
                return
            }

            val params = LastFmSigning.sessionParams(apiKey, token)
            val signature = LastFmSigning.sign(params, secret)
            val response = httpClient.submitForm(
                url = LastFmSigning.API_URL,
                formParameters = parameters {
                    params.forEach { (k, v) -> append(k, v) }
                    append("api_sig", signature)
                    append("format", "json")
                },
            )
            val body = response.bodyAsText()

            when (val result = LastFmSigning.parseSession(body)) {
                is LastFmSigning.SessionResult.Granted -> {
                    preferences.setLastFmSession(result.sessionKey, result.username)
                    _errorMessage.value = null
                    Log.d(TAG, "Last.fm connected as ${result.username}")
                }
                is LastFmSigning.SessionResult.Refused -> {
                    _errorMessage.value = LastFmSigning.explain(result.code, result.message)
                    Log.w(TAG, "Last.fm refused the session (${result.code}): ${result.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Last.fm callback failed", e)
            _errorMessage.value = "Last.fm connection failed: ${e.message}"
        } finally {
            _isConnecting.value = false
        }
    }

    /**
     * Forget the session.
     *
     * Last.fm has no revocation endpoint — a session key is killed from the
     * account's own Applications page — so clearing it locally is the whole of
     * disconnecting, the same as the Spotify flow.
     */
    suspend fun disconnect() {
        preferences.clearLastFmSession()
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private companion object {
        const val TAG = "LastFmAuth"
    }
}
