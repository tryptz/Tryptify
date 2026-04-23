package tf.monochrome.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.ErrorScreen
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAlbumDetailScreen(
    navController: NavController,
    onPlayTrack: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onPlayAll: (List<UnifiedTrack>) -> Unit,
    onShuffleAll: (List<UnifiedTrack>) -> Unit,
    viewModel: LocalAlbumDetailViewModel = hiltViewModel()
) {
    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        when {
            isLoading -> LoadingScreen()
            error != null -> ErrorScreen(
                message = error ?: "Unknown error",
                onRetry = { viewModel.retry() }
            )
            album != null -> {
                val albumData = album ?: return
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (albumData.artworkUri != null) {
                                AsyncImage(
                                    model = albumData.artworkUri,
                                    contentDescription = albumData.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Album,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = albumData.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = albumData.artistName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val metaParts = buildList {
                                albumData.year?.let { add(it.toString()) }
                                add("${albumData.trackCount} tracks")
                                albumData.qualitySummary?.let { add(it) }
                            }
                            Text(
                                text = metaParts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (albumData.genres.isNotEmpty()) {
                                Text(
                                    text = albumData.genres.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledIconButton(
                                    onClick = { if (tracks.isNotEmpty()) onPlayAll(tracks) },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play All",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                FilledIconButton(
                                    onClick = { if (tracks.isNotEmpty()) onShuffleAll(tracks) },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Shuffle,
                                        contentDescription = "Shuffle",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        LocalTrackRow(
                            track = track,
                            trackNumber = track.trackNumber ?: (index + 1),
                            onClick = { onPlayTrack(track, tracks) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(
    track: UnifiedTrack,
    trackNumber: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs),
        shape = MonoDimens.shapeMd,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.cardAlpha),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (track.qualityBadge != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = track.qualityBadge!!,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = formatDuration(track.durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
