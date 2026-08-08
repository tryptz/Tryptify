package tf.monochrome.android.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The hand-off that lets a Qobuz track play while it is still downloading. A
 * reader must never be told about bytes that aren't on disk, must never mistake
 * a failed or truncated download for a finished one, and must never be left
 * parked when the writer stops.
 */
class PartialStreamTest {

    private fun stream() = PartialStream(File("track.bin.part"))

    @Test
    fun `starts empty and unfinished`() {
        val s = stream()
        assertEquals(0L, s.availableBytes)
        assertEquals(PartialStream.UNKNOWN_LENGTH, s.totalBytes)
        assertFalse(s.isComplete)
        assertNull(s.failure)
    }

    @Test
    fun `returns immediately when bytes are already ahead of the reader`() {
        val s = stream()
        s.publish(4_096)
        assertEquals(4_096L, s.awaitAvailable(0))
    }

    @Test
    fun `progress never goes backwards`() {
        val s = stream()
        s.publish(8_000)
        s.publish(2_000)
        assertEquals(8_000L, s.availableBytes)
    }

    @Test
    fun `a reader that catches up is woken by the next write`() {
        val s = stream()
        val started = CountDownLatch(1)
        val got = AtomicLong(-1)
        val reader = Thread {
            started.countDown()
            got.set(s.awaitAvailable(1_000))
        }
        reader.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))

        s.publish(1_000)   // not past the reader's offset — must not wake it through
        s.publish(5_000)

        reader.join(5_000)
        assertEquals(5_000L, got.get())
    }

    @Test
    fun `completion wakes a waiting reader and reports end of data`() {
        val s = stream()
        val got = AtomicLong(-1)
        val reader = Thread { got.set(s.awaitAvailable(9_999)) }
        reader.start()
        Thread.sleep(50)

        s.complete(1_234, File("track.bin"))

        reader.join(5_000)
        // Nothing past the requested offset ever arrived — the caller sees the
        // final size and treats it as end-of-input rather than hanging.
        assertEquals(1_234L, got.get())
        assertTrue(s.isComplete)
    }

    @Test
    fun `a failed download surfaces as an exception, not a short file`() {
        val s = stream()
        val thrown = AtomicReference<Throwable?>()
        val reader = Thread {
            thrown.set(runCatching { s.awaitAvailable(500) }.exceptionOrNull())
        }
        reader.start()
        Thread.sleep(50)

        s.fail(IOException("connection reset"))

        reader.join(5_000)
        assertTrue(thrown.get() is IOException)
        assertEquals("connection reset", thrown.get()?.message)
    }

    @Test
    fun `failure is reported even to a reader that arrives afterwards`() {
        val s = stream()
        s.publish(100)
        s.fail(IOException("gone"))
        assertThrows(IOException::class.java) { s.awaitAvailable(0) }
    }

    @Test
    fun `cancellation wakes a waiting reader`() {
        val s = stream()
        val thrown = AtomicReference<Throwable?>()
        val reader = Thread {
            thrown.set(runCatching { s.awaitAvailable(0) }.exceptionOrNull())
        }
        reader.start()
        Thread.sleep(50)

        s.cancel()

        reader.join(5_000)
        assertTrue(thrown.get() is IOException)
    }

    @Test
    fun `a stalled reader gives up rather than parking forever`() {
        val s = stream()
        val started = System.currentTimeMillis()
        assertThrows(IOException::class.java) { s.awaitAvailable(0, timeoutMs = 150) }
        assertTrue(System.currentTimeMillis() - started >= 150)
    }

    @Test
    fun `completing without a content length adopts the bytes written`() {
        val s = stream()
        s.publish(700)
        s.complete(700, File("track.bin"))
        assertEquals(700L, s.totalBytes)
    }

    @Test
    fun `a declared content length survives completion`() {
        val s = stream()
        s.setTotalBytes(9_000)
        s.complete(9_000, File("track.bin"))
        assertEquals(9_000L, s.totalBytes)
    }

    @Test
    fun `awaitCompletion returns the installed file`() {
        val s = stream()
        val installed = File("track.bin")
        val got = AtomicReference<File?>()
        val waiter = Thread { got.set(runCatching { s.awaitCompletion() }.getOrNull()) }
        waiter.start()
        Thread.sleep(50)

        s.complete(42, installed)

        waiter.join(5_000)
        assertSame(installed, got.get())
    }

    @Test
    fun `awaitCompletion rethrows a download failure`() {
        val s = stream()
        s.fail(IOException("no route to host"))
        assertThrows(IOException::class.java) { s.awaitCompletion() }
    }

    @Test
    fun `many readers are all released by one write`() {
        val s = stream()
        val results = List(8) { AtomicLong(-1) }
        val threads = results.map { slot ->
            Thread { slot.set(s.awaitAvailable(0)) }.also { it.start() }
        }
        Thread.sleep(80)
        s.publish(3_333)
        threads.forEach { it.join(5_000) }
        assertTrue(results.all { it.get() == 3_333L })
    }
}
