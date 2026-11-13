package tf.monochrome.android.audio.atmos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The raw frames the Atmos stage pairs with the decoded audio. They have to
 * outlive a flush of the audio chain (which also runs when a song is first
 * configured, after the decoder has been handed frames ahead) and go only
 * when playback really jumps.
 */
class AtmosFrameBufferTest {

    private val frameUs = 32_000L
    private fun frame(n: Int) = byteArrayOf(n.toByte())

    @Test
    fun `frames in a row are kept, and the oldest is the first of the song`() {
        val buffer = AtmosFrameBuffer()
        for (n in 0 until 20) buffer.put(n * frameUs, frame(n))
        assertEquals(20, buffer.size())
        assertEquals(0L, buffer.peekTimeUs())
        assertEquals(3, buffer.frameForTime(3 * frameUs + frameUs / 2)!!.bytes[0].toInt())
    }

    @Test
    fun `a seek back or forward drops what was buffered`() {
        val buffer = AtmosFrameBuffer()
        for (n in 100 until 110) buffer.put(n * frameUs, frame(n))
        buffer.put(5 * frameUs, frame(5))            // back
        assertEquals(1, buffer.size())
        assertEquals(5 * frameUs, buffer.peekTimeUs())
        buffer.put(6 * frameUs, frame(6))
        buffer.put(900 * frameUs, frame(7))          // forward
        assertEquals(1, buffer.size())
        assertEquals(900 * frameUs, buffer.peekTimeUs())
    }

    @Test
    fun `the next song, whose times start again, drops the last one's frames`() {
        val buffer = AtmosFrameBuffer()
        for (n in 5000 until 5010) buffer.put(n * frameUs, frame(1))
        buffer.put(0L, frame(2))
        assertEquals(0L, buffer.peekTimeUs())
        assertEquals(1, buffer.size())
    }

    @Test
    fun `a little jitter between frames is not a jump`() {
        val buffer = AtmosFrameBuffer()
        buffer.put(0L, frame(0))
        buffer.put(frameUs + 700, frame(1))
        buffer.put(2 * frameUs - 400, frame(2))
        assertEquals(3, buffer.size())
    }

    @Test
    fun `nothing buffered answers nothing`() {
        val buffer = AtmosFrameBuffer()
        assertEquals(Long.MIN_VALUE, buffer.peekTimeUs())
        assertNull(buffer.frameForTime(1_000_000L))
    }
}
