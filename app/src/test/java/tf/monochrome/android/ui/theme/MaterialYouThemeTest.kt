package tf.monochrome.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system palette entry, held to the three things that are easy to get wrong
 * about it and impossible to see from the code.
 *
 * The scheme itself is not asserted here and cannot be: it is read back from the
 * platform, needs a real Context, and its whole point is that it differs per
 * device and per wallpaper. What is pinned instead is the wiring around it —
 * that the entry is offered exactly where it works, named everywhere, and never
 * treated as one of the generated palettes. Each of those three has a failure
 * mode that is silent rather than loud.
 */
class MaterialYouThemeTest {

    /** Android 12, where the system palette first exists. */
    private val androidS = 31

    /** Android 11, the last release without one. */
    private val androidR = 30

    @Test
    fun `the system palette is offered on Android 12 and up`() {
        assertTrue(
            "Android 12+ can read a system palette, so the picker must offer it",
            selectableThemes(androidS).containsKey(MATERIAL_YOU_KEY),
        )
    }

    @Test
    fun `the system palette is withheld below Android 12, and nothing else is`() {
        val old = selectableThemes(androidR)
        assertFalse(
            "There is no system palette before Android 12; offering it would " +
                "silently resolve to Monochrome",
            old.containsKey(MATERIAL_YOU_KEY),
        )
        assertEquals(
            "Gating the system palette must not drop any other theme",
            themeDisplayNames - MATERIAL_YOU_KEY,
            old,
        )
    }

    @Test
    fun `the system palette is always nameable, even where it cannot be chosen`() {
        // The Settings subtitle looks the stored key up in the full catalogue.
        // A preference restored from a backup, or synced from a newer Android,
        // must render as a name rather than as the raw key.
        assertTrue(themeDisplayNames.containsKey(MATERIAL_YOU_KEY))
    }

    @Test
    fun `the system palette is not a generated theme`() {
        // ThemeAccents drives the built light variants. An accent here would
        // manufacture a "material_you_light" twin from a fixed colour — a second
        // palette wearing the system's name while ignoring the system entirely.
        assertFalse(
            "The OS supplies both polarities itself; it has no accent of ours",
            ThemeAccents.containsKey(MATERIAL_YOU_KEY),
        )
        assertFalse(
            themeDisplayNames.containsKey(lightVariantOf(MATERIAL_YOU_KEY)),
        )
    }

    @Test
    fun `below Android 12 the system palette falls back to Monochrome`() {
        // rememberMaterialYouScheme returns null there and MonochromeTheme drops
        // through to getColorScheme, which must land somewhere real rather than
        // throwing on a key it has no branch for.
        assertSame(MonochromeDarkScheme, getColorScheme(MATERIAL_YOU_KEY))
    }
}
