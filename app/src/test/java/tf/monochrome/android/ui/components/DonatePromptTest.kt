package tf.monochrome.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the tip bar is offered.
 *
 * The failures that matter are the two ends: a bar that never goes away, and
 * one that never arrives. Neither is visible in a screenshot of a working app,
 * and the second is invisible entirely, so both are pinned here.
 */
class DonatePromptTest {

    private val every = DonatePrompt.EVERY_N_SONGS

    @Test
    fun `nothing is offered before the songs have played`() {
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = 0, neverShow = false))
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = every - 1, neverShow = false))
    }

    @Test
    fun `the bar arrives on the twentieth song`() {
        assertTrue(DonatePrompt.shouldShow(playsSincePrompt = every, neverShow = false))
    }

    @Test
    fun `it keeps showing past the threshold until it is put away`() {
        // The count is what the bar is read from, so it has to keep answering
        // yes while it sits there -- a strict equality check would blink the
        // bar out of existence on the next song.
        assertTrue(DonatePrompt.shouldShow(playsSincePrompt = every + 1, neverShow = false))
        assertTrue(DonatePrompt.shouldShow(playsSincePrompt = every * 5, neverShow = false))
    }

    @Test
    fun `never-show wins over any number of songs`() {
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = every, neverShow = true))
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = every * 100, neverShow = true))
    }

    @Test
    fun `dismissing resets the count, so the next offer is a full run away`() {
        // What dismissDonatePrompt stores.
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = 0, neverShow = false))
        assertFalse(DonatePrompt.shouldShow(playsSincePrompt = every - 1, neverShow = false))
        assertTrue(DonatePrompt.shouldShow(playsSincePrompt = every, neverShow = false))
    }

    @Test
    fun `the interval is occasional rather than nagging`() {
        // A guard on the constant itself: this is the one number that decides
        // how often the app asks for money, and dropping it to something small
        // would be an easy edit with a large effect.
        assertTrue("asking more often than every 10 songs is nagging", every >= 10)
    }
}
