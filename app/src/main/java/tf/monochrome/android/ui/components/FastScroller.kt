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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * A draggable scrollbar down the right edge, for lists too long to flick.
 *
 * Call it inside the same [Box] as the list and hand it the list's state.
 * Fades in while the list moves or the thumb is held, out when idle.
 *
 * With a paged list the Pager wants `enablePlaceholders = true`, or
 * `totalItemsCount` is only the rows loaded so far and the thumb sizes itself
 * against a fraction of the list.
 *
 * Three things here are load-bearing for smoothness, and all three were wrong
 * in the first version of this file:
 *
 *  - The gesture is keyed on [Unit]. Keying `pointerInput` on the list metrics
 *    tears the detector down and back up whenever they change — which, while
 *    paging loads and `totalItemsCount` grows, is *during the drag*. The
 *    gesture was being cancelled under the finger.
 *  - The drag publishes a fraction; one long-lived effect does the scrolling.
 *    Launching a `scrollToItem` per drag event puts dozens of coroutines in a
 *    fight over the list's scroll mutex, and all but one lose.
 *  - The thumb's position is read inside `offset { }`, not in composition.
 *    `firstVisibleItemIndex` changes every frame of a scroll, so reading it
 *    during composition recomposes this on every one of them; read from the
 *    layout lambda it only re-lays-out, which is what a moving thumb needs.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbMin: Dp = 48.dp,
    width: Dp = 6.dp,
    touchWidth: Dp = 28.dp,
) {
    // Deliberately does NOT include firstVisibleItemIndex: these two settle
    // quickly and change rarely, so this derived value — and therefore this
    // composable — is stable while scrolling.
    val extent by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.size
        }
    }
    val (total, visible) = extent
    if (total == 0 || visible == 0 || visible >= total) return

    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(0f) }
    // Non-null only while the thumb is held; the value is where on the track.
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val alpha by animateFloatAsState(
        targetValue = if (dragFraction != null || state.isScrollInProgress) 1f else 0f,
        label = "fastScroller",
    )

    // One scroller, fed the newest fraction. collectLatest cancels the previous
    // jump the moment a newer one arrives, so a fast drag issues one scroll per
    // frame rather than queueing every sample the finger produced.
    LaunchedEffect(state) {
        snapshotFlow { dragFraction }.collectLatest { fraction ->
            if (fraction == null) return@collectLatest
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.scrollToItem((last * fraction).roundToInt().coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .pointerInput(Unit) {
                val track = { size.height.toFloat().coerceAtLeast(1f) }
                detectVerticalDragGestures(
                    onDragStart = { dragFraction = (it.y / track()).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.y / track()).coerceIn(0f, 1f)
                }
            }
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.TopEnd,
    ) {
        val thumbPx = (trackPx * visible / total)
            .coerceAtLeast(with(density) { thumbMin.toPx() })
            .coerceAtMost(trackPx)
        Box(
            Modifier
                .offset {
                    // Read in the layout phase, not composition — see above.
                    val last = (total - visible).coerceAtLeast(1)
                    val progress = (state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
                    IntOffset(0, ((trackPx - thumbPx) * progress).roundToInt())
                }
                .width(width)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
    }
}

/**
 * The same thumb over a [LazyVerticalGrid][androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * Counts in items, not rows, which is what the grid's own state reports — so on
 * a three-column grid the thumb measures a third of what it would on a list of
 * the same length. That is correct: both describe the same fraction of the
 * content being on screen.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    thumbMin: Dp = 48.dp,
    width: Dp = 6.dp,
    touchWidth: Dp = 28.dp,
) {
    val extent by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.size
        }
    }
    val (total, visible) = extent
    if (total == 0 || visible == 0 || visible >= total) return

    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(0f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val alpha by animateFloatAsState(
        targetValue = if (dragFraction != null || state.isScrollInProgress) 1f else 0f,
        label = "fastScrollerGrid",
    )

    LaunchedEffect(state) {
        snapshotFlow { dragFraction }.collectLatest { fraction ->
            if (fraction == null) return@collectLatest
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.scrollToItem((last * fraction).roundToInt().coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .pointerInput(Unit) {
                val track = { size.height.toFloat().coerceAtLeast(1f) }
                detectVerticalDragGestures(
                    onDragStart = { dragFraction = (it.y / track()).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.y / track()).coerceIn(0f, 1f)
                }
            }
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.TopEnd,
    ) {
        val thumbPx = (trackPx * visible / total)
            .coerceAtLeast(with(density) { thumbMin.toPx() })
            .coerceAtMost(trackPx)
        Box(
            Modifier
                .offset {
                    val last = (total - visible).coerceAtLeast(1)
                    val progress = (state.firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
                    IntOffset(0, ((trackPx - thumbPx) * progress).roundToInt())
                }
                .width(width)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
    }
}
