// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
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
            if (event == Lifecycle.Event.ON_PAUSE && state.gameplay.isPlaying) {
                viewModel.onEvent(GlyphEvent.TogglePause)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.screen != GlyphScreen.HOME) {
        when (state.screen) {
            GlyphScreen.GAMEPLAY, GlyphScreen.TRAINING -> viewModel.onEvent(GlyphEvent.Quit)
            GlyphScreen.RESULTS -> viewModel.onEvent(GlyphEvent.BackToHome)
            GlyphScreen.HOME -> Unit
        }
    }

    // Read straight from the transport's clock rather than from the state: this
    // is called inside the playfield's draw scope, and routing it through
    // recomposition would put the note positions a frame behind the music.
    val positionProvider = remember(viewModel) { { viewModel.transport.positionNow() } }

    when (state.screen) {
        GlyphScreen.HOME -> GlyphHomeScreen(
            state = state,
            assets = assets,
            onEvent = viewModel::onEvent,
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
        )

        GlyphScreen.GAMEPLAY -> GlyphGameplayScreen(
            state = state,
            engine = viewModel.engine,
            assets = assets,
            palette = palette,
            positionProvider = positionProvider,
            onEvent = viewModel::onEvent,
            modifier = modifier.fillMaxSize(),
        )

        GlyphScreen.TRAINING -> GlyphTrainingScreen(
            state = state,
            engine = viewModel.engine,
            assets = assets,
            palette = palette,
            positionProvider = positionProvider,
            onEvent = viewModel::onEvent,
            modifier = modifier.fillMaxSize(),
        )

        GlyphScreen.RESULTS -> {
            val results = state.results
            if (results == null) {
                // Nothing to show means the run was abandoned; going home is
                // the only sensible thing and beats an empty screen.
                LaunchedEffect(Unit) { viewModel.onEvent(GlyphEvent.BackToHome) }
            } else {
                GlyphResultsScreen(
                    results = results,
                    assets = assets,
                    onEvent = viewModel::onEvent,
                    modifier = modifier.fillMaxSize(),
                )
            }
        }
    }
}
