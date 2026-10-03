package tf.monochrome.android.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The hi-res path's chain holds audio inside its stages — a time-stretcher's
 * window, a resampler's history. At the end of a track that audio has to come
 * out, or the last few hundred milliseconds are never heard.
 */
class AudioProcessorChainTest {

    /** Passes bytes through [hold] bytes late, releasing the rest only at end of stream. */
    private class Delay(private val hold: Int) : BaseAudioProcessor() {
        private val held = java.io.ByteArrayOutputStream()
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat
        override fun queueInput(inputBuffer: ByteBuffer) {
            while (inputBuffer.hasRemaining()) held.write(inputBuffer.get().toInt())
            val all = held.toByteArray()
            val out = (all.size - hold).coerceAtLeast(0)
            emit(all.copyOfRange(0, out))
            held.reset()
            held.write(all, out, all.size - out)
        }
        override fun onQueueEndOfStream() {
            emit(held.toByteArray())
            held.reset()
        }
        override fun onFlush() = held.reset()
        private fun emit(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            replaceOutputBuffer(bytes.size).put(bytes).flip()
        }
    }

    private val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)

    private fun collect(chain: AudioProcessorChain, input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun take(b: ByteBuffer) { while (b.hasRemaining()) out.write(b.get().toInt()) }
        take(chain.process(ByteBuffer.wrap(input).order(ByteOrder.nativeOrder())))
        chain.queueEndOfStream()
        var steps = 0
        while (!chain.isEnded() && steps++ < 16) take(chain.process(AudioProcessor.EMPTY_BUFFER))
        return out.toByteArray()
    }

    @Test
    fun `everything a stage held comes out at end of stream, through every stage`() {
        val chain = AudioProcessorChain(listOf(Delay(6), Delay(4))) {}
        chain.configure(format)
        val input = ByteArray(20) { it.toByte() }
        assertTrue(input.contentEquals(collect(chain, input)))
        assertTrue(chain.isEnded())
    }

    @Test
    fun `a flush starts the next stream un-ended`() {
        val chain = AudioProcessorChain(listOf(Delay(4))) {}
        chain.configure(format)
        collect(chain, ByteArray(8))
        chain.flush()
        assertFalse(chain.isEnded())
        val out = chain.process(ByteBuffer.wrap(ByteArray(8)))
        assertEquals(4, out.remaining())
    }
}
