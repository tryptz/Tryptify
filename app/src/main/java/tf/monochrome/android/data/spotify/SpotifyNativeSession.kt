package tf.monochrome.android.data.spotify

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.auth.SpotifyAuthManager
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
 * queue of Spotify tracks does not retry a doomed login per track.
 */
@Singleton
class SpotifyNativeSession @Inject constructor(
    private val auth: SpotifyAuthManager,
    private val librespot: LibrespotPlayerWrapper,
) {
    private val mutex = Mutex()
    private var lastFailureAt = 0L

    suspend fun ensureConnected(): Boolean {
        if (librespot.isConnected) return true
        return mutex.withLock {
            if (librespot.isConnected) return@withLock true
            if (lastFailureAt != 0L && SystemClock.elapsedRealtime() - lastFailureAt < RETRY_AFTER_MS) {
                return@withLock false
            }
            val token = auth.getValidAccessToken()
            // Blocking network I/O; librespot applies its own connect timeout.
            val connected = token != null && withContext(Dispatchers.IO) {
                runCatching { librespot.connect(token) }
                    .onFailure { Log.w(TAG, "librespot sign-in failed; using the Spotify app instead", it) }
                    .isSuccess
            }
            lastFailureAt = if (connected) 0L else SystemClock.elapsedRealtime()
            connected
        }
    }

    private companion object {
        const val TAG = "SpotifyNativeSession"
        const val RETRY_AFTER_MS = 5 * 60_000L
    }
}
