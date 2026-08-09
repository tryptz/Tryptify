package tf.monochrome.android.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.DiscoverySort
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.DiscoveryTrackCard
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.swallowHorizontalScroll
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.openCatalogAlbum
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens

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
 * Which is also why the furniture above the first shelf is kept honest. A
 * header that fills the screen before a single recommendation is visible costs
 * every visit, and the page has twice been asked to carry one — a hero card
 * built from an artist you already play, and a permanent search field for a
 * question most visits don't ask. Neither survived: search folds into an icon
 * in the bar, and the feed starts at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
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
    val adventure by viewModel.adventureDisplay.collectAsStateWithLifecycle()
    val genreRail by viewModel.genreRail.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val exhausted by viewModel.exhausted.collectAsStateWithLifecycle()

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Discover (Beta)") },
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
                        contentDescription = if (searchOpen) "Close genre search" else "Search genres",
                    )
                }
                IconButton(onClick = { viewModel.showSomethingElse() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Show me something else")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // Search, then the genres it matched. 771 genres is far past what a row
        // can hold, and the ones somebody wants are rarely the ones a fixed row
        // would have picked — typing is the only interface that reaches all of
        // them. Empty, it still shows the listener's own genres, so the row is
        // never blank and never worse than the static one it replaces.
        AnimatedVisibility(visible = searchOpen) {
            GenreSearchField(
                query = genreQuery,
                onQueryChange = viewModel::setGenreQuery,
                resultCount = genreRail.size,
                onOpenMap = { navController.navigateSafe(Screen.GenreMap.route) },
            )
        }

        AnimatedVisibility(visible = searchOpen || genreSelected) {
            GenreRail(
                items = genreRail,
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

        SortRow(selected = sort, onSelect = viewModel::setSort)

        // The map, full width now that Flow is gone. Sits above the shelves
        // because it is the fastest route into music, not an afterthought.
        MapEntryButton(onClick = { navController.navigateSafe(Screen.GenreMap.route) })

        AdventureControl(
            value = adventure,
            onChange = viewModel::setAdventure,
            onCommit = viewModel::commitAdventure,
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            items(shelves, key = { it.id }) { shelf ->
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

            // The paging footer. PullToRefreshBox owns the *top* indicator, so
            // this one only ever speaks for the fetch happening below.
            if (shelves.isNotEmpty()) {
                item(key = "paging_footer") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                            )
                            exhausted -> Text(
                                text = "That's everything for this one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (!loading && shelves.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (selectedChip == null) {
                            "Nothing to show yet. Play a few tracks — Discover builds itself " +
                                "from what you listen to."
                        } else {
                            "Nothing came back for that one. Try another mood, or check the " +
                                "Qobuz instance in Settings › Connections."
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

    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
        onRemove = menuTrack?.let { held -> { viewModel.dismissItem("t:" + held.id) } },
        removeLabel = "Not interested",
    )
}

/**
 * The genre search box.
 *
 * Deliberately a plain field rather than a full search screen: the results are
 * the row directly beneath it, so typing and picking never leaves the feed, and
 * the page you were reading stays where it was. The count is shown because a
 * query that matches nothing and a query still being typed look identical
 * otherwise.
 */
@Composable
private fun GenreSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    onOpenMap: () -> Unit,
) {
    // Only composed while the search is open, so opening it is one tap and the
    // keyboard is already up — asking for a second tap on a field that only
    // exists because you asked for it is a tap for nothing.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search 771 genres — try dnb, liquid, phonk") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            } else {
                IconButton(onClick = onOpenMap) {
                    Icon(Icons.Default.AccountTree, contentDescription = "Browse the genre map")
                }
            }
        },
        supportingText = if (query.isNotBlank() && resultCount == 0) {
            { Text("No genre matches that") }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focus),
    )
}

/**
 * The listener's own genres — hearted on the map, or recently played from it.
 *
 * Sits above the moods because the moods are the same seven for everybody and
 * this row isn't. Empty until they've used the map, and then it says so and
 * offers the way there rather than rendering as a blank strip: an invisible
 * feature is one nobody finds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreRail(
    items: List<GenreRailItem>,
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
            AssistChip(
                onClick = onOpenMap,
                label = { Text("Pick genres on the map") },
                leadingIcon = {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
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
            FilterChip(
                selected = selected == item.node.name,
                onClick = { onSelect(item) },
                label = { Text(item.node.name) },
                leadingIcon = if (item.hearted) {
                    {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Hearted",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(),
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortRow(selected: DiscoverySort, onSelect: (DiscoverySort) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        DiscoverySort.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, DiscoverySort.entries.size),
                label = {
                    Text(
                        text = option.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryChipRail(
    chips: List<String>,
    selected: String?,
    combinedLabels: Set<String>,
    onSelect: (String?) -> Unit,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "for_you") {
            FilterChip(
                selected = selected == null && combinedLabels.isEmpty(),
                onClick = { onSelect(null) },
                label = { Text("For you") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        itemsIndexed(chips, key = { _, label -> label }) { index, label ->
            // The rail opens with the listener's own entry points, which are a
            // single choice about whose taste the page follows and don't
            // compose with each other. Moods start after them and do.
            val combinable = index >= COMBINABLE_FROM
            val isOn = if (combinable) label in combinedLabels else selected == label
            FilterChip(
                selected = isOn,
                onClick = {
                    if (combinable) onToggle(label)
                    else onSelect(if (selected == label) null else label)
                },
                label = { Text(label) },
                leadingIcon = if (combinable && isOn) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(),
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
                    "Nothing left in this mix"
                } else {
                    "Drawing on ${genres.size} genres — tap to remove"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (excludedCount > 0) {
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(genres, key = { it.id }) { node ->
                AssistChip(
                    onClick = { onSubtract(node.id) },
                    label = { Text(node.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${node.name}",
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

/** The way into [GenreMapScreen] — the whole taxonomy as one picture. */
@Composable
private fun MapEntryButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Genre map",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
                Text(
                    text = "Every genre, linked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The familiar ↔ adventurous knob.
 *
 * Dragging updates only the caption; the feed is rebuilt on release. Writing
 * on every frame would be a DataStore write and a fan-out of Qobuz searches
 * per pixel of travel, which is both slow and useless — you can't read a feed
 * that is rebuilding under your thumb.
 */
@Composable
private fun AdventureControl(
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Familiar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DiscoveryAdventure.label(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Adventurous",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Hold the live drag value rather than committing the composition-
        // captured `value`. Material3 runs onValueChange and
        // onValueChangeFinished inside the same pointer dispatch with no
        // recomposition between them, and `value` arrives back through a
        // StateFlow, so the closure would commit the position from before the
        // last drag delta — the feed would rebuild at not-quite where you let
        // go. Same bug the player's scrubber carries a fix for.
        var latest by remember(value) { mutableFloatStateOf(value) }
        Slider(
            value = value,
            onValueChange = { latest = it; onChange(it) },
            onValueChangeFinished = { onCommit(latest) },
            modifier = Modifier.fillMaxWidth(),
        )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    title = shelf.title,
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
                        contentDescription = "Queue all of " + shelf.title,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDismissShelf) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Hide " + shelf.title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        shelf.reason?.let { reason ->
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
