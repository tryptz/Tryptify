package tf.monochrome.android.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import tf.monochrome.android.performance.LocalLowPerformance

/**
 * The spring every press in the app rides.
 *
 * Tight and barely bouncy — critically damped enough to look like the surface
 * moved under the finger rather than wobbled. It came from the player's dock,
 * which is the press that felt right, and it is shared so that "smooth like the
 * player" is one constant rather than a feel each screen re-invents.
 *
 * The old `bounceClick` default was DampingRatioMediumBouncy (0.5) at
 * StiffnessMedium (400): a long, visibly oscillating settle. On a glyph you
 * barely see it; on a full-width tile it is the wobble that reads as jank.
 *
 * One spring for both directions on purpose. The dock used to pair a bouncy
 * spring up with a tween down and it stuttered — a tween carries no velocity,
 * so releasing mid-bounce snapped the motion still before easing off.
 */
val PressSpring: AnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 900f)

/**
 * The glass dome rising under a finger, and settling back once it lifts.
 *
 * Four places raised this swell — the mini player's controls, the bar itself,
 * the play disc and the skip chevrons — and all four paired a bouncy spring in
 * with `tween(260)` out. That is the pairing the dock had removed from it and
 * the note above explains why: a tween carries no velocity. At the moment a
 * finger lifts the spring is still travelling *upward*, and the tween threw
 * that away and restarted from a standstill. `FastOutSlowInEasing` then spent
 * most of its travel in the first eighty milliseconds, so the dome dropped at
 * once and crawled invisibly through the rest — a snap, not a settle, which is
 * exactly how it read.
 *
 * So: a spring both ways, and a deliberately soft one coming back. Springs hand
 * velocity between them, so the release picks up the rise it interrupted and
 * carries it for a moment before easing down.
 *
 * Critically damped on the way back, not bouncy. It is leaving from a *positive*
 * velocity, so the dome still swells a little further before it falls, which is
 * the spring you can see — and at `dampingRatio = 1` the value cannot cross
 * zero on the way, which a negative bulge would hand straight to the shader.
 */
val GlassPressSpring: AnimationSpec<Float> =
    spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

/** @see GlassPressSpring — the slow half, deliberately about three times its rise. */
val GlassReleaseSpring: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 150f)

/**
 * "Disable animations" (Settings › System › Performance) in the form the UI
 * needs it.
 *
 * There are ~190 animation call sites in the app and switching every one of
 * them on a boolean would be both unreadable and beside the point — a spring on
 * a button press costs nothing next to a shader clock that runs at display
 * refresh for the life of the process. What is covered instead is the two kinds
 * that matter: motion that never stops on its own (infinite transitions, the
 * glass frame clock, colour blends) and motion met on every single interaction
 * (press feedback, tab changes). The latter is handled at its source — see
 * `Modifier.bounceClick` — rather than through a helper here.
 *
 * Compose's own animation APIs already honour the *system* "Remove animations"
 * accessibility setting from 1.2.0. This is the in-app switch beside it, for
 * people who want the OS animating normally and this one app still.
 */
@Composable
@ReadOnlyComposable
fun reduceMotion(): Boolean = LocalLowPerformance.current.disableAnimations

/** [millis] normally; 0 when animations are off. */
@Composable
@ReadOnlyComposable
fun motionMillis(millis: Int): Int = if (reduceMotion()) 0 else millis

/**
 * A value that cycles forever between [initialValue] and [targetValue] — the
 * pulses, sweeps and drifting phases the app uses as ambience — or a constant
 * [still] when animations are off.
 *
 * Returned as `State<Float>` rather than a plain Float on purpose: every caller
 * reads it inside a draw scope so the motion redraws instead of recomposing.
 * When animations are off no transition is created at all, so nothing is
 * scheduled and nothing redraws.
 */
@Composable
fun rememberMotionFloat(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    label: String,
    repeatMode: RepeatMode = RepeatMode.Reverse,
    easing: Easing = LinearEasing,
    still: Float = initialValue,
): State<Float> {
    if (reduceMotion()) return remember(still) { mutableFloatStateOf(still) }
    return rememberInfiniteTransition(label = label).animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = easing),
            repeatMode = repeatMode,
        ),
        label = label,
    )
}

/**
 * Move a pager to [page], sliding when [animated] and jumping when not.
 *
 * Not a Composable, so callers read [reduceMotion] once where they already are
 * and pass the answer down into their coroutine.
 */
suspend fun PagerState.goToPage(page: Int, animated: Boolean) {
    if (animated) animateScrollToPage(page) else scrollToPage(page)
}
