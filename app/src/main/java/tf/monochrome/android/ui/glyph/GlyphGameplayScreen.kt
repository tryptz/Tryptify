// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudge
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.MonoDimens

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
    comboProvider: () -> Int = { 0 },
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val fonts = rememberStepFonts()
    val context = LocalContext.current
    // Every judgement sprite decoded before the first step, so the first
    // FLAWLESS is never drawn a frame late.
    LaunchedEffect(Unit) { GlyphStepArt.preloadJudgements(context) }
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
            skin = gameplay.modifiers.skin,
            explosionProvider = explosionProvider,
            comboBurstProvider = comboBurstProvider,
            comboProvider = comboProvider,
            look = GlyphFieldLook.of(gameplay.modifiers),
            modifier = Modifier.fillMaxSize(),
        )

        // The lane input sits over the playfield rather than under a row of
        // buttons: the whole lane is the target, which is what makes the mode
        // playable with thumbs.
        GlyphLaneInput(
            onPress = { lane, at -> onEvent(GlyphEvent.LanePressed(lane, at)) },
            onRelease = { lane, at -> onEvent(GlyphEvent.LaneReleased(lane, at)) },
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
            early = gameplay.lastJudgementEarly,
            shownAtMs = gameplay.lastJudgementAtMs,
            combo = gameplay.score.combo,
            fullCombo = gameplay.fullCombo,
            fonts = fonts,
            modifier = Modifier.align(Alignment.Center),
        )

        if (gameplay.introStartedAtMs > 0L) {
            ReadyGo(
                startedAtMs = gameplay.introStartedAtMs,
                fonts = fonts,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Clear of the status bar and the cutout when they are there, and only
        // of those: the route runs full-bleed while playing and the system
        // bars are usually hidden, and the HUD is pinned to the top, so a
        // bottom inset here would only pad a column nobody sees the end of.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            GameplayHud(
                state = state,
                typography = typography,
                fonts = fonts,
                onEvent = onEvent,
            )
        }

        if (gameplay.isPaused) {
            PauseOverlay(
                state = state,
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
    fonts: GlyphStepFonts,
    onEvent: (GlyphEvent) -> Unit,
) {
    val gameplay = state.gameplay

    Column(modifier = Modifier.padding(horizontal = GlyphTheme.Grid * 2)) {
        // StepMania's top row: the life bar, the percent, and the way out.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid * 2),
        ) {
            GlyphLifeBar(
                life = gameplay.life,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = StepManiaScoreKeeper.formatPercent(gameplay.percent),
                style = fonts.score,
                color = GlyphTheme.Paper,
                modifier = Modifier.semantics {
                    contentDescription = "Score ${StepManiaScoreKeeper.formatPercent(gameplay.percent)}"
                },
            )
            IconButton(onClick = { onEvent(GlyphEvent.TogglePause) }) {
                Icon(
                    imageVector = if (gameplay.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (gameplay.isPaused) "Resume" else "Pause",
                    tint = GlyphTheme.Paper,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid),
        ) {
            Text(text = "${gameplay.bpm.toInt()} BPM", style = fonts.label, color = GlyphTheme.Muted)
            Text(text = gameplay.sectionLabel, style = fonts.label, color = GlyphTheme.Muted)
            // The scroll mode is the first thing a player checks when a chart
            // reads wrong, so it stays on screen rather than two taps away.
            Text(text = gameplay.modifiers.scrollMode.label, style = fonts.label, color = GlyphTheme.Muted)
            modifierSummary(gameplay.modifiers).takeIf { it.isNotEmpty() }?.let { summary ->
                // A run at another rate or judge says so while it is played.
                Text(
                    text = summary,
                    style = fonts.label,
                    color = if (gameplay.modifiers.altersScoring) GlyphTheme.Warning else GlyphTheme.Muted,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        GlyphMeter(
            fraction = gameplay.progress,
            color = GlyphTheme.Paper.copy(alpha = 0.6f),
            description = "Song progress, ${(gameplay.progress * 100).toInt()} percent",
            trackColor = GlyphTheme.InkRaised,
        )
    }
}

private fun modifierSummary(modifiers: GlyphModifiers): String = buildList {
    if (modifiers.speed != 1f) add("%.1fx rate".format(modifiers.speed))
    if (modifiers.turn != GlyphTurn.OFF) add(modifiers.turn.label)
    if (modifiers.appearance != GlyphAppearance.VISIBLE) add(modifiers.appearance.label)
    // Said while it is played: an empty receptor row otherwise looks broken.
    if (modifiers.dark) add("No receptors")
    if (modifiers.timingWindowScale != 1f) {
        add(GlyphJudge.forScale(modifiers.timingWindowScale).label)
    }
}.joinToString(" · ")

/**
 * The judgement and combo, announced: the sprite is a picture to a screen
 * reader, so without a live region a judgement would land silently.
 */
@Composable
private fun JudgementOverlay(
    judgement: GlyphJudgement?,
    early: Boolean,
    shownAtMs: Long,
    combo: Int,
    fullCombo: GlyphFullCombo?,
    fonts: GlyphStepFonts,
    modifier: Modifier = Modifier,
) {
    if (judgement == null) return
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = if (combo > 1) {
                "${stepJudgementName(judgement)}, combo $combo"
            } else {
                stepJudgementName(judgement)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        GlyphJudgementFx(
            judgement = judgement,
            early = early,
            shownAtMs = shownAtMs,
            combo = combo,
            fullCombo = fullCombo,
            fonts = fonts,
        )
    }
}

/**
 * READY, then GO!, while the music waits: StepMania's own way into a song.
 * Flat: READY fades in and out, GO! lands from a little larger. Nothing tilts.
 */
@Composable
private fun ReadyGo(startedAtMs: Long, fonts: GlyphStepFonts, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) withFrameMillis { now = System.currentTimeMillis() }
    }
    val elapsed = (now - startedAtMs).coerceAtLeast(0L)
    val ready = elapsed < READY_MILLIS
    val local = if (ready) elapsed / READY_MILLIS.toFloat() else (elapsed - READY_MILLIS) / GO_MILLIS.toFloat()
    val fade = when {
        local < 0.15f -> local / 0.15f
        local > 0.8f -> ((1f - local) / 0.2f).coerceAtLeast(0f)
        else -> 1f
    }
    val zoom = if (ready) 1f else 1f + 0.35f * (1f - (local / 0.25f).coerceIn(0f, 1f))
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = if (ready) "Ready" else "Go"
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (ready) "READY" else "GO!",
            style = fonts.banner,
            color = if (ready) GlyphTheme.Paper else Color(0xFFFFD43B),
            modifier = Modifier.graphicsLayer {
                alpha = fade
                scaleX = zoom
                scaleY = zoom
            },
        )
    }
}

private const val READY_MILLIS = 1_000L
private const val GO_MILLIS = 800L

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

/**
 * Pause: the practice controls, and the two ways out.
 *
 * A menu, so it is the app's: the theme's page colour drawn over the stopped
 * playfield and a pane of the app's glass on it. The veil is the page colour
 * rather than black because the glass works out its ink against the page
 * ([GlassPanel] via `GlassInkScope`), and over a black veil in a light theme
 * it would pick dark text for a dark pane. It is nearly opaque, as the old one
 * was: the notes stopped mid-lane under it are noise, not information.
 *
 * The veil takes every touch the panel does not. The lanes are under it, and a
 * thumb resting on the screen while paused must not press one. It is the
 * panel's bottom sibling rather than its ancestor, for the reason `GlassPanel`
 * gives: an ancestor consuming every event broke the scrolls inside it.
 */
@Composable
private fun PauseOverlay(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        GlassPanel(
            // Nothing behind it worth frosting: the playfield is not a haze
            // source (recording it into a layer every frame of play would cost
            // more than the pause is worth), and the veil is plain colour.
            hazeState = null,
            glass = LocalMiniPlayerGlass.current,
            avoidNavigationBar = false,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .widthIn(max = 480.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(MonoDimens.spacingLg),
            ) {
                Text(
                    text = "Paused",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(MonoDimens.spacingLg))

                GlyphScrollControl(
                    mode = state.gameplay.modifiers.scrollMode,
                    onFamily = { onEvent(GlyphEvent.SetScrollFamily(it)) },
                    onValue = { onEvent(GlyphEvent.SetScrollValue(it)) },
                )

                Spacer(Modifier.height(MonoDimens.spacingLg))

                GlyphSkinControl(
                    skin = state.gameplay.modifiers.skin,
                    onSelect = { onEvent(GlyphEvent.SetSkin(it)) },
                )

                Spacer(Modifier.height(MonoDimens.spacingLg))

                GlyphSpeedControl(
                    speed = state.gameplay.modifiers.speed,
                    pitchLinked = state.gameplay.modifiers.pitchLinkedToSpeed,
                    onSpeedChange = { onEvent(GlyphEvent.SetSpeed(it)) },
                    onPitchLinkChange = { onEvent(GlyphEvent.SetPitchLinked(it)) },
                )

                Spacer(Modifier.height(MonoDimens.spacingLg))
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = "Resume",
                        icon = Icons.Default.PlayArrow,
                        primary = true,
                        onClick = { onEvent(GlyphEvent.TogglePause) },
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(
                        text = "Restart",
                        icon = Icons.Default.Replay,
                        onClick = { onEvent(GlyphEvent.Restart) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphButton(
                    text = "Quit to songs",
                    onClick = { onEvent(GlyphEvent.Quit) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
