// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
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
    /** The player's colour settings, for the player's background behind the play. */
    backdropLook: GlyphBackdropLook = GlyphBackdropLook(),
    explosionProvider: () -> LaneFlashes = { NO_FLASHES },
    comboBurstProvider: () -> Long = { 0L },
    comboProvider: () -> Int = { 0 },
    /** A shared dance by file name, for an imported character dancing behind the field. */
    sharedDance: (String) -> java.io.File? = { null },
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val fonts = rememberStepFonts()
    val context = LocalContext.current
    // Every judgement sprite decoded before the first step, so the first
    // FLAWLESS is never drawn a frame late.
    LaunchedEffect(Unit) { GlyphStepArt.preloadJudgements(context) }
    val gameplay = state.gameplay
    val landscape = glyphIsLandscape()
    val fieldFraction = glyphFieldFraction(gameplay.modifiers.landscapeFieldWidth)
    // A chart that failed to load still has to draw something rather than
    // crash; a steady map is the harmless stand-in.
    val timing = state.simfile?.timing ?: remember { GlyphTiming.constant(120f) }
    val keyboard = rememberGlyphKeyboardFocus()
    val reduced = gameplay.modifiers.reducedMotion

    // The way in: while READY shows, the HUD drops in, the field rises into
    // place and the life bar fills to its start. Set at once without an intro
    // (a restart, Training Ground) and with reduced motion.
    // Starts hidden when an intro is coming, so the HUD never shows for a
    // frame before the effect hides it.
    val intro = remember { Animatable(if (gameplay.introStartedAtMs > 0L && !reduced) 0f else 1f) }
    LaunchedEffect(gameplay.introStartedAtMs) {
        if (gameplay.introStartedAtMs > 0L && !reduced) {
            intro.snapTo(0f)
            intro.animateTo(1f, tween(INTRO_IN_MILLIS, easing = FastOutSlowInEasing))
        } else {
            intro.snapTo(1f)
        }
    }
    // The fail: the screen shakes as the bar empties, hardest at first.
    val shake = remember { Animatable(1f) }
    LaunchedEffect(gameplay.failedAtMs) {
        if (gameplay.failedAtMs > 0L && !reduced) {
            shake.snapTo(0f)
            shake.animateTo(1f, tween(SHAKE_MILLIS, easing = LinearEasing))
        } else {
            shake.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink)
            .glyphKeyboard(
                keys = state.keys,
                lanes = !gameplay.isPaused,
                onPress = { lane, at -> onEvent(GlyphEvent.LanePressed(lane, at)) },
                onRelease = { lane, at -> onEvent(GlyphEvent.LaneReleased(lane, at)) },
                onPause = { onEvent(GlyphEvent.TogglePause) },
                focus = keyboard,
            ),
    ) {
        // The game through the TV: the playfield, the judgements and the HUD.
        // The pause menu is drawn over it, outside the shader, in the app's own
        // glass; a menu is never seen through the TV.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glyphTvShader(gameplay.modifiers.graphics),
        ) {
            // The whole game picture, which a fail shakes. FAILED, over it,
            // holds still: a word that shook with it would be hard to read.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = shake.value
                        if (t < 1f) {
                            val decay = (1f - t) * (1f - t)
                            val amplitude = SHAKE_AMPLITUDE.toPx() * decay
                            translationX = amplitude * sin(t * SHAKE_X_TURNS * TAU)
                            translationY = amplitude * SHAKE_Y_SHARE * sin(t * SHAKE_Y_TURNS * TAU + 1f)
                            rotationZ = SHAKE_TILT_DEGREES * decay * sin(t * SHAKE_TILT_TURNS * TAU)
                        } else {
                            translationX = 0f
                            translationY = 0f
                            rotationZ = 0f
                        }
                    },
            ) {
                // The player's background, under everything and across the
                // whole screen, the field's column included: seen through the
                // TV with the rest, and still, so the arrows are what moves.
                // An imported StepMania theme's own gameplay background, dimmed as
                // StepMania dims a song's, so the arrows stay what reads; the
                // player's album wash otherwise.
                val themeBackground = rememberStepThemeArt(tf.monochrome.android.glyph.style.StepManiaStyleBaker.BACKGROUND)
                if (themeBackground != null) {
                    androidx.compose.foundation.Image(
                        bitmap = themeBackground,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = THEME_BACKGROUND_DIM)))
                } else {
                    GlyphPlayerBackground(
                        coverUri = gameplayCoverUri(state.selectedSong),
                        albumColored = backdropLook.albumColored,
                        colorTransitionMs = backdropLook.colorTransitionMs,
                    )
                }

                // An imported StepMania character dancing behind the field, drawn
                // off the field's frame (GlyphCharacterRenderer): one picture here.
                state.themes.characterDir?.let { dir ->
                    val firstNoteBeat = remember(state.chart) { state.chart?.notes?.minOfOrNull { it.beat } ?: 0f }
                    GlyphDancer(
                        characterDir = dir,
                        sharedDance = sharedDance,
                        songBeat = { timing.secondsToBeat(positionProvider()) },
                        firstNoteBeat = firstNoteBeat,
                        failed = { state.gameplay.failedAtMs > 0L },
                        paused = { state.gameplay.isPaused },
                    )
                }

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
                    // Paused, the field stops redrawing once its flashes have
                    // faded; a room's song plays on under its pause panel.
                    running = gameplay.isPlaying,
                    // Held sideways, a centred column of the setting's width, its
                    // receptors nearer the bottom: thumbs come from the sides.
                    receptorInset = if (landscape && !gameplay.modifiers.reverse) LANDSCAPE_RECEPTOR_INSET else null,
                    modifier = Modifier
                        .fillMaxSize()
                        .glyphFieldColumn(fieldFraction)
                        // Rises into place during READY.
                        .graphicsLayer {
                            alpha = intro.value
                            translationY = (1f - intro.value) * INTRO_RISE.toPx()
                        },
                )

                // The lane input sits over the playfield rather than under a row of
                // buttons: the whole lane is the target, which is what makes the mode
                // playable with thumbs.
                GlyphLaneInput(
                    onPress = { lane, at -> onEvent(GlyphEvent.LanePressed(lane, at)) },
                    onRelease = { lane, at -> onEvent(GlyphEvent.LaneReleased(lane, at)) },
                    hitboxScale = gameplay.modifiers.hitboxScale,
                    fieldFraction = fieldFraction,
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
                    reducedMotion = reduced,
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
                        )
                        // Drops in from the top during READY.
                        .graphicsLayer {
                            alpha = intro.value
                            translationY = -(1f - intro.value) * INTRO_DROP.toPx()
                        },
                ) {
                    GameplayHud(
                        state = state,
                        typography = typography,
                        fonts = fonts,
                        onEvent = onEvent,
                        // The bar fills to its start as the run comes in. Read
                        // by the bar as it draws: read here, it recomposed this
                        // whole screen on every frame of the intro.
                        lifeScale = { intro.value },
                        reducedMotion = reduced,
                    )
                }

                // A room's run: everyone's score as it stands, down the left
                // as osu! has it, where the arrows are in flight rather than
                // at the receptors. Fewer cards upright, where the lanes take
                // the whole width; more in landscape, where they leave room.
                if (state.inRoomPlay) {
                    RoomLeaderboard(
                        rows = roomBoardRows(
                            players = state.multiplayer.opponents,
                            myId = state.multiplayer.session.myId,
                            maxRows = if (glyphIsLandscape()) BOARD_ROWS_LANDSCAPE else BOARD_ROWS_PORTRAIT,
                        ),
                        fonts = fonts,
                        reducedMotion = reduced,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(start = GlyphTheme.Grid)
                            .graphicsLayer { alpha = intro.value },
                    )
                }
            }

            if (gameplay.failStops && gameplay.failedAtMs > 0L) {
                FailedOverlay(
                    fonts = fonts,
                    reducedMotion = reduced,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
    /** How much of the run's life the bar draws: less while it fills at the start. Read while drawing. */
    lifeScale: () -> Float = { 1f },
    reducedMotion: Boolean = false,
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
                scale = lifeScale,
                modifier = Modifier.weight(1f),
            )
            PercentReadout(
                percent = gameplay.percent,
                style = fonts.score,
                reducedMotion = reducedMotion,
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
            // Built when the modifiers change, not on every readout of the run.
            val summary = remember(gameplay.modifiers) { modifierSummary(gameplay.modifiers) }
            if (summary.isNotEmpty()) {
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

/**
 * The run's percent, rolling to each new value rather than jumping to it.
 *
 * Its own scope, so the roll recomposes the number and nothing else: read in
 * the HUD, every frame of every roll rebuilt the HUD, which in dense play is
 * every frame. The text is made only when the hundredths it shows change.
 * Its spoken label is the real percent, not the rolling one.
 */
@Composable
private fun PercentReadout(percent: Float, style: androidx.compose.ui.text.TextStyle, reducedMotion: Boolean) {
    val rolled = animateFloatAsState(
        targetValue = percent,
        animationSpec = if (reducedMotion) snap() else tween(SCORE_ROLL_MILLIS, easing = FastOutSlowInEasing),
        label = "score roll",
    )
    val shown by remember { derivedStateOf { StepManiaScoreKeeper.formatPercent(rolled.value) } }
    Text(
        text = shown,
        style = style,
        color = GlyphTheme.Paper,
        modifier = Modifier.semantics {
            contentDescription = "Score ${StepManiaScoreKeeper.formatPercent(percent)}"
        },
    )
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
    reducedMotion: Boolean = false,
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
            reducedMotion = reducedMotion,
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

/** The way in: how long the HUD and the field take, and how far they travel. */
private const val INTRO_IN_MILLIS = 900
private val INTRO_DROP = 48.dp
private val INTRO_RISE = 40.dp

/** How long the percent takes to roll to a new value. */
private const val SCORE_ROLL_MILLIS = 220

/** The fail shake: how long, how far, and how many swings each way. */
private const val SHAKE_MILLIS = 650
private val SHAKE_AMPLITUDE = 22.dp
private const val SHAKE_X_TURNS = 9f
private const val SHAKE_Y_TURNS = 7f
private const val SHAKE_Y_SHARE = 0.4f
private const val SHAKE_TILT_TURNS = 5f
private const val SHAKE_TILT_DEGREES = 1.4f
private const val TAU = (2.0 * PI).toFloat()

/** FAILED lands once the shake is under way. */
private const val FAILED_LANDS_AFTER_MILLIS = 180L
private val FAILED_RED = Color(0xFFFF3B4A)

/**
 * A run that has failed and stopped: the stopped field sinks under a dark red
 * veil, and FAILED slams down onto it from large, springing as it lands. The
 * results follow on their own ([GlyphViewModel.FAIL_HOLD_MILLIS]). Reduced
 * motion shows both at once.
 */
@Composable
private fun FailedOverlay(fonts: GlyphStepFonts, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val veil = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val slam = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reducedMotion) return@LaunchedEffect
        launch { veil.animateTo(1f, tween(VEIL_MILLIS)) }
        delay(FAILED_LANDS_AFTER_MILLIS)
        slam.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
    }
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = "Failed"
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = veil.value }
                .background(Brush.verticalGradient(listOf(Color(0xB31A0006), Color(0xD9050008)))),
        )
        Text(
            text = "FAILED",
            style = fonts.banner.copy(shadow = Shadow(Color.Black, Offset(0f, 6f), 18f)),
            color = FAILED_RED,
            modifier = Modifier.graphicsLayer {
                val s = slam.value
                alpha = s.coerceIn(0f, 1f)
                // From more than twice its size down to it, and a little past.
                val scale = 2.4f - 1.4f * s
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

private const val VEIL_MILLIS = 450

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
                    // A room's song cannot be started again by one phone.
                    if (!state.inRoomPlay) {
                        GlyphButton(
                            text = "Restart",
                            icon = Icons.Default.Replay,
                            onClick = { onEvent(GlyphEvent.Restart) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphButton(
                    text = if (state.inRoomPlay) stringResource(R.string.tm_mp_quit_to_room) else "Quit to songs",
                    onClick = { onEvent(GlyphEvent.Quit) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Room board cards: all of a room's first places upright would sit on the lanes. */
private const val BOARD_ROWS_PORTRAIT = 5
private const val BOARD_ROWS_LANDSCAPE = 8

/** How far an imported theme's gameplay background is darkened under the field. */
private const val THEME_BACKGROUND_DIM = 0.45f

/**
 * The character behind the field: its renderer's latest picture, drawn once
 * a frame, scaled up smooth, in the lower middle of the screen as StepMania's
 * dancers stand. The renderer runs while this is on screen and stops with it.
 * The song's beat is read here, on the main thread with the field, once a
 * frame, and handed to the renderer's thread.
 */
@Composable
private fun GlyphDancer(
    characterDir: java.io.File,
    sharedDance: (String) -> java.io.File?,
    songBeat: () -> Float,
    firstNoteBeat: Float,
    failed: () -> Boolean,
    paused: () -> Boolean,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val beatNow = remember { java.util.concurrent.atomic.AtomicReference(0f) }
    val currentSongBeat by androidx.compose.runtime.rememberUpdatedState(songBeat)
    val currentFirstNote by androidx.compose.runtime.rememberUpdatedState(firstNoteBeat)
    val currentFailed by androidx.compose.runtime.rememberUpdatedState(failed)
    val currentPaused by androidx.compose.runtime.rememberUpdatedState(paused)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            beatNow.set(currentSongBeat())
        }
    }
    val renderer = remember(characterDir) {
        tf.monochrome.android.glyph.style.GlyphCharacterRenderer(
            characterDir = characterDir,
            sharedDance = sharedDance,
            scope = scope,
            beat = { beatNow.get() },
            firstNoteBeat = { currentFirstNote },
            failed = { currentFailed() },
            paused = { currentPaused() },
        )
    }
    androidx.compose.runtime.DisposableEffect(renderer) {
        renderer.start()
        onDispose { renderer.stop() }
    }
    val frame by renderer.frame.collectAsState()
    val picture = frame ?: return
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val height = size.height * DANCER_HEIGHT_SHARE
        val width = height * picture.width / picture.height
        drawImage(
            image = picture.asImageBitmap(),
            dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - width) / 2).toInt(), (size.height * DANCER_TOP_SHARE).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(width.toInt(), height.toInt()),
            alpha = DANCER_ALPHA,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
        )
    }
}

/** How much of the screen's height the dancer stands, from where, and how strongly it shows under the notes. */
private const val DANCER_HEIGHT_SHARE = 0.62f
private const val DANCER_TOP_SHARE = 0.3f
private const val DANCER_ALPHA = 0.85f
