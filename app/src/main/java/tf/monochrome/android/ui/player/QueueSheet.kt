package tf.monochrome.android.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.roundToInt

private val QueueRowHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex by playerViewModel.currentIndex.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isRadioActive by playerViewModel.isRadioActive.collectAsState()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsState()
    val radioStatusMessage by playerViewModel.radioStatusMessage.collectAsState()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // Drag-to-reorder: the handle accumulates a vertical offset and the move
    // is committed once on release. Rows keep stable bindings during the
    // gesture, which keeps the pointerInput lambda alive for its duration.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

    fun exitSelection() {
        selectionMode = false
        selectedIndices = emptySet()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Queue",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${queue.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Radio: seeds from the current track, keeps the tail topped up.
                TextButton(
                    onClick = {
                        if (isRadioActive) playerViewModel.stopRadio()
                        else playerViewModel.startRadio()
                    },
                    enabled = currentTrack != null
                ) {
                    if (isRadioGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isRadioActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRadioActive) "Radio on" else "Radio")
                }

                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = queue.size > 1
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }

            radioStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedIndices.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { exitSelection() }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            playerViewModel.removeSelectedFromQueue(selectedIndices)
                            exitSelection()
                        },
                        enabled = selectedIndices.isNotEmpty()
                    ) {
                        Text("Delete")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (queue.isEmpty()) {
                Text(
                    text = "Queue is empty.\nPlay some music to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Now Playing header
                    if (currentTrack != null) {
                        item {
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    itemsIndexed(queue) { index, track ->
                        val isCurrent = index == currentIndex
                        val isDragging = index == draggingIndex

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                }
                        ) {
                            QueueTrackItem(
                                track = track,
                                isCurrentTrack = isCurrent,
                                selectionMode = selectionMode,
                                isSelected = index in selectedIndices,
                                isDragging = isDragging,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIndices =
                                            if (index in selectedIndices) selectedIndices - index
                                            else selectedIndices + index
                                        if (selectedIndices.isEmpty()) selectionMode = false
                                    } else {
                                        playerViewModel.skipToQueueIndex(index)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) menuIndex = index
                                },
                                dragHandleModifier = Modifier.pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDragEnd = {
                                            val from = draggingIndex
                                            if (from != null) {
                                                val target = (from + (dragOffsetY / rowHeightPx).roundToInt())
                                                    .coerceIn(0, queue.lastIndex)
                                                if (target != from) {
                                                    playerViewModel.moveQueueItem(from, target)
                                                }
                                            }
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                        }
                                    )
                                }
                            )

                            QueueTrackMenu(
                                expanded = menuIndex == index,
                                onDismiss = { menuIndex = null },
                                onPlayNext = { playerViewModel.playQueueItemNext(index) },
                                onStartRadio = { playerViewModel.startRadioFrom(track) },
                                onSelect = {
                                    selectionMode = true
                                    selectedIndices = setOf(index)
                                },
                                onDelete = { playerViewModel.removeFromQueue(index) }
                            )
                        }

                        // Divider after current track
                        if (isCurrent && index < queue.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Up Next",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
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
                        exitSelection()
                        showResetConfirm = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun QueueTrackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onStartRadio: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Play next") },
            onClick = {
                onDismiss()
                onPlayNext()
            }
        )
        DropdownMenuItem(
            text = { Text("Start radio from this song") },
            onClick = {
                onDismiss()
                onStartRadio()
            }
        )
        DropdownMenuItem(
            text = { Text("Select") },
            onClick = {
                onDismiss()
                onSelect()
            }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTrackItem(
    track: Track,
    isCurrentTrack: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandleModifier: Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .graphicsLayer { alpha = if (isDragging) 0.85f else 1f }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            selectionMode -> {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp)
                )
            }
            isCurrentTrack -> {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Now playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp).padding(8.dp)
                )
            }
            else -> {
                CoverImage(
                    url = track.coverUrl,
                    contentDescription = track.title,
                    size = 40.dp,
                    cornerRadius = 4.dp
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.displayArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = track.formattedDuration,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 24dp glyph, but the drag pointer node wraps the 48dp minimum
            // touch target — this is the sheet's only reorder affordance.
            modifier = dragHandleModifier
                .minimumInteractiveComponentSize()
                .padding(start = 12.dp)
                .size(24.dp)
        )
    }
}
