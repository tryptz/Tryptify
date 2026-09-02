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

    @Test
    fun `a speed reads in the unit that was chosen`() {
        // The bug: the player's chip formatted the raw ratio whatever the unit
        // was, so stepping in semitones showed "+3 st" in the panel and "1.19x"
        // in the bar above it -- the same number, one of them stripped of the
        // meaning that produced it.
        val threeUp = PitchRatio.ratioFor(3)
        assertEquals("+3 st", PitchRatio.formatSpeed(threeUp, semitoneUnit = true))
        assertEquals("1.19x", PitchRatio.formatSpeed(threeUp, semitoneUnit = false))

        val oneUp = PitchRatio.ratioFor(1)
        assertEquals("+1 st", PitchRatio.formatSpeed(oneUp, semitoneUnit = true))
        assertEquals("1.06x", PitchRatio.formatSpeed(oneUp, semitoneUnit = false))
    }

    @Test
    fun `unity reads as no change in either unit`() {
        assertEquals("0 st", PitchRatio.formatSpeed(1f, semitoneUnit = true))
        assertEquals("1.00x", PitchRatio.formatSpeed(1f, semitoneUnit = false))
    }

    @Test
    fun `a speed below unity keeps its sign`() {
        val down = PitchRatio.ratioFor(-5)
        assertEquals("-5 st", PitchRatio.formatSpeed(down, semitoneUnit = true))
        assertEquals("0.75x", PitchRatio.formatSpeed(down, semitoneUnit = false))
    }

    @Test
    fun `a speed between semitones rounds rather than inventing precision`() {
        // 1.15x is about 2.4 semitones. In semitone units it has to commit to
        // one, and the multiplier still reads exactly what it is.
        assertEquals("+2 st", PitchRatio.formatSpeed(1.15f, semitoneUnit = true))
        assertEquals("1.15x", PitchRatio.formatSpeed(1.15f, semitoneUnit = false))
    }

    @Test
    fun `the readout does not depend on the device locale`() {
        // A comma decimal separator would render "1,19x", which is not a number
        // this app ever wrote and not one its own parsing would read back.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.19x", PitchRatio.formatSpeed(PitchRatio.ratioFor(3), semitoneUnit = false))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
