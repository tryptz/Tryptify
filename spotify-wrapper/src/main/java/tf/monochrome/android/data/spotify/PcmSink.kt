package tf.monochrome.android.data.spotify

import android.util.Log
import kotlin.concurrent.withLock
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkOutput
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * The pipe between librespot's decoder and ExoPlayer: librespot's player is
 * configured with `setOutputClass(PcmSink)`, so it reflects an instance of
 * this class and writes every decoded PCM chunk into it. The chunk queue is
 * what [PcmSinkDataSource] — an ExoPlayer DataSource — pulls from, turning
 * Spotify's audio into just another MediaItem inside Tryptify's queue.
 *
 * Ring-buffer semantics: if the reader falls behind, the oldest chunk is
 * dropped rather than blocking Spotify's decoder thread.
 *
 * Robustness notes
 * ----------------
 * An underrun (buffer empty but the player is still connected and decoding)
 * must NOT be reported as end-of-stream — ExoPlayer would terminate playback
 * on any network jitter or seek. `read()` therefore distinguishes:
 *  - `UNDERRUN`      — buffer empty but sink still active (keep reading)
 *  - count >= 0      — bytes copied
 *  - `C.RESULT_END_OF_INPUT` (via DataSource) — only when sink is closed
 *                (i.e., [release] called).
 * The DataSource knows the track length from the WAV header and will stop
 * reading after that many bytes, regardless of whether the sink has more data.
 */
class PcmSink : SinkOutput {

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    /** Guards transitions on [closed] against concurrent writer/reader. */
    private val stateLock = ReentrantLock()

    /** True once [release] has run; further writes/no-ops are ignored. */
    @Volatile
    private var closed = false

    /** Non-zero while librespot's decoder thread is active, so readers can poll efficiently. */
    private val producing = AtomicBoolean(false)

    override fun start(format: OutputAudioFormat): Boolean {
        Log.d(TAG, "sink start ${format.sampleRate}Hz ${format.channels}ch")
        closed = false
        producing.set(true)
        PcmSinkRegistry.sink = this
        return true
    }

    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        if (closed) return
        // The decoder owns and may reuse its buffer after this call returns;
        // the queued copy must remain immutable until ExoPlayer consumes it.
        val chunk = buffer.copyOfRange(offset, offset + len)
        // Drop the oldest chunk when the reader stalls — audio keeps flowing.
        while (!queue.offer(chunk)) {
            queue.poll()
        }
    }

    /** Called by librespot between tracks and on shutdown. */
    override fun flush() {
        stateLock.withLock { queue.clear(); pending = null; pendingOffset = 0 }
    }

    /** librespot calls this between tracks, not at real EOS. */
    override fun stop() {
        Log.d(TAG, "sink stop (between tracks)")
        stateLock.withLock { queue.clear(); pending = null; pendingOffset = 0 }
    }

    override fun release() {
        Log.d(TAG, "sink release")
        stateLock.withLock {
            queue.clear()
            pending = null
            pendingOffset = 0
            closed = true
        }
        producing.set(false)
        PcmSinkRegistry.sink = null
    }

    override fun close() = flush()

    /**
     * Reads up to [length] bytes into [target]. Callers (PcmSinkDataSource)
     * distinguish:
     * @return bytes copied (>=0), [UNDERRUN] if the buffer is empty but the
     * stream is still live.
     */
    @Synchronized
    fun read(target: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (length == 0) return 0
        if (closed) return EOS

        // Refill the pending-chunk cursor if the last one is exhausted.
        if (pending == null || pendingOffset >= pending!!.size) {
            pending = try {
                queue.poll(timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            pendingOffset = 0
        }

        val chunk = pending
        if (chunk == null) {
            // Timed out waiting — this is an underrun, not EOF. If the
            // player is still producing, keep the stream alive and let the
            // caller decide whether to wait longer or retry.
            return if (producing.get()) UNDERRUN else EOS
        }

        val count = minOf(length, chunk.size - pendingOffset)
        System.arraycopy(chunk, pendingOffset, target, offset, count)
        pendingOffset += count
        if (pendingOffset >= chunk.size) {
            pending = null
            pendingOffset = 0
        }
        bytesProduced(count.toLong())
        return count
    }

    /** Marks the sink reached end-of-stream without tearing it down. */
    fun signalEos() {
        stateLock.withLock { closed = true }
    }

    companion object {
        private const val TAG = "Tryptify/PcmSink"
        /** Size of the ring buffer (number of ByteArray chunks). */
        const val QUEUE_CAPACITY = 256

        /** Return value meaning "no data right now but stream is still live". */
        const val UNDERRUN = -2
        /** Return value meaning the player has reached end-of-stream. */
        const val EOS = -1

        /** Don't let a stalled sink starve ExoPlayer for less than this. */
        private const val MIN_TIMEOUT_MS = 50L

        @JvmStatic
        fun bytesProduced(count: Long) { /* no-op hook for future perf counters */ }
    }
}