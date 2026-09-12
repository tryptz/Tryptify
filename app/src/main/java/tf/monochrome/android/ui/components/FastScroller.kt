package tf.monochrome.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A draggable scrollbar down the right edge, for lists too long to flick.
 *
 * Call it inside the same [Box] as the list. Fades in while the list moves or
 * the thumb is held, out when idle.
 *
 * With a paged list the Pager must have `enablePlaceholders = true`, or
 * `totalItemsCount` is only the rows loaded so far and the thumb reports the
 * wrong size and crawls instead of jumping.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbMin: Dp = 48.dp,
    width: Dp = 6.dp,
    touchWidth: Dp = 28.dp,
) {
    // layoutInfo changes on every frame of a scroll. derivedStateOf keeps that
    // off the composition — without it this recomposes ~120 times a second on
    // the very lists it exists to help.
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            Metrics(info.totalItemsCount, info.visibleItemsInfo.size, state.firstVisibleItemIndex)
        }
    }
    val scrolling = state.isScrollInProgress
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (dragging || scrolling) 1f else 0f,
        label = "fastScroller",
    )

    // Composed even at zero alpha so it can fade out rather than vanish; the
    // early return is only for lists that do not scroll at all.
    val (total, visible, first) = metrics
    if (total == 0 || visible == 0 || visible >= total) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(0f) }
    val lastIndex = (total - visible).coerceAtLeast(1)

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .pointerInput(total, visible, trackPx) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    if (trackPx <= 0f) return@detectVerticalDragGestures
                    val fraction = (change.position.y / trackPx).coerceIn(0f, 1f)
                    // scrollToItem, not animateScrollToItem: the thumb should
                    // track the finger, and an animation per drag event would
                    // queue up behind itself.
                    scope.launch { state.scrollToItem((lastIndex * fraction).roundToInt()) }
                }
            }
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.TopEnd,
    ) {
        val thumbPx = (trackPx * visible / total)
            .coerceAtLeast(with(density) { thumbMin.toPx() })
            .coerceAtMost(trackPx)
        val y = (trackPx - thumbPx) * (first.toFloat() / lastIndex).coerceIn(0f, 1f)
        Box(
            Modifier
                .offset { IntOffset(0, y.roundToInt()) }
                .width(width)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
    }
}

/** Destructured above; a class rather than Triple so the fields have names. */
private data class Metrics(val total: Int, val visible: Int, val first: Int)
