package tf.monochrome.android.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.DiscoverySort
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.domain.usecase.DISCOVERY_SERVICES
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.DiscoveryTrackCard
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.swallowHorizontalScroll
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.openCatalogAlbum
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.SearchOverlay
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

/**
 * Discover — the browsing half of the app, split out of Home.
 *
 * The shape follows what actually works on a streaming service's discovery
 * page, in order down the screen: a mood/activity rail so someone who doesn't
 * know what they want has an entry point that isn't a search box, then
 * explained shelves — each labelled with *why* it is being shown — that stay
 * short and open into a full grid rather than scrolling forever. The failure
 * mode being designed against is not a thin catalogue, it's decision fatigue:
 * an undifferentiated wall of covers is exactly as unhelpful as an empty page.
 *
 * The furniture above the first shelf is kept honest. A header that fills the
 * screen costs every visit, and two never earned it — a hero built from an
 * artist you already play, and a permanent search field for a question most
 * visits don't ask. What heads the feed now is the one thing that changes
 * daily and only daily, today's discovery: a genre next door to the listener's
 * own that they have not been to. It scrolls away with the feed rather than
 * pinning, and only "For you" carries it.
 *
 * The page's other ways in — the galaxy that fills in as genres are explored,
 * the genre of the day with its history, World radio — are cards between the
 * shelves rather than buttons pinned above them, where they took a row of
 * every visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    // The nav host's way of opening a page — World radio's entry button moves
    // the pager there rather than pushing a screen.
    onSelectPage: (String) -> Unit,
    viewModel: DiscoverViewModel = rememberDiscoverViewModel(),
) {
    val shelves by viewModel.visibleShelves.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedChip.collectAsStateWithLifecycle()
    val genreQuery by viewModel.genreQuery.collectAsStateWithLifecycle()
    val selectedMoods by viewModel.selectedMoods.collectAsStateWithLifecycle()
    val combinedGenres by viewModel.combinedGenres.collectAsStateWithLifecycle()
    val excluded by viewModel.excludedGenres.collectAsStateWithLifecycle()
    val combinedLabels = remember(selectedMoods) { viewModel.labelsForMoods(selectedMoods) }
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val genreRail by viewModel.genreRail.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val exhausted by viewModel.exhausted.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val spotlight by viewModel.spotlight.collectAsStateWithLifecycle()
    val explored by viewModel.exploredGenres.collectAsStateWithLifecycle()
    val starting by viewModel.startingGenre.collectAsStateWithLifecycle()
    val heartedGenres by viewModel.heartedGenres.collectAsStateWithLifecycle()
    val service by viewModel.service.collectAsStateWithLifecycle()
    val availableServices by viewModel.availableServices.collectAsStateWithLifecycle()
    val deckProgress by viewModel.deckProgress.collectAsStateWithLifecycle()
    val radar by viewModel.radar.collectAsStateWithLifecycle()
    val graph = viewModel.genreGraph

    // A new day turns over when the page is next shown, not on a timer.
    LaunchedEffect(Unit) { viewModel.onShown() }

    val listState = rememberLazyListState()
    // Nothing is fetched ahead of time: the next page is requested when the
    // listener has actually reached the end of this one — the footer coming
    // into view is the signal. Re-armed on every change to the feed, because a
    // collector that stayed running would see "still at the end" as no change
    // and never ask for a third page.
    LaunchedEffect(listState, shelves.size, selectedChip) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1
        }
            .distinctUntilChanged()
            .collect { atEnd -> if (atEnd) viewModel.loadMore() }
    }

    // The ⋮ sheet for a tapped-and-held track card. Held here, outside the
    // list, so it survives the row that opened it scrolling out of view.
    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }

    // Search is folded away by default. Everything above the first shelf is
    // page furniture, and a two-line field plus its results row was the tallest
    // piece of it — carried on every visit, for a question most visits don't
    // ask. The icon in the bar is the whole feature when it isn't being used.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // A picked genre is the exception: the rail is the only thing that says
    // which one is driving the feed, and the only way to turn it back off, so
    // it stays out while a selection is live even with the field folded.
    val genreSelected = genreRail.any { it.node.name == selectedChip }

    // Today's layer belongs to "For you". A mood or a genre is a page about
    // that choice, and a hero about something else on top of it would be noise.
    val forYou = selectedChip == null && selectedMoods.isEmpty()
    val hero = today.takeIf { forYou }

    // The page's own backdrop for its glass, a sibling of everything drawn over
    // it — never the app-wide source this page is itself inside.
    val haze = rememberHazeState()
    Box(modifier = Modifier.fillMaxSize()) {
    DiscoverBackdrop(
        graph = graph,
        explored = explored,
        todayId = today?.genre?.id,
        listState = listState,
        // Shorter than the hero, which is the first row whenever there is drift.
        drift = if (hero != null) 200.dp else 0.dp,
        modifier = Modifier.fillMaxSize().hazeSource(haze),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.discover_beta)) },
            actions = {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    // Folding it away takes the query with it: a filter still
                    // narrowing the row from behind a closed door is a page
                    // nobody can explain.
                    if (!searchOpen) viewModel.setGenreQuery("")
                }) {
                    Icon(
                        if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (searchOpen) stringResource(R.string.close_genre_search) else stringResource(R.string.search_genres),
                    )
                }
                IconButton(onClick = { viewModel.showSomethingElse() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.show_something_else))
                }
                // Every page can now be the only visible one, so every page has
                // to be a way into Settings — which is the only place to make
                // another page visible again. Discover was the one top bar
                // without this, which made "hide everything but Discover" a
                // three-tap way to strand yourself.
                IconButton(onClick = {
                    navController.navigateTool(Screen.Settings, Screen.Settings.createRoute())
                }) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // Search, then the genres it matched. 771 genres is far past what a row
        // can hold, and the ones somebody wants are rarely the ones a fixed row
        // would have picked — typing is the only interface that reaches all of
        // them. Empty, it still shows the listener's own genres, so the row is
        // never blank and never worse than the static one it replaces.
        //
        // Floating over the feed rather than laid out above it: as a row of this
        // Column, opening the search pushed the rails, the map buttons and every
        // shelf down by its height, and the glass had the page background behind
        // it instead of the feed.
        SearchOverlay(
            open = searchOpen,
            query = genreQuery,
            onQueryChange = viewModel::setGenreQuery,
            placeholder = stringResource(R.string.search_genres_hint),
            onClose = {
                searchOpen = false
                viewModel.setGenreQuery("")
            },
            modifier = Modifier.weight(1f),
            // Just the field, one slim row like every other glass search bar in
            // the app. It used to carry a second line ("Browse the map", and
            // "no match" when nothing did), which made it twice the height of
            // the rest. Both live on the page now, in the genre row under the
            // bar, where there is room for them.
        ) { searchTopInset ->
        Column(modifier = Modifier.fillMaxSize().padding(top = searchTopInset)) {
        // The matches, and — with the search folded away — whatever selection is
        // still live, which needs somewhere to show itself and something to turn
        // it back off with.
        AnimatedVisibility(visible = searchOpen || genreSelected) {
            GenreRail(
                items = genreRail,
                query = genreQuery,
                selected = selectedChip,
                onSelect = {
                    if (selectedChip == it.node.name) viewModel.selectChip(null)
                    else viewModel.selectGenre(it.node.id)
                },
                onOpenMap = { navController.navigateSafe(Screen.GenreMap.route) },
            )
        }

        // Chip rail, pinned above the feed rather than scrolling with it: it is
        // the control for what's below, so it has to stay reachable once the
        // user is three shelves deep.
        DiscoveryChipRail(
            chips = viewModel.chips.map { it.label },
            selected = selectedChip,
            combinedLabels = combinedLabels,
            onSelect = { viewModel.selectChip(it) },
            onToggle = { viewModel.toggleMood(it) },
        )

        if (selectedMoods.size >= 2) {
            CombinedGenreRow(
                genres = combinedGenres,
                excludedCount = excluded.size,
                onSubtract = { viewModel.subtractGenre(it) },
                onReset = { viewModel.clearSubtractions() },
            )
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tf.monochrome.android.ui.navigation.bottomChromePadding),
        ) {
            hero?.let { pick ->
                item(key = "today") {
                    DiscoverGlassCard(haze = haze) {
                        TodayHero(
                            graph = graph,
                            pick = pick,
                            hearted = pick.genre.id in heartedGenres,
                            starting = starting == pick.genre.id,
                            onPlay = { viewModel.playGenre(pick.genre.id, playerViewModel) },
                            onRadio = { viewModel.radioGenre(pick.genre.id, playerViewModel) },
                            onHeart = { viewModel.toggleHeartGenre(pick.genre.id) },
                            onOpenMap = {
                                viewModel.openOnMap(pick.genre.id, withHistory = false)
                                navController.navigateSafe(Screen.GenreMap.route)
                            },
                        )
                    }
                }
            }

            // New releases from the artists you play, under today's pick and
            // above everything that only changes when you ask it to.
            if (forYou && radar.isNotEmpty()) {
                item(key = "radar") {
                    ReleaseRadarRow(
                        items = radar,
                        onOpen = { navController.openCatalogAlbum(it.release.albumId) },
                        onShown = viewModel::markRadarSeen,
                    )
                }
            }

            // Which service the page finds its music on. Above the sort, because
            // it decides what there is to sort.
            item(key = "service") {
                ServiceRow(
                    selected = service,
                    available = availableServices,
                    onSelect = viewModel::setService,
                )
            }

            // Scrolls with the feed now. It orders what is already on screen,
            // which is a choice made once in a while, not a control the page
            // has to keep in reach.
            item(key = "sort") {
                SortRow(selected = sort, onSelect = viewModel::setSort)
            }

            // The cards between the shelves, and the shelf each one follows.
            // Only on "For you", like the hero.
            val cards = if (!forYou) emptyList() else buildList {
                // The deck first: it is the one thing on the page with a
                // "today" you can finish.
                add(FeedCard.DECK to 0)
                add(FeedCard.GALAXY to 1)
                if (spotlight != null) add(FeedCard.SPOTLIGHT to 3)
                add(FeedCard.WORLD_RADIO to 5)
            }
            val feedCard: @Composable (FeedCard) -> Unit = { card ->
                when (card) {
                    FeedCard.DECK -> DiscoverGlassCard(
                        haze = haze,
                        onClick = { navController.navigateSafe(Screen.DiscoverDeck.route) },
                    ) {
                        DeckEntryCard(progress = deckProgress)
                    }
                    FeedCard.GALAXY -> DiscoverGlassCard(
                        haze = haze,
                        onClick = {
                            today?.let { viewModel.openOnMap(it.genre.id, withHistory = false) }
                            navController.navigateSafe(Screen.GenreMap.route)
                        },
                    ) {
                        GalaxyCard(graph = graph, explored = explored, next = today?.genre)
                    }
                    FeedCard.SPOTLIGHT -> spotlight?.let { spot ->
                        DiscoverGlassCard(haze = haze) {
                            SpotlightCard(
                                graph = graph,
                                spotlight = spot,
                                starting = starting == spot.genre.id,
                                onPlay = { viewModel.playGenre(spot.genre.id, playerViewModel) },
                                onReadHistory = {
                                    viewModel.openOnMap(spot.genre.id, withHistory = true)
                                    navController.navigateSafe(Screen.GenreMap.route)
                                },
                            )
                        }
                    }
                    FeedCard.WORLD_RADIO -> DiscoverGlassCard(
                        haze = haze,
                        // A page, not a destination: move the pager rather
                        // than pushing a screen onto the stack.
                        onClick = { onSelectPage(tf.monochrome.android.ui.navigation.RADIO_PAGE_ID) },
                    ) {
                        WorldRadioCard()
                    }
                }
            }

            shelves.forEachIndexed { index, shelf ->
              item(key = shelf.id) {
                DiscoveryShelfRow(
                    shelf = shelf,
                    onSeeAll = {
                        navController.navigateSafe(Screen.DiscoverShelf.createRoute(shelf.id))
                    },
                    onItemClick = { item ->
                        openDiscoveryItem(navController, playerViewModel, shelf, item)
                    },
                    onItemLongClick = { item ->
                        when (item) {
                            is DiscoveryItem.TrackItem -> menuTrack = item.track
                            // Albums and artists have their own pages, which
                            // are the menu; long-press just gets you there.
                            else -> openDiscoveryItem(navController, playerViewModel, shelf, item)
                        }
                    },
                    onDismissShelf = { viewModel.dismissShelf(shelf.id) },
                    onQueueAll = {
                        playerViewModel.addUnifiedToQueue(
                            shelf.items.filterIsInstance<DiscoveryItem.TrackItem>().map { it.track }
                        )
                    },
                )
              }
              cards.filter { it.second == index }.forEach { (card, _) ->
                  item(key = card.key) { feedCard(card) }
              }
            }

            // A short feed still gets its cards, after the last shelf — but
            // only once it has stopped loading, or they would land at the
            // bottom and then jump up between shelves as those arrive.
            if (!loading) {
                cards.filter { it.second >= shelves.size }.forEach { (card, _) ->
                    item(key = card.key) { feedCard(card) }
                }
            }

            // The paging footer. PullToRefreshBox owns the *top* indicator, so
            // this one only ever speaks for the fetch happening below — either
            // the next page, or the rest of a page that is still drawing itself
            // row by row.
            if (shelves.isNotEmpty()) {
                item(key = "paging_footer") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore || loading -> CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                            )
                            exhausted -> Text(
                                text = stringResource(R.string.feed_end),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Something to look at while the feed is built. Picking a mood or a
            // genre fans out into catalogue searches, and until the first shelf
            // arrives the page was simply empty — indistinguishable from a
            // chip that returned nothing, so the honest empty-state message
            // below was being pre-empted by a blank screen that said less.
            if (loading && shelves.isEmpty()) {
                item(key = "loading") {
                    DiscoveryLoading(
                        label = when {
                            combinedLabels.size >= 2 -> combinedLabels.joinToString(" + ")
                            selectedChip != null -> selectedChip
                            else -> null
                        },
                    )
                }
            }

            if (!loading && shelves.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (selectedChip == null) {
                            stringResource(R.string.discover_empty_feed)
                        } else {
                            stringResource(R.string.discover_empty_chip, service.label)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
        }
        }
        }
        }
    }
    }

    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
        onRemove = menuTrack?.let { held -> { viewModel.dismissItem("t:" + held.id) } },
        removeLabel = stringResource(R.string.not_interested),
    )
}

/**
 * The placeholder feed, shown while the real one is being built.
 *
 * Shelf-shaped rather than a spinner in the middle of nothing: the point is to
 * say what is coming, so the page keeps its layout and the content lands into a
 * shape the eye is already holding. A bare spinner tells you to wait; this
 * tells you what for.
 *
 * The pulse is deliberately slow. Skeletons that flash read as broken, and this
 * can be on screen for a couple of seconds while several catalogue searches
 * come back.
 */
@Composable
private fun DiscoveryLoading(label: String?) {
    val pulse = rememberInfiniteTransition(label = "discovery-loading")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    // Honouring the app-wide switch: with animations off this settles to a
    // still skeleton rather than pulsing, same as every other moving thing.
    val restAlpha = if (tf.monochrome.android.ui.theme.reduceMotion()) 0.35f else alpha
    val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = restAlpha)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = if (label != null) stringResource(R.string.building_label, moodLabel(label)) else stringResource(R.string.building_feed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        repeat(3) {
            // A shelf: its reason line, then the row of covers it will hold.
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(14.dp)
                    .fillMaxWidth(0.45f)
                    .clip(MonoDimens.shapeSm)
                    .background(block),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(MonoDimens.coverCard)
                            .clip(MonoDimens.shapeMd)
                            .background(block),
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

/**
 * The listener's own genres — hearted on the map, or recently played from it.
 *
 * Sits above the moods because the moods are the same seven for everybody and
 * this row isn't. Empty until they've used the map, and then it says so and
 * offers the way there rather than rendering as a blank strip: an invisible
 * feature is one nobody finds.
 */
@Composable
private fun GenreRail(
    items: List<GenreRailItem>,
    query: String,
    selected: String?,
    onSelect: (GenreRailItem) -> Unit,
    onOpenMap: () -> Unit,
) {
    if (items.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A query that matches nothing and a query still being typed look
            // identical without this.
            if (query.isNotBlank()) {
                Text(
                    text = stringResource(R.string.no_genre_match),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(if (query.isNotBlank()) R.string.browse_the_map else R.string.pick_genres_on_map),
                selected = false,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onOpenMap,
                leadingIcon = Icons.Default.AccountTree,
            )
        }
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.node.id }) { item ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = item.node.name,
                selected = selected == item.node.name,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onSelect(item) },
                leadingIcon = if (item.hearted) Icons.Default.Favorite else null,
                description = if (item.hearted) item.node.name + ", " + stringResource(R.string.hearted) else item.node.name,
            )
        }
        // The map, always one more pill along: the field this replaced
        // carried it permanently, and the search bar no longer does.
        item(key = "open_map") {
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(R.string.browse_the_map),
                selected = false,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onOpenMap,
                leadingIcon = Icons.Default.AccountTree,
            )
        }
    }
}

/**
 * How the current category is ordered.
 *
 * Applies to whatever is on screen — a mood, a genre, or the personalized feed
 * — and reorders what has already been fetched, so switching is instant. See
 * [tf.monochrome.android.domain.model.DiscoverySort] for why it isn't pushed
 * into the search instead.
 */
@Composable
private fun SortRow(selected: DiscoverySort, onSelect: (DiscoverySort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiscoverySort.entries.forEach { option ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = when (option) {
                    DiscoverySort.FOR_YOU -> stringResource(R.string.for_you)
                    DiscoverySort.POPULAR -> stringResource(R.string.sort_most_popular)
                    DiscoverySort.NEWEST -> stringResource(R.string.sort_newest)
                },
                selected = option == selected,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The three-way switch for where Discover finds its music: TIDAL, Qobuz or
 * Deezer.
 *
 * Every shelf, chart, genre play and today's pick follows it. A service with no
 * server under Settings › Connections is still shown, disabled, so the switch
 * reads the same on every phone; if the chosen one has lost its server, it
 * stays selectable and says what is missing rather than quietly emptying the
 * page. Deezer carries a note, because its tracks play from Qobuz when Qobuz
 * has them and as previews when it doesn't — a difference you would otherwise
 * only find out about by listening.
 */
@Composable
private fun ServiceRow(
    selected: ApiService,
    available: Set<ApiService>?,
    onSelect: (ApiService) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DISCOVERY_SERVICES.forEach { option ->
                // Unknown yet counts as reachable: a switch that greys itself
                // out for the first frame and then lights up reads as broken.
                val reachable = available == null || option in available
                tf.monochrome.android.ui.mixer.GlassChoiceChip(
                    label = option.label,
                    selected = option == selected,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onSelect(option) },
                    enabled = reachable || option == selected,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val note = when {
            available != null && selected !in available ->
                stringResource(R.string.discover_service_missing, selected.label)
            selected == ApiService.DEEZER -> stringResource(R.string.discover_service_deezer_note)
            else -> null
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The moods, as a row of the app's glass pills — the same pills as the
 * Library's section switcher, so the two rows that steer a page look like one
 * control. "For you" leads; the moods after [COMBINABLE_FROM] combine, and a
 * tick says which are on.
 */
@Composable
private fun DiscoveryChipRail(
    chips: List<String>,
    selected: String?,
    combinedLabels: Set<String>,
    onSelect: (String?) -> Unit,
    onToggle: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    LazyRow(
        modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "for_you") {
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(R.string.for_you),
                selected = selected == null && combinedLabels.isEmpty(),
                accent = accent,
                onClick = { onSelect(null) },
            )
        }
        itemsIndexed(chips, key = { _, label -> label }) { index, label ->
            // The rail opens with the listener's own entry points, which are a
            // single choice about whose taste the page follows and don't
            // compose with each other. Moods start after them and do.
            val combinable = index >= COMBINABLE_FROM
            val isOn = if (combinable) label in combinedLabels else selected == label
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                // The label is the chip's key; only what is shown is translated.
                label = moodLabel(label),
                selected = isOn,
                accent = accent,
                onClick = {
                    if (combinable) onToggle(label)
                    else onSelect(if (selected == label) null else label)
                },
                leadingIcon = if (combinable && isOn) Icons.Default.Check else null,
            )
        }
    }
}

/**
 * What a combination is currently drawing on, and a way to take pieces out.
 *
 * Only shown once two or more moods are on, because with one mood the pool is
 * just that mood and "subtract" would be a second, worse way to say "pick a
 * different chip". Subtractions last as long as the combination does — changing
 * the moods clears them, since an exclusion only means anything against the
 * pool it was made from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CombinedGenreRow(
    genres: List<tf.monochrome.android.domain.model.GenreNode>,
    excludedCount: Int,
    onSubtract: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp),
        ) {
            Text(
                text = if (genres.isEmpty()) {
                    stringResource(R.string.mix_empty)
                } else {
                    pluralStringResource(R.plurals.drawing_on_genres, genres.size, genres.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (excludedCount > 0) {
                TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset)) }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(genres, key = { it.id }) { node ->
                tf.monochrome.android.ui.mixer.GlassChoiceChip(
                    label = node.name,
                    selected = false,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onSubtract(node.id) },
                    leadingIcon = Icons.Default.Close,
                    description = stringResource(R.string.remove_named, node.name),
                )
            }
        }
    }
}

/**
 * One shelf: a header with its reason, then a horizontal row of cards.
 *
 * The reason line is the part that matters. "Because you play Aphex Twin" and
 * an unlabelled row are the same twelve records; only one of them tells the
 * listener whether to trust it.
 *
 * Holding a card opens the full action sheet, which is also where "Not
 * interested" lives, and the header dismisses the whole shelf.
 *
 * Waving a single card away is deliberately NOT a swipe. A card here sits
 * inside a horizontally-scrolling row, inside a vertically-scrolling feed,
 * inside the tab pager — all three axes are already spoken for, and a card
 * that consumed vertical drags would eat the gesture that scrolls the feed.
 * The action lives on the hold instead, where nothing competes for it.
 */
@Composable
private fun DiscoveryShelfRow(
    shelf: DiscoveryShelf,
    onSeeAll: () -> Unit,
    onItemClick: (DiscoveryItem) -> Unit,
    onItemLongClick: (DiscoveryItem) -> Unit,
    onDismissShelf: () -> Unit,
    onQueueAll: () -> Unit,
) {
    val trackCount = shelf.items.count { it is DiscoveryItem.TrackItem }
    val shelfTitle = shelfText(shelf.titleLine, shelf.title) ?: shelf.title
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    title = shelfTitle,
                    onSeeAllClick = onSeeAll.takeIf { shelf.seeAll && shelf.items.size > 3 },
                )
            }
            // Queue the whole shelf. The alternative — a selection mode inside
            // a horizontal carousel — is fiddly to drive with one thumb; the
            // See All grid is where picking individual tracks belongs.
            if (trackCount > 1) {
                IconButton(onClick = onQueueAll) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = stringResource(R.string.queue_all_of, shelfTitle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDismissShelf) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.hide_named, shelfTitle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        shelfText(shelf.reasonLine, shelf.reason)?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { it.key }) { item ->
                DiscoveryCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Renders whichever of the three card shapes this item is. */
@Composable
internal fun DiscoveryCard(
    item: DiscoveryItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val modifier = if (selected) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = MonoDimens.shapeMd,
        )
    } else {
        Modifier
    }
    when (item) {
        is DiscoveryItem.TrackItem -> DiscoveryTrackCard(
            track = item.track,
            onClick = onClick,
            modifier = modifier,
            onLongClick = onLongClick,
        )
        is DiscoveryItem.AlbumItem ->
            AlbumItem(album = item.album, onClick = onClick, modifier = modifier)
        is DiscoveryItem.ArtistItem ->
            ArtistItem(artist = item.artist, onClick = onClick, modifier = modifier)
    }
}

/**
 * Tracks play (in the context of their own shelf, so the queue continues with
 * the rest of the row); albums and artists open their page.
 */
internal fun openDiscoveryItem(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    shelf: DiscoveryShelf,
    item: DiscoveryItem,
) {
    when (item) {
        is DiscoveryItem.TrackItem -> {
            val queue = shelf.items.filterIsInstance<DiscoveryItem.TrackItem>().map { it.track }
            playerViewModel.playUnifiedTrack(item.track, queue)
        }
        is DiscoveryItem.AlbumItem -> navController.openCatalogAlbum(item.album.id)
        is DiscoveryItem.ArtistItem -> navController.openCatalogArtist(item.artist.id)
    }
}

/** The cards set between the shelves on "For you". */
private enum class FeedCard(val key: String) {
    DECK("card_deck"),
    GALAXY("card_galaxy"),
    SPOTLIGHT("card_spotlight"),
    WORLD_RADIO("card_world_radio"),
}
