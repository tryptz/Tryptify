package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One control a screen puts in the mini player's place: a glyph, punched into
 * the bar's glass the way play and skip are, and what it does.
 */
class MiniBarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** Drawn a little smaller: the secondary control between two primary ones. */
    val small: Boolean = false,
)

/** What the bar shows instead of the track, while a screen has it. */
class MiniBarTakeover(
    val actions: List<MiniBarAction>,
    /** A swipe along the bar, which otherwise changes track. */
    val onSwipeLeft: (() -> Unit)? = null,
    val onSwipeRight: (() -> Unit)? = null,
)

/**
 * A screen's hold on the bar. The handle itself is stable for as long as the
 * screen is up; only [takeover] inside it changes, and only the bar reads it.
 * That split is load-bearing: the screen hands over a new [MiniBarTakeover] on
 * every composition, and if the nav host read it directly, the nav host would
 * recompose, recompose the screen, and be handed a new one — forever.
 */
@Stable
class MiniBarHandle internal constructor() {
    var takeover by mutableStateOf<MiniBarTakeover?>(null)
        internal set
}

/**
 * The mini player's slot, which a screen can borrow.
 *
 * Swipe to discover plays each card as it comes up, so the bar under it was
 * showing the same song as the card above it, with play and skip that fought
 * the deck's own skip and keep. While the deck is up the bar is the deck's
 * controls instead — same glass, same place — and it goes back to the track the
 * moment the deck leaves the screen.
 *
 * One slot for the app, provided by the nav host around everything, because
 * the bar is drawn by the nav host and not by the screen that borrows it. A
 * screen leaving clears only its own handle, never one a screen arriving has
 * just put there.
 */
@Stable
class MiniPlayerSlot {
    /** The screen holding the bar, if any. Changes when one arrives or leaves. */
    var handle by mutableStateOf<MiniBarHandle?>(null)
        private set

    internal fun hold(handle: MiniBarHandle) {
        this.handle = handle
    }

    internal fun release(handle: MiniBarHandle) {
        if (this.handle === handle) this.handle = null
    }
}

val LocalMiniPlayerSlot = staticCompositionLocalOf { MiniPlayerSlot() }

/**
 * Puts [takeover] in the mini player's place for as long as the caller is in
 * the composition. Updated after every composition, so its callbacks are
 * always the current ones.
 */
@Composable
fun TakeOverMiniPlayer(takeover: MiniBarTakeover) {
    val slot = LocalMiniPlayerSlot.current
    val handle = remember { MiniBarHandle() }
    SideEffect { handle.takeover = takeover }
    DisposableEffect(slot, handle) {
        slot.hold(handle)
        onDispose { slot.release(handle) }
    }
}
