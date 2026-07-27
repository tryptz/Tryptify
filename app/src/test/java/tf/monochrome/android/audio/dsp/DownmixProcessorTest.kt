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
 * Pure-JVM tests for the fixed-matrix multichannel → stereo fold-down.
 *
 * Expected coefficients (verbatim, no normalization):
 *   FL/BL/BLC/SL/TFL/TSL/TBL → [1, 0]
 *   FR/BR/BRC/SR/TFR/TSR/TBR → [0, 1]
 *   FC (and BC in 6.1)       → [0.70710678, 0.70710678]
 *   LFE                      → [2.26464431, 2.26464431]
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

    /** One N-channel frame with a single channel at the given level. */
    private fun soloFrame(channels: Int, index: Int, level: Float = 1f) =
        FloatArray(channels).also { it[index] = level }

    // ── Matrix vectors, 5.1 float ────────────────────────────────────────

    @Test
    fun `5_1 float - FL only folds hard left at unity`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 0)))
        assertEquals(2, out.size)
        assertEquals(1f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `5_1 float - FR only folds hard right at unity`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 1)))
        assertEquals(0f, out[0], floatTol)
        assertEquals(1f, out[1], floatTol)
    }

    @Test
    fun `5_1 float - center feeds both sides equally at 0_70711`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 2)))
        assertEquals(0.70711f, out[0], floatTol)
        assertEquals(0.70711f, out[1], floatTol)
        assertEquals(out[0], out[1], 0f)
    }

    @Test
    fun `5_1 float - BL only folds hard left at unity`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 4)))
        assertEquals(1f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `5_1 - LFE feeds both sides at 2_26464431`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 3)))
        assertEquals(2.26464431f, out[0], floatTol)
        assertEquals(2.26464431f, out[1], floatTol)
    }

    // ── Level / clipping behaviour ───────────────────────────────────────

    @Test
    fun `all channels full-scale sums verbatim on the float path`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(FloatArray(6) { 1f }))
        // 1 (FL) + 0.70710678 (FC) + 2.26464431 (LFE) + 1 (BL) — no
        // normalization, so the float path is allowed to exceed unity.
        val expected = 1f + 0.70710678f + 2.26464431f + 1f
        assertEquals(expected, out[0], floatTol)
        assertEquals(expected, out[1], floatTol)
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
        // Frame 0: FL=0.5 → L = 0.5·1.0·32768 = 16384
        assertEquals(16384f, out[0].toFloat(), 3f)
        assertEquals(0f, out[1].toFloat(), 1f)
        // Frame 1: FC=0.5 → both = 0.5·0.70710678·32768 ≈ 11585
        assertEquals(11585f, out[2].toFloat(), 3f)
        assertEquals(11585f, out[3].toFloat(), 3f)
        // Frame 2: LFE=0.9 → 0.9·2.26464431 ≈ 2.038 → clamps at the rail.
        assertEquals(32767, out[4].toInt())
        assertEquals(32767, out[5].toInt())
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
        val folded = drainFloats(p, floatBuffer(soloFrame(6, 2)))
        assertEquals(0.70711f, folded[0], floatTol)
    }

    @Test
    fun `flush without reconfigure (seek) keeps the active format`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        p.flush() // seek: flush() with no configure()
        assertTrue(p.isActive)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 0)))
        assertEquals(1f, out[0], floatTol)
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

    @Test
    fun `9 channels (no known layout) passes through inactive`() {
        val p = processor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48000, 9, C.ENCODING_PCM_FLOAT)))
        assertFalse(p.isActive)
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun `17 channels throws`() {
        processor().configure(AudioFormat(48000, 17, C.ENCODING_PCM_FLOAT))
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
        val first = drainFloats(p, floatBuffer(soloFrame(6, 0)))
        assertEquals(1f, first[0], floatTol)
        // getOutput() swapped in EMPTY_BUFFER; a second round must re-fill.
        assertEquals(0, p.getOutput().remaining())
        val second = drainFloats(p, floatBuffer(soloFrame(6, 1)))
        assertEquals(1f, second[1], floatTol)
        assertEquals(0f, second[0], floatTol)
    }

    // ── 7.1 and 6.1 spot vectors ─────────────────────────────────────────

    @Test
    fun `7_1 - SL folds hard left at unity`() {
        val p = processor()
        configureAndFlush(p, 48000, 8, C.ENCODING_PCM_FLOAT)
        // 8 ch order: FL FR FC LFE BL BR SL SR
        val out = drainFloats(p, floatBuffer(soloFrame(8, 6)))
        assertEquals(1f, out[0], floatTol)
        assertEquals(0f, out[1], floatTol)
    }

    @Test
    fun `6_1 - back-center feeds both sides equally`() {
        val p = processor()
        configureAndFlush(p, 48000, 7, C.ENCODING_PCM_FLOAT)
        // 7 ch order: FL FR FC LFE BC SL SR
        val out = drainFloats(p, floatBuffer(soloFrame(7, 4)))
        assertEquals(0.70711f, out[0], floatTol)
        assertEquals(0.70711f, out[1], floatTol)
    }

    @Test
    fun `3_0 - center at 0_70711, fronts at unity`() {
        val p = processor()
        configureAndFlush(p, 48000, 3, C.ENCODING_PCM_FLOAT)
        val center = drainFloats(p, floatBuffer(soloFrame(3, 2)))
        assertEquals(0.70711f, center[0], floatTol)
        assertEquals(0.70711f, center[1], floatTol)
        val fl = drainFloats(p, floatBuffer(soloFrame(3, 0)))
        assertEquals(1f, fl[0], floatTol)
        assertEquals(0f, fl[1], floatTol)
    }

    // ── Lt/Rt surround encode ────────────────────────────────────────────

    @Test
    fun `ltRt - BL goes anti-phase with cross-feed`() {
        val p = processor().apply { setSurroundEncode(true) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(6, 4))) // BL
        assertEquals(-0.8165f, out[0], floatTol)
        assertEquals(0.5774f, out[1], floatTol)
    }

    @Test
    fun `ltRt - fronts, center and LFE fold as in LoRo`() {
        val p = processor().apply { setSurroundEncode(true) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val fl = drainFloats(p, floatBuffer(soloFrame(6, 0)))
        assertEquals(1f, fl[0], floatTol)
        assertEquals(0f, fl[1], floatTol)
        val fc = drainFloats(p, floatBuffer(soloFrame(6, 2)))
        assertEquals(0.70711f, fc[0], floatTol)
        assertEquals(0.70711f, fc[1], floatTol)
        val lfe = drainFloats(p, floatBuffer(soloFrame(6, 3)))
        assertEquals(2.26464f, lfe[0], floatTol)
        assertEquals(2.26464f, lfe[1], floatTol)
    }

    @Test
    fun `ltRt - 6_1 back-center is anti-phase at 0_70711`() {
        val p = processor().apply { setSurroundEncode(true) }
        configureAndFlush(p, 48000, 7, C.ENCODING_PCM_FLOAT)
        val out = drainFloats(p, floatBuffer(soloFrame(7, 4))) // BC
        assertEquals(-0.70711f, out[0], floatTol)
        assertEquals(0.70711f, out[1], floatTol)
    }

    @Test
    fun `ltRt - 16ch top-front stays a front, top-back encodes as surround`() {
        val p = processor().apply { setSurroundEncode(true) }
        configureAndFlush(p, 48000, 16, C.ENCODING_PCM_FLOAT)
        val tfl = drainFloats(p, floatBuffer(soloFrame(16, 10))) // TFL
        assertEquals(1f, tfl[0], floatTol)
        assertEquals(0f, tfl[1], floatTol)
        val tbr = drainFloats(p, floatBuffer(soloFrame(16, 15))) // TBR
        assertEquals(-0.5774f, tbr[0], floatTol)
        assertEquals(0.8165f, tbr[1], floatTol)
    }

    @Test
    fun `preamp scales the fold by its linear gain`() {
        val p = processor().apply { setPreampDb(-20f) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val fl = drainFloats(p, floatBuffer(soloFrame(6, 0)))
        assertEquals(0.1f, fl[0], floatTol) // 1.0 × 10^(−20/20)
        val lfe = drainFloats(p, floatBuffer(soloFrame(6, 3)))
        assertEquals(0.22646f, lfe[0], floatTol)
    }

    // ── LFE low-pass path ────────────────────────────────────────────────

    @Test
    fun `lfe lowpass - DC LFE settles to the matrix gain (unity DC filter)`() {
        val p = processor().apply { setLfeLowpass(true) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val frames = Array(8000) { floatArrayOf(0f, 0f, 0f, 0.5f, 0f, 0f) }
        val out = drainFloats(p, floatBuffer(*frames))
        // Butterworth DC gain is 1 → steady state = 0.5 × 2.26464431.
        assertEquals(1.13232f, out[out.size - 2], 1e-3f)
        assertEquals(1.13232f, out[out.size - 1], 1e-3f)
    }

    @Test
    fun `lfe lowpass - dry path is delayed by the filter group delay`() {
        val p = processor().apply { setLfeLowpass(true) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        // FL impulse in frame 0; delay at 48 kHz = round(48000·2.6131/(2π·125)) = 160.
        val frames = Array(400) { i ->
            if (i == 0) floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f) else FloatArray(6)
        }
        val out = drainFloats(p, floatBuffer(*frames))
        assertEquals(0f, out[0], floatTol)
        assertEquals(1f, out[2 * 160], floatTol)
        assertEquals(0f, out[2 * 161], floatTol)
    }

    @Test
    fun `lfe lowpass - high-frequency LFE content is attenuated to silence`() {
        val p = processor().apply { setLfeLowpass(true) }
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        // 4.8 kHz into the LFE: ~5.3 octaves above 125 Hz → ≥ −127 dB at 24 dB/oct.
        val frames = Array(4000) { i ->
            floatArrayOf(0f, 0f, 0f, kotlin.math.sin(2.0 * Math.PI * 4800.0 * i / 48000.0).toFloat() * 0.5f, 0f, 0f)
        }
        val out = drainFloats(p, floatBuffer(*frames))
        for (i in (out.size - 200) until out.size) {
            assertTrue("leaked hf at $i: ${out[i]}", kotlin.math.abs(out[i]) < 0.01f)
        }
    }

    @Test
    fun `matrix and preamp changes apply on the fly at the next buffer`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        val before = drainFloats(p, floatBuffer(soloFrame(6, 4)))
        assertEquals(1f, before[0], floatTol)
        // No flush: the very next buffer must use the new settings.
        p.setSurroundEncode(true)
        val ltRt = drainFloats(p, floatBuffer(soloFrame(6, 4)))
        assertEquals(-0.8165f, ltRt[0], floatTol)
        p.setPreampDb(-20f)
        val trimmed = drainFloats(p, floatBuffer(soloFrame(6, 4)))
        assertEquals(-0.08165f, trimmed[0], floatTol)
    }

    @Test
    fun `lfe lowpass toggles on the fly mid-stream`() {
        val p = processor()
        configureAndFlush(p, 48000, 6, C.ENCODING_PCM_FLOAT)
        // Dry direct first.
        val dry = drainFloats(p, floatBuffer(soloFrame(6, 3, 0.5f)))
        assertEquals(1.13232f, dry[0], floatTol)
        // Enable mid-stream: DC LFE settles to the same matrix gain through
        // the filter, no flush in between.
        p.setLfeLowpass(true)
        val frames = Array(8000) { floatArrayOf(0f, 0f, 0f, 0.5f, 0f, 0f) }
        val out = drainFloats(p, floatBuffer(*frames))
        assertEquals(1.13232f, out[out.size - 2], 1e-3f)
    }

    // ── 16-channel (9.1.6-style) layout ──────────────────────────────────
    // Order: FL FR FC LFE BL BR BLC BRC SL SR TFL TFR TSL TSR TBL TBR

    @Test
    fun `16ch - configure maps to stereo`() {
        val out = processor().configure(AudioFormat(48000, 16, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT), out)
    }

    @Test
    fun `16ch - every channel matches the fold matrix`() {
        val p = processor()
        configureAndFlush(p, 48000, 16, C.ENCODING_PCM_FLOAT)
        val expected = arrayOf(
            floatArrayOf(1f, 0f),                       // FL
            floatArrayOf(0f, 1f),                       // FR
            floatArrayOf(0.70710678f, 0.70710678f),     // FC
            floatArrayOf(2.26464431f, 2.26464431f),     // LFE
            floatArrayOf(1f, 0f),                       // BL
            floatArrayOf(0f, 1f),                       // BR
            floatArrayOf(1f, 0f),                       // BLC
            floatArrayOf(0f, 1f),                       // BRC
            floatArrayOf(1f, 0f),                       // SL
            floatArrayOf(0f, 1f),                       // SR
            floatArrayOf(1f, 0f),                       // TFL
            floatArrayOf(0f, 1f),                       // TFR
            floatArrayOf(1f, 0f),                       // TSL
            floatArrayOf(0f, 1f),                       // TSR
            floatArrayOf(1f, 0f),                       // TBL
            floatArrayOf(0f, 1f),                       // TBR
        )
        for (ch in 0 until 16) {
            val out = drainFloats(p, floatBuffer(soloFrame(16, ch)))
            assertEquals("ch $ch L", expected[ch][0], out[0], floatTol)
            assertEquals("ch $ch R", expected[ch][1], out[1], floatTol)
        }
    }
}
