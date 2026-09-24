package tf.monochrome.android.data.spotify

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The byte pipe between librespot's output thread (writer) and ExoPlayer's
 * loader thread (reader), as one bounded ring buffer.
 *
 * **Backpressure, not dropping.** librespot's `AudioSink` loop has no clock of
 * its own: it pulls decoded PCM from its mixer and calls `write` as fast as the
 * output accepts it (a `LineOutput` is paced by the sound card; a custom sink
 * is paced by nothing). So [write] blocks while the ring is full. That makes
 * ExoPlayer the one clock: while it is paused or its buffer is full it stops
 * reading, the ring fills, and librespot's output thread simply waits. Dropping
 * instead — the old queue's behaviour — threw away everything the decoder
 * produced faster than real time, which is almost all of it.
 *
 * **Generations.** Each stream a reader opens gets a generation number
 * ([newGeneration]). Opening a new stream (a seek, or a different track)
 * bumps it; a reader holding an older number is told its stream is gone
 * ([read] returns [SUPERSEDED]) instead of being handed another track's audio.
 * A writer blocked on a full ring when the generation changes discards the
 * rest of its chunk — that audio belongs to the stream that was just replaced.
 *
 * The writer is librespot's own thread, not a realtime audio callback, so
 * blocking and locking here are fine. Nothing on this path allocates per call.
 *
 * Pure JVM so it can be unit tested without a device.
 */
class PcmPipe(
    capacityBytes: Int,
    private val endGraceNanos: Long = TimeUnit.MILLISECONDS.toNanos(DEFAULT_END_GRACE_MS),
    private val nanoTime: () -> Long = System::nanoTime,
) {

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    private val ring = ByteArray(capacityBytes)
    private var head = 0          // next byte to read
    private var size = 0          // bytes buffered
    private var generation = 0L
    private var ended = false     // librespot finished the track of this generation
    private var endedAt = 0L      // nanoTime of markEnded
    private var failure: Throwable? = null  // librespot gave up on this generation's track
    private var closed = false

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    /**
     * Starts a new stream: empties the ring, clears the end flag, and
     * invalidates every reader and blocked writer of the previous one.
     */
    fun newGeneration(): Long = lock.withLock {
        generation++
        head = 0
        size = 0
        ended = false
        failure = null
        notFull.signalAll()
        notEmpty.signalAll()
        generation
    }

    /** Empties the ring without ending the stream — librespot's own flush on seek. */
    fun clear() = lock.withLock {
        head = 0
        size = 0
        notFull.signalAll()
    }

    /**
     * librespot reports the track finished. Its output thread may still be
     * writing the last buffers when it says so, so [read] only reports
     * [ENDED] once the pipe has also stayed empty for the grace period.
     *
     * [forGeneration] is the stream the event is about. librespot's events
     * arrive on their own thread and say nothing of which track they belong
     * to, so the end of the previous track can land after the next stream has
     * opened; applied to that stream it would end it at once and pad the rest
     * with silence. A mark for a generation that is no longer current is
     * dropped.
     */
    fun markEnded(forGeneration: Long) = lock.withLock {
        if (forGeneration != generation) return@withLock
        ended = true
        endedAt = nanoTime()
        notEmpty.signalAll()
    }

    /**
     * librespot could not play the track — it never loaded (no playable file,
     * no audio key) or broke off mid-way. Unlike [markEnded] this is not an
     * end of stream: [read] still hands out what is buffered, then reports
     * [FAILED] instead of [ENDED], so the reader can fail the load rather
     * than pad the rest of the track with silence. [failureOf] has the cause.
     * Scoped to [forGeneration] for the same reason as [markEnded].
     */
    fun markFailed(forGeneration: Long, cause: Throwable) = lock.withLock {
        if (forGeneration != generation) return@withLock
        failure = cause
        notEmpty.signalAll()
    }

    /** Why generation [readerGeneration] failed; null if it did not or is gone. */
    fun failureOf(readerGeneration: Long): Throwable? = lock.withLock {
        if (generation == readerGeneration) failure else null
    }

    /** Wakes and fails every waiter permanently; for shutdown. */
    fun close() = lock.withLock {
        closed = true
        notFull.signalAll()
        notEmpty.signalAll()
    }

    /**
     * Copies [len] bytes of [src] into the ring, blocking while it is full.
     * Returns early, discarding the remainder, if the generation changes or
     * the pipe closes while waiting. [src] may be reused by the caller as soon
     * as this returns (librespot reuses one 4 KiB buffer for every write).
     */
    fun write(src: ByteArray, offset: Int, len: Int) {
        require(offset >= 0 && len >= 0 && offset + len <= src.size) { "bad range" }
        lock.withLock {
            val startGeneration = generation
            var written = 0
            while (written < len) {
                while (size == ring.size) {
                    if (closed || generation != startGeneration) return
                    notFull.await()
                }
                if (closed || generation != startGeneration) return
                val tail = (head + size) % ring.size
                val contiguous = minOf(len - written, ring.size - size, ring.size - tail)
                System.arraycopy(src, offset + written, ring, tail, contiguous)
                size += contiguous
                written += contiguous
                notEmpty.signalAll()
            }
        }
    }

    /**
     * Reads up to [len] bytes of generation [readerGeneration] into [dst].
     *
     * @return bytes read (> 0); [TIMED_OUT] if nothing arrived within
     *   [timeoutMs]; [ENDED] if the track is finished and fully drained;
     *   [FAILED] if librespot gave up on the track and the pipe is drained;
     *   [SUPERSEDED] if a newer stream replaced this one or the pipe closed.
     */
    fun read(readerGeneration: Long, dst: ByteArray, offset: Int, len: Int, timeoutMs: Long): Int {
        require(offset >= 0 && len > 0 && offset + len <= dst.size) { "bad range" }
        lock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                if (closed || generation != readerGeneration) return SUPERSEDED
                if (size > 0) break
                if (failure != null) return FAILED
                if (ended) {
                    val graceLeft = endGraceNanos - (nanoTime() - endedAt)
                    if (graceLeft <= 0L) return ENDED
                    notEmpty.awaitNanos(graceLeft)
                    continue
                }
                if (remainingNs <= 0L) return TIMED_OUT
                remainingNs = notEmpty.awaitNanos(remainingNs)
            }
            val contiguous = minOf(len, size, ring.size - head)
            System.arraycopy(ring, head, dst, offset, contiguous)
            head = (head + contiguous) % ring.size
            size -= contiguous
            notFull.signalAll()
            return contiguous
        }
    }

    companion object {
        const val TIMED_OUT = 0
        const val ENDED = -1
        const val SUPERSEDED = -2
        const val FAILED = -3
        const val DEFAULT_END_GRACE_MS = 250L
    }
}
