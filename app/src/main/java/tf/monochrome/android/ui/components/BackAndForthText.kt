package tf.monochrome.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * One line of [text] that, when it is wider than its room, shows the rest of
 * itself and comes back: it rests at its start, slides until its end is in
 * view, rests there, and slides back to the start, again and again. Text that
 * fits stays still. The player's title used to end in "…", and a long remix
 * title lost the part that says which remix it is.
 *
 * Back and forth rather than round and round (as `basicMarquee` does): a
 * title wrapped into its own start reads as a different title.
 *
 * The slide is a layer translation read at draw time, so a moving title does
 * not recompose its screen every frame. A new [text] starts again from the
 * start, and so does a tap on it: back to the start, and the slide begins at
 * once rather than after the usual rest.
 */
@Composable
fun BackAndForthText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var room by remember { mutableIntStateOf(0) }
    var taps by remember(text) { mutableIntStateOf(0) }
    var width by remember(text) { mutableIntStateOf(0) }
    val offset = remember(text) { Animatable(0f) }
    val overflow = (width - room).coerceAtLeast(0)
    val pxPerSecond = LocalDensity.current.density * SLIDE_DP_PER_SECOND
    // Toward the end of the line: left in a left-to-right layout.
    val toward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f

    // "Disable animations": no loop. The title rests at its start, and each
    // tap jumps it to its end and back, so the rest of it is still in reach.
    val still = reduceMotion()

    LaunchedEffect(text, overflow, toward, taps, still) {
        offset.snapTo(0f)
        if (overflow <= 0 || room <= 0) return@LaunchedEffect
        if (still) {
            if (taps % 2 == 1) offset.snapTo(toward * overflow)
            return@LaunchedEffect
        }
        val slide = slideMillis(overflow, pxPerSecond)
        var rest = if (taps > 0) REST_AFTER_TAP_MS else REST_AT_START_MS
        while (true) {
            delay(rest)
            rest = REST_AT_START_MS
            offset.animateTo(toward * overflow, tween(slide, easing = LinearEasing))
            delay(REST_AT_END_MS)
            offset.animateTo(0f, tween(slide, easing = LinearEasing))
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { room = it.width }
            // A tap starts it over. No ripple: the title is not a button.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = null,
            ) { taps++ },
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .onSizeChanged { width = it.width }
                .graphicsLayer { translationX = offset.value },
        )
    }
}

/** How long a slide of [overflowPx] takes at [pxPerSecond]: never a jump. */
internal fun slideMillis(overflowPx: Int, pxPerSecond: Float): Int =
    if (overflowPx <= 0 || pxPerSecond <= 0f) 0
    else (overflowPx / pxPerSecond * 1000f).toInt().coerceAtLeast(MIN_SLIDE_MS)

/** Reading pace: slow enough to read while it moves. */
private const val SLIDE_DP_PER_SECOND = 30f
private const val REST_AT_START_MS = 2_000L
private const val REST_AT_END_MS = 1_500L
private const val REST_AFTER_TAP_MS = 300L
private const val MIN_SLIDE_MS = 600
