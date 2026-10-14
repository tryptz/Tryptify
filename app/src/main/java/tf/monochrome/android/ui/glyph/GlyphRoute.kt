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
import tf.monochrome.android.ui.main.SystemBarsHidden
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * The mode's single destination.
 *
 * Four screens behind one route rather than four routes: they share a running
 * engine, a transport and an audio clock, and putting them on the navigation
 * back stack would mean tearing that down and rebuilding it on every
 * transition. Results-to-training in particular has to keep the same chart
 * loaded, which is what makes "practise this section" one tap.
 */
@Composable
fun GlyphRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GlyphViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val assets = viewModel.assets
    val reduceMotion = reduceMotion()

    var palette by remember { mutableStateOf<GlyphPalette?>(null) }
    LaunchedEffect(Unit) { palette = assets.palette() }

    // The tab bar and the mini player stay on the menus, as on every other
    // pushed screen, and step aside only while a chart is being played or
    // practised: a mini player over the receptor row would cover the one part
    // of the screen the player is looking at, and its transport is not the one
    // driving the chart. While they are aside the nav host runs this route to
    // the bottom edge, so the playfield's black reaches it.
    val playing = state.screen == GlyphScreen.GAMEPLAY || state.screen == GlyphScreen.TRAINING
    HideAppChrome(playing)
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
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back in a run pauses it, and only Back from the pause leaves: the pause
    // panel is where "Quit to songs" lives, so Back walks the same two steps.
    // Quitting on the first press threw away a run to a stray edge swipe, which
    // on a phone held in both thumbs is most of the screen's border.
    BackHandler(enabled = state.screen != GlyphScreen.HOME) {
        when (state.screen) {
            GlyphScreen.GAMEPLAY, GlyphScreen.TRAINING ->
                if (state.gameplay.isPlaying && !state.gameplay.isPaused) {
                    viewModel.onEvent(GlyphEvent.TogglePause)
                } else {
                    viewModel.onEvent(GlyphEvent.Quit)
                }
            GlyphScreen.RESULTS -> viewModel.onEvent(GlyphEvent.BackToHome)
            GlyphScreen.OPTIONS -> viewModel.onEvent(GlyphEvent.CloseOptions)
            GlyphScreen.LEADERBOARD -> viewModel.onEvent(GlyphEvent.CloseLeaderboard)
            GlyphScreen.HOME -> Unit
        }
    }

    // Read straight from the transport's clock rather than from the state: this
    // is called inside the playfield's draw scope, and routing it through
    // recomposition would put the note positions a frame behind the music.
    val positionProvider = remember(viewModel) { { viewModel.transport.positionNow() } }
    val explosionProvider = remember(viewModel) { { viewModel.flashes() } }
    val comboBurstProvider = remember(viewModel) { { viewModel.comboBurstNanos() } }
    val comboProvider = remember(viewModel) { { viewModel.liveCombo() } }

    // The results stay on screen while they fade out. Going home clears them
    // in the same step, and the outgoing screen must not read that as an
    // abandoned run and send the player home a second time. A plain holder,
    // not state: it is written here and only read by the outgoing screen.
    val lastResults = remember { arrayOfNulls<GlyphResultsUi>(1) }
    state.results?.let { lastResults[0] = it }

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
            GlyphScreen.HOME -> GlyphHomeScreen(
                state = state,
                assets = assets,
                onEvent = viewModel::onEvent,
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )

            GlyphScreen.OPTIONS -> GlyphOptionsScreen(
                state = state,
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
                    )
                }
            }
        }
    }
}

private const val SCREEN_FADE_MILLIS = 180
