// SPDX-License-Identifier: GPL-3.0-or-later
// Crossfeed configuration page — enable switch, speaker-angle slider, and a
// live top-down visual of the virtual speaker pair around the listener.

package tf.monochrome.android.ui.crossfeed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CrossfeedScreen(
    effect: CrossfeedEffect,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val state by effect.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Text(
                    text = "Crossfeed",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speaker simulation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Blends a touch of each channel into the " +
                                    "opposite ear — delayed and darkened the way a " +
                                    "real room would — so headphones image like a " +
                                    "speaker pair in front of you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = effect::setEnabled,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    SpeakerStage(
                        angleDeg = state.speakerAngleDeg,
                        enabled = state.enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.45f)
                            .alpha(if (state.enabled) 1f else 0.45f),
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Speaker angle",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${state.speakerAngleDeg.roundToInt()}°",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = state.speakerAngleDeg,
                        onValueChange = effect::setSpeakerAngleDeg,
                        valueRange = CrossfeedState.MIN_ANGLE_DEG..CrossfeedState.MAX_ANGLE_DEG,
                        enabled = state.enabled,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "30° · narrow",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "180° · headphones",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Narrow angles pull the stereo image together in " +
                            "front of you (strongest crossfeed). At 180° the " +
                            "speakers sit at your ears — plain headphones, no " +
                            "crossfeed at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Top-down view of the listening setup: the listener's head at the sweet spot
 * with the two virtual speakers on an arc, ±angle/2 either side of straight
 * ahead. Redraws live as the slider moves.
 */
@Composable
private fun SpeakerStage(
    angleDeg: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val speakerBody = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.62f
        val radius = minOf(size.width * 0.36f, size.height * 0.52f)
        val half = Math.toRadians(angleDeg / 2.0)

        // Guide arc the speakers travel on (front semicircle, dashed).
        drawArc(
            color = outline.copy(alpha = 0.30f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            ),
        )

        // Speaker azimuths measured from straight ahead (up on the canvas).
        val azimuths = floatArrayOf(-half.toFloat(), half.toFloat())
        val positions = azimuths.map { az ->
            Offset(cx + radius * sin(az), cy - radius * cos(az))
        }

        // Sound paths from each speaker to the sweet spot.
        for (p in positions) {
            drawLine(
                color = accent.copy(alpha = if (enabled) 0.45f else 0.25f),
                start = p,
                end = Offset(cx, cy),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
            )
        }

        // Angle wedge at the head with the current spread.
        val wedgeR = radius * 0.38f
        drawArc(
            color = accent.copy(alpha = 0.9f),
            startAngle = -90f - angleDeg / 2f,
            sweepAngle = angleDeg,
            useCenter = false,
            topLeft = Offset(cx - wedgeR, cy - wedgeR),
            size = Size(wedgeR * 2f, wedgeR * 2f),
            style = Stroke(width = 2.dp.toPx()),
        )

        // Listener head: circle + ears + nose, facing up.
        val headR = radius * 0.16f
        drawCircle(color = surface, radius = headR, center = Offset(cx, cy))
        drawCircle(
            color = outline,
            radius = headR,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        // Ears
        for (side in intArrayOf(-1, 1)) {
            drawCircle(
                color = outline,
                radius = headR * 0.28f,
                center = Offset(cx + side * headR, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        // Nose — small triangle on the front of the head.
        val nose = Path().apply {
            moveTo(cx - headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx + headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx, cy - headR * 1.38f)
            close()
        }
        drawPath(nose, color = outline)

        // Speakers — rounded cabinet + driver, each rotated to face the head.
        positions.forEachIndexed { i, p ->
            drawSpeaker(
                at = p,
                facingDeg = Math.toDegrees(azimuths[i].toDouble()).toFloat(),
                scale = radius,
                body = speakerBody,
                cone = if (enabled) accent else outline,
            )
        }
    }
}

/**
 * One speaker cabinet at [at]. [facingDeg] is the speaker's azimuth from the
 * listener's front axis; the cabinet is drawn pointing down (toward the
 * listener when at 0°) and rotated by the azimuth so it always aims at the
 * sweet spot.
 */
private fun DrawScope.drawSpeaker(
    at: Offset,
    facingDeg: Float,
    scale: Float,
    body: Color,
    cone: Color,
) {
    val w = scale * 0.22f
    val h = scale * 0.30f
    withTransform({
        rotate(degrees = facingDeg, pivot = at)
    }) {
        drawRoundRect(
            color = body,
            topLeft = Offset(at.x - w / 2f, at.y - h),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.2f, w * 0.2f),
        )
        // Driver cone near the front (listener-facing) edge.
        drawCircle(
            color = cone,
            radius = w * 0.26f,
            center = Offset(at.x, at.y - h * 0.30f),
        )
        drawCircle(
            color = body.copy(alpha = 0.35f),
            radius = w * 0.12f,
            center = Offset(at.x, at.y - h * 0.75f),
        )
    }
}
