package tf.monochrome.android.data.spotify

/**
 * The process-wide meeting point of librespot and ExoPlayer. librespot builds
 * its [PcmSink] by reflection from a class name, so it cannot be handed an
 * instance; both sides find the one [pipe] here instead. One Spotify track
 * streams at a time, so one pipe is enough.
 */
object PcmSinkRegistry {

    /** librespot's output format: 44.1 kHz, 16-bit signed little-endian, stereo. */
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = CHANNELS * 2

    /**
     * Two seconds of audio. ExoPlayer drains it far faster than real time while
     * buffering, so this only has to cover jitter between the two threads, not
     * hold the song — ExoPlayer's own 120 s buffer does that.
     */
    private const val CAPACITY_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 2

    val pipe = PcmPipe(CAPACITY_BYTES)

    /**
     * False once librespot has started its output in a format other than the
     * one above. The WAV header [PcmSinkDataSource] writes would then describe
     * the audio wrongly, so it refuses to serve rather than play noise.
     */
    @Volatile
    var formatSupported: Boolean = true
}
