package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.data.downloads.TrackDownloadState
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * Utility top bar for the main player: collapse handle on the left, then the
 * devices and output buttons, a speed chip and the ⋮ on the right. The ⋮ opens
 * the playing song's menu, the one a song row has ([onMoreClick]; the route
 * shows it, since the menu is drawn in the app's overlay slot, not here).
 */
@Composable
fun PlayerTopBar(
    speedLabel: String,
    onCollapse: () -> Unit,
    onOutputClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onMoreClick: () -> Unit,
    /** Tryptify Connect's Devices panel; null hides the button. */
    onDevicesClick: (() -> Unit)? = null,
    /** Another of the listener's devices is online (the icon says so). */
    devicesOnline: Boolean = false,
) {

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
                contentDescription = stringResource(R.string.action_collapse),
                tint = Color.White,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onDevicesClick != null) {
                IconButton(onClick = onDevicesClick) {
                    Icon(
                        imageVector = if (devicesOnline) Icons.Default.DevicesOther else Icons.Default.Devices,
                        contentDescription = stringResource(R.string.connect_devices),
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = onOutputClick) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = stringResource(R.string.output_device),
                    tint = Color.White,
                )
            }

            // The speed (or BPM) pill: the same glass button as the transport,
            // the dock and the status cards, in the player's own material —
            // real backdrop, the dome under a finger, the squeeze on press. It
            // was a flat liquidGlass tint, glass in name only. Pill-shaped by
            // PressableGlass's default.
            tf.monochrome.android.ui.components.PressableGlass(
                onClick = onSpeedClick,
                onClickLabel = stringResource(R.string.speed),
                glass = LocalPlayerGlass.current,
            ) {
                Text(
                    text = speedLabel,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }

            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                    tint = Color.White,
                )
            }
        }
    }
}

/** What the player's song menu calls Download: how the playing song's download stands. */
@androidx.compose.runtime.Composable
fun playerDownloadLabel(state: TrackDownloadState, isDownloaded: Boolean): String = when {
    isDownloaded || state.status == DownloadStatus.COMPLETED -> stringResource(R.string.download_done)
    state.status == DownloadStatus.DOWNLOADING -> stringResource(R.string.download_progress, (state.progress.coerceIn(0f, 1f) * 100f).toInt())
    state.status == DownloadStatus.QUEUED -> stringResource(R.string.download_queued)
    state.status == DownloadStatus.FAILED -> stringResource(R.string.download_failed_retry)
    else -> stringResource(R.string.action_download)
}
