package tf.monochrome.android.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.CreatePlaylistDialog
import tf.monochrome.android.ui.components.LoadingScreen
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.TrackArtistAlbumLine
import tf.monochrome.android.ui.components.TrackContextMenu
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.bounceCombinedClick
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.openAlbum
import tf.monochrome.android.ui.navigation.openArtist
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
    emptyContent: @Composable (() -> Unit)? = null,
    onLoadMore: (SearchViewModel.SearchPageType) -> Unit = {},
    isLoadingMore: Boolean = false,
    endReached: Boolean = false,
    searchError: Boolean = false,
    onRetry: () -> Unit = {},
) {
    var showContextMenuForTrack by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistForTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistForSelection by remember { mutableStateOf(false) }

    // UnifiedTrack ids are Strings ("api_…", "qobuz_…", "local_…").
    val selection = rememberTrackSelectionState<String>()
    BackHandler(enabled = selection.active) { selection.clear() }

    showContextMenuForTrack?.let { track ->
        TrackContextMenu(
            track = track,
            isLiked = favoriteTrackIds.contains(track.id),
            onDismiss = { showContextMenuForTrack = null },
            onPlayNext = { playerViewModel.playNext(track) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavorite(track) },
            onAddToPlaylist = { showAddToPlaylistForTrack = track },
            onDownloadTrack = if (playerViewModel.isLocalTrack(track)) null
            else ({ playerViewModel.downloadTrack(track) }),
            onShareFile = { playerViewModel.shareTrack(track) },
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

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = "Add ${selection.count} tracks to playlist",
            playlists = libraryPlaylists,
            onDismiss = { showAddToPlaylistForSelection = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(
                    playlist.id,
                    tracks.filter { it.id in selection.selectedIds }.map { it.toLegacyTrack() },
                )
                showAddToPlaylistForSelection = false
                selection.clear()
            },
            onCreateNew = {
                showAddToPlaylistForSelection = false
                showCreatePlaylistDialog = true
            }
        )
    }

    when {
        query.isBlank() && emptyContent != null -> emptyContent()
        isSearching -> LoadingScreen()
        else -> {
            // One LazyListState per scrollable axis. Each gets its own
            // prefetch trigger so artists / albums / tracks page
            // independently as the user scrolls past their respective
            // last visible item.
            val columnState = rememberLazyListState()
            val artistsRowState = rememberLazyListState()
            val albumsRowState = rememberLazyListState()

            // These horizontal result rows live inside the Home/Library
            // HorizontalPager. Left as-is, a horizontal swipe on a row (or any
            // swipe once the row is at its edge / too short to scroll) leaks up
            // and flips the pager to the other tab. Swallow the leftover
            // horizontal scroll + fling here so the pager never sees it; the
            // vertical component is left untouched so the outer list still
            // scrolls.
            val swallowHorizontal = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset = available.copy(y = 0f)

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity,
                    ): Velocity = available.copy(y = 0f)
                }
            }

            // derivedStateOf only emits when the boolean flips, so the
            // LaunchedEffect re-runs once per page boundary, not once per
            // scroll pixel. PREFETCH thresholds picked to keep the list
            // looking continuous while not over-fetching.
            //
            // The vertical column carries tracks — except on the Playlists-only
            // tab, where playlists ARE the vertical list. Paging TRACKS there
            // loaded more (hidden) tracks and never advanced the playlists, so
            // pick the page type that matches what the column is actually showing.
            val columnPageType = if (selectedType == SearchViewModel.SearchTypeFilter.PLAYLISTS) {
                SearchViewModel.SearchPageType.PLAYLISTS
            } else {
                SearchViewModel.SearchPageType.TRACKS
            }
            PrefetchTrigger(columnState, threshold = TRACK_COL_PREFETCH) {
                onLoadMore(columnPageType)
            }
            PrefetchTrigger(artistsRowState, threshold = ROW_PREFETCH) {
                onLoadMore(SearchViewModel.SearchPageType.ARTISTS)
            }
            PrefetchTrigger(albumsRowState, threshold = ROW_PREFETCH) {
                onLoadMore(SearchViewModel.SearchPageType.ALBUMS)
            }

            Column(modifier = modifier.fillMaxSize()) {
            AnimatedVisibility(visible = selection.active) {
                TrackSelectionBar(
                    selectedCount = selection.count,
                    onClose = { selection.clear() },
                    onAddToQueue = {
                        playerViewModel.addUnifiedToQueue(tracks.filter { it.id in selection.selectedIds })
                        selection.clear()
                    },
                    onAddToPlaylist = { showAddToPlaylistForSelection = true }
                )
            }
            LazyColumn(
                state = columnState,
                modifier = Modifier.fillMaxSize(),
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
                            state = artistsRowState,
                            modifier = Modifier.nestedScroll(swallowHorizontal),
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
                            state = albumsRowState,
                            modifier = Modifier.nestedScroll(swallowHorizontal),
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
                            onClick = {
                                if (selection.active) selection.toggle(track.id)
                                else playerViewModel.playUnifiedTrack(track, tracks)
                            },
                            onLongClick = { selection.toggle(track.id) },
                            onArtistClick = { ref -> ref.id?.let { navController.openArtist(track.sourceType, it) } },
                            onAlbumClick = { navController.openAlbum(track.albumId) },
                            onMoreClick = if (track.sourceType == SourceType.API ||
                                track.sourceType == SourceType.QOBUZ) {
                                { showContextMenuForTrack = track.toLegacyTrack() }
                            } else null,
                            selectionMode = selection.active,
                            selected = track.id in selection.selectedIds
                        )
                    }
                }

                // Footer spinner while another page is in-flight.
                // Disappears once every type has hit endReached so the
                // list terminates cleanly.
                if (isLoadingMore && !endReached) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }

                if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlistResults.isEmpty()) {
                    item {
                        if (searchError) {
                            // Every backend failed (offline / all instances
                            // down) — offer a retry instead of implying the
                            // query genuinely has no matches.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "Couldn't reach search. Check your connection and try again.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onRetry) { Text("Retry") }
                            }
                        } else {
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
    }
}

/** Compose-private threshold constants for the prefetch triggers. */
private const val TRACK_COL_PREFETCH = 15
private const val ROW_PREFETCH = 6

/**
 * Fires [onTrigger] when the given list state's last visible item index
 * approaches [threshold] of the tail. Uses derivedStateOf so the
 * LaunchedEffect only re-runs when the boolean transitions, not on every
 * scroll pixel.
 */
@Composable
private fun PrefetchTrigger(
    state: LazyListState,
    threshold: Int,
    onTrigger: () -> Unit,
) {
    val shouldLoad by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && last >= total - threshold
        }
    }
    // Also key on the item count: when a page adds fewer items than the
    // threshold, `shouldLoad` stays true and a plain LaunchedEffect(shouldLoad)
    // would never re-run — paging stalled. Re-firing whenever the count grows
    // keeps pulling pages until the tail moves out of range (loadMore itself
    // no-ops once the type is exhausted or a page is already in flight).
    val itemCount by remember(state) {
        derivedStateOf { state.layoutInfo.totalItemsCount }
    }
    LaunchedEffect(shouldLoad, itemCount) {
        if (shouldLoad) onTrigger()
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
    onArtistClick: (UnifiedArtistRef) -> Unit,
    onAlbumClick: () -> Unit,
    onMoreClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false
) {
    val legacyTrack = track.toLegacyTrack()
    // Same treatment as TrackItem: hide per-row affordances while selecting.
    val effectiveOnLikeClick = onLikeClick.takeUnless { selectionMode }
    val effectiveOnMoreClick = onMoreClick.takeUnless { selectionMode }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            .bounceCombinedClick(onClick = onClick, onLongClick = onLongClick ?: onMoreClick)
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
            }
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
                TrackArtistAlbumLine(
                    track = track,
                    onArtistClick = if (selectionMode) ({}) else onArtistClick,
                    onAlbumClick = if (selectionMode) ({}) else onAlbumClick,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResultBadge(text = track.sourceType.label())
                    if (track.isThxSpatialAudio) {
                        tf.monochrome.android.ui.components.ThxBadgePill()
                    }
                    track.qualityBadge?.let { ResultBadge(text = it) }
                }
            }
            if (effectiveOnLikeClick != null) {
                IconButton(onClick = effectiveOnLikeClick) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = legacyTrack.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (effectiveOnMoreClick != null) {
                IconButton(onClick = effectiveOnMoreClick) {
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
    SourceType.QOBUZ -> "Qobuz"
    SourceType.APPLE -> "Apple Music"
}
