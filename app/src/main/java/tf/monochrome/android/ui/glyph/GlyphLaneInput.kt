// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPopnLane
import tf.monochrome.android.glyph.input.GlyphKeyBindings

/**
 * The touch zones, shared by the play and training screens: one a lane down
 * the whole field for dance, or pop'n's two-row pad of nine buttons along the
 * bottom ([GlyphPopnPadInput]).
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
    /**
     * The share of the width the field is drawn in ([glyphFieldFraction]):
     * below 1, the zones follow the centred lanes ([glyphLaneSpans]).
     */
    fieldFraction: Float = 1f,
    /** Which buttons the zones are: the dance panels, or pop'n's nine. */
    course: CourseType = CourseType.DANCE,
    /** The lanes held now, which pop'n's pad lights. */
    heldLanes: Set<GlyphLane> = emptySet(),
) {
    if (course == CourseType.POPN) {
        GlyphPopnPadInput(onPress = onPress, onRelease = onRelease, heldLanes = heldLanes, modifier = modifier)
        return
    }
    val fraction = hitboxScale.coerceIn(MIN_HITBOX, MAX_HITBOX)
    val lanes = GlyphLane.forCourse(course)

    if (fieldFraction >= 1f) {
        Row(modifier = modifier) {
            for (lane in lanes) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    LaneZone(
                        lane = lane,
                        onPress = onPress,
                        onRelease = onRelease,
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    } else {
        Layout(
            modifier = modifier,
            content = {
                for (lane in lanes) {
                    LaneZone(lane = lane, onPress = onPress, onRelease = onRelease)
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val spans = glyphLaneSpans(width, fieldFraction, fraction, lanes.size)
            val placeables = measurables.mapIndexed { i, measurable ->
                measurable.measure(Constraints.fixed((spans[i].last + 1 - spans[i].first).coerceAtLeast(0), height))
            }
            layout(width, height) {
                placeables.forEachIndexed { i, placeable -> placeable.place(spans[i].first, 0) }
            }
        }
    }
}

/**
 * pop'n's controller on the screen: nine buttons in two rows along the
 * bottom, the five odd ones below and the four even ones above, each over
 * the gap between two below, as the cabinet's are. Each is a touch zone and
 * draws its own button, so a thumb lands where the button is seen. The
 * field above is not touchable: pop'n is played on its buttons, and a
 * zone the height of the screen nine lanes wide would be a thumb's width.
 */
@Composable
fun GlyphPopnPadInput(
    onPress: (GlyphLane, Long) -> Unit,
    onRelease: (GlyphLane, Long) -> Unit,
    heldLanes: Set<GlyphLane>,
    modifier: Modifier = Modifier,
) {
    val held by rememberUpdatedState(heldLanes)
    Layout(
        modifier = modifier,
        content = {
            for (button in GlyphPopnLane.entries) {
                val lane = button.lane
                LaneZone(
                    lane = lane,
                    label = button.label,
                    onPress = onPress,
                    onRelease = onRelease,
                    modifier = Modifier.drawBehind {
                        val sizePx = minOf(size.width, size.height) * POPN_BUTTON_SHARE
                        with(GlyphPopnArt) {
                            drawPopnButton(center, sizePx, lane, held = lane in held)
                        }
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val geometry = popnPadGeometry(width, height, POPN_ROW_HEIGHT_DP.dp.roundToPx(), POPN_MAX_ROW_HEIGHT_DP.dp.roundToPx())
        val placeables = measurables.mapIndexed { index, measurable ->
            val cell = geometry.cell(GlyphPopnLane.entries[index])
            measurable.measure(Constraints.fixed(cell.width, cell.height))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val cell = geometry.cell(GlyphPopnLane.entries[index])
                placeable.place(cell.left, cell.top)
            }
        }
    }
}

/** One button's zone on the pad, in pixels. */
data class PopnPadCell(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where the pad's buttons are on a field [widthPx] by [heightPx]: two rows
 * of [rowHeightPx] along the bottom, the lower of five cells a fifth of the
 * width each, the upper of four the same width, each starting half a cell in.
 */
class PopnPadGeometry(val widthPx: Int, val heightPx: Int, val rowHeightPx: Int) {
    val cellWidth: Int get() = widthPx / 5
    val padTop: Int get() = (heightPx - rowHeightPx * 2).coerceAtLeast(0)

    fun cell(button: GlyphPopnLane): PopnPadCell {
        val column = (button.button - 1) / 2
        return if (button.isBottomRow) {
            // The last cell takes the pixels the division left over.
            val left = cellWidth * column
            val width = if (column == 4) widthPx - left else cellWidth
            PopnPadCell(left, padTop + rowHeightPx, width, rowHeightPx)
        } else {
            PopnPadCell(cellWidth / 2 + cellWidth * column, padTop, cellWidth, rowHeightPx)
        }
    }

    /** The button under ([x], [y]), or null off the pad. */
    fun buttonAt(x: Int, y: Int): GlyphPopnLane? = GlyphPopnLane.entries.firstOrNull { button ->
        val c = cell(button)
        x >= c.left && x < c.left + c.width && y >= c.top && y < c.top + c.height
    }
}

/** A row [minRowPx] tall at the least, [maxRowPx] at most, else a share of the height. */
fun popnPadGeometry(widthPx: Int, heightPx: Int, minRowPx: Int, maxRowPx: Int): PopnPadGeometry =
    PopnPadGeometry(widthPx, heightPx, (heightPx * POPN_ROW_SHARE).toInt().coerceIn(minRowPx, maxRowPx.coerceAtLeast(minRowPx)))

/** Each pad row's share of the field's height, within the limits below. */
private const val POPN_ROW_SHARE = 0.14f
private const val POPN_ROW_HEIGHT_DP = 64
private const val POPN_MAX_ROW_HEIGHT_DP = 112

/** A button's disc, as a share of its cell's smaller side. */
private const val POPN_BUTTON_SHARE = 0.78f

/** One lane's touch zone: each pointer's down and up, as they happen. */
@Composable
private fun LaneZone(
    lane: GlyphLane,
    onPress: (GlyphLane, Long) -> Unit,
    onRelease: (GlyphLane, Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = lane.label,
) {
    Box(
        modifier = modifier
            .semantics { contentDescription = "$label lane" }
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
    /** Whose keys: the course being played. */
    course: CourseType = CourseType.DANCE,
): Modifier = this
    .onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode == KEYCODE_ESCAPE) {
            if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) onPause()
            return@onPreviewKeyEvent true
        }
        if (!lanes) return@onPreviewKeyEvent false
        val lane = keys.laneFor(native.keyCode, course) ?: return@onPreviewKeyEvent false
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
