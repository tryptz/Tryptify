package tf.monochrome.android.ui.mixer.fxchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import tf.monochrome.android.ui.mixer.fxchain.FxVisualMath as M

/**
 * Pure-JVM tests for the FX-chain visualization math. These lock the displayed
 * curves to the behaviour of the native processors they mirror (RBJ biquads,
 * `distortion.h` waveshapes, the trance-gate pattern table, the dynamics
 * transfer curve, …).
 */
class FxVisualMathTest {

    private val tol = 1e-3f

    // ── Frequency axis ──────────────────────────────────────────────────────

    @Test
    fun `freq axis endpoints and round-trip`() {
        assertEquals(M.MIN_FREQ, M.freqAt(0f), tol)
        assertEquals(M.MAX_FREQ, M.freqAt(1f), 1f)
        assertEquals(0.5f, M.freqToT(M.freqAt(0.5f)), tol)
        assertEquals(1000f, M.freqAt(M.freqToT(1000f)), 0.5f)
    }

    @Test
    fun `db lin round-trip`() {
        assertEquals(1f, M.dbToLin(0f), tol)
        assertEquals(-6.0206f, M.linToDb(0.5f), 1e-2f)
        assertEquals(12f, M.linToDb(M.dbToLin(12f)), tol)
    }

    // ── Biquad responses ────────────────────────────────────────────────────

    @Test
    fun `peak filter hits its gain at center and is flat far away`() {
        val atCenter = M.biquadDb(M.BiquadShape.PEAK, 1000f, 1000f, 1f, 9f)
        assertEquals(9f, atCenter, 0.1f)
        val farAway = M.biquadDb(M.BiquadShape.PEAK, 30f, 1000f, 1f, 9f)
        assertEquals(0f, farAway, 0.5f)
    }

    @Test
    fun `lowpass is unity in passband and attenuates above cutoff`() {
        val passband = M.biquadDb(M.BiquadShape.LOWPASS, 50f, 2000f, 0.707f)
        assertEquals(0f, passband, 0.5f)
        // -3 dB at cutoff for Butterworth Q
        assertEquals(-3f, M.biquadDb(M.BiquadShape.LOWPASS, 2000f, 2000f, 0.707f), 0.3f)
        // ~ -12 dB/oct beyond: two octaves up should be well below -20 dB
        assertTrue(M.biquadDb(M.BiquadShape.LOWPASS, 8000f, 2000f, 0.707f) < -20f)
    }

    @Test
    fun `highpass mirrors lowpass`() {
        assertTrue(M.biquadDb(M.BiquadShape.HIGHPASS, 50f, 2000f, 0.707f) < -20f)
        assertEquals(0f, M.biquadDb(M.BiquadShape.HIGHPASS, 10000f, 2000f, 0.707f), 0.6f)
    }

    @Test
    fun `shelves land on their gain on the shelved side`() {
        assertEquals(6f, M.biquadDb(M.BiquadShape.LOWSHELF, 25f, 500f, 0.707f, 6f), 0.4f)
        assertEquals(0f, M.biquadDb(M.BiquadShape.LOWSHELF, 10000f, 500f, 0.707f, 6f), 0.4f)
        assertEquals(-9f, M.biquadDb(M.BiquadShape.HIGHSHELF, 15000f, 2000f, 0.707f, -9f), 0.5f)
    }

    @Test
    fun `notch kills the center frequency`() {
        assertTrue(M.biquadDb(M.BiquadShape.NOTCH, 1000f, 1000f, 2f) < -30f)
        assertEquals(0f, M.biquadDb(M.BiquadShape.NOTCH, 100f, 1000f, 2f), 0.5f)
    }

    @Test
    fun `filter slope multiplies the stage response`() {
        val oneStage = M.filterDb(0, 8000f, 2000f, 0.707f, 0f, 0)
        val fourStages = M.filterDb(0, 8000f, 2000f, 0.707f, 0f, 3)
        assertEquals(oneStage * 4f, fourStages, 0.1f)
    }

    @Test
    fun `ladder resonance raises a peak at cutoff`() {
        val flat = M.ladderDb(1000f, 1000f, 0f)
        val resonant = M.ladderDb(1000f, 1000f, 80f)
        assertTrue(resonant > flat + 6f)
        // Still a lowpass: far above cutoff it dives steeply (24 dB/oct)
        assertTrue(M.ladderDb(8000f, 1000f, 0f) < -60f)
    }

    @Test
    fun `comb has peaks at harmonics and notches between for positive polarity`() {
        val peak = M.combDb(440f, 440f, 100f, negativePolarity = false)
        val notch = M.combDb(660f, 440f, 100f, negativePolarity = false)
        assertEquals(6.02f, peak, 0.1f)     // |1 + 1| = 2
        assertTrue(notch < -30f)            // |1 - 1| = 0
        // Negative polarity swaps peaks and notches
        assertTrue(M.combDb(440f, 440f, 100f, negativePolarity = true) < -30f)
    }

    @Test
    fun `phaser depth controls notch depth`() {
        val center = 1000f
        val shallow = M.phaserDb(center, 6, center, 20f, 30f)
        val deep = M.phaserDb(center, 6, center, 100f, 30f)
        assertTrue(deep < shallow)
    }

    // ── Dynamics transfer curves ────────────────────────────────────────────

    @Test
    fun `compressor is unity below threshold and ratio-sloped above`() {
        val thr = -18f
        assertEquals(-40f, M.compressorOutDb(-40f, thr, 4f, 0f, 0f), tol)
        // 12 dB over threshold at 4:1 → 3 dB over
        assertEquals(thr + 3f, M.compressorOutDb(thr + 12f, thr, 4f, 0f, 0f), tol)
        // Makeup shifts everything up
        assertEquals(-34f, M.compressorOutDb(-40f, thr, 4f, 0f, 6f), tol)
    }

    @Test
    fun `compressor soft knee is continuous at both knee edges`() {
        val thr = -18f; val knee = 12f
        val eps = 0.01f
        val belowEdge = M.compressorOutDb(thr - knee / 2f - eps, thr, 4f, knee, 0f)
        val atEdge = M.compressorOutDb(thr - knee / 2f + eps, thr, 4f, knee, 0f)
        assertEquals(belowEdge, atEdge, 0.05f)
        val aboveEdge = M.compressorOutDb(thr + knee / 2f + eps, thr, 4f, knee, 0f)
        val kneeTop = M.compressorOutDb(thr + knee / 2f - eps, thr, 4f, knee, 0f)
        assertEquals(aboveEdge, kneeTop, 0.05f)
    }

    @Test
    fun `limiter clamps at threshold`() {
        assertEquals(-6f, M.limiterOutDb(-3f, 0f, -6f, 0f), tol)
        assertEquals(-20f, M.limiterOutDb(-20f, 0f, -6f, 0f), tol)
        // Input gain drives into the ceiling; output gain shifts after
        assertEquals(-4f, M.limiterOutDb(-3f, 12f, -6f, 2f), tol)
    }

    @Test
    fun `gate passes above threshold and attenuates by range below`() {
        assertEquals(-10f, M.gateOutDb(-10f, -30f, 6f, 80f), tol)
        assertEquals(-140f, M.gateOutDb(-60f, -30f, 6f, 80f), tol)
        // Inside the tolerance band: partially attenuated
        val mid = M.gateOutDb(-33f, -30f, 6f, 80f)
        assertTrue(mid < -33f && mid > -113f)
    }

    @Test
    fun `dynamics is unity between thresholds and compresses above`() {
        assertEquals(0f, M.dynamicsGainDb(-25f, -40f, 1f, -12f, 4f, 0f), tol)
        // 8 dB above high threshold at 4:1 → gain reduction of 6 dB
        assertEquals(-6f, M.dynamicsGainDb(-4f, -40f, 1f, -12f, 4f, 0f), tol)
        // Below the low threshold the native curve divides by the ratio:
        // ratio > 1 lifts quiet material (upward compression)…
        assertEquals(5f, M.dynamicsGainDb(-50f, -40f, 2f, -12f, 4f, 0f), tol)
        // …ratio < 1 pushes it further down (downward expansion)
        assertEquals(-10f, M.dynamicsGainDb(-50f, -40f, 0.5f, -12f, 4f, 0f), tol)
    }

    @Test
    fun `compactor range scales limiting strength`() {
        assertEquals(-12f, M.compactorOutDb(-6f, -12f, 100f), tol)   // full limiting
        assertEquals(-9f, M.compactorOutDb(-6f, -12f, 50f), tol)     // half
        assertEquals(-18f, M.compactorOutDb(-6f, -12f, 200f), tol)   // over-compression ducks
        assertEquals(-20f, M.compactorOutDb(-20f, -12f, 100f), tol)  // below thresh untouched
    }

    // ── Waveshapers ─────────────────────────────────────────────────────────

    @Test
    fun `distortion shapes match the native waveshape cases`() {
        // Soft clip cubic in the linear region
        assertEquals(0.5f - 0.125f / 3f, M.distortionShape(0, 0.5f), tol)
        assertEquals(1f, M.distortionShape(0, 2f), tol)
        // Hard clip
        assertEquals(1f, M.distortionShape(1, 3f), tol)
        assertEquals(-0.4f, M.distortionShape(1, -0.4f), tol)
        // Tanh
        assertEquals(kotlin.math.tanh(0.8f), M.distortionShape(2, 0.8f), tol)
        // Foldback: sin(x·π) folds a full-scale input back to zero
        assertEquals(0f, M.distortionShape(3, 1f), tol)
        // Rectify
        assertEquals(0.7f, M.distortionShape(4, -0.7f), tol)
        // Asymmetric: soft positive, harder negative
        assertEquals(1f - kotlin.math.exp(-0.5f), M.distortionShape(5, 0.5f), tol)
        assertEquals(-kotlin.math.tanh(0.75f), M.distortionShape(5, -0.5f), tol)
    }

    @Test
    fun `shaper overflow modes hold wrap and mirror`() {
        assertEquals(1f, M.shaperOverflow(0, 1.7f), tol)
        assertEquals(-1f, M.shaperOverflow(0, -4f), tol)
        // Wrap: 1.5 → -0.5
        assertEquals(-0.5f, M.shaperOverflow(1, 1.5f), tol)
        assertEquals(0.5f, M.shaperOverflow(1, -1.5f), tol)
        // Mirror: 1.5 → 0.5, 2.5 → -0.5
        assertEquals(0.5f, M.shaperOverflow(2, 1.5f), tol)
        assertEquals(-0.5f, M.shaperOverflow(2, 2.5f), tol)
        // In-range values untouched by all modes
        for (mode in 0..2) assertEquals(0.3f, M.shaperOverflow(mode, 0.3f), tol)
    }

    @Test
    fun `bitcrush quantizes to the bit depth`() {
        // 2 bits → 2 levels per polarity: only -1, -0.5, 0, 0.5, 1
        val q = M.bitcrushQuantize(0.6f, 2f)
        assertEquals(0.5f, q, tol)
        // 24 bits is effectively transparent
        assertEquals(0.6f, M.bitcrushQuantize(0.6f, 24f), 1e-4f)
    }

    @Test
    fun `phase distortion at zero drive is a pure sine`() {
        val t = 1.1f
        assertEquals(kotlin.math.sin(t), M.phaseDistortion(t, 0f, 0f), tol)
        // Drive bends the phase
        assertTrue(abs(M.phaseDistortion(t, 100f, 0f) - kotlin.math.sin(t)) > 0.01f)
    }

    @Test
    fun `misstortion shape mirrors the original processing chain`() {
        // No drive, symmetry 50%: both halves scaled by 0.5
        assertEquals(0.3f, M.misstortionShape(0.6f, 0f, 0f, 0.5f), tol)
        assertEquals(-0.3f, M.misstortionShape(-0.6f, 0f, 0f, 0.5f), tol)
        // Hard clip at +12 dB drive: 0.5 * ~3.98 clamps to 1, then × sym
        assertEquals(0.5f, M.misstortionShape(0.5f, 12f, 0f, 0.5f), tol)
        // Soft clip: atan(x·drive) clamped into [-1+sym, sym] before symmetry
        val soft = M.misstortionShape(0.9f, 0f, 20f, 0.5f)
        assertEquals(0.5f * 0.5f, soft, tol)  // atan(9) ≈ 1.46 → clamp 0.5 → ×0.5
        // Full symmetry kills the negative half entirely…
        assertEquals(0f, M.misstortionShape(-0.8f, 12f, 0f, 1f), tol)
        // …and passes the positive half unscaled
        assertEquals(1f, M.misstortionShape(0.8f, 12f, 0f, 1f), tol)
        // Drives at 0 dB are inactive: pure symmetry multiply only
        assertEquals(0.75f * 0.25f, M.misstortionShape(0.75f, 0f, 0f, 0.25f), tol)
    }

    // ── Trance gate patterns ────────────────────────────────────────────────

    @Test
    fun `trance patterns mirror the native table`() {
        val p = M.TRANCE_PATTERNS
        assertEquals(8, p.size)
        p.forEach { assertEquals(32, it.size) }
        // Four-on-the-floor: steps 0,4,8,12
        assertEquals(listOf(0, 4, 8, 12), (0 until 32).filter { p[0][it] })
        // Offbeat
        assertEquals(listOf(2, 6, 10, 14), (0 until 32).filter { p[1][it] })
        // 8ths
        assertEquals(8, (0 until 16).count { p[2][it] })
        // Syncopated + dotted
        assertEquals(listOf(0, 3, 6, 8, 11, 14), (0 until 32).filter { p[3][it] })
        assertEquals(listOf(0, 3, 6, 9, 12, 15), (0 until 32).filter { p[4][it] })
        // Buildup: second half of 16
        assertEquals((8 until 16).toList(), (0 until 32).filter { p[5][it] })
        // Stutter pairs
        assertEquals(listOf(0, 1, 4, 5, 8, 9, 12, 13), (0 until 32).filter { p[6][it] })
        // All on
        assertTrue((0 until 32).all { p[7][it] })
    }

    // ── Misc ────────────────────────────────────────────────────────────────

    @Test
    fun `midi to hz and semitone ratios`() {
        assertEquals(440f, M.midiToHz(69f), tol)
        assertEquals(880f, M.midiToHz(81f), 0.01f)
        assertEquals(2f, M.semitonesToRatio(12f), tol)
        assertEquals(0.5f, M.semitonesToRatio(-12f), tol)
        assertEquals(1f, M.semitonesToRatio(0f), tol)
    }

    @Test
    fun `allpass group delay peaks at the tuned frequency and stays positive`() {
        val f0 = 500f; val q = 2f
        val atF0 = M.allpassGroupDelayMs(f0, f0, q)
        // Analog 2nd-order APF peak group delay = 2Q/(pi*f0) seconds
        assertEquals(2f * q / (Math.PI.toFloat() * f0) * 1000f, atF0, atF0 * 0.1f)
        assertTrue(atF0 > M.allpassGroupDelayMs(f0 * 4f, f0, q))
        assertTrue(atF0 > M.allpassGroupDelayMs(f0 / 4f, f0, q))
        // All-pass group delay is non-negative across the band
        for (t in 0..20) {
            assertTrue(M.allpassGroupDelayMs(M.freqAt(t / 20f), f0, q) >= -1e-3f)
        }
    }

    @Test
    fun `pinch concentrates the delay around the cutoff`() {
        val f0 = 500f
        val narrowAtF0 = M.allpassGroupDelayMs(f0, f0, 8f)
        val wideAtF0 = M.allpassGroupDelayMs(f0, f0, 0.5f)
        assertTrue(narrowAtF0 > wideAtF0)                       // taller at the cutoff…
        val narrowFar = M.allpassGroupDelayMs(f0 * 8f, f0, 8f)
        val wideFar = M.allpassGroupDelayMs(f0 * 8f, f0, 0.5f)
        assertTrue(narrowFar < wideFar)                         // …thinner away from it
    }

    @Test
    fun `waveMinMax reduces buckets to min-max pairs`() {
        // Ramp -1..1 over 100 samples, 4 buckets
        val wave = FloatArray(100) { it / 99f * 2f - 1f }
        val mm = M.waveMinMax(wave, 100, 4)
        assertEquals(8, mm.size)
        assertEquals(-1f, mm[0], tol)              // bucket 0 min = first sample
        assertEquals(wave[24], mm[1], tol)         // bucket 0 max = last of bucket
        assertEquals(wave[75], mm[6], tol)         // bucket 3 min
        assertEquals(1f, mm[7], tol)               // bucket 3 max = last sample
        // Length 0 → all zeros
        assertTrue(M.waveMinMax(wave, 0, 4).all { it == 0f })
        // Length beyond array is clamped
        val clamped = M.waveMinMax(floatArrayOf(0.5f, -0.5f), 10, 2)
        assertEquals(0.5f, clamped[1], tol)
        assertEquals(-0.5f, clamped[2], tol)
    }

    @Test
    fun `formant response peaks near the vowel formants`() {
        // /a/ corner (x=0, y=0): F1=800, F2=1200
        val atF1 = M.formantDb(800f, 0f, 0f, 8f, 0f, 0f)
        val far = M.formantDb(8000f, 0f, 0f, 8f, 0f, 0f)
        assertTrue(atF1 > far + 12f)
    }
}
