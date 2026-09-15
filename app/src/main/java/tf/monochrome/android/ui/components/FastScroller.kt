package tf.monochrome.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * A draggable scrollbar down the right edge, for lists too long to flick.
 *
 * Call it inside the same [Box] as the list and hand it the list's state. The
 * thumb appears while the list moves, lingers a moment so it can be caught, and
 * fades out.
 *
 * With a paged list the Pager wants `enablePlaceholders = true`, or
 * `totalItemsCount` is only the rows loaded so far and the thumb sizes itself
 * against a fraction of the list.
 *
 * Four things here are load-bearing for smoothness, and each was wrong once:
 *
 *  - **Only the thumb takes touches.** This used to be a 28dp-wide, full-height
 *    strip with the gesture on it, and it is the last child of the Box, so it
 *    hit-tested above the list: any drag that started within 28dp of the right
 *    edge was grabbed and turned into a jump-to-position, while the thumb was
 *    animated to alpha 0 and invisible. You flicked the list and the list
 *    teleported. The gesture is on the thumb, which is measured to nothing while
 *    it is invisible so that it cannot be hit at all.
 *  - **Nothing about the scroll is read during composition.** The extent used to
 *    be a `derivedStateOf` of `totalItemsCount to visibleItemsInfo.size`, and
 *    that second number really does oscillate — 12, 13, 12 — as partial rows
 *    enter and leave. Every oscillation was a new Pair, so the whole composable
 *    re-ran mid-scroll and the thumb, sized in composition, changed height on
 *    each one. Size and position are computed in the layout phase now; only a
 *    Boolean is derived for composition, and a Boolean is equal to itself frame
 *    after frame.
 *  - **The drag accumulates deltas, not positions.** The gesture is on the thumb
 *    and the thumb moves under the finger while it scrolls, so an absolute
 *    `change.position` would be measured against a moving origin and fight
 *    itself.
 *  - **The drag publishes a fraction; one long-lived effect does the scrolling.**
 *    Launching a `scrollToItem` per drag event puts dozens of coroutines in a
 *    fight over the list's scroll mutex, and all but one lose.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    thumbMin: Dp = 48.dp,
    width: Dp = 6.dp,
    touchWidth: Dp = 28.dp,
) {
    // A Boolean, deliberately: it is structurally equal to itself on every
    // frame of a scroll, so reading it in composition invalidates nothing.
    val enabled by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.size in 1 until info.totalItemsCount
        }
    }
    if (!enabled) return

    FastScrollerThumb(
        modifier = modifier,
        thumbMin = thumbMin,
        width = width,
        touchWidth = touchWidth,
        key = state,
        label = "fastScroller",
        isScrolling = { state.isScrollInProgress },
        extent = {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) 1f else info.visibleItemsInfo.size.toFloat() / total
        },
        progress = {
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            // The offset into the first row, or the thumb steps one row at a
            // time instead of gliding.
            val rowPx = info.visibleItemsInfo.firstOrNull()?.size ?: 0
            val within = if (rowPx > 0) state.firstVisibleItemScrollOffset.toFloat() / rowPx else 0f
            (state.firstVisibleItemIndex + within) / last
        },
        scrollTo = { fraction ->
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.scrollToItem((last * fraction).roundToInt().coerceAtLeast(0))
        },
    )
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
    val enabled by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.visibleItemsInfo.size in 1 until info.totalItemsCount
        }
    }
    if (!enabled) return

    FastScrollerThumb(
        modifier = modifier,
        thumbMin = thumbMin,
        width = width,
        touchWidth = touchWidth,
        key = state,
        label = "fastScrollerGrid",
        isScrolling = { state.isScrollInProgress },
        extent = {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) 1f else info.visibleItemsInfo.size.toFloat() / total
        },
        progress = {
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            val rowPx = info.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
            val within = if (rowPx > 0) state.firstVisibleItemScrollOffset.toFloat() / rowPx else 0f
            (state.firstVisibleItemIndex + within) / last
        },
        scrollTo = { fraction ->
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.scrollToItem((last * fraction).roundToInt().coerceAtLeast(0))
        },
    )
}

/**
 * The track, the thumb and the drag — everything neither list nor grid needs to
 * know about.
 *
 * [key] is the list's own state object. The lambdas below close over it, so
 * everything remembered here is keyed on it too: handed a different list at the
 * same call site, a stale closure would keep reporting the old one's scroll.
 *
 * The four lambdas are read from the layout phase, so they must read the list's
 * state directly rather than be handed a snapshot of it: that is what keeps the
 * thumb moving every frame without recomposing anything.
 *
 * [extent] is the fraction of the content on screen (the thumb's height);
 * [progress] is how far down it is (the thumb's position). Both 0..1.
 */
@Composable
private fun BoxScope.FastScrollerThumb(
    modifier: Modifier,
    thumbMin: Dp,
    width: Dp,
    touchWidth: Dp,
    key: Any,
    label: String,
    isScrolling: () -> Boolean,
    extent: () -> Float,
    progress: () -> Float,
    scrollTo: suspend (Float) -> Unit,
) {
    val density = LocalDensity.current
    val thumbMinPx = with(density) { thumbMin.toPx() }

    var trackPx by remember(key) { mutableFloatStateOf(0f) }
    // Non-null only while the thumb is held; the value is where on the track.
    var dragFraction by remember(key) { mutableStateOf<Float?>(null) }
    // Where the top of the thumb is being dragged to, in pixels down the track.
    var dragTopPx by remember(key) { mutableFloatStateOf(0f) }

    // Visible while the list moves, and for a moment afterwards — long enough
    // to reach for. An always-on scrollbar would be back to covering the right
    // edge of an idle list.
    var lingering by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        snapshotFlow { isScrolling() }.collectLatest { scrolling ->
            if (scrolling) {
                lingering = true
            } else {
                delay(LINGER_MS)
                lingering = false
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (dragFraction != null || lingering) 1f else 0f,
        label = label,
    )

    // One scroller, fed the newest fraction. collectLatest cancels the previous
    // jump the moment a newer one arrives, so a fast drag issues one scroll per
    // frame rather than queueing every sample the finger produced.
    LaunchedEffect(key) {
        snapshotFlow { dragFraction }.collectLatest { fraction ->
            if (fraction != null) scrollTo(fraction)
        }
    }

    fun thumbPx(track: Float) = (track * extent()).coerceIn(thumbMinPx.coerceAtMost(track), track)
    fun thumbTopPx(track: Float) = (track - thumbPx(track)) * progress().coerceIn(0f, 1f)

    Box(
        // No pointer input on the track. It spans the full height of the list,
        // and anything that consumes here is taking the list's flicks.
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                // Position and size, both in the layout phase — reading the
                // list's scroll state here re-lays-out the thumb without
                // recomposing anything, which is what a moving thumb needs.
                .offset { IntOffset(0, thumbTopPx(trackPx).roundToInt()) }
                .layout { measurable, constraints ->
                    // Measured to nothing while invisible, so an idle scroller
                    // has no bounds to hit and the touch reaches the list.
                    val height = if (alpha <= 0f) 0 else thumbPx(trackPx).roundToInt()
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = height, maxHeight = height)
                    )
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
                .width(touchWidth)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragTopPx = thumbTopPx(trackPx) },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, dragAmount ->
                        change.consume()
                        // Deltas, not change.position: the thumb is what is
                        // being dragged and it moves as the list scrolls, so an
                        // absolute position would be measured against a moving
                        // origin.
                        val travel = (trackPx - thumbPx(trackPx)).coerceAtLeast(1f)
                        dragTopPx = (dragTopPx + dragAmount).coerceIn(0f, travel)
                        dragFraction = dragTopPx / travel
                    }
                }
                .graphicsLayer { this.alpha = alpha },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(width)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )
        }
    }
}

/** How long the thumb stays up after the list stops, in milliseconds. */
private const val LINGER_MS = 1200L
