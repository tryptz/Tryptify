package tf.monochrome.android.audio.eq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music links' loop through silence: asleep until the audio thread's next
 * buffer, never polling, and awake the moment one comes.
 */
class SoundArrivalTest {

    private suspend fun untilWaiting(arrival: SoundArrival) {
        withTimeout(5_000) { while (!arrival.waiting) yield() }
    }

    @Test
    fun theLoopSleepsUntilABufferComesAndWakesOnIt() = runBlocking {
        val arrival = SoundArrival()
        val woke = AtomicBoolean(false)
        val loop = launch(Dispatchers.Default) {
            arrival.await { true }
            woke.set(true)
        }
        untilWaiting(arrival)
        // Well past a hop with no buffer: still asleep.
        delay(SpectrumAnalyzerTap.LINK_HOP_MS * 10)
        assertFalse(woke.get())
        assertTrue(arrival.waiting)
        // The audio thread's side, from a thread of its own.
        Thread { arrival.arrived() }.apply { start(); join() }
        withTimeout(5_000) { loop.join() }
        assertTrue(woke.get())
        assertFalse(arrival.waiting)
    }

    @Test
    fun aBufferThatCameBeforeTheWaitIsNotSleptThrough() = runBlocking {
        // The ring moved between the loop's look and its wait: idle says no.
        val arrival = SoundArrival()
        withTimeout(5_000) { arrival.await { false } }
        assertFalse(arrival.waiting)
    }

    @Test
    fun aBufferWithNobodyWaitingDoesNothing() {
        val arrival = SoundArrival()
        repeat(3) { arrival.arrived() }
        assertFalse(arrival.waiting)
    }

    @Test
    fun releasingTheLinksLetsGoOfTheWait() = runBlocking {
        val arrival = SoundArrival()
        val loop = launch(Dispatchers.Default) { arrival.await { true } }
        untilWaiting(arrival)
        loop.cancel()
        loop.join()
        assertFalse(arrival.waiting)
        // A buffer after that wakes nothing and throws nothing.
        arrival.arrived()
    }

    @Test
    fun everySilenceAfterTheFirstSleepsAgain() = runBlocking {
        val arrival = SoundArrival()
        repeat(3) {
            val loop = launch(Dispatchers.Default) { arrival.await { true } }
            untilWaiting(arrival)
            arrival.arrived()
            withTimeout(5_000) { loop.join() }
        }
        assertFalse(arrival.waiting)
    }

    @Test
    fun aLoopAlreadyLetGoDoesNotTakeTheLiveOnesWait() = runBlocking {
        // A loop cancelled mid-pass (releaseLinks) that reaches its wait after
        // the next one has gone silent must not leave that one sleeping
        // through every buffer. The tap gives each loop its own wait as well;
        // this holds even when two share one.
        val arrival = SoundArrival()
        val live = launch(Dispatchers.Default) { arrival.await { true } }
        untilWaiting(arrival)
        val gone = launch(Dispatchers.Default) {
            coroutineContext.job.cancel()
            arrival.await { true }
        }
        gone.join()
        assertTrue(arrival.waiting)
        Thread { arrival.arrived() }.apply { start(); join() }
        withTimeout(5_000) { live.join() }
        assertFalse(arrival.waiting)
    }

    /** Runs what it is handed only when told to, so a wake can be measured on the waking thread alone. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private var held: Runnable? = null
        override fun dispatch(context: CoroutineContext, block: Runnable) { held = block }
        fun runHeld() { val block = held ?: return; held = null; block.run() }
    }

    @Test
    fun wakingTheLoopAllocatesNothingOnTheAudioThread() {
        // With a cancellation handler on the wait, every wake cost the audio
        // thread 32 bytes: the record kotlinx keeps the handler in.
        val loop = HeldDispatcher()
        val scope = CoroutineScope(loop)
        val arrival = SoundArrival()
        // The JVM's own count of what this thread has allocated (HotSpot's
        // ThreadMXBean, by reflection: the unit tests compile against Android's
        // classes, which have no java.lang.management).
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        fun counted(block: () -> Unit): Long {
            val before = allocated.invoke(threads) as Long
            block()
            return allocated.invoke(threads) as Long - before
        }
        fun wake(): Long {
            val job = scope.launch { arrival.await { true } }
            loop.runHeld()
            assertTrue(arrival.waiting)
            val cost = counted { arrival.arrived() }
            loop.runHeld()
            assertTrue(job.isCompleted)
            return cost
        }
        // Classes loaded and paths warmed first; the least of several, less
        // what reading the counter itself costs.
        repeat(200) { wake() }
        val reading = (0 until 20).minOf { counted { } }
        assertEquals(0L, (0 until 20).minOf { wake() } - reading)
    }
}
