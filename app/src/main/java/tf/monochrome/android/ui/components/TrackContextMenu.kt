package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.theme.MonoDimens

data class ContextAction(
    val icon: ImageVector,
    val label: String,
    val action: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenu(
    track: Track,
    isLiked: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDownloadTrack: (() -> Unit)? = null,
    onShareFile: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onShowTrackInfo: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha)
    ) {
        Column(
            modifier = Modifier
                // Scrollable so the bottom actions (Go to album/artist) stay
                // reachable when the sheet is taller than a landscape window.
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // Track header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(
                    url = track.coverUrl,
                    contentDescription = track.title,
                    size = 48.dp,
                    cornerRadius = 6.dp
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
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
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Actions
            ContextMenuItem(
                icon = Icons.Default.SkipNext,
                label = "Play next",
                onClick = { onPlayNext(); onDismiss() }
            )
            ContextMenuItem(
                icon = Icons.Default.QueueMusic,
                label = "Add to queue",
                onClick = { onAddToQueue(); onDismiss() }
            )
            ContextMenuItem(
                icon = Icons.Default.Favorite,
                label = if (isLiked) "Unlike" else "Like",
                tint = if (isLiked) MaterialTheme.colorScheme.primary else null,
                onClick = { onToggleLike(); onDismiss() }
            )
            ContextMenuItem(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to playlist",
                onClick = { onAddToPlaylist(); onDismiss() }
            )

            if (onRemoveFromPlaylist != null) {
                ContextMenuItem(
                    icon = Icons.Default.RemoveCircleOutline,
                    label = "Remove from playlist",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { onRemoveFromPlaylist(); onDismiss() }
                )
            }

            if (onDownloadTrack != null) {
                ContextMenuItem(
                    icon = Icons.Default.Download,
                    label = "Download",
                    onClick = { onDownloadTrack(); onDismiss() }
                )
            }

            // Share the actual audio file when the caller provides a handler.
            // The handler resolves to a downloaded copy or a Qobuz cache hit
            // and dispatches Intent.ACTION_SEND with the file URI; if neither
            // exists the action is a no-op (logged in TrackShareHelper).
            if (onShareFile != null) {
                ContextMenuItem(
                    icon = Icons.Default.Share,
                    label = "Share file",
                    onClick = { onShareFile(); onDismiss() }
                )
            }

            if (onGoToAlbum != null && track.album != null) {
                ContextMenuItem(
                    icon = Icons.Default.Album,
                    label = "Go to album",
                    onClick = { onGoToAlbum(); onDismiss() }
                )
            }
            if (onGoToArtist != null && track.artist != null) {
                ContextMenuItem(
                    icon = Icons.Default.Person,
                    label = "Go to artist",
                    onClick = { onGoToArtist(); onDismiss() }
                )
            }
            if (onShowTrackInfo != null) {
                ContextMenuItem(
                    icon = Icons.Default.Info,
                    label = "Track info",
                    onClick = { onShowTrackInfo(); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
