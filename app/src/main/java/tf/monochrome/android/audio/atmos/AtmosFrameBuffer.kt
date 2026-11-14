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

    /**
     * How many Atmos objects the stream being read carries (its OAMD count,
     * the LFE included), or 0 for none or not known. Read off the frames
     * themselves, so it is known whether or not anything renders them: with
     * the binaural render off the pipeline sheet said "6 (5.1)" and nothing
     * showed the stream was an Atmos one.
     */
    private val _streamObjects = kotlinx.coroutines.flow.MutableStateFlow(0)
    val streamObjects: kotlinx.coroutines.flow.StateFlow<Int> = _streamObjects
    private var sinceProbe = 0

    private val lock = Any()
    private val frames = ArrayDeque<TappedFrame>()

    /** Whether a frame at [timeUs] carries on from [last] (no seek, no other song between). */
    private fun follows(last: TappedFrame, timeUs: Long) =
        timeUs >= last.timeUs && timeUs - last.timeUs <= CONTINUOUS_US

    /** Records a raw E-AC-3 access unit at its presentation time. */
    fun put(timeUs: Long, bytes: ByteArray) {
        // Every frame of an Atmos stream carries the count; one in 32 (a
        // second) is plenty, and a stream's first frame is always looked at.
        val before = synchronized(lock) { frames.lastOrNull() }
        if (before == null || !follows(before, timeUs) || ++sinceProbe >= PROBE_EVERY) {
            sinceProbe = 0
            val objects = if (AtmosNative.isAvailable) {
                runCatching { AtmosNative.nativeParseAtmos(bytes) }.getOrDefault(-1)
            } else {
                -1
            }
            _streamObjects.value = if (objects > 0) objects else 0
        }
        synchronized(lock) {
            // A frame that does not follow the last one (a seek, another
            // song) starts afresh: what is buffered belongs to somewhere else.
            val last = frames.lastOrNull()
            if (last != null && !follows(last, timeUs)) {
                android.util.Log.i("AtmosTap", "jump ${last.timeUs}us -> ${timeUs}us: dropped ${frames.size} tapped frames")
                frames.clear()
            }
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
        const val PROBE_EVERY = 32
        // Frames are 32 ms apart; past a quarter of a second it is a jump.
        const val CONTINUOUS_US = 250_000L
    }
}
