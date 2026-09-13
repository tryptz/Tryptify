package tf.monochrome.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the album colours take to cross over.
 *
 * This used to derive the length from "Blend Between Tracks" so the picture and
 * the sound finished together, and that was the default. The cost did not show
 * on the control: a four-second blend is an ordinary setting, and it made the
 * first stop of the Appearance slider read "Match blend · 4.00 s" and repaint
 * the whole window for four seconds on every track change.
 *
 * The two are unhitched, so what is left to pin is that nothing can hand an
 * animation a length it should not have — including the `-1` an older build
 * wrote into the preference to mean "match blend".
 */
class ColorBlendTest {

    @Test
    fun `a stored length is used as it stands`() {
        assertEquals(0, ColorBlend.millisFor(0))
        assertEquals(250, ColorBlend.millisFor(250))
        assertEquals(2_500, ColorBlend.millisFor(2_500))
        assertEquals(ColorBlend.MAX_MS, ColorBlend.millisFor(ColorBlend.MAX_MS))
    }

    @Test
    fun `zero is a choice, not a missing value`() {
        // Instant is the first stop and has to survive the sanitising the
        // negative cases below go through.
        assertEquals(0, ColorBlend.millisFor(0))
    }

    @Test
    fun `the old match-blend marker becomes a real length`() {
        // -1 is what shipped in the preference before the slider had a zero
        // stop. Handed to an animation spec it is not a duration at all.
        assertEquals(ColorBlend.DEFAULT_MS, ColorBlend.millisFor(-1))
        assertEquals(ColorBlend.DEFAULT_MS, ColorBlend.millisFor(Int.MIN_VALUE))
    }

    @Test
    fun `nothing can produce a fade longer than the slider allows`() {
        assertEquals(ColorBlend.MAX_MS, ColorBlend.millisFor(ColorBlend.MAX_MS + 1))
        assertEquals(ColorBlend.MAX_MS, ColorBlend.millisFor(Int.MAX_VALUE))
    }

    @Test
    fun `the default is short enough not to be an animation you sit through`() {
        assertTrue("a fade of zero is a cut", ColorBlend.DEFAULT_MS > 0)
        assertTrue(
            "the default is ${ColorBlend.DEFAULT_MS}ms — that is the bug this replaced",
            ColorBlend.DEFAULT_MS <= 1_000,
        )
    }

    /**
     * The slider indexes this list, so a stop that cannot be reached back from a
     * stored value would leave the thumb pinned at the left end.
     */
    @Test
    fun `every slider stop is a distinct round value the rule leaves alone`() {
        val stops = ColorBlend.steps
        assertEquals("first stop must be instant", 0, stops.first())
        assertEquals("last stop must be the maximum", ColorBlend.MAX_MS, stops.last())
        assertEquals("stops must be unique", stops.size, stops.distinct().size)
        for (stop in stops) {
            assertTrue("$stop is not a round step", stop % ColorBlend.STEP_MS == 0)
            assertEquals("$stop does not survive a round trip", stop, ColorBlend.millisFor(stop))
        }
    }

    @Test
    fun `the default sits on a stop the slider can rest at`() {
        // Otherwise an untouched install shows a thumb at a position that is not
        // what it is actually using.
        assertTrue(
            "${ColorBlend.DEFAULT_MS} is not one of the slider's stops",
            ColorBlend.DEFAULT_MS in ColorBlend.steps,
        )
    }
}
