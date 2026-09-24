package tf.monochrome.android.data.spotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PcmPipeTest {

    private fun ramp(n: Int, start: Int = 0) = ByteArray(n) { ((start + it) and 0xFF).toByte() }

    /** Reads until [n] bytes or the pipe stops yielding data. */
    private fun drain(pipe: PcmPipe, gen: Long, n: Int, chunk: Int = 7): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = pipe.read(gen, out, got, minOf(chunk, n - got), 1_000)
            if (r <= 0) break
            got += r
        }
        return out.copyOf(got)
    }

    @Test
    fun `bytes come out in order across the ring's wrap`() {
        val pipe = PcmPipe(capacityBytes = 16)
        val gen = pipe.newGeneration()
        val data = ramp(1_000)
        val writer = thread { pipe.write(data, 0, data.size) }
        assertArrayEquals(data, drain(pipe, gen, data.size))
        writer.join(1_000)
    }

    @Test
    fun `a full pipe blocks the writer instead of dropping audio`() {
        val pipe = PcmPipe(capacityBytes = 8)
        val gen = pipe.newGeneration()
        val done = CountDownLatch(1)
        val writer = thread { pipe.write(ramp(20), 0, 20); done.countDown() }
        // Nothing is read: the writer must still be waiting, holding 12 bytes.
        assertTrue(!done.await(200, TimeUnit.MILLISECONDS))
        assertArrayEquals(ramp(20), drain(pipe, gen, 20))
        assertTrue(done.await(1, TimeUnit.SECONDS))
        writer.join()
    }

    @Test
    fun `the writer's buffer can be reused as soon as write returns`() {
        val pipe = PcmPipe(capacityBytes = 64)
        val gen = pipe.newGeneration()
        val buf = ramp(8)
        pipe.write(buf, 0, 8)
        buf.fill(0x7F)
        pipe.write(buf, 2, 4)
        val out = drain(pipe, gen, 12)
        assertArrayEquals(ramp(8) + ByteArray(4) { 0x7F }, out)
    }

    @Test
    fun `an empty pipe times out`() {
        val pipe = PcmPipe(capacityBytes = 8)
        val gen = pipe.newGeneration()
        assertEquals(PcmPipe.TIMED_OUT, pipe.read(gen, ByteArray(4), 0, 4, 20))
    }

    @Test
    fun `a new generation supersedes the old reader and discards the blocked writer's audio`() {
        val pipe = PcmPipe(capacityBytes = 4)
        val old = pipe.newGeneration()
        val writer = thread { pipe.write(ramp(12), 0, 12) }   // fills, then blocks
        Thread.sleep(100)
        val new = pipe.newGeneration()
        writer.join(1_000)
        assertTrue("blocked writer must give up", !writer.isAlive)
        assertEquals(PcmPipe.SUPERSEDED, pipe.read(old, ByteArray(4), 0, 4, 20))
        // Nothing from the old stream leaks into the new one.
        assertEquals(PcmPipe.TIMED_OUT, pipe.read(new, ByteArray(4), 0, 4, 20))
        pipe.write(ramp(3, start = 100), 0, 3)
        assertArrayEquals(ramp(3, start = 100), drain(pipe, new, 3))
    }

    @Test
    fun `clear drops buffered audio but keeps the stream`() {
        val pipe = PcmPipe(capacityBytes = 16)
        val gen = pipe.newGeneration()
        pipe.write(ramp(10), 0, 10)
        pipe.clear()
        pipe.write(ramp(2, start = 50), 0, 2)
        assertArrayEquals(ramp(2, start = 50), drain(pipe, gen, 2))
    }

    @Test
    fun `ended is reported only once drained and after the grace period`() {
        var now = 0L
        val pipe = PcmPipe(capacityBytes = 16, endGraceNanos = 1_000, nanoTime = { now })
        val gen = pipe.newGeneration()
        pipe.write(ramp(3), 0, 3)
        pipe.markEnded()
        // Buffered audio still comes out first.
        assertArrayEquals(ramp(3), drain(pipe, gen, 3))
        now += 2_000
        assertEquals(PcmPipe.ENDED, pipe.read(gen, ByteArray(4), 0, 4, 1_000))
    }

    @Test
    fun `late writes after the end event are still delivered within the grace period`() {
        val pipe = PcmPipe(capacityBytes = 16, endGraceNanos = TimeUnit.MILLISECONDS.toNanos(500))
        val gen = pipe.newGeneration()
        pipe.markEnded()
        val writer = thread { Thread.sleep(50); pipe.write(ramp(4), 0, 4) }
        val out = ByteArray(4)
        assertEquals(4, pipe.read(gen, out, 0, 4, 1_000))
        assertArrayEquals(ramp(4), out)
        writer.join()
    }

    @Test
    fun `a new generation clears the end flag`() {
        val pipe = PcmPipe(capacityBytes = 8, endGraceNanos = 0)
        pipe.newGeneration()
        pipe.markEnded()
        val gen = pipe.newGeneration()
        assertEquals(PcmPipe.TIMED_OUT, pipe.read(gen, ByteArray(4), 0, 4, 20))
    }

    @Test
    fun `close releases a blocked writer and fails readers`() {
        val pipe = PcmPipe(capacityBytes = 4)
        val gen = pipe.newGeneration()
        val writer = thread { pipe.write(ramp(12), 0, 12) }
        Thread.sleep(100)
        pipe.close()
        writer.join(1_000)
        assertTrue(!writer.isAlive)
        assertEquals(PcmPipe.SUPERSEDED, pipe.read(gen, ByteArray(4), 0, 4, 20))
    }
}
