package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tf.monochrome.android.domain.model.LinkArea
import tf.monochrome.android.domain.model.PlayerGlassSettings

/**
 * What a glass block reads from the music links changes only when the glass
 * does. Reading the links' values themselves in a block ran every pane's
 * block on every frame (see [VisualLinkLive.glassState]).
 */
class LinkedGlassTest {
    private val base = PlayerGlassSettings.DEFAULT

    @Test
    fun `a link to something else never changes the glass a block reads`() {
        val live = VisualLinkLive()
        val glass = live.glassState(base, LinkArea.PLAYER)
        assertSame(base, glass.value)
        // A lyric setting and the panels' glass move on every frame.
        for (frame in 1..5) {
            live.values = mapOf("lyrics.godRayExposure" to frame / 5f, "panels.frost" to frame / 10f)
            assertSame(base, glass.value)
        }
    }

    @Test
    fun `a linked glass setting that holds still hands the block the same glass`() {
        val live = VisualLinkLive()
        val glass = live.glassState(base, LinkArea.PLAYER)
        live.values = mapOf("player.frost" to 0.5f, "lyrics.godRayExposure" to 0.1f)
        val first = glass.value
        assertEquals(0.5f, first.frost, 0f)
        // The lyric link moves; the glass's own does not.
        live.values = mapOf("player.frost" to 0.5f, "lyrics.godRayExposure" to 0.9f)
        assertSame(first, glass.value)
    }

    @Test
    fun `a linked glass setting that moves is followed`() {
        val live = VisualLinkLive()
        val glass = live.glassState(base, LinkArea.PLAYER)
        live.values = mapOf("player.frost" to 0.2f)
        val first = glass.value
        live.values = mapOf("player.frost" to 0.8f)
        assertNotEquals(first, glass.value)
        assertEquals(0.8f, glass.value.frost, 0f)
    }

    @Test
    fun `the panels inside the player follow the driver above`() {
        val above = VisualLinkLive()
        val live = VisualLinkLive(above)
        val glass = live.glassState(base, LinkArea.PANELS)
        above.values = mapOf("panels.frost" to 0.4f)
        assertEquals(0.4f, glass.value.frost, 0f)
    }
}
