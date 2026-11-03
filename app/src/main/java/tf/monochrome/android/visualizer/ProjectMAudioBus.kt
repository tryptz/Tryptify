package tf.monochrome.android.visualizer

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectMAudioFrame(
    val samples: FloatArray,
    val channelCount: Int,
    val sampleRate: Int,
    val timestampMs: Long
)

/**
 * Audio on its way from the playback chain to the visualizer.
 *
 * The audio thread ([publishPcm]) converts each buffer straight into one float
 * ring and notes where it lies; the render thread ([drainDue]) takes the
 * frames that have come due, oldest first. This used to cost a float array, a
 * frame object and a queue node per buffer for as long as the visualizer was
 * up. The ring is the same queue without the garbage: the same frames in the
 * same order, withheld by the same delay and dropped at the same age.
 *
 * One writer, the playback thread (both chains that carry the projectM tap run
 * there), and one reader at a time, the render thread under the engine's
 * lock. Lock-free between them: frames are numbered, [head] is the oldest one
 * still held, and whichever side moves it does so by compare-and-set. The
 * reader copies a frame out before claiming it, so if the writer dropped it in
 * the meantime (too old, or its room needed) the claim fails and the copy,
 * which may hold some of what overwrote it, is thrown away instead of drawn.
 */
@Singleton
class ProjectMAudioBus @Inject constructor() {
    private val latestTimestampMs = AtomicLong(0L)
    private val subscriberCount = AtomicInteger(0)

    // ── The ring: written only by the audio thread ──────────────────────

    /**
     * The samples. Replaced by a bigger one when a format needs more room; a
     * frame names the array it was written to, so frames still in the old
     * one stay readable. Empty while nobody listens.
     */
    private var ring = FloatArray(0)

    /**
     * Where the next sample goes in [ring], counted from its start without
     * wrapping. A frame never straddles the end: one that would is written at
     * the start of the next lap instead, so every frame is one contiguous run.
     */
    private var ringWrite = 0L

    /** A ring made off the audio thread, taken up at the next publish. */
    @Volatile private var preparedRing: FloatArray? = null

    /** Samples a second in the format the tap last announced; 0 before it has. */
    @Volatile private var samplesPerSecond = 0

    // Frame slots: frame n lives in slot n % MAX_FRAMES.
    private val slotRing = arrayOfNulls<FloatArray>(MAX_FRAMES)
    private val slotStart = LongArray(MAX_FRAMES)
    private val slotLength = IntArray(MAX_FRAMES)
    private val slotChannels = IntArray(MAX_FRAMES)
    private val slotRate = IntArray(MAX_FRAMES)
    private val slotTime = LongArray(MAX_FRAMES)

    /** The oldest frame still held. Moved by both sides, only by compare-and-set. */
    private val head = AtomicLong(0L)

    /** Frames published. Written by the audio thread once a frame's slot is filled. */
    @Volatile private var tail = 0L

    // ── The waveform snapshot ───────────────────────────────────────────

    /**
     * Copies of the latest buffers for the waveform overlay, used in turn, so
     * the one it was handed is not rewritten for two more buffers: far longer
     * than drawing it takes. Each is a buffer's exact length, since the
     * overlay spreads its points over the whole array, so one is made again
     * only when the length changes.
     */
    private val snapshots = arrayOfNulls<FloatArray>(SNAPSHOTS)
    private var nextSnapshot = 0
    private val sampleSnapshot = AtomicReference<FloatArray?>(null)

    /** The reader's copy of the frame it is handing on. Render thread only. */
    private var drainScratch = FloatArray(0)

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
     *
     * A subscriber also makes the ring when the stream's format is known and
     * there is none big enough, so that the audio thread does not have to.
     */
    fun acquire() {
        subscriberCount.incrementAndGet()
        val needed = ringSamplesFor(samplesPerSecond)
        // [ring] belongs to the audio thread and may read stale here; the
        // worst that does is make a ring that is then not needed.
        if (needed > 0 && preparedRing == null && ring.size < needed) preparedRing = FloatArray(needed)
    }

    fun release() { subscriberCount.updateAndGet { (it - 1).coerceAtLeast(0) } }
    fun hasSubscribers(): Boolean = subscriberCount.get() > 0

    /**
     * The format the tap will publish in, from its flush on the audio thread.
     * With someone listening, a ring too small for it is made here, as a track
     * starts, rather than in the middle of one.
     */
    fun prepare(sampleRate: Int, channelCount: Int) {
        val perSecond = sampleRate.coerceAtLeast(1) * channelCount.coerceAtLeast(1)
        samplesPerSecond = perSecond
        if (!hasSubscribers()) return
        val needed = ringSamplesFor(perSecond)
        if ((preparedRing?.size ?: ring.size) < needed) preparedRing = FloatArray(needed)
    }

    /**
     * A buffer arrived with nobody listening. Audio thread only. The ring and
     * whatever it still holds are let go: audio from before the visualizer
     * went away is no use to the next one.
     */
    fun idle() {
        if (ring.isEmpty() && preparedRing == null) return
        advanceHeadTo(tail)
        ring = FloatArray(0)
        ringWrite = 0L
        preparedRing = null
        slotRing.fill(null)
    }

    /**
     * One buffer of PCM from the tap, converted to float and clamped as it
     * always was. Audio thread only.
     */
    fun publishPcm(buffer: ByteBuffer, encoding: Int, channelCount: Int, sampleRate: Int) {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_8BIT -> 1
            else -> return
        }
        val count = buffer.remaining() / bytesPerSample
        if (count <= 0) return
        // Media3's PCM is in native order, little endian on every Android ABI,
        // so this is the buffer itself; the view is for a platform where not.
        val src = if (buffer.order() == ByteOrder.LITTLE_ENDIAN) {
            buffer
        } else {
            buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        }
        val start = src.position()
        val dst = reserve(count, channelCount, sampleRate)
        val off = (ringWrite % dst.size).toInt()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) {
                dst[off + i] = (src.getShort(start + i * 2) / 32768f).coerceIn(-1f, 1f)
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) {
                dst[off + i] = src.getFloat(start + i * 4).coerceIn(-1f, 1f)
            }
            else -> for (i in 0 until count) {
                dst[off + i] = ((src.get(start + i).toInt() and 0xFF) - 128) / 128f
            }
        }
        commit(dst, off, count, channelCount, sampleRate)
    }

    /** Float samples as they are, for callers that already have them. Audio thread only. */
    fun publish(samples: FloatArray, channelCount: Int, sampleRate: Int) {
        if (samples.isEmpty()) return
        val dst = reserve(samples.size, channelCount, sampleRate)
        val off = (ringWrite % dst.size).toInt()
        System.arraycopy(samples, 0, dst, off, samples.size)
        commit(dst, off, samples.size, channelCount, sampleRate)
    }

    /**
     * Room for [count] contiguous samples at [ringWrite]: a big enough ring,
     * the oldest frames dropped if they occupy the space, and a free slot.
     */
    private fun reserve(count: Int, channelCount: Int, sampleRate: Int): FloatArray {
        preparedRing?.let { fresh ->
            preparedRing = null
            if (fresh.size > ring.size) adopt(fresh)
        }
        val needed = maxOf(
            count,
            ringSamplesFor(sampleRate.coerceAtLeast(1) * channelCount.coerceAtLeast(1)),
        )
        // Nothing made one in time: the visualizer opened before the tap
        // announced a format, or a format grew. Once per format at most.
        if (ring.size < needed) adopt(FloatArray(needed))

        val cap = ring.size
        val lapOffset = (ringWrite % cap).toInt()
        if (lapOffset + count > cap) ringWrite += (cap - lapOffset).toLong()

        // Frames of this ring the new one would overwrite: the ones that start
        // less than a lap before its end. Frames are dropped oldest first, so
        // any still in a replaced ring, all older, go with them.
        val overwriteBelow = ringWrite + count - cap
        var n = head.get()
        val end = tail
        while (n < end && slotRing[(n % MAX_FRAMES).toInt()] !== ring) n++
        var dropTo = -1L
        while (n < end && slotStart[(n % MAX_FRAMES).toInt()] < overwriteBelow) {
            dropTo = ++n
        }
        if (dropTo >= 0) advanceHeadTo(dropTo)
        // And a slot for it.
        if (end - head.get() >= MAX_FRAMES) advanceHeadTo(end - MAX_FRAMES + 1)
        return ring
    }

    private fun adopt(fresh: FloatArray) {
        ring = fresh
        ringWrite = 0L
    }

    /** Moves [head] forward to [target], unless the reader already has. */
    private fun advanceHeadTo(target: Long) {
        while (true) {
            val h = head.get()
            if (h >= target || head.compareAndSet(h, target)) return
        }
    }

    /** Publishes the frame just written at [off] in [dst], then drops what has aged out. */
    private fun commit(dst: FloatArray, off: Int, count: Int, channelCount: Int, sampleRate: Int) {
        val now = System.currentTimeMillis()
        latestTimestampMs.set(now)
        // Deliberately not delayed. This feeds the waveform overlay, which draws
        // on its own Canvas whether or not the projectM engine is running, so
        // holding it back here would freeze the overlay whenever nothing was
        // draining the queue.
        var snap = snapshots[nextSnapshot]
        if (snap == null || snap.size != count) {
            snap = FloatArray(count)
            snapshots[nextSnapshot] = snap
        }
        System.arraycopy(dst, off, snap, 0, count)
        sampleSnapshot.set(snap)
        nextSnapshot = (nextSnapshot + 1) % SNAPSHOTS

        val t = tail
        val slot = (t % MAX_FRAMES).toInt()
        slotRing[slot] = dst
        slotStart[slot] = ringWrite
        slotLength[slot] = count
        slotChannels[slot] = channelCount
        slotRate[slot] = sampleRate
        slotTime[slot] = now
        ringWrite += count.toLong()
        // The volatile write that hands the frame to the reader.
        tail = t + 1

        // Bounded by age rather than by count, because the queue has to be
        // long enough to hold the delay: a fixed eight frames is a fraction of
        // a second at one buffer each and would throw away audio before it
        // came due.
        val oldestWanted = now - (delayMs + RETAIN_MS)
        while (true) {
            val h = head.get()
            if (h >= tail) break
            if (slotTime[(h % MAX_FRAMES).toInt()] >= oldestWanted) break
            head.compareAndSet(h, h + 1)
        }
    }

    /** Receives one due frame from [drainDue]: the first [count] samples of [samples]. */
    fun interface FrameSink {
        fun frame(samples: FloatArray, count: Int, channelCount: Int, sampleRate: Int, timestampMs: Long)
    }

    /**
     * Hands every frame that has come due to [sink], oldest first, and returns
     * when the last of them was published, or 0 when none was due.
     *
     * With no delay set this is every frame accumulated since the last render,
     * exactly as before. With one, a frame is withheld until it has been in the
     * queue that long, so what the visualizer draws is what is leaving the
     * speaker rather than what will leave it a quarter of a second from now.
     *
     * [sink] is given this bus's scratch array, rewritten for the next frame.
     */
    fun drainDue(sink: FrameSink): Long {
        val dueBefore = System.currentTimeMillis() - delayMs
        var lastTimestamp = 0L
        while (true) {
            val h = head.get()
            if (h >= tail) break
            val slot = (h % MAX_FRAMES).toInt()
            val timestamp = slotTime[slot]
            if (timestamp > dueBefore) break
            val src = slotRing[slot]
            val count = slotLength[slot]
            val channels = slotChannels[slot]
            val rate = slotRate[slot]
            val off = if (src == null || src.isEmpty()) -1 else (slotStart[slot] % src.size).toInt()
            // Checked rather than trusted: if the slot was reused while being
            // read, the fields may not belong together. The claim below fails
            // then, but the copy has to be safe to make first.
            val valid = src != null && count > 0 && off >= 0 && off + count <= src.size
            if (valid) {
                if (drainScratch.size < count) drainScratch = FloatArray(count)
                System.arraycopy(src!!, off, drainScratch, 0, count)
            }
            // Claim it. Failing means the audio thread dropped it while it was
            // being copied, and the copy may hold some of what replaced it.
            if (!head.compareAndSet(h, h + 1)) continue
            if (!valid) continue
            lastTimestamp = timestamp
            sink.frame(drainScratch, count, channels, rate, timestamp)
        }
        return lastTimestamp
    }

    /**
     * Frames that have come due, oldest first, each in an array of its own.
     * Allocates for every frame; the render thread uses [drainDue].
     */
    fun drainAll(): List<ProjectMAudioFrame> {
        val frames = mutableListOf<ProjectMAudioFrame>()
        drainDue { samples, count, channels, rate, timestamp ->
            frames.add(ProjectMAudioFrame(samples.copyOf(count), channels, rate, timestamp))
        }
        return frames
    }

    fun latestTimestampMs(): Long = latestTimestampMs.get()

    /** Returns the latest audio samples without consuming them (for waveform overlay). */
    fun peekSamples(): FloatArray? = sampleSnapshot.get()

    fun clear() {
        advanceHeadTo(tail)
        sampleSnapshot.set(null)
        latestTimestampMs.set(0L)
    }

    companion object {
        /** Past this the picture is so far behind that it reads as a fault. */
        const val MAX_DELAY_MS = 500

        /** Headroom kept beyond the delay so a late render still finds its audio. */
        private const val RETAIN_MS = 250

        /**
         * Seconds of audio the ring holds: the longest delay and its headroom,
         * and the burst the player decodes ahead of the speaker when a track
         * starts or after a seek, which arrives at once and comes due at once.
         */
        private const val RING_SECONDS = 2

        /** Never more than this many samples (3 MB), however wide the format. */
        private const val MAX_RING_SAMPLES = 768_000

        /** Frames held at most: far more than [RING_SECONDS] holds at any buffer size Media3 uses. */
        private const val MAX_FRAMES = 512

        private const val SNAPSHOTS = 3

        private fun ringSamplesFor(samplesPerSecond: Int): Int =
            if (samplesPerSecond <= 0) {
                0
            } else {
                minOf(samplesPerSecond.toLong() * RING_SECONDS, MAX_RING_SAMPLES.toLong()).toInt()
            }
    }
}
