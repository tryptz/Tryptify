package tf.monochrome.android.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.components.LIGHT_INK
import tf.monochrome.android.ui.components.glassFace
import tf.monochrome.android.ui.components.glassInk

/**
 * The player's glass stands over the player's own dark background, whatever
 * the theme and whatever the cover: its panes' text is light. Worked out
 * against a light theme's page instead, the Speed panel drew dark text on dark
 * red glass.
 */
class PlayerGlassGroundTest {

    private val covers = listOf(
        Color.White, Color(0xFFFFF59D), Color(0xFFFFC0CB), Color(0xFF9E1B1B),
        Color(0xFF1B5E20), Color(0xFF0D47A1), Color.Black,
    )

    @Test
    fun `the ground is dark under every cover`() {
        for (c in covers) assertTrue("$c", playerGlassGround(c).luminance() < 0.05f)
    }

    @Test
    fun `text on the player's glass is light under every cover, with a light theme's frost or a dark one's`() {
        val lightFrost = Color.White.copy(alpha = 0.26f)
        val darkFrost = Color.Black.copy(alpha = 0.32f)
        // The frost a light theme gives is never what the player passes now
        // (it is chosen from this dark ground), but even it stays dark enough.
        for (c in covers) for (frost in listOf(darkFrost, lightFrost)) {
            val face = glassFace(playerGlassGround(c), frost, Color(0xFFB71C1C), bodyOpacity = 0.2f, shaded = true, fallback = Color(0xFF2A2A2A))
            assertEquals("$c / $frost", LIGHT_INK, glassInk(face, Color(0xFFE53935)).text)
        }
    }
}
