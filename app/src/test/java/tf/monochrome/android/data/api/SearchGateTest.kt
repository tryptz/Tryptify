package tf.monochrome.android.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What stands between a screen and the TIDAL server's search: one request per
 * question, a few at a time and spaced out, and none at all while the server
 * is refusing them.
 *
 * The clock is the test's own: time moves only when the gate sleeps, so what
 * is checked is the gate's arithmetic and not how fast the machine is.
 */
class SearchGateTest {

    private var clock = 1_000_000L
    private val slept = mutableListOf<Long>()

    private fun gate(permits: Int = 4, spacingMs: Long = 150) = SearchGate(
        permits = permits,
        spacingMs = spacingMs,
        now = { clock },
        sleep = { ms -> slept += ms; clock += ms },
    )

    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `the same search asked six times at once is sent once`() = runBlocking {
        val gate = gate()
        var sent = 0
        val release = CompletableDeferred<Unit>()
        val callers = List(6) {
            async { gate.run("/search/?s=daft+punk") { sent++; release.await(); "page" } }
        }
        settle()
        assertEquals(1, sent)
        release.complete(Unit)
        assertEquals(List(6) { "page" }, callers.awaitAll())
        assertEquals(1, sent)
    }

    @Test
    fun `no more than the permits run at once`() = runBlocking {
        val gate = gate(permits = 2, spacingMs = 0)
        var running = 0
        var peak = 0
        val releases = List(6) { CompletableDeferred<Unit>() }
        val callers = List(6) { i ->
            async { gate.run("/search/?s=$i") { running++; peak = maxOf(peak, running); releases[i].await(); running--; "ok" } }
        }
        settle()
        assertEquals(2, running)
        releases.forEach { it.complete(Unit) }
        callers.awaitAll()
        assertEquals(2, peak)
    }

    @Test
    fun `a burst is started no closer together than the spacing`() = runBlocking {
        val gate = gate(permits = 4, spacingMs = 150)
        val starts = mutableListOf<Long>()
        List(5) { i -> async { gate.run("/search/?s=$i") { starts += clock; "ok" } } }.awaitAll()
        assertEquals(5, starts.size)
        val sorted = starts.sorted()
        assertTrue("starts $sorted", sorted.zipWithNext().all { (a, b) -> b - a >= 150 })
    }

    @Test
    fun `searches sent one after another do not wait`() = runBlocking {
        val gate = gate()
        repeat(4) { i ->
            gate.run("/search/?s=$i") { "ok" }
            // A real search takes longer than the spacing to answer.
            clock += 220
        }
        assertEquals(emptyList<Long>(), slept)
    }

    @Test
    fun `after a 429 nothing is sent until the wait the server named is over`() = runBlocking {
        val gate = gate()
        var sent = 0
        val refusal = gate.refused(retryAfterSeconds = 30)
        assertEquals(30_000L, refusal.waitMs)

        try {
            gate.run("/search/?s=justice") { sent++; "page" }
            fail("expected the search to be refused")
        } catch (refused: SearchGate.Refused) {
            assertEquals(30_000L, refused.waitMs)
        }
        assertEquals(0, sent)

        clock += 30_000
        assertEquals("page", gate.run("/search/?s=justice") { sent++; "page" })
        assertEquals(1, sent)
    }

    @Test
    fun `a search already queued when the 429 arrives is not sent either`() = runBlocking {
        val gate = gate(permits = 1, spacingMs = 0)
        var sent = 0
        val release = CompletableDeferred<Unit>()
        val first = async {
            runCatching { gate.run("/search/?s=a") { sent++; release.await(); throw gate.refused(20) } }
        }
        settle()
        val queued = async { runCatching { gate.run("/search/?s=b") { sent++; "page" } } }
        settle()
        release.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is SearchGate.Refused)
        assertTrue(queued.await().exceptionOrNull() is SearchGate.Refused)
        assertEquals(1, sent)
    }

    @Test
    fun `the pause is the server's wait, within two seconds to a minute`() {
        val gate = gate()
        assertEquals(2_000L, gate.refused(0).waitMs)
        assertEquals(45_000L, gate.refused(45).waitMs)
        // TIDAL's own wait can be hours (until midnight UTC): looked in on every minute.
        assertEquals(60_000L, gate.refused(15_134).waitMs)
        // No Retry-After at all.
        assertEquals(5_000L, gate.refused(null).waitMs)
    }
}
