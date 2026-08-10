package tf.monochrome.android.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import tf.monochrome.android.performance.LocalLowPerformance

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
