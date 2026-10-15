package tf.monochrome.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Song row badges: each on its own, and all of them at once. */
class RowBadgesTest {

    @Test
    fun `every badge shows until turned off`() {
        for (badge in RowBadge.entries) assertTrue("$badge", RowBadges().shows(badge))
        assertTrue(RowBadges().any)
    }

    @Test
    fun `turning one off leaves the rest`() {
        for (badge in RowBadge.entries) {
            val badges = RowBadges().with(badge, false)
            assertFalse("$badge", badges.shows(badge))
            for (other in RowBadge.entries - badge) assertTrue("$badge leaves $other", badges.shows(other))
            assertTrue(badges.any)
            assertEquals(RowBadges(), badges.with(badge, true))
        }
    }

    @Test
    fun `none is every one off, and one back on is some`() {
        for (badge in RowBadge.entries) assertFalse("$badge", RowBadges.NONE.shows(badge))
        assertFalse(RowBadges.NONE.any)
        assertTrue(RowBadges.NONE.with(RowBadge.DURATION, true).any)
    }
}
