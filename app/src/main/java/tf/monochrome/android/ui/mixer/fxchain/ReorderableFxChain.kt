package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bounds of one reorderable card, in the LazyColumn's content-coordinate space.
 * [index] is the card's position within the visual chain (not the raw LazyColumn
 * item index, which also counts the IN/OUT caps and the add bar).
 */
data class ReorderItem(val index: Int, val top: Int, val size: Int) {
    val bottom: Int get() = top + size
}

/**
 * Pure geometry for drag-reordering: given the dragged card's current Y-center
 * and the bounds of every *other* card, return the index the dragged card should
 * move to, or null if it should stay put.
 *
 * A move triggers only when the center crosses fully into another card's span,
 * which naturally yields single-step swaps (up or down) and clamps at both ends
 * (dragging past the last card leaves the center beyond all spans → null).
 */
fun computeSwap(draggedCenterY: Float, others: List<ReorderItem>): Int? =
    others.firstOrNull { draggedCenterY >= it.top && draggedCenterY <= it.bottom }?.index

/**
 * Drag-reorder controller for the FX chain LazyColumn.
 *
 * Design (see plan): the visual chain is a frozen snapshot with stable [Long]
 * uid keys; reordering happens *within the snapshot* during the gesture and is
 * committed to the ViewModel exactly once at drag end via [onCommit]. Never
 * commit per-crossing — that would renumber native slots while captured card
 * callbacks still hold stale indices.
 */
class FxChainDragState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val indexOfKey: (Long) -> Int?,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onCommit: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Long?>(null)
        private set

    private var startIndex = -1
    private var initialOffset = 0
    private var draggedDelta by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = draggingKey != null

    /** Vertical translation to apply to the dragged card via graphicsLayer. */
    fun translationFor(key: Long): Float {
        if (key != draggingKey) return 0f
        val info = itemInfoFor(key) ?: return draggedDelta
        return (initialOffset + draggedDelta) - info.offset
    }

    private fun itemInfoFor(key: Long): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun onDragStart(key: Long) {
        val info = itemInfoFor(key) ?: return
        draggingKey = key
        startIndex = indexOfKey(key) ?: -1
        initialOffset = info.offset
        draggedDelta = 0f
    }

    fun onDrag(deltaY: Float) {
        val key = draggingKey ?: return
        draggedDelta += deltaY
        val dragged = itemInfoFor(key) ?: return
        val currentIndex = indexOfKey(key) ?: return
        val centerY = initialOffset + draggedDelta + dragged.size / 2f

        val others = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val k = info.key as? Long ?: return@mapNotNull null
            if (k == key) return@mapNotNull null
            val idx = indexOfKey(k) ?: return@mapNotNull null
            ReorderItem(idx, info.offset, info.size)
        }

        computeSwap(centerY, others)?.let { target ->
            if (target != currentIndex) onMove(currentIndex, target)
        }
        maybeAutoScroll(dragged)
    }

    fun onDragEnd() {
        val key = draggingKey
        if (key != null && startIndex >= 0) {
            val finalIndex = indexOfKey(key) ?: startIndex
            if (finalIndex != startIndex) onCommit(startIndex, finalIndex)
        }
        draggingKey = null
        draggedDelta = 0f
        startIndex = -1
    }

    private fun maybeAutoScroll(dragged: LazyListItemInfo) {
        val key = draggingKey ?: return
        val start = listState.layoutInfo.viewportStartOffset
        val end = listState.layoutInfo.viewportEndOffset
        val cardTop = dragged.offset + translationFor(key)
        val cardBottom = cardTop + dragged.size
        val edge = 96f
        val step = 28f
        val scrollBy = when {
            cardBottom > end - edge -> step
            cardTop < start + edge -> -step
            else -> 0f
        }
        if (scrollBy != 0f) scope.launch { listState.scrollBy(scrollBy) }
    }
}

@Composable
fun rememberFxChainDragState(
    listState: LazyListState,
    scope: CoroutineScope,
    indexOfKey: (Long) -> Int?,
    onMove: (from: Int, to: Int) -> Unit,
    onCommit: (from: Int, to: Int) -> Unit,
): FxChainDragState {
    val latestIndexOfKey by rememberUpdatedState(indexOfKey)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnCommit by rememberUpdatedState(onCommit)
    return remember(listState, scope) {
        FxChainDragState(
            listState = listState,
            scope = scope,
            indexOfKey = { latestIndexOfKey(it) },
            onMove = { from, to -> latestOnMove(from, to) },
            onCommit = { from, to -> latestOnCommit(from, to) },
        )
    }
}
