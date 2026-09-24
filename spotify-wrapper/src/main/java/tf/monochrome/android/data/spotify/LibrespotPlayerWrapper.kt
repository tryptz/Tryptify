package tf.monochrome.android.data.spotify

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import com.spotify.Authentication
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.core.TimeProvider
import xyz.gianlu.librespot.metadata.PlayableId
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A headless Spotify client embedded in Tryptify (librespot-java). It decodes
 * the track into [PcmSink] → [PcmSinkRegistry.pipe], and [PcmSinkDataSource]
 * serves that pipe to ExoPlayer, so the song goes through the DSP chain,
 * AutoEQ, the mixer and the USB DAC like any other source.
 *
 * **ExoPlayer drives, librespot follows.** Nothing here mirrors play/pause:
 * the data source calls [openStream] when ExoPlayer opens (or, for a seek,
 * reopens) the track, and pausing is backpressure — ExoPlayer stops reading,
 * the pipe fills, librespot's output thread blocks. Pausing librespot itself
 * would be wrong twice over: ExoPlayer buffers far ahead of what is audible,
 * so the two players' positions never match, and librespot's pause calls the
 * sink's `stop()`.
 *
 * **Sign-in** reuses the app's Spotify PKCE access token (it needs the
 * `streaming` scope), presented as an `AUTHENTICATION_SPOTIFY_TOKEN` login.
 * librespot then stores a reusable credentials blob in app-private storage,
 * which later connects try first.
 */
@Singleton
class LibrespotPlayerWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var session: Session? = null
    private var player: Player? = null

    /** The `spotify:track:` URI librespot currently has loaded, if any. */
    private var loadedUri: String? = null

    val isConnected: Boolean
        get() = session?.isValid == true && player != null

    private val storageDir: File
        get() = context.getDir("spotify_native", Context.MODE_PRIVATE)

    /**
     * Connects if not already connected. Blocking network I/O — call off the
     * main thread. The device shows up as a Spotify Connect device on the
     * account while connected.
     */
    @Synchronized
    @Throws(Exception::class)
    fun connect(accessToken: String) {
        if (isConnected) return
        release()
        val credentialsFile = File(storageDir, "credentials.json")
        // Both paths must be set: librespot's defaults are relative to the
        // working directory, which on Android is "/" and not writable.
        val conf = Session.Configuration.Builder()
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .setCacheEnabled(true)
            .setCacheDir(File(storageDir, "cache").apply { mkdirs() })
            .setDoCacheCleanUp(true)
            // PING asks Spotify's own servers for the time, rather than NTP over UDP.
            .setTimeSynchronizationMethod(TimeProvider.Method.PING)
            .build()

        fun withToken(): Session {
            require(accessToken.isNotBlank()) { "Spotify access token is blank" }
            return Session.Builder(conf)
                .setDeviceName(DEVICE_NAME)
                .credentials(
                    Authentication.LoginCredentials.newBuilder()
                        .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                        .setAuthData(ByteString.copyFromUtf8(accessToken))
                        .build(),
                )
                .create()
        }

        val newSession = if (credentialsFile.canRead()) {
            runCatching {
                Session.Builder(conf).setDeviceName(DEVICE_NAME).stored(credentialsFile).create()
            }.getOrElse {
                // A stored blob can be revoked server-side; drop it and fall
                // back to the fresh token.
                Log.w(TAG, "Stored librespot credentials rejected, using the access token", it)
                credentialsFile.delete()
                withToken()
            }
        } else {
            withToken()
        }

        try {
            val newPlayer = Player(
                PlayerConfiguration.Builder()
                    .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                    .setOutputClass(PcmSink::class.java.name)
                    .setPreferredQuality(AudioQuality.VERY_HIGH)
                    // Gain is Tryptify's business (ReplayGain, the DSP chain):
                    // no normalisation and full volume, so the PCM arrives
                    // exactly as decoded.
                    .setEnableNormalisation(false)
                    .setInitialVolume(Player.VOLUME_MAX)
                    // ExoPlayer owns the queue. librespot must stop at the end
                    // of the one track it was given, not preload or autoplay
                    // something else into the pipe.
                    .setAutoplayEnabled(false)
                    .setPreloadEnabled(false)
                    .setCrossfadeDuration(0)
                    .build(),
                newSession,
            )
            newPlayer.addEventsListener(EndListener())
            session = newSession
            player = newPlayer
        } catch (error: Throwable) {
            runCatching { newSession.close() }
            throw error
        }
    }

    /**
     * Starts a fresh stream of [spotifyUri] from [startMs] into the pipe and
     * returns its generation for [PcmPipe.read]. Any reader of an older stream
     * is told it was superseded. Blocking (loading a track fetches its
     * metadata) — called from ExoPlayer's loader thread.
     */
    @Synchronized
    fun openStream(spotifyUri: String, startMs: Int): Long {
        val p = player ?: throw IllegalStateException("librespot is not connected")
        val pipe = PcmSinkRegistry.pipe
        // New generation first: it wakes the output thread if it is blocked
        // on a full pipe and makes it discard what it held for the old stream.
        val generation = pipe.newGeneration()
        if (loadedUri != spotifyUri) {
            p.ready().get(READY_TIMEOUT_S, TimeUnit.SECONDS)
            // Synchronous: resolves the track, builds a fresh PlayerSession on
            // the sink and starts decoding from 0.
            p.load(spotifyUri, /* play = */ true, /* shuffle = */ false)
            loadedUri = spotifyUri
            if (startMs > 0) p.seek(startMs)
        } else {
            // Seeking also flushes the sink (PlayerSession.seekCurrent), which
            // is what discards audio decoded before the seek.
            p.seek(startMs)
        }
        p.play()
        // Whatever the output thread wrote between the new generation and the
        // seek is from the old position.
        pipe.clear()
        return generation
    }

    @Synchronized
    fun release() {
        runCatching { player?.close() }
        player = null
        runCatching { session?.close() }
        session = null
        loadedUri = null
        PcmSinkRegistry.pipe.newGeneration()
    }

    /**
     * Tells the pipe when the loaded track has been fully decoded. The track
     * is also forgotten: a finished librespot session cannot seek, so if
     * ExoPlayer reopens it (repeat-one, or a seek back past its buffer) the
     * next [openStream] must load it again rather than seek a dead session.
     */
    private inner class EndListener : Player.EventsListener {
        private fun finished() {
            synchronized(this@LibrespotPlayerWrapper) { loadedUri = null }
            PcmSinkRegistry.pipe.markEnded()
        }
        override fun onPlaybackEnded(player: Player) = finished()
        override fun onPlaybackFailed(player: Player, e: Exception) {
            Log.w(TAG, "librespot playback failed", e)
            finished()
        }
        override fun onContextChanged(player: Player, newUri: String) = Unit
        override fun onTrackChanged(player: Player, id: PlayableId, metadata: MetadataWrapper?, userInitiated: Boolean) = Unit
        override fun onPlaybackPaused(player: Player, trackTime: Long) = Unit
        override fun onPlaybackResumed(player: Player, trackTime: Long) = Unit
        override fun onTrackSeeked(player: Player, trackTime: Long) = Unit
        override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) = Unit
        override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) = Unit
        override fun onInactiveSession(player: Player, timeout: Boolean) = Unit
        override fun onVolumeChanged(player: Player, volume: Float) = Unit
        override fun onPanicState(player: Player) = finished()
        override fun onStartedLoading(player: Player) = Unit
        override fun onFinishedLoading(player: Player) = Unit
    }

    private companion object {
        const val TAG = "LibrespotPlayer"
        const val DEVICE_NAME = "Tryptify"
        const val READY_TIMEOUT_S = 15L
    }
}
