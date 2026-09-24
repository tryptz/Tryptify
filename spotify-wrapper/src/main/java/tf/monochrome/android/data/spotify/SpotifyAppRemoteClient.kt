package tf.monochrome.android.data.spotify

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tf.monochrome.android.data.spotify.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin coroutine wrapper over the Spotify App Remote SDK: the Spotify app does
 * the playing, this only tells it what to play and listens to what it reports.
 *
 * Authorization reuses the app's Spotify connection. The client id and
 * redirect URI are the same ones [SpotifyAuthManager][tf.monochrome.android.data.auth.SpotifyAuthManager]
 * signs in with, and that flow now also asks for `app-remote-control`. An
 * account connected before that scope existed has not granted it yet, so
 * [connect] still passes `showAuthView(true)`: the Spotify app then shows its
 * own one-time consent instead of failing.
 *
 * Requirements the SDK enforces, surfaced as failures from [play] and friends:
 * the Spotify app must be installed and logged in, the account must be Premium
 * to start a specific track, and this app's package name + signing SHA-1 must
 * be registered on the client id in the Spotify Developer Dashboard.
 */
@Singleton
class SpotifyAppRemoteClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectMutex = Mutex()
    private var remote: SpotifyAppRemote? = null
    private var stateSubscription: Subscription<PlayerState>? = null

    private val _playerState = MutableStateFlow<PlayerState?>(null)

    /** Latest state the Spotify app reported; null until connected. */
    val playerState: StateFlow<PlayerState?> = _playerState.asStateFlow()

    fun isSpotifyInstalled(): Boolean = SpotifyAppRemote.isSpotifyInstalled(context)

    suspend fun play(spotifyUri: String): Result<Unit> = command { it.playerApi.play(spotifyUri) }

    suspend fun resume(): Result<Unit> = command { it.playerApi.resume() }

    suspend fun pause(): Result<Unit> = command { it.playerApi.pause() }

    suspend fun seekTo(positionMs: Long): Result<Unit> = command { it.playerApi.seekTo(positionMs) }

    /** Drops the connection and the state subscription. Safe to call when not connected. */
    fun disconnect() {
        stateSubscription?.cancel()
        stateSubscription = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _playerState.value = null
    }

    private suspend fun <T> command(call: (SpotifyAppRemote) -> CallResult<T>): Result<Unit> = runCatching {
        val connected = connect()
        withContext(Dispatchers.Main) {
            withTimeout(COMMAND_TIMEOUT_MS) { call(connected).awaitResult() }
        }
        Unit
    }

    /**
     * The live connection, opening one if needed. Serialised so a burst of
     * commands (play then seek, say) shares one connection attempt rather than
     * racing several binds to the Spotify app.
     */
    private suspend fun connect(): SpotifyAppRemote = connectMutex.withLock {
        remote?.takeIf { it.isConnected }?.let { return it }
        // A dropped connection leaves a dead subscription behind; clear it so
        // the new one below is the only listener.
        stateSubscription?.cancel()
        stateSubscription = null

        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()

        val connected = withContext(Dispatchers.Main) {
            // The first connect may sit on Spotify's consent screen, so this
            // budget is generous; after that it is a local bind and quick.
            withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                        override fun onConnected(appRemote: SpotifyAppRemote) {
                            if (cont.isActive) cont.resume(appRemote)
                            else SpotifyAppRemote.disconnect(appRemote)
                        }

                        override fun onFailure(error: Throwable) {
                            Log.w(TAG, "App Remote connection failed", error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    })
                }
            }
        }
        remote = connected
        stateSubscription = connected.playerApi.subscribeToPlayerState().also { sub ->
            sub.setEventCallback { state -> _playerState.value = state }
        }
        connected
    }

    // Not named `await`: CallResult already has a *blocking* member await(),
    // and a member always beats an extension — that name would silently block
    // the main thread on the Spotify IPC.
    private suspend fun <T> CallResult<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        setResultCallback { value -> if (cont.isActive) cont.resume(value) }
        setErrorCallback { error -> if (cont.isActive) cont.resumeWithException(error) }
    }

    private companion object {
        const val TAG = "SpotifyAppRemote"
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val COMMAND_TIMEOUT_MS = 10_000L
    }
}
