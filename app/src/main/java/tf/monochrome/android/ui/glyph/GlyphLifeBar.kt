// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper

/**
 * StepMania's life bar. It fills in the player's blue, turns red and throbs
 * below the danger line, and shimmers through the colours when it is full,
 * as the theme's "hot" bar does. The fill eases to each new level rather than
 * jumping, which is the bar's whole animation.
 *
 * [scale] is how much of [life] is drawn, read as the bar draws: the run's
 * intro fills it from empty without recomposing anything. The fill eases to
 * the life times the scale, as it did when the intro was multiplied in
 * before the call, so the intro's fill trails its curve as it always has. The throb and the
 * shimmer run only while the bar is in danger or full; anywhere between, the
 * bar is still, and their frame clock no longer ran under it all game long.
 */
@Composable
fun GlyphLifeBar(life: Float, modifier: Modifier = Modifier, scale: () -> Float = { 1f }) {
    val currentScale by rememberUpdatedState(scale)
    val currentLife by rememberUpdatedState(life)
    // What animateFloatAsState was (its 0.01 threshold, the same tween), fed
    // from a snapshotFlow so a frame of the intro is a new target, not a
    // recomposition.
    val fill = remember {
        Animatable(Snapshot.withoutReadObservation { (life * scale()).coerceIn(0f, 1f) }, 0.01f)
    }
    LaunchedEffect(fill) {
        snapshotFlow { (currentLife * currentScale()).coerceIn(0f, 1f) }.collect { next ->
            launch { if (next != fill.targetValue) fill.animateTo(next, tween(140, easing = FastOutSlowInEasing)) }
        }
    }
    // Only a change of the answer recomposes, not each frame of the intro.
    val moving by remember(life) {
        derivedStateOf {
            val level = life * currentScale()
            level < StepManiaScoreKeeper.DANGER_LIFE || level >= 1f
        }
    }
    val pulse = if (moving) rememberLifeBarPulse() else null

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics { contentDescription = "Life ${(life * 100).roundToInt()} percent" },
    ) {
        val drawnScale = currentScale()
        val level = life * drawnScale
        val danger = level < StepManiaScoreKeeper.DANGER_LIFE
        val hot = level >= 1f
        val corner = CornerRadius(size.height / 3f)
        drawRoundRect(color = Color.Black.copy(alpha = 0.55f), cornerRadius = corner)
        val width = size.width * fill.value
        if (width > 0f) {
            val brush = when {
                danger -> {
                    val throb = pulse?.throb?.value ?: THROB_LOW
                    Brush.horizontalGradient(
                        listOf(DangerRed.copy(alpha = throb), DangerRed.copy(alpha = throb * 0.7f)),
                    )
                }
                hot -> {
                    // The colours slide along the bar, a flat sweep, not a turn.
                    val shift = size.width * (pulse?.shimmer?.value ?: 0f)
                    Brush.linearGradient(
                        colors = HotColours,
                        start = Offset(shift - size.width, 0f),
                        end = Offset(shift, 0f),
                        tileMode = TileMode.Repeated,
                    )
                }
                else -> PlayerFill
            }
            drawRoundRect(brush = brush, size = Size(width, size.height), cornerRadius = corner)
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.35f),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** The danger throb and the full bar's shimmer, read while drawing. */
private class LifeBarPulse(val throb: State<Float>, val shimmer: State<Float>)

@Composable
private fun rememberLifeBarPulse(): LifeBarPulse {
    val transition = rememberInfiniteTransition(label = "lifeBar")
    val throb = transition.animateFloat(
        initialValue = THROB_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(320), RepeatMode.Reverse),
        label = "lifeBarDanger",
    )
    val shimmer = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "lifeBarHot",
    )
    return remember(throb, shimmer) { LifeBarPulse(throb, shimmer) }
}

/** The throb's dimmest. */
private const val THROB_LOW = 0.55f

private val DangerRed = Color(0xFFE5383B)
private val HotColours = listOf(
    Color(0xFFFF5F6D), Color(0xFFFFC371), Color(0xFFA0FF8B), Color(0xFF6BD6FF), Color(0xFFC08BFF), Color(0xFFFF5F6D),
)

/** The bar's own blue, the same at every level: made once, not on every draw. */
private val PlayerFill = Brush.horizontalGradient(listOf(StepPlayerColour.copy(alpha = 0.75f), StepPlayerColour))
