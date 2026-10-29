// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.ui.components.HideAppChrome
import tf.monochrome.android.ui.components.HideMiniPlayer
import tf.monochrome.android.ui.main.SystemBarsHidden
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * The mode's single destination.
 *
 * Every screen behind one route rather than a route each: they share a running
 * engine, a transport and an audio clock, and putting them on the navigation
 * back stack would mean tearing that down and rebuilding it on every
 * transition. Results-to-training in particular has to keep the same chart
 * loaded, which is what makes "practise this section" one tap, and the map
 * editor's test play comes back to the grid it left. It opens on the menu.
 */
@Composable
fun GlyphRoute(
    onBack: () -> Unit,
    /** The rooms screen's Sign in: the app's account page, which is not this route's to show. */
    onSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GlyphViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val backdropLook by viewModel.backdropLook.collectAsStateWithLifecycle()
    val assets = viewModel.assets
    val reduceMotion = reduceMotion()

    var palette by remember { mutableStateOf<GlyphPalette?>(null) }
    LaunchedEffect(Unit) { palette = assets.palette() }
    // The Sync tab was opened from Settings › Audio: closing it goes back there.
    LaunchedEffect(state.leaveRequested) {
        if (state.leaveRequested) {
            viewModel.onEvent(GlyphEvent.LeaveHandled)
            onBack()
        }
    }

    // The tab bar and the mini player stay on the menus, as on every other
    // pushed screen, and step aside only while a chart is being played or
    // practised: a mini player over the receptor row would cover the one part
    // of the screen the player is looking at, and its transport is not the one
    // driving the chart. While they are aside the nav host runs this route to
    // the bottom edge, so the playfield's black reaches it.
    // The map editor is a work surface like the playfield: a mini player over
    // the grid's lower half would cover the measures being edited.
    val playing = state.screen == GlyphScreen.GAMEPLAY || state.screen == GlyphScreen.TRAINING ||
        state.screen == GlyphScreen.EDITOR
    HideAppChrome(playing)
    // The tab bar stays on the menus; the mini player does not. The song
    // panel's pills and the previews sit where it would, and another track's
    // controls over a rhythm game's song select were noise.
    HideMiniPlayer(true)
    // A scored run takes the status and navigation bars too; an edge swipe
    // brings them back for a moment. Training Ground keeps them: it is as much
    // a panel of controls as a playfield, and is left and re-entered often.
    SystemBarsHidden(state.screen == GlyphScreen.GAMEPLAY)

    // The system-wide "disable animations" setting reaches gameplay as a real
    // modifier rather than being ignored: it turns off the explosion and glow
    // work, which is also the most expensive thing on screen during a dense
    // chart.
    LaunchedEffect(reduceMotion) {
        if (state.gameplay.modifiers.reducedMotion != reduceMotion) {
            viewModel.setReducedMotion(reduceMotion)
        }
    }

    // Leaving the app must stop the audio. A rhythm game that keeps playing in
    // the background is both wrong and a way to lose a run to a phone call.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onBackground()
                if (state.gameplay.isPlaying) viewModel.onEvent(GlyphEvent.TogglePause)
            }
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
            // Out of sight, not merely out of focus (a split screen still
            // shows the rooms list): only then does the rooms socket close.
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHidden()
            if (event == Lifecycle.Event.ON_START) viewModel.onShown()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The note art, some twenty megabytes of it, is the mode's: kept from run
    // to run while the mode is up, and let go when it is left rather than held
    // until the next visit. Coming back warms it again.
    DisposableEffect(assets) { onDispose { assets.releaseImages() } }

    // Back in a run pauses it, and only Back from the pause leaves: the pause
    // panel is where "Quit to songs" lives, so Back walks the same two steps.
    // Quitting on the first press threw away a run to a stray edge swipe, which
    // on a phone held in both thumbs is most of the screen's border.
    BackHandler(enabled = state.screen != GlyphScreen.MENU) {
        when (state.screen) {
            GlyphScreen.GAMEPLAY, GlyphScreen.TRAINING ->
                if (state.gameplay.isPlaying && !state.gameplay.isPaused) {
                    viewModel.onEvent(GlyphEvent.TogglePause)
                } else {
                    viewModel.onEvent(GlyphEvent.Quit)
                }
            GlyphScreen.RESULTS -> viewModel.onEvent(if (state.inRoomPlay) GlyphEvent.BackToRoom else GlyphEvent.BackToHome)
            GlyphScreen.OPTIONS -> viewModel.onEvent(GlyphEvent.CloseOptions)
            GlyphScreen.LEADERBOARD -> viewModel.onEvent(GlyphEvent.CloseLeaderboard)
            GlyphScreen.HOME -> viewModel.onEvent(GlyphEvent.BackToMenu)
            GlyphScreen.SETTINGS -> viewModel.onEvent(GlyphEvent.CloseSettings)
            GlyphScreen.PACKS -> viewModel.onEvent(GlyphEvent.ClosePacks)
            GlyphScreen.CHARTING -> viewModel.onEvent(GlyphEvent.CloseCharting)
            GlyphScreen.EDITOR -> viewModel.onEvent(GlyphEvent.CloseEditor)
            // The rooms screen's own handler leaves a room first; this is
            // what is left once it has, or when there was none.
            GlyphScreen.MULTIPLAYER -> viewModel.onEvent(GlyphEvent.CloseMultiplayer)
            GlyphScreen.MENU -> Unit
        }
    }

    // Asked before the first chart is made, from whichever screen made it.
    if (state.askPackFolder) GlyphPackFolderDialog(viewModel::onEvent)
    state.batchOffer?.let { GlyphBatchDialog(it, viewModel::onEvent) }

    // Read straight from the transport's clock rather than from the state: this
    // is called inside the playfield's draw scope, and routing it through
    // recomposition would put the note positions a frame behind the music.
    val positionProvider = remember(viewModel) { { viewModel.fieldPosition() } }
    val explosionProvider = remember(viewModel) { { viewModel.flashes() } }
    val comboBurstProvider = remember(viewModel) { { viewModel.comboBurstNanos() } }
    val comboProvider = remember(viewModel) { { viewModel.liveCombo() } }
    val previewPositionProvider = remember(viewModel) { { viewModel.previewPosition() } }
    val editorBeatProvider = remember(viewModel) { { viewModel.editorBeat() } }

    // The results stay on screen while they fade out. Going home clears them
    // in the same step, and the outgoing screen must not read that as an
    // abandoned run and send the player home a second time. A plain holder,
    // not state: it is written here and only read by the outgoing screen.
    val lastResults = remember { arrayOfNulls<GlyphResultsUi>(1) }
    state.results?.let { lastResults[0] = it }

    // The app's colours follow the song picked on the wheel while the mode is
    // open, as they follow the playing track everywhere else: one source, so
    // the tab bar, the ground and the accent are one song's, never two
    // (DynamicColorSource). The fade is capped at GLYPH_COLOR_BLEND_MAX_MS:
    // songs are skipped through here, and a colour still arriving two picks
    // later is a lag, not a fade. The mode's own screens then take that
    // palette's accent through the contrast-safe tint (AlbumTintScope), as the
    // player takes it, menus or not.
    tf.monochrome.android.ui.theme.OverrideDynamicColorSource(
        coverUrl = gameplayCoverUri(state.selectedSong),
        maxBlendMillis = GLYPH_COLOR_BLEND_MAX_MS,
    )
    tf.monochrome.android.ui.theme.AlbumTintScope(tf.monochrome.android.ui.theme.LocalDynamicColorPalette.current) {
    AnimatedContent(
        targetState = state.screen,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            // A short cross-fade, not a slide: these are one place in four
            // states, not a stack of pages to move between.
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(SCREEN_FADE_MILLIS)) togetherWith fadeOut(tween(SCREEN_FADE_MILLIS))
            }
        },
        label = "glyphScreen",
    ) { screen ->
        when (screen) {
            GlyphScreen.MENU -> GlyphMenuScreen(
                state = state,
                assets = assets,
                palette = palette,
                onEvent = viewModel::onEvent,
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.HOME -> GlyphHomeScreen(
                state = state,
                assets = assets,
                onEvent = viewModel::onEvent,
                onBack = { viewModel.onEvent(GlyphEvent.BackToMenu) },
                previewPosition = previewPositionProvider,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.OPTIONS -> GlyphOptionsScreen(
                state = state,
                assets = assets,
                palette = palette,
                previewPosition = previewPositionProvider,
                previewClip = viewModel.previewClip(),
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.SETTINGS -> GlyphSettingsScreen(
                state = state,
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
                assets = assets,
                palette = palette,
            )

            GlyphScreen.PACKS -> GlyphPacksScreen(
                state = state,
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.CHARTING -> GlyphChartingScreen(
                state = state,
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.MULTIPLAYER -> GlyphMultiplayerScreen(
                state = state,
                onEvent = viewModel::onEvent,
                onSignIn = onSignIn,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.EDITOR -> GlyphEditorScreen(
                state = state,
                palette = palette,
                playingBeat = editorBeatProvider,
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.LEADERBOARD -> GlyphLeaderboardScreen(
                state = state,
                onEvent = viewModel::onEvent,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.GAMEPLAY -> GlyphGameplayScreen(
                state = state,
                backdropLook = backdropLook,
                engine = viewModel.engine,
                assets = assets,
                palette = palette,
                positionProvider = positionProvider,
                onEvent = viewModel::onEvent,
                explosionProvider = explosionProvider,
                comboBurstProvider = comboBurstProvider,
                comboProvider = comboProvider,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.TRAINING -> GlyphTrainingScreen(
                state = state,
                engine = viewModel.engine,
                assets = assets,
                palette = palette,
                positionProvider = positionProvider,
                onEvent = viewModel::onEvent,
                ghost = viewModel.activeGhost,
                explosionProvider = explosionProvider,
                comboBurstProvider = comboBurstProvider,
                comboProvider = comboProvider,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.RESULTS -> {
                // The held copy only for the screen on its way out; the one
                // arriving shows what the state has, or goes home.
                val results = state.results
                    ?: lastResults[0].takeIf { state.screen != GlyphScreen.RESULTS }
                if (results == null) {
                    // Nothing to show means the run was abandoned; going home is
                    // the only sensible thing and beats an empty screen.
                    if (state.screen == GlyphScreen.RESULTS) {
                        LaunchedEffect(Unit) { viewModel.onEvent(GlyphEvent.BackToHome) }
                    }
                } else {
                    GlyphResultsScreen(
                        results = results,
                        assets = assets,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier.fillMaxSize(),
                        reducedMotion = reduceMotion,
                        recentLateMs = state.recentLateMs,
                        room = state.multiplayer.takeIf { state.inRoomPlay },
                    )
                }
            }
        }
    }
    }
}

private const val SCREEN_FADE_MILLIS = 180
