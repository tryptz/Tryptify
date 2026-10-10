package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * What the screen on show asks of the app's floating chrome — the tab bar and
 * the mini player, which the nav host draws above every screen and which no
 * screen otherwise reaches.
 *
 * - [hidden]: the screen wants the whole window (the genre galaxy in full
 *   screen). The chrome steps aside while it is set, and comes back when the
 *   screen lets go or leaves.
 * - [ground]: what the chrome is floating over, when that is not the page. The
 *   galaxy is deep space under every theme, so the mini player's glass there is
 *   dark glass, and its text has to be light ([GlassInkScope]).
 */
@Stable
class AppChrome {
    var hidden by mutableStateOf(false)
        internal set
    var ground by mutableStateOf<Color?>(null)
        internal set
}

val LocalAppChrome = staticCompositionLocalOf<AppChrome?> { null }

/** Hides the tab bar and the mini player while [hidden] and this is in the composition. */
@Composable
fun HideAppChrome(hidden: Boolean) {
    val chrome = LocalAppChrome.current ?: return
    DisposableEffect(chrome, hidden) {
        chrome.hidden = hidden
        onDispose { if (hidden) chrome.hidden = false }
    }
}

/** Tells the floating chrome it is over [ground] while this is in the composition. */
@Composable
fun AppChromeGround(ground: Color) {
    val chrome = LocalAppChrome.current ?: return
    DisposableEffect(chrome, ground) {
        chrome.ground = ground
        onDispose { if (chrome.ground == ground) chrome.ground = null }
    }
}
