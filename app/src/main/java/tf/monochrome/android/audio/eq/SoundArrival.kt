package tf.monochrome.android.audio.eq

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * How the music links' loop sleeps through silence ([SpectrumAnalyzerTap]):
 * it waits here for the audio thread's next buffer instead of waking every
 * hop to find the ring where it was. Paused with the lyrics open, that was
 * 200 wake-ups a second for nothing.
 *
 * [arrived] is the audio thread's side, called once a buffer: a read of one
 * reference, and only on the buffer that ends a wait, one resume, which puts
 * the loop on its dispatcher's lock-free queue. Nothing here allocates,
 * locks or waits (the dispatcher's queue may, rarely, grow). That is why
 * the wait has no cancellation handler: with one, every resume allocates a
 * record to hold it (`SoundArrivalTest` counts the bytes).
 */
internal class SoundArrival {

    private val waiter = AtomicReference<CancellableContinuation<Unit>?>(null)

    /** Audio thread: a buffer has gone into the ring. Wakes the loop if it waits. */
    fun arrived() {
        val waiting = waiter.get() ?: return
        if (waiter.compareAndSet(waiting, null)) waiting.resume(Unit)
    }

    /**
     * Suspends until the next [arrived]. [idle] is asked once more after the
     * wait is set, so a buffer that came between the caller's own look at
     * the ring and the wait is not slept through.
     */
    suspend fun await(idle: () -> Boolean) {
        var mine: CancellableContinuation<Unit>? = null
        try {
            suspendCancellableCoroutine { cont ->
                // A loop already let go (releaseLinks) must not take a live
                // one's place: the live one would sleep through every buffer
                // that followed. The tap gives each loop its own wait too.
                if (!cont.isActive) return@suspendCancellableCoroutine
                mine = cont
                waiter.set(cont)
                if (!idle() && waiter.compareAndSet(cont, null)) cont.resume(Unit)
            }
        } finally {
            // Cancelled while waiting: the wait is let go here, on the loop's
            // own thread. A buffer that takes it first resumes a cancelled
            // wait, which does nothing.
            mine?.let { waiter.compareAndSet(it, null) }
        }
    }

    /** Whether the loop is waiting now. For the tests. */
    internal val waiting: Boolean get() = waiter.get() != null
}
