package tf.monochrome.android.ui.navigation

import tf.monochrome.android.ui.theme.goToPage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.ui.components.liquidGlass
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import tf.monochrome.android.ui.components.MiniPlayer
import tf.monochrome.android.ui.components.SwipeToLibraryHint
import tf.monochrome.android.ui.components.SwipeHintPillHeight
import tf.monochrome.android.ui.components.swipeHintPillWidth
import tf.monochrome.android.ui.theme.ColorBlend
import tf.monochrome.android.ui.theme.DynamicColorScope
import tf.monochrome.android.ui.detail.AlbumDetailScreen
import tf.monochrome.android.ui.detail.ArtistDetailScreen
import tf.monochrome.android.ui.detail.LocalAlbumDetailScreen
import tf.monochrome.android.ui.detail.LocalArtistDetailScreen
import tf.monochrome.android.ui.detail.LocalGenreDetailScreen
import tf.monochrome.android.ui.eq.EqualizerScreen
import tf.monochrome.android.ui.eq.ParametricEqEditScreen
import tf.monochrome.android.ui.eq.ParametricEqScreen
import tf.monochrome.android.ui.discover.DiscoverScreen
import tf.monochrome.android.ui.discover.DiscoverShelfScreen
import tf.monochrome.android.ui.discover.GenreChartScreen
import tf.monochrome.android.ui.discover.GenreMapScreen
import tf.monochrome.android.ui.home.HomeScreen
import tf.monochrome.android.ui.mixer.MixerScreen
import tf.monochrome.android.ui.library.LibraryScreen
import tf.monochrome.android.ui.library.LIBRARY_SECTION_NAMES
import tf.monochrome.android.ui.library.librarySections
import tf.monochrome.android.ui.library.DownloadsScreen
import tf.monochrome.android.ui.library.PlaylistScreen
import tf.monochrome.android.ui.player.NowPlayingScreen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.library.FolderBrowserScreen
import tf.monochrome.android.ui.profile.ProfileScreen
import tf.monochrome.android.ui.stats.ListeningStatsScreen
import tf.monochrome.android.ui.stats.StatsScreen
import tf.monochrome.android.ui.search.SearchScreen
import tf.monochrome.android.ui.settings.SettingsScreen
import tf.monochrome.android.ui.carmode.CarModeScreen
import tf.monochrome.android.ui.debug.DebugLogScreen
import tf.monochrome.android.ui.crossfeed.CrossfeedScreen
import tf.monochrome.android.ui.crossfeed.CrossfeedViewModel
import tf.monochrome.android.ui.oxford.OxfordEffectsTabs
import tf.monochrome.android.ui.oxford.OxfordViewModel

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Discover : Screen("discover")
    data object GenreMap : Screen("discover/map")
    data object WorldRadio : Screen("discover/radio")
    data object DiscoverShelf : Screen("discover/shelf/{shelfId}") {
        fun createRoute(shelfId: String) = "discover/shelf/${android.net.Uri.encode(shelfId)}"
    }
    data object GenreChart : Screen("discover/chart/{genreId}?name={name}") {
        fun createRoute(genreId: String, name: String) =
            "discover/chart/${android.net.Uri.encode(genreId)}" +
                "?name=${android.net.Uri.encode(name)}"
    }
    data object Library : Screen("library")
    data object AlbumDetail : Screen("album/{albumId}") {
        fun createRoute(albumId: Long) = "album/$albumId"
    }
    data object ArtistDetail : Screen("artist/{artistId}?name={name}") {
        /**
         * [name] is the fallback identity. Some catalogue rows reach the player
         * with an artist name and an id of 0, and an id of 0 can never be
         * looked up — so the name rides along and the screen resolves from it
         * when there is nothing else to go on.
         */
        fun createRoute(artistId: Long, name: String? = null) =
            "artist/$artistId?name=${android.net.Uri.encode(name.orEmpty())}"
    }
    data object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
    data object Downloads : Screen("downloads")
    data object NowPlaying : Screen("now_playing")
    data object Settings : Screen("settings?tab={tab}") {
        fun createRoute(tab: Int = 0) = "settings?tab=$tab"
    }
    data object Equalizer : Screen("equalizer")
    data object ParametricEq : Screen("parametric_eq")
    data object ParametricEqEdit : Screen("parametric_eq_edit")
    data object Profile : Screen("profile")
    data object Stats : Screen("stats")
    data object ListeningStats : Screen("listening_stats")
    data object FolderBrowser : Screen("folder/{folderPath}") {
        // Uri.encode (not URLEncoder) so spaces become %20 not '+', and every
        // reserved char (incl. '/' and '%') is percent-encoded. Navigation
        // decodes the argument exactly once when it reaches the destination,
        // so the read site must NOT decode again.
        fun createRoute(folderPath: String) = "folder/${android.net.Uri.encode(folderPath)}"
    }
    data object LocalAlbumDetail : Screen("local_album/{albumId}") {
        fun createRoute(albumId: Long) = "local_album/$albumId"
    }
    data object LocalArtistDetail : Screen("local_artist/{artistId}") {
        fun createRoute(artistId: Long) = "local_artist/$artistId"
    }
    data object LocalGenreDetail : Screen("local_genre/{genre}") {
        fun createRoute(genre: String) = "local_genre/${android.net.Uri.encode(genre)}"
    }
    data object Mixer : Screen("mixer")
    data object CarMode : Screen("car_mode")
    data object Oxford : Screen("oxford?tab={tab}") {
        /** tab: 0 = Compressor, 1 = Inflator. */
        fun createRoute(tab: Int = 0) = "oxford?tab=$tab"
    }
    data object Crossfeed : Screen("crossfeed")
    data object DebugLog : Screen("debug_log")
    data object LyricsFxStudio : Screen("lyrics_fx_studio")
    data object AtmosRenderer : Screen("atmos_renderer")
    data object HrtfDatabase : Screen("hrtf_database")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

// The three main tab screens, in pager order. Discover sits between Home and
// Library because it is the browsing step between "what am I doing now" and
// "what do I already own".
private val tabRoutes =
    listOf(Screen.Home.route, Screen.Discover.route, Screen.Library.route)

@Composable
fun MonochromeNavHost(initialRoute: String? = null) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val playerViewModel: PlayerViewModel = hiltViewModel()

    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    // Mini-player glass settings (its own blob; Studio › Mini Player tab).
    val miniPlayerGlass by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()

    // The mini player's cover changes track at the same speed its album tint
    // does — both come off "Blend Between Tracks" — and tells a skip from a
    // song ending the same way the full player does.
    val blendSeconds by playerViewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val miniBlendMs = tf.monochrome.android.ui.theme.motionMillis(ColorBlend.millisFor(blendSeconds))
    val userTrackChanges by playerViewModel.userTrackChanges.collectAsStateWithLifecycle()

    // Position/duration tick every 250 ms. Keep them as State<Long> and read
    // only inside the draw-scope progress lambda below — reading `.value` here
    // would recompose the entire nav host (pager + HomeScreen + LibraryScreen)
    // four times a second.
    val positionState = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val durationState = playerViewModel.durationMs.collectAsStateWithLifecycle()
    val progressProvider = remember(positionState, durationState) {
        {
            val d = durationState.value
            if (d > 0) positionState.value.toFloat() / d.toFloat() else 0f
        }
    }

    // True when the user is on one of the three main tab screens
    val isOnMainTab = currentDestination?.route in tabRoutes

    val showMiniPlayer = currentTrack != null
        && currentDestination?.route != Screen.NowPlaying.route
        && currentDestination?.route != Screen.Mixer.route

    // Pager state for the main tabs
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabRoutes.size })
    val scope = rememberCoroutineScope()
    // Tab changes slide normally; with "Disable animations" on they jump.
    val animateTabs = !tf.monochrome.android.ui.theme.reduceMotion()

    // Library's own section pager lives up here rather than inside LibraryScreen
    // so the top-bar indicator can count and track every page in the app — Home
    // plus each Library section — instead of only the Home↔Library split.
    val settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel()
    val libraryTabOrder by settingsViewModel.libraryTabOrder.collectAsStateWithLifecycle()
    val librarySectionIds = remember(libraryTabOrder) { librarySections(libraryTabOrder) }
    val librarySectionPager = rememberPagerState(pageCount = { librarySectionIds.size })

    // One-shot landing route handed over by onboarding ("library" lands on
    // the Library pager tab; anything else is a plain navigation target).
    LaunchedEffect(Unit) {
        when (initialRoute) {
            null -> Unit
            Screen.Library.route -> {
                pagerState.scrollToPage(tabRoutes.indexOf(Screen.Library.route))
                navController.navigate(Screen.Library.route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            else -> navController.navigateSafe(initialRoute)
        }
    }

    // When the user swipes the pager, keep the NavController in sync.
    LaunchedEffect(pagerState.currentPage) {
        if (isOnMainTab) {
            val route = tabRoutes[pagerState.currentPage]
            if (currentDestination?.route != route) {
                navController.navigate(route) {
                    popUpTo(Screen.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    // Back on a non-first main tab returns the pager to Home instead of popping
    // the nav out from under it. Previously back on the Library tab popped the
    // NavController to Home while the pager stayed on Library — visually nothing
    // happened, and the next back exited the app with Library still on screen.
    BackHandler(enabled = isOnMainTab && pagerState.currentPage != 0) {
        scope.launch { pagerState.goToPage(0, animateTabs) }
    }

    val themeBackground = MaterialTheme.colorScheme.background

    val hazeState = rememberHazeState()

    // System bar heights for overlapping content
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 0: Background ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(themeBackground)
        )

        // ── Layer 1: Full-screen content (draws behind bars) ─────────────
        // Detail screens reserve nav bar + mini-player height so content
        // isn't hidden behind the floating mini player. Main tabs (pager)
        // handle their own bottom contentPadding already.
        val miniPlayerReserve = 72.dp
        // The genre map draws under the mini player rather than being
        // letterboxed above it: this Box is the app's haze source, so anything
        // stopping short of the bar leaves flat background behind it and the
        // bar's glass has nothing to blur. Running the map underneath gives the
        // glass real content to lens, and the map pads its own panel clear of
        // the bar.
        // Both maps run full-bleed under the mini player on purpose, so its
        // glass has real content to lens rather than a flat inset.
        val fullBleedRoute = currentDestination?.route == Screen.GenreMap.route ||
            currentDestination?.route == Screen.WorldRadio.route
        val detailBottomInset = when {
            fullBleedRoute -> 0.dp
            else -> navBarHeight + if (showMiniPlayer) miniPlayerReserve else 0.dp
        }

        // One SaveableStateHolder keeps each tab's subtree state (selected
        // Library sub-tab, LazyColumn scroll offsets, text field input, etc.)
        // alive across the pager being torn down when the user enters a detail
        // screen and rebuilt when they come back. Without this wrapper,
        // `rememberSaveable` inside the tabs loses its entry the moment
        // `isOnMainTab` flips false and the pager is dropped from composition,
        // so coming back to Library would reset to the Overview sub-tab and
        // scroll to the top.
        val tabStateHolder = rememberSaveableStateHolder()

        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Pager for main tabs — fills entire screen
            if (isOnMainTab) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0
                ) { page ->
                    // Key by route, not page index — `Screen.Home.route` /
                    // `Screen.Library.route` survive even if the pager order
                    // ever changes. SaveableStateProvider persists every
                    // rememberSaveable inside the lambda across pager recreate.
                    val key = tabRoutes[page]
                    tabStateHolder.SaveableStateProvider(key) {
                        when (page) {
                            0 -> tf.monochrome.android.devedit.DevEditScreen("home") {
                                HomeScreen(navController = navController, playerViewModel = playerViewModel)
                            }
                            1 -> tf.monochrome.android.devedit.DevEditScreen("discover") {
                                DiscoverScreen(
                                    navController = navController,
                                    playerViewModel = playerViewModel,
                                )
                            }
                            2 -> tf.monochrome.android.devedit.DevEditScreen("library") {
                                LibraryScreen(
                                    navController = navController,
                                    playerViewModel = playerViewModel,
                                    sections = librarySectionIds,
                                    sectionPager = librarySectionPager,
                                )
                            }
                        }
                    }
                }
            }

            // Detail / overlay screens
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(bottom = detailBottomInset),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() }
            ) {
                // Tab stubs – content is rendered by the pager above
                composable(Screen.Home.route) { }
                composable(Screen.Discover.route) { }
                composable(Screen.GenreMap.route) {
                    tf.monochrome.android.devedit.DevEditScreen("genre_map") {
                        GenreMapScreen(
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }
                composable(Screen.WorldRadio.route) {
                    tf.monochrome.android.devedit.DevEditScreen("world_radio") {
                        tf.monochrome.android.ui.discover.WorldRadioScreen(
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }
                composable(Screen.Library.route) { }

                composable(
                    route = Screen.DiscoverShelf.route,
                    arguments = listOf(navArgument("shelfId") { type = NavType.StringType })
                ) { entry ->
                    tf.monochrome.android.devedit.DevEditScreen("discover_shelf") {
                        DiscoverShelfScreen(
                            shelfId = entry.arguments?.getString("shelfId").orEmpty(),
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }

                composable(
                    route = Screen.GenreChart.route,
                    arguments = listOf(
                        navArgument("genreId") { type = NavType.StringType },
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    )
                ) { entry ->
                    tf.monochrome.android.devedit.DevEditScreen("genre_chart") {
                        GenreChartScreen(
                            genreId = entry.arguments?.getString("genreId").orEmpty(),
                            genreName = entry.arguments?.getString("name").orEmpty(),
                            navController = navController,
                            playerViewModel = playerViewModel,
                        )
                    }
                }

                composable(
                    route = Screen.AlbumDetail.route,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType })
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("album_detail") {
                        AlbumDetailScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(
                    route = Screen.ArtistDetail.route,
                    arguments = listOf(
                        navArgument("artistId") { type = NavType.LongType },
                        navArgument("name") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    )
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("artist_detail") {
                        ArtistDetailScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(
                    route = Screen.PlaylistDetail.route,
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("playlist_detail") {
                        PlaylistScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
                composable(Screen.NowPlaying.route) {
                    NowPlayingScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(
                    route = Screen.Settings.route,
                    arguments = listOf(navArgument("tab") {
                        type = NavType.IntType
                        defaultValue = 0
                    })
                ) { backStackEntry ->
                    val tab = backStackEntry.arguments?.getInt("tab") ?: 0
                    SettingsScreen(navController = navController, initialTab = tab)
                }
                composable(Screen.Equalizer.route) {
                    tf.monochrome.android.devedit.DevEditScreen("equalizer") {
                        EqualizerScreen(navController = navController)
                    }
                }
                composable(Screen.ParametricEq.route) {
                    tf.monochrome.android.devedit.DevEditScreen("parametric_eq") {
                        ParametricEqScreen(navController = navController)
                    }
                }
                composable(Screen.ParametricEqEdit.route) {
                    tf.monochrome.android.devedit.DevEditScreen("parametric_eq_edit") {
                        ParametricEqEditScreen(navController = navController)
                    }
                }
                composable(Screen.Mixer.route) {
                    tf.monochrome.android.devedit.DevEditScreen("mixer") {
                        MixerScreen(
                            navController = navController,
                            viewModel = hiltViewModel()
                        )
                    }
                }
                composable(Screen.CarMode.route) {
                    tf.monochrome.android.devedit.DevEditScreen("car_mode") {
                        CarModeScreen(navController = navController)
                    }
                }
                composable(Screen.DebugLog.route) {
                    tf.monochrome.android.devedit.DevEditScreen("debug_log") {
                        DebugLogScreen(navController = navController)
                    }
                }
                composable(Screen.LyricsFxStudio.route) {
                    tf.monochrome.android.devedit.DevEditScreen("lyrics_fx_studio") {
                        tf.monochrome.android.ui.settings.LyricsFxStudioScreen(navController = navController)
                    }
                }
                composable(Screen.AtmosRenderer.route) {
                    tf.monochrome.android.devedit.DevEditScreen("atmos_renderer") {
                        tf.monochrome.android.ui.settings.AtmosRendererScreen(navController = navController)
                    }
                }
                composable(Screen.HrtfDatabase.route) {
                    tf.monochrome.android.ui.settings.HrtfDatabaseScreen(navController = navController)
                }
                composable(
                    route = Screen.Oxford.route,
                    arguments = listOf(navArgument("tab") {
                        type = NavType.IntType
                        defaultValue = 0
                    })
                ) { backStackEntry ->
                    val tab = backStackEntry.arguments?.getInt("tab") ?: 0
                    val vm: OxfordViewModel = hiltViewModel()
                    tf.monochrome.android.devedit.DevEditScreen("oxford") {
                        OxfordEffectsTabs(
                            inflator = vm.inflator,
                            compressor = vm.compressor,
                            initialTab = tab,
                            onBack = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize().padding(top = statusBarHeight),
                        )
                    }
                }
                composable(Screen.Crossfeed.route) {
                    val vm: CrossfeedViewModel = hiltViewModel()
                    tf.monochrome.android.devedit.DevEditScreen("crossfeed") {
                        CrossfeedScreen(
                            effect = vm.crossfeed,
                            onBack = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize().padding(top = statusBarHeight),
                        )
                    }
                }
                composable(Screen.Downloads.route) {
                    tf.monochrome.android.devedit.DevEditScreen("downloads") {
                        DownloadsScreen(navController = navController)
                    }
                }
                composable(Screen.Profile.route) {
                    tf.monochrome.android.devedit.DevEditScreen("profile") {
                        ProfileScreen(navController = navController)
                    }
                }
                composable(Screen.Stats.route) {
                    tf.monochrome.android.devedit.DevEditScreen("stats") {
                        StatsScreen(navController = navController)
                    }
                }
                composable(Screen.ListeningStats.route) {
                    tf.monochrome.android.devedit.DevEditScreen("listening_stats") {
                        ListeningStatsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(
                    route = Screen.FolderBrowser.route,
                    arguments = listOf(navArgument("folderPath") { type = NavType.StringType })
                ) { backStackEntry ->
                    // Navigation already URL-decoded this once; decoding again
                    // (the old URLDecoder call) crashed on folders containing
                    // '%' and turned '+' into a space, opening the wrong folder.
                    val folderPath = backStackEntry.arguments?.getString("folderPath") ?: ""
                    tf.monochrome.android.devedit.DevEditScreen("folder_browser") {
                        FolderBrowserScreen(
                            folderPath = folderPath,
                            navController = navController,
                            onPlayTrack = { track, queue ->
                                playerViewModel.playUnifiedTrack(track, queue)
                            },
                            onPlayAll = { tracks ->
                                playerViewModel.playAllUnified(tracks)
                            },
                            onAddToQueue = { track ->
                                playerViewModel.addUnifiedToQueue(listOf(track))
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }
                composable(
                    route = Screen.LocalAlbumDetail.route,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType })
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("local_album_detail") {
                        LocalAlbumDetailScreen(
                            navController = navController,
                            onPlayTrack = { track, queue ->
                                playerViewModel.playUnifiedTrack(track, queue)
                            },
                            onPlayAll = { tracks ->
                                playerViewModel.playAllUnified(tracks)
                            },
                            onShuffleAll = { tracks ->
                                playerViewModel.shufflePlayUnified(tracks)
                            },
                            onAddToQueue = { track ->
                                playerViewModel.addUnifiedToQueue(listOf(track))
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }
                composable(
                    route = Screen.LocalArtistDetail.route,
                    arguments = listOf(navArgument("artistId") { type = NavType.LongType })
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("local_artist_detail") {
                        LocalArtistDetailScreen(
                            navController = navController,
                            onPlayTrack = { track, queue ->
                                playerViewModel.playUnifiedTrack(track, queue)
                            },
                            onPlayAll = { tracks ->
                                playerViewModel.playAllUnified(tracks)
                            },
                            onShuffleAll = { tracks ->
                                playerViewModel.shufflePlayUnified(tracks)
                            },
                            onAddToQueue = { track ->
                                playerViewModel.addUnifiedToQueue(listOf(track))
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }
                composable(
                    route = Screen.LocalGenreDetail.route,
                    arguments = listOf(navArgument("genre") { type = NavType.StringType })
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("local_genre_detail") {
                        LocalGenreDetailScreen(
                            navController = navController,
                            onPlayTrack = { track, queue ->
                                playerViewModel.playUnifiedTrack(track, queue)
                            },
                            onPlayAll = { tracks ->
                                playerViewModel.playAllUnified(tracks)
                            },
                            onShuffleAll = { tracks ->
                                playerViewModel.shufflePlayUnified(tracks)
                            },
                            onAddToQueue = { track ->
                                playerViewModel.addUnifiedToQueue(listOf(track))
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
        }

        // ── Layer 1.5: page indicator ───────────────────────────────
        // Compact glass pill centred on the TopAppBar's own centre line, in the
        // free gap between the screens' titles and their action icons. Drawn
        // OVER the pager, not behind it: Haze can only blur content already
        // drawn this frame, so a pill under the pager loses its frost entirely.
        if (isOnMainTab) {
            CompositionLocalProvider(
                tf.monochrome.android.ui.player.LocalPlayerGlass provides miniPlayerGlass,
            ) {
                // Home, Discover, then one slot per Library section.
                val libraryPage = tabRoutes.lastIndex
                val indicatorPages = libraryPage + librarySectionIds.size
                SwipeToLibraryHint(
                    pageCount = indicatorPages,
                    progressProvider = {
                        // Flattens the two nested pagers onto one axis: Home 0,
                        // Discover 1, the local library 2, each further section
                        // 3, 4, … The inner pager only contributes once the
                        // outer one is on Library, and it is faded in by how far
                        // that crossing has got, so the worm stays continuous
                        // through the swipe instead of jumping when Library was
                        // left on a later section.
                        val outer = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val inner = librarySectionPager.currentPage +
                            librarySectionPager.currentPageOffsetFraction
                        outer + inner * (outer - (libraryPage - 1)).coerceIn(0f, 1f)
                    },
                    // Tap walks forward one page and wraps at the end. Both
                    // pagers animate, so the worm plays the same way it does
                    // under a finger.
                    onClick = {
                        scope.launch {
                            when {
                                pagerState.currentPage < libraryPage -> {
                                    if (pagerState.currentPage + 1 == libraryPage) {
                                        librarySectionPager.scrollToPage(0)
                                    }
                                    pagerState.goToPage(pagerState.currentPage + 1, animateTabs)
                                }
                                librarySectionPager.currentPage + 1 < librarySectionIds.size ->
                                    librarySectionPager.goToPage(
                                        librarySectionPager.currentPage + 1,
                                        animateTabs,
                                    )
                                else -> pagerState.goToPage(0, animateTabs)
                            }
                        }
                    },
                    onClickLabel = when {
                        pagerState.currentPage < libraryPage ->
                            "Open " + (tabRoutes.getOrNull(pagerState.currentPage + 1)
                                ?.replaceFirstChar { it.uppercase() } ?: "next tab")
                        librarySectionPager.currentPage + 1 < librarySectionIds.size ->
                            "Open " + (LIBRARY_SECTION_NAMES[
                                librarySectionIds[librarySectionPager.currentPage + 1]
                            ] ?: "next section")
                        else -> "Open Home"
                    },
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // The TopAppBar pads itself for the status bar and is
                        // 64.dp tall, so this drops the pill onto its centre.
                        .padding(top = statusBarHeight + (64.dp - SwipeHintPillHeight) / 2)
                        .size(
                            width = swipeHintPillWidth(indicatorPages),
                            height = SwipeHintPillHeight,
                        ),
                )
            }
        }

        // ── Layer 2: Navigation bar + mini player (overlays content) ──
        if (isOnMainTab) {
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                // Mini player — sits below nav bar with its own space
                if (showMiniPlayer) {
                    // Mini player follows the album art (dynamic colours), while
                    // the menus around it stay on the base theme.
                    DynamicColorScope {
                        CompositionLocalProvider(
                            tf.monochrome.android.ui.player.LocalPlayerGlass provides miniPlayerGlass,
                        ) {
                            MiniPlayer(
                                track = currentTrack,
                                isPlaying = isPlaying,
                                progressProvider = progressProvider,
                                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                onSkipNextClick = { playerViewModel.skipToNext() },
                                onSkipPreviousClick = { playerViewModel.skipToPrevious() },
                                onClick = { navController.navigateTool(Screen.NowPlaying) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                                hazeState = hazeState,
                                blendMillis = miniBlendMs,
                                userTrackChanges = userTrackChanges,
                            )
                        }
                    }
                }

                // Fill the system nav bar area
                Spacer(modifier = Modifier.height(navBarHeight))
            }
        } else if (showMiniPlayer) {
            // Mini player on non-tab screens — pad above system nav bar
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight)
            ) {
                DynamicColorScope {
                    CompositionLocalProvider(
                        tf.monochrome.android.ui.player.LocalPlayerGlass provides miniPlayerGlass,
                    ) {
                        MiniPlayer(
                            track = currentTrack,
                            isPlaying = isPlaying,
                            progressProvider = progressProvider,
                            onPlayPauseClick = { playerViewModel.togglePlayPause() },
                            onSkipNextClick = { playerViewModel.skipToNext() },
                            onSkipPreviousClick = { playerViewModel.skipToPrevious() },
                            onClick = { navController.navigateTool(Screen.NowPlaying) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                            // No frost on detail screens (Settings, EQ, …):
                            // they stop above the mini player, so the frost has
                            // nothing real to sample there and its base colour
                            // renders as a solid bar. Null skips the frost
                            // layer — the glass slab floats clean over the
                            // screen's own content.
                            //
                            // A full-bleed route is the exception, and the
                            // reason it is full-bleed: the map runs underneath
                            // the bar, so there IS content to blur and passing
                            // null was throwing it away — the one screen built
                            // to feed the frost was the one screen without it.
                            hazeState = if (fullBleedRoute) hazeState else null,
                            blendMillis = miniBlendMs,
                            userTrackChanges = userTrackChanges,
                        )
                    }
                }
            }
        }

        // ── Layer 3: Download progress pill + monitor (global chrome) ──
        val downloadCenter: tf.monochrome.android.ui.downloads.DownloadCenterViewModel = hiltViewModel()
        val activeDownloads by downloadCenter.active.collectAsStateWithLifecycle()
        var pillHidden by rememberSaveable { mutableStateOf(false) }
        var showDownloadsMonitor by rememberSaveable { mutableStateOf(false) }
        // Re-show the pill whenever a fresh batch of downloads begins.
        LaunchedEffect(activeDownloads.isNotEmpty()) {
            if (activeDownloads.isNotEmpty()) pillHidden = false
        }
        val onChromeScreen = currentDestination?.route != Screen.NowPlaying.route &&
            currentDestination?.route != Screen.Mixer.route &&
            // Flow is full-bleed: a floating pill would sit over the artwork
            // and the action rail, which is exactly the chrome it does without.
            !fullBleedRoute
        if (onChromeScreen && activeDownloads.isNotEmpty() && !pillHidden) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + if (showMiniPlayer) 80.dp else 12.dp)
                    .padding(horizontal = 8.dp)
            ) {
                tf.monochrome.android.ui.downloads.DownloadProgressPill(
                    downloads = activeDownloads,
                    onClick = { showDownloadsMonitor = true },
                    onHide = { pillHidden = true },
                )
            }
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
    }
}
