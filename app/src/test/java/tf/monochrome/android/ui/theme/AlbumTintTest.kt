package tf.monochrome.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Tint the menus", held to the one promise that makes it usable.
 *
 * The feature hands the album's colours to [customScheme], which already has its
 * own contrast guarantees (`CustomSchemeTest`). What it cannot guarantee is
 * *stability*: it will happily build a light scheme from a bright cover and a
 * dark one from the next track, and a queue that alternates between the two
 * strobes the whole app between light and dark. That is the same class of bug as
 * the globe inverting, and it is exactly what [albumTintedGround] exists to
 * prevent — it moves the ground only a quarter of the way, so the theme keeps
 * deciding the polarity.
 *
 * These tests are that guarantee. Do not raise the mix to make a tint stronger.
 */
class AlbumTintTest {

    /**
     * The app's real grounds, both ends of the range. The dark ones come from
     * the presets, the light ones from the two papers.
     */
    private val darkGrounds = listOf(0xFF050706, 0xFF101014, 0xFF0D1117, 0xFF2E3440, 0xFF282A36)
        .map { Color(it) }
    private val lightGrounds = listOf(0xFFFFFFFF, 0xFFFAF6EF, 0xFFECEFF4, 0xFFF5F5F7)
        .map { Color(it) }

    /** Covers, from a black sleeve to a white one and a spread in between. */
    private val covers = listOf(
        0xFF000000, 0xFFFFFFFF,
        0xFFB23030, 0xFFB27A2A, 0xFF4CB230, 0xFF2AB2A8, 0xFF3060B2, 0xFF7A30B2, 0xFFB2307A,
        0xFFF08A8A, 0xFFF0C87A, 0xFFA6F07A, 0xFF7AF0E6, 0xFF7AA6F0, 0xFFC87AF0, 0xFFF07AC8,
        0xFF3A0D0D, 0xFF0D1E3A, 0xFF1A1A1E, 0xFFE0E0E6, 0xFF808088,
    ).map { Color(it) }

    /**
     * The line the scheme builder itself draws: which way its ink points. A
     * ground whose `onBackground` is bright is a dark theme, and vice versa.
     */
    private fun isDarkTheme(ground: Color): Boolean =
        relativeLuminance(customScheme(Color(0xFF5865F2), ground).onBackground) > 0.5

    @Test
    fun `no cover flips a dark theme light`() {
        for (ground in darkGrounds) {
            for (cover in covers) {
                val tinted = albumTintedGround(ground, cover)
                assertTrue(
                    "ground ${ground.hex()} + cover ${cover.hex()} -> ${tinted.hex()} stopped " +
                        "reading as a dark theme",
                    isDarkTheme(tinted),
                )
            }
        }
    }

    @Test
    fun `no cover flips a light theme dark`() {
        for (ground in lightGrounds) {
            for (cover in covers) {
                val tinted = albumTintedGround(ground, cover)
                assertTrue(
                    "ground ${ground.hex()} + cover ${cover.hex()} -> ${tinted.hex()} stopped " +
                        "reading as a light theme",
                    !isDarkTheme(tinted),
                )
            }
        }
    }

    /**
     * The other half of the deal: a tint nobody can see is not a feature. Any
     * cover that is not already the ground has to move it somewhere.
     */
    @Test
    fun `a cover actually moves the ground`() {
        for (ground in darkGrounds + lightGrounds) {
            for (cover in covers) {
                val tinted = albumTintedGround(ground, cover)
                val distance = maxOf(
                    kotlin.math.abs(tinted.red - ground.red),
                    kotlin.math.abs(tinted.green - ground.green),
                    kotlin.math.abs(tinted.blue - ground.blue),
                )
                val separation = maxOf(
                    kotlin.math.abs(cover.red - ground.red),
                    kotlin.math.abs(cover.green - ground.green),
                    kotlin.math.abs(cover.blue - ground.blue),
                )
                if (separation < 0.1f) continue
                assertTrue(
                    "cover ${cover.hex()} left ground ${ground.hex()} essentially untouched",
                    distance >= separation * 0.15f,
                )
            }
        }
    }

    /**
     * Bypassing the ground is a bypass, not a softer tint: the background comes
     * back byte-for-byte, because [customScheme] uses what it is handed verbatim.
     */
    @Test
    fun `keeping the theme background returns it unchanged`() {
        for (ground in darkGrounds + lightGrounds) {
            val kept = customScheme(Color(0xFFB2307A), ground)
            assertTrue(
                "background drifted: asked ${ground.hex()}, got ${kept.background.hex()}",
                ground.hex() == kept.background.hex(),
            )
        }
    }

    private fun Color.hex(): String =
        "#" + (
            (red * 255).toInt() shl 16 or
                ((green * 255).toInt() shl 8) or
                (blue * 255).toInt()
            ).toString(16).padStart(6, '0')
}
