package tf.monochrome.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.bounceCombinedClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens

@Composable
fun SearchQueryField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "Search tracks, albums, artists, playlists…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "Search")
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = {
                onSubmit()
                keyboardController?.hide()
            }
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .liquidGlass(shape = MonoDimens.shapePill)
    )
}

@Composable
fun SearchHistoryContent(
    history: List<String>,
    onSelect: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            Text(
                text = "Search for your favorite music",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(title = "Recent searches")
                    TextButton(onClick = onClearHistory) {
                        Text("Clear")
                    }
                }
            }
            items(history, key = { it }) { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .liquidGlass(shape = MonoDimens.shapeMd),
                    shape = MonoDimens.shapeMd,
                    color = Color.Transparent,
                    onClick = { onSelect(item) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultsContent(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    query: String,
    tracks: List<UnifiedTrack>,
    albums: List<Album>,
    artists: List<Artist>,
    playlistResults: List<Playlist>,
    isSearching: Boolean,
    selectedType: SearchViewModel.SearchTypeFilter,
    onTypeSelected: (SearchViewModel.SearchTypeFilter) -> Unit,
    selectedSource: SearchViewModel.SearchSourceFilter,
    onSourceSelected: (SearchViewModel.SearchSourceFilter) -> Unit,
    showSourceFilter: Boolean,
    favoriteTrackIds: Set<Long>,
    libraryPlaylists: List<UserPlaylistEntity>,
    modifier: Modifier = Modifier,
    emptyContent: @Composable (() -> Unit)? = null
) {
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

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
                playerViewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    showAddToPlaylistForTrack?.let { track ->
        AddToPlaylistSheet(
            track = track,
            playlists = libraryPlaylists,
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

    when {
        query.isBlank() && emptyContent != null -> emptyContent()
        isSearching -> LoadingScreen()
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    SearchFilterRow(
                        selectedType = selectedType,
                        onTypeSelected = onTypeSelected,
                        selectedSource = selectedSource,
                        onSourceSelected = onSourceSelected,
                        showSourceFilter = showSourceFilter
                    )
                }

                if (artists.isNotEmpty()) {
                    item { SectionHeader(title = "Artists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(artists, key = { it.id }) { artist ->
                                ArtistItem(
                                    artist = artist,
                                    onClick = {
                                        navController.navigate(
                                            Screen.ArtistDetail.createRoute(artist.id)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (albums.isNotEmpty()) {
                    item { SectionHeader(title = "Albums") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumItem(
                                    album = album,
                                    onClick = {
                                        navController.navigate(
                                            Screen.AlbumDetail.createRoute(album.id)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (playlistResults.isNotEmpty()) {
                    item { SectionHeader(title = "Playlists") }
                    items(playlistResults, key = { it.uuid }) { playlist ->
                        PlaylistSearchItem(
                            playlist = playlist,
                            onClick = {
                                navController.navigate(
                                    Screen.PlaylistDetail.createRoute(playlist.uuid)
                                )
                            }
                        )
                    }
                }

                if (tracks.isNotEmpty()) {
                    item { SectionHeader(title = "Tracks") }
                    items(tracks, key = { it.id }) { track ->
                        UnifiedSearchTrackItem(
                            track = track,
                            isLiked = favoriteTrackIds.contains(track.toLegacyTrack().id),
                            onLikeClick = { playerViewModel.toggleFavorite(track.toLegacyTrack()) },
                            onClick = { playerViewModel.playUnifiedTrack(track, tracks) },
                            onMoreClick = if (track.sourceType == SourceType.API) {
                                { showContextMenuForTrack = track.toLegacyTrack() }
                            } else null
                        )
                    }
                }

                if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlistResults.isEmpty()) {
                    item {
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterRow(
    selectedType: SearchViewModel.SearchTypeFilter,
    onTypeSelected: (SearchViewModel.SearchTypeFilter) -> Unit,
    selectedSource: SearchViewModel.SearchSourceFilter,
    onSourceSelected: (SearchViewModel.SearchSourceFilter) -> Unit,
    showSourceFilter: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SearchViewModel.SearchTypeFilter.entries) { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) },
                    label = { Text(type.label) }
                )
            }
        }
        if (showSourceFilter) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SearchViewModel.SearchSourceFilter.entries) { source ->
                    FilterChip(
                        selected = selectedSource == source,
                        onClick = { onSourceSelected(source) },
                        label = { Text(source.label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedSearchTrackItem(
    track: UnifiedTrack,
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)?
) {
    val legacyTrack = track.toLegacyTrack()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            .bounceCombinedClick(onClick = onClick, onLongClick = onMoreClick)
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (track.artworkUri != null) {
                CoverImage(
                    url = track.artworkUri,
                    contentDescription = track.title,
                    size = MonoDimens.coverList,
                    cornerRadius = MonoDimens.radiusSm
                )
            } else {
                Surface(
                    modifier = Modifier.size(MonoDimens.coverList),
                    shape = RoundedCornerShape(MonoDimens.radiusSm),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(track.artistName)
                        track.albumTitle?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResultBadge(text = track.sourceType.label())
                    track.qualityBadge?.let { ResultBadge(text = it) }
                }
            }
            IconButton(onClick = onLikeClick) {
                Icon(
                    imageVector = if (isLiked) Favorite else FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = legacyTrack.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onMoreClick != null) {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSearchItem(
    playlist: Playlist,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (playlist.coverUrl != null) {
                CoverImage(
                    url = playlist.coverUrl,
                    contentDescription = playlist.title,
                    size = MonoDimens.coverList,
                    cornerRadius = MonoDimens.radiusSm
                )
            } else {
                Surface(
                    modifier = Modifier.size(MonoDimens.coverList),
                    shape = RoundedCornerShape(MonoDimens.radiusSm),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(playlist.creator?.name ?: "Playlist")
                        playlist.numberOfTracks?.let { append(" • $it tracks") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ResultBadge(text = "TIDAL")
        }
    }
}

@Composable
private fun ResultBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun SourceType.label(): String = when (this) {
    SourceType.API -> "TIDAL"
    SourceType.LOCAL -> "Local"
    SourceType.COLLECTION -> "Collection"
}
