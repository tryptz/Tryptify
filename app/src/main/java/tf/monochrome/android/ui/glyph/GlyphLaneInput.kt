// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.input.GlyphKeyBindings

/**
 * The four touch zones, shared by the play and training screens.
 *
 * Multitouch is handled per pointer rather than per zone so a jump lands as two
 * presses instead of the second finger being ignored, and `awaitPointerEvent`
 * is used rather than `detectTapGestures` because a rhythm game needs the down
 * and the up as they happen, not a synthesised tap after the gesture ends.
 *
 * [hitboxScale] narrows each zone toward its own centre, leaving dead gutters
 * between the lanes. That is the only direction the setting can meaningfully
 * go: four lanes already tile the full width, so there is nothing to widen
 * into, and narrowing is a real precision knob — it stops a thumb landing
 * between two lanes from counting as whichever one it happened to graze.
 *
 * Expressed as a fraction of the lane rather than a distance, because the lane
 * width depends on the screen: a fixed inset would be most of a lane on a small
 * phone and a sliver on a tablet, so one setting would mean two difficulties.
 */
@Composable
fun GlyphLaneInput(
    /** The lane, and the touch's own SystemClock.uptimeMillis stamp. */
    onPress: (GlyphLane, Long) -> Unit,
    onRelease: (GlyphLane, Long) -> Unit,
    modifier: Modifier = Modifier,
    hitboxScale: Float = 1f,
) {
    val fraction = hitboxScale.coerceIn(MIN_HITBOX, MAX_HITBOX)

    Row(modifier = modifier) {
        for (lane in GlyphLane.entries) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .semantics { contentDescription = "${lane.label} lane" }
                        .pointerInput(lane) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        if (change.pressed && !change.previousPressed) {
                                            onPress(lane, change.uptimeMillis)
                                            change.consume()
                                        } else if (!change.pressed && change.previousPressed) {
                                            onRelease(lane, change.uptimeMillis)
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        },
                )
            }
        }
    }
}

/** Below this a lane is too narrow to hit deliberately. */
const val MIN_HITBOX = 0.55f

/** Four lanes already tile the width; there is nothing to widen into. */
const val MAX_HITBOX = 1.0f

/**
 * A hardware keyboard on the lanes: each bound key presses its lane while it
 * is down, stamped with the key event's own time (uptime milliseconds, the
 * clock touches are stamped with), so a key is judged when it went down, not
 * when the event reached the screen. A held key's repeats are ignored: a
 * lane is pressed once per press. Escape pauses.
 *
 * The screen holds focus itself (see [rememberGlyphKeyboardFocus]) so the
 * keys reach it without anything to tab to first. [lanes] off lets the keys
 * through, for the pause panel's own controls.
 */
fun Modifier.glyphKeyboard(
    keys: GlyphKeyBindings,
    lanes: Boolean,
    onPress: (GlyphLane, Long) -> Unit,
    onRelease: (GlyphLane, Long) -> Unit,
    onPause: () -> Unit,
    focus: FocusRequester,
): Modifier = this
    .onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode == KEYCODE_ESCAPE) {
            if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) onPause()
            return@onPreviewKeyEvent true
        }
        if (!lanes) return@onPreviewKeyEvent false
        val lane = keys.laneFor(native.keyCode) ?: return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> if (native.repeatCount == 0) onPress(lane, native.eventTime)
            KeyEventType.KeyUp -> onRelease(lane, native.eventTime)
            else -> Unit
        }
        true
    }
    .focusRequester(focus)
    .focusable()

/** A focus requester that takes focus as the screen arrives, for [glyphKeyboard]. */
@Composable
fun rememberGlyphKeyboardFocus(): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return focus
}

/** Android's KEYCODE_ESCAPE. */
private const val KEYCODE_ESCAPE = 111
