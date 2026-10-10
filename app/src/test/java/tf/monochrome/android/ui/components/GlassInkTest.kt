package tf.monochrome.android.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.theme.contrastRatio

/**
 * Text on glass follows the glass: light on dark panes, dark on light ones,
 * whatever the theme's own ink is — and always readable on the pane it is on.
 */
class GlassInkTest {

    private val space = Color(0xFF04060E)
    private val lime = Color(0xFFD9DB0F)
    private val darkFrost = Color.Black.copy(alpha = 0.32f)
    private val lightFrost = Color.White.copy(alpha = 0.26f)
    private val surface = Color(0xFF2A2A2A)

    @Test
    fun `dark glass over the galaxy has light text, even in a light theme`() {
        // The theme's ink would be dark; the pane is not the page.
        val face = glassFace(space, lightFrost, lime, bodyOpacity = 0.2f, shaded = true, fallback = surface)
        assertEquals(LIGHT_INK, glassInk(face, lime).text)
    }

    @Test
    fun `light glass on a light page has dark text`() {
        val page = Color(0xFFF4F1EA)
        val face = glassFace(page, lightFrost, Color(0xFF6A4CFF), bodyOpacity = 0.2f, shaded = true, fallback = surface)
        assertEquals(DARK_INK, glassInk(face, Color(0xFF6A4CFF)).text)
    }

    @Test
    fun `a bright tint laid on thick turns the text dark`() {
        val face = glassFace(space, darkFrost, Color(0xFFFFF59D), bodyOpacity = 1f, shaded = true, fallback = surface)
        assertEquals(DARK_INK, glassInk(face, Color(0xFFFFF59D)).text)
    }

    @Test
    fun `the text clears AA and the accent 3 to 1 on every pane`() {
        val grounds = listOf(space, Color(0xFF121212), Color(0xFF808080), Color(0xFFF4F1EA), Color.White)
        val tints = listOf(lime, Color(0xFFFF4D6D), Color(0xFF2B59FF), Color(0xFFFFFFFF), Color(0xFF101010))
        for (ground in grounds) for (tint in tints) for (opacity in listOf(0f, 0.2f, 0.5f, 1f)) {
            for (frost in listOf(darkFrost, lightFrost)) {
                val face = glassFace(ground, frost, tint, opacity, shaded = true, fallback = surface)
                val ink = glassInk(face, tint)
                assertTrue("text on $face", contrastRatio(ink.text, face) >= 4.5)
                assertTrue("accent on $face", contrastRatio(ink.accent.copy(alpha = 1f), face) >= 3.0 - 1e-3)
            }
        }
    }

    @Test
    fun `an accent that already reads is left alone`() {
        val face = glassFace(space, darkFrost, lime, bodyOpacity = 0.2f, shaded = true, fallback = surface)
        assertEquals(lime, glassInk(face, lime).accent)
    }
}
