package tf.monochrome.android.data.spotify

import com.google.protobuf.ByteString
import com.spotify.Authentication
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.File

/**
 * A headless Spotify client embedded in Tryptify (librespot-java): signs in
 * with the user's Premium credentials and streams the decrypted track audio
 * into [PcmSink], which [PcmSinkDataSource] feeds to ExoPlayer. The song then
 * plays inside Tryptify — through the DSP chain, AutoEQ, the mixer and the
 * USB DAC — with no Spotify app on the device and no silent-shadow mirroring.
 *
 * The first connection accepts the access token from the app's existing PKCE
 * sign-in, then librespot stores its reusable credential blob in app-private
 * storage. No second login form and no raw Spotify password are required.
 */
class LibrespotPlayerWrapper {

    private var session: Session? = null
    private var player: Player? = null

    val isConnected: Boolean get() = session?.isValid ?: false

    /**
     * Connects (or reconnects). Blocking — call off the main thread. librespot
     * authenticates directly against Spotify's servers, so the device shows up
     * as a Spotify Connect device for the account.
     */
    @Synchronized
    @Throws(Exception::class)
    fun connect(accessToken: String, storageDir: File) {
        if (isConnected) return
        require(accessToken.isNotBlank()) { "Spotify access token is blank" }
        storageDir.mkdirs()
        val credentialsFile = File(storageDir, "credentials.json")
        val conf = Session.Configuration.Builder()
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .setCacheEnabled(true)
            .setCacheDir(File(storageDir, "cache").apply { mkdirs() })
            .build()

        fun tokenBuilder() = Session.Builder(conf).credentials(
            Authentication.LoginCredentials.newBuilder()
                .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                .setAuthData(ByteString.copyFromUtf8(accessToken))
                .build(),
        )

        val connected = if (credentialsFile.canRead()) {
            runCatching { Session.Builder(conf).stored(credentialsFile).create() }
                .getOrElse {
                    // Stored blobs can be revoked server-side. Discard one bad
                    // blob and retry with the app's freshly refreshed token.
                    credentialsFile.delete()
                    tokenBuilder().create()
                }
        } else {
            tokenBuilder().create()
        }

        try {
            val createdPlayer = Player(
                PlayerConfiguration.Builder()
                    .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                    .setOutputClass(PcmSink::class.java.name)
                    .setPreloadEnabled(true)
                    .build(),
                connected,
            )
            session = connected
            player = createdPlayer
        } catch (error: Throwable) {
            runCatching { connected.close() }
            throw error
        }
    }

    /** Streams the given `spotify:track:` URI; audio flows to [PcmSinkRegistry]. */
    fun loadAndPlay(trackUri: String) {
        val p = player ?: error("librespot not connected")
        p.load(trackUri, true, false)
    }

    fun pause() = player?.pause()
    fun resume() = player?.play()
    fun seekTo(positionMs: Long) = player?.seek(positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    fun release() {
        runCatching { player?.close() }
        player = null
        runCatching { session?.close() }
        session = null
        PcmSinkRegistry.sink = null
    }
}
