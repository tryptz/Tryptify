package tf.monochrome.android.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.playerGlass
import kotlin.math.floor

private val SlotSpacing = 8.dp
private val SlotRadius = 2.5.dp
/** Height of the tap target over the marks, independent of the slab's height. */
private val TapTargetHeight = 26.dp

private val HintPadding = 9.dp

/** Span the marks occupy for [pageCount] slots, mark centre to mark centre plus the mark itself. */
private fun marksWidth(pageCount: Int) =
    SlotRadius * 2 + SlotSpacing * (pageCount - 1).coerceAtLeast(0)

/** Width of the compact pill form for [pageCount] slots. */
fun swipeHintPillWidth(pageCount: Int): Dp = HintPadding * 2 + marksWidth(pageCount)

/** Height of the compact pill form. */
val SwipeHintPillHeight = TapTargetHeight

/**
 * The app's page indicator, and the only thing on screen advertising that the
 * pages are swipeable at all.
 *
 * The bottom `NavigationBar` is gone (its imports in `MonochromeNavHost` are
 * dead) and Library's tab row with it, so horizontal swipes are the *only*
 * route between Home, each Library section, and each local-library sub-tab —
 * invisible gestures with nothing pointing at them. This is the glass header
 * they hang off: one slot per page with a filled mark travelling between them.
 *
 * Sizing comes entirely from [modifier], so the same component works as a
 * compact pill or as a full-bleed header bar. The marks are always centred
 * horizontally in whatever bounds it's given; [markCentreY] places them
 * vertically (default: centred), which is how a bar that spans the status bar
 * still puts its marks on the app-bar's centre line. Only a mark-sized region
 * is clickable, not the whole slab — a full-width bar that swallowed taps would
 * fire on presses meant for the title beside it.
 *
 * Motion is a worm indicator: between any two adjacent slots the leading and
 * trailing edges of the mark run on the same easing curve offset by half the
 * travel, so the mark stretches across the gap at the midpoint and contracts
 * into the destination slot. It's driven straight off the pager fraction rather
 * than an animation clock, which is what makes it track a half-finished drag —
 * including one the user abandons — and play identically when a tap drives the
 * pager instead.
 *
 * Built from the same layers as the mini player rather than the `liquidGlass`
 * modifier, so it reads as the same material: an optional Haze frost backdrop,
 * an AGSL [playerGlass] slab over it, and the marks punched *out* of that slab
 * with [BlendMode.DstOut] so they're see-through holes rather than painted
 * glyphs. Callers must provide `LocalPlayerGlass` (the nav host hands it the
 * same `miniPlayerGlass` settings the mini player gets) or the slab falls back
 * to a flat tint. [hazeState] may be null when the bar is drawn *behind* the
 * haze source — Haze can only blur content already drawn this frame — in which
 * case the marks read against the slab's own tint instead of a blurred backdrop.
 *
 * [progressProvider] is the absolute page position across every page — 0f on
 * Home, 1f on the local library, 2f on the next Library section and so on —
 * passed as a lambda rather than a plain Float on purpose. The value changes
 * every frame of a swipe; reading `currentPageOffsetFraction` in composition
 * would recompose this subtree at 60–120 Hz for the whole gesture. Read inside
 * `DrawScope` instead, it only re-runs draw.
 */
@Composable
fun SwipeToLibraryHint(
    pageCount: Int,
    progressProvider: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TapTargetHeight / 2),
    markCentreY: Dp? = null,
    onClickLabel: String? = null,
    hazeState: HazeState? = null,
) {
    if (pageCount < 2) return

    val glass = LocalPlayerGlass.current
    val profile = LocalPerformanceProfile.current
    val tint = if (glass.tintColor != 0) Color(glass.tintColor)
               else MaterialTheme.colorScheme.primary

    Box(modifier = modifier.clip(shape)) {
        // Frosted backdrop UNDER the slab, mirroring the mini player. The slab
        // body can be nearly transparent (bodyOpacity bottoms out at 0.2), so
        // without this the page behind reads through sharply. Skipped entirely
        // when the caller passes no hazeState, and on LOW-tier devices.
        //
        // backgroundColor and at least one tint must be REAL colours; a
        // Transparent background with an empty tint list silently no-ops.
        if (hazeState != null && profile.allowHazeBlur) {
            val frostBg = MaterialTheme.colorScheme.background
            val isDark = frostBg.luminance() <= 0.5f
            val frostTint = if (isDark) Color.Black.copy(alpha = 0.32f)
                            else Color.White.copy(alpha = 0.45f)
            Box(
                Modifier
                    .matchParentSize()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = frostBg,
                            blurRadius = 40.dp,
                            tints = listOf(HazeTint(frostTint)),
                        ),
                    )
            )
        }

        // The glass slab with the marks carved out of it. One offscreen layer so
        // the DstOut punch clears only the mark shapes, not the whole rectangle.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .playerGlass(tint = tint)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(color = tint)

            val cy = markCentreY?.toPx() ?: (size.height / 2f)
            val r = SlotRadius.toPx()
            val pitch = SlotSpacing.toPx()
            // Centre the whole run of marks in whatever width we were given.
            val runWidth = r * 2f + pitch * (pageCount - 1)
            val firstSlot = (size.width - runWidth) / 2f + r
            fun slotX(i: Int) = firstSlot + pitch * i

            // Empty slots, punched softly so they read as dimmed rest positions.
            repeat(pageCount) { i ->
                drawCircle(Color.Black, radius = r, center = Offset(slotX(i), cy),
                    alpha = 0.35f, blendMode = BlendMode.DstOut)
            }

            // ── Worm ────────────────────────────────────────────────────────
            // At rest both edges sit on the same slot, so the shape below is a
            // rounded rect exactly 2r wide and 2r tall — a dot, identical in
            // size to the empty slots, just punched at full strength instead of
            // dimmed. Mid-crossing the edges separate and it elongates into a
            // pill, then collapses back to a dot on arrival.
            //
            // Only ever spans the pair of slots being crossed, so the stretch
            // stays the same length whether there are 2 pages or 10. One edge
            // clears the gap over the first half of the crossing, the other over
            // the second half. min/max rather than assuming which edge leads:
            // the same maths plays in reverse for a backwards swipe, and for a
            // drag the user gives up on halfway and releases back.
            val last = pageCount - 1
            val p = progressProvider().coerceIn(0f, last.toFloat())
            val from = floor(p).toInt().coerceIn(0, last)
            val to = (from + 1).coerceAtMost(last)
            val f = p - from
            val headF = FastOutSlowInEasing.transform((f * 2f).coerceIn(0f, 1f))
            val tailF = FastOutSlowInEasing.transform((f * 2f - 1f).coerceIn(0f, 1f))
            val headX = lerp(slotX(from), slotX(to), headF)
            val tailX = lerp(slotX(from), slotX(to), tailF)
            val left = minOf(headX, tailX) - r
            val right = maxOf(headX, tailX) + r
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(left, cy - r),
                size = Size(right - left, r * 2f),
                cornerRadius = CornerRadius(r, r),
                blendMode = BlendMode.DstOut,
            )
        }

        // Tap target over the marks only. Sized and placed to match where the
        // Canvas drew them, so a full-width slab doesn't steal presses aimed at
        // the title or the action icons sharing this bar.
        val tapWidth = marksWidth(pageCount) + 24.dp
        Box(
            modifier = Modifier
                .align(if (markCentreY == null) Alignment.Center else Alignment.TopCenter)
                .padding(
                    if (markCentreY == null) PaddingValues(0.dp)
                    else PaddingValues(top = (markCentreY - TapTargetHeight / 2).coerceAtLeast(0.dp))
                )
                .size(width = tapWidth, height = TapTargetHeight)
                .clip(RoundedCornerShape(TapTargetHeight / 2))
                .clickable(onClickLabel = onClickLabel, onClick = onClick)
        )
    }
}
