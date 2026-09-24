package tf.monochrome.android.data.spotify

import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A headless Spotify client embedded in Tryptify (librespot-java): signs in
 * with the user's Premium credentials and streams the decrypted track audio
 * into [PcmSink], which [PcmSinkDataSource] feeds to ExoPlayer. The song then
 * plays inside Tryptify — through the DSP chain, AutoEQ, the mixer and the
 * USB DAC — with no Spotify app on the device and no silent-shadow mirroring.
 *
 * Credentials: the same Spotify account the app signs in with (see
 * SpotifyAuthManager); librespot needs the raw username/password once, then
 * stores its own credentials in its cache.
 */
class LibrespotPlayerWrapper {

    private var session: Session? = null
    private var player: Player? = null

    val isConnected: Boolean get() = session?.isValid ?: false

    /** The live sink for the currently loaded track; null between tracks. */
    private val activeSink = PcmSink()

    init {
        PcmSinkRegistry.sink = activeSink
    }

    /**
     * Connects (or reconnects). Blocking — call off the main thread. librespot
     * authenticates directly against Spotify's servers, so the device shows up
     * as a Spotify Connect device for the account.
     */
    @Synchronized
    @Throws(Exception::class)
    fun connect(username: String, password: String) {
        if (isConnected) return
        val conf = Session.Configuration.Builder()
            .setStoreCredentials(true)
            .setCacheEnabled(true)
            .build()

        session = Session.Builder(conf)
            .userPass(username, password)
            .create()

        val s = session!!
        player = Player(
            PlayerConfiguration.Builder()
                .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                .setOutputClass(PcmSink::class.java.name)
                .setPreloadEnabled(true)
                .build(),
            s,
        )
    }

    /** Streams the given `spotify:track:` URI; audio flows to [PcmSinkRegistry]. */
    fun loadAndPlay(trackUri: String) {
        val p = player ?: error("librespot not connected")
        p.load(trackUri, true, false)
    }

    fun pause() = player?.pause()
    fun resume() = player?.play()

    fun release() {
        runCatching { player?.close() }
        player = null
        runCatching { session?.close() }
        session = null
        PcmSinkRegistry.sink = null
    }
}
