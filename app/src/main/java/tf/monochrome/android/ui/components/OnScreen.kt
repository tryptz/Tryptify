package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Whether the screen this is on can be seen: started — in front of the
 * others, the app in the foreground, the phone awake.
 *
 * For work that runs on its own thread or timer and so does not stop with
 * Compose's frame clock, which pauses by itself when the app is stopped: a
 * render thread, a sensor, an audio tap. Inside a destination the owner is
 * its back stack entry, so a screen pushed over this one counts as not seen.
 */
@Composable
fun rememberOnScreen(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
