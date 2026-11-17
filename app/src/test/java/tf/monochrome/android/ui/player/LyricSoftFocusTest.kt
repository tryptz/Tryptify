package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricsFxSettings

/**
 * Soft focus and where the lines start: the two layout choices of the lyric
 * view ([LyricSoftFocus], [LyricsFxSettings.lineAlign]).
 */
class LyricSoftFocusTest {
    @Test
    fun `off, nothing is blurred or dimmed`() {
        for (steps in 0..6) {
            assertEquals(0f, LyricSoftFocus.blurDp(0f, steps), 0f)
            assertEquals(1f, LyricSoftFocus.alpha(0f, steps), 0f)
        }
        assertEquals(0f, LyricsFxSettings.DEFAULT.softFocus, 0f)
        assertEquals(LyricsFxSettings.ALIGN_CENTER, LyricsFxSettings.DEFAULT.lineAlign)
    }

    @Test
    fun `the sung line is sharp and whole, and each line further is softer`() {
        assertEquals(0f, LyricSoftFocus.blurDp(1f, 0), 0f)
        assertEquals(1f, LyricSoftFocus.alpha(1f, 0), 0f)
        var blur = 0f
        var alpha = 1f
        for (steps in 1..LyricSoftFocus.MAX_STEPS) {
            assertTrue(LyricSoftFocus.blurDp(1f, steps) > blur)
            assertTrue(LyricSoftFocus.alpha(1f, steps) < alpha)
            blur = LyricSoftFocus.blurDp(1f, steps)
            alpha = LyricSoftFocus.alpha(1f, steps)
        }
        // Past the last step nothing gets softer, and no line is ever lost.
        assertEquals(blur, LyricSoftFocus.blurDp(1f, 40), 0f)
        assertEquals(alpha, LyricSoftFocus.alpha(1f, 40), 0f)
        assertTrue(alpha > 0.33f)
        // Half strength is half the blur.
        assertEquals(LyricSoftFocus.blurDp(1f, 2) / 2f, LyricSoftFocus.blurDp(0.5f, 2), 1e-6f)
    }

    @Test
    fun `distance is counted from the sung line, either way`() {
        assertEquals(0, LyricSoftFocus.steps(7, 7))
        assertEquals(2, LyricSoftFocus.steps(5, 7))
        assertEquals(2, LyricSoftFocus.steps(9, 7))
        assertEquals(LyricSoftFocus.MAX_STEPS, LyricSoftFocus.steps(30, 7))
        // Before the first line is sung, it is the one coming.
        assertEquals(1, LyricSoftFocus.steps(0, -1))
        assertEquals(2, LyricSoftFocus.steps(1, -1))
    }

    @Test
    fun `the two are clamped, saved, and carried over a theme`() {
        assertEquals(1f, LyricsFxSettings(softFocus = 9f).clamped().softFocus, 0f)
        assertEquals(0f, LyricsFxSettings(softFocus = Float.NaN).clamped().softFocus, 0f)
        assertEquals(LyricsFxSettings.ALIGN_CENTER, LyricsFxSettings(lineAlign = 7).clamped().lineAlign)
        assertEquals(LyricsFxSettings.ALIGN_START, LyricsFxSettings(lineAlign = LyricsFxSettings.ALIGN_START).clamped().lineAlign)
        val mine = LyricsFxSettings(lineAlign = LyricsFxSettings.ALIGN_START, softFocus = 0.6f)
        val applied = LyricsFxSettings.DEFAULT.withPersonalFrom(mine)
        assertEquals(LyricsFxSettings.ALIGN_START, applied.lineAlign)
        assertEquals(0.6f, applied.softFocus, 0f)
        // So a theme's chip still lights with them set.
        assertTrue(applied.matchesPreset(LyricsFxSettings.DEFAULT))
        assertEquals(lyricLineAlignment(mine), androidx.compose.ui.Alignment.Start)
        assertEquals(lyricLineAlignment(LyricsFxSettings.DEFAULT), androidx.compose.ui.Alignment.CenterHorizontally)
    }
}
