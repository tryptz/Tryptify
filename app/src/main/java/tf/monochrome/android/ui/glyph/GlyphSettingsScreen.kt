// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.audio.sampler.stems.KnownModels
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.data.GlyphCalibration
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.input.GlyphKeyBindings
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Trypt Mania's settings, the ones that are the same for every song: the
 * keyboard's keys, the audio offset, the playfield's graphics, and sound,
 * the name on the scores and the drum model. A menu of the app, under four
 * glass tabs.
 *
 * Keys are set the way StepMania's key config sets them: choose a slot,
 * press the key. The screen holds focus itself so a hardware keyboard's
 * presses reach it, and Test lights each lane's arrow as its keys go down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphSettingsScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.CONTROLS) }
    // Which key slot is waiting for a key: "lane:slot".
    var listening by remember { mutableStateOf<Pair<GlyphLane, Int>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(emptySet<GlyphLane>()) }
    var moved by remember { mutableStateOf<String?>(null) }
    BackHandler {
        when {
            listening != null -> listening = null
            else -> onEvent(GlyphEvent.CloseSettings)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val slot = listening
                when {
                    slot != null && event.type == KeyEventType.KeyDown -> when {
                        code == KEYCODE_ESCAPE -> {
                            listening = null
                            true
                        }
                        // Back and the system's keys keep their jobs.
                        GlyphKeyBindings.isReserved(code) -> false
                        else -> {
                            val rebound = state.keys.bind(slot.first, slot.second, code)
                            moved = rebound.movedFrom?.let {
                                "${GlyphKeyBindings.label(code)} moved from ${it.label} to ${slot.first.label}."
                            }
                            onEvent(GlyphEvent.SetKey(slot.first, slot.second, code))
                            listening = null
                            true
                        }
                    }
                    testing -> {
                        val lane = state.keys.laneFor(code) ?: return@onPreviewKeyEvent false
                        lit = when (event.type) {
                            KeyEventType.KeyDown -> lit + lane
                            KeyEventType.KeyUp -> lit - lane
                            else -> lit
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        TopAppBar(
            title = { Text("Settings", modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseSettings) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        GlyphSegmented(
            options = SettingsTab.entries,
            selected = tab,
            label = { it.label },
            onSelect = {
                tab = it
                listening = null
                testing = false
                lit = emptySet()
            },
            description = "Settings group",
            modifier = Modifier.padding(horizontal = MonoDimens.spacingLg),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MonoDimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            when (tab) {
                SettingsTab.CONTROLS -> ControlsTab(
                    keys = state.keys,
                    listening = listening,
                    testing = testing,
                    lit = lit,
                    moved = moved,
                    onListen = { lane, slot ->
                        moved = null
                        listening = if (listening == lane to slot) null else lane to slot
                        runCatching { focus.requestFocus() }
                    },
                    onTest = {
                        testing = !testing
                        lit = emptySet()
                        listening = null
                        runCatching { focus.requestFocus() }
                    },
                    onReset = {
                        moved = null
                        listening = null
                        onEvent(GlyphEvent.ResetKeys)
                    },
                )
                SettingsTab.SYNC -> SyncTab(state, onEvent)
                SettingsTab.GRAPHICS -> GraphicsTab(state, onEvent)
                SettingsTab.MORE -> MoreTab(state, onEvent)
            }
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }
}

private enum class SettingsTab(val label: String) {
    CONTROLS("Controls"),
    SYNC("Sync"),
    GRAPHICS("Graphics"),
    MORE("More"),
}

@Composable
private fun ControlsTab(
    keys: GlyphKeyBindings,
    listening: Pair<GlyphLane, Int>?,
    testing: Boolean,
    lit: Set<GlyphLane>,
    moved: String?,
    onListen: (GlyphLane, Int) -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        text = "For playing with a keyboard. Tap a key, then press the one you want; each lane takes two.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
        for (lane in GlyphLane.entries) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
            ) {
                LaneArrow(lane = lane, lit = lane in lit)
                Text(
                    text = lane.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (slot in 0 until GlyphKeyBindings.SLOTS) {
                    KeyCap(
                        code = keys.key(lane, slot),
                        listening = listening == lane to slot,
                        description = "${lane.label} ${if (slot == 0) "key" else "second key"}",
                        onClick = { onListen(lane, slot) },
                    )
                }
            }
        }
    }
    val note = when {
        listening != null -> "Press the new key for ${listening.first.label}. Esc keeps the old one."
        moved != null -> moved
        else -> null
    }
    if (note != null) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    GlyphButton(
        text = if (testing) "Testing: press your keys" else "Test keys",
        icon = Icons.Default.Keyboard,
        primary = testing,
        onClick = onTest,
        height = 56.dp,
        modifier = Modifier.fillMaxWidth(),
    )
    GlyphButton(
        text = "Reset to the arrows and D F J K",
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A lane's arrow, outlined, filled while one of its keys is down. */
@Composable
private fun LaneArrow(lane: GlyphLane, lit: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val degrees = when (lane) {
        GlyphLane.LEFT -> -90f
        GlyphLane.DOWN -> 180f
        GlyphLane.UP -> 0f
        GlyphLane.RIGHT -> 90f
    }
    Canvas(
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = lane.label + if (lit) ", pressed" else "" },
    ) {
        val w = size.width
        val h = size.height
        // A block arrow pointing up, turned to the lane.
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.86f, h * 0.46f)
            lineTo(w * 0.64f, h * 0.46f)
            lineTo(w * 0.64f, h * 0.88f)
            lineTo(w * 0.36f, h * 0.88f)
            lineTo(w * 0.36f, h * 0.46f)
            lineTo(w * 0.14f, h * 0.46f)
            close()
        }
        rotate(degrees) {
            if (lit) drawPath(arrow, ink)
            drawPath(arrow, if (lit) ink else faint, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** One key slot, as a key cap: what is on it, or "Press" while it waits for a key. */
@Composable
private fun KeyCap(code: Int, listening: Boolean, description: String, onClick: () -> Unit) {
    // Only the cap waiting for a key pulses; the rest stay still.
    val pulseAlpha = if (listening) {
        rememberInfiniteTransition(label = "keyCap").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "keyCapPulse",
        ).value
    } else {
        1f
    }
    val label = GlyphKeyBindings.label(code)
    val scheme = MaterialTheme.colorScheme
    GlassPill(
        onClick = onClick,
        hazeState = null,
        height = 48.dp,
        onClickLabel = "Change $description, now $label",
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (listening) Modifier.border(2.dp, scheme.onSurface, MonoDimens.shapePill) else Modifier,
            )
            .semantics { contentDescription = "$description: ${if (code == GlyphKeyBindings.NONE) "none" else label}" },
    ) {
        Text(
            text = if (listening) "Press" else label,
            style = if (!listening && label.length > 2) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
            color = if (code == GlyphKeyBindings.NONE && !listening) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (listening) Modifier.graphicsLayer { alpha = pulseAlpha } else Modifier),
        )
    }
}

@Composable
private fun SyncTab(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    Text(
        text = "Phones and headphones send sound out late. If the notes land after the beat you hear, " +
            "raise the offset; the arrows and every judgement allow for it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val offset = state.audioOffsetMs
    Column {
        GlyphControlHeader("Audio offset", if (offset == 0) "None" else if (offset > 0) "Sound reaches you late" else "Sound reaches you early")
        GlyphStepper(
            value = (if (offset > 0) "+" else "") + "$offset ms",
            onLess = { onEvent(GlyphEvent.SetAudioOffset(offset - OFFSET_STEP_MS)) },
            onMore = { onEvent(GlyphEvent.SetAudioOffset(offset + OFFSET_STEP_MS)) },
            lessLabel = "Lower the offset",
            moreLabel = "Raise the offset",
            lessEnabled = offset > -GlyphCalibration.MAX_OFFSET_MS,
            moreEnabled = offset < GlyphCalibration.MAX_OFFSET_MS,
        )
    }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        val late = state.recentLateMs
        Text(
            text = "From your recent runs",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MonoDimens.spacingXs))
        Text(
            text = when {
                late == null -> "Play a few songs through and this reads your timing from them: " +
                    "a phone that sends sound out late shows as a player who is late on every song."
                late > 0 -> "You have been hitting $late ms late on average. Apply it to move the offset by that much."
                else -> "You have been hitting ${-late} ms early on average. Apply it to move the offset by that much."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (late != null) {
            Spacer(Modifier.height(MonoDimens.spacingMd))
            GlyphButton(
                text = "Apply ${if (late > 0) "+" else ""}$late ms",
                primary = true,
                filled = true,
                onClick = { onEvent(GlyphEvent.ApplyRecentTiming) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GraphicsTab(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val graphics = state.gameplay.modifiers.graphics
    val set: (GlyphGraphics) -> Unit = { onEvent(GlyphEvent.SetGraphics(it)) }
    Text(
        text = "What the playfield draws around the notes. None of it changes a judgement.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GlyphSwitchRow(
        label = "Hit god rays",
        checked = graphics.hitRays,
        onChange = { set(graphics.copy(hitRays = it)) },
        note = "Coloured by the combo, red on a miss. Android 13 and later.",
    )
    if (graphics.hitRays) {
        Column {
            GlyphControlHeader("Ray quality", if (graphics.rayQuality > 1) "Smoother, at more GPU work per hit" else "Cheapest")
            GlyphSegmented(
                options = GlyphGraphics.RAY_QUALITIES,
                selected = GlyphGraphics.RAY_QUALITIES.minBy { kotlin.math.abs(it - graphics.rayQuality) },
                label = { GlyphGraphics.rayQualityLabel(it) },
                onSelect = { set(graphics.copy(rayQuality = it)) },
                description = "Ray quality",
            )
        }
        GlyphSwitchRow(
            label = "Light on notes",
            checked = graphics.noteLighting,
            onChange = { set(graphics.copy(noteLighting = it)) },
            note = "Arrows near a hit catch its light",
        )
    }
    GlyphSwitchRow(
        label = "Explosions",
        checked = graphics.explosions,
        onChange = { set(graphics.copy(explosions = it)) },
        note = "A burst on each receptor you hit",
    )
    GlyphSwitchRow(
        label = "Beat flash",
        checked = graphics.beatFlash,
        onChange = { set(graphics.copy(beatFlash = it)) },
        note = "Receptors flash on the beat",
    )
    GlyphSwitchRow(
        label = "Combo burst",
        checked = graphics.comboBurst,
        onChange = { set(graphics.copy(comboBurst = it)) },
        note = "A burst across the field every fifty combo",
    )
}

@Composable
private fun MoreTab(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    var editingName by remember { mutableStateOf(false) }
    GlyphSwitchRow(
        label = "Song previews",
        checked = state.previews,
        onChange = { onEvent(GlyphEvent.SetPreviews(it)) },
        note = "Tapping a song plays twelve seconds from its drop",
    )
    GlyphSwitchRow(
        label = "Metronome",
        checked = state.gameplay.modifiers.metronome,
        onChange = { if (it != state.gameplay.modifiers.metronome) onEvent(GlyphEvent.ToggleMetronome) },
        note = "A click on every beat while you play",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Name on scores", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = state.playerName.ifBlank { "PLAYER" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        GlyphButton(
            text = "Change",
            height = 44.dp,
            onClickLabel = "Change your name on the high score table",
            onClick = { editingName = true },
        )
    }
    ModelSection(state.model, onEvent)

    if (editingName) {
        NameDialog(
            current = state.playerName,
            onDone = {
                onEvent(GlyphEvent.SetPlayerName(it))
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

/**
 * The drum separation model: which one charts are generated with, imported
 * from the phone's own storage since the app ships without one.
 */
@Composable
private fun ModelSection(model: GlyphModelUi, onEvent: (GlyphEvent) -> Unit) {
    // A model file has no MIME type Android knows (.onnx), so the picker takes
    // anything; the installer identifies the file by its hash.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onEvent(GlyphEvent.ImportModel(uri))
    }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        val scheme = MaterialTheme.colorScheme
        Text(
            text = "Stem separation model",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MonoDimens.spacingXs))
        val installed = model.installedName
        Text(
            text = if (installed != null) {
                "$installed · ${StemModelManager.formatBytes(model.sizeBytes)}. New charts read the rhythm off its drums and follow the vocals, melody and bass."
            } else {
                "None installed. Charts use the built-in separator, which hears drums less cleanly. " +
                    "Import a model file from your phone to use it instead."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        if (model.importing) {
            Spacer(Modifier.height(MonoDimens.spacingMd))
            val percent = model.progress?.let { (it * 100).toInt() }
            Text(
                text = listOfNotNull(model.stage ?: "Importing", percent?.let { "$it%" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(MonoDimens.spacingSm))
            GlyphMeter(
                fraction = model.progress ?: 0f,
                color = scheme.onSurface,
                description = listOfNotNull(model.stage, percent?.let { "$it percent" }).joinToString(", "),
            )
            Spacer(Modifier.height(MonoDimens.spacingMd))
            GlyphButton(
                text = "Cancel",
                onClick = { onEvent(GlyphEvent.CancelModelImport) },
                onClickLabel = "Cancel the model import",
                height = 44.dp,
            )
            return@GlyphPanel
        }
        model.message?.let { message ->
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(MonoDimens.spacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            GlyphButton(
                text = if (installed != null) "Replace…" else "Import model…",
                icon = Icons.Default.FolderOpen,
                primary = installed == null,
                onClick = {
                    onEvent(GlyphEvent.DismissModelMessage)
                    picker.launch(arrayOf("*/*"))
                },
                modifier = Modifier.weight(1f),
            )
            if (installed != null) {
                GlyphButton(
                    text = "Remove",
                    onClick = { onEvent(GlyphEvent.RemoveModel) },
                    onClickLabel = "Remove $installed",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Text(
            text = "Accepts " + KnownModels.supportedFileNames().joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(12) },
                singleLine = true,
                label = { Text("Up to 12 letters") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Android's KEYCODE_ESCAPE: cancels a key being set. */
private const val KEYCODE_ESCAPE = 111
private const val OFFSET_STEP_MS = 5
