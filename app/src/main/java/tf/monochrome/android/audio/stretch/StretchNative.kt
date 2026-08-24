package tf.monochrome.android.audio.stretch

import java.nio.ByteBuffer

/**
 * JNI bridge to the vendored signalsmith-stretch engine (see
 * `third_party/signalsmith-stretch`), which transposes without moving the
 * tempo — the thing neither resampling nor Sonic's speech-tuned WSOLA can do
 * for music.
 *
 * [isAvailable] is false when the library is missing, which is the normal case
 * in JVM unit tests and the safe case on a device where the ABI did not ship.
 * Callers must treat that as "no pitch shift" and pass audio through, never as
 * an error: an unavailable effect has to stay silent, not break playback.
 */
object StretchNative {

    @Volatile private var available: Boolean = false

    init {
        available = try {
            System.loadLibrary("monochrome_stretch")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    val isAvailable: Boolean get() = available

    /** Returns 0 if the engine could not be created. */
    external fun nativeCreate(channels: Int, sampleRate: Int): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeSetSemitones(handle: Long, semitones: Float)

    external fun nativeReset(handle: Long)

    /**
     * Round-trip latency in frames. This is how far behind the audible pitch
     * change lags the moment [nativeSetSemitones] is called, which is what the
     * AutoEQ pre-warp glide has to be matched against.
     */
    external fun nativeLatencyFrames(handle: Long): Int

    /** Largest frame count [nativeProcess] accepts in one call. */
    external fun nativeMaxBlockFrames(): Int

    /**
     * Transposes [frames] of interleaved float audio. Both buffers must be
     * direct — the native side takes their addresses rather than copying.
     */
    external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int,
    ): Int
}
