// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphDecor
import tf.monochrome.android.glyph.asset.GlyphIcon
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphGauntlets

/**
 * Training Ground: the same playfield, plus the tools to work on one passage.
 *
 * The layout puts the playfield at the top and the controls below it, so the
 * segment handles, the speed and the live timing readout are all reachable
 * without leaving the notes. Everything here is a modifier on the run in
 * progress rather than a separate mode — the engine, the clock and the scoring
 * are the ones the play screen uses.
 */
@Composable
fun GlyphTrainingScreen(
    state: GlyphUiState,
    engine: GlyphGameplayEngine?,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    positionProvider: () -> Float,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
    ghost: tf.monochrome.android.glyph.data.GlyphGhost? = null,
    explosionProvider: () -> Map<tf.monochrome.android.glyph.asset.GlyphLane, LaneFlash> =
        { emptyMap() },
    comboBurstProvider: () -> Long = { 0L },
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val training = state.training
    val gameplay = state.gameplay
    val duration = state.selectedSong?.durationSeconds?.toFloat() ?: 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlyphTheme.Grid),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIconButton(
                icon = GlyphIcon.ARROW_BACK,
                label = "Leave Training Ground",
                assets = assets,
                onClick = { onEvent(GlyphEvent.Quit) },
            )
            Text(
                text = "TRAINING GROUND",
                style = typography.title,
                color = GlyphTheme.Paper,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            GlyphIconButton(
                icon = if (gameplay.isPaused) GlyphIcon.PLAY else GlyphIcon.PAUSE,
                label = if (gameplay.isPaused) "Resume" else "Pause",
                assets = assets,
                onClick = { onEvent(GlyphEvent.TogglePause) },
            )
            GlyphIconButton(
                icon = GlyphIcon.RESTART,
                label = "Restart the segment",
                assets = assets,
                onClick = { onEvent(GlyphEvent.Restart) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            GlyphPlayfield(
                engine = engine,
                assets = assets,
                palette = palette,
                positionProvider = positionProvider,
                heldLanes = gameplay.heldLanes,
                scrollMode = gameplay.modifiers.scrollMode,
                timing = state.simfile?.timing ?: GlyphTiming.constant(120f),
                reducedMotion = gameplay.modifiers.reducedMotion,
                ghost = if (state.training.ghostEnabled) ghost else null,
                explosionProvider = explosionProvider,
                comboBurstProvider = comboBurstProvider,
                modifier = Modifier.fillMaxSize(),
            )
            GlyphLaneInput(
                onPress = { onEvent(GlyphEvent.LanePressed(it)) },
                onRelease = { onEvent(GlyphEvent.LaneReleased(it)) },
                hitboxScale = gameplay.modifiers.hitboxScale,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(GlyphTheme.Grid * 2),
            verticalArrangement = Arrangement.spacedBy(GlyphTheme.Grid + 4.dp),
        ) {
            LiveTimingRow(state = state, typography = typography)

            SegmentPicker(
                waveform = training.waveform,
                durationSeconds = duration,
                loopStart = training.loop?.startSeconds,
                loopEnd = training.loop?.endSeconds,
                positionSeconds = gameplay.positionSeconds,
                onLoopChange = { start, end -> onEvent(GlyphEvent.SetLoop(start, end)) },
                typography = typography,
                assets = assets,
                isLoading = training.isWaveformLoading,
            )

            GlyphScrollControl(
                mode = gameplay.modifiers.scrollMode,
                typography = typography,
                onFamily = { onEvent(GlyphEvent.SetScrollFamily(it)) },
                onValue = { onEvent(GlyphEvent.SetScrollValue(it)) },
            )

            GlyphSpeedControl(
                speed = gameplay.modifiers.speed,
                pitchLinked = gameplay.modifiers.pitchLinkedToSpeed,
                typography = typography,
                onSpeedChange = { onEvent(GlyphEvent.SetSpeed(it)) },
                onPitchLinkChange = { onEvent(GlyphEvent.SetPitchLinked(it)) },
            )

            PractiseToggles(
                state = state,
                assets = assets,
                typography = typography,
                onEvent = onEvent,
            )

            WindowSliders(
                timingWindowScale = gameplay.modifiers.timingWindowScale,
                hitboxScale = gameplay.modifiers.hitboxScale,
                typography = typography,
                onTimingWindowChange = { onEvent(GlyphEvent.SetTimingWindowScale(it)) },
                onHitboxChange = { onEvent(GlyphEvent.SetHitboxScale(it)) },
            )

            GauntletRow(
                selected = training.gauntlet?.id,
                typography = typography,
                onSelect = { onEvent(GlyphEvent.StartGauntlet(it)) },
            )
        }
    }
}

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

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GlyphStat(
                label = "Offset",
                value = score.offsetLabel,
                valueColor = when {
                    abs(training.liveOffsetMs) < 8f -> GlyphTheme.Positive
                    training.liveOffsetMs < 0f -> GlyphTheme.Early
                    else -> GlyphTheme.Late
                },
                typography = typography,
            )
            GlyphStat(
                label = "Spread",
                value = "±%.0f ms".format(training.consistencyMs),
                typography = typography,
            )
            GlyphStat(
                label = "Accuracy",
                value = "%.1f%%".format(score.accuracy * 100),
                typography = typography,
            )
            GlyphStat(label = "Combo", value = score.combo.toString(), typography = typography)
            GlyphStat(label = "Pass", value = training.passCount.toString(), typography = typography)
        }

        Spacer(Modifier.height(GlyphTheme.Grid))

        // Early/late as a balance, with both counts in the label so the bar is
        // never the only way to read it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EARLY ${score.early}", style = typography.label, color = GlyphTheme.Early)
            Spacer(Modifier.weight(1f))
            Text("${score.late} LATE", style = typography.label, color = GlyphTheme.Late)
        }
        Spacer(Modifier.height(4.dp))
        EarlyLateBar(lateShare = score.lateShare, early = score.early, late = score.late)
    }
}

@Composable
private fun EarlyLateBar(lateShare: Float, early: Int, late: Int) {
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
            color = GlyphTheme.Paper,
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
    typography: GlyphTypography,
    assets: GlyphAssetRepository,
    isLoading: Boolean,
) {
    if (durationSeconds <= 0f) return

    var start by remember(loopStart) { mutableFloatStateOf(loopStart ?: 0f) }
    var end by remember(loopEnd) { mutableFloatStateOf(loopEnd ?: durationSeconds) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SEGMENT", style = typography.label, color = GlyphTheme.Muted)
            if (isLoading) {
                Spacer(Modifier.width(GlyphTheme.Grid))
                Text("reading…", style = typography.label, color = GlyphTheme.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${timeLabel(start)} – ${timeLabel(end)}",
                style = typography.mono,
                color = GlyphTheme.Paper,
            )
        }
        Spacer(Modifier.height(GlyphTheme.Grid))

        // The pack ships a timeline ruler; using it beats hand-rolling ticks
        // that would drift out of step with the rest of the artwork.
        GlyphImageStrip(
            id = GlyphAssetCatalog.decor(GlyphDecor.TIMELINE_TICKS),
            assets = assets,
            aspect = 320f / 32f,
            tint = GlyphTheme.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(GlyphTheme.Grid))
                .background(GlyphTheme.InkPanel)
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
                            color = GlyphTheme.Muted.copy(alpha = 0.5f),
                            topLeft = Offset(index * barWidth, (size.height - height) / 2f),
                            size = Size(barWidth * 0.7f, height),
                        )
                    }
                }

                val startX = (start / durationSeconds) * size.width
                val endX = (end / durationSeconds) * size.width
                drawRect(
                    color = GlyphTheme.Positive.copy(alpha = 0.14f),
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(0f), size.height),
                )
                for (x in listOf(startX, endX)) {
                    drawRect(
                        color = GlyphTheme.Positive,
                        topLeft = Offset(x - 1.5f, 0f),
                        size = Size(3f, size.height),
                    )
                }

                val playX = (positionSeconds / durationSeconds).coerceIn(0f, 1f) * size.width
                drawRect(
                    color = GlyphTheme.Paper,
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
    typography: GlyphTypography,
    onSpeedChange: (Float) -> Unit,
    onPitchLinkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SPEED", style = typography.label, color = GlyphTheme.Muted)
            Spacer(Modifier.weight(1f))
            Text("%.2f×".format(speed), style = typography.mono, color = GlyphTheme.Paper)
        }
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = SPEED_STEPS,
            selected = SPEED_STEPS.minByOrNull { abs(it - speed) },
            label = { "%.2f×".format(it) },
            onSelect = onSpeedChange,
            typography = typography,
        )
        Spacer(Modifier.height(GlyphTheme.Grid))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphSecondaryButton(
                text = if (pitchLinked) "Pitch follows speed" else "Pitch held",
                typography = typography,
                accent = if (pitchLinked) GlyphTheme.Paper else GlyphTheme.Positive,
                onClick = { onPitchLinkChange(!pitchLinked) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    typography: GlyphTypography,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SCROLL", style = typography.label, color = GlyphTheme.Muted)
            Spacer(Modifier.weight(1f))
            Text(mode.label, style = typography.mono, color = GlyphTheme.Paper)
        }
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = GlyphScrollFamily.entries.toList(),
            selected = family,
            label = { it.label },
            onSelect = onFamily,
            typography = typography,
        )
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = steps,
            selected = steps.minByOrNull { abs(it - value) },
            label = { step ->
                if (family == GlyphScrollFamily.X) "%.1fx".format(step) else step.toInt().toString()
            },
            onSelect = onValue,
            typography = typography,
        )
        Spacer(Modifier.height(4.dp))
        // Says what the family does, not what it is called. "CMod" alone tells
        // someone who does not already know exactly nothing.
        Text(mode.summary, style = typography.label, color = GlyphTheme.Muted)
    }
}

@Composable
private fun PractiseToggles(
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    typography: GlyphTypography,
    onEvent: (GlyphEvent) -> Unit,
) {
    val modifiers = state.gameplay.modifiers
    val training = state.training

    Column {
        Text("PRACTICE", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        Row(horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid)) {
            GlyphIconButton(
                icon = GlyphIcon.METRONOME,
                label = "Metronome",
                assets = assets,
                active = modifiers.metronome,
                onClick = { onEvent(GlyphEvent.ToggleMetronome) },
            )
            GlyphIconButton(
                icon = GlyphIcon.MIRROR,
                label = "Mirror",
                assets = assets,
                active = modifiers.mirror,
                onClick = { onEvent(GlyphEvent.ToggleMirror) },
            )
            GlyphIconButton(
                icon = GlyphIcon.SHUFFLE,
                label = "Shuffle",
                assets = assets,
                active = modifiers.shuffle,
                onClick = { onEvent(GlyphEvent.ToggleShuffle) },
            )
            GlyphIconButton(
                icon = GlyphIcon.GHOST,
                label = if (training.hasGhost) "Ghost of your last run" else "No ghost recorded yet",
                assets = assets,
                enabled = training.hasGhost,
                active = training.ghostEnabled,
                onClick = { onEvent(GlyphEvent.SetGhostEnabled(!training.ghostEnabled)) },
            )
            GlyphIconButton(
                icon = GlyphIcon.LOOP_SEGMENT,
                label = if (training.loop != null) "Clear the loop" else "No loop set",
                assets = assets,
                enabled = training.loop != null,
                active = training.loop != null,
                onClick = { onEvent(GlyphEvent.ClearLoop) },
            )
        }

        Spacer(Modifier.height(GlyphTheme.Grid))
        Text("COUNT-IN", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
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
            typography = typography,
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
    typography: GlyphTypography,
    onTimingWindowChange: (Float) -> Unit,
    onHitboxChange: (Float) -> Unit,
) {
    Column {
        Text("TIMING WINDOWS", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
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
            typography = typography,
        )

        Spacer(Modifier.height(GlyphTheme.Grid))
        Text("LANE HITBOX", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = HITBOX_STEPS,
            selected = HITBOX_STEPS.minByOrNull { abs(it - hitboxScale) },
            label = { scale ->
                if (scale >= 1f) "Full lane" else "%.0f%% lane".format(scale * 100)
            },
            onSelect = onHitboxChange,
            typography = typography,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Says which way the setting goes, because "narrower" is the only
            // direction available and a bare multiplier suggests otherwise.
            text = "Narrowing leaves dead gutters between lanes, so a thumb " +
                "landing between two no longer counts as either.",
            style = typography.label,
            color = GlyphTheme.Muted,
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
    typography: GlyphTypography,
    onSelect: (String) -> Unit,
) {
    Column {
        Text("CHALLENGE GAUNTLETS", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = GlyphGauntlets.ALL.map { it.id },
            selected = selected,
            label = { id -> GlyphGauntlets.byId(id)?.name ?: id },
            onSelect = onSelect,
            typography = typography,
        )
        val active = selected?.let(GlyphGauntlets::byId)
        if (active != null) {
            Spacer(Modifier.height(GlyphTheme.Grid))
            Text(active.description, style = typography.body, color = GlyphTheme.Muted)
            Text(
                text = "Target ${(active.targetAccuracy * 100).toInt()}% · ${active.focus}",
                style = typography.label,
                color = GlyphTheme.Positive,
            )
        }
    }
}

