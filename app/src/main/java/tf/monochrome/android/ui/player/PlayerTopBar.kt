package tf.monochrome.android.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.data.downloads.TrackDownloadState
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.ui.components.liquidGlass

/**
 * Utility top bar for the main player: collapse handle on the left, then an
 * output-device button, a speed chip and an overflow menu on the right. The
 * overflow hosts the secondary controls (shuffle, repeat, download, hero
 * style) that no longer live on the main transport row.
 */
@Composable
fun PlayerTopBar(
    speedLabel: String,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    isDownloaded: Boolean,
    downloadState: TrackDownloadState,
    heroStyle: PlayerHeroStyle,
    onCollapse: () -> Unit,
    onOutputClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onDownload: () -> Unit,
    onCycleHeroStyle: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyricsStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PlayerDesignTokens.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Collapse",
                tint = Color.White,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onOutputClick) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = "Output device",
                    tint = Color.White,
                )
            }

            Surface(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSpeedClick,
                    )
                    .liquidGlass(
                        shape = RoundedCornerShape(999.dp),
                        tintAlpha = PlayerDesignTokens.GlassTintMedium,
                        borderAlpha = PlayerDesignTokens.GlassTintSoft,
                    ),
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                contentColor = Color.White,
            ) {
                Text(
                    text = speedLabel,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (onGoToArtist != null) {
                        DropdownMenuItem(
                            text = { Text("Go to artist") },
                            onClick = { onGoToArtist(); menuExpanded = false },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        )
                    }
                    if (onGoToAlbum != null) {
                        DropdownMenuItem(
                            text = { Text("Go to album") },
                            onClick = { onGoToAlbum(); menuExpanded = false },
                            leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (shuffleEnabled) "Shuffle: On" else "Shuffle: Off") },
                        onClick = { onToggleShuffle(); menuExpanded = false },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = if (shuffleEnabled) PlayerGlowMint else Color.Unspecified,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (repeatMode) {
                                    RepeatMode.OFF -> "Repeat: Off"
                                    RepeatMode.ONE -> "Repeat: One"
                                    RepeatMode.ALL -> "Repeat: All"
                                }
                            )
                        },
                        onClick = { onCycleRepeat(); menuExpanded = false },
                        leadingIcon = {
                            Icon(
                                if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = null,
                                tint = if (repeatMode != RepeatMode.OFF) PlayerGlowMint else Color.Unspecified,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(downloadMenuLabel(downloadState, isDownloaded)) },
                        onClick = { onDownload(); menuExpanded = false },
                        leadingIcon = {
                            val completed = isDownloaded || downloadState.status == DownloadStatus.COMPLETED
                            Icon(
                                when {
                                    downloadState.status == DownloadStatus.FAILED -> Icons.Default.ErrorOutline
                                    completed -> Icons.Default.DownloadDone
                                    else -> Icons.Default.Download
                                },
                                contentDescription = null,
                                tint = if (completed) PlayerGlowMint else Color.Unspecified,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (heroStyle == PlayerHeroStyle.CircularProgress) "Square art" else "Circular art") },
                        onClick = { onCycleHeroStyle(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Visualizer") },
                        onClick = { onOpenVisualizer(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Equalizer") },
                        onClick = { onOpenEqualizer(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Default.Equalizer, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Player Visuals Studio") },
                        onClick = { onOpenLyricsStudio(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { onOpenSettings(); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
            }
        }
    }
}

private fun downloadMenuLabel(state: TrackDownloadState, isDownloaded: Boolean): String = when {
    isDownloaded || state.status == DownloadStatus.COMPLETED -> "Downloaded"
    state.status == DownloadStatus.DOWNLOADING -> "Downloading ${(state.progress.coerceIn(0f, 1f) * 100f).toInt()}%"
    state.status == DownloadStatus.QUEUED -> "Queued"
    state.status == DownloadStatus.FAILED -> "Download failed — retry"
    else -> "Download"
}
