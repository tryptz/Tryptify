package tf.monochrome.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import tf.monochrome.android.ui.theme.reduceMotion

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
        val alignment: Alignment = Alignment.Center,
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
    /**
     * Where the pane sits: centred, as a dialog, or at [Alignment.BottomCenter]
     * for a menu that used to be a bottom sheet and should still be in reach
     * of the thumb.
     */
    alignment: Alignment = Alignment.Center,
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
    val entry = remember(host, alignment) {
        GlassOverlayHost.Entry(
            content = { latestContent() },
            onDismiss = { latestDismiss() },
            alignment = alignment,
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
 *
 * The scrim fades and the pane rises into place, and both go the same way out:
 * the pane that was just withdrawn is held ([held]) and drawn until it has
 * left, so a menu closed by one of its own actions does not vanish mid-frame.
 * Nothing on the way out can be pressed, and Back no longer belongs to it.
 *
 * [bottomClearance] is the app's bottom bar as it stands: a pane anchored at
 * the bottom sits above the bar, not over it.
 */
@Composable
fun BoxScope.GlassOverlayLayer(
    host: GlassOverlayHost,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    bottomClearance: Dp = 0.dp,
) {
    val current = host.entry
    val open = current != null
    // Transition states that start hidden, so the very first pane animates in
    // too: AnimatedVisibility given `visible = true` on its first composition
    // just appears.
    val scrimState = remember { MutableTransitionState(false) }
    val paneState = remember { MutableTransitionState(false) }
    scrimState.targetState = open
    paneState.targetState = open
    // The last pane shown, kept for its exit and let go once it has left. A
    // plain holder rather than state: it only follows [current] and the
    // transitions, which are the state that recomposes this.
    val held = remember { arrayOfNulls<GlassOverlayHost.Entry>(1) }
    if (current != null) {
        held[0] = current
    } else if (paneState.isIdle && !paneState.currentState && scrimState.isIdle && !scrimState.currentState) {
        held[0] = null
    }
    val entry = current ?: held[0] ?: return
    // "Disable animations": the scrim and the pane come and go at once.
    val still = reduceMotion()

    BackHandler(enabled = open) { entry.onDismiss() }

    AnimatedVisibility(
        visibleState = scrimState,
        modifier = Modifier.matchParentSize(),
        enter = if (still) EnterTransition.None else fadeIn(tween(OPEN_MS)),
        exit = if (still) ExitTransition.None else fadeOut(tween(CLOSE_MS)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // No ripple and no indication: this is a dismiss region, not a
                // button, and a ripple blooming across the whole screen reads as
                // one.
                .clickable(
                    enabled = open,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.action_dismiss),
                    onClick = { entry.onDismiss() },
                ),
        )
    }

    val bottom = entry.alignment == Alignment.BottomCenter
    Box(
        modifier = Modifier
            .matchParentSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // The playlist pane has text fields in it; without this the keyboard
            // covers the one being typed into.
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .padding(bottom = if (bottom) bottomClearance else 0.dp),
        contentAlignment = entry.alignment,
    ) {
        AnimatedVisibility(
            visibleState = paneState,
            // A menu rises from below, where it is anchored; a dialog from a
            // little under its place.
            enter = if (still) EnterTransition.None else fadeIn(tween(OPEN_MS)) +
                slideInVertically(tween(OPEN_MS, easing = FastOutSlowInEasing)) { if (bottom) it / 3 else it / 12 },
            exit = if (still) ExitTransition.None else fadeOut(tween(CLOSE_MS)) +
                slideOutVertically(tween(CLOSE_MS)) { if (bottom) it / 4 else it / 16 },
        ) {
            GlassPanel(
                hazeState = hazeState,
                glass = glass,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    // On its way out a pane takes no presses: a second tap on a
                    // menu item would otherwise run it twice. Taken in the
                    // Initial pass, ahead of the buttons inside.
                    .then(if (open) Modifier else Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }),
                // The layer already holds off the navigation bar; a second inset
                // here would push a centred pane visibly off-centre.
                avoidNavigationBar = false,
                // Without glass, a near-solid pane: the list it opened over
                // must not read through the menu's own rows.
                fallbackMinAlpha = FLOATING_FALLBACK_MIN_ALPHA,
            ) {
                entry.content(this)
            }
        }
    }
}

/** How long a pane takes to arrive, and to leave. */
private const val OPEN_MS = 260
private const val CLOSE_MS = 180
