package tf.monochrome.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.ErrorScreen
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * A Spotify playlist — public ones included — laid out like
 * [SpotifyAlbumDetailScreen]. Its tracks are SpotifyRemote UnifiedTracks, so
 * play and shuffle take the same path as a Spotify album's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistDetailScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: SpotifyPlaylistDetailViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = header?.name ?: "Playlist",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        when {
            isLoading -> LoadingScreen()
            error != null -> ErrorScreen(message = error ?: "Failed to load playlist") { viewModel.retry() }
            else -> {
                val h = header
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            h?.coverUrl?.let { cover ->
                                CoverImage(url = cover, contentDescription = h.name, size = 96.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column {
                                h?.owner?.let {
                                    Text(
                                        text = "By $it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "${tracks.size} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                h?.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledIconButton(onClick = { playerViewModel.playAllUnified(tracks) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                            }
                            FilledIconButton(onClick = { playerViewModel.shufflePlayUnified(tracks) }) {
                                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
                            }
                        }
                    }
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        SpotifyTrackRow(
                            track = track,
                            trackNumber = index + 1,
                            onClick = { playerViewModel.playUnifiedTrack(track, tracks) },
                        )
                    }
                }
            }
        }
    }
}
