package tf.monochrome.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.ErrorScreen
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val artistDetail by viewModel.artistDetail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val playlists by playerViewModel.playlists.collectAsState()

    var showContextMenuForTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<tf.monochrome.android.domain.model.Track?>(null) }
    var showAddToPlaylistForTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<tf.monochrome.android.domain.model.Track?>(null) }
    var showCreatePlaylistDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAllTopTracks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    showContextMenuForTrack?.let { track ->
        TrackContextMenu(
            track = track,
            isLiked = favoriteTrackIds.contains(track.id),
            onDismiss = { showContextMenuForTrack = null },
            onPlayNext = { playerViewModel.playNext(track) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavorite(track) },
            onAddToPlaylist = { showAddToPlaylistForTrack = track },
            onDownloadTrack = { playerViewModel.downloadTrack(track) },
            onShareFile = { playerViewModel.shareTrack(track) },
            onGoToAlbum = track.album?.id?.let { albumId ->
                { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
            },
            onGoToArtist = null // Already here
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onSubmit = { name, description ->
                playerViewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    showAddToPlaylistForTrack?.let { track ->
        AddToPlaylistSheet(
            track = track,
            playlists = playlists,
            onDismiss = { showAddToPlaylistForTrack = null },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTrackToPlaylist(playlist.id, track)
                showAddToPlaylistForTrack = null
            },
            onCreateNew = {
                showAddToPlaylistForTrack = null
                showCreatePlaylistDialog = true
            }
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
                containerColor = Color.Transparent
            )
        )

        when {
            isLoading -> LoadingScreen()
            error != null -> ErrorScreen(
                message = error ?: "Unknown error",
                onRetry = { viewModel.retry() }
            )
            artistDetail != null -> {
                val detail = artistDetail ?: return
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        tf.monochrome.android.devedit.DevEditable("artist_hero", Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (detail.artist.pictureUrl != null) {
                                AsyncImage(
                                    model = detail.artist.pictureUrl,
                                    contentDescription = detail.artist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(180.dp).clip(CircleShape)
                                )
                            } else {
                                CoverImage(
                                    url = null,
                                    contentDescription = detail.artist.name,
                                    size = 180.dp,
                                    cornerRadius = 90.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = detail.artist.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        }
                    }

                    if (detail.topTracks.isNotEmpty()) {
                        item { tf.monochrome.android.devedit.DevEditable("artist_section_top_tracks", Modifier.fillMaxWidth()) { SectionHeader(title = "Top Tracks") } }
                        val visibleTracks =
                            if (showAllTopTracks) detail.topTracks else detail.topTracks.take(5)
                        items(visibleTracks) { track ->
                            TrackItem(
                                track = track,
                                isLiked = favoriteTrackIds.contains(track.id),
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = { playerViewModel.playTrack(track, detail.topTracks) },
                                onLongClick = { showContextMenuForTrack = track },
                                onMoreClick = { showContextMenuForTrack = track },
                                onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                }
                            )
                        }
                        if (detail.topTracks.size > 5) {
                            item {
                                ShowAllTracksRow(
                                    expanded = showAllTopTracks,
                                    totalCount = detail.topTracks.size,
                                    onToggle = { showAllTopTracks = !showAllTopTracks },
                                )
                            }
                        }
                    }

                    if (detail.albums.isNotEmpty()) {
                        item { tf.monochrome.android.devedit.DevEditable("artist_section_albums", Modifier.fillMaxWidth()) { SectionHeader(title = "Albums") } }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(detail.albums) { album ->
                                    AlbumItem(
                                        album = album,
                                        onClick = {
                                            navController.navigate(Screen.AlbumDetail.createRoute(album.id))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    val epSingles = detail.eps + detail.singles
                    if (epSingles.isNotEmpty()) {
                        item { tf.monochrome.android.devedit.DevEditable("artist_section_singles_eps", Modifier.fillMaxWidth()) { SectionHeader(title = "Singles & EPs") } }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(epSingles) { album ->
                                    AlbumItem(
                                        album = album,
                                        onClick = {
                                            navController.navigate(Screen.AlbumDetail.createRoute(album.id))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (detail.unreleasedTracks.isNotEmpty()) {
                        item { tf.monochrome.android.devedit.DevEditable("artist_section_unreleased", Modifier.fillMaxWidth()) { SectionHeader(title = "Unreleased (ArtistGrid)") } }
                        item {
                            androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
                                rows = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                                modifier = Modifier.height(220.dp).fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(detail.unreleasedTracks) { track ->
                                    ArtistGridTrackItem(
                                        track = track,
                                        onClick = {
                                            playerViewModel.playTrack(track, detail.unreleasedTracks)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (detail.similarArtists.isNotEmpty()) {
                        item { tf.monochrome.android.devedit.DevEditable("artist_section_similar_artists", Modifier.fillMaxWidth()) { SectionHeader(title = "Similar Artists") } }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(detail.similarArtists) { artist ->
                                    ArtistItem(
                                        artist = artist,
                                        onClick = {
                                            navController.navigate(Screen.ArtistDetail.createRoute(artist.id))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Show all N tracks" / "Show less" toggle under the Top Tracks list. The
 * Qobuz artist endpoint only returns top_tracks (no full discography), so this
 * surfaces the complete list the catalogue provides rather than just the
 * first five.
 */
@Composable
private fun ShowAllTracksRow(
    expanded: Boolean,
    totalCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (expanded) "Show less" else "Show all $totalCount tracks",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ArtistGridTrackItem(
    track: tf.monochrome.android.domain.model.Track,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverImage(
            url = track.coverUrl,
            contentDescription = track.title,
            size = 80.dp,
            cornerRadius = 8.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
