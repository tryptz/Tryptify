package tf.monochrome.android.audio.usb

import java.nio.ByteBuffer

/**
 * Where a crossfade's outgoing tail meets the exclusive USB stream.
 *
 * The DAC belongs to one stream — the main player's, written by
 * [LibusbAudioSink] — so a blend cannot open a second one the way it does on
 * Android's mixer. Instead the tail player's sink ([MixFeedAudioSink]) writes
 * its finished audio here, already at the DAC's rate and as float, and the
 * main sink adds it into its own samples just before they are packed for the
 * DAC: one stream, two songs.
 *
 * The main sink can be short-written by the DAC's ring, so it [peek]s tail
 * frames when it mixes and [consume]s only as many as the driver accepted —
 * consuming on peek would drop the rest, and a partial write would skip part
 * of the outgoing song.
 *
 * Threads: [offer] on the tail player's playback thread, [peek] / [consume]
 * on the main player's. One lock; every call is a short copy.
 */
class UsbCrossfadeMix(private val capacitySeconds: Float = 0.5f) {

    private val lock = Any()

    private var ring = FloatArray(0)
    private var ringFrames = 0
    private var channels = 0
    private var readFrame = 0
    private var available = 0

    /** Gain applied to the tail as it is mixed: the blend's fade-out times the volume. */
    @Volatile var gain: Float = 1f

    /**
     * Whether the tail is playing. A paused tail still buffers — ExoPlayer
     * prerolls into a paused player's sink, and a blend prepares its tail
     * paused at the blend point — and none of that may be heard until the
     * hand-off starts it.
     */
    @Volatile var playing: Boolean = false

    /** Whether a tail is attached. The main sink mixes nothing while it is not. */
    @Volatile var isOpen: Boolean = false
        private set

    /**
     * The stream the tail is mixed into, set by the main sink at configure:
     * the tail resamples to [consumerRate] and lays out [consumerChannels].
     */
    @Volatile var consumerRate: Int = 0
        private set
    @Volatile var consumerChannels: Int = 0
        private set

    /**
     * Set by the main sink: writes the tail on its own while the main stream
     * has nothing to write — between the hand-off and the next song's first
     * buffer, which a change of codec or rate makes long. Called by the tail
     * as it delivers audio.
     */
    @Volatile var idlePump: (() -> Unit)? = null

    // Heard time the main sink has taken from the tail, µs: the tail's clock.
    @Volatile private var consumedUs = 0L

    fun setConsumer(rate: Int, channels: Int) {
        consumerRate = rate
        consumerChannels = channels
    }

    /** A tail attaches, producing [channels]-channel audio. */
    fun open(channels: Int) = synchronized(lock) {
        val rate = consumerRate.coerceAtLeast(8_000)
        val frames = (rate * capacitySeconds).toInt().coerceAtLeast(1024)
        if (this.channels != channels || ringFrames != frames) {
            ring = FloatArray(frames * channels)
            ringFrames = frames
            this.channels = channels
        }
        readFrame = 0
        available = 0
        consumedUs = 0L
        playing = false
        isOpen = true
    }

    /** The tail is gone; whatever it left unplayed goes with it. */
    fun close() = synchronized(lock) {
        isOpen = false
        playing = false
        available = 0
        readFrame = 0
    }

    /** Drops queued audio: the tail seeked. */
    fun clear() = synchronized(lock) {
        available = 0
        readFrame = 0
    }

    /** Frames queued and not yet mixed. */
    fun pending(): Int = synchronized(lock) { available }

    /** Heard microseconds the main stream has played of the tail. */
    fun playedUs(): Long = consumedUs

    /**
     * Queues interleaved float frames from [src] (native order, from its
     * position), advancing it by what fitted. Returns frames taken; 0 when
     * full, which is the tail's back-pressure.
     */
    fun offer(src: ByteBuffer): Int = synchronized(lock) {
        if (!isOpen || channels <= 0) return 0
        val frameBytes = channels * 4
        val frames = minOf(src.remaining() / frameBytes, ringFrames - available)
        if (frames <= 0) return 0
        val start = src.position()
        var write = (readFrame + available) % ringFrames
        for (f in 0 until frames) {
            val base = start + f * frameBytes
            val o = write * channels
            for (c in 0 until channels) ring[o + c] = src.getFloat(base + c * 4)
            write = if (write + 1 == ringFrames) 0 else write + 1
        }
        available += frames
        src.position(start + frames * frameBytes)
        frames
    }

    /**
     * Copies up to [frames] queued frames into [dst], laid out for
     * [dstChannels], without taking them; frames past what is queued are
     * zero. Channels the tail does not have stay silent, ones the stream
     * does not have are dropped. Returns frames copied.
     */
    fun peek(dst: FloatArray, frames: Int, dstChannels: Int): Int = synchronized(lock) {
        val n = if (isOpen && playing) minOf(frames, available) else 0
        var read = readFrame
        val shared = minOf(channels, dstChannels)
        for (f in 0 until frames) {
            val o = f * dstChannels
            if (f < n) {
                val i = read * channels
                for (c in 0 until dstChannels) dst[o + c] = if (c < shared) ring[i + c] else 0f
                read = if (read + 1 == ringFrames) 0 else read + 1
            } else {
                for (c in 0 until dstChannels) dst[o + c] = 0f
            }
        }
        n
    }

    /** Takes [frames] that the DAC accepted, at [rate] — the tail's clock moves. */
    fun consume(frames: Int, rate: Int) = synchronized(lock) {
        val n = minOf(frames, available)
        if (n <= 0) return
        readFrame = (readFrame + n) % ringFrames
        available -= n
        if (rate > 0) consumedUs += n * 1_000_000L / rate
    }
}
