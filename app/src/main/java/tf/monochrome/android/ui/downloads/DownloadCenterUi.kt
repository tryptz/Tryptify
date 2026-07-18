package tf.monochrome.android.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.ActiveDownload
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.ui.components.CoverImage

/**
 * Floating, dismissible pill that surfaces the current download and its progress.
 * Tapping it opens the full monitor; the X hides it until the next download starts.
 */
@Composable
fun DownloadProgressPill(
    downloads: List<ActiveDownload>,
    onClick: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = downloads.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        ?: downloads.firstOrNull() ?: return
    val remaining = downloads.size

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 10.dp,
        tonalElevation = 6.dp,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(
                    url = current.artworkUri,
                    contentDescription = null,
                    size = 38.dp,
                    cornerRadius = 8.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (remaining > 1) "Downloading · $remaining left" else "Downloading",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onHide) {
                    Icon(Icons.Default.Close, contentDescription = "Hide downloads")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (current.status == DownloadStatus.DOWNLOADING && current.progress > 0f) {
                LinearProgressIndicator(
                    progress = { current.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Compact top-bar indicator: a determinate ring (overall progress) wrapping a
 * download glyph. Hidden when nothing is in flight; tap opens the monitor.
 */
@Composable
fun DownloadTopBarIndicator(
    activeCount: Int,
    overallProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeCount == 0) return
    IconButton(onClick = onClick, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { overallProgress.coerceIn(0f, 1f) },
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
            )
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Downloads",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Bottom-sheet monitor listing every in-flight download with per-track progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsMonitorSheet(
    downloads: List<ActiveDownload>,
    onCancel: (Long) -> Unit,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Downloads" + if (downloads.isNotEmpty()) " · ${downloads.size}" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (downloads.isNotEmpty()) {
                    TextButton(onClick = onCancelAll) { Text("Cancel all") }
                }
            }
            if (downloads.isEmpty()) {
                Text(
                    text = "No active downloads.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Bounded, scrollable list so a big batch doesn't clip its
                // trailing entries (the whole sheet was a fixed Column before).
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                downloads.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(
                            url = d.artworkUri,
                            contentDescription = null,
                            size = 44.dp,
                            cornerRadius = 8.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (d.isThxSpatialAudio) {
                                    tf.monochrome.android.ui.components.ThxBadgePill()
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text = d.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (d.status == DownloadStatus.DOWNLOADING) d.artistName else "Queued",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (d.status == DownloadStatus.DOWNLOADING && d.progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { d.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        IconButton(onClick = { onCancel(d.trackId) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                }
                }
            }
        }
    }
}
