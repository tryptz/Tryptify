// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.audio.stepmania.ChartMode
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPopnLane
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphDemoChart
import tf.monochrome.android.glyph.data.GlyphCalibration
import tf.monochrome.android.glyph.data.GlyphSyncTrack
import tf.monochrome.android.glyph.data.GlyphTapSync
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.glyph.input.GlyphKeyBindings
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.roundToInt

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
    /** For the TV shader's preview; without them the Graphics tab shows none. */
    assets: GlyphAssetRepository? = null,
    palette: GlyphPalette? = null,
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
                            val rebound = state.keys.bind(slot.first, slot.second, code, state.course)
                            moved = rebound.movedFrom?.let {
                                "${GlyphKeyBindings.label(code)} moved from ${it.labelIn(state.course)} to ${slot.first.labelIn(state.course)}."
                            }
                            onEvent(GlyphEvent.SetKey(slot.first, slot.second, code))
                            listening = null
                            true
                        }
                    }
                    testing -> {
                        val lane = state.keys.laneFor(code, state.course) ?: return@onPreviewKeyEvent false
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
                    course = state.course,
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
                SettingsTab.GRAPHICS -> GraphicsTab(state, onEvent, assets, palette)
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
    course: CourseType,
    lit: Set<GlyphLane>,
    moved: String?,
    onListen: (GlyphLane, Int) -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        text = when (course) {
            CourseType.DANCE -> "For playing with a keyboard. Tap a key, then press the one you want; each lane takes two."
            CourseType.POPN -> "For playing pop'n with a keyboard: its nine buttons, laid out as its two rows. Tap a key, then press the one you want; each button takes two."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Dance's four in a row; pop'n's as its pad, the four above the five.
    val rows = when (course) {
        CourseType.DANCE -> listOf(GlyphLane.DANCE)
        CourseType.POPN -> listOf(GlyphPopnLane.TOP_ROW.map { it.lane }, GlyphPopnLane.BOTTOM_ROW.map { it.lane })
    }
    for (row in rows) Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
        for (lane in row) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
            ) {
                LaneArrow(lane = lane, lit = lane in lit, course = course)
                Text(
                    text = lane.labelIn(course),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                for (slot in 0 until GlyphKeyBindings.SLOTS) {
                    KeyCap(
                        code = keys.key(lane, slot, course),
                        listening = listening == lane to slot,
                        description = "${lane.labelIn(course)} ${if (slot == 0) "key" else "second key"}",
                        onClick = { onListen(lane, slot) },
                    )
                }
            }
        }
    }
    val note = when {
        listening != null -> "Press the new key for ${listening.first.labelIn(course)}. Esc keeps the old one."
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
        text = GlyphKeyBindings.defaultLabel(course),
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A lane's arrow, outlined, filled while one of its keys is down; a pop'n button's disc. */
@Composable
private fun LaneArrow(lane: GlyphLane, lit: Boolean, course: CourseType = CourseType.DANCE) {
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    if (course == CourseType.POPN) {
        Canvas(
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = lane.labelIn(course) + if (lit) ", pressed" else "" },
        ) {
            with(GlyphPopnArt) { drawPopnButton(center, size.minDimension, lane, held = lit) }
        }
        return
    }
    val degrees = when (lane) {
        GlyphLane.LEFT -> -90f
        GlyphLane.DOWN -> 180f
        GlyphLane.UP -> 0f
        GlyphLane.RIGHT -> 90f
        else -> 0f
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
    TapSyncPanel(state.tapSync, onEvent)
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

/**
 * Tap to sync: a click track at [GlyphSyncTrack.BPM] and a pad to tap along
 * on. Listening, not watching — the pad deliberately does not flash with the
 * beat, since a player following a flash measures the screen, not the sound.
 */
@Composable
private fun TapSyncPanel(sync: GlyphTapSyncUi?, onEvent: (GlyphEvent) -> Unit) {
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Tap to sync",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(MonoDimens.spacingXs))
        val scheme = MaterialTheme.colorScheme
        when {
            sync == null -> {
                Text(
                    text = "For Bluetooth headphones above all. Put them on, press Start, and tap the pad on " +
                        "every click you hear: ${GlyphSyncTrack.BPM} beats a minute, ${GlyphTapSync.NEEDED} taps. " +
                        "Listen with your eyes off the screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = "Start",
                    primary = true,
                    filled = true,
                    onClick = { onEvent(GlyphEvent.StartTapSync) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            sync.playing -> {
                // Leaving the tab mid-track stops it rather than leaving the
                // clicks playing under another page.
                DisposableEffect(Unit) { onDispose { onEvent(GlyphEvent.StopTapSync) } }
                TapPad(
                    caption = if (sync.beat < 0) "Get ready…" else "Tap on every click",
                    count = "${sync.taps} of ${sync.needed}",
                    onTap = { onEvent(GlyphEvent.SyncTap(it)) },
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = "Stop",
                    onClick = { onEvent(GlyphEvent.StopTapSync) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            sync.offsetMs == null -> {
                Text(
                    text = "Too few taps to read a timing from. Tap on each click until the count fills.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = "Try again",
                    primary = true,
                    filled = true,
                    onClick = { onEvent(GlyphEvent.StartTapSync) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                val ms = sync.offsetMs
                Text(
                    text = when {
                        ms > 0 -> "Sound reaches you $ms ms late"
                        ms < 0 -> "Sound reaches you ${-ms} ms early"
                        else -> "Sound reaches you on time"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = when {
                        !sync.steady -> "Your taps wandered by about ±${sync.spreadMs} ms, so this is a rough reading. " +
                            "Another go, tapping as evenly as you can, will be closer."
                        sync.couldBeAClickOff -> "Bluetooth can be this late. But if your first tap came on the " +
                            "second click, this is one click too much: try again from the very first."
                        else -> "Your taps kept within ±${sync.spreadMs} ms of each other: a steady reading."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = "Try again",
                        onClick = { onEvent(GlyphEvent.StartTapSync) },
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(
                        text = "Apply ${if (ms > 0) "+" else ""}$ms ms",
                        primary = true,
                        filled = true,
                        onClick = { onEvent(GlyphEvent.ApplyTapSync) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The pad tapped along to the click track. Each tap is sent with the time the
 * finger came down, not when the event reached here, the way a lane press is.
 */
@Composable
private fun TapPad(caption: String, count: String, onTap: (Long) -> Unit) {
    val onTapNow by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TAP_PAD_HEIGHT)
            .liquidGlass(shape = MonoDimens.shapeLg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTapNow(down.uptimeMillis)
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Tap pad. $caption. $count"
                onClick(label = "Tap") {
                    onTapNow(SystemClock.uptimeMillis())
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = caption,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = count,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun GraphicsTab(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    assets: GlyphAssetRepository?,
    palette: GlyphPalette?,
) {
    val graphics = state.gameplay.modifiers.graphics
    val set: (GlyphGraphics) -> Unit = { onEvent(GlyphEvent.SetGraphics(it)) }
    Text(
        text = "What the playfield draws around the notes. None of it changes a judgement.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LandscapeFieldControl(
        fraction = state.gameplay.modifiers.landscapeFieldWidth,
        onChange = { onEvent(GlyphEvent.SetLandscapeFieldWidth(it)) },
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
    Column {
        GlyphControlHeader("TV shader", "Android 13 and later")
        GlyphChipRow(
            options = GlyphTvShader.entries,
            selected = graphics.tvShader,
            label = { it.label },
            onSelect = { set(graphics.copy(tvShader = it)) },
        )
        Text(
            text = if (LocalPerformanceProfile.current.allowHazeBlur) {
                graphics.tvShader.note
            } else {
                // Said rather than left to look broken: the choice is kept,
                // and drawn on a phone or setting that allows it.
                "${graphics.tvShader.note} Not drawn here: the app keeps its heavier effects off on this phone, or with liquid glass removed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MonoDimens.spacingSm),
        )
    }
    // The same presets as the app-wide CRT effect: picking one sets the look
    // and every slider, and turns the TV on.
    tf.monochrome.android.ui.settings.crt.CrtPresetsPanel(
        shader = graphics.tvShader,
        tuning = graphics.tv,
        onApply = { look, tv -> set(graphics.copy(tvShader = look, tv = tv)) },
    )
    if (graphics.tvShader != GlyphTvShader.OFF) {
        TvShaderControls(state, set, assets, palette)
    }
}

/**
 * How wide the lanes are with the phone held sideways, over a sketch of a
 * landscape screen with the lanes in it, drawn to this phone's own shape.
 */
@Composable
private fun LandscapeFieldControl(fraction: Float, onChange: (Float) -> Unit) {
    val window = LocalWindowInfo.current.containerSize
    val long = maxOf(window.width, window.height).coerceAtLeast(1)
    val short = minOf(window.width, window.height).coerceAtLeast(1)
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(long.toFloat() / short)
            .clearAndSetSemantics { contentDescription = "Sketch of the lanes across a landscape screen" },
    ) {
        val corner = CornerRadius(size.height * 0.06f)
        drawRoundRect(color = ink.copy(alpha = 0.06f), cornerRadius = corner)
        drawRoundRect(color = ink.copy(alpha = 0.3f), cornerRadius = corner, style = Stroke(width = 2f))
        // The same arithmetic the game lays its lanes out with.
        val bounds = GlyphFieldBounds.of(size.width.toInt(), fraction)
        drawRect(
            color = ink.copy(alpha = 0.12f),
            topLeft = Offset(bounds.leftPx.toFloat(), 0f),
            size = Size(bounds.widthPx.toFloat(), size.height),
        )
        val note = bounds.laneWidthPx * 0.82f
        val receptorY = size.height - size.height * (LANDSCAPE_RECEPTOR_INSET.value / LANDSCAPE_SKETCH_HEIGHT_DP) - note / 2f
        for (i in 0 until 4) {
            val centreX = bounds.leftPx + bounds.laneWidthPx * (i + 0.5f)
            drawRoundRect(
                color = ink.copy(alpha = 0.55f),
                topLeft = Offset(centreX - note / 2f, receptorY - note / 2f),
                size = Size(note, note),
                cornerRadius = CornerRadius(note * 0.2f),
                style = Stroke(width = 3f),
            )
        }
    }
    GlyphSlider(
        label = "Field width in landscape",
        value = fraction,
        reading = "${(fraction * 100f).roundToInt()}%",
        range = MIN_LANDSCAPE_FIELD..1f,
        note = "Held sideways, the lanes take this much of the screen, centred, and are tapped where they are. " +
            "The sides count for Left and Right.",
        onChange = onChange,
    )
}

/**
 * The TV shader's sliders, over a preview of the demo chart drawn through
 * them, so a change is seen as it is made rather than at the next song.
 */
@Composable
private fun TvShaderControls(
    state: GlyphUiState,
    set: (GlyphGraphics) -> Unit,
    assets: GlyphAssetRepository?,
    palette: GlyphPalette?,
) {
    val modifiers = state.gameplay.modifiers
    val graphics = modifiers.graphics
    val shader = graphics.tvShader
    val tv = graphics.tv
    val setTv: (GlyphTvTuning) -> Unit = { set(graphics.copy(tv = it)) }
    if (assets != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TV_PREVIEW_HEIGHT)
                .clip(MonoDimens.shapeLg)
                .glyphTvShader(graphics)
                .background(GlyphTheme.Ink)
                .clearAndSetSemantics { contentDescription = "Preview of the TV shader" },
        ) {
            GlyphPreviewField(
                chart = GlyphDemoChart.chart,
                timing = GlyphDemoChart.timing,
                turn = GlyphTurn.OFF,
                scrollMode = GlyphScrollMode.XMod(TV_PREVIEW_SPEED),
                skin = modifiers.skin,
                look = GlyphFieldLook.of(modifiers),
                assets = assets,
                palette = palette,
                loop = GlyphDemoChart.loopStartSeconds to GlyphDemoChart.loopSeconds,
                receptorInset = TV_PREVIEW_RECEPTOR_INSET,
                noteScale = TV_PREVIEW_NOTE_SCALE,
                reducedMotion = modifiers.reducedMotion,
                fadeLoop = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    val window = LocalWindowInfo.current.containerSize
    val pixel = tv.pixelSize(shader, LocalDensity.current.density, window.height)
    val range = GlyphTvTuning.pixelRange(shader)
    val wave = shader == GlyphTvShader.WAVE || shader == GlyphTvShader.ROYALE
    GlyphSlider(
        label = if (wave) "Line height" else "Pixel size",
        value = pixel.toFloat(),
        default = GlyphTvTuning.defaultPixelSize(shader, LocalDensity.current.density, window.height).coerceIn(range).toFloat(),
        reading = "$pixel px",
        range = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        note = when {
            window.height <= 0 -> null
            wave -> "${window.height / pixel} lines down the screen"
            shader == GlyphTvShader.CRT && pixel < CRT_LINES_FROM ->
                "${window.width / pixel} × ${window.height / pixel}" +
                    (if (pixel == 1) ", the phone's own pixels" else "") +
                    ". Scanlines show from $CRT_LINES_FROM px."
            else -> "${window.width / pixel} × ${window.height / pixel} TV pixels"
        },
        onChange = { setTv(tv.withPixelSize(shader, it.roundToInt())) },
    )
    GlyphSlider(
        label = "Strength",
        value = tv.strength,
        default = TV_DEFAULTS.strength,
        reading = percent(tv.strength),
        note = "How much of the effect over the plain picture",
        onChange = { setTv(tv.copy(strength = it)) },
    )
    GlyphSlider(
        label = "Brightness",
        value = tv.brightness,
        default = TV_DEFAULTS.brightness,
        reading = percent(tv.brightness),
        range = GlyphTvTuning.MIN_BRIGHTNESS..GlyphTvTuning.MAX_BRIGHTNESS,
        note = "Gives back the light the pattern takes",
        onChange = { setTv(tv.copy(brightness = it)) },
    )
    if (shader == GlyphTvShader.ROYALE) {
        RoyaleControls(tv, setTv)
    }
    if (shader == GlyphTvShader.CRT) {
        GlyphSlider(
            label = "Scanlines",
            value = tv.scanlines,
            default = TV_DEFAULTS.scanlines,
            reading = percent(tv.scanlines),
            note = "How dark the gaps between lines are",
            onChange = { setTv(tv.copy(scanlines = it)) },
        )
        GlyphSlider(
            label = "Mask",
            value = tv.mask,
            default = TV_DEFAULTS.mask,
            reading = percent(tv.mask),
            note = "The red, green and blue stripes of the tube",
            onChange = { setTv(tv.copy(mask = it)) },
        )
        GlyphSlider(
            label = "Glow",
            value = tv.glow,
            default = TV_DEFAULTS.glow,
            reading = percent(tv.glow),
            note = "Light bleeding out of bright parts",
            onChange = { setTv(tv.copy(glow = it)) },
        )
        GlyphSlider(
            label = "Sharpness",
            value = tv.sharpness,
            default = TV_DEFAULTS.sharpness,
            reading = percent(tv.sharpness),
            note = "How hard each pixel's edges are",
            onChange = { setTv(tv.copy(sharpness = it)) },
        )
    }
    GlyphSlider(
        label = "Chromatic shift",
        value = tv.chromatic,
        default = TV_DEFAULTS.chromatic,
        reading = percent(tv.chromatic),
        note = "Red and blue drift apart, as on a tube with its beams out of line",
        onChange = { setTv(tv.copy(chromatic = it)) },
    )
    GlyphSlider(
        label = "Curve: top & bottom",
        value = tv.curveTopDeg,
        default = TV_DEFAULTS.curveTopDeg,
        reading = degrees(tv.curveTopDeg),
        range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
        note = "How far the glass bows toward the top and bottom edges. About 16° is the classic tube.",
        onChange = { setTv(tv.copy(curveTopDeg = it)) },
    )
    GlyphSlider(
        label = "Curve: sides",
        value = tv.curveSideDeg,
        default = TV_DEFAULTS.curveSideDeg,
        reading = degrees(tv.curveSideDeg),
        range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
        note = "How far it bows toward the left and right edges. About 14° is the classic tube.",
        onChange = { setTv(tv.copy(curveSideDeg = it)) },
    )
    GlyphSlider(
        label = "Tilt: sideways",
        value = tv.tiltSideDeg,
        default = TV_DEFAULTS.tiltSideDeg,
        reading = degrees(tv.tiltSideDeg),
        range = -GlyphTvTuning.MAX_TILT_DEG..GlyphTvTuning.MAX_TILT_DEG,
        note = "The TV seen from one side: that side comes nearer and stands taller",
        onChange = { setTv(tv.copy(tiltSideDeg = it)) },
    )
    GlyphSlider(
        label = "Tilt: up & down",
        value = tv.tiltTopDeg,
        default = TV_DEFAULTS.tiltTopDeg,
        reading = degrees(tv.tiltTopDeg),
        range = -GlyphTvTuning.MAX_TILT_DEG..GlyphTvTuning.MAX_TILT_DEG,
        note = "The same from above or below",
        onChange = { setTv(tv.copy(tiltTopDeg = it)) },
    )
    GlyphSlider(
        label = "Bezel",
        value = tv.bezel,
        default = TV_DEFAULTS.bezel,
        reading = percent(tv.bezel),
        note = "A dark frame around the picture",
        onChange = { setTv(tv.copy(bezel = it)) },
    )
    GlyphSlider(
        label = "Rounded corners",
        value = tv.corners,
        default = TV_DEFAULTS.corners,
        reading = percent(tv.corners),
        onChange = { setTv(tv.copy(corners = it)) },
    )
    GlyphSlider(
        label = "Vignette",
        value = tv.vignette,
        default = TV_DEFAULTS.vignette,
        reading = percent(tv.vignette),
        note = "The picture darker toward its edges",
        onChange = { setTv(tv.copy(vignette = it)) },
    )
    GlyphSlider(
        label = "Glass glare",
        value = tv.glare,
        default = TV_DEFAULTS.glare,
        reading = percent(tv.glare),
        note = "A sheen across the glass and a streak of light on the frame",
        onChange = { setTv(tv.copy(glare = it)) },
    )
    GlyphButton(
        text = "Old TV look",
        onClick = {
            set(
                graphics.copy(
                    tvShader = GlyphTvShader.ROYALE,
                    tv = GlyphTvTuning.oldTv(window.height, keep = tv),
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    GlyphButton(
        text = "Reset ${shader.label}",
        onClick = { setTv(tv.reset(shader)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"

private fun degrees(value: Float): String = "%.1f°".format(value)

/** CRT-Royale's own sliders: its mask, the beams, and the light in the glass. */
@Composable
private fun RoyaleControls(tv: GlyphTvTuning, setTv: (GlyphTvTuning) -> Unit) {
    val density = LocalDensity.current.density
    Column {
        GlyphControlHeader("Mask", null)
        GlyphChipRow(
            options = ROYALE_MASKS,
            selected = ROYALE_MASKS.firstOrNull { it.first == tv.royaleMask } ?: ROYALE_MASKS[1],
            label = { it.second },
            onSelect = { setTv(tv.copy(royaleMask = it.first)) },
        )
    }
    val triad = tv.royaleTriadPx(density)
    val triads = GlyphTvTuning.ROYALE_TRIADS
    GlyphSlider(
        label = "Mask size",
        value = triad.toFloat(),
        default = TV_DEFAULTS.royaleTriadPx(density).toFloat(),
        reading = "$triad px",
        range = triads.first.toFloat()..triads.last.toFloat(),
        steps = triads.last - triads.first - 1,
        note = "One red, green and blue group of the mask",
        onChange = { setTv(tv.copy(royaleTriad = it.roundToInt())) },
    )
    GlyphSlider(
        label = "Mask strength",
        value = tv.royaleMaskStrength,
        default = TV_DEFAULTS.royaleMaskStrength,
        reading = percent(tv.royaleMaskStrength),
        note = "How much of the mask shows; the light it takes is given back",
        onChange = { setTv(tv.copy(royaleMaskStrength = it)) },
    )
    GlyphSlider(
        label = "Beam width",
        value = tv.royaleBeamWidth,
        default = TV_DEFAULTS.royaleBeamWidth,
        reading = percent(tv.royaleBeamWidth),
        note = "How far a bright line spreads into the gap between lines",
        onChange = { setTv(tv.copy(royaleBeamWidth = it)) },
    )
    GlyphSlider(
        label = "Beam shape",
        value = tv.royaleBeamShape,
        default = TV_DEFAULTS.royaleBeamShape,
        reading = percent(tv.royaleBeamShape),
        note = "Soft and round, or flat on top with hard edges, as a line brightens",
        onChange = { setTv(tv.copy(royaleBeamShape = it)) },
    )
    GlyphSlider(
        label = "Halation",
        value = tv.royaleHalation,
        default = TV_DEFAULTS.royaleHalation,
        reading = percent(tv.royaleHalation),
        note = "Light scattered inside the tube, spilling onto the lines",
        onChange = { setTv(tv.copy(royaleHalation = it)) },
    )
    GlyphSlider(
        label = "Diffusion",
        value = tv.royaleDiffusion,
        default = TV_DEFAULTS.royaleDiffusion,
        reading = percent(tv.royaleDiffusion),
        note = "A soft haze from the glass over the whole picture",
        onChange = { setTv(tv.copy(royaleDiffusion = it)) },
    )
    GlyphSlider(
        label = "Bloom",
        value = tv.royaleBloom,
        default = TV_DEFAULTS.royaleBloom,
        reading = percent(tv.royaleBloom),
        note = "How much bright parts glow past the mask",
        onChange = { setTv(tv.copy(royaleBloom = it)) },
    )
    GlyphSlider(
        label = "Edge shade",
        value = tv.royaleBorder,
        default = TV_DEFAULTS.royaleBorder,
        reading = percent(tv.royaleBorder),
        note = "The dark rim around the picture",
        onChange = { setTv(tv.copy(royaleBorder = it)) },
    )
}

/** CRT-Royale's three masks, by what they look like. */
private val ROYALE_MASKS = listOf(
    GlyphTvTuning.ROYALE_GRILLE to "Aperture grille",
    GlyphTvTuning.ROYALE_SLOT to "Slot mask",
    GlyphTvTuning.ROYALE_SHADOW to "Shadow mask",
)

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
        label = "Wheel sound",
        checked = state.wheelSound,
        onChange = { onEvent(GlyphEvent.SetWheelSound(it)) },
        note = "A soft swish as each song turns into the middle",
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
    CourseSection(state, onEvent)
    ChartModeSection(state, onEvent)

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
 * Fast or Slow: which separation server a chart goes to. The phone separates
 * nothing; this says where it happens and what it costs, and what is missing
 * when the chosen mode has no server to go to.
 */
/**
 * The course type: which buttons the charts are made for and played on. One
 * setting for the whole mode, since the song list, the charts and the keys
 * all follow it; the song list carries the same pill for switching in place.
 */
@Composable
private fun CourseSection(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val names = mapOf(
        CourseType.DANCE to stringResource(R.string.glyph_course_dance),
        CourseType.POPN to stringResource(R.string.glyph_course_popn),
    )
    Column {
        GlyphControlHeader(
            stringResource(R.string.glyph_course_type),
            when (state.course) {
                CourseType.DANCE -> stringResource(R.string.glyph_course_dance_note)
                CourseType.POPN -> stringResource(R.string.glyph_course_popn_note)
            },
        )
        GlyphSegmented(
            options = CourseType.entries.toList(),
            selected = state.course,
            label = { "${names.getValue(it)} · ${it.buttonsLabel}" },
            onSelect = { onEvent(GlyphEvent.SetCourse(it)) },
            description = stringResource(R.string.glyph_course_type),
        )
    }
}

@Composable
private fun ChartModeSection(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val servers = state.chartServers
    Column {
        GlyphControlHeader(
            "Chart processing",
            when (state.chartMode) {
                ChartMode.FAST -> "On a GPU server: seconds a song, billed by the second"
                ChartMode.SLOW -> "On a CPU server: minutes a song"
            },
        )
        GlyphSegmented(
            options = ChartMode.entries.toList(),
            selected = state.chartMode,
            label = { "${it.label} · ${it.hardware}" },
            onSelect = { onEvent(GlyphEvent.SetChartMode(it)) },
            description = "Chart processing",
        )
        val missing = when {
            !servers.any -> "No separation server yet. Add one under Settings › Connections."
            state.chartMode == ChartMode.SLOW && !servers.cpu ->
                "Slow needs a CPU server (Railway). Add one under Settings › Connections."
            state.chartMode == ChartMode.FAST && !servers.gpu && !servers.unknown ->
                "No GPU server, so Fast charts on the CPU server."
            state.chartMode == ChartMode.FAST && !servers.cpu ->
                "Add a CPU server too, and Fast falls back to it when the GPU cannot be reached."
            else -> null
        }
        if (missing != null) {
            Spacer(Modifier.height(MonoDimens.spacingXs))
            Text(
                text = missing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private val TAP_PAD_HEIGHT = 168.dp
/** A phone's landscape height in dp, to place the sketch's receptors as the game would. */
private const val LANDSCAPE_SKETCH_HEIGHT_DP = 400f
private val TV_PREVIEW_HEIGHT = 220.dp
private val TV_PREVIEW_RECEPTOR_INSET = 18.dp
private const val TV_PREVIEW_NOTE_SCALE = 0.6f
private const val TV_PREVIEW_SPEED = 2.25f
/** Below this a CRT's line is too few phone pixels to have a dark gap. */
private const val CRT_LINES_FROM = 3

/** Every TV slider's default, for its reset. */
private val TV_DEFAULTS = GlyphTvTuning()
