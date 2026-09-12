package tf.monochrome.android.ui.navigation

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
import androidx.compose.runtime.mutableStateListOf
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
import tf.monochrome.android.ui.components.LocalGlassOverlayHost
import tf.monochrome.android.ui.components.GlassOverlayLayer
import tf.monochrome.android.ui.components.GlassOverlayHost
import tf.monochrome.android.ui.components.liquidGlass
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import tf.monochrome.android.ui.components.MiniPlayer
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

// The NavHost destinations the swipeable page list is drawn on. These are stubs
// (their `composable {}` bodies are empty) — the pager below draws the pages, and
// which page you are on is the pager's business, not the NavController's.
//
// This used to be the page list itself, hardcoded to Home / Discover / Library,
// with the Library sections in a second pager nested inside it. All three routes
// are kept even though only Home is ever navigated to now, so a back stack
// restored after process death onto "discover" or "library" still shows a pager.
private val pagerRoutes =
    setOf(Screen.Home.route, Screen.Discover.route, Screen.Library.route)

// Screens whose own controls run to the bottom edge, where the mini player would
// sit on top of them. The player and the mixer are the transport itself; Oxford
// puts CLIP / BAND SPLIT / EFFECT IN under the compressor and inflator faders.
// Everywhere else the mini player stays — this list is the whole exception.
private val miniPlayerHiddenRoutes = setOf(
    Screen.NowPlaying.route,
    Screen.Mixer.route,
    Screen.Oxford.route,
)

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
    val colorTransitionMs by playerViewModel.colorTransitionMs.collectAsStateWithLifecycle()
    val miniBlendMs = tf.monochrome.android.ui.theme.motionMillis(
        ColorBlend.millisFor(blendSeconds, colorTransitionMs)
    )
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

    // True when the user is on a destination the page pager is drawn on, rather
    // than a detail or tool screen pushed over it.
    val isOnMainTab = currentDestination?.route in pagerRoutes

    val showMiniPlayer = currentTrack != null
        && currentDestination?.route !in miniPlayerHiddenRoutes

    val scope = rememberCoroutineScope()

    // One pager over one flat list of pages. There used to be two — an outer one
    // hardcoded to Home / Discover / Library and an inner one over the Library's
    // sections — which is why Discover could not be reordered and why the
    // indicator below had to fold two axes onto one by hand.
    val settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel()
    val pageOrder by settingsViewModel.pageOrder.collectAsStateWithLifecycle()
    val hiddenPages by settingsViewModel.hiddenPages.collectAsStateWithLifecycle()
    val pages = remember(pageOrder, hiddenPages) { visiblePages(pageOrder, hiddenPages) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })

    // Keep the user on the same PAGE, not the same index, when the list changes
    // under them: hiding a page shortens it, so the index they were on now points
    // somewhere else or past the end, and reordering moves it. Both edits happen
    // in Settings with the pager off screen, so this lands before it is seen.
    var lastPageId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pages) {
        pagerState.scrollToPage(restoredPageIndex(pages, lastPageId))
    }
    LaunchedEffect(pagerState.currentPage, pages) {
        lastPageId = pages.getOrNull(pagerState.currentPage)
    }

    // Every jump to a page goes through here, and it matters that `scope` is
    // the nav host's rather than the calling screen's. The list that issues the
    // jump is inside the thing being navigated away from — a sheet that closes,
    // or Home, which the pager disposes on the way out — and a
    // rememberCoroutineScope dies with its composable, cancelling the scroll
    // part-way. The pager then settled wherever it had got to, which is why
    // tapping a distant page opened the wrong one.
    val selectPage: (String) -> Unit = { id ->
        val page = pages.indexOf(id)
        // scrollToPage, not animateScrollToPage: picking a page is a choice off
        // a list, not a drag, and sliding there sweeps the pager through every
        // page in between — Home to Downloads animated across five of them.
        // The slide was there to follow a finger, and there is no finger now.
        if (page >= 0) scope.launch { pagerState.scrollToPage(page) }
    }

    // One-shot landing route handed over by onboarding. Keyed on Unit and not on
    // `pages`: keying it there would re-run the landing every time the user
    // reordered or hid a page and yank them back to it.
    //
    // On the very first frame `pages` is still the default order, before DataStore
    // has emitted. This only fires straight out of onboarding, on an install whose
    // order IS the default unless settings sync raced it down — the same exposure
    // the previous version had.
    LaunchedEffect(Unit) {
        val route = initialRoute ?: return@LaunchedEffect
        val page = landingPageIndex(pages, route)
        if (page != null) pagerState.scrollToPage(page) else navController.navigateSafe(route)
    }

    // There is deliberately no swipe->NavController sync any more. It existed so
    // `tabRoutes[currentPage]` matched the current destination, and nothing read
    // that correspondence: no code outside this file navigates to the Discover or
    // Library routes, and `isOnMainTab`, `miniPlayerHiddenRoutes` and
    // `fullBleedRoute` only ask which KIND of destination this is. The NavHost
    // now simply stays on "home" while you swipe, and the pager state — declared
    // outside the `if (isOnMainTab)` block, so it survives the pager being torn
    // down for a detail screen — is the sole record of which page you are on.
    // That is already how the inner section pager worked.

    // Back goes to Home, and nowhere else. There is no swipe any more, so there
    // is no route to retrace: the only movement to undo is "I opened this page
    // from the list", and its undo is the list.
    //
    // Back never popped the NavController here and must not: back on a library
    // page used to pop to Home while the pager stayed put, so visually nothing
    // happened and the next Back exited the app with the page still on screen.
    //
    // Each page's scroll position rides along for free — `tabStateHolder` below
    // keeps every `rememberSaveable` in a page alive while it is off screen,
    // `rememberLazyListState` included, so a page returned to is where it was
    // left rather than at the top.
    //
    // Composed before the pager content below, so it registers first and
    // LibraryScreen's selection handler — composed later, inside a page — wins
    // the first back press while a selection is active.
    val homePage = homePageIndex(pages)
    BackHandler(enabled = isOnMainTab && pagerState.currentPage != homePage) {
        pages.getOrNull(homePage)?.let(selectPage)
    }

    val themeBackground = MaterialTheme.colorScheme.background

    val hazeState = rememberHazeState()

    // Where dialogs that want to be glass are drawn. They cannot be Dialog
    // windows: haze cannot reach across a window boundary, so a pane in one
    // blurs nothing. See GlassOverlay.
    val glassOverlayHost = remember { GlassOverlayHost() }

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
        // The genre map draws under the mini player rather than being
        // letterboxed above it: this Box is the app's haze source, so anything
        // stopping short of the bar leaves flat background behind it and the
        // bar's glass has nothing to blur. Running the map underneath gives the
        // glass real content to lens, and the map pads its own panel clear of
        // the bar.
        // Both maps run full-bleed under the mini player on purpose, so its
        // glass has real content to lens rather than a flat inset.
        //
        // The player joins them: it already insets itself, so reserving the bar
        // out here charged it twice — a second button bar of dead height on
        // 3-button navigation, which the height-bound artwork paid for.
        val fullBleedRoute = currentDestination?.route == Screen.GenreMap.route ||
            currentDestination?.route == Screen.WorldRadio.route ||
            currentDestination?.route == Screen.NowPlaying.route

        // Every screen runs *under* the mini player. Reserving the bar's height
        // out here letterboxed them: the strip behind the bar was flat theme
        // background, so the bar's glass had nothing but a solid colour to lens
        // and read as an opaque container however transparent it was set.
        //
        // The reserve moves into each screen's own scroll, published below as
        // [LocalMiniPlayerInset], where it is scrollable — content passes
        // behind the glass and the last row still comes clear of the bar. The
        // nav bar stays reserved out here, because that one is not glass and
        // nothing should ever be under it.
        val detailBottomInset = if (fullBleedRoute) 0.dp else navBarHeight

        // One SaveableStateHolder keeps each tab's subtree state (selected
        // Library sub-tab, LazyColumn scroll offsets, text field input, etc.)
        // alive across the pager being torn down when the user enters a detail
        // screen and rebuilt when they come back. Without this wrapper,
        // `rememberSaveable` inside the tabs loses its entry the moment
        // `isOnMainTab` flips false and the pager is dropped from composition,
        // so coming back to Library would reset to the Overview sub-tab and
        // scroll to the top.
        val tabStateHolder = rememberSaveableStateHolder()

        CompositionLocalProvider(
            LocalMiniPlayerInset provides if (showMiniPlayer) MINI_PLAYER_INSET else 0.dp,
            // So a song row anywhere in the app can show that it is the one
            // playing, without every list having to pass it down.
            LocalNowPlayingTrackId provides currentTrack?.id,
            LocalAppHaze provides hazeState,
            // Published for the whole app, so every floating sheet of glass on
            // an ordinary screen is the same material as the bar it sits beside
            // rather than the untouched defaults.
            LocalMiniPlayerGlass provides miniPlayerGlass,
            LocalGlassOverlayHost provides glassOverlayHost,
        ) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Pager for main tabs — fills entire screen
            if (isOnMainTab) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 0,
                    // Pages are chosen from the list on Home. The pager stays
                    // because SaveableStateProvider hangs off it — that is what
                    // keeps each page's scroll position while it is off screen
                    // — but it is driven, not dragged.
                    userScrollEnabled = false,
                ) { page ->
                    // getOrNull, not [page]: `pages` shrinks when a page is
                    // hidden, and the content lambda can be invoked for a stale
                    // index in the frame before the pager clamps currentPage to
                    // the new count. The old code indexed directly and got away
                    // with it only because reordering never changed the size.
                    val pageId = pages.getOrNull(page) ?: return@HorizontalPager
                    // Key and content both come off `pageId`. They used to be
                    // derived separately — the key from tabRoutes[page], the
                    // content from a `when (page)` — so a change to one silently
                    // desynced the other's saved state. SaveableStateProvider
                    // persists every rememberSaveable inside across recreation.
                    tabStateHolder.SaveableStateProvider(pageId) {
                        tf.monochrome.android.devedit.DevEditScreen(pageId) {
                            when (pageId) {
                                Screen.Home.route ->
                                    HomeScreen(
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        pages = pages,
                                        onSelectPage = selectPage,
                                    )
                                Screen.Discover.route ->
                                    DiscoverScreen(
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        pages = pages,
                                        onSelectPage = selectPage,
                                    )
                                // Everything else is a Library page.
                                // reconcilePageOrder drops ids this build does
                                // not know, so nothing else can arrive here.
                                else -> LibraryScreen(
                                    navController = navController,
                                    playerViewModel = playerViewModel,
                                    sectionId = pageId,
                                    pages = pages,
                                    onSelectPage = selectPage,
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
                // Pager hosts – content is drawn by the page list above.
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
                            viewModel = hiltViewModel(),
                            playerViewModel = playerViewModel
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
                            // The frost is on everywhere now. It used to be
                            // switched off here because detail screens stopped
                            // above the bar and left it nothing real to sample,
                            // so its base colour rendered as a solid slab —
                            // true at the time, and fixed by putting content
                            // under the bar rather than by hiding the frost.
                            //
                            // A full-bleed route is the exception, and the
                            // reason it is full-bleed: the map runs underneath
                            // the bar, so there IS content to blur and passing
                            // null was throwing it away — the one screen built
                            // to feed the frost was the one screen without it.
                            hazeState = hazeState,
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

        // Last child of the whole screen, so it covers the mini player and the
        // navigation bar as a modal must, and a sibling of the haze source
        // rather than a child of it -- a pane inside that box would be asking
        // to blur a picture it is already part of, which is the flat slab this
        // exists to avoid. Outside `isOnMainTab` too: the playlist pane opens
        // from album, artist and playlist detail as well as from the tabs.
        GlassOverlayLayer(
            host = glassOverlayHost,
            hazeState = hazeState,
            glass = miniPlayerGlass,
        )
    }
}

/**
 * Room the floating mini player needs at the bottom of a scrolling screen.
 *
 * Published as [LocalMiniPlayerInset] while a track is loaded, and zero
 * otherwise so no screen carries dead space for a bar that is not there.
 */
private val MINI_PLAYER_INSET = 72.dp
