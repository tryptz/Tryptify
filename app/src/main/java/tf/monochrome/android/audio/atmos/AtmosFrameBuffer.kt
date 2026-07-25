package tf.monochrome.android.audio.atmos

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small, time-keyed ring of raw E-AC-3 access units captured by the sample tap
 * ([AtmosTapMediaSourceFactory]) before NextLib's FfmpegAudioRenderer decodes
 * them. The [AtmosAudioProcessor] (P4) reads back the frame that covers each
 * decoded-PCM buffer's presentation time and hands it to
 * [AtmosNative.nativeProcessFrame] together with the bed PCM.
 *
 * Single-producer (the loading thread, via the tapped `SampleStream.readData`) /
 * single-consumer (the audio thread). Bounded so it never grows without limit;
 * the consumer trails the producer by only the decoder's latency, so a small
 * window is plenty. Inert unless an E-AC-3 track is playing.
 */
@Singleton
class AtmosFrameBuffer @Inject constructor() {

    /** A raw E-AC-3 access unit and the presentation time it was tapped at. */
    class TappedFrame(@JvmField val timeUs: Long, @JvmField val bytes: ByteArray)

    private val lock = Any()
    private val frames = ArrayDeque<TappedFrame>()

    /** Records a raw E-AC-3 access unit at its presentation time. */
    fun put(timeUs: Long, bytes: ByteArray) {
        synchronized(lock) {
            frames.addLast(TappedFrame(timeUs, bytes))
            while (frames.size > MAX_FRAMES) frames.removeFirst()
        }
    }

    /**
     * The raw frame whose presentation window covers [timeUs] — the newest entry
     * with `entry.timeUs <= timeUs` — or null if none is buffered. Entries older
     * than the returned one are dropped (playback only moves forward within a
     * track; seeks call [clear]). The entry's own [TappedFrame.timeUs] lets the
     * consumer detect timestamp discontinuities and re-anchor its clock.
     */
    fun frameForTime(timeUs: Long): TappedFrame? {
        synchronized(lock) {
            var chosen: TappedFrame? = null
            while (frames.isNotEmpty() && frames.first().timeUs <= timeUs) {
                chosen = frames.removeFirst()
            }
            // Put the chosen frame back so a slightly-later query for the same
            // frame (buffers can span a frame boundary) still finds it.
            if (chosen != null) frames.addFirst(chosen)
            return chosen
        }
    }

    /** Presentation time of the oldest buffered frame, or [Long.MIN_VALUE]. */
    fun peekTimeUs(): Long =
        synchronized(lock) { frames.firstOrNull()?.timeUs ?: Long.MIN_VALUE }

    /** Number of buffered frames (for lookahead diagnostics). */
    fun size(): Int = synchronized(lock) { frames.size }

    /** Drops all buffered frames — call on seek / track transition / flush. */
    fun clear() {
        synchronized(lock) { frames.clear() }
    }

    private companion object {
        // ~128 frames * 1536 samples / 48 kHz ≈ 4 s of lookahead; far more than
        // the decoder's latency, and each frame is a few hundred bytes.
        const val MAX_FRAMES = 128
    }
}
