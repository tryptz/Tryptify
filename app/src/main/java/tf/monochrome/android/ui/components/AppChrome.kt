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
 * - [lensDivisor]: the resolution the chrome's live glass runs at, as a
 *   divisor (`LocalLensDivisor`). The galaxy lowers it when the listener
 *   asks for lighter glass, so the bars match the map's own panes.
 */
@Stable
class AppChrome {
    var hidden by mutableStateOf(false)
        internal set
    var ground by mutableStateOf<Color?>(null)
        internal set
    var lensDivisor by mutableStateOf(1)
        internal set

    // Who set each of them last. Only that screen puts it back: the next screen
    // is composed before the last one is disposed, so a screen that leaves
    // after another arrived must leave the newcomer's value alone. Comparing
    // values instead failed when both asked for the same one (the genre galaxy
    // and the library galaxy are both deep space): the one leaving cleared it.
    internal var hiddenBy: Any? = null
    internal var groundBy: Any? = null
    internal var lensBy: Any? = null
}

val LocalAppChrome = staticCompositionLocalOf<AppChrome?> { null }

/** Hides the tab bar and the mini player while [hidden] and this is in the composition. */
@Composable
fun HideAppChrome(hidden: Boolean) {
    val chrome = LocalAppChrome.current ?: return
    DisposableEffect(chrome, hidden) {
        val me = Any()
        chrome.hidden = hidden
        chrome.hiddenBy = me
        onDispose {
            if (chrome.hiddenBy === me) {
                chrome.hidden = false
                chrome.hiddenBy = null
            }
        }
    }
}

/** Tells the floating chrome it is over [ground] while this is in the composition. */
@Composable
fun AppChromeGround(ground: Color) {
    val chrome = LocalAppChrome.current ?: return
    DisposableEffect(chrome, ground) {
        val me = Any()
        chrome.ground = ground
        chrome.groundBy = me
        onDispose {
            if (chrome.groundBy === me) {
                chrome.ground = null
                chrome.groundBy = null
            }
        }
    }
}

/** Runs the floating chrome's live glass at 1 / [divisor] resolution while this is in the composition. */
@Composable
fun AppChromeLens(divisor: Int) {
    val chrome = LocalAppChrome.current ?: return
    DisposableEffect(chrome, divisor) {
        val me = Any()
        chrome.lensDivisor = divisor
        chrome.lensBy = me
        onDispose {
            if (chrome.lensBy === me) {
                chrome.lensDivisor = 1
                chrome.lensBy = null
            }
        }
    }
}
