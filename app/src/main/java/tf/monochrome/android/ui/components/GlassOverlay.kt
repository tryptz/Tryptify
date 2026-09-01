package tf.monochrome.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Where a floating pane goes when it has to be made of glass.
 *
 * A `Dialog` and a `ModalBottomSheet` each open their own window, and a haze
 * effect cannot sample a layer belonging to another one: the backdrop it wants
 * to blur was captured into the window underneath. Handed a haze state across
 * that boundary it paints its own base colour and comes out as a flat slab,
 * which is the failure `docs/ui-invariants.md` keeps warning about, and there is
 * no setting that fixes it. The pane has to be drawn in the window whose
 * background it is blurring.
 *
 * So this is that window's slot. The host is published by the nav host, which
 * renders whatever is registered as the topmost sibling *of* its haze source —
 * a sibling, not a descendant, because a pane inside the source would be trying
 * to blur a picture it is part of. The page indicator above the pager already
 * works exactly this way.
 *
 * What that costs is the scrim and Back, which `Dialog` gave away for free and
 * are handled here instead. That is the cheaper half of the trade.
 */
@Stable
class GlassOverlayHost {

    internal class Entry(
        val content: @Composable BoxScope.() -> Unit,
        val onDismiss: () -> Unit,
    )

    internal var entry by mutableStateOf<Entry?>(null)
        private set

    internal fun show(e: Entry) {
        entry = e
    }

    /**
     * Withdraw [e], and only [e]. Two panes can overlap for a frame while one
     * replaces another, and a blind clear would take the incoming one down with
     * the outgoing one.
     */
    internal fun hide(e: Entry) {
        if (entry === e) entry = null
    }
}

val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayHost?> { null }

/**
 * Shows [content] in the app's overlay slot for as long as this call is in
 * composition, which makes it a drop-in for the `if (visible) { Dialog(…) }`
 * shape the screens already use.
 *
 * [content] is composed in the *host's* composition rather than the caller's,
 * so it is held in [rememberUpdatedState] and read through it: a lambda
 * captured once would go on rendering the values it closed over, and a dialog
 * showing yesterday's playlist name is a worse bug than one that does not open.
 */
@Composable
fun GlassOverlay(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        // No slot in this window -- a preview, or a screen hosted outside the
        // nav host. Fall back to a plain dialog rather than rendering nothing:
        // it cannot be glass there for the reason above, and a pane that
        // silently fails to open is a far worse answer than a solid one.
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = MonoDimens.shapeLg,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                Box(Modifier.padding(14.dp)) { content() }
            }
        }
        return
    }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    // Keyed on the host alone: the entry reads both lambdas through state, so it
    // never needs re-registering when the caller recomposes.
    val entry = remember(host) {
        GlassOverlayHost.Entry(
            content = { latestContent() },
            onDismiss = { latestDismiss() },
        )
    }
    DisposableEffect(host, entry) {
        host.show(entry)
        onDispose { host.hide(entry) }
    }
}

/**
 * Renders the registered pane. Called by the nav host as the last child of the
 * box that holds its haze source, so this sits above everything and blurs it.
 */
@Composable
fun BoxScope.GlassOverlayLayer(
    host: GlassOverlayHost,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
) {
    val entry = host.entry ?: return

    BackHandler(enabled = true) { entry.onDismiss() }

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // No ripple and no indication: this is a dismiss region, not a
            // button, and a ripple blooming across the whole screen reads as
            // one.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Dismiss",
                onClick = { entry.onDismiss() },
            ),
    )

    Box(
        modifier = Modifier
            .matchParentSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // The playlist pane has text fields in it; without this the keyboard
            // covers the one being typed into.
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            hazeState = hazeState,
            glass = glass,
            modifier = Modifier.fillMaxWidth(0.94f),
            // The layer already holds off the navigation bar; a second inset
            // here would push a centred pane visibly off-centre.
            avoidNavigationBar = false,
        ) {
            entry.content(this)
        }
    }
}
