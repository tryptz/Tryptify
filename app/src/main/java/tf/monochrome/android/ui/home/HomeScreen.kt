package tf.monochrome.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.PageJumpList
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.search.SearchHistoryContent
import tf.monochrome.android.ui.search.SearchResultsContent
import tf.monochrome.android.ui.search.SearchViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    // The page list, and the nav host's way of opening one. A lambda rather
    // than the PagerState: see PageJumpList's own note on whose coroutine scope
    // the scroll has to run on.
    pages: List<String>,
    onSelectPage: (String) -> Unit,
    searchViewModel: SearchViewModel = hiltViewModel(),
    downloadCenter: tf.monochrome.android.ui.downloads.DownloadCenterViewModel = hiltViewModel(),
    settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel(),
) {
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val activeDownloads by downloadCenter.active.collectAsStateWithLifecycle()
    val downloadProgress by downloadCenter.overallProgress.collectAsStateWithLifecycle()
    var showDownloadsMonitor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Both only feed SearchResultsContent now that the Recently Played list is
    // gone from this screen.
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val libraryPlaylists by playerViewModel.playlists.collectAsStateWithLifecycle()

    // Update notice. Reads straight off the settings store so opening About
    // from anywhere — the bar, or the user's own navigation — clears it.
    val whatsNewSeen by settingsViewModel.whatsNewSeenVersion.collectAsStateWithLifecycle()
    val whatsNewNeverShow by settingsViewModel.whatsNewNeverShow.collectAsStateWithLifecycle()
    val showWhatsNew = tf.monochrome.android.ui.settings.WhatsNew
        .shouldNotify(whatsNewSeen, whatsNewNeverShow)
    val whatsNewVersionName = tf.monochrome.android.ui.settings.WhatsNew
        .current?.versionName.orEmpty()

    // A release waiting on GitHub outranks the notes for the build already
    // installed: "there's a newer version" is the more useful of the two, and
    // showing both at once would be two bars saying almost the same thing.
    val availableUpdate by settingsViewModel.availableUpdate.collectAsStateWithLifecycle()
    val showUpdateBar by settingsViewModel.showUpdateBar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settingsViewModel.refreshUpdateStatus() }

    // Search state
    val searchQuery by searchViewModel.query.collectAsStateWithLifecycle()
    val searchTracks by searchViewModel.tracks.collectAsStateWithLifecycle()
    val searchAlbums by searchViewModel.albums.collectAsStateWithLifecycle()
    val searchArtists by searchViewModel.artists.collectAsStateWithLifecycle()
    val searchPlaylists by searchViewModel.playlists.collectAsStateWithLifecycle()
    val isSearching by searchViewModel.isSearching.collectAsStateWithLifecycle()
    val selectedType by searchViewModel.selectedType.collectAsStateWithLifecycle()
    val selectedSource by searchViewModel.selectedSource.collectAsStateWithLifecycle()
    val showSourceFilter by searchViewModel.showSourceFilter.collectAsStateWithLifecycle()
    val isLoadingMore by searchViewModel.isLoadingMore.collectAsStateWithLifecycle()
    val endReached by searchViewModel.endReached.collectAsStateWithLifecycle()
    val searchError by searchViewModel.searchError.collectAsStateWithLifecycle()
    val searchHistory by searchViewModel.searchHistory.collectAsStateWithLifecycle()
    val hasSearchResults = searchQuery.isNotBlank()

    // Search reveals on demand; radio is the resting primary action.
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Whether opening the bar should take the keyboard with it.
    //
    // Deliberately a plain remember against a saveable searchOpen: the bar is
    // composed only while it is showing, so it asks for focus each time it
    // appears — and "appears" includes Home being rebuilt with searchOpen
    // restored true, which is arriving back from a detail screen, not a request
    // to type. That case rebuilds this as false and the keyboard stays down.
    var focusOnOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Back closes an open search (and clears the query so the feed returns)
    // instead of falling through and exiting the app.
    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        searchViewModel.onQueryChange("")
        searchOpen = false
    }

    if (showDownloadsMonitor) {
        tf.monochrome.android.ui.downloads.DownloadsMonitorSheet(
            downloads = activeDownloads,
            onCancel = downloadCenter::cancel,
            onCancelAll = downloadCenter::cancelAll,
            onDismiss = { showDownloadsMonitor = false },
            onRetry = downloadCenter::retry,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        tf.monochrome.android.devedit.DevEditable("home_header", Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    Text(
                        text = "Tryptify",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOpen) {
                            // Closing search also clears the query so the
                            // home feed comes back.
                            searchViewModel.onQueryChange("")
                        }
                        val opening = !searchOpen
                        searchOpen = opening
                        focusOnOpen = opening
                    }) {
                        Icon(
                            if (searchOpen) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                            tint = if (searchOpen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    tf.monochrome.android.ui.downloads.DownloadTopBarIndicator(
                        activeCount = activeDownloads.size,
                        overallProgress = downloadProgress,
                        onClick = { showDownloadsMonitor = true },
                    )
                    IconButton(onClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigateTool(Screen.Profile) }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            )
        }

        // Everything below the bar, with the search floating over it.
        //
        // The field used to be a row in this column, which meant it took layout
        // space: the content started *below* it and clipped at its own top edge,
        // so rows vanished at a hard line instead of sliding under the glass.
        // Home worked out the fix first and kept its own hand-built Box, haze
        // source and AnimatedVisibility for it; that is SearchOverlay now, and
        // this is the last screen to stop having a private copy of it.
        //
        // The bar stays out while a query is live, not only while the search is
        // "open", so results never lose the field that produced them.
        SearchOverlay(
            open = searchOpen || hasSearchResults,
            query = searchQuery,
            onQueryChange = searchViewModel::onQueryChange,
            placeholder = "Search tracks, albums, artists, playlists…",
            onSubmit = searchViewModel::submitSearch,
            onClose = {
                searchViewModel.onQueryChange("")
                searchOpen = false
            },
            // Only on a genuine user open. This is a plain remember, not a
            // saveable, so coming back to a Home whose searchOpen was restored
            // true does not re-pop the keyboard over the results.
            autoFocus = focusOnOpen,
        ) { searchTopInset ->
        Column(modifier = Modifier.fillMaxSize()) {
        if (hasSearchResults) {
            SearchResultsContent(
                navController = navController,
                playerViewModel = playerViewModel,
                query = searchQuery,
                tracks = searchTracks,
                albums = searchAlbums,
                artists = searchArtists,
                playlistResults = searchPlaylists,
                isSearching = isSearching,
                selectedType = selectedType,
                onTypeSelected = searchViewModel::setSelectedType,
                selectedSource = selectedSource,
                onSourceSelected = searchViewModel::setSelectedSource,
                showSourceFilter = showSourceFilter,
                favoriteTrackIds = favoriteTrackIds,
                libraryPlaylists = libraryPlaylists,
                onLoadMore = searchViewModel::loadMore,
                isLoadingMore = isLoadingMore,
                endReached = endReached,
                searchError = searchError,
                onRetry = searchViewModel::submitSearch,
                // Recent-search history — previously only reachable from the
                // orphaned standalone SearchScreen; now shown when the Home
                // search is open with an empty query.
                emptyContent = {
                    SearchHistoryContent(
                        history = searchHistory,
                        onSelect = searchViewModel::selectHistoryQuery,
                        onClearHistory = searchViewModel::clearSearchHistory,
                    )
                },
            )
        } else {
            // ── Home IS the page list ───────────────────────────
            //
            // It used to be a feed: Play Radio, then Recently Played. Both are
            // gone — the history is still on Overview, which this list is one
            // tap from — because the swipe was the only way to reach six of the
            // seven pages and Home is where people start.
            //
            // The two banners sit above the list rather than inside it. They
            // are one dismissible row apiece, and keeping them out of the
            // LazyColumn means the list's own indices are the pages and nothing
            // else.
            Column(modifier = Modifier.fillMaxSize().padding(top = searchTopInset)) {
                val update = availableUpdate
                if (showUpdateBar && update != null) {
                    tf.monochrome.android.ui.components.WhatsNewBar(
                        title = "Version ${update.versionName} is available",
                        subtitle = "Tap to see the release on GitHub",
                        onOpen = {
                            settingsViewModel.dismissUpdate()
                            runCatching {
                                homeContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(update.releaseUrl),
                                    )
                                )
                            }
                        },
                        onDismiss = { settingsViewModel.dismissUpdate() },
                        onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                    )
                } else if (showWhatsNew) {
                    tf.monochrome.android.ui.components.WhatsNewBar(
                        title = "Updated to $whatsNewVersionName",
                        subtitle = "See what's new",
                        onOpen = {
                            settingsViewModel.markWhatsNewSeen()
                            navController.navigateSafe(
                                Screen.Settings.createRoute(
                                    tf.monochrome.android.ui.settings.SETTINGS_TAB_ABOUT
                                )
                            )
                        },
                        onDismiss = { settingsViewModel.markWhatsNewSeen() },
                        onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                    )
                }

                PageJumpList(
                    pages = pages,
                    onSelect = onSelectPage,
                    current = Screen.Home.route,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 160.dp),
                )
            }
        }
        }
        }
    }
}


// The About tab index used to be written down here as a literal and silently
// broke every time Settings was reordered. SETTINGS_TAB_ABOUT is derived from
// the tab list itself, so there is nothing left to keep in sync.
