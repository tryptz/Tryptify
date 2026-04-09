package tf.monochrome.android.ui.navigation

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import tf.monochrome.android.ui.detail.AlbumDetailScreen
import tf.monochrome.android.ui.detail.ArtistDetailScreen
import tf.monochrome.android.ui.detail.LocalAlbumDetailScreen
import tf.monochrome.android.ui.detail.LocalArtistDetailScreen
import tf.monochrome.android.ui.detail.MixScreen
import tf.monochrome.android.ui.eq.EqualizerScreen
import tf.monochrome.android.ui.home.HomeScreen
import tf.monochrome.android.ui.mixer.MixerScreen
import tf.monochrome.android.ui.library.LibraryScreen
import tf.monochrome.android.ui.library.DownloadsScreen
import tf.monochrome.android.ui.library.PlaylistScreen
import tf.monochrome.android.ui.player.NowPlayingScreen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.library.FolderBrowserScreen
import tf.monochrome.android.ui.profile.ProfileScreen
import tf.monochrome.android.ui.stats.StatsScreen
import tf.monochrome.android.ui.search.SearchScreen
import tf.monochrome.android.ui.settings.SettingsScreen
import tf.monochrome.android.ui.carmode.CarModeScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object AlbumDetail : Screen("album/{albumId}") {
        fun createRoute(albumId: Long) = "album/$albumId"
    }
    data object ArtistDetail : Screen("artist/{artistId}") {
        fun createRoute(artistId: Long) = "artist/$artistId"
    }
    data object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
    data object Mix : Screen("mix/{mixId}") {
        fun createRoute(mixId: String) = "mix/$mixId"
    }
    data object Downloads : Screen("downloads")
    data object NowPlaying : Screen("now_playing")
    data object Settings : Screen("settings?tab={tab}") {
        fun createRoute(tab: Int = 0) = "settings?tab=$tab"
    }
    data object Equalizer : Screen("equalizer")
    data object Profile : Screen("profile")
    data object Stats : Screen("stats")
    data object FolderBrowser : Screen("folder/{folderPath}") {
        fun createRoute(folderPath: String) = "folder/${java.net.URLEncoder.encode(folderPath, "UTF-8")}"
    }
    data object LocalAlbumDetail : Screen("local_album/{albumId}") {
        fun createRoute(albumId: Long) = "local_album/$albumId"
    }
    data object LocalArtistDetail : Screen("local_artist/{artistId}") {
        fun createRoute(artistId: Long) = "local_artist/$artistId"
    }
    data object Mixer : Screen("mixer")
    data object CarMode : Screen("car_mode")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

// The two main tab screens, in pager order
private val tabRoutes = listOf(Screen.Home.route, Screen.Library.route)

@Composable
fun MonochromeNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val playerViewModel: PlayerViewModel = hiltViewModel()

    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val positionMs by playerViewModel.positionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()

    // True when the user is on one of the three main tab screens
    val isOnMainTab = currentDestination?.route in tabRoutes

    val showMiniPlayer = currentTrack != null
        && currentDestination?.route != Screen.NowPlaying.route
        && currentDestination?.route != Screen.Mixer.route

    // Pager state for the two main tabs
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()

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
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Pager for main tabs — fills entire screen
            if (isOnMainTab) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(navController = navController, playerViewModel = playerViewModel)
                        1 -> LibraryScreen(navController = navController, playerViewModel = playerViewModel)
                    }
                }
            }

            // Detail / overlay screens
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() }
            ) {
                // Tab stubs – content is rendered by the pager above
                composable(Screen.Home.route) { }
                composable(Screen.Library.route) { }

                composable(
                    route = Screen.AlbumDetail.route,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType })
                ) {
                    AlbumDetailScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(
                    route = Screen.ArtistDetail.route,
                    arguments = listOf(navArgument("artistId") { type = NavType.LongType })
                ) {
                    ArtistDetailScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(
                    route = Screen.PlaylistDetail.route,
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                ) {
                    PlaylistScreen(navController = navController, playerViewModel = playerViewModel)
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
                    EqualizerScreen(navController = navController)
                }
                composable(Screen.Mixer.route) {
                    MixerScreen(
                        navController = navController,
                        viewModel = hiltViewModel()
                    )
                }
                composable(Screen.CarMode.route) {
                    CarModeScreen(navController = navController)
                }
                composable(Screen.Downloads.route) {
                    DownloadsScreen(navController = navController)
                }
                composable(
                    route = Screen.Mix.route,
                    arguments = listOf(navArgument("mixId") { type = NavType.StringType })
                ) {
                    MixScreen(navController = navController, playerViewModel = playerViewModel)
                }
                composable(Screen.Profile.route) {
                    ProfileScreen(navController = navController)
                }
                composable(Screen.Stats.route) {
                    StatsScreen(navController = navController)
                }
                composable(
                    route = Screen.FolderBrowser.route,
                    arguments = listOf(navArgument("folderPath") { type = NavType.StringType })
                ) { backStackEntry ->
                    val folderPath = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("folderPath") ?: "", "UTF-8"
                    )
                    FolderBrowserScreen(
                        folderPath = folderPath,
                        navController = navController,
                        onPlayTrack = { track, queue ->
                            playerViewModel.playUnifiedTrack(track, queue)
                        },
                        onPlayAll = { tracks ->
                            playerViewModel.playAllUnified(tracks)
                        }
                    )
                }
                composable(
                    route = Screen.LocalAlbumDetail.route,
                    arguments = listOf(navArgument("albumId") { type = NavType.LongType })
                ) {
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
                        }
                    )
                }
                composable(
                    route = Screen.LocalArtistDetail.route,
                    arguments = listOf(navArgument("artistId") { type = NavType.LongType })
                ) {
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
                        }
                    )
                }
            }
        }

        // ── Layer 2: Navigation bar + mini player (overlays content) ──
        if (isOnMainTab) {
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                // Mini player — sits below nav bar with its own space
                if (showMiniPlayer) {
                    MiniPlayer(
                        track = currentTrack,
                        isPlaying = isPlaying,
                        progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onSkipNextClick = { playerViewModel.skipToNext() },
                        onSkipPreviousClick = { playerViewModel.skipToPrevious() },
                        onClick = { navController.navigate(Screen.NowPlaying.route) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        hazeState = hazeState
                    )
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
                MiniPlayer(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                    onPlayPauseClick = { playerViewModel.togglePlayPause() },
                    onSkipNextClick = { playerViewModel.skipToNext() },
                    onSkipPreviousClick = { playerViewModel.skipToPrevious() },
                    onClick = { navController.navigate(Screen.NowPlaying.route) },
                    hazeState = hazeState
                )
            }
        }
    }
}
