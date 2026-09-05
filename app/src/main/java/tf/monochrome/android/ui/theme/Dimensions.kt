package tf.monochrome.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Centralized design tokens for consistent spacing, shapes, and sizes
 * across the Monochrome UI.
 */
object MonoDimens {

    // ── Spacing ──────────────────────────────────────────────────────
    /** Minimal spacing between tightly related elements */
    val spacingXs = 4.dp
    /** Small spacing (icon-to-text gap, inner row padding) */
    val spacingSm = 8.dp
    /** Default spacing between elements */
    val spacingMd = 12.dp
    /** Standard horizontal page padding */
    val spacingLg = 16.dp
    /** Breathing room for section separators */
    val spacingXl = 24.dp

    // ── Corner Radii ─────────────────────────────────────────────────
    /** Small inline elements (cover thumbnails, chips) */
    val radiusSm = 6.dp
    /** Medium cards and containers */
    val radiusMd = 12.dp
    /** Large artwork and hero images */
    val radiusLg = 16.dp
    /** Pill-shaped buttons and search bars */
    val radiusPill = 24.dp

    // ── Shapes (reusable, no allocations per recompose) ─────────────
    val shapeSm = RoundedCornerShape(radiusSm)
    val shapeMd = RoundedCornerShape(radiusMd)
    val shapeLg = RoundedCornerShape(radiusLg)
    val shapePill = RoundedCornerShape(radiusPill)
    val shapeCircle = CircleShape

    // ── Icon Sizes ───────────────────────────────────────────────────
    /** Inline icons within text rows */
    val iconSm = 24.dp
    /** List-item leading icons (genre, folder, etc.) */
    val iconMd = 32.dp
    /** Cover thumbnails in lists */
    val iconLg = 48.dp
    /** Primary action buttons (play/pause) */
    val iconXl = 64.dp

    // ── Cover Art Sizes ──────────────────────────────────────────────
    /** Mini player artwork */
    val coverMini = 40.dp
    /** List item artwork */
    val coverList = 48.dp
    /** Album grid / artist card artwork */
    val coverCard = 160.dp
    /** Album detail hero artwork */
    val coverHero = 240.dp
    /** Now playing artwork */
    val coverPlayer = 300.dp

    // ── List Item Dimensions ─────────────────────────────────────────
    /** Standard horizontal padding for list items */
    val listItemPaddingH = 16.dp
    /** Standard vertical padding for list items */
    val listItemPaddingV = 10.dp
    /** Bottom padding to clear mini player / nav bar */
    val listBottomPadding = 80.dp
    /**
     * Vertical inset on an inline artist/album link's hit box. Lives here
     * rather than in `ClickableArtists` because [listRowHeight] has to budget
     * for it — a subtitle with a linkable artist is this much taller than one
     * with a plain "Unknown Artist", which is one of the two things that used
     * to make library rows ragged.
     */
    val linkHitBoxV = 4.dp

    /**
     * The one height every list row is laid out at.
     *
     * Rows used to size themselves to their content, and content in a music
     * library is not uniform. Three things moved the height around: a subtitle
     * whose artist and album did not both fit wrapped to a second line, a
     * linkable artist carried [linkHitBoxV] where a plain one did not, and a
     * row whose text was shorter than its artwork was sized by the artwork
     * instead. Neighbouring rows differed by up to a whole line, which is very
     * visible in a long scrolling list.
     *
     * Derived from the typography rather than pinned to a dp constant because
     * the app ships its own text-size setting (Settings > Theme, 0.85x..1.5x)
     * and rebuilds the whole type scale from it. A hard 64dp is right at 1.0x
     * and clips the subtitle at the top presets; this tracks the type, so the
     * rows grow together and stay equal at every scale.
     *
     * The budget is the tallest row shape the app has: a `bodyLarge` title over
     * a `bodySmall` subtitle carrying a link inset, or the 48dp cover if the
     * type is smaller than that, plus the row's own vertical padding.
     */
    val listRowHeight: Dp
        @Composable get() {
            val typography = MaterialTheme.typography
            return with(LocalDensity.current) {
                listRowHeightOf(
                    titleLineHeight = lineHeightDp(typography.bodyLarge),
                    subtitleLineHeight = lineHeightDp(typography.bodySmall),
                )
            }
        }

    // ── Card surface alpha ───────────────────────────────────────────
    /** Uniform alpha for all card/surface backgrounds */
    const val cardAlpha = 0.85f

    // ── Glass effect tokens ─────────────────────────────────────────
    /** Tint layer opacity for glass cards */
    const val glassAlpha = 0.25f
    /** Border glow opacity for glass cards */
    const val glassBorderAlpha = 0.15f
    /** Thin luminous border width */
    val glassBorderWidth = 0.5.dp
    /** Backdrop blur radius for glass surfaces */
    val glassBlurRadius = 80.dp
    /** Soft shadow elevation for glass cards */
    val glassElevation = 4.dp
}

/**
 * A text style's line height in dp, falling back to its font size when a style
 * leaves the line height unspecified (the app's own scale always sets both, but
 * `MaterialTheme.typography` is replaceable and `toDp()` throws on an
 * unspecified unit).
 */
private fun Density.lineHeightDp(style: TextStyle): Dp {
    val unit = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    return if (unit.isSpecified) unit.toDp() else 0.dp
}

/**
 * The rule behind [MonoDimens.listRowHeight], split out so it can be checked
 * without a Compose runtime — see `ListRowHeightTest`, which asserts that every
 * row shape in the app fits inside it at every text-size preset.
 *
 * The budget is the tallest shape a row can take: a `bodyLarge` title stacked
 * over a `bodySmall` subtitle whose artist is a link (and so carries
 * [MonoDimens.linkHitBoxV] top and bottom), or the 48dp cover when the type is
 * smaller than that. Rows with shorter content centre inside it rather than
 * shrinking, which is the whole point — a list of equal rows.
 */
internal fun listRowHeightOf(
    titleLineHeight: Dp,
    subtitleLineHeight: Dp,
    coverSize: Dp = MonoDimens.coverList,
    linkInset: Dp = MonoDimens.linkHitBoxV,
    verticalPadding: Dp = MonoDimens.spacingSm,
): Dp = maxOf(coverSize, titleLineHeight + subtitleLineHeight + linkInset * 2) + verticalPadding * 2
