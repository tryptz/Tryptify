package tf.monochrome.android.ui.discover

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.ui.components.AlbumItem
import tf.monochrome.android.ui.components.ArtistItem
import tf.monochrome.android.ui.components.DiscoveryTrackCard
import tf.monochrome.android.ui.components.SectionHeader
import tf.monochrome.android.ui.components.swallowHorizontalScroll
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.openCatalogAlbum
import tf.monochrome.android.ui.navigation.openCatalogArtist
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * Discover — the browsing half of the app, split out of Home.
 *
 * The shape follows what actually works on a streaming service's discovery
 * page, in order down the screen: one featured thing so the page has a top, a
 * mood/activity rail so someone who doesn't know what they want has an entry
 * point that isn't a search box, then explained shelves — each labelled with
 * *why* it is being shown — that stay short and open into a full grid rather
 * than scrolling forever. The failure mode being designed against is not a thin
 * catalogue, it's decision fatigue: an undifferentiated wall of covers is
 * exactly as unhelpful as an empty page.
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
    val hero by viewModel.hero.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val adventure by viewModel.adventureDisplay.collectAsStateWithLifecycle()
    val isRadioActive by playerViewModel.isRadioActive.collectAsStateWithLifecycle()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsStateWithLifecycle()

    // The ⋮ sheet for a tapped-and-held track card. Held here, outside the
    // list, so it survives the row that opened it scrolling out of view.
    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Discover") },
            actions = {
                IconButton(onClick = { viewModel.showSomethingElse() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Show me something else")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // Chip rail, pinned above the feed rather than scrolling with it: it is
        // the control for what's below, so it has to stay reachable once the
        // user is three shelves deep.
        DiscoveryChipRail(
            chips = viewModel.chips.map { it.label },
            selected = selectedChip,
            onSelect = { viewModel.selectChip(it) },
        )

        // Flow: the active way through the same feed. Sits above the shelves
        // because it is the fastest route into music, not an afterthought.
        FlowEntryButton(
            onClick = { navController.navigateSafe(Screen.DiscoveryFlow.route) },
        )

        AdventureControl(
            value = adventure,
            onChange = viewModel::setAdventure,
            onCommit = viewModel::commitAdventure,
        )

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            // The hero belongs to the personalized feed. On a mood chip the
            // chip itself is the headline, and a second one would compete.
            val currentHero = hero
            if (selectedChip == null && currentHero != null) {
                item(key = "hero") {
                    DiscoveryHeroCard(
                        hero = currentHero,
                        isRadioActive = isRadioActive,
                        isRadioGenerating = isRadioGenerating,
                        onPlay = {
                            if (isRadioActive) playerViewModel.stopRadio()
                            else playerViewModel.playRadio()
                        },
                    )
                }
            }

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
                    onItemDismiss = { item -> viewModel.dismissItem(item.key) },
                    onDismissShelf = { viewModel.dismissShelf(shelf.id) },
                    onQueueAll = {
                        playerViewModel.addUnifiedToQueue(
                            shelf.items.filterIsInstance<DiscoveryItem.TrackItem>().map { it.track }
                        )
                    },
                )
            }

            // No spinner item: PullToRefreshBox draws the indicator, and a
            // second one at the bottom of the list said the same thing twice.
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryChipRail(
    chips: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "for_you") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("For you") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        items(chips, key = { it }) { label ->
            FilterChip(
                selected = selected == label,
                onClick = { onSelect(if (selected == label) null else label) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

/**
 * The way into [DiscoveryFlowScreen] — one track at a time, swipe for the next.
 */
@Composable
private fun FlowEntryButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Flow",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "One track at a time — swipe for the next",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
 * Every card is also a target for the three things a listener wants to do with
 * a recommendation beyond taking it: hold it for the full action sheet, swipe
 * it up to wave it away, or dismiss the entire shelf from its header.
 */
@Composable
private fun DiscoveryShelfRow(
    shelf: DiscoveryShelf,
    onSeeAll: () -> Unit,
    onItemClick: (DiscoveryItem) -> Unit,
    onItemLongClick: (DiscoveryItem) -> Unit,
    onItemDismiss: (DiscoveryItem) -> Unit,
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
                DismissibleCard(onDismiss = { onItemDismiss(item) }) {
                    DiscoveryCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * Swipe a card upward to wave it away.
 *
 * Vertical, not horizontal: the row it sits in already owns horizontal drags,
 * and the outer tab pager owns whatever the row doesn't. Up is the only axis
 * left that doesn't fight something else for the gesture.
 */
@Composable
private fun DismissibleCard(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // A card is ~200dp tall; a third of that is a deliberate flick, not a
    // wobble while scrolling the row.
    val commitPx = with(LocalDensity.current) { 64.dp.toPx() }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationY = offsetY.value
                alpha = 1f - (kotlin.math.abs(offsetY.value) / (commitPx * 3f)).coerceIn(0f, 0.85f)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dy ->
                        // Only upward travel accumulates; dragging down is the
                        // outer list's scroll, not a dismissal.
                        val next = (offsetY.value + dy).coerceAtMost(0f)
                        if (next != offsetY.value) change.consume()
                        scope.launch { offsetY.snapTo(next) }
                    },
                    onDragEnd = {
                        if (offsetY.value < -commitPx) {
                            scope.launch {
                                offsetY.animateTo(-commitPx * 4f, tween(160))
                                onDismiss()
                                offsetY.snapTo(0f)
                            }
                        } else {
                            scope.launch { offsetY.animateTo(0f, spring()) }
                        }
                    },
                    onDragCancel = { scope.launch { offsetY.animateTo(0f, spring()) } },
                )
            },
    ) { content() }
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
