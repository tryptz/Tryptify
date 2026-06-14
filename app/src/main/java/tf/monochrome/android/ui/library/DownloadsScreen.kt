package tf.monochrome.android.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.player.PlayerViewModel

@Composable
fun DownloadsScreen(
    navController: NavController,
    viewModel: DownloadsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()

    if (downloadedTracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No downloaded tracks found.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    // Materialize unified-track lists once per recomposition. The full list
    // drives 'tap a track to play within the entire downloads queue', the
    // per-album lists drive 'tap an album card to play that album in order'.
    val allUnified = downloadedTracks.map { it.toUnifiedTrack() }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (albumGroups.isNotEmpty()) {
            item {
                tf.monochrome.android.devedit.DevEditable("downloads_albums_header", Modifier.fillMaxWidth()) {
                Text(
                    text = "Albums",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albumGroups, key = { it.title + it.artistName }) { group ->
                        AlbumCard(
                            group = group,
                            onClick = {
                                val groupUnified = group.tracks.map { it.toUnifiedTrack() }
                                groupUnified.firstOrNull()?.let { first ->
                                    playerViewModel.playUnifiedTrack(first, groupUnified)
                                }
                            },
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                tf.monochrome.android.devedit.DevEditable("downloads_tracks_header", Modifier.fillMaxWidth()) {
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                }
            }
        }

        items(downloadedTracks, key = { it.id }) { track ->
            DownloadedTrackRow(
                track = track,
                onClick = {
                    val tappedUnified = track.toUnifiedTrack()
                    playerViewModel.playUnifiedTrack(tappedUnified, allUnified)
                },
                onShare = { playerViewModel.shareDownloadedTrack(track) },
                onDelete = { viewModel.deleteDownload(track) },
            )
        }
    }
}

@Composable
private fun AlbumCard(
    group: DownloadedAlbumGroup,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        CoverImage(
            url = group.cover,
            contentDescription = group.title,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = group.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${group.artistName} • ${group.trackCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadedTrackRow(
    track: DownloadedTrackEntity,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = track.albumCover,
            contentDescription = track.title,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val mbSize = track.sizeBytes / (1024 * 1024)
            Text(
                text = "${track.artistName} • $mbSize MB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onShare) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete Download",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
