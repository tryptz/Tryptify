// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternChannel
import tf.monochrome.android.domain.patterns.PatternScheduler

/**
 * The trig grid for one channel.
 *
 * ## Why two rows of eight
 *
 * Sixteen keys in a single row on a phone gives each one about 22 dp of width.
 * That is half the smallest reliably hittable target, and it is why every
 * mobile step sequencer that tries it is frustrating to use. Two rows of eight
 * doubles the width, keeps the 4/4 reading intact — each row is two beats, so
 * beats stay in the same columns — and costs one row of vertical space. Wider
 * windows raise [columns] to 16 where there is genuinely room for it.
 *
 * ## Why the grid owns its gestures
 *
 * Tap, long-press and drag-to-paint are handled here, at the grid, rather than
 * on each key. Nesting a drag detector inside per-key tap detectors is a fight
 * over who consumes the pointer, and the usual outcome is a drag that paints
 * the first key and then stops. One detector over the whole grid, resolving
 * position to a step index, has no such ambiguity — and it is what makes
 * dragging across eight keys fill all eight rather than one.
 *
 * The value a drag writes is decided by the key it *starts* on: beginning on a
 * lit step erases the run, beginning on a dark one fills it. Deciding per key
 * would invert a mixed run one key at a time, which is never what the gesture
 * means.
 */
@Composable
fun PatternGrid(
    pattern: Pattern,
    channelIndex: Int,
    channel: PatternChannel,
    page: Int,
    playheadStep: Int,
    playing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    columns: Int = DEFAULT_COLUMNS,
    onToggleStep: (Int) -> Unit,
    onLongPressStep: (Int) -> Unit = {},
    onPaintStep: (Int, Boolean) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()

    val stepsPerPage = PatternLooperViewModel.STEPS_PER_PAGE
    val firstStep = page * stepsPerPage
    val visible = (firstStep until minOf(firstStep + stepsPerPage, pattern.lengthSteps)).toList()
    val rows = visible.chunked(columns)
    val rowCount = rows.size.coerceAtLeast(1)

    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val sources = remember(channelIndex, page) { mutableMapOf<Int, MutableInteractionSource>() }
    fun sourceFor(step: Int) = sources.getOrPut(step) { MutableInteractionSource() }

    fun stepAt(offset: Offset): Int? {
        if (gridSize.width <= 0 || gridSize.height <= 0) return null
        val column = (offset.x / (gridSize.width.toFloat() / columns)).toInt()
        val row = (offset.y / (gridSize.height.toFloat() / rowCount)).toInt()
        if (column !in 0 until columns || row !in 0 until rowCount) return null
        val index = row * columns + column
        return visible.getOrNull(index)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { gridSize = it }
            .pointerInput(channelIndex, page, pattern.lengthSteps, columns, visible.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startStep = stepAt(down.position) ?: return@awaitEachGesture
                    val press = PressInteraction.Press(down.position)
                    scope.launch { sourceFor(startStep).emit(press) }

                    var dragging = false
                    var longPressed = false
                    var released = false
                    var paintOn = false
                    var lastPainted = startStep
                    var elapsed = 0L
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                    while (true) {
                        // A finger held perfectly still produces no further
                        // pointer events, so waiting for one would mean the
                        // long press never fires. The timeout is the clock.
                        val waitFor = longPressTimeout - elapsed
                        val change: PointerInputChange? =
                            if (!dragging && !longPressed && waitFor > 0) {
                                val started = System.currentTimeMillis()
                                val event = withTimeoutOrNull(waitFor) { awaitPointerEvent() }
                                elapsed += System.currentTimeMillis() - started
                                if (event == null) {
                                    longPressed = true
                                    PatternHaptics.patternChange(haptics)
                                    onLongPressStep(startStep)
                                    continue
                                }
                                event.changes.firstOrNull { it.id == down.id }
                            } else {
                                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            }

                        if (change == null) break
                        if (!change.pressed) {
                            released = true
                            break
                        }

                        if (!dragging && !longPressed) {
                            val distance = (change.position - down.position).getDistance()
                            if (distance > viewConfiguration.touchSlop) {
                                dragging = true
                                paintOn = !channel.step(startStep).enabled
                                onPaintStep(startStep, paintOn)
                                PatternHaptics.paint(haptics)
                            }
                        }

                        if (dragging) {
                            // Consumed so an enclosing pager or scroll does
                            // not steal the drag halfway through a run.
                            change.consume()
                            val step = stepAt(change.position)
                            if (step != null && step != lastPainted) {
                                lastPainted = step
                                onPaintStep(step, paintOn)
                                PatternHaptics.paint(haptics)
                            }
                        }
                    }

                    scope.launch {
                        sourceFor(startStep).emit(
                            if (released) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press),
                        )
                    }

                    if (released && !dragging && !longPressed) {
                        if (channel.step(startStep).enabled) {
                            PatternHaptics.stepOff(haptics)
                        } else {
                            PatternHaptics.stepOn(haptics)
                        }
                        onToggleStep(startStep)
                    }
                }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp)) {
            rows.forEach { rowSteps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
                ) {
                    rowSteps.forEach { stepIndex ->
                        val step = channel.step(stepIndex)
                        TrigButton(
                            stepNumber = stepIndex + 1,
                            enabled = step.enabled,
                            // A stopped transport shows no playhead at all: a
                            // stationary highlight on step 1 reads as a
                            // selection and makes people think it is running.
                            isPlayhead = playing && playheadStep == stepIndex,
                            accent = accent,
                            muted = channel.muted || !channel.enabled,
                            locked = step.isLocked,
                            beatAccent = PatternScheduler.isBeat(pattern, stepIndex),
                            velocity = step.velocity,
                            interactionSource = sourceFor(stepIndex),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pads the last row of a pattern whose length is not a
                    // multiple of the column count — 12 steps, 5 steps — so
                    // the remaining keys keep their size rather than
                    // stretching across the gap.
                    repeat(columns - rowSteps.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Page selector for patterns longer than one screen of steps.
 *
 * Hidden entirely at 16 steps or fewer — a control that can only ever have one
 * option is noise — and always visible above that, because paging must not
 * depend on knowing a gesture.
 */
@Composable
fun PatternPageBar(
    pageCount: Int,
    page: Int,
    modifier: Modifier = Modifier,
    onPageChange: (Int) -> Unit,
) {
    if (pageCount <= 1) return
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(pageCount) { index ->
            PatternPill(
                label = "${index + 1}",
                active = index == page,
                modifier = Modifier.weight(1f),
                onClick = {
                    PatternHaptics.pageChange(haptics)
                    onPageChange(index)
                },
            )
        }
    }
}

private const val GRID_GAP_DP = 4

/** Eight keys per row: the widest layout that stays thumb-sized on a phone. */
const val DEFAULT_COLUMNS = 8
