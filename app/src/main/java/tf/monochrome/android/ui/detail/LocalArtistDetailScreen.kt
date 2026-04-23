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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.ErrorScreen
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalArtistDetailScreen(
    navController: NavController,
    onPlayTrack: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onPlayAll: (List<UnifiedTrack>) -> Unit,
    onShuffleAll: (List<UnifiedTrack>) -> Unit,
    viewModel: LocalArtistDetailViewModel = hiltViewModel()
) {
    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val sortedTracks = remember(tracks) {
        tracks.sortedWith(
            compareBy({ it.albumTitle ?: "" }, { it.discNumber ?: 1 }, { it.trackNumber ?: 0 })
        )
    }

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

        val artistData = artist
        when {
            isLoading -> LoadingScreen()
            error != null -> ErrorScreen(
                message = error ?: "Unknown error",
                onRetry = { viewModel.retry() }
            )
            artistData != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Artist header
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (artistData.artworkUri != null) {
                                AsyncImage(
                                    model = artistData.artworkUri,
                                    contentDescription = artistData.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(180.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = artistData.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${artistData.albumCount} albums · ${artistData.trackCount} tracks",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                        }
                    }

                    // Albums section
                    if (albums.isNotEmpty()) {
                        item { SectionHeader(title = "Albums") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    LocalAlbumCard(
                                        album = album,
                                        onClick = {
                                            val albumId = album.id.removePrefix("local_album_").toLongOrNull()
                                            if (albumId != null) {
                                                navController.navigate(Screen.LocalAlbumDetail.createRoute(albumId))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // All tracks
                    if (sortedTracks.isNotEmpty()) {
                        item { SectionHeader(title = "All Tracks") }
                        items(sortedTracks, key = { it.id }) { track ->
                            ArtistTrackRow(
                                track = track,
                                onClick = { onPlayTrack(track, sortedTracks) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalAlbumCard(
    album: UnifiedAlbum,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .bounceClick(onClick = onClick),
        shape = MonoDimens.shapeMd,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.cardAlpha)
        )
    ) {
        Column {
            if (album.artworkUri != null) {
                AsyncImage(
                    model = album.artworkUri,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MonoDimens.shapeMd)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
            Column(modifier = Modifier.padding(MonoDimens.spacingSm)) {
                Text(
                    album.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = listOfNotNull(
                    album.year?.toString(),
                    "${album.trackCount} tracks"
                ).joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArtistTrackRow(
    track: UnifiedTrack,
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
            if (track.artworkUri != null) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MonoDimens.shapeSm)
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.albumTitle ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val badge = track.qualityBadge
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
