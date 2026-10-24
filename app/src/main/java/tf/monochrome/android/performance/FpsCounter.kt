package tf.monochrome.android.performance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Settings › System › Performance › FPS counter: the frames the window drew in
 * the last second, beside the display's refresh rate. Counted from the
 * window's frame metrics, so it is what reached the screen. In brackets, the
 * frames the app's own clock moved its animations on ([CappedFrameClock]): a
 * window drawing more than that is drawing for something outside it. An app
 * at rest draws nothing and reads near 0 (the counter's own update is one
 * frame a second). The visualizer's surface is
 * not the window's and keeps its own counter (Settings › Visualizer › Show
 * FPS).
 *
 * Drawn outside the CRT effect, so it stays legible over it, and it takes no
 * touches.
 */
@Composable
fun FpsCounter(
    /** The frame rate limit in force, shown beside the reading; 0 when there is none. */
    limit: Int,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val frames = remember { AtomicInteger() }
    var fps by remember { mutableIntStateOf(0) }
    var composed by remember { mutableIntStateOf(0) }
    var hz by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            // Frame metrics arrive on a thread of their own, never the main one.
            val thread = HandlerThread("FpsCounter").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> frames.incrementAndGet() }
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            onDispose {
                runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
                thread.quitSafely()
            }
        }
    }
    LaunchedEffect(Unit) {
        // The clock counts whether or not the counter is shown; start from now,
        // not from the last time it was.
        FrameRateLimit.composedFrames.set(0)
        while (true) {
            delay(1_000L)
            fps = frames.getAndSet(0)
            composed = FrameRateLimit.composedFrames.getAndSet(0)
            hz = view.display?.refreshRate?.roundToInt() ?: 0
        }
    }
    val text = if (limit > 0) {
        stringResource(R.string.fps_counter_reading_limited, fps, composed, hz, limit)
    } else {
        stringResource(R.string.fps_counter_reading, fps, composed, hz)
    }
    Text(
        text = text,
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        ),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
