package tf.monochrome.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The controls exist to hit musical intervals. These pin the arithmetic that
 * makes that exact, and the size of the error the old two-decimal rounding
 * introduced — which is the reason any of this is here.
 */
class PitchRatioTest {

    @Test
    fun `semitone ratios are exact to float precision`() {
        for (n in PitchRatio.SEMITONE_RANGE) {
            val expected = 2.0.pow(n / 12.0)
            val got = PitchRatio.ratioFor(n).toDouble()
            val cents = 1200.0 * kotlin.math.log2(got / expected)
            assertTrue(
                "$n semitones off by $cents cents",
                abs(cents) < 0.001,
            )
        }
    }

    @Test
    fun `octaves and the tritone land where theory says`() {
        assertEquals(0.5f, PitchRatio.ratioFor(-12), 1e-6f)
        assertEquals(1.0f, PitchRatio.ratioFor(0), 1e-6f)
        assertEquals(2.0f, PitchRatio.ratioFor(12), 1e-6f)
        assertEquals(1.4142136f, PitchRatio.ratioFor(6), 1e-6f)
    }

    @Test
    fun `two-decimal rounding is worst at minus nine semitones`() {
        // The regression this guards: the sliders used to store
        // Math.round(v * 100) / 100f.
        var worst = 0f
        var worstAt = 0
        for (n in -12..12) {
            val exact = PitchRatio.ratioFor(n)
            val rounded = (Math.round(exact * 100f) / 100f)
            val err = abs(PitchRatio.cents(exact, rounded))
            if (err > worst) { worst = err; worstAt = n }
        }
        assertEquals(-9, worstAt)
        assertTrue("worst rounding error was $worst cents", worst > 13f)
        // And what we do now is exact instead.
        assertTrue(PitchRatio.isOnSemitone(PitchRatio.ratioFor(-9), toleranceCents = 0.01f))
    }

    @Test
    fun `round trip through semitones is stable`() {
        for (n in PitchRatio.SEMITONE_RANGE) {
            assertEquals(n, PitchRatio.nearestSemitone(PitchRatio.ratioFor(n)))
        }
    }

    @Test
    fun `snap pulls a near miss onto the interval and leaves a real one alone`() {
        val nearFifth = PitchRatio.ratioFor(7) * 1.001f   // ~1.7 cents sharp
        assertEquals(PitchRatio.ratioFor(7), PitchRatio.snap(nearFifth), 1e-6f)

        val deliberate = 1.25f  // 386 cents: a just major third, not equal-tempered
        assertEquals(1.25f, PitchRatio.snap(deliberate), 1e-6f)
        assertNotEquals(PitchRatio.ratioFor(4), PitchRatio.snap(deliberate))
    }

    @Test
    fun `stepping walks the scale without drifting`() {
        var r = 1.0f
        repeat(5) { r = PitchRatio.step(r, 1) }
        assertEquals(PitchRatio.ratioFor(5), r, 1e-6f)
        repeat(5) { r = PitchRatio.step(r, -1) }
        assertEquals(1.0f, r, 1e-6f)
    }

    @Test
    fun `stepping stays inside the slider range`() {
        var r = 1.0f
        repeat(40) { r = PitchRatio.step(r, 1) }
        assertTrue("$r above max", r <= PitchRatio.MAX_SPEED + 1e-6f)
        repeat(80) { r = PitchRatio.step(r, -1) }
        assertTrue("$r below min", r >= PitchRatio.MIN_SPEED - 1e-6f)
    }

    @Test
    fun `nightcore preset is not a semitone`() {
        // 1.10x is 1.65 semitones — worth knowing it is a flavour, not an interval.
        assertTrue(abs(PitchRatio.centsOffSemitone(1.10f)) > 30f)
    }
}
