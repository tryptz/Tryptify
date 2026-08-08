package tf.monochrome.android.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedChip.collectAsStateWithLifecycle()
    val hero by viewModel.hero.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val isRadioActive by playerViewModel.isRadioActive.collectAsStateWithLifecycle()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Discover") },
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
                )
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (shelves.isEmpty()) {
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
 * One shelf: a header with its reason, then a horizontal row of cards.
 *
 * The reason line is the part that matters. "Because you play Aphex Twin" and
 * an unlabelled row are the same twelve records; only one of them tells the
 * listener whether to trust it.
 */
@Composable
private fun DiscoveryShelfRow(
    shelf: DiscoveryShelf,
    onSeeAll: () -> Unit,
    onItemClick: (DiscoveryItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = shelf.title,
            onSeeAllClick = onSeeAll.takeIf { shelf.seeAll && shelf.items.size > 3 },
        )
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
                DiscoveryCard(item = item, onClick = { onItemClick(item) })
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Renders whichever of the three card shapes this item is. */
@Composable
internal fun DiscoveryCard(item: DiscoveryItem, onClick: () -> Unit) {
    when (item) {
        is DiscoveryItem.TrackItem -> DiscoveryTrackCard(track = item.track, onClick = onClick)
        is DiscoveryItem.AlbumItem -> AlbumItem(album = item.album, onClick = onClick)
        is DiscoveryItem.ArtistItem -> ArtistItem(artist = item.artist, onClick = onClick)
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
