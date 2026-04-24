package tf.monochrome.android.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import tf.monochrome.android.domain.model.VisualizerEnginePhase
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.visualizer.ProjectMAudioBus
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.visualizer.ProjectMRendererView

private val VisualizerInk = Color(0xFF060913)
private val VisualizerBlue = Color(0xFF53B7FF)
private val VisualizerMint = Color(0xFF6EF0C2)
private val VisualizerRose = Color(0xFFFF6E9A)
private val VisualizerGold = Color(0xFFFFC857)

@Composable
fun VisualizerComponent(
    isPlaying: Boolean,
    sensitivity: Int,
    brightness: Int,
    modifier: Modifier = Modifier,
    engineStatus: VisualizerEngineStatus,
    engineEnabled: Boolean,
    showFps: Boolean = false,
    isFullscreen: Boolean = false,
    touchWaveformEnabled: Boolean = true,
    repository: ProjectMEngineRepository? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "projectm-shell")
    val travel by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 5200 else 9400,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "travel"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 1800 else 3200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val intensity = (sensitivity / 100f).coerceIn(0.18f, 1f)
    val alpha = (brightness / 100f).coerceIn(0.25f, 1f)
    
    val currentFps by repository?.currentFps?.collectAsState(initial = 0) ?: remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .clip(if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(28.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        VisualizerInk,
                        Color(0xFF091525),
                        Color(0xFF151024)
                    )
                )
            )
    ) {
        if (engineEnabled && engineStatus.isNativeReady && repository != null) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
                factory = { context ->
                    ProjectMRendererView(context, repository).apply {
                        updatePlayback(isPlaying)
                    }
                },
                update = { view ->
                    view.updatePlayback(isPlaying)
                }
            )
            if (touchWaveformEnabled) {
                TouchWaveformOverlay(
                    audioBus = repository.audioBus,
                    alpha = alpha,
                    travel = travel
                )
            }
        } else {
            AmbientGlowLayer(
                travel = travel,
                pulse = pulse,
                alpha = alpha
            )
            SpectrumWaveLayer(
                isPlaying = isPlaying,
                intensity = intensity,
                alpha = alpha,
                travel = travel
            )
        }
        ProjectMStatusOverlay(
            engineStatus = engineStatus,
            showFps = showFps,
            currentFps = currentFps,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        if (!engineStatus.isNativeReady) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .liquidGlass(
                        shape = RoundedCornerShape(18.dp),
                        tintAlpha = 0.15f,
                        borderAlpha = 0.10f
                    ),
                shape = RoundedCornerShape(18.dp),
                color = Color.Transparent,
                contentColor = Color.White
            ) {
                Text(
                    text = engineStatus.message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AmbientGlowLayer(
    travel: Float,
    pulse: Float,
    alpha: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    VisualizerBlue.copy(alpha = 0.26f * alpha),
                    Color.Transparent
                )
            ),
            radius = w * 0.42f * pulse,
            center = androidx.compose.ui.geometry.Offset(
                x = w * (0.18f + 0.14f * travel),
                y = h * 0.28f
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    VisualizerRose.copy(alpha = 0.22f * alpha),
                    Color.Transparent
                )
            ),
            radius = w * 0.36f * (2f - pulse),
            center = androidx.compose.ui.geometry.Offset(
                x = w * (0.78f - 0.12f * travel),
                y = h * 0.24f
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    VisualizerMint.copy(alpha = 0.16f * alpha),
                    Color.Transparent
                )
            ),
            radius = w * 0.48f,
            center = androidx.compose.ui.geometry.Offset(
                x = w * 0.52f,
                y = h * 0.88f
            )
        )
    }
}

@Composable
private fun SpectrumWaveLayer(
    isPlaying: Boolean,
    intensity: Float,
    alpha: Float,
    travel: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum-lines")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 1700 else 5200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height * 0.56f
        val lineCount = 4

        repeat(lineCount) { index ->
            val progress = index / (lineCount - 1).toFloat()
            val amplitude = height * (0.06f + (0.045f * index)) * intensity
            val baseline = centerY + ((index - 1.5f) * 26.dp.toPx())
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, baseline)
                val steps = 64
                for (step in 1..steps) {
                    val x = width * (step / steps.toFloat())
                    val wave = sin((phase * (1f + progress)) + (step * 0.24f) + (travel * 3f))
                    val shimmer = sin((phase * 0.7f) + (step * 0.11f) + index)
                    val y = baseline + (wave * amplitude) + (shimmer * amplitude * 0.24f)
                    lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        VisualizerBlue.copy(alpha = 0.12f * alpha),
                        VisualizerMint.copy(alpha = (0.32f + progress * 0.08f) * alpha),
                        VisualizerGold.copy(alpha = (0.3f - progress * 0.06f) * alpha),
                        VisualizerRose.copy(alpha = 0.18f * alpha)
                    )
                ),
                style = Stroke(
                    width = (1.8f + index).dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.cornerPathEffect(18f)
                )
            )
        }

        val barCount = 28
        val gap = 7.dp.toPx()
        val availableWidth = width - gap * (barCount - 1)
        val barWidth = availableWidth / barCount
        for (bar in 0 until barCount) {
            val progress = bar / (barCount - 1).toFloat()
            val envelope = 1f - kotlin.math.abs(progress - 0.5f) * 1.35f
            val wave = sin(phase + (bar * 0.48f) + (travel * 6f))
            val heightFactor = if (isPlaying) 0.38f + (wave + 1f) * 0.31f else 0.22f
            val barHeight = (height * heightFactor * envelope.coerceAtLeast(0.18f) * intensity)
                .coerceAtLeast(14.dp.toPx())
            val left = bar * (barWidth + gap)
            val top = height - barHeight - 22.dp.toPx()

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        VisualizerRose.copy(alpha = 0.18f * alpha),
                        VisualizerBlue.copy(alpha = 0.72f * alpha),
                        VisualizerMint.copy(alpha = 0.94f * alpha)
                    )
                ),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth, barWidth)
            )
        }
    }
}

@Composable
private fun ProjectMStatusOverlay(
    engineStatus: VisualizerEngineStatus,
    modifier: Modifier = Modifier,
    showFps: Boolean = false,
    currentFps: Int = 0,
) {
    Surface(
        modifier = modifier
            .padding(16.dp)
            .liquidGlass(
                shape = RoundedCornerShape(999.dp),
                tintAlpha = 0.12f,
                borderAlpha = 0.10f
            ),
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = 0.92f }
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when (engineStatus.phase) {
                            VisualizerEnginePhase.ACTIVE -> VisualizerMint
                            VisualizerEnginePhase.READY -> VisualizerBlue
                            VisualizerEnginePhase.INSTALLING -> VisualizerGold
                            VisualizerEnginePhase.ERROR -> VisualizerRose
                            VisualizerEnginePhase.FALLBACK -> VisualizerGold
                        }
                    )
            )
            Text(
                text = engineStatus.badge,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (showFps && engineStatus.isNativeReady) {
                Text(
                    text = "• $currentFps FPS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ─── Touch waveform overlay ────────────────────────────────────────

@Composable
private fun TouchWaveformOverlay(
    audioBus: ProjectMAudioBus,
    alpha: Float,
    travel: Float,
    modifier: Modifier = Modifier
) {
    val touchPoints = remember { mutableStateMapOf<Long, Offset>() }

    DisposableEffect(audioBus) {
        audioBus.acquire()
        onDispose { audioBus.release() }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = mutableMapOf<Long, Offset>()
                        for (change in event.changes) {
                            if (change.pressed) {
                                active[change.id.value] = change.position
                            }
                            change.consume()
                        }
                        touchPoints.clear()
                        touchPoints.putAll(active)
                    }
                }
            }
    ) {
        // Read travel to force continuous redraws while fingers are down
        @Suppress("UNUSED_EXPRESSION")
        travel

        if (touchPoints.isEmpty()) return@Canvas
        val samples = audioBus.peekSamples() ?: return@Canvas
        val points = touchPoints.values.toList()

        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                drawLinearWaveform(points[i], points[i + 1], samples, alpha)
            }
            if (points.size > 2) {
                drawLinearWaveform(points.last(), points.first(), samples, alpha)
            }
        } else {
            drawRadialWaveform(points[0], samples, alpha)
        }
    }
}

/** Draws an audio waveform stretched between two finger positions. */
private fun DrawScope.drawLinearWaveform(
    from: Offset,
    to: Offset,
    samples: FloatArray,
    alpha: Float
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dist = sqrt(dx * dx + dy * dy)
    if (dist < 2f) return

    // Perpendicular direction for the wave displacement
    val nx = -dy / dist
    val ny = dx / dist

    val path = Path()
    val numPoints = 160
    val waveHeight = dist * 0.14f

    for (i in 0..numPoints) {
        val t = i / numPoints.toFloat()
        val sIdx = ((t * (samples.size - 1)).toInt()).coerceIn(0, samples.size - 1)
        val s = samples[sIdx]
        // Taper to zero at the endpoints so the wave meets the fingers
        val envelope = sin(t * PI.toFloat())
        val amp = s * waveHeight * envelope

        val x = from.x + dx * t + nx * amp
        val y = from.y + dy * t + ny * amp

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // Glow layer
    drawPath(
        path = path,
        color = VisualizerMint.copy(alpha = 0.22f * alpha),
        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
    )
    // Main line
    drawPath(
        path = path,
        color = VisualizerMint.copy(alpha = 0.88f * alpha),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
}

/** Draws a circular oscilloscope waveform around a single finger. */
private fun DrawScope.drawRadialWaveform(
    center: Offset,
    samples: FloatArray,
    alpha: Float
) {
    val path = Path()
    val numPoints = 160
    val baseRadius = 38.dp.toPx()
    val waveAmp = 28.dp.toPx()

    for (i in 0..numPoints) {
        val t = i / numPoints.toFloat()
        val angle = t * 2f * PI.toFloat()
        val sIdx = ((t * (samples.size - 1)).toInt()).coerceIn(0, samples.size - 1)
        val radius = baseRadius + samples[sIdx] * waveAmp

        val x = center.x + cos(angle) * radius
        val y = center.y + sin(angle) * radius

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(
        path = path,
        color = VisualizerBlue.copy(alpha = 0.22f * alpha),
        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
    )
    drawPath(
        path = path,
        color = VisualizerBlue.copy(alpha = 0.88f * alpha),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )
}
