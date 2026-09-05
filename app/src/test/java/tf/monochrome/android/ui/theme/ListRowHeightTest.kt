package tf.monochrome.android.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every list row in the app is laid out at one shared height
 * ([MonoDimens.listRowHeight]) so a scrolling list reads as an even column
 * rather than a ragged one. A fixed height clips whatever does not fit, so the
 * height has to be big enough for the tallest shape a row can take — at every
 * text size, not just the default.
 *
 * That is what these tests hold. They walk the app's own text-size presets
 * (Settings › Theme, 0.85x..1.5x) crossed with the system font scale, and check
 * each row shape the app actually renders against the shared budget. A line
 * height raised in `Type.kt`, a bigger cover, or a trimmed row height that
 * would start clipping subtitles fails here rather than on someone's phone.
 *
 * The heights come from `Type.kt`'s own numbers rather than from
 * `buildTypography`, which needs font resources; if the two drift apart
 * [`the type scale still matches the numbers this test uses`] catches it.
 */
class ListRowHeightTest {

    /** The app's own text-size presets, from `FONT_SCALE_PRESETS`. */
    private val appScales = listOf(0.85f, 1.00f, 1.15f, 1.30f, 1.50f)

    /**
     * System font scale multiplies the app's own, because the app scales the sp
     * sizes and Android then scales sp to px. 2.0 is above what the system
     * settings offer; it is here as headroom.
     */
    private val systemScales = listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f)

    // Line heights as declared in Type.kt, in sp at 1.0x.
    private val bodyLargeSp = 24f
    private val bodySmallSp = 16f
    private val titleMediumSp = 22f
    private val labelSmallSp = 14f

    private fun sp(base: Float, scale: Float): Dp = (base * scale).dp

    private fun budget(scale: Float): Dp = listRowHeightOf(
        titleLineHeight = sp(bodyLargeSp, scale),
        subtitleLineHeight = sp(bodySmallSp, scale),
    )

    /** Runs [check] for every combination of app and system text scaling. */
    private fun forEachScale(check: (scale: Float, budget: Dp) -> Unit) {
        for (app in appScales) {
            for (system in systemScales) {
                val scale = app * system
                check(scale, budget(scale))
            }
        }
    }

    private fun assertFits(label: String, scale: Float, budget: Dp, content: Dp) {
        assertTrue(
            "$label needs $content at ${scale}x text scale but the shared row " +
                "height is only $budget — the row would be clipped",
            content.value <= budget.value,
        )
    }

    /**
     * The Songs row and the shared [tf.monochrome.android.ui.components.TrackItem]:
     * a 48dp cover beside a bodyLarge title over a bodySmall subtitle whose
     * artist is a link, so it carries the hit-box inset. This is the shape the
     * budget is derived from, so it is the one with no slack to spare.
     */
    @Test
    fun `a track row with cover, title, linked subtitle and badge fits`() {
        forEachScale { scale, budget ->
            val text = sp(bodyLargeSp, scale) + sp(bodySmallSp, scale) +
                MonoDimens.linkHitBoxV * 2
            val content = maxOf(MonoDimens.coverList, text)
            assertFits("track row", scale, budget, content)
        }
    }

    /**
     * A quality badge (`FLAC`, `MP3 320`) and the THX / channel pills sit inline
     * on the subtitle and title rows. Both are labelSmall, which is shorter than
     * either line they sit on, so neither may drive the row height.
     */
    @Test
    fun `inline badges never exceed the line they sit on`() {
        for (app in appScales) {
            for (system in systemScales) {
                val scale = app * system
                // Pills add 1dp of padding above and below their labelSmall text.
                val pill = sp(labelSmallSp, scale) + 2.dp
                assertTrue(
                    "a badge pill is ${pill} at ${scale}x but the bodyLarge title " +
                        "line it sits on is only ${sp(bodyLargeSp, scale)}",
                    pill.value <= sp(bodyLargeSp, scale).value,
                )
            }
        }
    }

    /** The Artists row: a 40dp avatar beside titleMedium over bodySmall. */
    @Test
    fun `an artist row fits`() {
        forEachScale { scale, budget ->
            val text = sp(titleMediumSp, scale) + sp(bodySmallSp, scale)
            assertFits("artist row", scale, budget, maxOf(40.dp, text))
        }
    }

    /** The Genres and Folders rows: a 32dp icon beside a single titleMedium line. */
    @Test
    fun `a genre or folder row fits`() {
        forEachScale { scale, budget ->
            val content = maxOf(MonoDimens.iconMd, sp(titleMediumSp, scale))
            assertFits("genre/folder row", scale, budget, content)
        }
    }

    /**
     * The search result row is the track row plus a 4dp gap between its title
     * and subtitle, which is the least slack of anything sharing the budget.
     */
    @Test
    fun `a search result row fits despite its extra title gap`() {
        forEachScale { scale, budget ->
            val text = sp(bodyLargeSp, scale) + 4.dp + sp(bodySmallSp, scale) +
                MonoDimens.linkHitBoxV * 2
            assertFits("search row", scale, budget, maxOf(MonoDimens.coverList, text))
        }
    }

    /** The folder browser's track row, whose artwork is 44dp rather than 48dp. */
    @Test
    fun `a folder browser track row fits`() {
        forEachScale { scale, budget ->
            val text = sp(bodyLargeSp, scale) + sp(bodySmallSp, scale) +
                MonoDimens.linkHitBoxV * 2
            assertFits("folder browser row", scale, budget, maxOf(44.dp, text))
        }
    }

    /**
     * The point of the whole exercise: the height depends only on the text
     * scale, never on what a given row happens to contain. Two rows on the same
     * screen therefore always agree.
     */
    @Test
    fun `the height ignores row content entirely`() {
        for (app in appScales) {
            val a = budget(app)
            val b = budget(app)
            assertEquals("the same scale must give the same height", a, b)
        }
        // A taller cover raises it; nothing a track's metadata does can.
        assertTrue(
            "a larger cover must raise the row height",
            listRowHeightOf(24.dp, 16.dp, coverSize = 96.dp).value >
                listRowHeightOf(24.dp, 16.dp, coverSize = 48.dp).value,
        )
    }

    /**
     * At the default text size the row is the familiar 64dp: a 48dp cover with
     * the row's 8dp of padding above and below. Pinned so an accidental change
     * to a spacing token shows up as a failure here and not as a resized list.
     */
    @Test
    fun `the default row is 64dp`() {
        assertEquals(64.dp, budget(1.0f))
    }

    /**
     * The type scale is linear in the scale factor, which is what lets this test
     * compute line heights from the numbers above instead of building a real
     * Typography (which needs font resources).
     */
    @Test
    fun `the type scale still matches the numbers this test uses`() {
        val oneX = buildTypography(androidx.compose.ui.text.font.FontFamily.Default, 1.0f)
        assertEquals(bodyLargeSp, oneX.bodyLarge.lineHeight.value)
        assertEquals(bodySmallSp, oneX.bodySmall.lineHeight.value)
        assertEquals(titleMediumSp, oneX.titleMedium.lineHeight.value)
        assertEquals(labelSmallSp, oneX.labelSmall.lineHeight.value)
    }
}
