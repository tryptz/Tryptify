package tf.monochrome.android.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription

/**
 * Shared accessibility helpers for the app's custom Canvas controls.
 *
 * The player, mixer and EQ are built from raw [androidx.compose.foundation.Canvas]
 * widgets (faders, knobs, the seek tube, mute/solo boxes). Canvas emits no
 * semantics of its own, so TalkBack announced them as unlabeled — or not at all.
 * These modifiers attach the missing semantics without changing the visuals.
 */

/**
 * Semantics for a custom control that represents a value along a range —
 * a fader, knob, or seek bar. TalkBack reads [label] and [stateText], and the
 * [setProgress] action lets the user adjust it with swipe up/down.
 */
fun Modifier.adjustableSemantics(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    stateText: (Float) -> String,
    onValueChange: (Float) -> Unit,
): Modifier = semantics {
    contentDescription = label
    val clamped = value.coerceIn(range.start, range.endInclusive)
    progressBarRangeInfo = ProgressBarRangeInfo(clamped, range)
    stateDescription = stateText(value)
    setProgress { target ->
        onValueChange(target.coerceIn(range.start, range.endInclusive))
        true
    }
}

/** Button role + spoken [label] (and optional [state]) for a custom clickable. */
fun Modifier.buttonSemantics(
    label: String,
    state: String? = null,
): Modifier = semantics {
    contentDescription = label
    role = Role.Button
    if (state != null) stateDescription = state
}

/** Switch role + [label] + on/off state for mute/solo-style toggle boxes. */
fun Modifier.toggleSemantics(
    label: String,
    checked: Boolean,
): Modifier = semantics {
    contentDescription = label
    role = Role.Switch
    stateDescription = if (checked) "On" else "Off"
}
