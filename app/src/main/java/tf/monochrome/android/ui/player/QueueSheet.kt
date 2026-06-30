package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()

    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIndices by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var menuTrackIndex by remember { mutableStateOf<Int?>(null) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val rowStepPx = with(LocalDensity.current) { QueueRowStep.toPx() }
    val selectedSet = selectedIndices.toSet()

    fun exitSelectionMode() {
        selectionMode = false
        selectedIndices = emptyList()
    }

    fun toggleSelected(index: Int) {
        selectedIndices = if (index in selectedSet) {
            selectedIndices.filterNot { it == index }
        } else {
            (selectedIndices + index).distinct()
        }
        if (selectedIndices.isEmpty()) selectionMode = false
    }

    fun startSelection(index: Int) {
        selectionMode = true
        selectedIndices = listOf(index)
    }

    fun deleteSelected() {
        playerViewModel.removeSelectedFromQueue(selectedIndices.toSet())
        exitSelectionMode()
    }

    fun moveDraggingItem(deltaY: Float) {
        val from = draggingIndex ?: return
        if (queue.size <= 1) return
        dragOffsetPx += deltaY
        val steps = (dragOffsetPx / rowStepPx).toInt()
        if (steps == 0) return

        val target = (from + steps).coerceIn(0, queue.lastIndex)
        if (target != from) {
            playerViewModel.moveQueueItem(from, target)
            draggingIndex = target
        }
        dragOffsetPx -= steps * rowStepPx
    }

    LaunchedEffect(queue.size) {
        selectedIndices = selectedIndices.filter { it in queue.indices }
        if (selectedIndices.isEmpty()) selectionMode = false
        menuTrackIndex = menuTrackIndex?.takeIf { it in queue.indices }
        draggingIndex = draggingIndex?.takeIf { it in queue.indices }
    }

    BackHandler(enabled = selectionMode) {
        exitSelectionMode()
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset queue?") },
            text = {
                Text("This will remove all upcoming tracks from the queue. The current track will keep playing.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playerViewModel.resetQueue()
                        exitSelectionMode()
                        showResetConfirm = false
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            QueueSheetHeader(
                queueSize = queue.size,
                selectionMode = selectionMode,
                selectedCount = selectedIndices.size,
                canReset = queue.size > 1 && currentIndex in queue.indices,
                canDeleteSelected = selectedIndices.isNotEmpty(),
                onResetClick = { showResetConfirm = true },
                onDeleteSelected = ::deleteSelected,
                onExitSelection = ::exitSelectionMode,
            )

            if (queue.isEmpty()) {
                Text(
                    text = "Queue is empty.\nPlay some music to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (currentTrack != null) {
                        item {
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }

                    itemsIndexed(queue, key = { index, track -> "${track.id}-$index" }) { index, track ->
                        val isCurrent = index == currentIndex
                        QueueTrackItem(
                            index = index,
                            track = track,
                            isCurrentTrack = isCurrent,
                            selectionMode = selectionMode,
                            selected = index in selectedSet,
                            menuExpanded = menuTrackIndex == index,
                            dragEnabled = !selectionMode && queue.size > 1,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(index)
                                } else {
                                    playerViewModel.skipToQueueIndex(index)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) menuTrackIndex = index
                            },
                            onDismissMenu = { menuTrackIndex = null },
                            onSelect = {
                                startSelection(index)
                                menuTrackIndex = null
                            },
                            onDelete = {
                                playerViewModel.removeFromQueue(index)
                                menuTrackIndex = null
                            },
                            onPlayNext = {
                                playerViewModel.playQueueItemNext(index)
                                menuTrackIndex = null
                            },
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                draggingIndex = null
                                dragOffsetPx = 0f
                            },
                            onDragDelta = ::moveDraggingItem,
                        )

                        if (isCurrent && index < queue.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Up Next",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSheetHeader(
    queueSize: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    canReset: Boolean,
    canDeleteSelected: Boolean,
    onResetClick: () -> Unit,
    onDeleteSelected: () -> Unit,
    onExitSelection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectionMode) {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, contentDescription = "Exit selection")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$queueSize tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onDeleteSelected,
                enabled = canDeleteSelected,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete")
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Queue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$queueSize tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onResetClick,
                enabled = canReset,
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset queue")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTrackItem(
    index: Int,
    track: Track,
    isCurrentTrack: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    menuExpanded: Boolean,
    dragEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPlayNext: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragDelta: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
            )
        } else {
            ReorderHandle(
                enabled = dragEnabled,
                onDragStart = onDragStart,
                onDragEnd = onDragEnd,
                onDragDelta = onDragDelta,
            )
        }

        if (isCurrentTrack) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).padding(8.dp),
            )
        } else {
            CoverImage(
                url = track.coverUrl,
                contentDescription = track.title,
                size = 40.dp,
                cornerRadius = 4.dp,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = track.formattedDuration,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        QueueTrackMenuAnchor(
            expanded = menuExpanded,
            isCurrentTrack = isCurrentTrack,
            onDismiss = onDismissMenu,
            onOpen = onLongClick,
            onPlayNext = onPlayNext,
            onSelect = onSelect,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun ReorderHandle(
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragDelta: (Float) -> Unit,
) {
    val dragModifier = if (enabled) {
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd,
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.y)
                },
            )
        }
    } else {
        Modifier
    }
    Icon(
        Icons.Default.DragHandle,
        contentDescription = "Reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = dragModifier.size(36.dp).padding(6.dp),
    )
}

@Composable
private fun QueueTrackMenuAnchor(
    expanded: Boolean,
    isCurrentTrack: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPlayNext: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Queue actions")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                enabled = !isCurrentTrack,
                onClick = onPlayNext,
            )
            DropdownMenuItem(
                text = { Text("Select") },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                onClick = onSelect,
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = onDelete,
            )
        }
    }
}

private val QueueRowStep = 64.dp
