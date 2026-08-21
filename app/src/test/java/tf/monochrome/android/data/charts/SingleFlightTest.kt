package tf.monochrome.android.data.charts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things that made the discovery feed's caches not behave like caches:
 * six shelves asking the same question at the same moment, and a batch of
 * lookups moving at the pace of its slowest member.
 *
 * These run on [runBlocking]'s single-threaded event loop on purpose. The
 * properties here are about who is allowed to be waiting on what, and a test
 * that relied on real parallelism to demonstrate them would be a test that
 * passes for timing reasons.
 */
class SingleFlightTest {

    @Test
    fun `one flight serves every caller that arrives while it is running`() = runBlocking {
        val flight = SingleFlight<String, Int>()
        var calls = 0
        val release = CompletableDeferred<Unit>()

        val callers = List(6) {
            async {
                flight.run("hard techno") {
                    calls++
                    release.await()
                    42
                }
            }
        }
        // Every caller has reached the flight; none of them has an answer yet.
        // This is the state the feed is in a few milliseconds after a chip tap.
        yield()
        release.complete(Unit)

        assertEquals(List(6) { 42 }, callers.awaitAll())
        assertEquals(1, calls)
    }

    @Test
    fun `a question answered earlier is asked again`() = runBlocking {
        // A flight is not a memo: it collapses callers that overlap and holds
        // nothing afterwards. The memos in front of it are what stop the
        // second visit paying, and this pins that this is not also trying to
        // be one — a cache with no TTL hiding in here would be a chart that
        // never updates.
        val flight = SingleFlight<String, Int>()
        var calls = 0

        assertEquals(1, flight.run("k") { ++calls })
        assertEquals(2, flight.run("k") { ++calls })
    }

    @Test
    fun `different keys do not wait on each other`() = runBlocking {
        val flight = SingleFlight<String, String>()
        val held = CompletableDeferred<Unit>()

        val blocked = async { flight.run("techno") { held.await(); "techno" } }
        yield()
        // The whole point of keying: a genre whose chart is slow must not make
        // the next genre's shelf slow too.
        assertEquals("gabber", flight.run("gabber") { "gabber" })

        held.complete(Unit)
        assertEquals("techno", blocked.await())
    }

    @Test
    fun `a cancelled leader hands the flight on instead of taking everyone with it`() = runBlocking {
        // The case this exists for: a shelf's budget expires, or a chip tap
        // replaces the feed, while that shelf happened to be the one leading a
        // lookup four other shelves are waiting on. Inheriting its cancellation
        // would make one shelf's timeout everybody's.
        val flight = SingleFlight<String, Int>()
        var calls = 0
        val leaderStarted = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()

        val leader = async {
            flight.run("neoclassical") {
                calls++
                leaderStarted.complete(Unit)
                neverReleased.await()
                1
            }
        }
        leaderStarted.await()
        val follower = async { flight.run("neoclassical") { calls++; 2 } }
        yield()

        leader.cancel()

        assertEquals(2, follower.await())
        assertEquals(2, calls)
        assertTrue(leader.isCancelled)
    }

    @Test
    fun `a failure reaches everyone waiting on it`() = runBlocking {
        // Unlike a cancellation, which belongs to whoever was cancelled, a
        // failed request is the answer to the question all of them asked.
        val flight = SingleFlight<String, Int>()
        var calls = 0
        val release = CompletableDeferred<Unit>()

        val callers = List(3) {
            async {
                runCatching {
                    flight.run("k") {
                        calls++
                        release.await()
                        throw IllegalStateException("unreachable")
                    }
                }
            }
        }
        yield()
        release.complete(Unit)

        val outcomes = callers.awaitAll()
        assertEquals(1, calls)
        assertTrue(outcomes.all { it.exceptionOrNull() is IllegalStateException })
    }

    @Test
    fun `mapConcurrent answers in the input's order`() = runBlocking {
        // A chart is a ranking and the pool is the chart. Returning rows in the
        // order the network happened to answer would reorder it by latency.
        val out = (1..20).toList().mapConcurrent(4) { it * 2 }
        assertEquals((1..20).map { it * 2 }, out)
    }

    @Test
    fun `mapConcurrent holds the line at the permitted number`() = runBlocking {
        var inFlight = 0
        var peak = 0

        (1..30).toList().mapConcurrent(5) {
            inFlight++
            peak = maxOf(peak, inFlight)
            yield()
            inFlight--
            it
        }

        assertEquals(5, peak)
    }

    @Test
    fun `a finished lookup hands its slot on rather than waiting out a batch`() = runBlocking {
        // The property the batched version did not have, and the reason this
        // replaced it. With two slots and the first item still in flight, the
        // third item must be able to start the moment the second finishes —
        // under `chunked(2)` it could not, and `startedThird` below would never
        // complete.
        val slow = CompletableDeferred<Unit>()
        val startedThird = CompletableDeferred<Unit>()

        val work = async {
            listOf(0, 1, 2).mapConcurrent(2) { index ->
                when (index) {
                    0 -> { slow.await(); index }
                    2 -> { startedThird.complete(Unit); index }
                    else -> index
                }
            }
        }

        startedThird.await()
        slow.complete(Unit)
        assertEquals(listOf(0, 1, 2), work.await())
    }
}
