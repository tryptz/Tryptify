package tf.monochrome.android.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whatever look the listener picks, the widget's text stays readable on its glass. */
class WidgetStyleTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private val covers = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFE8875A, 0xFFFFEB3B, 0xFF1A237E, 0xFF00E5FF, 0xFF808080, 0xFFFF1744,
    ).map { it.toInt() }

    /** Wallpaper main colours, from black to white; the owner's is the light pink one. */
    private val wallpapers = listOf(
        0xFF000000, 0xFF2A2840, 0xFF1B5E20, 0xFF7E57C2, 0xFFE5643A, 0xFF9E9E9E, 0xFFE8DDE0, 0xFFFFFFFF,
    ).map { Color(it.toInt()) }

    @Test
    fun onAutoTheTextReadsOnEveryGlassOverEveryWallpaper() {
        for (ground in wallpapers) for (glass in WidgetStyle.Glass.entries) for (cover in covers) {
            for (opacity in WidgetStyle.MIN_OPACITY..WidgetStyle.MAX_OPACITY step 5) {
                val look = resolveWidgetLook(WidgetStyle(glass = glass, opacity = opacity), cover, ground)
                val seen = look.pane.compositeOver(ground)
                assertTrue(
                    "$glass $opacity% cover ${Integer.toHexString(cover)} on ${Integer.toHexString(ground.toArgb())}",
                    contrast(look.ink, seen) >= 4.0f,
                )
            }
        }
    }

    @Test
    fun theOwnersCaseDarkGlassAtTwentyTwoOnALightWallpaperGetsDarkText() {
        // Seen on device: faint white text on light glass, judged over a fixed dim colour.
        val look = resolveWidgetLook(WidgetStyle(glass = WidgetStyle.Glass.Dark, opacity = 22), null, Color(0xFFE8DDE0))
        assertTrue(look.ink.luminance() < 0.05f)
    }

    @Test
    fun whiteAndDarkOverrideTheWallpaper() {
        val light = Color(0xFFFFFFFF)
        val dim = Color(0xFF101010)
        assertEquals(Color.White, resolveWidgetLook(WidgetStyle(text = WidgetStyle.Text.White), null, light).ink)
        assertTrue(resolveWidgetLook(WidgetStyle(text = WidgetStyle.Text.Dark), null, dim).ink.luminance() < 0.05f)
    }

    @Test
    fun theDefaultIsClearWhiteGlassWithWhiteText() {
        val look = resolveWidgetLook(WidgetStyle.DEFAULT, coverColor = null)
        assertEquals(Color.White.copy(alpha = 0.18f).toArgb(), look.pane.toArgb())
        assertEquals(Color.White, look.ink)
        assertEquals(Color.White, look.disc)
    }

    @Test
    fun denseLightGlassTurnsTheTextDark() {
        val look = resolveWidgetLook(WidgetStyle(glass = WidgetStyle.Glass.Light, opacity = 80), coverColor = null)
        assertTrue(look.ink.luminance() < 0.05f)
        // The solid disc follows the ink, and its glyph is the opposite.
        assertEquals(look.ink, look.disc)
        assertTrue(look.onDisc.luminance() > 0.9f)
    }

    @Test
    fun theGlassPlayButtonIsAClearDiscWithAnInkGlyph() {
        val look = resolveWidgetLook(WidgetStyle(play = WidgetStyle.PlayButton.Glass), coverColor = null)
        assertEquals(look.ink, look.onDisc)
        assertTrue(look.disc.alpha < 0.5f)
    }

    @Test
    fun noEdgeMeansNoEdge() {
        assertNull(resolveWidgetLook(WidgetStyle(edge = false), coverColor = null).edge)
    }

    @Test
    fun albumGlassWithNoCoverFallsBackToLightGlass() {
        val album = resolveWidgetLook(WidgetStyle(glass = WidgetStyle.Glass.Album), coverColor = null)
        val light = resolveWidgetLook(WidgetStyle(glass = WidgetStyle.Glass.Light), coverColor = null)
        assertEquals(light, album)
    }
}
