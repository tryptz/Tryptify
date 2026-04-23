package tf.monochrome.android.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackItem
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: LibraryViewModel = hiltViewModel(),
    localLibraryViewModel: LocalLibraryViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val favoriteAlbums by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtists by viewModel.favoriteArtists.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsState()
    val activeDownloads by playerViewModel.activeDownloads.collectAsState()

    val libraryTabOrder by settingsViewModel.libraryTabOrder.collectAsState()

    val tabDisplayNames = mapOf(
        "overview" to "Overview",
        "local" to "Local",
        "playlists" to "Playlists",
        "favorites" to "Favorites",
        "downloads" to "Downloads"
    )
    val tabs = libraryTabOrder.mapNotNull { id -> tabDisplayNames[id]?.let { id to it } }

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }

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
            onGoToAlbum = track.album?.id?.let { albumId ->
                { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
            },
            onGoToArtist = track.artist?.id?.let { artistId ->
                { navController.navigate(Screen.ArtistDetail.createRoute(artistId)) }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onSubmit = { name, description ->
                viewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            },
            onImportCsv = { uri, strict, name, description ->
                viewModel.importCsvPlaylist(uri, strict, name, description)
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
            title = {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium
                )
            },
            actions = {
                IconButton(onClick = { navController.navigate(Screen.Settings.createRoute()) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            edgePadding = 8.dp
        ) {
            tabs.forEachIndexed { index, (_, title) ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        val currentSectionId = tabs.getOrNull(selectedTabIndex)?.first ?: "overview"

        when (currentSectionId) {
            "overview" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (recentTracks.isNotEmpty()) {
                        item { SectionHeader(title = "Recently Played") }
                        items(recentTracks.take(5)) { track ->
                            TrackItem(
                                track = track,
                                isLiked = favoriteTrackIds.contains(track.id),
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = { playerViewModel.playTrack(track, recentTracks) },
                                onLongClick = { showContextMenuForTrack = track },
                                onMoreClick = { showContextMenuForTrack = track },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id]
                            )
                        }
                    }

                    if (favoriteTracks.isNotEmpty()) {
                        item { SectionHeader(title = "Liked Songs") }
                        items(favoriteTracks.take(5)) { track ->
                            TrackItem(
                                track = track,
                                isLiked = true,
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = { playerViewModel.playTrack(track, favoriteTracks) },
                                onLongClick = { showContextMenuForTrack = track },
                                onMoreClick = { showContextMenuForTrack = track },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id]
                            )
                        }
                    }

                    if (favoriteTracks.isEmpty() && recentTracks.isEmpty()) {
                        item { EmptyState("Start playing music to build your library.") }
                    }
                }

            "local" ->
                LocalLibraryTab(
                    viewModel = localLibraryViewModel,
                    onTrackClick = { track, queue ->
                        playerViewModel.playUnifiedTrack(track, queue)
                    },
                    onAlbumClick = { album ->
                        val albumId = album.id.removePrefix("local_album_").toLongOrNull()
                        if (albumId != null) {
                            navController.navigate("local_album/$albumId")
                        }
                    },
                    onArtistClick = { artist ->
                        val artistId = artist.id.removePrefix("local_artist_").toLongOrNull()
                        if (artistId != null) {
                            navController.navigate("local_artist/$artistId")
                        }
                    },
                    onGenreClick = { genre ->
                        navController.navigate(Screen.LocalGenreDetail.createRoute(genre))
                    },
                    onFolderClick = { path ->
                        navController.navigate("folder/${java.net.URLEncoder.encode(path, "UTF-8")}")
                    },
                    onShuffleAll = { tracks ->
                        playerViewModel.shufflePlayUnified(tracks)
                    }
                )

            "playlists" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreatePlaylistDialog = true }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Create Playlist",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (playlists.isEmpty()) {
                        item { EmptyState("Create a playlist to organize your music.") }
                    } else {
                        items(playlists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate("playlist/${playlist.id}") }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistPlay,
                                    contentDescription = "Playlist",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!playlist.description.isNullOrEmpty()) {
                                        Text(
                                            text = playlist.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            "favorites" ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (favoriteTracks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionHeader(title = "Liked Songs")
                                IconButton(onClick = { playerViewModel.downloadAllTracks(favoriteTracks) }) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download All",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        items(favoriteTracks) { track ->
                            TrackItem(
                                track = track,
                                isLiked = true,
                                onLikeClick = { playerViewModel.toggleFavorite(track) },
                                onClick = { playerViewModel.playTrack(track, favoriteTracks) },
                                onLongClick = { showContextMenuForTrack = track },
                                onMoreClick = { showContextMenuForTrack = track },
                                onAlbumClick = track.album?.id?.let { albumId ->
                                    { navController.navigate(Screen.AlbumDetail.createRoute(albumId)) }
                                },
                                downloadState = activeDownloads[track.id]
                            )
                        }
                    }

                    if (favoriteAlbums.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item { SectionHeader(title = "Liked Albums") }
                        items(favoriteAlbums) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate(Screen.AlbumDetail.createRoute(album.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AlbumItem(album = album, onClick = { navController.navigate(Screen.AlbumDetail.createRoute(album.id)) })
                            }
                        }
                    }

                    if (favoriteArtists.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                        item { SectionHeader(title = "Liked Artists") }
                        items(favoriteArtists) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate(Screen.ArtistDetail.createRoute(artist.id)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtistItem(artist = artist, onClick = { navController.navigate(Screen.ArtistDetail.createRoute(artist.id)) })
                            }
                        }
                    }

                    if (favoriteTracks.isEmpty() && favoriteAlbums.isEmpty() && favoriteArtists.isEmpty()) {
                        item { EmptyState("Like tracks, albums, and artists to see them here.") }
                    }
                }

            "downloads" ->
                DownloadsScreen(navController = navController)
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}
