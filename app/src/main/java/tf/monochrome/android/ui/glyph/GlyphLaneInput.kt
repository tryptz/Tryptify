// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import tf.monochrome.android.glyph.asset.GlyphLane

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
    onPress: (GlyphLane) -> Unit,
    onRelease: (GlyphLane) -> Unit,
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
                                            onPress(lane)
                                            change.consume()
                                        } else if (!change.pressed && change.previousPressed) {
                                            onRelease(lane)
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
