package tf.monochrome.android.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.player.BackdropArtFit
import tf.monochrome.android.ui.player.LocalPlayerBackdrop
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerBackdrop
import tf.monochrome.android.ui.player.playerFrostTint
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.player.rememberLiquidGlassAvailable
import tf.monochrome.android.ui.theme.PressSpring
import tf.monochrome.android.ui.theme.glassTint
import tf.monochrome.android.ui.theme.reduceMotion

/** Height of the tab pill and the round Search button beside it. */
internal val TabBarHeight = 64.dp

private val TabGlyph = 26.dp
// Where the glyph sits in the slot when it has a title under it. The punch and
// the overlay both read this, so a hole can never drift off the glyph that
// lights up over it.
private val TabGlyphTop = 9.dp
private val TabLabelGap = 3.dp

// Room for the lit glyph's bloom; see PlayerActionDock's DockBloomPadding for
// why a blur needs margin around the glyph it smears.
private val TabBloomPadding = 12.dp
private val TabBloomBox = TabGlyph + TabBloomPadding * 2

/**
 * A row of tabs carved out of one sheet of glass — the player dock's material,
 * with a title under each glyph.
 *
 * Built exactly like the mini player, because it sits beside it and is the same
 * material: the frost of the app behind it, then a solid slab of the bar's tint
 * relit by the glass shader, with every glyph punched out of the slab so the
 * frosted backdrop shows through and the shader bevels each cut edge. The
 * selected tab's glyph lights up over its hole in [accent], with the dock's
 * bloom, and the slab swells under whichever tab is pressed.
 *
 * Titles are drawn as plain text rather than punched. The glyphs are chunky on
 * purpose — the bevel needs about 3dp of stroke to read as an edge — and an
 * 11sp title's strokes are thinner than the bevel itself, so etched titles
 * would come out as a smear.
 *
 * Used three ways: the four-tab pill, the round Search button (one tab), and
 * the minimised pill the bar folds down to on scroll ([showLabels] false, one
 * tab: the glyph alone, centred).
 *
 * Without the shader — below API 33, glass off, or a device that cannot compile
 * it — the slab would be an opaque block with holes in it, so the bar falls
 * back to the app's frosted pane with ordinary icons, like the mini player's
 * legacy path.
 */
@Composable
internal fun GlassTabBar(
    tabs: List<AppTab>,
    selected: AppTab?,
    onSelect: (AppTab) -> Unit,
    accent: Color,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    val shape = RoundedCornerShape(TabBarHeight / 2)
    val painters = tabs.map { painterResource(it.glyph) }
    val glyphTop = if (showLabels) TabGlyphTop else (TabBarHeight - TabGlyph) / 2

    // One interaction source per tab, so the slab knows which one is held. The
    // dome is placed on the pressed tab, not animated to it — see the dock.
    val sources = remember(tabs.size) { List(tabs.size) { MutableInteractionSource() } }
    val pressed = sources.map { it.collectIsPressedAsState() }
    val pressedIndex = pressed.indexOfFirst { it.value }
    val bulgeSlot = remember { mutableIntStateOf(0) }
    LaunchedEffect(pressedIndex) { if (pressedIndex >= 0) bulgeSlot.intValue = pressedIndex }
    val bulgeAmt by animateFloatAsState(
        targetValue = if (pressedIndex >= 0) 1f else 0f,
        animationSpec = PressSpring,
        label = "tabBulge",
    )

    val glass = LocalPlayerGlass.current
    val tint = glassTint(glass.tintColor)
    val shaderGlass = rememberLiquidGlassAvailable()

    Box(
        modifier = modifier
            .height(TabBarHeight)
            .clip(shape)
            .then(if (shaderGlass) Modifier else Modifier.liquidGlass(hazeState = hazeState, shape = shape)),
    ) {
        if (shaderGlass) {
            // The frost — same style and the same gate as the mini player's, so
            // the two read as one material side by side.
            val profile = LocalPerformanceProfile.current
            if (hazeState != null && profile.allowHazeBlur && glass.hazeBlurDp > 0f) {
                val frostBg = MaterialTheme.colorScheme.background
                val frostTint = playerFrostTint(glass, isDark = frostBg.luminance() <= 0.5f)
                Box(
                    Modifier
                        .matchParentSize()
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = frostBg,
                                blurRadius = glass.hazeBlurDp.dp,
                                tints = listOf(HazeTint(frostTint)),
                                noiseFactor = 0f,
                            ),
                        ),
                )
            }

            // No cover to lens: the bar is chrome, not a window onto artwork,
            // so the backdrop is the flat tint the shader reconstructs exactly.
            val backdrop = remember(tint) {
                PlayerBackdrop(dominant = tint, secondary = tint, fit = BackdropArtFit.PANE)
            }
            CompositionLocalProvider(LocalPlayerBackdrop provides backdrop) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .playerGlass(
                            tint = tint,
                            bulgeCenter = Offset((bulgeSlot.intValue + 0.5f) / tabs.size, 0.5f),
                            bulgeAmount = { bulgeAmt },
                        )
                        // One offscreen layer, so the punch clears only the
                        // glyphs and never the app behind the bar.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    val corner = size.height / 2f
                    // Solid, never faint: the shader builds its bevel from this
                    // fill's alpha, and the body opacity is what makes it see-through.
                    drawRoundRect(color = tint, cornerRadius = CornerRadius(corner, corner))
                    punchTabGlyphs(painters, glyphTop)
                }
            }
        }

        Row(modifier = Modifier.matchParentSize()) {
            tabs.forEachIndexed { i, tab ->
                TabSlot(
                    tab = tab,
                    painter = painters[i],
                    selected = tab == selected,
                    accent = accent,
                    showLabel = showLabels,
                    glyphTop = glyphTop,
                    // Over a punched hole the glyph is only drawn when lit; on
                    // the fallback pane every glyph is an ordinary icon.
                    carved = shaderGlass,
                    interactionSource = sources[i],
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Erase each glyph from the slab, centred in its equal-width slot. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.punchTabGlyphs(
    painters: List<Painter>,
    glyphTop: Dp,
) {
    val glyphPx = TabGlyph.toPx()
    val top = glyphTop.toPx()
    // Anti-aliased DstOut and whole-pixel placement: the same hygiene as the
    // dock and the mini player, or the holes come out stair-stepped and soft.
    val punch = Paint().apply {
        blendMode = BlendMode.DstOut
        isAntiAlias = true
    }
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), punch)
    painters.forEachIndexed { i, painter ->
        val cx = size.width * (i + 0.5f) / painters.size
        translate(kotlin.math.round(cx - glyphPx / 2f), kotlin.math.round(top)) {
            with(painter) { draw(Size(glyphPx, glyphPx)) }
        }
    }
    canvas.restore()
}

@Composable
private fun TabSlot(
    tab: AppTab,
    painter: Painter,
    selected: Boolean,
    accent: Color,
    showLabel: Boolean,
    glyphTop: Dp,
    carved: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val still = reduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !still) 0.92f else 1f,
        animationSpec = PressSpring,
        label = "tabScale",
    )
    val lit by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (still) spring(stiffness = Spring.StiffnessHigh) else spring(stiffness = Spring.StiffnessLow),
        label = "tabLit",
    )
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClickLabel = tab.label,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(glyphTop))
        Box(modifier = Modifier.size(TabGlyph), contentAlignment = Alignment.Center) {
            if (carved) {
                if (lit > 0.004f) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier
                            .requiredSize(TabBloomBox)
                            .blur(radius = 9.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer { alpha = lit }
                            .padding(TabBloomPadding),
                        tint = accent.copy(alpha = 0.6f),
                    )
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(TabGlyph).graphicsLayer { alpha = lit },
                        tint = accent,
                    )
                }
            } else {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(TabGlyph),
                    tint = androidx.compose.ui.graphics.lerp(idle, accent, lit),
                )
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(TabLabelGap))
            // The pill is a fixed 64dp, so the title follows the system font
            // scale only so far — past that it would push itself out of the
            // bottom of the glass. Apple caps tab labels for the same reason.
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f)),
            ) {
                Text(
                    text = tab.label,
                    color = androidx.compose.ui.graphics.lerp(idle, accent, lit),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
