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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import tf.monochrome.android.ui.theme.goToPage
import tf.monochrome.android.ui.theme.reduceMotion
import tf.monochrome.android.ui.theme.DynamicColorScope
import tf.monochrome.android.ui.detail.AlbumDetailScreen
import tf.monochrome.android.ui.detail.ArtistDetailScreen
import tf.monochrome.android.ui.detail.LocalAlbumDetailScreen
import tf.monochrome.android.ui.detail.LocalArtistDetailScreen
import tf.monochrome.android.ui.detail.LocalFacet
import tf.monochrome.android.ui.detail.LocalFacetDetailScreen
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
import tf.monochrome.android.ui.theme.motionMillis
import tf.monochrome.android.ui.settings.SettingsScreen
import tf.monochrome.android.ui.carmode.CarModeScreen
import tf.monochrome.android.ui.debug.DebugLogScreen
import tf.monochrome.android.ui.crossfeed.CrossfeedScreen
import tf.monochrome.android.ui.crossfeed.CrossfeedViewModel
import tf.monochrome.android.ui.oxford.OxfordEffectsTabs
import tf.monochrome.android.ui.oxford.OxfordViewModel
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Discover : Screen("discover")
    data object GenreMap : Screen("discover/map")
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
    /**
     * One value of a library tag — a genre, an album artist, a composer, a
     * year — and the tracks under it.
     *
     * This was `local_genre/{genre}`. The facet moved into the path rather
     * than four near-identical routes being added beside it, because the
     * screen and its view model are the same four times over; only the query
     * differs. [LocalFacet.fromKey] decides what an unknown segment means, so
     * a stale link opens a genre instead of crashing.
     */
    data object LocalFacetDetail : Screen("local_facet/{facet}/{value}") {
        fun createRoute(facet: LocalFacet, value: String) =
            "local_facet/${facet.key}/${android.net.Uri.encode(value)}"
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

// Screens whose own controls run to the bottom edge, where the tab bar and the
// mini player would sit on top of them. The player and the mixer are the
// transport itself; Oxford puts CLIP / BAND SPLIT / EFFECT IN under the
// compressor and inflator faders; car mode is a full-screen driving surface
// with its own transport. Everywhere else the chrome stays — on pushed screens
// too, the way Apple Music keeps its tab bar — and this list is the whole
// exception.
internal val chromeHiddenRoutes = setOf(
    Screen.NowPlaying.route,
    Screen.Mixer.route,
    Screen.Oxford.route,
    Screen.CarMode.route,
)

@Composable
fun MonochromeNavHost(initialRoute: String? = null) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val playerViewModel: PlayerViewModel = hiltViewModel()

    // TIDAL could not play a song Qobuz has. Asked here, at the root, so the
    // question reaches the listener wherever they are — the skip that caused
    // it may have come from the notification or a track ending on its own.
    val qobuzOffer by playerViewModel.qobuzOffer.collectAsStateWithLifecycle()
    qobuzOffer?.let { offer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = playerViewModel::dismissQobuzOffer,
            title = { androidx.compose.material3.Text(stringResource(R.string.tidal_offer_title)) },
            text = {
                androidx.compose.material3.Text(
                    stringResource(R.string.tidal_offer_body, offer.title, offer.artist),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = playerViewModel::acceptQobuzOffer) {
                    androidx.compose.material3.Text(stringResource(R.string.play_from_qobuz))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = playerViewModel::dismissQobuzOffer) {
                    androidx.compose.material3.Text(stringResource(R.string.action_skip))
                }
            },
        )
    }

    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    // Mini-player glass settings (its own blob; Studio › Mini Player tab).
    val miniPlayerGlass by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()

    // The mini player's cover changes track at the same speed its album tint
    // does — both come off "Color transition" — and tells a skip from a song
    // ending the same way the full player does.
    val colorTransitionMs by playerViewModel.colorTransitionMs.collectAsStateWithLifecycle()
    val miniBlendMs = tf.monochrome.android.ui.theme.motionMillis(
        ColorBlend.millisFor(colorTransitionMs)
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

    // Null before the first destination is placed, which is not a reason to
    // hide anything.
    val showChrome = currentDestination?.route !in chromeHiddenRoutes
    val showMiniPlayer = currentTrack != null && showChrome

    val scope = rememberCoroutineScope()

    // One pager over one flat list of pages. There used to be two — an outer one
    // hardcoded to Home / Discover / Library and an inner one over the Library's
    // sections — which is why Discover could not be reordered and why the
    // indicator below had to fold two axes onto one by hand.
    val settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel()
    val pageOrder by settingsViewModel.pageOrder.collectAsStateWithLifecycle()
    val hiddenPages by settingsViewModel.hiddenPages.collectAsStateWithLifecycle()
    // The tabs and the mini player take turns instead of stacking — see TabChrome.
    val miniPlayerHideWithTabs by settingsViewModel.miniPlayerHideWithTabs.collectAsStateWithLifecycle()
    // The two pages between Home and Library in the nav bar.
    val navBarSlots by settingsViewModel.navBarSlots.collectAsStateWithLifecycle()
    // The pages the user can show or hide, then Search, which is always there:
    // it is the round button beside the tab bar, not one of the ordered pages.
    val pages = remember(pageOrder, hiddenPages) {
        visiblePages(pageOrder, hiddenPages) + SEARCH_PAGE_ID
    }
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
    // Read here, not in the coroutine: reduceMotion is a Composable.
    val slidePages = !reduceMotion()
    // [animate] false is a tab switch, which jumps the way Apple's tabs do: a
    // slide would say the two tabs are neighbours, and the bar's order is not
    // the pager's. The Library switcher keeps the slide between adjacent
    // sections, which really are next to each other.
    val selectPageWith: (String, Boolean) -> Unit = { id, animate ->
        val page = pages.indexOf(id)
        // Slide to the page next door, jump to anything further.
        //
        // Sliding was removed outright because it sweeps the pager through
        // every page in between, and Home to Downloads animated across five of
        // them — a long smear of pages nobody asked for. That reasoning only
        // holds when there IS something in between. One page over there is
        // nothing to sweep, and the slide reads as the two pages being
        // neighbours, which they are.
        //
        // Distance from the page actually shown, so this stays right if a drag
        // left the pager somewhere other than where the last pick put it.
        if (page >= 0) scope.launch {
            val adjacent = kotlin.math.abs(page - pagerState.currentPage) == 1
            pagerState.goToPage(page, animated = animate && slidePages && adjacent)
        }
    }
    val selectPage: (String) -> Unit = { id -> selectPageWith(id, true) }

    // ── The tab bar ──────────────────────────────────────────────────────
    val currentPageId = pages.getOrNull(pagerState.currentPage)
    // The Library tab returns to the section that was open last, the way a tab
    // keeps its place. Saveable: it is navigation state, like the page itself.
    var lastLibrarySection by rememberSaveable { mutableStateOf<String?>(null) }
    // A section pinned to its own button is not where Library goes back to:
    // that button already opens it.
    LaunchedEffect(currentPageId, navBarSlots) {
        if (currentPageId in LIBRARY_PAGE_IDS && currentPageId !in navBarSlots) lastLibrarySection = currentPageId
    }
    // Set by a tap on Search and spent by the Search page's first composition,
    // so the keyboard comes up for a tap and not for coming back to results.
    var focusSearch by remember { mutableStateOf(false) }
    val onTab: (AppTab) -> Unit = { tab ->
        // A tab tapped from a pushed screen closes it, as in Apple Music: the
        // pager is only drawn while the NavHost is on "home".
        if (!isOnMainTab && !navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route)
        }
        if (tab == AppTab.SEARCH) focusSearch = true
        selectPageWith(pageForTab(tab, pages, lastLibrarySection, navBarSlots), false)
    }

    // The bar folds the mini player into itself while the listener scrolls down
    // through a page, and unfolds it when they scroll back up — measured from
    // what the lists actually scroll, through nested scroll, so every list in
    // the app drives it without knowing it exists. Only worth doing with a
    // mini player to fold; without one the bar stays as it is.
    var chromeCollapsed by remember { mutableStateOf(false) }
    val collapseThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val collapseOnScroll = remember(collapseThresholdPx) {
        object : NestedScrollConnection {
            // Distance travelled in the current direction; a reversal resets it,
            // so a twitch the other way never flips the bar.
            private var travel = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = consumed.y
                if (dy == 0f) return Offset.Zero
                if ((dy < 0f) != (travel < 0f)) travel = 0f
                travel += dy
                if (travel <= -collapseThresholdPx) chromeCollapsed = true
                else if (travel >= collapseThresholdPx) chromeCollapsed = false
                return Offset.Zero
            }
        }
    }
    // A new page or screen starts with the bar open: the fold belongs to the
    // scroll that caused it, not to wherever the listener goes next.
    LaunchedEffect(currentPageId, currentDestination?.route) { chromeCollapsed = false }

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

    // How tall the floating chrome stands above the system bar, expanded: the
    // tab bar, and the mini player stacked over it. Lists pad by the expanded
    // height even while the bar is folded — following the fold would jolt them.
    // Taking turns, nothing is ever stacked: both states are one row high.
    val chromeHeight = when {
        !showChrome -> 0.dp
        showMiniPlayer && !miniPlayerHideWithTabs -> CHROME_GAP + TabBarHeight + CHROME_GAP + MINI_PLAYER_HEIGHT
        else -> CHROME_GAP + TabBarHeight
    }

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
        // World radio used to be here. It is a pager page now, and the pager
        // is already full-bleed — this list is only about NavHost destinations.
        val fullBleedRoute = currentDestination?.route == Screen.GenreMap.route ||
            currentDestination?.route == Screen.NowPlaying.route

        // Every screen runs *under* the mini player. Reserving the bar's height
        // out here letterboxed them: the strip behind the bar was flat theme
        // background, so the bar's glass had nothing but a solid colour to lens
        // and read as an opaque container however transparent it was set.
        //
        // The reserve moves into each screen's own scroll, published below as
        // [LocalBottomChromeInset], where it is scrollable — content passes
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
            // A pushed screen stops above the system bar (the NavHost is padded
            // for it), so its lists need only the chrome. The pager and the
            // full-bleed routes run underneath the system bar, and get it added
            // — see the providers around each below.
            LocalBottomChromeInset provides if (fullBleedRoute) navBarHeight + chromeHeight else chromeHeight,
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
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState).nestedScroll(collapseOnScroll)) {
            // Pager for main tabs — fills entire screen
            if (isOnMainTab) CompositionLocalProvider(
                LocalBottomChromeInset provides navBarHeight + chromeHeight,
            ) {
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
                                        onSelectPage = { id -> selectPageWith(id, false) },
                                    )
                                // Its own branch rather than a Library section:
                                // the globe is full-bleed and brings its own top
                                // bar and gestures, and LibraryScreen wraps its
                                // sections in chrome the globe does not want.
                                RADIO_PAGE_ID ->
                                    tf.monochrome.android.ui.discover.WorldRadioScreen(
                                        playerViewModel = playerViewModel,
                                    )
                                SEARCH_PAGE_ID -> {
                                    SearchScreen(
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        autoFocus = focusSearch,
                                    )
                                    // Spent once the bar has had it. Keyed on the
                                    // flag, so a second tap while already here
                                    // is spent too — keyed on Unit it stayed set,
                                    // and coming back from an album re-popped
                                    // the keyboard over the results.
                                    LaunchedEffect(focusSearch) { if (focusSearch) focusSearch = false }
                                }
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
                            onBack = { navController.popBackStackSafe() },
                            modifier = Modifier.fillMaxSize().padding(top = statusBarHeight),
                        )
                    }
                }
                composable(Screen.Crossfeed.route) {
                    val vm: CrossfeedViewModel = hiltViewModel()
                    tf.monochrome.android.devedit.DevEditScreen("crossfeed") {
                        CrossfeedScreen(
                            effect = vm.crossfeed,
                            onBack = { navController.popBackStackSafe() },
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
                        ListeningStatsScreen(onBack = { navController.popBackStackSafe() })
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
                    route = Screen.LocalFacetDetail.route,
                    arguments = listOf(
                        navArgument("facet") { type = NavType.StringType },
                        navArgument("value") { type = NavType.StringType },
                    )
                ) {
                    tf.monochrome.android.devedit.DevEditScreen("local_facet_detail") {
                        LocalFacetDetailScreen(
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

        // ── Layer 2: Tab bar + mini player (overlays content) ──
        //
        // A sibling of the haze source, never inside it: each pane frosts the
        // content layer behind it, and a pane drawn inside its own source would
        // be asked to blur a picture it is part of — the flat slab.
        if (showChrome) {
            val miniPlayer: (@Composable (Modifier) -> Unit)? = if (showMiniPlayer) {
                { mod ->
                    // The glass tint is taken here, outside the album scope, so
                    // the bar's glass is the tab bar's colour exactly.
                    val chromeGlassTint = tf.monochrome.android.ui.theme.glassTint(miniPlayerGlass.tintColor)
                    // Mini player follows the album art (dynamic colours), while
                    // the menus around it — the tab bar included — stay on the
                    // base theme. Its glass does not: see chromeGlassTint.
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
                                // A plain push, not navigateTool: collapsing an
                                // earlier player would take every screen above it
                                // along. One left under the mixer is the only kind
                                // there can be (see leavePlayerFor), and Back walks
                                // through it.
                                onClick = { navController.navigateSafe(Screen.NowPlaying.route) },
                                modifier = mod,
                                hazeState = hazeState,
                                blendMillis = miniBlendMs,
                                userTrackChanges = userTrackChanges,
                                glassTintColor = chromeGlassTint,
                            )
                        }
                    }
                }
            } else null

            CompositionLocalProvider(
                // The bar is the app's chrome, so it is the mini player's
                // material — see LocalMiniPlayerGlass.
                tf.monochrome.android.ui.player.LocalPlayerGlass provides miniPlayerGlass,
            ) {
                TabChrome(
                    tabs = pillTabs(pages, navBarSlots),
                    // On a pushed screen the bar lights the tab underneath it.
                    selected = tabFor(currentPageId, navBarSlots),
                    onTab = { tab ->
                        // Taking turns, the open bar has no mini player, and a
                        // page too short to scroll could never fold to show it.
                        // So the tab you are already on — a tap that did nothing
                        // — folds the bar instead: the player is always one tap
                        // from anywhere, and switching tab is still one tap.
                        if (miniPlayerHideWithTabs && miniPlayer != null && isOnMainTab &&
                            !chromeCollapsed && tab == tabFor(currentPageId, navBarSlots)
                        ) {
                            chromeCollapsed = true
                        } else {
                            onTab(tab)
                        }
                    },
                    collapsed = chromeCollapsed && miniPlayer != null,
                    onExpand = { chromeCollapsed = false },
                    stackMiniPlayer = !miniPlayerHideWithTabs,
                    hazeState = hazeState,
                    miniPlayer = miniPlayer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBarHeight + CHROME_GAP),
                )
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
        // The same screens the tab bar keeps off, for the same reason — their
        // controls run to the bottom edge. Oxford used to be missing here, so
        // the pill sat over its CLIP / BAND SPLIT row.
        val onChromeScreen = showChrome
        if (onChromeScreen && activeDownloads.isNotEmpty() && !pillHidden) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarHeight + chromeHeight + 8.dp)
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

/** Space between the chrome's panes, and between the bar and the system bar. */
private val CHROME_GAP = 8.dp

/** The mini player's height with its progress line — see MiniPlayer's metrics. */
private val MINI_PLAYER_HEIGHT = 66.dp

/**
 * The floating bottom chrome: the mini player over the tab pill, with Search
 * as its own round button beside it.
 *
 * Folded ([collapsed]), the pill shrinks to the current tab's glyph alone and
 * the mini player slides in between it and Search — iOS 26's tab bar. Tapping
 * the shrunken pill opens it back up rather than switching tab: what it shows
 * is the tab you are already on.
 *
 * Open, the mini player stacks over the pill unless [stackMiniPlayer] is off.
 * Then the two take turns, and the bar only ever moves between two one-row
 * states: the tabs, and the folded pill with the mini player beside it.
 */
@Composable
private fun TabChrome(
    tabs: List<AppTab>,
    selected: AppTab,
    onTab: (AppTab) -> Unit,
    collapsed: Boolean,
    onExpand: () -> Unit,
    stackMiniPlayer: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState,
    miniPlayer: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val foldMillis = motionMillis(240)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CHROME_GAP),
    ) {
        AnimatedVisibility(
            visible = miniPlayer != null && !collapsed && stackMiniPlayer,
            // clip = false: the bar casts a glass shadow past its own bounds,
            // and expand/shrink clip to them by default, which cut the spill
            // off. The bar's own content is still clipped to its rounded
            // shape; the fade covers the brief overflow while it grows.
            enter = fadeIn(tween(foldMillis)) + expandVertically(tween(foldMillis), clip = false),
            exit = fadeOut(tween(foldMillis)) + shrinkVertically(tween(foldMillis), clip = false),
        ) {
            miniPlayer?.invoke(Modifier.fillMaxWidth())
        }
        AnimatedContent(
            targetState = collapsed,
            transitionSpec = {
                fadeIn(tween(foldMillis)) togetherWith fadeOut(tween(foldMillis)) using
                    SizeTransform(clip = false)
            },
            label = "tabChromeFold",
        ) { folded ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(CHROME_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (folded && miniPlayer != null) {
                    // The tab you are on, or Home while you are on Search —
                    // Search is already showing itself beside the mini player.
                    val shown = selected.takeIf { it in tabs } ?: tabs.first()
                    GlassTabBar(
                        tabs = listOf(shown),
                        selected = selected,
                        onSelect = { onExpand() },
                        accent = accent,
                        hazeState = hazeState,
                        showLabels = false,
                        modifier = Modifier.width(TabBarHeight),
                    )
                    miniPlayer(Modifier.weight(1f))
                } else {
                    GlassTabBar(
                        tabs = tabs,
                        selected = selected,
                        onSelect = onTab,
                        accent = accent,
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )
                }
                GlassTabBar(
                    tabs = listOf(AppTab.SEARCH),
                    selected = selected,
                    onSelect = onTab,
                    accent = accent,
                    hazeState = hazeState,
                    modifier = Modifier.width(TabBarHeight),
                )
            }
        }
    }
}
