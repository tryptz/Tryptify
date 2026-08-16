// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.patterns.PatternChannel
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.domain.patterns.SpeedPitchPreset
import tf.monochrome.android.ui.sampler.SamplePickerSheet
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The SOUND sheet for one channel.
 *
 * Scoped on purpose. Volume, pan, pitch, trim, envelope and filter are the
 * controls that change what a hit sounds like in a pattern; anything beyond
 * them belongs in the sample editor, where the audio itself is being changed
 * rather than how the channel plays it.
 *
 * Every control here writes straight through to the engine as a parameter
 * command, so a fader moved while the loop runs is audible on the next hit
 * without republishing the pattern or touching the database. Persistence is
 * debounced behind that, which is why a drag does not put Room in the path of
 * a gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternSoundEditorSheet(
    channelIndex: Int,
    channel: PatternChannel,
    sample: SampleRef?,
    library: List<SampleRef>,
    accent: Color,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAssignSample: (SampleRef?) -> Unit,
    onVolume: (Float) -> Unit,
    onPan: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onSampleStart: (Float) -> Unit,
    onSampleEnd: (Float) -> Unit,
    onAttack: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onFilter: (Float) -> Unit,
    onReverse: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onLinked: (Boolean) -> Unit,
    onPreset: (SpeedPitchPreset) -> Unit,
    onResetSpeedPitch: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onAudition: () -> Unit,
    onClearSteps: () -> Unit,
    onRemoveChannel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(channelIndex) { mutableStateOf(channel.name) }
    var showBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(channel.name) { name = channel.name }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Sound",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onRename(it)
                },
                label = { Text("Channel name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            PanelLabel("Sample")
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PatternPill(
                    label = sample?.name ?: "Choose sample",
                    active = sample != null,
                    accent = accent,
                    modifier = Modifier.weight(2f),
                    onClick = { showBrowser = true },
                )
                PatternPill(
                    label = "Play",
                    active = false,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    enabled = sample != null,
                    onClick = onAudition,
                )
            }

            Spacer(Modifier.height(14.dp))

            PatternFader(
                label = "Volume",
                value = channel.volume,
                valueRange = 0f..2f,
                display = formatGainDb(channel.volume),
                accent = accent,
                onValueChange = onVolume,
            )
            PatternFader(
                label = "Pan",
                value = channel.pan,
                valueRange = -1f..1f,
                display = formatPan(channel.pan),
                accent = accent,
                onValueChange = onPan,
            )

            Spacer(Modifier.height(10.dp))
            SpeedPitchSection(
                channel = channel,
                accent = accent,
                onSpeed = onSpeed,
                onPitch = onPitch,
                onLinked = onLinked,
                onPreset = onPreset,
                onReset = onResetSpeedPitch,
            )
            Spacer(Modifier.height(10.dp))

            PatternFader(
                label = "Start",
                value = channel.sampleStart,
                valueRange = 0f..0.95f,
                display = "${(channel.sampleStart * 100).roundToInt()}%",
                accent = accent,
                onValueChange = onSampleStart,
            )
            PatternFader(
                label = "End",
                value = channel.sampleEnd,
                valueRange = 0.05f..1f,
                display = "${(channel.sampleEnd * 100).roundToInt()}%",
                accent = accent,
                onValueChange = onSampleEnd,
            )
            PatternFader(
                label = "Attack",
                value = channel.attackMs,
                valueRange = 0f..500f,
                display = "${channel.attackMs.roundToInt()} ms",
                accent = accent,
                onValueChange = onAttack,
            )
            PatternFader(
                label = "Release",
                value = channel.releaseMs,
                valueRange = 1f..2000f,
                display = "${channel.releaseMs.roundToInt()} ms",
                accent = accent,
                onValueChange = onRelease,
            )
            PatternFader(
                label = "Filter",
                value = channel.filterHz,
                valueRange = 200f..PatternChannel.FILTER_OFF,
                display = if (channel.filterHz >= 20000f) {
                    "Off"
                } else {
                    "${channel.filterHz.roundToInt()} Hz"
                },
                accent = accent,
                onValueChange = onFilter,
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PatternPill(
                    label = "Reverse",
                    active = channel.reverse,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onReverse(!channel.reverse) },
                )
                PatternPill(
                    label = "Mute",
                    active = channel.muted,
                    accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = onMute,
                )
                PatternPill(
                    label = "Solo",
                    active = channel.soloed,
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = onSolo,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PatternPill(
                    label = "Clear steps",
                    active = false,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                    onClick = onClearSteps,
                )
                PatternPill(
                    label = "Remove channel",
                    active = false,
                    accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onRemoveChannel()
                        onDismiss()
                    },
                )
            }
        }
    }

    if (showBrowser) {
        SamplePickerSheet(
            library = library,
            selected = sample,
            accent = accent,
            onDismiss = { showBrowser = false },
            onPick = {
                onAssignSample(it)
                showBrowser = false
            },
        )
    }
}

/**
 * The step editor, reached by long-pressing a trig.
 *
 * v1 exposes velocity only, which is the one per-step value the engine
 * actually applies. The sheet exists now rather than later because the
 * gesture and the data model are already in place for the rest — pitch,
 * probability and pan are stored per step and read by the audio thread, so
 * turning them on is a matter of adding faders here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepEditorSheet(
    stepNumber: Int,
    velocity: Int,
    accent: Color,
    onDismiss: () -> Unit,
    onVelocity: (Int) -> Unit,
    onClearStep: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Step %02d".format(stepNumber),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            PatternFader(
                label = "Velocity",
                value = velocity.toFloat(),
                valueRange = 1f..127f,
                display = "$velocity",
                accent = accent,
                onValueChange = { onVelocity(it.roundToInt()) },
            )
            Spacer(Modifier.height(10.dp))
            PatternPill(
                label = "Clear step",
                active = false,
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onClearStep()
                    onDismiss()
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pitch, probability and pan are stored per step and will " +
                    "appear here once parameter locks are switched on.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Speed and pitch, together, because the interesting thing about them is the
 * relationship.
 *
 * Two sliders and a link. Unlinked they are genuinely independent — the
 * stretcher changes how long the sample takes without changing the note, and
 * the pitch slider changes the note without changing the length. Linked they
 * collapse into one varispeed factor and the readout says so, because a user
 * who drags speed in tape mode and sees the pitch number sit still would
 * reasonably conclude the app is broken.
 *
 * The speed slider runs in octaves rather than in multiples. Linear travel
 * over 0.25 to 4.0 would put unity a fifth of the way along and squash
 * everything below it into the first centimetre of the track; in log space
 * unity sits dead centre and half time and double time are the same distance
 * out on either side.
 */
@Composable
private fun SpeedPitchSection(
    channel: PatternChannel,
    accent: Color,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onLinked: (Boolean) -> Unit,
    onPreset: (SpeedPitchPreset) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelLabel("Time and pitch")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternPill(
                label = if (channel.linked) "Linked" else "Free",
                active = channel.linked,
                accent = accent,
                onClick = { onLinked(!channel.linked) },
            )
            PatternPill(
                label = "Reset",
                active = false,
                accent = accent,
                enabled = !channel.isAtNaturalRate || channel.linked,
                onClick = onReset,
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = if (channel.linked) {
            "Tape. Faster is higher \u2014 the two move together."
        } else {
            "Independent. Speed does not change the note."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    PatternFader(
        label = "Speed",
        value = log2(channel.speed.coerceIn(PatternChannel.MIN_SPEED, PatternChannel.MAX_SPEED)),
        valueRange = -2f..2f,
        display = formatSpeed(channel.speed),
        accent = accent,
        onValueChange = { onSpeed(2f.pow(it)) },
    )
    PatternFader(
        label = if (channel.linked) "Pitch (follows speed)" else "Pitch",
        value = channel.pitch,
        valueRange = PatternChannel.MIN_PITCH..PatternChannel.MAX_PITCH,
        display = formatSemitones(if (channel.linked) linkedSemitones(channel) else channel.pitch),
        accent = accent,
        onValueChange = onPitch,
    )

    Spacer(Modifier.height(8.dp))
    // Two rows of four rather than a scrolling strip: eight is few enough to
    // show at once, and a preset you have to hunt for is slower than the
    // slider it was meant to save you from.
    val presets = SpeedPitchPreset.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    PatternPill(
                        label = preset.label,
                        active = channel.speed == preset.speed &&
                            channel.pitch == preset.semitones &&
                            channel.linked == preset.linked,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onPreset(preset) },
                    )
                }
            }
        }
    }
}

/** In tape mode the audible pitch is speed and pitch multiplied together. */
private fun linkedSemitones(channel: PatternChannel): Float =
    channel.pitch + 12f * log2(channel.speed.coerceAtLeast(0.01f))

private fun formatSpeed(speed: Float): String = when {
    speed >= 1f -> "%.2fx".format(speed)
    else -> "%.3fx".format(speed).trimEnd('0').trimEnd('.') + "x"
}

private fun formatSemitones(st: Float): String {
    val sign = if (st >= 0f) "+" else ""
    return if (kotlin.math.abs(st - st.roundToInt()) < 0.05f) {
        "$sign${st.roundToInt()} st"
    } else {
        "$sign%.1f st".format(st)
    }
}

private fun formatGainDb(linear: Float): String = when {
    linear <= 0.0001f -> "−∞"
    else -> {
        val db = 20.0 * kotlin.math.log10(linear.toDouble())
        "${if (db >= 0) "+" else ""}${(db * 10).roundToInt() / 10.0} dB"
    }
}

private fun formatPan(pan: Float): String = when {
    kotlin.math.abs(pan) < 0.02f -> "C"
    pan < 0 -> "L${(-pan * 100).roundToInt()}"
    else -> "R${(pan * 100).roundToInt()}"
}
