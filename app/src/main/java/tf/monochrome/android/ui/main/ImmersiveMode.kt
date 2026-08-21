package tf.monochrome.android.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Whether Settings › System › Full screen is on, published by MainActivity so
 * anything that hides the bars for its own reasons can tell what to restore to.
 *
 * Without it every local fullscreen mode would end by showing the bars again on
 * the way out, silently cancelling the app-wide setting — see [SystemBarsHidden].
 */
val LocalImmersiveFullScreen = staticCompositionLocalOf { false }

/**
 * Hides or shows the status and navigation bars for as long as this composable
 * is in the composition.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is deliberate: with the bars gone
 * there is no other way back to the shade or the recents gesture, so a swipe
 * from an edge has to bring them back for a moment.
 *
 * Restoring is conditional on [LocalImmersiveFullScreen]. A caller that hides
 * the bars for a screen of its own (the visualiser) must not show them again on
 * exit when the whole app is meant to be full screen — the user would leave the
 * visualiser and get their status bar back for good.
 */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    val immersiveApp = LocalImmersiveFullScreen.current
    val keepHidden = rememberUpdatedState(immersiveApp)

    LaunchedEffect(hidden, immersiveApp) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (hidden) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else if (!immersiveApp) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (window != null && !keepHidden.value) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
