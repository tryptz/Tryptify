package tf.monochrome.android.ui.discover.deck

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.DeckCard
import tf.monochrome.android.domain.model.DeckReason
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.MiniBarAction
import tf.monochrome.android.ui.components.MiniBarTakeover
import tf.monochrome.android.ui.components.TakeOverMiniPlayer
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.reduceMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.min

/**
 * Swipe to discover: today's stack, full screen.
 *
 * A screen of its own rather than a row in the feed, because a sideways swipe
 * on a page of the tab pager is already spoken for — it changes the page. Here
 * the gesture belongs to the card and nothing else.
 *
 * Each card plays as it comes up, from about a third of the way in, where a
 * song is usually past its intro: the point is to hear enough to decide, and
 * an intro rarely says what a song is. Previews, which are thirty seconds
 * already, play from the start.
 *
 * The glass is the app's UI panels material from Visual Studio, frosting the
 * card's own cover, which is drawn behind the whole screen as its haze source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDeckScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: SwipeDeckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val saved by viewModel.savedPlaylist.collectAsStateWithLifecycle()
    val serviceLabel by viewModel.serviceLabel.collectAsStateWithLifecycle()
    val haze = rememberHazeState()
    val swiping = state as? DeckUiState.Swiping
    val current = swiping?.current

    // Each card plays as it comes up.
    LaunchedEffect(current?.track?.id) {
        val track = current?.track ?: return@LaunchedEffect
        playerViewModel.playUnifiedTrack(track, listOf(track))
        seekPastIntro(playerViewModel, track)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DeckBackdrop(
            url = current?.track?.artworkUri ?: (state as? DeckUiState.Done)?.keptCards?.firstOrNull()?.track?.artworkUri,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.deck_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    swiping?.let {
                        Text(
                            text = stringResource(R.string.deck_counter, it.position + 1, it.cards.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = LocalBottomChromeInset.current),
            ) {
                when (val s = state) {
                    DeckUiState.Dealing -> Centered {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.deck_dealing), style = MaterialTheme.typography.bodyMedium)
                    }
                    DeckUiState.Empty -> Centered {
                        Text(
                            text = stringResource(R.string.deck_empty, serviceLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { navController.popBackStack() }) {
                            Text(stringResource(R.string.deck_back))
                        }
                    }
                    is DeckUiState.Swiping -> DeckBody(
                        state = s,
                        haze = haze,
                        playerViewModel = playerViewModel,
                        canUndo = canUndo,
                        onKeep = viewModel::keep,
                        onSkip = viewModel::skip,
                        onUndo = viewModel::undo,
                    )
                    is DeckUiState.Done -> DeckDone(
                        state = s,
                        haze = haze,
                        saved = saved,
                        onSave = viewModel::saveAsPlaylist,
                        onGoAgain = viewModel::goAgain,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/** Below this, a track is a preview or an interlude, and plays from the start. */
private const val MIN_SEEK_TRACK_MS = 60_000L

/**
 * Moves the player about a third into [track] once it is really playing it.
 *
 * Waits for the player to report this track *and* a length that agrees with
 * the card's, so it never seeks the previous song, and never seeks a 30-second
 * preview standing in for a four-minute song — that would land past its end.
 */
private suspend fun seekPastIntro(player: PlayerViewModel, track: UnifiedTrack) {
    val expectedMs = track.durationSeconds * 1000L
    if (expectedMs < MIN_SEEK_TRACK_MS) return
    val legacyId = track.toLegacyTrack().id
    withTimeoutOrNull(15_000) {
        combine(player.currentTrack, player.durationMs) { playing, duration ->
            playing?.id == legacyId && abs(duration - expectedMs) < 10_000
        }.first { it }
    } ?: return
    player.seekTo(expectedMs / 3)
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** The card's cover, filling the screen behind everything, darkened toward the bottom. */
@Composable
private fun DeckBackdrop(url: String?, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = modifier.background(background)) {
        Crossfade(targetState = url, label = "deckBackdrop", modifier = Modifier.fillMaxSize()) { shown ->
            if (shown != null) {
                AsyncImage(
                    model = shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.35f),
                        0.6f to background.copy(alpha = 0.7f),
                        1f to background,
                    ),
                ),
        )
    }
}

@Composable
private fun reasonText(reason: DeckReason): String = when (reason) {
    is DeckReason.Today -> stringResource(R.string.deck_reason_today, reason.genre)
    is DeckReason.Genre -> stringResource(R.string.deck_reason_genre, reason.genre)
    is DeckReason.Artist -> stringResource(R.string.shelf_because_you_play, reason.playedArtist)
}

@Composable
private fun DeckBody(
    state: DeckUiState.Swiping,
    haze: HazeState,
    playerViewModel: PlayerViewModel,
    canUndo: Boolean,
    onKeep: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val instant = reduceMotion()
    // A fresh offset for each card: the next one starts centred.
    val offset = remember(state.position) { Animatable(0f) }
    // Measured by the card's box below; the buttons fling with the same width.
    var widthPx by remember { mutableFloatStateOf(1f) }
    val keepLabel = stringResource(R.string.deck_keep)
    val skipLabel = stringResource(R.string.action_skip)
    val card = state.current ?: return

    fun fling(keep: Boolean) {
        scope.launch {
            val target = if (keep) widthPx * 1.4f else -widthPx * 1.4f
            if (instant) offset.snapTo(target) else offset.animateTo(target, tween(200))
            if (keep) onKeep() else onSkip()
        }
    }

    // Skip, undo and keep live in the mini player while the deck is up. The
    // bar was showing the very song on the card above it, with play and skip
    // that fought the deck's own skip and keep; now it is the deck's controls,
    // in the same glass, and it goes back to the track when the deck closes.
    // A swipe along the bar does what a swipe of the card does.
    TakeOverMiniPlayer(
        MiniBarTakeover(
            actions = listOf(
                MiniBarAction(Icons.Default.Close, skipLabel, onClick = { fling(keep = false) }),
                MiniBarAction(
                    Icons.AutoMirrored.Filled.Undo,
                    stringResource(R.string.deck_undo),
                    onClick = onUndo,
                    enabled = canUndo,
                    small = true,
                ),
                MiniBarAction(Icons.Default.Favorite, keepLabel, onClick = { fling(keep = true) }),
            ),
            onSwipeLeft = { fling(keep = false) },
            onSwipeRight = { fling(keep = true) },
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 8.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) },
            contentAlignment = Alignment.Center,
        ) {
            val side = min(maxWidth.value, maxHeight.value).dp
            val threshold = widthPx * 0.3f

            state.next?.let { behind ->
                DeckCardFace(
                    card = behind,
                    side = side,
                    modifier = Modifier.graphicsLayer {
                        val lift = (abs(offset.value) / threshold).coerceIn(0f, 1f)
                        val scale = 0.9f + 0.1f * lift
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.5f + 0.5f * lift
                    },
                )
            }
            DeckCardFace(
                card = card,
                side = side,
                stamp = { offset.value / threshold },
                modifier = Modifier
                    .graphicsLayer {
                        translationX = offset.value
                        rotationZ = offset.value / widthPx * 10f
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch { offset.snapTo(offset.value + delta) }
                        },
                        onDragStopped = { velocity ->
                            when {
                                offset.value > threshold || velocity > FLING_VELOCITY -> fling(keep = true)
                                offset.value < -threshold || velocity < -FLING_VELOCITY -> fling(keep = false)
                                instant -> offset.snapTo(0f)
                                else -> offset.animateTo(0f)
                            }
                        },
                    )
                    .clickable(onClickLabel = stringResource(R.string.deck_play_pause)) {
                        playerViewModel.togglePlayPause()
                    }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(keepLabel) { fling(keep = true); true },
                            CustomAccessibilityAction(skipLabel) { fling(keep = false); true },
                        )
                    },
            )
        }

        GlassPanel(
            hazeState = haze,
            glass = LocalMiniPlayerGlass.current,
            avoidNavigationBar = false,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = reasonText(card.reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = card.track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.track.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                PlayingProgress(playerViewModel, card.track)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.deck_hint) + " · " + stringResource(R.string.deck_kept_so_far, state.kept),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How fast a release counts as a flick, in pixels a second. */
private const val FLING_VELOCITY = 1800f

/**
 * One card: the cover, square and large, with a KEEP or SKIP stamp fading in as
 * it is dragged. [stamp] reads the drag as a fraction of the decision
 * threshold, signed, positive leaning to keep. It is read only while drawing,
 * so a drag fades the stamps without recomposing the card on every frame.
 */
@Composable
private fun DeckCardFace(
    card: DeckCard,
    side: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    stamp: (() -> Float)? = null,
) {
    Box(modifier = modifier.size(side)) {
        CoverImage(
            url = card.track.artworkUri,
            contentDescription = card.track.title,
            size = side,
            cornerRadius = 24.dp,
        )
        if (stamp != null) {
            Stamp(R.string.deck_keep, MaterialTheme.colorScheme.primary, -14f, Modifier.align(Alignment.TopStart)) {
                stamp().coerceIn(0f, 1f)
            }
            Stamp(R.string.action_skip, MaterialTheme.colorScheme.error, 14f, Modifier.align(Alignment.TopEnd)) {
                (-stamp()).coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
private fun Stamp(label: Int, color: Color, tilt: Float, modifier: Modifier, strength: () -> Float) {
    Text(
        text = stringResource(label).uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        color = color,
        modifier = modifier
            .padding(20.dp)
            .graphicsLayer {
                alpha = strength()
                rotationZ = tilt
            }
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** How far into the card's song the player is; empty until it is playing that song. */
@Composable
private fun PlayingProgress(player: PlayerViewModel, track: UnifiedTrack) {
    val playing by player.currentTrack.collectAsStateWithLifecycle()
    val position by player.positionMs.collectAsStateWithLifecycle()
    val duration by player.durationMs.collectAsStateWithLifecycle()
    val isThis = playing?.id == track.toLegacyTrack().id && duration > 0
    LinearProgressIndicator(
        progress = { if (isThis) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth(),
        drawStopIndicator = {},
    )
}

/** The end of the stack: what was kept, and what next. */
@Composable
private fun DeckDone(
    state: DeckUiState.Done,
    haze: HazeState,
    saved: Boolean,
    onSave: (String) -> Unit,
    onGoAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    val playlistName = stringResource(R.string.deck_playlist_name, date)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        GlassPanel(hazeState = haze, glass = LocalMiniPlayerGlass.current, avoidNavigationBar = false) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.deck_done_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.deck_done_summary, state.kept, state.dealt),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.deck_done_tomorrow),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.keptCards.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        items(state.keptCards, key = { it.track.id }) { kept ->
                            CoverImage(url = kept.track.artworkUri, contentDescription = kept.track.title, size = 56.dp, cornerRadius = 10.dp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { onSave(playlistName) }, enabled = !saved, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (saved) R.string.deck_saved_playlist else R.string.deck_save_playlist))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = onGoAgain, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.deck_go_again))
                    }
                    TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.deck_back))
                    }
                }
            }
        }
    }
}
