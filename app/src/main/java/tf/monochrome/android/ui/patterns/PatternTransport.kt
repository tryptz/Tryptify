// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import tf.monochrome.android.ui.theme.MonoDimens
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
import androidx.compose.runtime.mutableStateOf
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
import tf.monochrome.android.domain.patterns.StretchQuality
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
    var editingBpm by remember { mutableStateOf(false) }

    if (editingBpm) {
        BpmEditorDialog(
            bpm = transport.bpm,
            accent = accent,
            onDismiss = { editingBpm = false },
            onBpmChange = onBpmChange,
        )
    }

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

        // Tempo takes three inputs, because the three ways people arrive at a
        // number are genuinely different. Drag is for nudging by feel while
        // the loop runs. Tapping opens the editor, for when the number is
        // already known — 128, or whatever the track it came from says. And
        // the editor carries tap tempo, for when it is not known at all.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .clip(MonoDimens.shapeSm)
                .clickable { editingBpm = true }
                .pointerInput(Unit) {
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
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            PanelLabel("Tempo")
            Text(
                // One decimal only when there is one: a tempo matched to a
                // sampled loop is rarely whole, and 124.0 everywhere else is
                // noise on a readout that has to be glanceable.
                text = formatBpm(transport.bpm),
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
    stretchQuality: StretchQuality,
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
    onStretchQuality: (StretchQuality) -> Unit,
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

        Spacer(Modifier.height(10.dp))
        StretchQualityRow(quality = stretchQuality, accent = accent, onQuality = onStretchQuality)
    }
}

/**
 * How much CPU the time stretcher may spend, globally.
 *
 * Here rather than in the per-channel editor because it is one setting for the
 * whole engine, and here rather than buried in app settings because the
 * trade-off is musical: the cheapest mode cannot hold a bass line together,
 * and the person who needs to know that is the person stretching a bass line,
 * not the person browsing preferences.
 *
 * The subtitle names the frequency each mode holds down to instead of saying
 * "better quality", because that is the actual difference and it is the kind
 * of difference someone can act on.
 */
@Composable
private fun StretchQualityRow(
    quality: StretchQuality,
    accent: Color,
    onQuality: (StretchQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    PanelLabel("Stretch quality")
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StretchQuality.entries.forEach { option ->
            PatternPill(
                label = option.label,
                active = option == quality,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = {
                    PatternHaptics.pageChange(haptics)
                    onQuality(option)
                },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = quality.detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * Type a tempo, nudge it, or tap it in.
 *
 * The text field is the point of this dialog — a tempo you already know
 * should not have to be dragged to. It is validated but not policed: the field
 * accepts whatever is typed and the OK key is simply unavailable until it
 * parses, which is far less irritating than a field that fights every
 * keystroke or silently rewrites what you entered.
 *
 * Tap tempo averages the last few intervals rather than using the most recent
 * one, because a single interval carries all the jitter of one imprecise tap.
 */
@Composable
fun BpmEditorDialog(
    bpm: Float,
    accent: Color,
    onDismiss: () -> Unit,
    onBpmChange: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var text by remember { mutableStateOf(formatBpm(bpm)) }
    val taps = remember { mutableListOf<Long>() }
    var tapHint by remember { mutableStateOf<String?>(null) }

    val parsed = text.trim().toFloatOrNull()
    val valid = parsed != null && parsed >= PatternScheduler.MIN_BPM && parsed <= PatternScheduler.MAX_BPM

    fun commit(value: Float) {
        val clamped = value.coerceIn(PatternScheduler.MIN_BPM, PatternScheduler.MAX_BPM)
        text = formatBpm(clamped)
        onBpmChange(clamped)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("BPM") },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    supportingText = {
                        Text(
                            if (text.isNotBlank() && !valid) {
                                "Between ${PatternScheduler.MIN_BPM.roundToInt()} and " +
                                    "${PatternScheduler.MAX_BPM.roundToInt()}"
                            } else {
                                "Applies as you change it"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Whole-BPM nudges and halve/double. Halving and doubling
                    // is here because a tempo detected from a loop is very
                    // often out by exactly that factor.
                    PatternPill("−1", false, Modifier.weight(1f), accent) {
                        commit((parsed ?: bpm) - 1f)
                    }
                    PatternPill("+1", false, Modifier.weight(1f), accent) {
                        commit((parsed ?: bpm) + 1f)
                    }
                    PatternPill("÷2", false, Modifier.weight(1f), accent) {
                        commit((parsed ?: bpm) / 2f)
                    }
                    PatternPill("×2", false, Modifier.weight(1f), accent) {
                        commit((parsed ?: bpm) * 2f)
                    }
                }

                Spacer(Modifier.height(10.dp))
                PatternPill(
                    label = tapHint ?: "Tap tempo",
                    active = tapHint != null,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PatternHaptics.transport(haptics)
                    val now = System.currentTimeMillis()
                    // A long gap means a new attempt rather than a very slow
                    // tempo, so the history is dropped instead of averaged
                    // with taps from a minute ago.
                    if (taps.isNotEmpty() && now - taps.last() > TAP_RESET_MS) taps.clear()
                    taps.add(now)
                    if (taps.size > TAP_HISTORY) taps.removeAt(0)

                    if (taps.size >= 2) {
                        val span = (taps.last() - taps.first()).toFloat()
                        val intervals = taps.size - 1
                        val perBeat = span / intervals
                        if (perBeat > 1f) {
                            val detected = 60_000f / perBeat
                            tapHint = "${formatBpm(detected)} — keep tapping"
                            commit(detected)
                        }
                    } else {
                        tapHint = "Keep tapping…"
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    parsed?.let(::commit)
                    onDismiss()
                },
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** One decimal only when the tempo actually has one. */
internal fun formatBpm(bpm: Float): String {
    val rounded = (bpm * 10f).roundToInt() / 10f
    return if (rounded % 1f == 0f) "${rounded.roundToInt()}" else "$rounded"
}

/** Roughly 6 dp of travel per BPM — fine control without feeling stuck. */
private const val BPM_DRAG_DP = 6f

/** Taps averaged together. Four covers a bar of 4/4 without lagging behind. */
private const val TAP_HISTORY = 5

/** A gap longer than this starts a new tap-tempo attempt. */
private const val TAP_RESET_MS = 2_500L
