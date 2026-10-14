// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper

/**
 * StepMania's life bar. It fills in the player's blue, turns red and throbs
 * below the danger line, and shimmers through the colours when it is full,
 * as the theme's "hot" bar does. The fill eases to each new level rather than
 * jumping, which is the bar's whole animation.
 */
@Composable
fun GlyphLifeBar(life: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(
        targetValue = life.coerceIn(0f, 1f),
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "lifeBarFill",
    )
    val transition = rememberInfiniteTransition(label = "lifeBar")
    val throb by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(320), RepeatMode.Reverse),
        label = "lifeBarDanger",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "lifeBarHot",
    )
    val danger = life < StepManiaScoreKeeper.DANGER_LIFE
    val hot = life >= 1f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics { contentDescription = "Life ${(life * 100).roundToInt()} percent" },
    ) {
        val corner = CornerRadius(size.height / 3f)
        drawRoundRect(color = Color.Black.copy(alpha = 0.55f), cornerRadius = corner)
        val width = size.width * shown
        if (width > 0f) {
            val brush = when {
                danger -> Brush.horizontalGradient(
                    listOf(DangerRed.copy(alpha = throb), DangerRed.copy(alpha = throb * 0.7f)),
                )
                hot -> {
                    // The colours slide along the bar, a flat sweep, not a turn.
                    val shift = size.width * shimmer
                    Brush.linearGradient(
                        colors = HotColours,
                        start = Offset(shift - size.width, 0f),
                        end = Offset(shift, 0f),
                        tileMode = TileMode.Repeated,
                    )
                }
                else -> Brush.horizontalGradient(listOf(PlayerColour.copy(alpha = 0.75f), PlayerColour))
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

private val DangerRed = Color(0xFFE5383B)
private val HotColours = listOf(
    Color(0xFFFF5F6D), Color(0xFFFFC371), Color(0xFFA0FF8B), Color(0xFF6BD6FF), Color(0xFFC08BFF), Color(0xFFFF5F6D),
)
