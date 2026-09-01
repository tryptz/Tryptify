package tf.monochrome.android.visualizer

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectMAudioFrame(
    val samples: FloatArray,
    val channelCount: Int,
    val sampleRate: Int,
    val timestampMs: Long
)

@Singleton
class ProjectMAudioBus @Inject constructor() {
    private val pendingFrames = ConcurrentLinkedQueue<ProjectMAudioFrame>()
    private val latestTimestampMs = AtomicLong(0L)
    private val sampleSnapshot = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val subscriberCount = AtomicInteger(0)

    /**
     * How long audio is held back before the visualizer is allowed to see it.
     *
     * The tap sits in the playback chain, upstream of the output device, so the
     * visualizer sees a moment of audio before it is heard. Over a wire that
     * head start is small enough to miss; over Bluetooth the codec and the
     * receiver's own buffering put it in the low hundreds of milliseconds, and
     * the picture visibly runs ahead of the sound. Delaying the visualizer's
     * copy is the only side that can move -- the audio itself must not be.
     *
     * Volatile rather than locked: written from a settings observer, read on
     * the audio thread, and a frame either side of a change does not matter.
     */
    @Volatile
    private var delayMs: Int = 0

    fun setDelayMs(value: Int) {
        delayMs = value.coerceIn(0, MAX_DELAY_MS)
    }

    /**
     * Reference-count subscribers (visualizer engine + waveform overlay). Callers
     * must pair acquire/release. When zero, the audio tap skips the per-frame
     * PCM→float conversion entirely so the audio render thread doesn't do work
     * no one is consuming.
     */
    fun acquire() { subscriberCount.incrementAndGet() }
    fun release() { subscriberCount.updateAndGet { (it - 1).coerceAtLeast(0) } }
    fun hasSubscribers(): Boolean = subscriberCount.get() > 0

    fun publish(samples: FloatArray, channelCount: Int, sampleRate: Int) {
        val now = System.currentTimeMillis()
        latestTimestampMs.set(now)
        // Deliberately not delayed. This feeds the waveform overlay, which draws
        // on its own Canvas whether or not the projectM engine is running, so
        // holding it back here would freeze the overlay whenever nothing was
        // draining the queue.
        sampleSnapshot.set(samples)
        pendingFrames.add(
            ProjectMAudioFrame(
                samples = samples,
                channelCount = channelCount,
                sampleRate = sampleRate,
                timestampMs = now
            )
        )
        // Bounded by age rather than by count, because the queue now has to be
        // long enough to hold the delay: a fixed eight frames is a fraction of a
        // second at one buffer each and would throw away audio before it came
        // due. Trimming by age also drops the O(n) size() this ran on the audio
        // thread for every buffer.
        val oldestWanted = now - (delayMs + RETAIN_MS)
        while (true) {
            val head = pendingFrames.peek() ?: break
            if (head.timestampMs >= oldestWanted) break
            pendingFrames.poll()
        }
    }

    /**
     * Frames that have come due, oldest first.
     *
     * With no delay set this is every frame accumulated since the last render,
     * exactly as before. With one, a frame is withheld until it has been in the
     * queue that long, so what the visualizer draws is what is leaving the
     * speaker rather than what will leave it a quarter of a second from now.
     */
    fun drainAll(): List<ProjectMAudioFrame> {
        val dueBefore = System.currentTimeMillis() - delayMs
        val frames = mutableListOf<ProjectMAudioFrame>()
        while (true) {
            val head = pendingFrames.peek() ?: break
            if (head.timestampMs > dueBefore) break
            // Take what the poll actually removed, not what the peek saw.
            //
            // publish() trims by age on the audio thread and polls too, so
            // between this peek and this poll the head can change. Adding the
            // peeked frame then meant adding one that was already gone while
            // silently destroying the one that was removed in its place -- a
            // buffer that never reached projectM. Using the polled value costs
            // at worst a frame drawn one position early, which the delay
            // window absorbs; the old shape lost audio outright.
            val taken = pendingFrames.poll() ?: break
            frames.add(taken)
        }
        return frames
    }

    fun latestTimestampMs(): Long = latestTimestampMs.get()

    /** Returns the latest audio samples without consuming them (for waveform overlay). */
    fun peekSamples(): FloatArray? = sampleSnapshot.get()

    fun clear() {
        pendingFrames.clear()
        sampleSnapshot.set(null)
        latestTimestampMs.set(0L)
    }

    companion object {
        /** Past this the picture is so far behind that it reads as a fault. */
        const val MAX_DELAY_MS = 500

        /** Headroom kept beyond the delay so a late render still finds its audio. */
        private const val RETAIN_MS = 250
    }
}
