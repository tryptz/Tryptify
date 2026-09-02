package tf.monochrome.android.audio.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The harness measuring itself.
 *
 * A measuring tool that is wrong is worse than no tool: it does not fail, it
 * certifies. Everything below pins a value this harness will later be trusted
 * to report about real DSP.
 */
class GoldenPcmHarnessTest {

    private val sampleRate = 48000

    // ── Fixtures are portable, or they are not golden ───────────────────

    @Test
    fun `generated sine is bit-identical on every JVM`() {
        // Captured from StrictMath and verified to match on JDK 17 and 21. Math
        // is allowed a ulp of drift between versions and between the
        // interpreter and a JIT intrinsic; StrictMath is specified to the bit.
        // If this ever fails, a fixture generator stopped being reproducible and
        // every bit-exact assertion downstream became a coin toss.
        val expected = intArrayOf(
            0, 1032169640, 1040483310, 1044639509,
            1048576000, 1050400714, 1052050675, 1053497652,
        )
        val actual = PcmSignals.sine(8, freqHz = 1000.0, sampleRate = sampleRate, amplitude = 0.5)
        assertEquals(expected.size, actual.size)
        actual.forEachIndexed { i, v ->
            assertEquals("sample $i drifted from the specified value", expected[i], v.toRawBits())
        }
    }

    @Test
    fun `the same signal generates identically twice`() {
        val a = PcmSignals.logSweep(2048, 100.0, 10000.0, sampleRate)
        val b = PcmSignals.logSweep(2048, 100.0, 10000.0, sampleRate)
        assertTrue(PcmCompare.isBitIdentical(a, b))
        assertTrue(
            PcmCompare.isBitIdentical(
                PcmSignals.whiteNoise(512, seed = 7),
                PcmSignals.whiteNoise(512, seed = 7),
            ),
        )
        assertFalse(
            PcmCompare.isBitIdentical(
                PcmSignals.whiteNoise(512, seed = 7),
                PcmSignals.whiteNoise(512, seed = 8),
            ),
        )
    }

    // ── Signals are what they claim ─────────────────────────────────────

    @Test
    fun `a sine lands at its own frequency and amplitude`() {
        val x = PcmSignals.sine(16384, 1000.0, sampleRate, amplitude = 0.5)
        assertEquals(0.5, PcmCompare.amplitudeAt(x, 1000.0, sampleRate), 1e-3)
        // And essentially nothing anywhere else.
        assertTrue(PcmCompare.amplitudeAt(x, 3000.0, sampleRate) < 1e-3)
    }

    @Test
    fun `a full-scale sine reaches 0 dBFS without passing it`() {
        val x = PcmSignals.fullScaleSine(16384, 997.0, sampleRate)
        assertEquals(0.0, PcmCompare.peakDbfs(x), 0.01)
        assertEquals("a generator must not overshoot full scale", 0, PcmCompare.clippedCount(x))
    }

    @Test
    fun `a sweep starts and ends where it says`() {
        // The phase is the integral of frequency. Written the obvious wrong way,
        // sin(2*pi*f(t)*t), a sweep runs at twice the intended rate and ends an
        // octave high -- which at these bounds would land past Nyquist and alias
        // back down, giving a fixture that looks plausible and measures nonsense.
        val frames = sampleRate // one second
        val x = PcmSignals.logSweep(frames, 100.0, 10000.0, sampleRate)
        val head = zeroCrossingHz(x, 0, frames / 20)
        val tail = zeroCrossingHz(x, frames - frames / 20, frames)
        assertTrue("sweep opens at $head Hz, expected near 100", head in 80.0..220.0)
        assertTrue("sweep closes at $tail Hz, expected near 10000", tail in 7000.0..11000.0)
    }

    @Test
    fun `an impulse is one sample and silence is none`() {
        val x = PcmSignals.impulse(64, at = 3)
        assertEquals(1f, x[3])
        assertEquals(63, x.count { it == 0f })
        assertEquals(Double.NEGATIVE_INFINITY, PcmCompare.peakDbfs(PcmSignals.silence(64)), 0.0)
    }

    @Test
    fun `interleaving round-trips`() {
        val l = PcmSignals.sine(256, 440.0, sampleRate)
        val r = PcmSignals.sine(256, 660.0, sampleRate)
        val stereo = PcmSignals.interleave(l, r)
        assertEquals(512, stereo.size)
        assertEquals(l[1], stereo[2])
        assertEquals(r[1], stereo[3])
        val (backL, backR) = PcmSignals.deinterleave(stereo, 2)
        assertTrue(PcmCompare.isBitIdentical(l, backL))
        assertTrue(PcmCompare.isBitIdentical(r, backR))
    }

    @Test
    fun `mismatched channels are refused rather than truncated`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmSignals.interleave(FloatArray(10), FloatArray(9))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmSignals.deinterleave(FloatArray(9), 2)
        }
    }

    // ── The gates' vocabulary ───────────────────────────────────────────

    @Test
    fun `an LSB is the step of a signed sample at that depth`() {
        assertEquals(1.0 / 32768, PcmCompare.lsb(16), 0.0)
        assertEquals(1.0 / 8388608, PcmCompare.lsb(24), 0.0)
        assertEquals(1.0 / 2147483648, PcmCompare.lsb(32), 0.0)
    }

    @Test
    fun `deviation is reported in LSBs at the stated depth`() {
        val a = FloatArray(4)
        val b = FloatArray(4)
        b[2] = (3.0 / 8388608).toFloat() // three 24-bit LSBs
        assertEquals(3.0, PcmCompare.maxDeviationLsb(a, b, 24), 1e-6)
        // The same absolute error is a smaller fraction of a coarser step.
        assertEquals(3.0 / 256, PcmCompare.maxDeviationLsb(a, b, 16), 1e-6)
        assertEquals(2, PcmCompare.worstIndex(a, b))
    }

    @Test
    fun `comparing different lengths is an error, not a shrug`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmCompare.maxAbsDeviation(FloatArray(4), FloatArray(5))
        }
        // isBitIdentical answers rather than throws -- it is a question, not a
        // measurement -- but it must never call different lengths identical.
        assertFalse(PcmCompare.isBitIdentical(FloatArray(4), FloatArray(5)))
        assertEquals(4, PcmCompare.firstDifference(FloatArray(4), FloatArray(5)))
    }

    @Test
    fun `bit-identity is stricter than float equality`() {
        // -0.0 == 0.0 and NaN != NaN, both of which are the wrong answer for
        // "did this chain change what it emits".
        assertFalse(PcmCompare.isBitIdentical(floatArrayOf(0.0f), floatArrayOf(-0.0f)))
        assertTrue(PcmCompare.isBitIdentical(floatArrayOf(Float.NaN), floatArrayOf(Float.NaN)))
        assertEquals(-1, PcmCompare.firstDifference(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)))
        assertEquals(1, PcmCompare.firstDifference(floatArrayOf(1f, 2f), floatArrayOf(1f, 3f)))
    }

    @Test
    fun `clipping is counted past full scale, not at it`() {
        val x = floatArrayOf(1.0f, -1.0f, 1.0001f, -1.0001f, 0.5f)
        assertEquals(2, PcmCompare.clippedCount(x))
        assertEquals(0, PcmCompare.clippedCount(x, ceiling = 1.001))
    }

    @Test
    fun `levels are reported in dBFS`() {
        assertEquals(-6.0206, PcmCompare.peakDbfs(floatArrayOf(0.5f)), 1e-3)
        // A full-scale sine is 3.01 dB below its peak in RMS.
        val sine = PcmSignals.fullScaleSine(48000, 1000.0, sampleRate)
        assertEquals(-3.01, PcmCompare.rmsDbfs(sine), 0.01)
        assertEquals(0.0, PcmCompare.levelDbAt(sine, 1000.0, sampleRate), 0.05)
    }

    @Test
    fun `a measurement can skip a transient`() {
        // The first half is junk; measuring across it would report neither.
        val x = PcmSignals.sine(8192, 1000.0, sampleRate, amplitude = 0.25)
        val withTransient = FloatArray(8192) { if (it < 4096) 0f else x[it] }
        assertEquals(0.25, PcmCompare.amplitudeAt(withTransient, 1000.0, sampleRate, from = 4096), 1e-3)
        assertTrue(
            "measuring across the transient must read low",
            PcmCompare.amplitudeAt(withTransient, 1000.0, sampleRate) < 0.2,
        )
    }

    // ── WAV ─────────────────────────────────────────────────────────────

    @Test
    fun `float32 WAV round-trips bit-identically`() {
        // The fixture format: it is the only one that survives a round trip
        // untouched, which is what a golden reference has to do.
        val samples = PcmSignals.interleave(
            PcmSignals.sine(1024, 440.0, sampleRate, amplitude = 0.9),
            PcmSignals.sine(1024, 660.0, sampleRate, amplitude = 0.1),
        )
        val wav = WavData(samples, sampleRate, channels = 2, bitDepth = 32, isFloat = true)
        val back = WavIo.decode(WavIo.encode(wav))
        assertEquals(wav, back)
        assertTrue(PcmCompare.isBitIdentical(samples, back.samples))
        assertEquals(1024, back.frames)
    }

    @Test
    fun `integer WAVs round-trip within a single LSB`() {
        val samples = PcmSignals.sine(4096, 997.0, sampleRate, amplitude = 0.8)
        intArrayOf(16, 24, 32).forEach { depth ->
            val back = WavIo.decode(
                WavIo.encode(WavData(samples, sampleRate, 1, depth, isFloat = false)),
            )
            assertEquals(depth, back.bitDepth)
            val off = PcmCompare.maxDeviationLsb(samples, back.samples, depth)
            assertTrue("$depth-bit round trip drifted $off LSB", off <= 0.5 + 1e-6)
        }
    }

    @Test
    fun `full scale survives quantisation without wrapping`() {
        // +1.0 scaled by 32768 lands one past the top of a signed 16-bit sample.
        // Clamped it reads back as very nearly full scale; wrapped it would read
        // back as full-scale *negative*, which is a click at the loudest point
        // of the signal and the reason this is pinned.
        val x = floatArrayOf(1.0f, -1.0f, 0.999999f, -0.999999f)
        val back = WavIo.decode(WavIo.encode(WavData(x, sampleRate, 1, 16, isFloat = false)))
        assertTrue("full scale must stay positive", back.samples[0] > 0.99f)
        assertTrue("negative full scale must stay negative", back.samples[1] < -0.99f)
        assertEquals(-1.0f, back.samples[1])
    }

    @Test
    fun `chunks are walked, not assumed`() {
        // A JUNK chunk before fmt, with an odd size so the pad byte matters. A
        // reader that assumes fmt-then-data reads this metadata as audio.
        val body = WavIo.encode(
            WavData(PcmSignals.sine(64, 1000.0, sampleRate), sampleRate, 1, 32, isFloat = true),
        )
        val junkBody = "oddsize".toByteArray(Charsets.US_ASCII) // 7 bytes: odd
        val out = ByteBuffer.allocate(body.size + 8 + junkBody.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.put(body, 0, 12) // RIFF header + WAVE
        out.put("JUNK".toByteArray(Charsets.US_ASCII))
        out.putInt(junkBody.size)
        out.put(junkBody)
        out.put(0) // the pad byte, not counted in the size
        out.put(body, 12, body.size - 12)
        val bytes = out.array()
        // Fix up the RIFF size for the inserted chunk.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)

        val read = WavIo.decode(bytes)
        assertEquals(64, read.frames)
        assertTrue(
            PcmCompare.isBitIdentical(PcmSignals.sine(64, 1000.0, sampleRate), read.samples),
        )
    }

    @Test
    fun `an extensible header is read as its subformat`() {
        // What editors write above 16-bit: format 0xFFFE, with the real format
        // in the subformat GUID. Read literally it is an unknown format and a
        // perfectly good fixture is rejected.
        val plain = WavIo.encode(
            WavData(PcmSignals.sine(32, 1000.0, sampleRate), sampleRate, 1, 32, isFloat = true),
        )
        val ext = ByteBuffer.allocate(plain.size + 24).order(ByteOrder.LITTLE_ENDIAN)
        ext.put(plain, 0, 12)
        ext.put("fmt ".toByteArray(Charsets.US_ASCII))
        ext.putInt(40)
        ext.putShort(0xFFFE.toShort())
        ext.putShort(1)
        ext.putInt(sampleRate)
        ext.putInt(sampleRate * 4)
        ext.putShort(4)
        ext.putShort(32)
        ext.putShort(22)          // extension size
        ext.putShort(32)          // valid bits
        ext.putInt(0)             // channel mask
        ext.putShort(3)           // subformat GUID: IEEE float
        ext.put(ByteArray(14))    // the rest of the GUID
        ext.put(plain, 36, plain.size - 36) // data chunk onward
        val bytes = ext.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size - 8)

        val read = WavIo.decode(bytes)
        assertEquals(32, read.frames)
        assertTrue(read.isFloat)
    }

    @Test
    fun `rubbish is refused with a reason`() {
        assertThrows(IllegalArgumentException::class.java) { WavIo.decode(ByteArray(4)) }
        assertThrows(IllegalArgumentException::class.java) {
            WavIo.decode("NOTARIFFATALL".toByteArray(Charsets.US_ASCII))
        }
        // A real WAV whose depth this harness does not handle.
        val bytes = WavIo.encode(
            WavData(FloatArray(8), sampleRate, 1, 16, isFloat = false),
        )
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(34, 12) // 12-bit
        assertThrows(IllegalArgumentException::class.java) { WavIo.decode(bytes) }
    }

    @Test
    fun `WavData compares by value`() {
        // The generated equals would compare the sample array by identity, so
        // two reads of the same fixture would be unequal.
        val a = WavData(floatArrayOf(0.5f), 48000, 1, 32, true)
        val b = WavData(floatArrayOf(0.5f), 48000, 1, 32, true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, WavData(floatArrayOf(0.25f), 48000, 1, 32, true))
    }

    /** Crude frequency estimate from zero crossings, for checking a sweep. */
    private fun zeroCrossingHz(x: FloatArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in (from + 1) until to) {
            if ((x[i - 1] < 0f && x[i] >= 0f) || (x[i - 1] >= 0f && x[i] < 0f)) crossings++
        }
        val seconds = (to - from).toDouble() / sampleRate
        return crossings / 2.0 / seconds
    }

    private fun assertEquals(expected: Float, actual: Float) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) < 1e-6f)
    }
}
