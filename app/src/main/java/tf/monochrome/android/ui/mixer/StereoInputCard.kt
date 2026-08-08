package tf.monochrome.android.ui.mixer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.input.StereoInputEngine
import tf.monochrome.android.audio.input.StereoInputMode
import tf.monochrome.android.audio.input.StereoInputStatus
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Input source card for the mixer console.
 *
 * Routing lives on the channel strips (each bus picks Play / Line / Both), so
 * this is only about the signal itself: which device it comes from, whether it
 * is running, how hot it is, and — in Live mode — whether it has taken the
 * player over from the library.
 *
 * It stays collapsed to one row until tapped, because the console header is
 * already dense and most sessions never touch line-in.
 */
@Composable
fun StereoInputCard(
    viewModel: MixerViewModel,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    val featureEnabled by viewModel.inputEnabled.collectAsState()
    val active by viewModel.inputActive.collectAsState()
    val mode by viewModel.inputMode.collectAsState()
    val status by viewModel.inputStatus.collectAsState()
    val format by viewModel.inputFormat.collectAsState()
    val devices by viewModel.inputDevices.collectAsState()
    val selectedId by viewModel.inputSelectedDeviceId.collectAsState()
    val peakL by viewModel.inputPeakL.collectAsState()
    val peakR by viewModel.inputPeakR.collectAsState()
    val trimDb by viewModel.inputTrimDb.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var deviceMenuOpen by remember { mutableStateOf(false) }

    // Starting capture is the moment the permission is actually needed, so it
    // is requested here rather than up front — the feature is opt-in and most
    // users never reach this card.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setStereoInputEnabled(true)
            viewModel.toggleStereoInput()
        }
    }

    fun onStartStop() {
        if (active) {
            viewModel.toggleStereoInput()
            return
        }
        if (!viewModel.hasRecordPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!featureEnabled) viewModel.setStereoInputEnabled(true)
        viewModel.toggleStereoInput()
    }

    val selectedDevice = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
    val deviceLabel = selectedDevice?.let { viewModel.describeInputDevice(it) } ?: "No input detected"
    val accent = if (active) LINE_IN_ACCENT else colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(shape = MonoDimens.shapeMd, tintAlpha = if (active) 0.16f else 0.07f)
            .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
        verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
    ) {
        // ── Collapsed header: identity, level, start/stop ────────────────
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STEREO INPUT",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = accent,
                )
                Text(
                    text = statusLine(active, status, deviceLabel, format?.sampleRate, format?.channelCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InputMeter(peakL = peakL, peakR = peakR, live = active)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MonoDimens.radiusPill))
                    .background(
                        if (active) LINE_IN_ACCENT.copy(alpha = 0.25f)
                        else colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )
                    .clickable { onStartStop() }
                    .padding(horizontal = MonoDimens.spacingSm, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (active) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (active) "Stop stereo input" else "Start stereo input",
                    tint = if (active) LINE_IN_ACCENT else colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs)) {

                // ── Device picker ────────────────────────────────────────
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MonoDimens.shapeSm)
                            .clickable { deviceMenuOpen = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
                    ) {
                        Text(
                            text = "Source",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = deviceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selectedDevice != null && viewModel.isInputFeedbackRisk(selectedDevice)) {
                            // Built-in mic into the speaker is a howlround; the
                            // picker allows it but says so first.
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Feedback risk",
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = deviceMenuOpen,
                        onDismissRequest = { deviceMenuOpen = false },
                    ) {
                        if (devices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No audio inputs detected") },
                                onClick = { deviceMenuOpen = false },
                            )
                        }
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(viewModel.describeInputDevice(device))
                                        Text(
                                            text = viewModel.describeInputCapabilities(device) +
                                                if (viewModel.isInputFeedbackRisk(device))
                                                    " · may feed back through the speaker" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectInputDevice(device.id)
                                    deviceMenuOpen = false
                                },
                            )
                        }
                    }
                }

                // ── Live / Mix ───────────────────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StereoInputMode.entries.forEach { entry ->
                        FilterChip(
                            selected = mode == entry,
                            onClick = { viewModel.setStereoInputMode(entry) },
                            label = { Text(entry.label, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    text = mode.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                )

                // ── Trim ─────────────────────────────────────────────────
                // Local state while dragging, committed on release, so every
                // pixel of the drag doesn't hit DataStore.
                var pendingTrim by remember(trimDb) { mutableFloatStateOf(trimDb) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Trim",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp),
                    )
                    Slider(
                        value = pendingTrim,
                        onValueChange = { pendingTrim = it },
                        onValueChangeFinished = { viewModel.setInputTrimDb(pendingTrim) },
                        valueRange = StereoInputEngine.MIN_TRIM_DB..StereoInputEngine.MAX_TRIM_DB,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "%+.1f dB".format(pendingTrim),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.width(58.dp),
                    )
                }

                Text(
                    text = "Route buses to LINE on their input rows to hear this through " +
                        "their effect chains.",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Two thin peak bars — enough to confirm signal and spot a clipping input. */
@Composable
private fun InputMeter(peakL: Float, peakR: Float, live: Boolean) {
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(44.dp),
    ) {
        MeterBarThin(level = if (live) peakL else 0f, idle = idle)
        MeterBarThin(level = if (live) peakR else 0f, idle = idle)
    }
}

@Composable
private fun MeterBarThin(level: Float, idle: Color) {
    val clamped = level.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(idle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(4.dp)
                .background(if (clamped >= 0.99f) Color(0xFFE53935) else LINE_IN_ACCENT),
        )
    }
}

private fun statusLine(
    active: Boolean,
    status: StereoInputStatus,
    deviceLabel: String,
    sampleRate: Int?,
    channels: Int?,
): String = when {
    !active -> "Off · $deviceLabel"
    status == StereoInputStatus.Running && sampleRate != null ->
        "$deviceLabel · ${sampleRate / 1000} kHz ${if ((channels ?: 2) >= 2) "stereo" else "mono"}"
    status == StereoInputStatus.Starting -> "Starting…"
    status == StereoInputStatus.NoDevice -> "No input device connected"
    status == StereoInputStatus.PermissionRequired -> "Microphone permission needed"
    status == StereoInputStatus.Error -> "Could not open the input — see Settings"
    else -> deviceLabel
}

/** Matches the LINE indicator on the strips' input rows. */
private val LINE_IN_ACCENT = Color(0xFF29B6F6)
