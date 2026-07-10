package tf.monochrome.android.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM tests for the ITU-R BS.775 multichannel → stereo fold-down.
 *
 * Expected normalized coefficients (rows sum to 1.0, a = 0.70711, LFE = 0):
 *   6 ch (FL FR FC LFE BL BR): L = [0.41421, 0, 0.29289, 0, 0.29289, 0]
 *   7 ch (FL FR FC LFE BC SL SR): L = [0.32037, 0, 0.22654, 0, 0.22654, 0.22654, 0]
 *   8 ch (FL FR FC LFE BL BR SL SR): L = [0.32037, 0, 0.22654, 0, 0.22654, 0, 0.22654, 0]
 */
class DownmixProcessorTest {

    private val floatTol = 1e-4f

    private fun processor(enabled: Boolean = true) = DownmixProcessor().apply { setEnabled(enabled) }

    private fun configureAndFlush(p: DownmixProcessor, sampleRate: Int, channels: Int, encoding: Int): AudioFormat {
        val out = p.configure(AudioFormat(sampleRate, channels, encoding))
        p.flush()
        return out
    }

    /** Interleave float frames (each FloatArray = one frame of N channels). */
    private fun floatBuffer(vararg frames: FloatArray): ByteBuffer {
        val ch = frames[0].size
        val buf = ByteBuffer.allocateDirect(frames.size * ch * 4).order(ByteOrder.nativeOrder())
        for (frame in frames) for (s in frame) buf.putFloat(s)
        buf.flip()
        return buf
    }

    /** Interleave PCM16 frames from float [-1, 1) values. */
    private fun pcm16Buffer(vararg frames: FloatArray): ByteBuffer {
        val ch = frames[0].size
        val buf = ByteBuffer.allocateDirect(frames.size * ch * 2).order(ByteOrder.nativeOrder())
        for (frame in frames) for (s in frame) {
            buf.putShort((s * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        }
        buf.flip()
        return buf
    }

    private fun drainFloats(p: DownmixProcessor, input: ByteBuffer): FloatArray {
        p.queueInput(input)
        val out = p.getOutput()
        val result = FloatArray(out.remaining() / 4)
        for (i in result.indices) result[i] = out.getFloat(out.position() + i * 4)
        return result
    }

    private fun drainShorts(p: DownmixProcessor, input: ByteBuffer): ShortArray {
        p.queueInput(input)
        val out = p.getOutput()
        val result = ShortArray(out.remaining() / 2)
        for (i in result.indices) result[i] = out.getShort(out.position() + i * 2)
        return result
    }

    // ── Matrix vectors, 5.1 float ────────────────────────────────────────

    @Test
    fun `5_1 float - FL only folds to left at 0_41421`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(2, out.size)
        assertEquals(0.41421f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `5_1 float - FR only folds to right`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)))
        assertEquals(0f, out[0], floatTol)
        assertEquals(0.41421f, out[1], floatTol)
    }

    @Test
    fun `5_1 float - center feeds both sides equally at 0_29289`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f)))
        assertEquals(0.29289f, out[0], floatTol)
        assertEquals(0.29289f, out[1], floatTol)
        assertEquals(out[0], out[1], 0f)
    }

    @Test
    fun `5_1 float - BL only folds to left only`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f)))
        assertEquals(0.29289f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `5_1 - LFE is dropped exactly`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)))
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    // ── Clipping / normalization ─────────────────────────────────────────

    @Test
    fun `all channels full-scale never exceeds unity (float)`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(FloatArray(6) { 1f }))
        // Row sums are exactly 1.0 → full-scale everywhere folds to ~1.0.
        assertEquals(1f, out[0], floatTol)
        assertEquals(1f, out[1], floatTol)
        assertTrue(out[0] <= 1f + floatTol && out[1] <= 1f + floatTol)
    }

    @Test
    fun `all channels full-scale does not wrap (pcm16)`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_16BIT)
        val out = drainShorts(p, pcm16Buffer(FloatArray(6) { 0.9999f }))
        // Must clamp at positive full scale, not wrap negative.
        assertTrue("left wrapped: ${out[0]}", out[0] > 30000)
        assertTrue("right wrapped: ${out[1]}", out[1] > 30000)
    }

    // ── PCM16 path ───────────────────────────────────────────────────────

    @Test
    fun `pcm16 matrix and buffer sizing - 6ch in, 2ch out, frames preserved`() {
        val p = processor()
        val outFormat = configureAndFlush(p, 44100, 6, C.ENCODING_PCM_16BIT)
        assertEquals(2, outFormat.channelCount)
        assertEquals(44100, outFormat.sampleRate)
        assertEquals(C.ENCODING_PCM_16BIT, outFormat.encoding)

        val frames = arrayOf(
            floatArrayOf(0.5f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0.5f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.9f, 0f, 0f),
        )
        val out = drainShorts(p, pcm16Buffer(*frames))
        assertEquals(6, out.size) // 3 frames × 2 channels
        // Frame 0: FL=0.5 → L ≈ 0.5·0.41421·32768 ≈ 6787
        assertEquals(6787f, out[0].toFloat(), 3f)
        assertEquals(0f, out[1].toFloat(), 1f)
        // Frame 1: FC=0.5 → both ≈ 0.5·0.29289·32768 ≈ 4799
        assertEquals(4799f, out[2].toFloat(), 3f)
        assertEquals(4799f, out[3].toFloat(), 3f)
        // Frame 2: LFE only → silence
        assertEquals(0, out[4].toInt())
        assertEquals(0, out[5].toInt())
    }

    // ── Active / inactive lifecycle ──────────────────────────────────────

    @Test
    fun `stereo and mono input leave the processor inactive`() {
        val p = processor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT)))
        assertFalse(p.isActive)
    }

    @Test
    fun `reconfigure 5_1 to stereo to 5_1 keeps isActive consistent`() {
        val p = processor()
        // Regression for Media3's AudioProcessingPipeline checkState():
        // an inactive processor must not linger active from a prior format.
        val out1 = configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        assertTrue(p.isActive)
        assertEquals(2, out1.channelCount)

        val out2 = p.configure(AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioFormat.NOT_SET, out2)
        assertFalse(p.isActive)
        p.flush()
        assertFalse(p.isActive)

        val out3 = configureAndFlush(p, 96000, 6, C.ENCODING_PCM_FLOAT)
        assertTrue(p.isActive)
        assertEquals(2, out3.channelCount)
        assertEquals(96000, out3.sampleRate)
        // And it still mixes correctly after the round trip.
        val folded = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f)))
        assertEquals(0.29289f, folded[0], floatTol)
    }

    @Test
    fun `flush without reconfigure (seek) keeps the active format`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        p.flush() // seek: flush() with no configure()
        assertTrue(p.isActive)
        val out = drainFloats(p, floatBuffer(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(0.41421f, out[0], floatTol)
    }

    @Test
    fun `disabled processor is inactive for multichannel (passthrough mode)`() {
        val p = processor(enabled = false)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
    }

    // ── Unsupported formats ──────────────────────────────────────────────

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `24-bit encoding throws`() {
        processor().configure(AudioFormat(48000, 6, C.ENCODING_PCM_24BIT))
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `9 channels throws`() {
        processor().configure(AudioFormat(48000, 9, C.ENCODING_PCM_FLOAT))
    }

    // ── Output format mapping ────────────────────────────────────────────

    @Test
    fun `configure maps 48000-6-float to 48000-2-float`() {
        val out = processor().configure(AudioFormat(48000, 6, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT), out)
    }

    // ── Buffer reuse across calls ────────────────────────────────────────

    @Test
    fun `consecutive queueInput-getOutput rounds stay correct`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val first = drainFloats(p, floatBuffer(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(0.41421f, first[0], floatTol)
        // getOutput() swapped in EMPTY_BUFFER; a second round must re-fill.
        assertEquals(0, p.getOutput().remaining())
        val second = drainFloats(p, floatBuffer(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)))
        assertEquals(0.41421f, second[1], floatTol)
        assertEquals(0f, second[0], floatTol)
    }

    // ── 7.1 and 6.1 spot vectors ─────────────────────────────────────────

    @Test
    fun `7_1 - SL folds to left at 0_22654`() {
        val p = processor()
        configureAndFlush(p, 48000, 8, C.ENCODING_PCM_FLOAT)
        // 8 ch order: FL FR FC LFE BL BR SL SR
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        assertEquals(0.22654f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `6_1 - back-center feeds both sides equally`() {
        val p = processor()
        configureAndFlush(p, 48000, 7, C.ENCODING_PCM_FLOAT)
        // 7 ch order: FL FR FC LFE BC SL SR
        val out = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f)))
        assertEquals(0.22654f, out[0], floatTol)
        assertEquals(0.22654f, out[1], floatTol)
    }

    @Test
    fun `3_0 - center at 0_41421, fronts at 0_58579`() {
        val p = processor()
        configureAndFlush(p, 48000, 3, C.ENCODING_PCM_FLOAT)
        val center = drainFloats(p, floatBuffer(floatArrayOf(0f, 0f, 1f)))
        assertEquals(0.41421f, center[0], floatTol)
        assertEquals(0.41421f, center[1], floatTol)
        val fl = drainFloats(p, floatBuffer(floatArrayOf(1f, 0f, 0f)))
        assertEquals(0.58579f, fl[0], floatTol)
        assertEquals(0f, fl[1], floatTol)
    }
}
