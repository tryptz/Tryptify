package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The exclusive USB stream fitted to a width the DAC has a format of: which
 * width, and that the song's samples go out untouched in its first channels.
 */
class DacChannelFitTest {

    @Test
    fun `a width the DAC has is the width played`() {
        assertEquals(2, DacChannelFit.pickWidth(2, listOf(2, 8)))
        assertEquals(8, DacChannelFit.pickWidth(8, listOf(2, 8)))
        assertEquals(6, DacChannelFit.pickWidth(6, listOf(2, 6, 8)))
    }

    @Test
    fun `a width it lacks goes out at the narrowest that holds it`() {
        // 5.1 on a 7.1 interface: its eight, never its two.
        assertEquals(8, DacChannelFit.pickWidth(6, listOf(2, 8)))
        // Stereo on an interface with an eight-channel format alone.
        assertEquals(8, DacChannelFit.pickWidth(2, listOf(8)))
        assertEquals(6, DacChannelFit.pickWidth(4, listOf(2, 6, 8)))
        assertEquals(12, DacChannelFit.pickWidth(10, listOf(2, 8, 12, 16)))
    }

    @Test
    fun `a song wider than every format is not fitted here`() {
        // 7.1.4 on a 7.1 interface: folded earlier, or left to Android.
        assertNull(DacChannelFit.pickWidth(12, listOf(2, 8)))
        assertNull(DacChannelFit.pickWidth(6, listOf(2)))
        // A DAC that told no widths, and nonsense.
        assertNull(DacChannelFit.pickWidth(6, emptyList()))
        assertNull(DacChannelFit.pickWidth(0, listOf(2, 8)))
        // Nothing wider than the pipeline carries is picked.
        assertNull(DacChannelFit.pickWidth(6, listOf(2, 32)))
    }

    private fun run(fit: DacChannelFit, bytes: ByteArray): ByteArray {
        val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        input.put(bytes).flip()
        fit.queueInput(input)
        assertEquals("all of the input is taken", 0, input.remaining())
        val out = fit.output
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun `off, it is not in the chain`() {
        val fit = DacChannelFit()
        assertEquals(AudioFormat.NOT_SET, fit.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)))
        assertFalse(fit.isActive)
        // The song's own width asked for is no fitting either.
        fit.targetChannels = 6
        assertEquals(AudioFormat.NOT_SET, fit.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)))
        assertFalse(fit.isActive)
    }

    @Test
    fun `a 5_1 song goes out bit for bit in the first six of eight, the rest silence`() {
        for ((encoding, size) in listOf(
            C.ENCODING_PCM_16BIT to 2, C.ENCODING_PCM_24BIT to 3, C.ENCODING_PCM_32BIT to 4, C.ENCODING_PCM_FLOAT to 4,
        )) {
            val fit = DacChannelFit().apply { targetChannels = 8 }
            val out = fit.configure(AudioFormat(96000, 6, encoding))
            assertEquals(AudioFormat(96000, 8, encoding), out)
            assertTrue(fit.isActive)
            val frames = 257
            // Every byte another value, none of them zero.
            val song = ByteArray(frames * 6 * size) { (1 + it % 251).toByte() }
            val played = run(fit, song)
            assertEquals(frames * 8 * size, played.size)
            for (f in 0 until frames) {
                for (b in 0 until 6 * size) {
                    assertEquals("encoding $encoding frame $f byte $b", song[f * 6 * size + b], played[f * 8 * size + b])
                }
                for (b in 6 * size until 8 * size) assertEquals(0.toByte(), played[f * 8 * size + b])
            }
            // A second block, smaller and larger than the first: nothing of the first is left in it.
            for (n in listOf(3, 1000)) {
                val next = ByteArray(n * 6 * size) { (7 + it % 199).toByte() }
                val again = run(fit, next)
                assertEquals(n * 8 * size, again.size)
                assertEquals(next[0], again[0])
                assertEquals(next[next.size - 1], again[(n - 1) * 8 * size + 6 * size - 1])
                assertEquals(0.toByte(), again[again.size - 1])
            }
        }
    }

    @Test
    fun `stereo into eight is the front pair and six silent channels`() {
        val fit = DacChannelFit().apply { targetChannels = 8 }
        fit.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        val played = run(fit, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(32, played.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), played.take(4))
        assertTrue(played.slice(4 until 16).all { it == 0.toByte() })
        assertEquals(listOf<Byte>(5, 6, 7, 8), played.slice(16 until 20))
        assertTrue(played.slice(20 until 32).all { it == 0.toByte() })
    }

    @Test
    fun `it never narrows, and never guesses at an encoding it cannot size`() {
        val narrow = DacChannelFit().apply { targetChannels = 2 }
        assertTrue(runCatching { narrow.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)) }
            .exceptionOrNull() is AudioProcessor.UnhandledAudioFormatException)
        assertFalse(narrow.isActive)
        val odd = DacChannelFit().apply { targetChannels = 8 }
        assertTrue(runCatching { odd.configure(AudioFormat(48000, 6, C.ENCODING_PCM_8BIT)) }
            .exceptionOrNull() is AudioProcessor.UnhandledAudioFormatException)
        val wide = DacChannelFit().apply { targetChannels = 32 }
        assertTrue(runCatching { wide.configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT)) }
            .exceptionOrNull() is AudioProcessor.UnhandledAudioFormatException)
    }

    @Test
    fun `the end of the stream is passed on once its last block is taken`() {
        val fit = DacChannelFit().apply { targetChannels = 8 }
        fit.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT))
        val input = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder())
        fit.queueInput(input)
        fit.queueEndOfStream()
        assertFalse(fit.isEnded)
        assertEquals(16, fit.output.remaining())
        assertTrue(fit.isEnded)
        fit.flush()
        assertFalse(fit.isEnded)
    }
}
