package tf.monochrome.android.data.spotify

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import com.spotify.Authentication
import com.spotify.login5v3.Login5
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.core.TimeProvider
import xyz.gianlu.librespot.core.TokenProvider
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
        val startedAt = System.nanoTime()
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
            Log.i(TAG, "connect: logging in with the app's access token (${accessToken.length} chars)")
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
            Log.i(TAG, "connect: trying stored librespot credentials")
            try {
                Session.Builder(conf).setDeviceName(DEVICE_NAME).stored(credentialsFile).create()
            } catch (rejected: Exception) {
                // Only a credentials failure means the blob is bad: drop it and
                // fall back to the fresh token. Anything else (network, a 403
                // from some other endpoint) is not the blob's fault, and
                // deleting good credentials for it just hides the real error
                // behind a second one.
                if (!isCredentialsRejection(rejected)) throw rejected
                Log.w(TAG, "connect: stored credentials rejected by Spotify, using the access token", rejected)
                credentialsFile.delete()
                withToken()
            }
        } else {
            Log.i(TAG, "connect: no stored credentials yet")
            withToken()
        }
        Log.i(TAG, "connect: session up as '${newSession.username()}' in ${elapsedMs(startedAt)} ms, " +
            "credentials stored=${credentialsFile.canRead()}")

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
            Log.i(TAG, "connect: player ready (output=${PcmSink::class.java.name}, quality=VERY_HIGH)")
        } catch (error: Throwable) {
            Log.e(TAG, "connect: player construction failed", error)
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
        val startedAt = System.nanoTime()
        val pipe = PcmSinkRegistry.pipe
        // New generation first: it wakes the output thread if it is blocked
        // on a full pipe and makes it discard what it held for the old stream.
        val generation = pipe.newGeneration()
        if (loadedUri != spotifyUri) {
            Log.i(TAG, "openStream #$generation: loading $spotifyUri from ${startMs} ms (was ${loadedUri ?: "nothing"})")
            p.ready().get(READY_TIMEOUT_S, TimeUnit.SECONDS)
            // Synchronous: resolves the track, builds a fresh PlayerSession on
            // the sink and starts decoding from 0.
            p.load(spotifyUri, /* play = */ true, /* shuffle = */ false)
            loadedUri = spotifyUri
            if (startMs > 0) p.seek(startMs)
        } else {
            Log.i(TAG, "openStream #$generation: seeking $spotifyUri to ${startMs} ms")
            // Seeking also flushes the sink (PlayerSession.seekCurrent), which
            // is what discards audio decoded before the seek.
            p.seek(startMs)
        }
        p.play()
        // Whatever the output thread wrote between the new generation and the
        // seek is from the old position.
        pipe.clear()
        Log.i(TAG, "openStream #$generation: ready in ${elapsedMs(startedAt)} ms")
        return generation
    }

    @Synchronized
    fun release() {
        if (session != null || player != null) Log.i(TAG, "release")
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
        private fun finished(why: String) {
            Log.i(TAG, "event: $why — forgetting ${loadedUri ?: "nothing"}, pipe marked ended")
            synchronized(this@LibrespotPlayerWrapper) { loadedUri = null }
            PcmSinkRegistry.pipe.markEnded()
        }
        override fun onPlaybackEnded(player: Player) = finished("playback ended")
        override fun onPlaybackFailed(player: Player, e: Exception) {
            Log.e(TAG, "event: playback failed", e)
            finished("playback failed")
        }
        override fun onPanicState(player: Player) {
            Log.e(TAG, "event: panic state (librespot gave up on the track)")
            finished("panic")
        }
        override fun onContextChanged(player: Player, newUri: String) =
            logEvent("context changed to $newUri")
        override fun onTrackChanged(player: Player, id: PlayableId, metadata: MetadataWrapper?, userInitiated: Boolean) =
            logEvent("track changed to ${id.toSpotifyUri()} (${metadata?.name ?: "no metadata yet"}, " +
                "duration=${metadata?.duration() ?: -1} ms)")
        override fun onPlaybackPaused(player: Player, trackTime: Long) = logEvent("paused at $trackTime ms")
        override fun onPlaybackResumed(player: Player, trackTime: Long) = logEvent("resumed at $trackTime ms")
        override fun onTrackSeeked(player: Player, trackTime: Long) = logEvent("seeked to $trackTime ms")
        override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) =
            logEvent("metadata: '${metadata.name}' by ${metadata.artist}, ${metadata.duration()} ms")
        override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) {
            // Halted = librespot is starved of network data mid-track.
            if (halted) Log.w(TAG, "event: halted (buffering from Spotify) at $trackTime ms")
            else logEvent("un-halted at $trackTime ms")
        }
        override fun onInactiveSession(player: Player, timeout: Boolean) {
            // Another device took over the account's playback, or the session timed out.
            Log.w(TAG, "event: session inactive (timeout=$timeout) — another device may have taken playback")
        }
        override fun onVolumeChanged(player: Player, volume: Float) = logEvent("volume $volume")
        override fun onStartedLoading(player: Player) = logEvent("started loading")
        override fun onFinishedLoading(player: Player) = logEvent("finished loading")

        private fun logEvent(message: String) {
            Log.d(TAG, "event: $message")
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /**
     * Whether a stored-credentials login failed because the blob itself is no
     * good. The access point says so with [Session.SpotifyAuthenticationException];
     * login5 says so with INVALID_CREDENTIALS. login5 matters because the access
     * point can accept a blob that login5 then refuses — blobs stored before the
     * move off keymaster do exactly that — and Session.create() asks login5 for
     * a token straight after the access point lets it in.
     */
    private fun isCredentialsRejection(error: Throwable): Boolean =
        error is Session.SpotifyAuthenticationException ||
            (error is TokenProvider.Login5Exception && error.error == Login5.LoginError.INVALID_CREDENTIALS)

    private companion object {
        const val TAG = "LibrespotPlayer"
        const val DEVICE_NAME = "Tryptify"
        const val READY_TIMEOUT_S = 15L
    }
}
