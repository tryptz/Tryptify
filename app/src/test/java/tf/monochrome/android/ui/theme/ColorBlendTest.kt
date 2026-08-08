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
}
