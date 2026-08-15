// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternLength
import tf.monochrome.android.domain.patterns.PatternLimits
import tf.monochrome.android.domain.patterns.PatternScheduler
import tf.monochrome.android.domain.patterns.PatternSwitchMode
import tf.monochrome.android.domain.patterns.TransportState
import kotlin.math.roundToInt

/**
 * The header readout: pattern identity, tempo, meter and loop position.
 *
 * Everything on this line comes from the audio clock rather than from a UI
 * timer, which is why the bar counter stays honest when the app drops frames
 * — it is reporting where the engine is, not counting how long the screen has
 * been open.
 */
@Composable
fun PatternHeader(
    pattern: Pattern,
    transport: TransportState,
    accent: Color,
    modifier: Modifier = Modifier,
    onBpmChange: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var accumulated by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PanelLabel("Pattern")
            Text(
                text = "${PatternLimits.slotName(pattern.bankSlot)}  ${
                    pattern.name.takeIf { it != PatternLimits.slotName(pattern.bankSlot) } ?: ""
                }".trim(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Tempo is a drag target as well as a readout. Horizontal drag is the
        // gesture people already expect on a BPM field, and the tick every
        // whole BPM is what makes it usable without watching the number.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f },
                ) { change, amount ->
                    change.consume()
                    accumulated += amount
                    val steps = (accumulated / BPM_DRAG_DP).roundToInt()
                    if (steps != 0) {
                        accumulated -= steps * BPM_DRAG_DP
                        PatternHaptics.pageChange(haptics)
                        onBpmChange((transport.bpm + steps).coerceIn(20f, 300f))
                    }
                }
            },
        ) {
            PanelLabel("Tempo")
            Text(
                text = "${transport.bpm.roundToInt()} BPM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(horizontalAlignment = Alignment.End) {
            PanelLabel("Bar")
            Text(
                text = if (transport.countInRemaining > 0) {
                    "—"
                } else {
                    "${PatternScheduler.barOfStep(pattern, transport.currentStep)}/${pattern.barCount}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * REC / PLAY / STOP plus the settings that belong beside them.
 *
 * REC is one key, not two: pressing it arms recording *and* starts the loop if
 * it is not already running, so the "REC + PLAY" of a hardware sequencer is
 * one gesture here. Pressing it again disarms without stopping, which is what
 * you want after laying down a part.
 */
@Composable
fun PatternTransport(
    transport: TransportState,
    accent: Color,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onMetronome: (Boolean) -> Unit,
    onSwing: (Float) -> Unit,
    onCountIn: (Int) -> Unit,
    onQuantize: (Int) -> Unit,
    onSwitchMode: (PatternSwitchMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TransportKey(
                label = "Rec",
                active = transport.recording,
                accent = MaterialTheme.colorScheme.error,
                pulsing = transport.recording,
                modifier = Modifier.weight(1f),
                onClick = {
                    PatternHaptics.transport(haptics)
                    onRecord()
                },
            )
            TransportKey(
                label = if (transport.countInRemaining > 0) "Count" else "Play",
                active = transport.playing,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = {
                    PatternHaptics.transport(haptics)
                    onPlay()
                },
            )
            TransportKey(
                label = "Stop",
                active = false,
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    PatternHaptics.transport(haptics)
                    onStop()
                },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PatternPill(
                label = "Click",
                active = transport.metronome,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onMetronome(!transport.metronome) },
            )
            PatternPill(
                // Cycles 0 → 1 → 2 bars. A count-in longer than two bars is
                // something nobody has ever wanted twice.
                label = if (transport.countInBars == 0) "No count" else "${transport.countInBars} bar",
                active = transport.countInBars > 0,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onCountIn((transport.countInBars + 1) % 3) },
            )
            PatternPill(
                label = when (transport.recordQuantum) {
                    0 -> "Free"
                    1 -> "1/16"
                    2 -> "1/8"
                    4 -> "1/4"
                    else -> "Q${transport.recordQuantum}"
                },
                active = transport.recordQuantum > 0,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = {
                    val next = when (transport.recordQuantum) {
                        0 -> 1
                        1 -> 2
                        2 -> 4
                        else -> 0
                    }
                    onQuantize(next)
                },
            )
            PatternPill(
                label = if (transport.switchMode == PatternSwitchMode.NEXT_LOOP) "Sync" else "Now",
                active = transport.switchMode == PatternSwitchMode.NEXT_LOOP,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = {
                    onSwitchMode(
                        if (transport.switchMode == PatternSwitchMode.NEXT_LOOP) {
                            PatternSwitchMode.IMMEDIATE
                        } else {
                            PatternSwitchMode.NEXT_LOOP
                        },
                    )
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        PatternFader(
            label = "Swing",
            value = transport.swing,
            valueRange = 0f..1f,
            display = "${(50 + transport.swing * 16.6f).roundToInt()}%",
            accent = accent,
            onValueChange = onSwing,
        )
    }
}

/** Pattern length, and the destructive pattern actions, in one row. */
@Composable
fun PatternLengthBar(
    pattern: Pattern,
    accent: Color,
    modifier: Modifier = Modifier,
    onLengthChange: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = modifier.fillMaxWidth()) {
        PanelLabel("Length")
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PatternLength.entries.forEach { option ->
                PatternPill(
                    label = option.label,
                    active = pattern.lengthSteps == option.steps,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        PatternHaptics.pageChange(haptics)
                        onLengthChange(option.steps)
                    },
                )
            }
        }
    }
}

/** The pattern menu: duplicate, copy, paste, clear, delete. */
@Composable
fun PatternActionsRow(
    canPaste: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onDuplicate: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PatternPill("Dup", false, Modifier.weight(1f), accent, onClick = onDuplicate)
        PatternPill("Copy", false, Modifier.weight(1f), accent, onClick = onCopy)
        PatternPill("Paste", false, Modifier.weight(1f), accent, enabled = canPaste, onClick = onPaste)
        PatternPill("Clear", false, Modifier.weight(1f), accent, onClick = onClear)
        PatternPill(
            "Del",
            false,
            Modifier.weight(1f),
            MaterialTheme.colorScheme.error,
            onClick = onDelete,
        )
    }
}

/** Roughly 6 dp of travel per BPM — fine control without feeling stuck. */
private const val BPM_DRAG_DP = 6f
