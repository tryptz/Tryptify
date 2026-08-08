package tf.monochrome.android.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Flow — one track at a time, full screen, swipe up for the next.
 *
 * The shelves on Discover are a *browsing* surface: you read them, you decide,
 * you commit. This is the opposite posture. Every swipe is a decision already
 * made — settling on a card plays that track immediately, so the gap between
 * "what's this" and "I'm hearing it" is one gesture and no taps. That
 * immediacy is the whole point; a feed you have to tap into is just a list
 * with bigger pictures.
 *
 * Two deliberate departures from the obvious short-video analogue:
 *
 * - **It plays for real, not a preview.** Landing on a card starts the actual
 *   track through the actual player and queue, so the thing you liked is the
 *   thing that keeps playing when you leave the screen — and the queue behind
 *   it is the rest of the feed.
 * - **It tells you why.** Every card carries its shelf's reason line. An
 *   endless scroll of unexplained recommendations is exactly the "when
 *   everything is content, nothing feels curated" failure; the point of the
 *   format here is to make you stop and dig into one artist, not to keep you
 *   swallowing.
 *
 * The action rail is the other half: the reward loop only closes if reacting is
 * as cheap as skipping. Heart it, queue it, start a station from it, or wave it
 * away — all one tap, none of them leaving the feed.
 */
/** How close to the end triggers the next fetch. */
private const val FLOW_PREFETCH_THRESHOLD = 5

@Composable
fun DiscoveryFlowScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: DiscoverViewModel = rememberDiscoverViewModel(),
) {
    val tracks by viewModel.flowTracks.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val favouriteIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val isRadioActive by playerViewModel.isRadioActive.collectAsStateWithLifecycle()

    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }

    // The pager and the "what have I already started" memory live outside the
    // empty branch below, so a momentarily empty list — dismissing the last
    // card, a rebuild landing — doesn't reset the position to page 0 or close
    // an open action sheet.
    val pagerState = rememberPagerState(pageCount = { tracks.size })
    var lastStartedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Read live inside the collector instead of keying the effect on it. The
    // list changes often — a top-up landing, a card dismissed — and restarting
    // the effect on every change would re-emit the settled page and start it
    // again. That is not hypothetical: once the player has advanced on its own
    // (track ended, resolve failure, a headphone skip) the page on screen is no
    // longer the playing track, so a restart would yank playback backwards to
    // the visible card and restart it from 0:00.
    val latestTracks by rememberUpdatedState(tracks)

    // Play on *settle*, not on currentPage: a fast flick through five cards
    // would otherwise start and abandon five tracks, hammering the player and
    // leaving whichever request resolved last playing.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                val current = latestTracks
                val track = current.getOrNull(page) ?: return@collect
                // Keyed on what this screen last *started*, not on what the
                // player is currently playing: the player moves on by itself,
                // and this feed should only take the wheel when the listener
                // swipes to something new.
                if (track.id != lastStartedId) {
                    lastStartedId = track.id
                    // The queue is the rest of the feed, so leaving the screen
                    // keeps playing forward instead of stopping dead.
                    playerViewModel.playUnifiedTrack(track, current)
                }
                // Fetch the next batch well before the end, so the feed never
                // visibly runs out under the thumb.
                if (page >= current.size - FLOW_PREFETCH_THRESHOLD) viewModel.extendFlow()
            }
    }

    if (tracks.isEmpty()) {
        FlowEmptyState(loading = loading, onBack = { navController.popBackStack() })
        UnifiedTrackContextMenuHost(
            track = menuTrack,
            onDismissRequest = { menuTrack = null },
            navController = navController,
            playerViewModel = playerViewModel,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // One page either side, so the next cover is decoded before the
            // swipe rather than popping in after it.
            beyondViewportPageCount = 1,
        ) { page ->
            val track = tracks[page]
            // Favourites are keyed on the legacy id, which is source-derived
            // (Qobuz/TIDAL/Apple keep their real id, everything else hashes).
            // Parsing the unified id as a Long instead would silently miss for
            // every source whose id isn't a bare number.
            val legacy = remember(track) { track.toLegacyTrack() }
            FlowCard(
                track = track,
                reason = viewModel.reasonFor(track),
                isLiked = favouriteIds.contains(legacy.id),
                isRadioActive = isRadioActive,
                onToggleLike = { playerViewModel.toggleFavorite(legacy) },
                onQueue = { playerViewModel.addUnifiedToQueue(listOf(track)) },
                onRadio = { playerViewModel.startRadioFrom(legacy) },
                onMore = { menuTrack = track },
                onNotForMe = { viewModel.dismissItem("t:" + track.id) },
            )
        }

        // Back out of the feed. Everything else on screen is an action on the
        // track, so this is deliberately the only chrome at the top.
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Leave Flow",
                tint = Color.White,
            )
        }

        Text(
            text = "${pagerState.currentPage + 1} / ${tracks.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
        )
    }

    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
    )
}

@Composable
private fun FlowCard(
    track: UnifiedTrack,
    reason: String?,
    isLiked: Boolean,
    isRadioActive: Boolean,
    onToggleLike: () -> Unit,
    onQueue: () -> Unit,
    onRadio: () -> Unit,
    onMore: () -> Unit,
    onNotForMe: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed artwork behind everything, dimmed so white type stays
        // legible over any cover.
        CoverImage(
            url = track.artworkUri,
            contentDescription = null,
            size = 0.dp,
            cornerRadius = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.85f),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            CoverImage(
                url = track.artworkUri,
                contentDescription = track.title,
                size = 300.dp,
                cornerRadius = MonoDimens.radiusLg,
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artists.joinToString(", ") { it.name }.ifBlank { track.artistName },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The reason line. Without it this is an endless scroll of
            // unexplained covers; with it, every card is an argument.
            if (reason != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MonoDimens.shapePill,
                    color = Color.White.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Swipe up for the next one",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // Action rail. Reacting has to be as cheap as skipping, or the feed
        // trains you to skip.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .navigationBarsPadding()
                .padding(end = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowAction(
                icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = if (isLiked) "Liked" else "Like",
                tint = if (isLiked) MaterialTheme.colorScheme.primary else Color.White,
                onClick = onToggleLike,
            )
            FlowAction(Icons.Default.QueueMusic, "Queue", Color.White, onQueue)
            FlowAction(
                icon = Icons.Default.Podcasts,
                label = "Radio",
                tint = if (isRadioActive) MaterialTheme.colorScheme.primary else Color.White,
                onClick = onRadio,
            )
            FlowAction(Icons.Default.Close, "Not for me", Color.White, onNotForMe)
            FlowAction(Icons.Default.MoreVert, "More", Color.White, onMore)
        }
    }
}

@Composable
private fun FlowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.bounceClick(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun FlowEmptyState(loading: Boolean, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Nothing to flow through yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Play a few tracks, or pick a mood on Discover.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = MonoDimens.shapePill,
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.bounceClick(onClick = onBack),
                ) {
                    Text(
                        text = "Back to Discover",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Leave Flow",
                tint = Color.White,
            )
        }
    }
}
