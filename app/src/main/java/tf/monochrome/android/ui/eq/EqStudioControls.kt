package tf.monochrome.android.ui.eq

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop

/**
 * A value as a pill of the app's glass that is also its control: swipe it
 * sideways to scrub the value, tap it to type an exact one.
 *
 * A slider needs a track as wide as the screen to be precise, and three of them
 * per filter pushed the graph off the screen on the classic editor. A scrub
 * pill is a third of a row and as precise as the caller's scale, and the graph
 * stays in view while it moves. [tick] is the caller's coarse step (a whole
 * dB, a sixth of an octave): each time it changes under the finger the phone
 * ticks, so the value can be felt without looking down at the number.
 *
 * The drag is reported as the total distance since the finger went down, in
 * dp, never as deltas: the caller recomputes from the value at [onDragStart],
 * so clamping at an end of the range cannot make the value drift.
 */
@Composable
internal fun ScrubPill(
    label: String,
    value: String,
    tick: Int,
    onDragStart: () -> Unit,
    onDrag: (totalDp: Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestTick by rememberUpdatedState(tick)
    var lastTick by remember { mutableIntStateOf(tick) }
    var total by remember { mutableFloatStateOf(0f) }
    val scrubHint = stringResource(R.string.eq_studio_scrub_hint)

    GlassPill(
        onClick = onTap,
        hazeState = LocalGlassBackdrop.current,
        enabled = enabled,
        height = height,
        onClickLabel = stringResource(R.string.eq_studio_type_value),
        modifier = modifier
            .semantics {
                contentDescription = label
                stateDescription = "$value. $scrubHint"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        total = 0f
                        lastTick = latestTick
                        latestDragStart()
                    },
                ) { change, dx ->
                    change.consume()
                    total += dx
                    latestDrag(with(density) { total.toDp().value })
                    if (latestTick != lastTick) {
                        lastTick = latestTick
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Typing an exact value: the other half of a [ScrubPill]. Accepts a comma as
 * the decimal mark, since half the app's languages write one, and refuses to
 * close on text that is not a number rather than guessing at it.
 */
@Composable
internal fun StudioValueDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val parsed = text.trim().replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm); onDismiss() }) {
                Text(stringResource(R.string.eq_studio_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
