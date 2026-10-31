package tf.monochrome.android.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import java.util.concurrent.atomic.AtomicInteger

/**
 * The YouTube or SoundCloud song next in line, made ready while the one
 * before plays: once however many ask, kept while it is playing or next, and
 * stopped when the queue moves on without it.
 */
class UpcomingPrepareTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = QueueManager()

    private val prepares = AtomicInteger()
    private val gate = CompletableDeferred<Boolean>()
    private val stopped = CompletableDeferred<Unit>()

    /** A play waiting for its own song (PreparingPlay.waiting). */
    private val playPreparing = MutableStateFlow(false)

    private val upcoming = UpcomingPrepare(queue, scope, playPreparing) { _ ->
        prepares.incrementAndGet()
        try {
            gate.await()
        } catch (e: CancellationException) {
            stopped.complete(Unit)
            throw e
        }
    }

    private fun tracks(vararg ids: Long) = ids.map { Track(id = it, title = "Song $it") }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun awaitPrepares(n: Int) = withTimeout(5_000) { while (prepares.get() < n) delay(5) }

    @Test
    fun `the gapless job and both preloads share one warm-up`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        val waits = List(3) { async(Dispatchers.Default) { upcoming.warm(2, "yt:two") } }
        awaitPrepares(1)
        gate.complete(true)
        assertEquals(listOf(true, true, true), waits.map { it.await() })
        assertEquals(1, prepares.get())
    }

    @Test
    fun `a wait cancelled stops only itself, not the fetch`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        val gaplessJob = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        awaitPrepares(1)
        // The queue emitted and the gapless job was started again.
        gaplessJob.cancelAndJoin()
        val again = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        gate.complete(true)
        assertTrue(again.await())
        assertFalse(stopped.isCompleted)
        assertEquals(1, prepares.get())
    }

    @Test
    fun `the queue moving on without the song stops its fetch`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        val wait = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        awaitPrepares(1)
        // Another list tapped: song 2 is neither playing nor next.
        queue.setQueue(tracks(7, 8), 0)
        withTimeout(5_000) { stopped.await() }
        assertFalse(wait.await())
    }

    @Test
    fun `a song reached while it is fetched keeps its warm-up, for its play to join`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        val wait = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        awaitPrepares(1)
        queue.next()
        delay(100)
        assertFalse(stopped.isCompleted)
        gate.complete(true)
        assertTrue(wait.await())
    }

    @Test
    fun `a song skipped past before it came is dropped`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        val wait = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        awaitPrepares(1)
        queue.next()
        queue.next()
        withTimeout(5_000) { stopped.await() }
        assertFalse(wait.await())
    }

    @Test
    fun `under repeat one nothing is next, so nothing is fetched ahead`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        queue.setRepeatMode(RepeatMode.ONE)
        assertFalse(upcoming.warm(2, "yt:two"))
        delay(100)
        assertEquals(0, prepares.get())
    }

    @Test
    fun `a song asked for after the queue moved past it is not fetched`() = runBlocking {
        // A preload read the queue, and the listener skipped on before it ran.
        queue.setQueue(tracks(1, 2, 3, 4), 0)
        queue.next()
        queue.next()
        assertFalse(upcoming.warm(2, "yt:two"))
        delay(100)
        assertEquals(0, prepares.get())
    }

    @Test
    fun `nothing is fetched ahead while the song asked for is still made ready`() = runBlocking {
        // A new list tapped: its first song is being fetched for the play,
        // and the gapless job asks for the second at once.
        playPreparing.value = true
        queue.setQueue(tracks(1, 2, 3), 0)
        val wait = async(Dispatchers.Default) { upcoming.warm(2, "yt:two") }
        delay(100)
        assertEquals(0, prepares.get())
        // The first song is in the player: the second's turn.
        playPreparing.value = false
        awaitPrepares(1)
        gate.complete(true)
        assertTrue(wait.await())
        assertEquals(1, prepares.get())
    }

    @Test
    fun `one that failed is tried again the next time`() = runBlocking {
        queue.setQueue(tracks(1, 2, 3), 0)
        gate.complete(false)
        assertFalse(upcoming.warm(2, "yt:two"))
        assertFalse(upcoming.warm(2, "yt:two"))
        assertEquals(2, prepares.get())
    }
}
