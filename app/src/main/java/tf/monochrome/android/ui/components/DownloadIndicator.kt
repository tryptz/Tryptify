package tf.monochrome.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.data.downloads.TrackDownloadState

@Composable
fun DownloadIndicator(
    state: TrackDownloadState,
    modifier: Modifier = Modifier,
    size: Float = 20f,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    when (state.status) {
        DownloadStatus.IDLE, DownloadStatus.FAILED -> return
        DownloadStatus.QUEUED -> QueuedIndicator(modifier, size, accentColor)
        DownloadStatus.DOWNLOADING -> DownloadingIndicator(state.progress, modifier, size, accentColor)
        DownloadStatus.COMPLETED -> CompletedIndicator(modifier, size, accentColor)
    }
}

@Composable
private fun QueuedIndicator(
    modifier: Modifier,
    size: Float,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "queued")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "queuedPulse"
    )

    Canvas(modifier = modifier.size(size.dp)) {
        val strokeWidth = size * 0.1f
        val padding = strokeWidth
        drawArc(
            color = accentColor.copy(alpha = alpha),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(padding, padding),
            size = Size(this.size.width - padding * 2, this.size.height - padding * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawDownArrow(accentColor.copy(alpha = alpha), this.size)
    }
}

@Composable
private fun DownloadingIndicator(
    progress: Float,
    modifier: Modifier,
    size: Float,
    accentColor: Color
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "downloadProgress"
    )

    // Subtle glow pulse while downloading
    val infiniteTransition = rememberInfiniteTransition(label = "downloading")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "downloadGlow"
    )

    Canvas(modifier = modifier.size(size.dp)) {
        val strokeWidth = size * 0.12f
        val padding = strokeWidth
        val arcSize = Size(this.size.width - padding * 2, this.size.height - padding * 2)
        val arcOffset = Offset(padding, padding)

        // Background glow circle
        drawCircle(
            color = accentColor.copy(alpha = glowAlpha),
            radius = this.size.width / 2f
        )

        // Background track
        drawArc(
            color = accentColor.copy(alpha = 0.15f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = arcOffset,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Progress arc
        drawArc(
            color = accentColor,
            startAngle = -90f,
            sweepAngle = animatedProgress * 360f,
            useCenter = false,
            topLeft = arcOffset,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Arrow icon in center
        drawDownArrow(accentColor, this.size)
    }
}

@Composable
private fun CompletedIndicator(
    modifier: Modifier,
    size: Float,
    accentColor: Color
) {
    // Pop-in scale animation
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "completedScale"
    )

    Canvas(modifier = modifier.size(size.dp)) {
        val scaledSize = this.size

        // Filled circle
        drawCircle(
            color = accentColor.copy(alpha = 0.2f),
            radius = (scaledSize.width / 2f) * scale
        )

        // Outer ring
        val strokeWidth = scaledSize.width * 0.1f
        val padding = strokeWidth
        drawArc(
            color = accentColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(padding, padding),
            size = Size(scaledSize.width - padding * 2, scaledSize.height - padding * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Check mark
        drawCheckMark(accentColor, scaledSize, scale)
    }
}

private fun DrawScope.drawDownArrow(color: Color, canvasSize: Size) {
    val cx = canvasSize.width / 2f
    val cy = canvasSize.height / 2f
    val arrowSize = canvasSize.width * 0.22f
    val strokeWidth = canvasSize.width * 0.09f

    val path = Path().apply {
        // Vertical line
        moveTo(cx, cy - arrowSize)
        lineTo(cx, cy + arrowSize * 0.5f)
        // Left wing
        moveTo(cx - arrowSize * 0.6f, cy)
        lineTo(cx, cy + arrowSize * 0.5f)
        // Right wing
        lineTo(cx + arrowSize * 0.6f, cy)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

private fun DrawScope.drawCheckMark(color: Color, canvasSize: Size, scale: Float) {
    val cx = canvasSize.width / 2f
    val cy = canvasSize.height / 2f
    val checkSize = canvasSize.width * 0.2f * scale
    val strokeWidth = canvasSize.width * 0.1f

    val path = Path().apply {
        moveTo(cx - checkSize, cy)
        lineTo(cx - checkSize * 0.2f, cy + checkSize * 0.7f)
        lineTo(cx + checkSize, cy - checkSize * 0.5f)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}
