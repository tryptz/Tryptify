package tf.monochrome.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule tying the album-colour fade to "Blend Between Tracks".
 *
 * The number matters because the queue advances at the *start* of an audio
 * blend, not the end: the UI sees the new track while the old one still has the
 * whole blend left to play. A colour fade of exactly the blend length is what
 * makes the two finish together.
 */
class ColorBlendTest {

    @Test
    fun `a set blend converts seconds to milliseconds unchanged`() {
        assertEquals(1_000, ColorBlend.millisFor(1))
        assertEquals(6_000, ColorBlend.millisFor(6))
        // The slider's maximum. Not clamped — the audio really does take this
        // long, so the colour has to as well.
        assertEquals(12_000, ColorBlend.millisFor(12))
    }

    @Test
    fun `gapless gets its own short fade rather than a hard cut`() {
        assertEquals(ColorBlend.GAPLESS_MS, ColorBlend.millisFor(0))
        assertTrue("a fade of zero is a cut", ColorBlend.GAPLESS_MS > 0)
    }

    @Test
    fun `a negative value cannot produce a negative duration`() {
        // The preference is an Int with no floor of its own; a corrupt or
        // hand-edited store must not reach a tween as a negative duration.
        assertEquals(ColorBlend.GAPLESS_MS, ColorBlend.millisFor(-1))
        assertEquals(ColorBlend.GAPLESS_MS, ColorBlend.millisFor(Int.MIN_VALUE))
    }

    @Test
    fun `longer blends never fade faster`() {
        var previous = 0
        for (seconds in 0..12) {
            val ms = ColorBlend.millisFor(seconds)
            assertTrue("blend $seconds fell to $ms from $previous", ms >= previous)
            previous = ms
        }
    }

    /**
     * The Appearance slider. Its whole job is to be ignorable: the default stop
     * has to leave every case above exactly as it was, or adding the control
     * changes the app for people who never touch it.
     */
    @Test
    fun `match blend is what the derived rule already said`() {
        for (seconds in 0..12) {
            assertEquals(
                ColorBlend.millisFor(seconds),
                ColorBlend.millisFor(seconds, ColorBlend.MATCH_BLEND),
            )
        }
    }

    @Test
    fun `an override wins whatever the blend is`() {
        for (seconds in 0..12) {
            assertEquals(0, ColorBlend.millisFor(seconds, 0))
            assertEquals(2_500, ColorBlend.millisFor(seconds, 2_500))
            assertEquals(ColorBlend.MAX_MS, ColorBlend.millisFor(seconds, ColorBlend.MAX_MS))
        }
    }

    /**
     * The slider indexes this list, so a stop that cannot be reached back from a
     * stored value would leave the thumb stuck at "match blend".
     */
    @Test
    fun `every slider stop is a distinct value the rule accepts`() {
        val stops = ColorBlend.steps
        assertEquals("first stop must be the default", ColorBlend.MATCH_BLEND, stops.first())
        assertEquals("last stop must be the maximum", ColorBlend.MAX_MS, stops.last())
        assertEquals("stops must be unique", stops.size, stops.distinct().size)
        for (stop in stops) {
            assertTrue("$stop is not a round step", stop < 0 || stop % ColorBlend.STEP_MS == 0)
            assertTrue("$stop produced a negative duration", ColorBlend.millisFor(0, stop) >= 0)
        }
    }
}
