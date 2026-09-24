package tf.monochrome.android.data.spotify

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.auth.SpotifyAuthManager

/** Owns the in-process Spotify decoder and mirrors Tryptify transport into it. */
@Singleton
@OptIn(UnstableApi::class)
class SpotifyNativePlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: SpotifyAuthManager,
) {
    private val mutex = Mutex()
    private val wrapper = LibrespotPlayerWrapper()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageDir get() = context.getDir("spotify_native", Context.MODE_PRIVATE)

    private var attachedPlayer: Player? = null
    private var listener: Player.Listener? = null

    /** Connects librespot and begins decoding into [PcmSinkRegistry]. */
    suspend fun prepare(spotifyUri: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val token = auth.getValidAccessToken()
                    ?: error("Connect Spotify again to enable native playback")
                if (!wrapper.isConnected) wrapper.connect(token, storageDir)
                wrapper.loadAndPlay(spotifyUri)
                Log.i(TAG, "NATIVE_PCM_ACTIVE uri=$spotifyUri")
            }
            Unit
        }.onFailure { Log.w(TAG, "Native Spotify playback unavailable", it) }
    }

    /** Stops the current track and releases the sink immediately (for track switch / stop). */
    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            wrapper.release()
            PcmSinkRegistry.sink?.signalEos()
            PcmSinkRegistry.sink = null
        }
    }

    fun attach(player: Player, scope: CoroutineScope) {
        if (attachedPlayer === player) return
        detach()
        val transportListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scope.launch(Dispatchers.IO) {
                    mutex.withLock {
                        if (!isNative(mediaItem)) {
                            wrapper.release()
                            PcmSinkRegistry.sink?.signalEos()
                        } else if (!player.playWhenReady) {
                            wrapper.pause()
                        } else {
                            wrapper.resume()
                        }
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isNative(player.currentMediaItem)) return
                scope.launch(Dispatchers.IO) {
                    mutex.withLock {
                        if (playWhenReady) wrapper.resume() else wrapper.pause()
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK || !isNative(player.currentMediaItem)) return
                scope.launch(Dispatchers.IO) {
                    mutex.withLock { wrapper.seekTo(newPosition.positionMs) }
                }
            }
        }
        attachedPlayer = player
        listener = transportListener
        player.addListener(transportListener)
    }

    fun detach() {
        listener?.let { attachedPlayer?.removeListener(it) }
        listener = null
        attachedPlayer = null
    }

    fun release() {
        detach()
        ioScope.launch {
            mutex.withLock {
                wrapper.release()
                PcmSinkRegistry.sink?.signalEos()
                PcmSinkRegistry.sink = null
            }
        }
    }

    private fun isNative(item: MediaItem?): Boolean =
        item?.localConfiguration?.uri?.toString()?.let(SpotifyPcmUri::matches) == true

    private companion object {
        const val TAG = "SpotifyNative"
    }
}