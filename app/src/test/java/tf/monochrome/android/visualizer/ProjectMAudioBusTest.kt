package tf.monochrome.android.visualizer

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio the visualizer is allowed to see, and when.
 *
 * The tap sits upstream of the output device, so the picture always runs a
 * little ahead of the sound; over Bluetooth that gap is wide enough to watch.
 * What is asserted here is the withholding itself -- that a frame is not handed
 * over until it has aged past the delay -- because that is the whole mechanism,
 * and the thing that would silently revert to the old behaviour.
 */
class ProjectMAudioBusTest {

    private fun bus() = ProjectMAudioBus().apply { acquire() }

    private fun ProjectMAudioBus.publishOne() =
        publish(FloatArray(256) { 0.1f }, channelCount = 2, sampleRate = 48_000)

    @Test
    fun `with no delay every frame is handed over at once`() {
        val bus = bus()
        repeat(3) { bus.publishOne() }
        assertEquals(3, bus.drainAll().size)
    }

    @Test
    fun `a delay withholds audio that has not come due`() {
        val bus = bus()
        bus.setDelayMs(400)
        bus.publishOne()
        // Published this instant, so it is 400ms short of being due.
        assertTrue("a fresh frame must not be released", bus.drainAll().isEmpty())
    }

    @Test
    fun `audio held back is kept, not dropped`() {
        val bus = bus()
        bus.setDelayMs(400)
        // Comfortably more than the eight frames the queue used to cap at: the
        // bound is the delay window now, and trimming to a frame count would
        // throw this audio away before it ever came due.
        repeat(40) { bus.publishOne() }
        bus.setDelayMs(0)
        assertEquals(40, bus.drainAll().size)
    }

    @Test
    fun `the delay is clamped to the range the slider offers`() {
        val bus = bus()
        bus.setDelayMs(-50)
        bus.publishOne()
        assertEquals("a negative delay must behave as none", 1, bus.drainAll().size)

        bus.setDelayMs(ProjectMAudioBus.MAX_DELAY_MS * 10)
        bus.publishOne()
        assertTrue(bus.drainAll().isEmpty())
    }

    /** [frames] frames of [size] samples, frame k all k, on a stream whose ring holds 200 samples. */
    private fun ProjectMAudioBus.publishNumbered(from: Int, frames: Int, size: Int = 64) {
        for (k in from until from + frames) {
            publish(FloatArray(size) { k.toFloat() }, channelCount = 1, sampleRate = 100)
        }
    }

    private fun List<ProjectMAudioFrame>.numbers() = map { frame ->
        assertTrue("a frame is one number throughout", frame.samples.all { it == frame.samples[0] })
        frame.samples[0].toInt()
    }

    @Test
    fun `frames come out whole and in order as the ring wraps`() {
        val bus = bus()
        val seen = mutableListOf<Int>()
        // Three 64-sample frames fill a lap of the 200-sample ring, so this
        // wraps a dozen times; drained as it goes, nothing is lost.
        for (k in 0 until 40 step 2) {
            bus.publishNumbered(k, 2)
            seen += bus.drainAll().numbers()
        }
        assertEquals((0 until 40).toList(), seen)
    }

    @Test
    fun `a full ring drops the oldest audio, never the newest`() {
        val bus = bus()
        bus.publishNumbered(0, 10)
        assertEquals(listOf(7, 8, 9), bus.drainAll().numbers())
    }

    @Test
    fun `16-bit PCM is scaled and clamped as it always was`() {
        val bus = bus()
        val shorts = shortArrayOf(0, 1, -1, 16384, -16384, 32767, -32768)
        val pcm = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        shorts.forEach { pcm.putShort(it) }
        pcm.flip()
        bus.publishPcm(pcm, C.ENCODING_PCM_16BIT, channelCount = 1, sampleRate = 48_000)
        val expected = FloatArray(shorts.size) { (shorts[it] / 32768f).coerceIn(-1f, 1f) }
        assertArrayEquals(expected, bus.drainAll().single().samples, 0f)
        assertArrayEquals(expected, bus.peekSamples(), 0f)
        assertEquals("the buffer itself is left where it was", 0, pcm.position())
    }

    @Test
    fun `float PCM is clamped and 8-bit centred, as before`() {
        val bus = bus()
        val floats = floatArrayOf(0f, 0.5f, -0.25f, 1.5f, -3f)
        val pcm = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        floats.forEach { pcm.putFloat(it) }
        pcm.flip()
        bus.publishPcm(pcm, C.ENCODING_PCM_FLOAT, channelCount = 1, sampleRate = 48_000)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.25f, 1f, -1f), bus.drainAll().single().samples, 0f)

        val bytes = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        bytes.put(0.toByte()).put(128.toByte()).put(255.toByte()).flip()
        bus.publishPcm(bytes, C.ENCODING_PCM_8BIT, channelCount = 1, sampleRate = 48_000)
        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128f), bus.drainAll().single().samples, 0f)
    }

    @Test
    fun `the waveform snapshot is the latest buffer, its own length`() {
        val bus = bus()
        bus.publishNumbered(0, 1, size = 64)
        bus.publishNumbered(1, 1, size = 32)
        val snap = bus.peekSamples()!!
        assertEquals(32, snap.size)
        assertTrue(snap.all { it == 1f })
    }

    @Test
    fun `audio published with nobody listening is let go`() {
        val bus = bus()
        bus.setDelayMs(400)
        bus.publishNumbered(0, 2)
        bus.release()
        bus.idle()
        bus.acquire()
        bus.setDelayMs(0)
        assertTrue(bus.drainAll().isEmpty())
        bus.publishNumbered(5, 1)
        assertEquals(listOf(5), bus.drainAll().numbers())
    }

    @Test
    fun `clear empties the queue and the snapshot`() {
        val bus = bus()
        bus.publishOne()
        bus.clear()
        assertTrue(bus.drainAll().isEmpty())
        assertNull(bus.peekSamples())
    }

    @Test
    fun `the waveform snapshot is not delayed`() {
        // It feeds an overlay that draws whether or not the engine is running,
        // so holding it back here would freeze it whenever nothing drained.
        val bus = bus()
        bus.setDelayMs(400)
        bus.publishOne()
        assertTrue(bus.peekSamples() != null)
    }
}
