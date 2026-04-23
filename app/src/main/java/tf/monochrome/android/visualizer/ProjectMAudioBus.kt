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
        sampleSnapshot.set(samples)
        pendingFrames.add(
            ProjectMAudioFrame(
                samples = samples,
                channelCount = channelCount,
                sampleRate = sampleRate,
                timestampMs = now
            )
        )
        // Prevent unbounded growth — keep at most 8 buffered frames
        while (pendingFrames.size > 8) pendingFrames.poll()
    }

    /** Drain all accumulated frames since the last render. */
    fun drainAll(): List<ProjectMAudioFrame> {
        val frames = mutableListOf<ProjectMAudioFrame>()
        while (true) {
            frames.add(pendingFrames.poll() ?: break)
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
}
