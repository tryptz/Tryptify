package tf.monochrome.android.data.spotify

/**
 * Holds the process-wide sink librespot's player writes into and ExoPlayer's
 * [PcmSinkDataSource] reads from. One Spotify track plays at a time, so one
 * global slot is enough — the wrapper points [sink] at the active player's
 * sink when a Spotify track becomes current and clears it otherwise.
 */
object PcmSinkRegistry {
    @Volatile
    var sink: PcmSink? = null
}
