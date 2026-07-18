package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.adjustableSemantics
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Scrubber plus elapsed / center-label / total time. The center label carries
 * the quality or queue-position context from the generated design.
 *
 * When the player glass is on with the "Glass progress bar" setting, the
 * scrubber is a thin liquid-glass tube (a thermometer) that fills up, with a
 * playhead dot that swells the tube into a smooth sine-wave bulge. Otherwise it
 * falls back to a plain Material slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerProgress(
    fraction: Float,
    elapsedLabel: String,
    totalLabel: String,
    centerLabel: String,
    accent: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalPlayerGlass.current
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else accent

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (glass.enabled && glass.progressGlass) {
            GlassProgressTube(
                fraction = fraction,
                tint = tint,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PlainSlider(fraction, tint, onSeek, onSeekFinished)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            if (centerLabel.isNotBlank()) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The liquid-glass "thermometer" scrubber: a thin tube whose vertical thickness
 * follows a raised-cosine (sine-wave) bump centred on the playhead, so the dot
 * reads as a smooth bulge in the tube. The played portion is filled with [tint];
 * the whole thing is handed to the [playerGlass] shader, which bevels the tube
 * and the bulge into refractive glass. Drag or tap to seek.
 */
@Composable
internal fun GlassProgressTube(
    fraction: Float,
    tint: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    // The position the finger is currently over, committed on release. Held in a
    // MutableState so the pointerInput(Unit) closures (created once) read/write
    // the live value instead of a stale captured fraction.
    var seekTarget by remember { mutableFloatStateOf(0f) }
    // The dot always shows a bulge; it swells further while dragging.
    val bulge by animateFloatAsState(
        targetValue = if (dragging) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "progressBulge",
    )
    val frac = fraction.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .adjustableSemantics(
                label = "Seek",
                value = frac,
                range = 0f..1f,
                stateText = { "${(it * 100).roundToInt()}%" },
                onValueChange = { onSeek(it); onSeekFinished(it) },
            )
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val f = (pos.x / size.width).coerceIn(0f, 1f)
                    onSeek(f)
                    onSeekFinished(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        dragging = true
                        val f = (pos.x / size.width).coerceIn(0f, 1f)
                        seekTarget = f
                        onSeek(f)
                    },
                    onDragEnd = { dragging = false; onSeekFinished(seekTarget) },
                    // Commit on cancel too — otherwise the parent stayed in
                    // seek mode and the bar/time froze at the last finger
                    // position until the next seek.
                    onDragCancel = { dragging = false; onSeekFinished(seekTarget) },
                    onHorizontalDrag = { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        seekTarget = f
                        onSeek(f)
                    },
                )
            }
            .playerGlass(tint = tint)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val w = size.width
        val cy = size.height / 2f
        val baseHalf = 2.6.dp.toPx()                          // thin tube half-thickness
        val bulgeHalf = 7.dp.toPx() + 5.dp.toPx() * bulge     // dot half-thickness
        val sigma = 26.dp.toPx()                              // bulge half-width
        val inset = bulgeHalf                                 // keep the dot on-screen
        val thumbX = inset + frac * (w - 2f * inset)

        // Raised-cosine (sine-wave) contour: fat at the dot, thin along the tube.
        fun halfAt(x: Float): Float {
            val d = abs(x - thumbX)
            val bump = if (d < sigma) 0.5f * (1f + cos(PI.toFloat() * d / sigma)) else 0f
            return baseHalf + (bulgeHalf - baseHalf) * bump
        }

        val path = Path()
        val step = 3f
        path.moveTo(0f, cy - halfAt(0f))
        var x = step
        while (x < w) { path.lineTo(x, cy - halfAt(x)); x += step }
        path.lineTo(w, cy - halfAt(w))
        x = w - step
        while (x > 0f) { path.lineTo(x, cy + halfAt(x)); x -= step }
        path.lineTo(0f, cy + halfAt(0f))
        path.close()

        // Inactive glass tube.
        drawPath(path, color = Color.White.copy(alpha = 0.22f))
        // Filled (played) portion up to the dot.
        clipRect(right = thumbX) { drawPath(path, color = tint) }
        // A defined dot core at the playhead so the bulge reads as the thumb.
        drawCircle(
            color = tint,
            radius = baseHalf + (bulgeHalf - baseHalf) * 0.6f,
            center = Offset(thumbX, cy),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainSlider(
    fraction: Float,
    tint: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    // Hold the live drag value: onValueChangeFinished(fraction) committed the
    // composition-captured (stale) fraction, so a tap-to-seek snapped back.
    var latestSeek by remember { mutableFloatStateOf(fraction) }
    val thumbSize by animateDpAsState(
        targetValue = if (dragged) 18.dp else PlayerDesignTokens.ProgressThumbSize,
        label = "progressThumb",
    )
    val colors = SliderDefaults.colors(
        thumbColor = tint,
        activeTrackColor = tint,
        inactiveTrackColor = Color.White.copy(alpha = 0.20f),
    )
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = { latestSeek = it; onSeek(it) },
        onValueChangeFinished = { onSeekFinished(latestSeek) },
        modifier = Modifier.fillMaxWidth(),
        interactionSource = interaction,
        colors = colors,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                thumbSize = DpSize(thumbSize, thumbSize),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(PlayerDesignTokens.ProgressHeight),
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}
