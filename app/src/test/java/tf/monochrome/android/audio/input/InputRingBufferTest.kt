package tf.monochrome.android.audio.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the line-in SPSC ring. It is plain Kotlin (no JNI, no
 * Android), so these lock the behaviours the audio thread depends on:
 * exact-length reads, zero-filled underruns, capacity-bounded writes, and the
 * clock-drift trim that stops monitoring latency creeping upward over a long
 * listening session.
 */
class InputRingBufferTest {

    private fun interleaved(frames: Int, base: Float = 0f) =
        FloatArray(frames * 2) { i ->
            val frame = i / 2
            if (i % 2 == 0) base + frame else -(base + frame)
        }

    @Test
    fun `capacity rounds up to a power of two`() {
        assertEquals(1024, InputRingBuffer(1000).capacity)
        assertEquals(1024, InputRingBuffer(1024).capacity)
        // Below the floor and above the ceiling both clamp before rounding.
        assertEquals(InputRingBuffer.MIN_CAPACITY_FRAMES, InputRingBuffer(1).capacity)
    }

    @Test
    fun `write then read round-trips stereo frames`() {
        val ring = InputRingBuffer(1024)
        assertTrue(ring.write(interleaved(64), 64, monoSource = false))

        val l = FloatArray(64)
        val r = FloatArray(64)
        assertEquals(64, ring.read(l, r, 64))
        for (i in 0 until 64) {
            assertEquals(i.toFloat(), l[i], 0f)
            assertEquals(-i.toFloat(), r[i], 0f)
        }
        assertEquals(0, ring.available())
    }

    @Test
    fun `mono source is duplicated to both channels`() {
        val ring = InputRingBuffer(1024)
        val mono = FloatArray(32) { it.toFloat() }
        assertTrue(ring.write(mono, 32, monoSource = true))

        val l = FloatArray(32)
        val r = FloatArray(32)
        ring.read(l, r, 32)
        assertTrue(l.contentEquals(r))
        assertEquals(31f, l[31], 0f)
    }

    @Test
    fun `gain is applied on the way out`() {
        val ring = InputRingBuffer(1024)
        ring.write(FloatArray(8) { 1f }, 8, monoSource = true)
        val l = FloatArray(8)
        val r = FloatArray(8)
        ring.read(l, r, 8, gain = 0.5f)
        assertEquals(0.5f, l[0], 1e-6f)
        assertEquals(0.5f, r[7], 1e-6f)
    }

    @Test
    fun `writes wrap around the end of the buffer`() {
        val ring = InputRingBuffer(256)
        // Drain repeatedly so the cursors run well past one lap of the array.
        val l = FloatArray(64)
        val r = FloatArray(64)
        repeat(10) { round ->
            assertTrue(ring.write(interleaved(64, base = round * 100f), 64, monoSource = false))
            assertEquals(64, ring.read(l, r, 64))
            assertEquals(round * 100f, l[0], 0f)
            assertEquals(round * 100f + 63f, l[63], 0f)
        }
    }

    @Test
    fun `underrun zero-fills the remainder and counts once`() {
        val ring = InputRingBuffer(1024)
        ring.write(interleaved(10), 10, monoSource = false)

        val l = FloatArray(32) { 99f }
        val r = FloatArray(32) { 99f }
        assertEquals(10, ring.read(l, r, 32))
        assertEquals(1L, ring.underruns)
        // Real audio up front, silence after — never a stale repeat.
        assertEquals(9f, l[9], 0f)
        for (i in 10 until 32) {
            assertEquals(0f, l[i], 0f)
            assertEquals(0f, r[i], 0f)
        }
    }

    @Test
    fun `a read with nothing queued is pure silence`() {
        val ring = InputRingBuffer(1024)
        val l = FloatArray(16) { 5f }
        val r = FloatArray(16) { 5f }
        assertEquals(0, ring.read(l, r, 16))
        assertTrue(l.all { it == 0f })
        assertTrue(r.all { it == 0f })
    }

    @Test
    fun `overflowing write is dropped rather than corrupting queued audio`() {
        val ring = InputRingBuffer(256)
        assertTrue(ring.write(interleaved(200), 200, monoSource = false))
        // 200 queued + 100 more exceeds 256, so the incoming block is refused.
        assertFalse(ring.write(interleaved(100, base = 1000f), 100, monoSource = false))
        assertEquals(1L, ring.overruns)
        assertEquals(200, ring.available())

        // What was already queued is untouched.
        val l = FloatArray(200)
        val r = FloatArray(200)
        ring.read(l, r, 200)
        assertEquals(0f, l[0], 0f)
        assertEquals(199f, l[199], 0f)
    }

    @Test
    fun `drift trim caps the backlog at the reader's block multiple`() {
        val ring = InputRingBuffer(4096)
        val block = 128
        // A faster input clock shows up as backlog well past MAX_FILL_BLOCKS.
        ring.write(interleaved(2000), 2000, monoSource = false)
        assertEquals(2000, ring.available())

        val l = FloatArray(block)
        val r = FloatArray(block)
        assertEquals(block, ring.read(l, r, block))

        // Trimmed to TARGET_FILL_BLOCKS, then this read consumed one block.
        val expectedRemaining = block * InputRingBuffer.TARGET_FILL_BLOCKS - block
        assertEquals(expectedRemaining, ring.available())
        assertTrue(ring.driftTrims > 0L)
        // Trimming drops the oldest audio, so the read starts near the head.
        assertEquals(
            (2000 - block * InputRingBuffer.TARGET_FILL_BLOCKS).toFloat(),
            l[0],
            0f,
        )
    }

    @Test
    fun `steady state never trims`() {
        val ring = InputRingBuffer(4096)
        val block = 256
        val l = FloatArray(block)
        val r = FloatArray(block)
        // One block in, one block out — the backlog stays at one block, well
        // under the trim threshold.
        repeat(50) {
            ring.write(interleaved(block), block, monoSource = false)
            assertEquals(block, ring.read(l, r, block))
        }
        assertEquals(0L, ring.driftTrims)
        assertEquals(0L, ring.underruns)
        assertEquals(0L, ring.overruns)
    }

    @Test
    fun `clear drops the backlog and resets the counters`() {
        val ring = InputRingBuffer(1024)
        ring.write(interleaved(64), 64, monoSource = false)
        ring.read(FloatArray(128), FloatArray(128), 128)  // forces an underrun
        assertEquals(1L, ring.underruns)

        ring.clear()
        assertEquals(0, ring.available())
        assertEquals(0L, ring.underruns)
        assertEquals(0L, ring.overruns)
        assertEquals(0L, ring.driftTrims)
    }

    @Test
    fun `read refuses to touch buffers shorter than the request`() {
        val ring = InputRingBuffer(1024)
        ring.write(interleaved(16), 16, monoSource = false)
        val short = FloatArray(8) { 7f }
        assertEquals(0, ring.read(short, FloatArray(64), 64))
        // Left untouched rather than partially written.
        assertTrue(short.all { it == 7f })
    }
}
