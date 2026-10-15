package tf.monochrome.android.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.bounceClick
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tf.monochrome.android.ui.components.GlassMaterial
import tf.monochrome.android.ui.components.GlassPressDefaults
import tf.monochrome.android.ui.components.glassBase
import tf.monochrome.android.ui.components.rememberGlassPress
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.reduceMotion

// The speed panel's one button family. The panel used to mix four kinds of
// control — Material's segmented row (grey when selected, whatever the
// theme), a square-cornered chip, and two sizes of outlined pill whose
// accent-coloured labels sat accent-on-accent on a tinted panel — so nothing
// in it read as belonging together and the steppers barely read at all.
//
// Here every control is the same capsule at the same height: a quiet tonal
// fill that sits on the glass rather than being more glass (a button on the
// pane, not a pane), labels in the panel's own content colour for contrast,
// and the accent kept for what is live — the selected segment, the stepper's
// signs. Press feedback is bounceClick, the app's squeeze
// for controls that aren't glass themselves.

/** Height of every control in the panel: the 48dp touch minimum, less a hair. */
internal val SpeedControlHeight: Dp = 44.dp

private val Capsule = RoundedCornerShape(percent = 50)

/** The quiet fill and hairline every unselected control shares. */
@Composable
private fun tonalFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

@Composable
private fun tonalBorder(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

/** Readable content on an accent fill, whatever the theme's accent is. */
private fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * A two-or-more-way choice as one capsule, its choice marked by a pill of
 * glass that moves between the segments: it slides to a tapped one, and it can
 * be taken and dragged freely along the capsule, settling on the nearest
 * segment when let go. Replaces a solid accent fill, which was the one control
 * in the panel that was not glass.
 *
 * The thumb is the app's pill recipe ([glassBase] and [GlassMaterial]), in the
 * panel's own material, with the accent rim a selected glass chip has. It
 * swells under the finger while held. It frosts nothing: it sits inside a
 * glass panel, not beside a backdrop of its own, and shader glass without a
 * frost is still glass (docs/ui-invariants.md). Without the shader the rim is
 * what marks it. With "Disable animations" it jumps rather than slides; a drag
 * still follows the finger.
 */
@Composable
internal fun SpeedSegmented(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = options.size
    if (count == 0) return
    val still = reduceMotion()
    val glass = LocalMiniPlayerGlass.current
    val press = rememberGlassPress()
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    val selectedNow by rememberUpdatedState(selectedIndex)
    // Where the thumb is, in segments: 0 over the first, count - 1 over the last.
    val position = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    // A choice made elsewhere (a tap, or the setting changing under it) moves
    // the thumb; a drag in progress owns it.
    LaunchedEffect(selectedIndex, still) {
        if (dragging) return@LaunchedEffect
        if (still) position.snapTo(selectedIndex.toFloat())
        else position.animateTo(selectedIndex.toFloat(), ThumbSpring)
    }

    BoxWithConstraints(
        modifier = modifier
            .height(SpeedControlHeight)
            .clip(Capsule)
            .background(tonalFill())
            .border(1.dp, tonalBorder(), Capsule)
            .padding(3.dp),
    ) {
        val density = LocalDensity.current
        val gapPx = with(density) { SegmentGap.toPx() }
        val segmentPx = segmentWidth(constraints.maxWidth.toFloat(), gapPx, count)
        val stepPx = segmentPx + gapPx
        val segmentDp = with(density) { segmentPx.toDp() }

        fun follow(x: Float, y: Float) {
            val at = thumbAt(x, segmentPx, stepPx, count)
            // Where the finger is on the thumb, for the swell.
            press.pressPoint = Offset(x - at * stepPx, y)
            scope.launch { position.snapTo(at) }
        }
        fun settle() {
            val target = nearestSegment(position.value, count)
            press.held = false
            scope.launch {
                if (still) position.snapTo(target.toFloat())
                else position.animateTo(target.toFloat(), ThumbSpring)
                dragging = false
            }
            if (target != selectedNow) select(target)
        }

        // The thumb, under the labels.
        Box(
            modifier = Modifier
                .offset { IntOffset((position.value * stepPx).roundToInt(), 0) }
                .width(segmentDp)
                .fillMaxHeight()
                .onSizeChanged { press.boxSize = it }
                .graphicsLayer {
                    if (!still) {
                        val squeeze = 1f - (1f - GlassPressDefaults.SQUEEZE) * press.amount
                        scaleX = squeeze
                        scaleY = squeeze
                    }
                }
                .clip(Capsule)
                .glassBase(hazeState = null, glass = glass, shape = Capsule),
        ) {
            GlassMaterial(hazeState = null, glass = glass, corner = Dp.Infinity, press = press)
            Box(Modifier.matchParentSize().border(1.5.dp, accent.copy(alpha = 0.85f), Capsule))
        }

        // The labels, each a tap target; a horizontal drag anywhere takes the
        // thumb along under the finger. The panel's own drag is vertical, so
        // whichever way the finger goes first has the gesture.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(count, segmentPx, stepPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            dragging = true
                            press.held = true
                            follow(at.x, at.y)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            follow(change.position.x, change.position.y)
                        },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            // The label over the thumb is the chosen one's, even mid-drag.
            val over = nearestSegment(position.value, count)
            options.forEachIndexed { index, label ->
                val lit = index == over
                val content by animateColorAsState(
                    if (lit) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    label = "segContent",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            // The thumb's slide is the answer to a tap.
                            indication = null,
                        ) { select(index) }
                        .semantics {
                            role = Role.Tab
                            this.selected = index == selectedIndex
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Space between segments, and so between the thumb's resting places. */
private val SegmentGap: Dp = 3.dp

/** The thumb's slide: settles quickly, with the slightest give. */
private val ThumbSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)

/** One segment's width in a capsule [widthPx] wide, [count] segments [gapPx] apart. */
internal fun segmentWidth(widthPx: Float, gapPx: Float, count: Int): Float =
    if (count <= 0) 0f else ((widthPx - gapPx * (count - 1)) / count).coerceAtLeast(0f)

/**
 * The thumb's position, in segments, centred under a finger at [x]: free
 * between the first and the last, and no further.
 */
internal fun thumbAt(x: Float, segmentPx: Float, stepPx: Float, count: Int): Float =
    if (count <= 1 || stepPx <= 0f) 0f
    else ((x - segmentPx / 2f) / stepPx).coerceIn(0f, (count - 1).toFloat())

/** The segment a thumb at [position] settles on. */
internal fun nearestSegment(position: Float, count: Int): Int =
    if (count <= 0) 0 else position.roundToInt().coerceIn(0, count - 1)

/**
 * `[ − ]  value  [ + ]` in one capsule: the number sits between the two
 * buttons that move it, instead of two wide pills that never showed it.
 * [compact] is the pitch row's, which shares its line with a label and reset.
 */
@Composable
internal fun SpeedStepper(
    value: String,
    accent: Color,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    compact: Boolean = false,
    /** Tap on the number — the BPM row measures the tempo again with it. */
    onValueClick: (() -> Unit)? = null,
    valueClickLabel: String? = null,
    /** Long-press on the number — the BPM row opens a keyboard with it. */
    onValueLongPress: (() -> Unit)? = null,
    valueLongPressLabel: String? = null,
) {
    // Read through updated state so the gesture detector, keyed once, always
    // calls the current lambdas: re-keying it on every recomposition would
    // cancel a long-press in progress whenever the panel redrew.
    val tap by rememberUpdatedState(onValueClick)
    val longPress by rememberUpdatedState(onValueLongPress)
    val interactive = onValueClick != null || onValueLongPress != null
    Row(
        modifier = modifier
            .height(SpeedControlHeight)
            .clip(Capsule)
            .background(tonalFill())
            .border(1.dp, tonalBorder(), Capsule)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepSign(Icons.Default.Remove, decrementLabel, accent, canDecrement, onDecrement)
        Text(
            text = value,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = (if (compact) Modifier.widthIn(min = 52.dp) else Modifier.weight(1f))
                .then(
                    if (interactive) Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tap?.invoke() },
                                onLongPress = { longPress?.invoke() },
                            )
                        }
                        .semantics {
                            onValueClick?.let { onClick(valueClickLabel) { it(); true } }
                            onValueLongPress?.let { onLongClick(valueLongPressLabel) { it(); true } }
                        }
                    else Modifier
                )
                .padding(horizontal = 6.dp),
        )
        StepSign(Icons.Default.Add, incrementLabel, accent, canIncrement, onIncrement)
    }
}

@Composable
private fun StepSign(
    icon: ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SpeedControlHeight - 8.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (enabled) 0.18f else 0.06f))
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (enabled) Modifier.bounceClick(scaleDown = 0.9f, onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
    }
}
