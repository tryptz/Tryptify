// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphDecor
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphGauntlets
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Training Ground: the same playfield, plus the tools to work on one passage.
 *
 * The layout puts the playfield at the top and the controls below it, so the
 * segment handles, the speed and the live timing readout are all reachable
 * without leaving the notes. Everything here is a modifier on the run in
 * progress rather than a separate mode — the engine, the clock and the scoring
 * are the ones the play screen uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphTrainingScreen(
    state: GlyphUiState,
    engine: GlyphGameplayEngine?,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    positionProvider: () -> Float,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
    ghost: GlyphGhost? = null,
    explosionProvider: () -> Map<tf.monochrome.android.glyph.asset.GlyphLane, LaneFlash> =
        { emptyMap() },
    comboBurstProvider: () -> Long = { 0L },
    comboProvider: () -> Int = { 0 },
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val training = state.training
    val gameplay = state.gameplay
    val landscape = glyphIsLandscape()
    val fieldFraction = glyphFieldFraction(gameplay.modifiers.landscapeFieldWidth)
    // The chart's buttons: the four dance panels, or pop'n's nine.
    val course = state.chart?.course ?: state.course
    val duration = state.selectedSong?.durationSeconds?.toFloat() ?: 0f
    val keyboard = rememberGlyphKeyboardFocus()

    // The route runs full-bleed here (the tab bar and mini player step aside),
    // so the top bar takes the status bar and the controls take the navigation
    // bar, each once.
    Column(
        modifier = modifier
            .fillMaxSize()
            .glyphKeyboard(
                keys = state.keys,
                lanes = !gameplay.isPaused,
                onPress = { lane, at -> onEvent(GlyphEvent.LanePressed(lane, at)) },
                onRelease = { lane, at -> onEvent(GlyphEvent.LaneReleased(lane, at)) },
                onPause = { onEvent(GlyphEvent.TogglePause) },
                focus = keyboard,
                course = course,
            ),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Training Ground",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                // Leaves at once, unlike system Back, which pauses first: this
                // is a labelled button pressed on purpose, not an edge swipe.
                IconButton(onClick = { onEvent(GlyphEvent.Quit) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Leave Training Ground",
                    )
                }
            },
            actions = {
                IconButton(onClick = { onEvent(GlyphEvent.TogglePause) }) {
                    Icon(
                        imageVector = if (gameplay.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (gameplay.isPaused) "Resume" else "Pause",
                    )
                }
                IconButton(onClick = { onEvent(GlyphEvent.Restart) }) {
                    Icon(Icons.Default.Replay, contentDescription = "Restart the segment")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // The playfield and the controls share the height. The controls used
        // to be an unweighted scrolling column under a weighted playfield,
        // which a Column measures first and lets take everything: with the
        // full set of controls the lanes were left no height at all.
        Box(
            modifier = Modifier
                .weight(PLAYFIELD_SHARE)
                .fillMaxWidth()
                .glyphTvShader(gameplay.modifiers.graphics)
                .background(GlyphTheme.Ink),
        ) {
            GlyphPlayfield(
                engine = engine,
                assets = assets,
                palette = palette,
                positionProvider = positionProvider,
                heldLanes = gameplay.heldLanes,
                scrollMode = gameplay.modifiers.scrollMode,
                timing = state.simfile?.timing ?: GlyphTiming.constant(120f),
                reducedMotion = gameplay.modifiers.reducedMotion,
                skin = gameplay.modifiers.skin,
                ghost = if (state.training.ghostEnabled) ghost else null,
                explosionProvider = explosionProvider,
                comboBurstProvider = comboBurstProvider,
                comboProvider = comboProvider,
                look = GlyphFieldLook.of(gameplay.modifiers),
                // Held sideways, a centred column of the setting's width, its
                // receptors nearer the bottom: thumbs come from the sides.
                receptorInset = if (landscape && !gameplay.modifiers.reverse) LANDSCAPE_RECEPTOR_INSET else null,
                modifier = Modifier
                    .fillMaxSize()
                    .glyphFieldColumn(fieldFraction, course.laneCount),
            )
            GlyphLaneInput(
                onPress = { lane, at -> onEvent(GlyphEvent.LanePressed(lane, at)) },
                onRelease = { lane, at -> onEvent(GlyphEvent.LaneReleased(lane, at)) },
                hitboxScale = gameplay.modifiers.hitboxScale,
                fieldFraction = fieldFraction,
                course = course,
                heldLanes = gameplay.heldLanes,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f - PLAYFIELD_SHARE)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MonoDimens.spacingLg)
                // Inside the scroll, so the last control scrolls clear of the
                // navigation bar rather than the column stopping short of it.
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            LiveTimingRow(state = state, typography = typography)

            SegmentPicker(
                waveform = training.waveform,
                durationSeconds = duration,
                loopStart = training.loop?.startSeconds,
                loopEnd = training.loop?.endSeconds,
                positionSeconds = gameplay.positionSeconds,
                onLoopChange = { start, end -> onEvent(GlyphEvent.SetLoop(start, end)) },
                assets = assets,
                isLoading = training.isWaveformLoading,
            )

            GlyphScrollControl(
                mode = gameplay.modifiers.scrollMode,
                onFamily = { onEvent(GlyphEvent.SetScrollFamily(it)) },
                onValue = { onEvent(GlyphEvent.SetScrollValue(it)) },
            )

            GlyphSkinControl(
                skin = gameplay.modifiers.skin,
                onSelect = { onEvent(GlyphEvent.SetSkin(it)) },
            )

            GlyphSpeedControl(
                speed = gameplay.modifiers.speed,
                pitchLinked = gameplay.modifiers.pitchLinkedToSpeed,
                onSpeedChange = { onEvent(GlyphEvent.SetSpeed(it)) },
                onPitchLinkChange = { onEvent(GlyphEvent.SetPitchLinked(it)) },
            )

            PractiseToggles(
                state = state,
                onEvent = onEvent,
            )

            WindowSliders(
                timingWindowScale = gameplay.modifiers.timingWindowScale,
                hitboxScale = gameplay.modifiers.hitboxScale,
                onTimingWindowChange = { onEvent(GlyphEvent.SetTimingWindowScale(it)) },
                onHitboxChange = { onEvent(GlyphEvent.SetHitboxScale(it)) },
            )

            GauntletRow(
                selected = training.gauntlet?.id,
                onSelect = { onEvent(GlyphEvent.StartGauntlet(it)) },
            )
        }
    }
}

/** How much of the height under the top bar the lanes get; the controls scroll in the rest. */
private const val PLAYFIELD_SHARE = 0.58f

/**
 * Early/late, mean offset, accuracy, combo and consistency.
 *
 * Consistency sits next to the mean deliberately: a large mean with a small
 * spread is a calibration problem and a small mean with a large spread is a
 * technique problem, and the two are only distinguishable side by side.
 */
@Composable
private fun LiveTimingRow(state: GlyphUiState, typography: GlyphTypography) {
    val score = state.gameplay.score
    val training = state.training

    // Live readouts, so the values are monospaced; everything around them is
    // the menu's.
    val readout = typography.title
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GlyphStat(
                label = "Offset",
                value = score.offsetLabel,
                valueColor = menuAccent(
                    when {
                        abs(training.liveOffsetMs) < 8f -> GlyphTheme.Positive
                        training.liveOffsetMs < 0f -> GlyphTheme.Early
                        else -> GlyphTheme.Late
                    },
                ),
                valueStyle = readout,
            )
            GlyphStat(
                label = "Spread",
                value = "±%.0f ms".format(training.consistencyMs),
                valueStyle = readout,
            )
            GlyphStat(
                label = "Accuracy",
                value = "%.1f%%".format(score.accuracy * 100),
                valueStyle = readout,
            )
            GlyphStat(label = "Combo", value = score.combo.toString(), valueStyle = readout)
            GlyphStat(label = "Pass", value = training.passCount.toString(), valueStyle = readout)
        }

        Spacer(Modifier.height(MonoDimens.spacingSm))

        // Early/late as a balance, with both counts in the label so the bar is
        // never the only way to read it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Early ${score.early}",
                style = MaterialTheme.typography.labelSmall,
                color = menuAccent(GlyphTheme.Early),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${score.late} late",
                style = MaterialTheme.typography.labelSmall,
                color = menuAccent(GlyphTheme.Late),
            )
        }
        Spacer(Modifier.height(4.dp))
        EarlyLateBar(lateShare = score.lateShare, early = score.early, late = score.late)
    }
}

@Composable
private fun EarlyLateBar(lateShare: Float, early: Int, late: Int) {
    val centre = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .semantics {
                contentDescription = "$early early, $late late"
            },
    ) {
        drawRect(GlyphTheme.Early, size = Size(size.width * (1f - lateShare), size.height))
        drawRect(
            color = GlyphTheme.Late,
            topLeft = Offset(size.width * (1f - lateShare), 0f),
            size = Size(size.width * lateShare, size.height),
        )
        // The centre mark is the target; without it a balanced bar and a
        // slightly-off one look the same.
        drawRect(
            color = centre,
            topLeft = Offset(size.width / 2f - 1f, 0f),
            size = Size(2f, size.height),
        )
    }
}

/**
 * The waveform, with two drag handles for the practice segment.
 *
 * The waveform is a coarse envelope rather than a sample-accurate trace: it
 * exists so a passage can be found by eye, and a hundred bars does that as well
 * as ten thousand for a fraction of the work.
 */
@Composable
private fun SegmentPicker(
    waveform: List<Float>,
    durationSeconds: Float,
    loopStart: Float?,
    loopEnd: Float?,
    positionSeconds: Float,
    onLoopChange: (Float, Float) -> Unit,
    assets: GlyphAssetRepository,
    isLoading: Boolean,
) {
    if (durationSeconds <= 0f) return

    var start by remember(loopStart) { mutableFloatStateOf(loopStart ?: 0f) }
    var end by remember(loopEnd) { mutableFloatStateOf(loopEnd ?: durationSeconds) }

    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val loopColour = scheme.primary
    val playhead = scheme.onSurface

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSectionLabel("Segment")
            if (isLoading) {
                Spacer(Modifier.width(MonoDimens.spacingSm))
                Text("reading…", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${timeLabel(start)} – ${timeLabel(end)}",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))

        // The pack ships a timeline ruler; using it beats hand-rolling ticks
        // that would drift out of step with the rest of the artwork.
        GlyphImageStrip(
            id = GlyphAssetCatalog.decor(GlyphDecor.TIMELINE_TICKS),
            assets = assets,
            aspect = 320f / 32f,
            tint = muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .liquidGlass(shape = MonoDimens.shapeMd)
                .semantics {
                    contentDescription =
                        "Practice segment from ${timeLabel(start)} to ${timeLabel(end)}. " +
                            "Drag to change."
                }
                .pointerInput(durationSeconds) {
                    detectHorizontalDragGestures { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val seconds = fraction * durationSeconds
                        // Whichever handle is nearer follows the finger, so a
                        // drag never has to begin exactly on a handle.
                        if (abs(seconds - start) <= abs(seconds - end)) {
                            start = seconds.coerceAtMost(end - 1f)
                        } else {
                            end = seconds.coerceAtLeast(start + 1f)
                        }
                        onLoopChange(start, end)
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bars = waveform.size
                if (bars > 0) {
                    val barWidth = size.width / bars
                    for (index in 0 until bars) {
                        val height = (waveform[index].coerceIn(0f, 1f) * size.height)
                        drawRect(
                            color = muted.copy(alpha = 0.5f),
                            topLeft = Offset(index * barWidth, (size.height - height) / 2f),
                            size = Size(barWidth * 0.7f, height),
                        )
                    }
                }

                val startX = (start / durationSeconds) * size.width
                val endX = (end / durationSeconds) * size.width
                drawRect(
                    color = loopColour.copy(alpha = 0.16f),
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(0f), size.height),
                )
                for (x in listOf(startX, endX)) {
                    drawRect(
                        color = loopColour,
                        topLeft = Offset(x - 1.5f, 0f),
                        size = Size(3f, size.height),
                    )
                }

                val playX = (positionSeconds / durationSeconds).coerceIn(0f, 1f) * size.width
                drawRect(
                    color = playhead,
                    topLeft = Offset(playX - 1f, 0f),
                    size = Size(2f, size.height),
                )
            }
        }
    }
}

private fun timeLabel(seconds: Float): String =
    "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)

/**
 * Practice speed, and whether pitch follows it.
 *
 * The link is exposed rather than assumed because both behaviours are wanted:
 * linked sounds like a tape slowing down and keeps the timbre honest; unlinked
 * holds the pitch, which is what someone learning a riff at 0.7× needs so the
 * notes stay where their ear expects them.
 */
@Composable
fun GlyphSpeedControl(
    speed: Float,
    pitchLinked: Boolean,
    onSpeedChange: (Float) -> Unit,
    onPitchLinkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ControlHeader(label = "Speed", value = "%.2f×".format(speed))
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = SPEED_STEPS,
            selected = SPEED_STEPS.minByOrNull { abs(it - speed) },
            label = { "%.2f×".format(it) },
            onSelect = onSpeedChange,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        // Both ways named, the chosen one marked, rather than one button whose
        // label flips: a label that changes on tap reads as either the state or
        // the action, and nobody can tell which.
        GlyphChipRow(
            options = listOf(true, false),
            selected = pitchLinked,
            label = { linked -> if (linked) "Pitch follows speed" else "Pitch held" },
            onSelect = onPitchLinkChange,
        )
    }
}

/** A control's caption, with its current value at the other end of the line. */
@Composable
private fun ControlHeader(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphSectionLabel(label)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val SPEED_STEPS = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f)

/**
 * Scroll family and value — StepMania's XMod / CMod / MMod.
 *
 * Two rows rather than one long list, because the two choices are different in
 * kind: the family decides what on-screen distance *means*, and the value only
 * scales it. Flattening them into one row of chips would put "2.0x" and "C400"
 * side by side as if they were comparable settings.
 *
 * Kept separate from playback speed above it, which they are constantly
 * confused with: speed changes the music, scroll changes only the reading.
 */
@Composable
fun GlyphScrollControl(
    mode: GlyphScrollMode,
    onFamily: (GlyphScrollFamily) -> Unit,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val family = when (mode) {
        is GlyphScrollMode.XMod -> GlyphScrollFamily.X
        is GlyphScrollMode.CMod -> GlyphScrollFamily.C
        is GlyphScrollMode.MMod -> GlyphScrollFamily.M
    }
    val steps = when (family) {
        GlyphScrollFamily.X -> GlyphScrollMode.X_STEPS
        GlyphScrollFamily.C -> GlyphScrollMode.C_STEPS
        GlyphScrollFamily.M -> GlyphScrollMode.M_STEPS
    }
    val value = when (mode) {
        is GlyphScrollMode.XMod -> mode.multiplier
        is GlyphScrollMode.CMod -> mode.targetBpm
        is GlyphScrollMode.MMod -> mode.targetBpm
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ControlHeader(label = "Scroll", value = mode.label)
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = GlyphScrollFamily.entries.toList(),
            selected = family,
            label = { it.label },
            onSelect = onFamily,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = steps,
            selected = steps.minByOrNull { abs(it - value) },
            label = { step ->
                if (family == GlyphScrollFamily.X) "%.1fx".format(step) else step.toInt().toString()
            },
            onSelect = onValue,
        )
        Spacer(Modifier.height(4.dp))
        // Says what the family does, not what it is called. "CMod" alone tells
        // someone who does not already know exactly nothing.
        Text(
            text = mode.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which note art the playfield draws. Looks only: it changes nothing about
 * the run, so it can be switched mid-song from the pause panel.
 */
@Composable
fun GlyphSkinControl(
    skin: GlyphSkin,
    onSelect: (GlyphSkin) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ControlHeader(label = "Notes", value = skin.label)
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = GlyphSkin.entries.toList(),
            selected = skin,
            label = { it.label },
            onSelect = onSelect,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (skin) {
                GlyphSkin.RETRO_GLASS ->
                    "StepMania's retro arrows in the app's glass, from your UI panel glass settings."
                GlyphSkin.RETRO -> "StepMania's retro arrows, as StepMania draws them."
                GlyphSkin.CLASSIC -> "Trypt Mania's own vector arrows."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PractiseToggles(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
) {
    val modifiers = state.gameplay.modifiers
    val training = state.training
    val accent = MaterialTheme.colorScheme.primary

    Column {
        GlyphSectionLabel("Practice")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        // Named chips, each with its icon, rather than a row of bare
        // pictograms: on or off is the selected rim and spoken as selected,
        // and what each one is is written on it.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        ) {
            GlassChoiceChip(
                label = "Metronome",
                leadingIcon = Icons.Default.Timer,
                selected = modifiers.metronome,
                accent = accent,
                height = 40.dp,
                onClick = { onEvent(GlyphEvent.ToggleMetronome) },
            )
            GlassChoiceChip(
                label = "Mirror",
                leadingIcon = Icons.Default.Flip,
                selected = modifiers.mirror,
                accent = accent,
                height = 40.dp,
                onClick = { onEvent(GlyphEvent.ToggleMirror) },
            )
            GlassChoiceChip(
                label = "Shuffle",
                leadingIcon = Icons.Default.Shuffle,
                selected = modifiers.shuffle,
                accent = accent,
                height = 40.dp,
                onClick = { onEvent(GlyphEvent.ToggleShuffle) },
            )
            GlassChoiceChip(
                label = "Ghost",
                description = if (training.hasGhost) "Ghost of your last run" else "No ghost recorded yet",
                leadingIcon = Icons.Default.History,
                selected = training.ghostEnabled,
                enabled = training.hasGhost,
                accent = accent,
                height = 40.dp,
                onClick = { onEvent(GlyphEvent.SetGhostEnabled(!training.ghostEnabled)) },
            )
            GlassChoiceChip(
                label = "Loop",
                description = if (training.loop != null) "Clear the loop" else "No loop set",
                leadingIcon = Icons.Default.Repeat,
                selected = training.loop != null,
                enabled = training.loop != null,
                accent = accent,
                height = 40.dp,
                onClick = { onEvent(GlyphEvent.ClearLoop) },
            )
        }

        Spacer(Modifier.height(MonoDimens.spacingMd))
        GlyphSectionLabel("Count-in")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = listOf(GlyphCountIn.NONE, GlyphCountIn.ONE_BAR, GlyphCountIn.TWO_BARS),
            selected = training.countIn,
            label = { countIn ->
                when (countIn.beats) {
                    0 -> "None"
                    else -> "${countIn.beats} beats"
                }
            },
            onSelect = { onEvent(GlyphEvent.SetCountIn(it)) },
        )
    }
}

/**
 * Timing windows and hitbox size.
 *
 * Both are practice aids and both are recorded on the attempt when they are not
 * at 1×, because a run under widened windows is not a run under standard ones
 * and a personal best has to mean one thing.
 */
@Composable
private fun WindowSliders(
    timingWindowScale: Float,
    hitboxScale: Float,
    onTimingWindowChange: (Float) -> Unit,
    onHitboxChange: (Float) -> Unit,
) {
    Column {
        GlyphSectionLabel("Timing windows")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = WINDOW_STEPS,
            selected = WINDOW_STEPS.minByOrNull { abs(it - timingWindowScale) },
            label = { scale ->
                when {
                    scale < 1f -> "Tight %.2f×".format(scale)
                    scale > 1f -> "Wide %.2f×".format(scale)
                    else -> "Standard"
                }
            },
            onSelect = onTimingWindowChange,
        )

        Spacer(Modifier.height(MonoDimens.spacingMd))
        GlyphSectionLabel("Lane hitbox")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = HITBOX_STEPS,
            selected = HITBOX_STEPS.minByOrNull { abs(it - hitboxScale) },
            label = { scale ->
                if (scale >= 1f) "Full lane" else "%.0f%% lane".format(scale * 100)
            },
            onSelect = onHitboxChange,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Says which way the setting goes, because "narrower" is the only
            // direction available and a bare multiplier suggests otherwise.
            text = "Narrowing leaves dead gutters between lanes, so a thumb " +
                "landing between two no longer counts as either.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val WINDOW_STEPS = listOf(0.6f, 0.8f, 1.0f, 1.3f, 1.6f)
// Four lanes already tile the width, so there is nothing above 1.0 to widen
// into. The steps go the one way the setting can actually go.
private val HITBOX_STEPS = listOf(0.55f, 0.7f, 0.85f, 1.0f)

@Composable
private fun GauntletRow(
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        GlyphSectionLabel("Challenge gauntlets")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = GlyphGauntlets.ALL.map { it.id },
            selected = selected,
            label = { id -> GlyphGauntlets.byId(id)?.name ?: id },
            onSelect = onSelect,
        )
        val active = selected?.let(GlyphGauntlets::byId)
        if (active != null) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Text(
                text = active.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Target ${(active.targetAccuracy * 100).toInt()}% · ${active.focus}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

