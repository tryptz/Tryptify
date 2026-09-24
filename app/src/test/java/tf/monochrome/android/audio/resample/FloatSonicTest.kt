package tf.monochrome.android.audio.resample

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port is only worth anything if it is still Sonic. Media3's own
 * SonicAudioProcessor is the reference: on input a 16-bit file can hold, the
 * float port has to make the same decisions (same frame count) and the same
 * samples to within Sonic's integer rounding. Then the reason for the port —
 * resolution below 16 bits surviving a speed change.
 */
class FloatSonicTest {

    @Test
    fun `matches Media3 Sonic on 16-bit input across speeds and pitches`() {
        val cases = listOf(
            1.5f to 1f, 0.75f to 1f, 2.5f to 1f, 0.4f to 1f, 1.25f to 1.25f, 1f to 0.8f, 1.3f to 1.1f,
        )
        for ((speed, pitch) in cases) {
            val input = testSignal(frames = 44_100, channels = 2, sampleRate = 44_100)
            val reference = runMedia3(input, 44_100, 2, speed, pitch)
            val ours = runFloat16(input, 44_100, 2, speed, pitch)
            assertEquals("frame count at speed=$speed pitch=$pitch", reference.size, ours.size)
            val worst = reference.indices.maxOf { abs(reference[it] - ours[it]) }
            assertTrue("speed=$speed pitch=$pitch differs by $worst LSB", worst <= 3)
        }
    }

    @Test
    fun `detail below 16 bits survives a speed change`() {
        // -110 dBFS: under half a 16-bit LSB, so the old 16-bit path rounded
        // it to digital silence.
        val amplitude = 3.2e-6f
        val sampleRate = 96_000
        val frames = sampleRate
        val input = FloatArray(frames * 2) { i ->
            amplitude * sin(2 * PI * 440.0 * (i / 2) / sampleRate).toFloat()
        }
        val out = runFloat(input, sampleRate, 2, speed = 1.5f, pitch = 1f)
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak", peak > amplitude * 0.5f && peak < amplitude * 1.5f)
        assertTrue("16-bit would round this to zero", (peak * 32768f).roundToInt() == 0)
    }

    @Test
    fun `unity is an exact pass-through, float and 16-bit`() {
        val floats = FloatArray(4_000) { (it % 97) / 97f - 0.5f }
        val sonic = FloatSonic(48_000, 2, 1f, 1f, 48_000)
        sonic.queueInput(floats, 0, 2_000)
        val out = FloatArray(4_000)
        assertEquals(2_000, sonic.getOutput(out, 2_000))
        assertTrue(floats.contentEquals(out))

        val shorts = ShortArray(4_000) { ((it * 37) % 65536 - 32768).toShort() }
        val processor = FloatSonicAudioProcessor()
        processor.setSpeed(1.5f)
        processor.setSpeed(1f) // engaged, back at unity
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertTrue(processor.isActive)
        processor.queueInput(shortsToBuffer(shorts))
        val back = processor.getOutput().order(ByteOrder.nativeOrder()).asShortBuffer()
        val result = ShortArray(back.remaining()).also { back.get(it) }
        assertTrue(shorts.contentEquals(result))
    }

    @Test
    fun `speed can change mid-stream and the output length follows it`() {
        val sampleRate = 48_000
        val sonic = FloatSonic(sampleRate, 2, 1f, 1f, sampleRate)
        val block = testSignalFloat(frames = sampleRate, channels = 2, sampleRate = sampleRate)
        var produced = 0
        val sink = FloatArray(block.size * 3)
        for (speed in listOf(1f, 1.5f, 0.5f, 2f, 1f)) {
            sonic.setSpeed(speed)
            sonic.queueInput(block, 0, sampleRate)
            produced += sonic.getOutput(sink, sonic.outputFrameCountAvailable)
        }
        sonic.queueEndOfStream()
        produced += sonic.getOutput(sink, sonic.outputFrameCountAvailable)
        val expected = sampleRate * (1 + 1 / 1.5 + 2 + 0.5 + 1)
        assertEquals(expected, produced.toDouble(), expected * 0.01)
    }

    @Test
    fun `handles high rates and many channels`() {
        for ((rate, channels) in listOf(192_000 to 2, 48_000 to 6, 44_100 to 1)) {
            val input = testSignalFloat(frames = rate / 2, channels = channels, sampleRate = rate)
            val out = runFloat(input, rate, channels, speed = 1.75f, pitch = 1f)
            val frames = out.size / channels
            val expected = rate / 2 / 1.75
            assertEquals("rate=$rate channels=$channels", expected, frames.toDouble(), expected * 0.02)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** A voice-like signal: a pitched harmonic stack, so Sonic finds real periods. */
    private fun testSignal(frames: Int, channels: Int, sampleRate: Int): ShortArray =
        ShortArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / sampleRate
            val f0 = 140.0 + 30.0 * sin(2 * PI * 0.7 * t)
            val v = 0.45 * sin(2 * PI * f0 * t) + 0.2 * sin(4 * PI * f0 * t + i % channels) +
                0.1 * sin(6 * PI * f0 * t)
            (v * 32767).roundToInt().toShort()
        }

    private fun testSignalFloat(frames: Int, channels: Int, sampleRate: Int): FloatArray =
        testSignal(frames, channels, sampleRate).let { s -> FloatArray(s.size) { s[it] / 32768f } }

    private fun shortsToBuffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).also {
            it.asShortBuffer().put(samples)
        }

    private fun drain(processor: AudioProcessor, input: ByteBuffer, chunkBytes: Int): List<ByteBuffer> {
        val outputs = mutableListOf<ByteBuffer>()
        while (input.hasRemaining()) {
            val slice = input.slice().order(ByteOrder.nativeOrder())
            slice.limit(minOf(chunkBytes, slice.remaining()))
            val take = slice.remaining()
            processor.queueInput(slice)
            input.position(input.position() + take - slice.remaining())
            outputs += copy(processor.getOutput())
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) outputs += copy(processor.getOutput())
        return outputs
    }

    private fun copy(b: ByteBuffer): ByteBuffer {
        val c = ByteBuffer.allocate(b.remaining()).order(ByteOrder.nativeOrder())
        c.put(b)
        c.flip()
        return c
    }

    private fun runMedia3(input: ShortArray, rate: Int, channels: Int, speed: Float, pitch: Float): ShortArray {
        val p = SonicAudioProcessor()
        p.setSpeed(speed)
        p.setPitch(pitch)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush()
        return shortsOf(drain(p, shortsToBuffer(input), 4096))
    }

    private fun runFloat16(input: ShortArray, rate: Int, channels: Int, speed: Float, pitch: Float): ShortArray {
        val p = FloatSonicAudioProcessor()
        p.setSpeed(speed)
        p.setPitch(pitch)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush()
        return shortsOf(drain(p, shortsToBuffer(input), 4096))
    }

    private fun runFloat(input: FloatArray, rate: Int, channels: Int, speed: Float, pitch: Float): FloatArray {
        val p = FloatSonicAudioProcessor()
        p.setSpeed(speed)
        p.setPitch(pitch)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        p.flush()
        val buf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(input)
        val outs = drain(p, buf, 8192)
        val total = outs.sumOf { it.remaining() } / 4
        val result = FloatArray(total)
        var at = 0
        for (o in outs) {
            val fb = o.asFloatBuffer()
            val n = fb.remaining()
            fb.get(result, at, n)
            at += n
        }
        return result
    }

    private fun shortsOf(outs: List<ByteBuffer>): ShortArray {
        val total = outs.sumOf { it.remaining() } / 2
        val result = ShortArray(total)
        var at = 0
        for (o in outs) {
            val sb = o.asShortBuffer()
            val n = sb.remaining()
            sb.get(result, at, n)
            at += n
        }
        return result
    }
}
