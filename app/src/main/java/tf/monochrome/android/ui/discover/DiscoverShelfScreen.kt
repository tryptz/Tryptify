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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
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
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val shelf = shelves.firstOrNull { it.id == shelfId }

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

        when {
            shelf != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(MonoDimens.coverCard),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.items, key = { it.key }) { item ->
                    DiscoveryCard(
                        item = item,
                        onClick = {
                            openDiscoveryItem(navController, playerViewModel, shelf, item)
                        },
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
}
