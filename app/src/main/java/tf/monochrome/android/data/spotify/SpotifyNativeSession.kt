package tf.monochrome.android.data.spotify

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SpotifyAuthManager
import xyz.gianlu.librespot.core.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs the in-process librespot client in with the account the app is
 * already connected to, on demand. StreamResolver asks [ensureConnected]
 * before minting a `spotify-pcm://` item: true means the real audio can be
 * played through ExoPlayer and the DSP chain; false means fall back to the
 * App Remote shadow (or skip the track).
 *
 * The token needs the `streaming` scope. Accounts connected before it was
 * requested fail here until they reconnect — and keep playing through the
 * Spotify app meanwhile. A failure is remembered for [RETRY_AFTER_MS] so a
 * queue of Spotify tracks does not retry a doomed login per track; [signIn]
 * (the settings button) skips that wait.
 *
 * Signing in is not the same as playing. librespot can sign in and still be
 * unable to load a track — Spotify's metadata changed under it once, a free
 * account gets no audio keys — and each such track used to fail on its own
 * with no way out but the next one. [reportPlaybackFailure] records that, and
 * [nativePlaybackUsable] then sends Spotify tracks to the Spotify app for
 * [PLAYBACK_RETRY_AFTER_MS], the same way a failed sign-in does.
 */
@Singleton
class SpotifyNativeSession @Inject constructor(
    private val auth: SpotifyAuthManager,
    private val librespot: LibrespotPlayerWrapper,
) {
    enum class State { SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }

    /**
     * What the Spotify settings show. [error] is the last sign-in failure, for
     * [State.FAILED]; [hasStoredCredentials] says whether a reusable login from
     * an earlier sign-in is on disk.
     */
    data class Status(
        val state: State,
        val username: String? = null,
        val error: String? = null,
        val hasStoredCredentials: Boolean = false,
        /**
         * Why the last native playback failed, while that failure is keeping
         * Spotify tracks on the Spotify app ([nativePlaybackUsable] is false).
         */
        val playbackError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastFailureAt = 0L
    @Volatile private var playbackFailedAt = 0L
    @Volatile private var playbackFailure: String? = null

    private val _status = MutableStateFlow(Status(State.SIGNED_OUT))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        // Every way the account gets disconnected — the settings button, or a
        // revoked refresh token — must take librespot's stored login with it,
        // or the next play signs in as the account that was just removed.
        scope.launch {
            var wasConnected = false
            auth.isConnected.collect { connected ->
                if (wasConnected && !connected) {
                    Log.i(TAG, "Spotify account disconnected; signing librespot out")
                    signOut()
                }
                wasConnected = connected
            }
        }
        scope.launch { refreshStatus() }
    }

    suspend fun ensureConnected(): Boolean = connect(force = false)

    /** Sign in now, ignoring the retry wait after a failure. */
    suspend fun signIn(): Boolean {
        clearPlaybackFailure()
        return connect(force = true)
    }

    /**
     * librespot was signed in and still could not play a track. Until
     * [PLAYBACK_RETRY_AFTER_MS] passes, or [signIn] is used, Spotify tracks
     * go to the Spotify app when it is installed.
     */
    fun reportPlaybackFailure(reason: String) {
        playbackFailedAt = SystemClock.elapsedRealtime()
        playbackFailure = reason
        Log.w(TAG, "native playback failed ($reason); Spotify tracks use the Spotify app for " +
            "${PLAYBACK_RETRY_AFTER_MS / 60_000} min")
        publish(_status.value.state, _status.value.error)
    }

    /** False while a recent [reportPlaybackFailure] stands. */
    fun nativePlaybackUsable(): Boolean {
        val at = playbackFailedAt
        if (at == 0L) return true
        if (SystemClock.elapsedRealtime() - at < PLAYBACK_RETRY_AFTER_MS) return false
        clearPlaybackFailure()
        return true
    }

    private fun clearPlaybackFailure() {
        if (playbackFailedAt == 0L) return
        playbackFailedAt = 0L
        playbackFailure = null
        publish(_status.value.state, _status.value.error)
    }

    /**
     * A playlist through librespot, which — unlike the Web API — serves any
     * public playlist. Signs in first if needed; throws if that fails.
     */
    suspend fun getPlaylist(playlistId: String): SpotifyNativePlaylist {
        check(ensureConnected()) {
            _status.value.error ?: "The in-app Spotify player isn't signed in"
        }
        return withContext(Dispatchers.IO) { librespot.getPlaylist(playlistId) }
    }

    /**
     * Drop librespot's session and stored blob but leave the Spotify account
     * connected — used when the manual token changes, so the next sign-in
     * picks the new token up instead of a cached session.
     */
    suspend fun signOutKeepAccount() {
        mutex.withLock {
            withContext(Dispatchers.IO) { librespot.signOut() }
            lastFailureAt = 0L
            playbackFailedAt = 0L
            playbackFailure = null
            publish(State.SIGNED_OUT)
        }
    }

    /** Disconnect and forget the stored login; the next sign-in uses the app's token. */
    suspend fun signOut() {
        mutex.withLock {
            withContext(Dispatchers.IO) { librespot.signOut() }
            lastFailureAt = 0L
            playbackFailedAt = 0L
            playbackFailure = null
            publish(State.SIGNED_OUT)
        }
    }

    /** Re-reads librespot's state — the session can drop on its own. */
    suspend fun refreshStatus() {
        mutex.withLock {
            val current = _status.value.state
            when {
                librespot.isConnected -> publish(State.SIGNED_IN)
                current == State.SIGNED_IN -> publish(State.SIGNED_OUT)
                else -> publish(current, _status.value.error)
            }
        }
    }

    private suspend fun connect(force: Boolean): Boolean {
        if (librespot.isConnected) return true
        return mutex.withLock {
            if (librespot.isConnected) return@withLock true
            if (!force && lastFailureAt != 0L && SystemClock.elapsedRealtime() - lastFailureAt < RETRY_AFTER_MS) {
                val waitS = (RETRY_AFTER_MS - (SystemClock.elapsedRealtime() - lastFailureAt)) / 1000
                Log.i(TAG, "native sign-in failed recently; not retrying for ${waitS}s (Spotify app fallback)")
                return@withLock false
            }
            publish(State.SIGNING_IN)
            Log.i(TAG, "signing librespot in (Spotify account connected=${auth.isConnected.value})")
            val token = auth.getValidAccessToken()
            var failure: String? = null
            if (token == null) {
                Log.w(TAG, "no Spotify access token — the account isn't connected or the refresh failed")
                failure = "No Spotify access token. Connect (or reconnect) the account."
            }
            // Blocking network I/O; librespot applies its own connect timeout.
            val connected = token != null && withContext(Dispatchers.IO) {
                runCatching { librespot.connect(token, BuildConfig.SPOTIFY_CLIENT_ID, ::appToken) }
                    .onFailure {
                        Log.w(TAG, "librespot sign-in failed; using the Spotify app instead", it)
                        failure = it.message ?: it.javaClass.simpleName
                    }
                    .isSuccess
            }
            lastFailureAt = if (connected) 0L else SystemClock.elapsedRealtime()
            Log.i(TAG, if (connected) "librespot signed in — Spotify tracks will play through the DSP"
                else "librespot not signed in — retrying in ${RETRY_AFTER_MS / 1000}s")
            publish(if (connected) State.SIGNED_IN else State.FAILED, failure)
            connected
        }
    }

    /**
     * The app's access token for librespot's TokenProvider, when login5 won't
     * issue one. Called on librespot's own threads, never the main thread, so
     * blocking on a refresh here is fine.
     */
    private fun appToken(): TokenProvider.FallbackToken? {
        // A token the user pasted in wins: they supplied it precisely because
        // the granted one is not being honoured. Its lifetime is unknown, so
        // assume a short one and let a failure re-ask.
        auth.manualLibrespotToken.value?.takeIf { it.isNotBlank() }?.let {
            return TokenProvider.FallbackToken(it, 1800)
        }
        val token = runBlocking { auth.getValidAccessToken() } ?: return null
        val remainingS = ((auth.accessTokenExpiresAt.value - System.currentTimeMillis()) / 1000)
            .coerceIn(60, 3600).toInt()
        return TokenProvider.FallbackToken(token, remainingS)
    }

    private fun publish(state: State, error: String? = null) {
        _status.value = Status(
            state = state,
            username = if (state == State.SIGNED_IN) librespot.username else null,
            error = if (state == State.FAILED) error else null,
            hasStoredCredentials = librespot.hasStoredCredentials,
            playbackError = playbackFailure,
        )
    }

    private companion object {
        const val TAG = "SpotifyNativeSession"
        const val RETRY_AFTER_MS = 5 * 60_000L
        const val PLAYBACK_RETRY_AFTER_MS = 10 * 60_000L
    }
}
