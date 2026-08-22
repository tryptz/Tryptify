// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphIcon
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * The play screen: a HUD, the playfield, and the lane input over it.
 *
 * The playfield is given the whole area and the readouts sit above and below it
 * rather than beside it, so the four lanes stay as wide as the device allows.
 * Nothing overlaps the receptor row, which is the one part of the screen the
 * player is actually looking at.
 */
@Composable
fun GlyphGameplayScreen(
    state: GlyphUiState,
    engine: GlyphGameplayEngine?,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    positionProvider: () -> Float,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
    explosionProvider: () -> Map<GlyphLane, LaneFlash> = { emptyMap() },
    comboBurstProvider: () -> Long = { 0L },
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val gameplay = state.gameplay
    // A chart that failed to load still has to draw something rather than
    // crash; a steady map is the harmless stand-in.
    val timing = state.simfile?.timing ?: remember { GlyphTiming.constant(120f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink),
    ) {
        GlyphPlayfield(
            engine = engine,
            assets = assets,
            palette = palette,
            positionProvider = positionProvider,
            heldLanes = gameplay.heldLanes,
            scrollMode = gameplay.modifiers.scrollMode,
            timing = timing,
            reducedMotion = gameplay.modifiers.reducedMotion,
            explosionProvider = explosionProvider,
            comboBurstProvider = comboBurstProvider,
            modifier = Modifier.fillMaxSize(),
        )

        // The lane input sits over the playfield rather than under a row of
        // buttons: the whole lane is the target, which is what makes the mode
        // playable with thumbs.
        GlyphLaneInput(
            onPress = { onEvent(GlyphEvent.LanePressed(it)) },
            onRelease = { onEvent(GlyphEvent.LaneReleased(it)) },
            hitboxScale = gameplay.modifiers.hitboxScale,
            modifier = Modifier.fillMaxSize(),
        )

        if (gameplay.countInBeatsRemaining > 0) {
            CountIn(
                beatsRemaining = gameplay.countInBeatsRemaining,
                typography = typography,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        JudgementOverlay(
            judgement = gameplay.lastJudgement,
            shownAtMs = gameplay.lastJudgementAtMs,
            combo = gameplay.score.combo,
            reducedMotion = gameplay.modifiers.reducedMotion,
            modifier = Modifier.align(Alignment.Center),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .systemBarsPadding(),
        ) {
            GameplayHud(
                state = state,
                typography = typography,
                assets = assets,
                onEvent = onEvent,
            )
        }

        if (gameplay.isPaused) {
            PauseOverlay(
                state = state,
                typography = typography,
                onEvent = onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}


/**
 * The readouts.
 *
 * Kept to one row plus a progress line. Everything here changes constantly and
 * every extra number is something competing with the lanes for attention.
 */
@Composable
private fun GameplayHud(
    state: GlyphUiState,
    typography: GlyphTypography,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
) {
    val gameplay = state.gameplay
    val score = gameplay.score

    Column(modifier = Modifier.padding(horizontal = GlyphTheme.Grid * 2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid * 2),
        ) {
            GlyphStat(
                label = "Score",
                value = "%,d".format(score.score),
                typography = typography,
            )
            GlyphStat(
                label = "Combo",
                value = score.combo.toString(),
                valueColor = if (score.combo > 0) GlyphTheme.Positive else GlyphTheme.Muted,
                typography = typography,
            )
            GlyphStat(
                label = "Accuracy",
                value = "%.1f%%".format(score.accuracy * 100),
                typography = typography,
            )
            Spacer(Modifier.weight(1f))
            GlyphIconButton(
                icon = if (gameplay.isPaused) GlyphIcon.PLAY else GlyphIcon.PAUSE,
                label = if (gameplay.isPaused) "Resume" else "Pause",
                assets = assets,
                onClick = { onEvent(GlyphEvent.TogglePause) },
            )
        }

        Spacer(Modifier.height(GlyphTheme.Grid))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid),
        ) {
            Text(
                text = "${gameplay.bpm.toInt()} BPM",
                style = typography.label,
                color = GlyphTheme.Muted,
            )
            Text(
                text = gameplay.sectionLabel,
                style = typography.label,
                color = GlyphTheme.Muted,
            )
            // The scroll mode is the first thing a player checks when a chart
            // reads wrong, so it stays on screen rather than living two taps
            // away in the pause panel.
            Text(
                text = gameplay.modifiers.scrollMode.label,
                style = typography.label,
                color = GlyphTheme.Muted,
            )
            if (gameplay.modifiers.altersScoring) {
                // A modified run is not comparable with a clean one, and the
                // player should be able to see that while playing rather than
                // discover it on the results screen.
                Text(
                    text = modifierSummary(gameplay.modifiers),
                    style = typography.label,
                    color = GlyphTheme.Warning,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        GlyphMeter(
            fraction = gameplay.progress,
            color = GlyphTheme.Paper.copy(alpha = 0.6f),
            description = "Song progress, ${(gameplay.progress * 100).toInt()} percent",
        )
    }
}

private fun modifierSummary(modifiers: GlyphModifiers): String = buildList {
    if (modifiers.speed != 1f) add("%.2f×".format(modifiers.speed))
    if (modifiers.mirror) add("Mirror")
    if (modifiers.shuffle) add("Shuffle")
    if (modifiers.timingWindowScale != 1f) add("Windows %.2f×".format(modifiers.timingWindowScale))
}.joinToString(" · ")

/**
 * The judgement, drawn through the lyrics FX pipeline.
 *
 * This replaced the pack's flat wordmark image. The 5x7 wordmarks are still in
 * the pack and still the right artwork for a results sheet or a still, but on
 * a live playfield a per-letter 3D letterform lit by the glass shader reads as
 * an event in a way a flat sprite does not.
 *
 * It announces itself either way: the letterforms are graphics as far as a
 * screen reader is concerned, so without a live region a judgement is silent.
 */
@Composable
private fun JudgementOverlay(
    judgement: GlyphJudgement?,
    shownAtMs: Long,
    combo: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (judgement == null) return
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = if (combo > 1) {
                "${judgement.label}, combo $combo"
            } else {
                judgement.label
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        GlyphJudgementFx(
            judgement = judgement,
            shownAtMs = shownAtMs,
            combo = combo,
            reducedMotion = reducedMotion,
        )
    }
}


/**
 * The count-in.
 *
 * A number, announced. The music is already playing during the count, so
 * without something on screen saying why nothing is being judged yet, the first
 * bar reads as the game having failed to start.
 */
@Composable
private fun CountIn(
    beatsRemaining: Int,
    typography: GlyphTypography,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = "Starting in $beatsRemaining"
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = beatsRemaining.toString(),
            style = typography.readout,
            color = GlyphTheme.Paper,
        )
    }
}

/** Pause: the practice controls, and the two ways out. */
@Composable
private fun PauseOverlay(
    state: GlyphUiState,
    typography: GlyphTypography,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(GlyphTheme.Ink.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        GlyphPanel(modifier = Modifier.padding(GlyphTheme.Grid * 3)) {
            Text("PAUSED", style = typography.title, color = GlyphTheme.Paper)
            Spacer(Modifier.height(GlyphTheme.Grid * 2))

            GlyphScrollControl(
                mode = state.gameplay.modifiers.scrollMode,
                typography = typography,
                onFamily = { onEvent(GlyphEvent.SetScrollFamily(it)) },
                onValue = { onEvent(GlyphEvent.SetScrollValue(it)) },
            )

            Spacer(Modifier.height(GlyphTheme.Grid * 2))

            GlyphSpeedControl(
                speed = state.gameplay.modifiers.speed,
                pitchLinked = state.gameplay.modifiers.pitchLinkedToSpeed,
                typography = typography,
                onSpeedChange = { onEvent(GlyphEvent.SetSpeed(it)) },
                onPitchLinkChange = { onEvent(GlyphEvent.SetPitchLinked(it)) },
            )

            Spacer(Modifier.height(GlyphTheme.Grid * 2))
            Row(horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid)) {
                GlyphPrimaryButton(
                    text = "Resume",
                    typography = typography,
                    onClick = { onEvent(GlyphEvent.TogglePause) },
                    modifier = Modifier.weight(1f),
                )
                GlyphSecondaryButton(
                    text = "Restart",
                    typography = typography,
                    onClick = { onEvent(GlyphEvent.Restart) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(GlyphTheme.Grid))
            GlyphSecondaryButton(
                text = "Quit to songs",
                typography = typography,
                accent = GlyphTheme.Muted,
                onClick = { onEvent(GlyphEvent.Quit) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
