package tf.monochrome.android.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.ui.components.AddToPlaylistSheet
import tf.monochrome.android.ui.components.TrackSelectionBar
import tf.monochrome.android.ui.components.rememberTrackSelectionState
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One shelf, opened out into a full grid — the far end of "See All".
 *
 * Shelves on the feed are deliberately short: a row you can take in at a glance
 * is a recommendation, and a row you have to work through is a chore. That only
 * holds if the rest is one tap away, which is this screen. It reads the shelf
 * back out of [DiscoverViewModel], so it shows the same feed the user was just
 * looking at rather than re-querying and possibly returning something else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverShelfScreen(
    shelfId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: DiscoverViewModel = rememberDiscoverViewModel(),
) {
    val shelves by viewModel.visibleShelves.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val shelf = shelves.firstOrNull { it.id == shelfId }

    // Multi-select belongs here rather than on the shelf carousel: this is a
    // vertical grid, which is the shape the app's existing selection bar and
    // selection state were built for, and where picking six things out of
    // twenty is actually comfortable.
    val selection = rememberTrackSelectionState<String>()
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val selectedTracks = shelf?.items.orEmpty()
        .filterIsInstance<DiscoveryItem.TrackItem>()
        .filter { it.key in selection.selectedIds }
        .map { it.track }

    // The feed can rebuild behind this screen — a chip build finishing, the
    // adventure slider being released, "show me something else" from a still
    // composed Discover — and the new shelf holds different cards. The
    // selection is a set of keys that no longer exist, so the bar would keep
    // reporting a count while every action resolved to nothing: "Add 5 to
    // playlist" adding zero and closing. Drop the selection when the cards it
    // refers to go away.
    val presentKeys = shelf?.items.orEmpty().map { it.key }.toSet()
    LaunchedEffect(presentKeys) {
        if (selection.active && selection.selectedIds.none { it in presentKeys }) {
            selection.clear()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(shelf?.title ?: "Discover") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        shelf?.reason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (selection.active) {
            TrackSelectionBar(
                // Counted off the live shelf, not the id set, so the number
                // always matches what the buttons will act on.
                selectedCount = selectedTracks.size,
                onClose = { selection.clear() },
                onAddToQueue = {
                    playerViewModel.addUnifiedToQueue(selectedTracks)
                    selection.clear()
                },
                onAddToPlaylist = { showAddToPlaylist = true },
            )
        }

        val gridState = rememberLazyGridState()
        // Reaching the bottom asks the shelf's genre for another page, the same
        // way the feed asks for another row of shelves (DiscoverScreen.kt:147).
        // Re-armed on the item count so a page that lands while sitting at the
        // end doesn't read as "no change" and stall at page two.
        LaunchedEffect(gridState, shelfId, shelf?.items?.size) {
            snapshotFlow {
                val info = gridState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 1
            }
                .distinctUntilChanged()
                .collect { atEnd -> if (atEnd) viewModel.loadMoreInShelf(shelfId) }
        }

        when {
            shelf != null -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(MonoDimens.coverCard),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.items, key = { it.key }) { item ->
                    val selectable = item is DiscoveryItem.TrackItem
                    DiscoveryCard(
                        item = item,
                        selected = item.key in selection.selectedIds,
                        onClick = {
                            // Once a selection is running, a tap extends it
                            // rather than playing — otherwise the gesture that
                            // starts selecting and the one that leaves the
                            // screen are the same gesture.
                            if (selection.active && selectable) selection.toggle(item.key)
                            else openDiscoveryItem(navController, playerViewModel, shelf, item)
                        },
                        onLongClick = { if (selectable) selection.toggle(item.key) },
                    )
                }
            }

            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            // The feed rebuilds per ViewModel, so a shelf can be gone by the
            // time a deep link or a restored back stack lands here.
            else -> Text(
                text = "That shelf isn't in the current feed any more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }

    if (showAddToPlaylist) {
        val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { showAddToPlaylist = false },
            onPlaylistSelected = { playlist ->
                selectedTracks.forEach {
                    playerViewModel.addTrackToPlaylist(playlist.id, it.toLegacyTrack())
                }
                showAddToPlaylist = false
                selection.clear()
            },
            onCreateNew = {
                playerViewModel.createPlaylist(
                    name = shelf?.title ?: "Discover",
                    description = null,
                    initialTracks = selectedTracks.map { it.toLegacyTrack() },
                )
                showAddToPlaylist = false
                selection.clear()
            },
            title = "Add " + selectedTracks.size + " to playlist",
        )
    }
}
