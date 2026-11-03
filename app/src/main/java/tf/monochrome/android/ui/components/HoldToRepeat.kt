// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A step button held down keeps stepping: after [HOLD_MS] it steps again
 * and again, faster as the hold goes on, until the finger lifts or slides
 * away. For steppers whose value is far from where it needs to be (an
 * audio offset of 180 ms at 5 ms a tap), so the listener holds instead of
 * tapping thirty-six times.
 *
 * Goes on the button itself, outside its click: it watches the pointer
 * before the click does and does not consume a tap, so a short press is
 * still one click with its own feedback. Once a hold has stepped, the
 * lift is consumed, so the click under it does not add one more step.
 */
fun Modifier.holdToRepeat(enabled: Boolean = true, onStep: () -> Unit): Modifier = composed {
    val step by rememberUpdatedState(onStep)
    val on by rememberUpdatedState(enabled)
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!on) return@awaitEachGesture
                var stepped = false
                val ticker = launch {
                    delay(HOLD_MS)
                    var period = FIRST_PERIOD_MS
                    while (isActive && on) {
                        stepped = true
                        step()
                        delay(period)
                        period = (period * SPEED_UP).toLong().coerceAtLeast(FASTEST_PERIOD_MS)
                    }
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (stepped) change.consume()
                            break
                        }
                        // Slid away (a scroll, a change of mind): no more steps, and
                        // the click under it cancels by the same rule.
                        if ((change.position - down.position).getDistance() > slop) break
                    }
                } finally {
                    ticker.cancel()
                }
            }
        }
    }
}

/** How long a press is held before it is a hold, not a tap. */
private const val HOLD_MS = 400L
/** The first repeat's gap, and the floor it speeds up to. */
private const val FIRST_PERIOD_MS = 160L
private const val FASTEST_PERIOD_MS = 45L
private const val SPEED_UP = 0.85
