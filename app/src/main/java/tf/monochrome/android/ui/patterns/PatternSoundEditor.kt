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
import tf.monochrome.android.ui.sampler.SamplePickerSheet
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
            PatternFader(
                label = "Pitch",
                value = channel.pitch,
                valueRange = -24f..24f,
                display = "${if (channel.pitch >= 0) "+" else ""}${channel.pitch.roundToInt()} st",
                accent = accent,
                onValueChange = onPitch,
            )
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
