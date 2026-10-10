package tf.monochrome.android.audio.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * A decoder asked for float can report float and write 16-bit. Samsung's FLAC
 * decoder did, and Tryptify played it as full-scale static with the position
 * running at double speed. These pin the guard that catches that: on the first
 * buffer with real content, before any of it is passed on, with the frame
 * count put right, and without ever touching audio that really is float.
 */
@OptIn(UnstableApi::class)
class FloatPcmGuardTest {

    private val rate = 44_100
    private val stereoFloat = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)

    private fun guard(log: MutableList<String> = mutableListOf()) =
        FloatPcmGuard { log += it }.apply {
            configure(stereoFloat)
            flush()
        }

    /** A stereo 440 Hz sine as 16-bit samples, the way the decoder really wrote it. */
    private fun int16Sine(frames: Int, amplitude: Int = 12_000, from: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (amplitude * sin(2 * PI * 440 * (from + i) / rate)).toInt().toShort()
            b.putShort(s).putShort(s)
        }
        b.flip()
        return b
    }

    private fun floatSine(frames: Int, amplitude: Float = 0.5f, from: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (amplitude * sin(2 * PI * 440 * (from + i) / rate)).toFloat()
            b.putFloat(s).putFloat(s)
        }
        b.flip()
        return b
    }

    private fun floats(b: ByteBuffer): FloatArray =
        FloatArray(b.remaining() / 4) { b.getFloat(b.position() + it * 4) }

    private fun run(g: FloatPcmGuard, input: ByteBuffer): FloatArray {
        g.queueInput(input)
        return floats(g.getOutput())
    }

    @Test
    fun `float audio passes through untouched`() {
        val log = mutableListOf<String>()
        val g = guard(log)
        val input = floatSine(4096)
        val expected = floats(input)
        val out = run(g, input)
        assertTrue(expected.contentEquals(out))
        assertFalse(input.hasRemaining())
        assertFalse(g.readingAsInt16)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `16-bit audio declared as float is caught on its first buffer and read as 16-bit`() {
        val log = mutableListOf<String>()
        val g = guard(log)
        // 4608 frames: a FLAC block, as in the log that showed this.
        val input = int16Sine(4608)
        val samples = ShortArray(4608 * 2) { input.getShort(it * 2) }
        val out = run(g, input)

        assertTrue(g.readingAsInt16)
        assertEquals(1, log.size)
        assertFalse(input.hasRemaining())
        // Every sample, exactly, and all 4608 frames: read as float it was
        // 2304, which is what doubled the play position's speed.
        assertEquals(samples.size, out.size)
        for (i in samples.indices) assertEquals(samples[i] / 32768f, out[i], 0f)
    }

    @Test
    fun `quiet 16-bit audio is caught too`() {
        // A small negative sample's bit pattern is NaN when read as float, so
        // a near-silent passage gives itself away as surely as a loud one.
        val g = guard()
        run(g, int16Sine(1024, amplitude = 40))
        assertTrue(g.readingAsInt16)
    }

    @Test
    fun `digital silence decides nothing`() {
        // Zero is zero read either way: it plays correctly and proves nothing.
        val g = guard()
        val out = run(g, int16Sine(1024, amplitude = 0))
        assertTrue(out.all { it == 0f })
        assertFalse(g.readingAsInt16)
        // The music after it is what gives the answer.
        run(g, int16Sine(1024, from = 1024))
        assertTrue(g.readingAsInt16)
    }

    @Test
    fun `a few stray values do not decide it`() {
        val g = guard()
        val input = floatSine(4096)
        input.putFloat(0, Float.NaN)
        input.putFloat(400, 1e9f)
        input.putFloat(800, -1e9f)
        val expected = floats(input)
        val out = run(g, input)
        assertFalse(g.readingAsInt16)
        assertTrue(expected.contentEquals(out))
    }

    @Test
    fun `classifying does not consume`() {
        val g = guard()
        val input = int16Sine(512)
        g.classify(input)
        assertEquals(512 * 4, input.remaining())
        assertTrue(g.readingAsInt16)
    }

    @Test
    fun `floatBytes is the buffer's length as float`() {
        val g = guard()
        assertEquals(4096, g.floatBytes(4096))
        g.classify(int16Sine(512))
        assertEquals(8192, g.floatBytes(4096))
    }

    @Test
    fun `a seek keeps the verdict and a new stream clears it`() {
        val g = guard()
        run(g, int16Sine(1024))
        assertTrue(g.readingAsInt16)

        g.flush() // a seek: same decoder
        assertTrue(g.readingAsInt16)

        g.configure(stereoFloat)
        g.flush() // a new stream, perhaps a new decoder
        assertFalse(g.readingAsInt16)
        val input = floatSine(1024)
        val expected = floats(input)
        assertTrue(expected.contentEquals(run(g, input)))
    }

    @Test
    fun `a stream trusted after a second of audible float is not scanned again`() {
        val g = guard()
        var at = 0
        while (at < rate) {
            run(g, floatSine(4410, from = at))
            at += 4410
        }
        run(g, int16Sine(1024))
        assertFalse(g.readingAsInt16)
    }

    @Test
    fun `the sink's look-ahead does not count toward trust`() {
        // What the sink does with each buffer: classify ahead for its timing,
        // then the chain's queueInput. Counted twice, 0.6 s of float trusted
        // the stream, and 16-bit after it went through as static.
        val g = guard()
        var at = 0
        while (at < rate * 6 / 10) {
            val b = floatSine(4410, from = at)
            g.classify(b, countTrust = false)
            run(g, b)
            at += 4410
        }
        run(g, int16Sine(1024))
        assertTrue(g.readingAsInt16)
    }

    @Test
    fun `a non-float stream after a 16-bit one is not counted double`() {
        val g = guard()
        run(g, int16Sine(1024))
        assertEquals(8192, g.floatBytes(4096))
        g.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
        g.flush()
        assertEquals(4096, g.floatBytes(4096))
    }

    @Test
    fun `a part-frame left over is consumed, not left to stall the sink`() {
        // Digital silence keeps the stream unverified, and an odd number of
        // 16-bit stereo frames is half a float frame short.
        val g = guard()
        val silence = ByteBuffer.allocateDirect(4097 * 4).order(ByteOrder.LITTLE_ENDIAN)
        silence.position(silence.limit())
        silence.flip()
        val out = run(g, silence)
        assertFalse(silence.hasRemaining())
        // 16,388 bytes: 2,048 whole float frames (4,096 samples) and 4 left over.
        assertEquals(4096, out.size)
        assertFalse(g.readingAsInt16)
    }

    @Test
    fun `only float input is taken`() {
        val g = FloatPcmGuard {}
        val out = g.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, out)
        g.flush()
        assertFalse(g.isActive)
    }

    @Test
    fun `in the chain, the stages after it see every frame`() {
        // What the sink's hi-res chain does with it: the guard first, then a
        // stage that counts what it is given.
        var framesSeen = 0
        val counter = object : androidx.media3.common.audio.BaseAudioProcessor() {
            override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat
            override fun queueInput(inputBuffer: ByteBuffer) {
                val n = inputBuffer.remaining()
                framesSeen += n / 8
                replaceOutputBuffer(n).put(inputBuffer).flip()
            }
        }
        val chain = AudioProcessorChain(listOf(FloatPcmGuard {}, counter)) {}
        chain.configure(stereoFloat)
        chain.process(int16Sine(4608))
        assertEquals(4608, framesSeen)
    }
}
