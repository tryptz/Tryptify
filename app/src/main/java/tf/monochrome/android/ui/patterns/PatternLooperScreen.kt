// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternLimits
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.domain.patterns.TransportState
import tf.monochrome.android.ui.navigation.LocalMiniPlayerInset
import tf.monochrome.android.ui.sampler.SamplePickerSheet

/**
 * The Patterns screen.
 *
 * ## Layout
 *
 * Three arrangements, chosen from the available width rather than from a
 * device class — a foldable and a split-screen tablet both change width
 * without changing device, and the layout has to follow the space it is
 * actually given.
 *
 * * **Compact** (< 600 dp) — one channel's grid at a time, with the channel
 *   list above it. Everything scrolls in one column. The premise is that a
 *   phone cannot show four channels' worth of 48 dp keys and still have a
 *   transport, so it shows one channel properly instead of four badly.
 * * **Medium** (600–839 dp) — two panes: channels on the left, grid on the
 *   right, transport across the bottom.
 * * **Expanded** (≥ 840 dp) — the workstation: channels left, sequencer
 *   centre, sound controls right, transport and bank along the bottom. This
 *   is the only layout where the sound editor is a panel rather than a sheet,
 *   because it is the only one with room for it.
 *
 * None of these is the phone layout stretched. The grid's column count, which
 * panes exist, and whether the sound editor is modal all change.
 */
@Composable
fun PatternLooperScreen(
    modifier: Modifier = Modifier,
    viewModel: PatternLooperViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
    onOpenSampler: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val transport by viewModel.transport.collectAsStateWithLifecycle()
    val triggers by viewModel.channelTriggers.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val available by viewModel.engineAvailable.collectAsStateWithLifecycle()

    val accent = MaterialTheme.colorScheme.primary
    var pickerFor by remember { mutableStateOf<Int?>(null) }
    var stepEditorFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        val layout = when {
            width < COMPACT_MAX -> PatternLayout.COMPACT
            width < EXPANDED_MIN -> PatternLayout.MEDIUM
            else -> PatternLayout.EXPANDED
        }

        val pattern = ui.pattern
        when {
            !available -> EngineUnavailable(onBack)
            ui.loading || pattern == null -> LoadingPatterns()
            else -> when (layout) {
                PatternLayout.COMPACT -> CompactLayout(
                    pattern = pattern,
                    ui = ui,
                    transport = transport,
                    triggers = triggers,
                    library = library,
                    accent = accent,
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSampler = onOpenSampler,
                    onPickSample = { pickerFor = it },
                    onEditStep = { channel, step -> stepEditorFor = channel to step },
                )

                PatternLayout.MEDIUM -> TwoPaneLayout(
                    pattern = pattern,
                    ui = ui,
                    transport = transport,
                    triggers = triggers,
                    library = library,
                    accent = accent,
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSampler = onOpenSampler,
                    onPickSample = { pickerFor = it },
                    onEditStep = { channel, step -> stepEditorFor = channel to step },
                )

                PatternLayout.EXPANDED -> WorkstationLayout(
                    pattern = pattern,
                    ui = ui,
                    transport = transport,
                    triggers = triggers,
                    library = library,
                    accent = accent,
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenSampler = onOpenSampler,
                    onPickSample = { pickerFor = it },
                    onEditStep = { channel, step -> stepEditorFor = channel to step },
                )
            }
        }
    }

    // ── modals ──────────────────────────────────────────────────────────

    val pattern = ui.pattern
    ui.editingChannel?.let { index ->
        val channel = pattern?.channelAt(index)
        if (channel != null) {
            PatternSoundEditorSheet(
                channelIndex = index,
                channel = channel,
                sample = library.firstOrNull { it.id == channel.sampleId },
                library = library,
                accent = accent,
                onDismiss = { viewModel.openChannelEditor(null) },
                onRename = { viewModel.renameChannel(index, it) },
                onAssignSample = { viewModel.assignSample(index, it) },
                onVolume = { viewModel.setChannelVolume(index, it) },
                onPan = { viewModel.setChannelPan(index, it) },
                onPitch = { viewModel.setChannelPitch(index, it) },
                onSampleStart = { viewModel.setChannelSampleStart(index, it) },
                onSampleEnd = { viewModel.setChannelSampleEnd(index, it) },
                onAttack = { viewModel.setChannelAttack(index, it) },
                onRelease = { viewModel.setChannelRelease(index, it) },
                onFilter = { viewModel.setChannelFilter(index, it) },
                onReverse = { viewModel.setChannelReverse(index, it) },
                onMute = { viewModel.toggleMute(index) },
                onSolo = { viewModel.toggleSolo(index) },
                onAudition = { viewModel.trigger(index) },
                onClearSteps = { viewModel.clearChannel(index) },
                onRemoveChannel = { viewModel.removeChannel(index) },
            )
        }
    }

    pickerFor?.let { index ->
        SamplePickerSheet(
            library = library,
            selected = library.firstOrNull { it.id == pattern?.channelAt(index)?.sampleId },
            accent = accent,
            onDismiss = { pickerFor = null },
            onPick = {
                viewModel.assignSample(index, it)
                pickerFor = null
            },
        )
    }

    stepEditorFor?.let { (channelIndex, stepIndex) ->
        val step = pattern?.channelAt(channelIndex)?.step(stepIndex)
        if (step != null) {
            StepEditorSheet(
                stepNumber = stepIndex + 1,
                velocity = step.velocity,
                accent = accent,
                onDismiss = { stepEditorFor = null },
                onVelocity = { viewModel.setStepVelocity(channelIndex, stepIndex, it) },
                onClearStep = {
                    if (step.enabled) viewModel.toggleStep(channelIndex, stepIndex)
                },
            )
        }
    }
}

private enum class PatternLayout { COMPACT, MEDIUM, EXPANDED }

// ── compact ─────────────────────────────────────────────────────────────

@Composable
private fun CompactLayout(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    transport: TransportState,
    triggers: IntArray,
    library: List<SampleRef>,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    onBack: (() -> Unit)?,
    onOpenSampler: (() -> Unit)?,
    onPickSample: (Int) -> Unit,
    onEditStep: (Int, Int) -> Unit,
) {
    val channelIndex = ui.selectedChannel.coerceIn(0, pattern.channels.lastIndex.coerceAtLeast(0))
    val channel = pattern.channelAt(channelIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 12.dp),
    ) {
        ScreenTopBar(onBack, onOpenSampler, accent)

        PatternPanel {
            PatternHeader(
                pattern = pattern,
                transport = transport,
                accent = accent,
                onBpmChange = viewModel::setBpm,
            )
        }

        Spacer(Modifier.height(10.dp))

        PatternPanel {
            PanelLabel("Channels")
            Spacer(Modifier.height(6.dp))
            pattern.channels.forEachIndexed { index, item ->
                PatternChannelStrip(
                    index = index,
                    channel = item,
                    sampleName = library.firstOrNull { it.id == item.sampleId }?.name,
                    selected = index == channelIndex,
                    triggerCount = triggers.getOrElse(index) { 0 },
                    accent = accent,
                    compact = true,
                    modifier = Modifier.padding(bottom = 4.dp),
                    onSelect = { viewModel.selectChannel(index) },
                    onToggleMute = { viewModel.toggleMute(index) },
                    onToggleSolo = { viewModel.toggleSolo(index) },
                    onOpenEditor = {
                        if (item.sampleId == null) {
                            onPickSample(index)
                        } else {
                            viewModel.openChannelEditor(index)
                        }
                    },
                )
            }
            if (pattern.channels.size < PatternLimits.MAX_CHANNELS) {
                PatternPill(
                    label = "+ Add channel",
                    active = false,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::addChannel,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (channel != null) {
            PatternPanel {
                PanelLabel(channel.name)
                Spacer(Modifier.height(8.dp))
                PatternGrid(
                    pattern = pattern,
                    channelIndex = channelIndex,
                    channel = channel,
                    page = ui.page,
                    playheadStep = transport.currentStep,
                    playing = transport.playing && transport.countInRemaining == 0,
                    accent = accent,
                    columns = DEFAULT_COLUMNS,
                    onToggleStep = { viewModel.toggleStep(channelIndex, it) },
                    onLongPressStep = { onEditStep(channelIndex, it) },
                    onPaintStep = { step, on ->
                        viewModel.paintSteps(channelIndex, listOf(step), on)
                    },
                )
                PatternPageBar(
                    pageCount = ui.pageCount,
                    page = ui.page,
                    onPageChange = viewModel::selectPage,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        PatternPanel {
            ChannelPadRow(
                channels = pattern.channels,
                triggers = triggers,
                accent = accent,
                onTap = { viewModel.trigger(it) },
            )
        }

        Spacer(Modifier.height(10.dp))

        PatternPanel {
            PatternLengthBar(pattern, accent, onLengthChange = viewModel::setPatternLength)
            Spacer(Modifier.height(10.dp))
            PatternActionsRow(
                canPaste = ui.pendingCopy != null,
                accent = accent,
                onDuplicate = viewModel::duplicatePattern,
                onCopy = viewModel::copyPattern,
                onPaste = viewModel::pastePattern,
                onClear = viewModel::clearPattern,
                onDelete = viewModel::deletePattern,
            )
        }

        Spacer(Modifier.height(10.dp))

        PatternPanel {
            PatternBank(
                bank = ui.bank,
                playingSlot = transport.currentPattern,
                queuedSlot = transport.queuedPattern,
                editingSlot = pattern.bankSlot,
                accent = accent,
                columns = 4,
                onSelect = viewModel::selectPattern,
                onCreate = viewModel::createPattern,
            )
        }

        Spacer(Modifier.height(10.dp))

        PatternPanel {
            PatternTransport(
                transport = transport,
                accent = accent,
                onPlay = viewModel::play,
                onStop = viewModel::stop,
                onRecord = viewModel::toggleRecord,
                onMetronome = viewModel::setMetronome,
                onSwing = viewModel::setSwing,
                onCountIn = viewModel::setCountIn,
                onQuantize = viewModel::setRecordQuantum,
                onSwitchMode = viewModel::setSwitchMode,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── medium ──────────────────────────────────────────────────────────────

@Composable
private fun TwoPaneLayout(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    transport: TransportState,
    triggers: IntArray,
    library: List<SampleRef>,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    onBack: (() -> Unit)?,
    onOpenSampler: (() -> Unit)?,
    onPickSample: (Int) -> Unit,
    onEditStep: (Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 12.dp),
    ) {
        ScreenTopBar(onBack, onOpenSampler, accent)

        PatternPanel {
            PatternHeader(pattern, transport, accent, onBpmChange = viewModel::setBpm)
        }
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Left: channels. Fixed width rather than a weight, so the grid on
            // the right keeps square keys as the window changes size.
            PatternPanel(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                ChannelListPane(
                    pattern = pattern,
                    ui = ui,
                    triggers = triggers,
                    library = library,
                    accent = accent,
                    viewModel = viewModel,
                    onPickSample = onPickSample,
                )
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                GridPane(
                    pattern = pattern,
                    ui = ui,
                    transport = transport,
                    accent = accent,
                    viewModel = viewModel,
                    columns = MEDIUM_COLUMNS,
                    onEditStep = onEditStep,
                )
                Spacer(Modifier.height(10.dp))
                PatternPanel {
                    ChannelPadRow(
                        channels = pattern.channels,
                        triggers = triggers,
                        accent = accent,
                        onTap = { viewModel.trigger(it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BottomBar(pattern, ui, transport, accent, viewModel)
        Spacer(Modifier.height(12.dp))
    }
}

// ── expanded ────────────────────────────────────────────────────────────

@Composable
private fun WorkstationLayout(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    transport: TransportState,
    triggers: IntArray,
    library: List<SampleRef>,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    onBack: (() -> Unit)?,
    onOpenSampler: (() -> Unit)?,
    onPickSample: (Int) -> Unit,
    onEditStep: (Int, Int) -> Unit,
) {
    val channelIndex = ui.selectedChannel.coerceIn(0, pattern.channels.lastIndex.coerceAtLeast(0))
    val channel = pattern.channelAt(channelIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 12.dp),
    ) {
        ScreenTopBar(onBack, onOpenSampler, accent)
        PatternPanel {
            PatternHeader(pattern, transport, accent, onBpmChange = viewModel::setBpm)
        }
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PatternPanel(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                ChannelListPane(pattern, ui, triggers, library, accent, viewModel, onPickSample)
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                GridPane(
                    pattern = pattern,
                    ui = ui,
                    transport = transport,
                    accent = accent,
                    viewModel = viewModel,
                    columns = EXPANDED_COLUMNS,
                    onEditStep = onEditStep,
                )
                Spacer(Modifier.height(10.dp))
                PatternPanel {
                    ChannelPadRow(pattern.channels, triggers, accent) { viewModel.trigger(it) }
                }
            }

            // Right: the sound controls, inline. This is the only layout with
            // room for them, and having them permanently visible is most of
            // what makes the wide layout worth using — a sheet that covers the
            // grid you are editing against defeats the point.
            PatternPanel(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (channel != null) {
                    InlineSoundPane(
                        channelIndex = channelIndex,
                        channel = channel,
                        sampleName = library.firstOrNull { it.id == channel.sampleId }?.name,
                        accent = accent,
                        viewModel = viewModel,
                        onPickSample = { onPickSample(channelIndex) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        BottomBar(pattern, ui, transport, accent, viewModel)
        Spacer(Modifier.height(12.dp))
    }
}

// ── shared pieces ───────────────────────────────────────────────────────

@Composable
private fun ScreenTopBar(
    onBack: (() -> Unit)?,
    onOpenSampler: (() -> Unit)?,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            text = "Patterns",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onOpenSampler != null) {
            PatternPill(label = "Sampler", active = false, accent = accent, onClick = onOpenSampler)
        }
    }
}

@Composable
private fun ChannelListPane(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    triggers: IntArray,
    library: List<SampleRef>,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    onPickSample: (Int) -> Unit,
) {
    PanelLabel("Channels")
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        pattern.channels.forEachIndexed { index, item ->
            PatternChannelStrip(
                index = index,
                channel = item,
                sampleName = library.firstOrNull { it.id == item.sampleId }?.name,
                selected = index == ui.selectedChannel,
                triggerCount = triggers.getOrElse(index) { 0 },
                accent = accent,
                modifier = Modifier.padding(bottom = 4.dp),
                onSelect = { viewModel.selectChannel(index) },
                onToggleMute = { viewModel.toggleMute(index) },
                onToggleSolo = { viewModel.toggleSolo(index) },
                onOpenEditor = {
                    if (item.sampleId == null) onPickSample(index)
                    else viewModel.openChannelEditor(index)
                },
            )
        }
        if (pattern.channels.size < PatternLimits.MAX_CHANNELS) {
            PatternPill(
                label = "+ Add channel",
                active = false,
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::addChannel,
            )
        }
    }
}

/**
 * The sequencer pane.
 *
 * On wide layouts every channel gets a row, which is the arrangement people
 * actually think in — the compact layout's one-channel-at-a-time view is a
 * concession to width, not a preference.
 */
@Composable
private fun GridPane(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    transport: TransportState,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    columns: Int,
    onEditStep: (Int, Int) -> Unit,
) {
    PatternPanel {
        pattern.channels.forEachIndexed { index, channel ->
            PanelLabel(channel.name)
            Spacer(Modifier.height(4.dp))
            PatternGrid(
                pattern = pattern,
                channelIndex = index,
                channel = channel,
                page = ui.page,
                playheadStep = transport.currentStep,
                playing = transport.playing && transport.countInRemaining == 0,
                accent = accent,
                columns = columns,
                onToggleStep = { viewModel.toggleStep(index, it) },
                onLongPressStep = { onEditStep(index, it) },
                onPaintStep = { step, on -> viewModel.paintSteps(index, listOf(step), on) },
            )
            Spacer(Modifier.height(10.dp))
        }
        PatternPageBar(
            pageCount = ui.pageCount,
            page = ui.page,
            onPageChange = viewModel::selectPage,
        )
    }
}

@Composable
private fun InlineSoundPane(
    channelIndex: Int,
    channel: tf.monochrome.android.domain.patterns.PatternChannel,
    sampleName: String?,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
    onPickSample: () -> Unit,
) {
    PanelLabel("Sound — ${channel.name}")
    Spacer(Modifier.height(8.dp))
    PatternPill(
        label = sampleName ?: "Choose sample",
        active = sampleName != null,
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
        onClick = onPickSample,
    )
    Spacer(Modifier.height(10.dp))
    PatternFader(
        "Volume", channel.volume, 0f..2f,
        "${(channel.volume * 100).toInt()}%", accent = accent,
    ) { viewModel.setChannelVolume(channelIndex, it) }
    PatternFader(
        "Pan", channel.pan, -1f..1f,
        if (channel.pan == 0f) "C" else "${(channel.pan * 100).toInt()}", accent = accent,
    ) { viewModel.setChannelPan(channelIndex, it) }
    PatternFader(
        "Pitch", channel.pitch, -24f..24f,
        "${channel.pitch.toInt()} st", accent = accent,
    ) { viewModel.setChannelPitch(channelIndex, it) }
    PatternFader(
        "Start", channel.sampleStart, 0f..0.95f,
        "${(channel.sampleStart * 100).toInt()}%", accent = accent,
    ) { viewModel.setChannelSampleStart(channelIndex, it) }
    PatternFader(
        "Attack", channel.attackMs, 0f..500f,
        "${channel.attackMs.toInt()} ms", accent = accent,
    ) { viewModel.setChannelAttack(channelIndex, it) }
    PatternFader(
        "Release", channel.releaseMs, 1f..2000f,
        "${channel.releaseMs.toInt()} ms", accent = accent,
    ) { viewModel.setChannelRelease(channelIndex, it) }
    PatternFader(
        "Filter", channel.filterHz, 200f..tf.monochrome.android.domain.patterns.PatternChannel.FILTER_OFF,
        if (channel.filterHz >= 20000f) "Off" else "${channel.filterHz.toInt()} Hz", accent = accent,
    ) { viewModel.setChannelFilter(channelIndex, it) }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PatternPill("Reverse", channel.reverse, Modifier.weight(1f), accent) {
            viewModel.setChannelReverse(channelIndex, !channel.reverse)
        }
        PatternPill("Play", false, Modifier.weight(1f), accent) {
            viewModel.trigger(channelIndex)
        }
    }
}

@Composable
private fun BottomBar(
    pattern: Pattern,
    ui: PatternLooperViewModel.UiState,
    transport: TransportState,
    accent: androidx.compose.ui.graphics.Color,
    viewModel: PatternLooperViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PatternPanel(modifier = Modifier.weight(1f)) {
            PatternBank(
                bank = ui.bank,
                playingSlot = transport.currentPattern,
                queuedSlot = transport.queuedPattern,
                editingSlot = pattern.bankSlot,
                accent = accent,
                columns = 8,
                onSelect = viewModel::selectPattern,
                onCreate = viewModel::createPattern,
            )
            Spacer(Modifier.height(8.dp))
            PatternLengthBar(pattern, accent, onLengthChange = viewModel::setPatternLength)
            Spacer(Modifier.height(8.dp))
            PatternActionsRow(
                canPaste = ui.pendingCopy != null,
                accent = accent,
                onDuplicate = viewModel::duplicatePattern,
                onCopy = viewModel::copyPattern,
                onPaste = viewModel::pastePattern,
                onClear = viewModel::clearPattern,
                onDelete = viewModel::deletePattern,
            )
        }
        PatternPanel(modifier = Modifier.width(380.dp)) {
            PatternTransport(
                transport = transport,
                accent = accent,
                onPlay = viewModel::play,
                onStop = viewModel::stop,
                onRecord = viewModel::toggleRecord,
                onMetronome = viewModel::setMetronome,
                onSwing = viewModel::setSwing,
                onCountIn = viewModel::setCountIn,
                onQuantize = viewModel::setRecordQuantum,
                onSwitchMode = viewModel::setSwitchMode,
            )
        }
    }
}

@Composable
private fun LoadingPatterns() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Loading patterns…", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Shown when the native sampler library could not be loaded.
 *
 * A build without the sampler `.so` for this ABI has to say so rather than
 * crash on the first JNI call — the rest of the app is unaffected, and a
 * missing optional library is not a reason to take the process down.
 */
@Composable
private fun EngineUnavailable(onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "The pattern engine isn't available on this build.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onBack != null) {
            Spacer(Modifier.height(12.dp))
            PatternPill(
                label = "Go back",
                active = false,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onBack,
            )
        }
    }
}

private val COMPACT_MAX: Dp = 600.dp
private val EXPANDED_MIN: Dp = 840.dp

/** Wider windows fit a full 16-step row, which is the layout people know. */
private const val MEDIUM_COLUMNS = 16
private const val EXPANDED_COLUMNS = 16
