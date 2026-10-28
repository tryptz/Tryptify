// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphFieldArt
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.asset.GlyphSpecialNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphVisibleFrame
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass

/**
 * The four-lane playfield.
 *
 * One `Canvas`, one draw pass, no child composables per note. That is the whole
 * performance strategy and it is not an optimisation so much as the only shape
 * that works: a Challenge chart puts a few hundred notes on screen at once, and
 * a composable per note would mean a few hundred layout nodes being created and
 * destroyed every frame.
 *
 * Inside the draw scope this does exactly four kinds of work — read the clock,
 * ask the engine which notes overlap the window, compute a y position, and
 * `drawImage` a bitmap that was rasterized before the song started. It never
 * opens a file, parses anything, allocates a bitmap, or measures text.
 *
 * Draw order is fixed and matters: lanes, then hold bodies, then hold heads and
 * taps, then receptors, then explosions. Receptors above notes so a note
 * arriving reads as passing *under* the target; explosions above receptors so
 * feedback is never hidden by the thing that produced it.
 */
@Composable
fun GlyphPlayfield(
    engine: GlyphGameplayEngine?,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    positionProvider: () -> Float,
    heldLanes: Set<GlyphLane>,
    /** How the chart scrolls. Decides what on-screen distance means. */
    scrollMode: GlyphScrollMode,
    /** The song's timing map, which XMod and MMod read and CMod ignores. */
    timing: GlyphTiming,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** Which artwork to draw; glass skins take the UI-panel glass. */
    skin: GlyphSkin = GlyphSkin.DEFAULT,
    /** A previous run's timing, drawn as markers beside the receptors. */
    ghost: GlyphGhost? = null,
    /**
     * The most recent judgement per lane, for the receptor explosion. Read
     * inside the draw scope, so it is a provider rather than a value.
     */
    explosionProvider: () -> LaneFlashes = { NO_FLASHES },
    /** When the last combo milestone was reached, for the burst. */
    comboBurstProvider: () -> Long = { 0L },
    /** The live combo, which colours the hit rays. */
    comboProvider: () -> Int = { 0 },
    /** StepMania's look options: reverse, appearance, dark, mini. */
    look: GlyphFieldLook = GlyphFieldLook(),
    /**
     * A field that is not the game: the options preview, the menu's
     * backdrop. It warms its own size without dropping the game's, and
     * draws nothing for a note whose art is not warm rather than a square.
     */
    preview: Boolean = false,
    /** Where the receptor row sits from its edge, when not the game's own. */
    receptorInset: Dp? = null,
    /** Notes this much of their usual size, for a small field. */
    noteScale: Float = 1f,
    /**
     * Whether the song is moving: false while a run is paused. Its frames stop
     * once the last flash and burst have faded, and start again with it. A
     * room's run is moving under its pause panel, so it passes true then.
     */
    running: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Lane width is snapped to whole pixels and every note is drawn at that
        // exact size. A fractional lane width would put each lane on a
        // different sub-pixel phase and the arrows would shimmer as they moved.
        val laneWidthPx = (widthPx / GlyphLane.entries.size).toInt().coerceAtLeast(1)
        // Mini shrinks the notes, as StepMania's does: 100% would be half size.
        val noteSizePx = (laneWidthPx * NOTE_SCALE * noteScale * (1f - look.mini / 2f)).toInt().coerceAtLeast(1)
        // Reverse puts the receptors at the top, clear of the HUD, and the
        // notes rise to them: StepMania's own default way round.
        val receptorYPx = if (look.reverse) {
            with(density) { (receptorInset ?: RECEPTOR_TOP_INSET).toPx() } + noteSizePx / 2f
        } else {
            heightPx - with(density) { (receptorInset ?: RECEPTOR_INSET).toPx() } - noteSizePx / 2f
        }
        // How far a note travels on screen, for the appearance mods.
        val fieldLengthPx = (if (look.reverse) heightPx - receptorYPx else receptorYPx).coerceAtLeast(1f)
        // Upward distance on screen is subtracted; reversed, it is added.
        val direction = if (look.reverse) -1f else 1f

        // Warm the exact pixel size this layout needs, for the note divisions
        // the chart uses. Re-runs on rotation or a window resize, a skin or
        // glass change and a chart with other divisions, never for a new
        // engine of the same chart: a restart, or the next run of the song,
        // draws the art it already has. The field's art is then read from
        // [GlyphFieldArt], by place, never by name.
        var warmArt by remember { mutableStateOf<GlyphFieldArt?>(null) }
        // The UI-panel glass, which the glass skin is baked from: moving those
        // sliders re-warms the notes in the new glass.
        val panelGlass = LocalMiniPlayerGlass.current
        val divisions = engine?.chart?.divisionsUsed
        val effects = !preview || look.graphics.explosions || look.graphics.comboBurst
        LaunchedEffect(divisions, noteSizePx, skin, panelGlass, effects) {
            warmArt = null
            if (divisions == null || noteSizePx <= 1) return@LaunchedEffect
            assets.configure(skin, panelGlass)
            // A preview leaves the game's rasters alone: it can be on screen
            // beside the game for the length of a cross-fade. The game keeps
            // its own size and drops the rest, so stale sizes cannot pile up.
            if (!preview) assets.releaseImagesExcept(noteSizePx)
            assets.prewarm(
                ids = GlyphAssetCatalog.playfieldAssets(divisions),
                widthPx = noteSizePx,
                heightPx = noteSizePx,
            )
            // The explosion is drawn larger than a note — the pack's effects
            // are 128-unit artwork with deliberate padding — so it needs its
            // own warm size. Warming it at the note size and scaling up would
            // throw away the resolution the pack exists to provide.
            if (effects) {
                assets.prewarm(
                    ids = GlyphAssetCatalog.playfieldEffects(),
                    widthPx = noteSizePx * 2,
                    heightPx = noteSizePx * 2,
                )
            }
            warmArt = GlyphFieldArt.from(assets, noteSizePx)
        }

        // The frame clock drives *redrawing* only. What is drawn comes from the
        // audio clock through positionProvider, so a dropped frame costs a
        // frame of animation and never a judgement.
        val frameTick = rememberFrameTick(
            enabled = engine != null,
            running = running,
            lastFlashNanos = { maxOf(explosionProvider().newestNanos(), comboBurstProvider()) },
        )

        // Filled by the engine every frame and kept between frames, so drawing
        // allocates nothing per note.
        val frame = remember { GlyphVisibleFrame() }

        // Hit flashes: the god-ray burst over the receptors, and the light it
        // throws on every glyph around it.
        val raysOn = rememberHitRaysAvailable(reducedMotion) && look.graphics.hitRays
        val hitLight = remember { GlyphHitLight() }

        val laneNames = remember {
            GlyphLane.entries.joinToString(", ") { it.label }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        "Playfield with four lanes: $laneNames. " +
                            if (look.reverse) {
                                "Notes rise toward the receptors at the top."
                            } else {
                                "Notes fall toward the receptors at the bottom."
                            }
                },
        ) {
            // Reading the tick inside the draw scope is what schedules the next
            // frame without recomposing anything.
            @Suppress("UNUSED_EXPRESSION") frameTick.value

            if (!look.dark) drawReceptorLine(receptorYPx)

            val running = engine ?: return@Canvas
            val art = warmArt ?: return@Canvas

            val position = positionProvider()
            if (raysOn && look.graphics.noteLighting) {
                val now = System.nanoTime()
                hitLight.tint(GlyphComboRays.colour(comboProvider(), now))
                hitLight.update(now, explosionProvider(), laneWidthPx, noteSizePx, receptorYPx)
            } else {
                hitLight.clear()
            }

            // Culling window. Taken at the chart's slowest tempo so it never
            // under-covers: a tempo change inside the window would otherwise
            // let a note that should be on screen be culled before it is drawn.
            val lookahead = scrollMode
                .visibleSeconds(timing.bpmRange.start)
                .coerceIn(MIN_LOOKAHEAD_SECONDS, MAX_LOOKAHEAD_SECONDS)
            running.fillVisible(
                fromSeconds = position - PAST_WINDOW_SECONDS,
                toSeconds = position + lookahead,
                positionSeconds = position,
                scrollMode = scrollMode,
                frame = frame,
            )
            val notes = running.chart.notes

            // One unit is a beat's worth of gap at 1×; the mode decides how many
            // units lie between two moments, and this turns units into pixels.
            val pixelsPerUnit = fieldLengthPx / GlyphScrollMode.UNITS_ON_SCREEN

            // Pass one: hold and roll bodies, underneath everything. Their
            // ends arrive already placed by the engine.
            var holdingMask = 0
            for (i in 0 until frame.count) {
                val note = notes[frame.noteIndex(i)]
                if (!note.type.isHoldLike) continue
                if (frame.isHeld(i)) holdingMask = holdingMask or (1 shl note.lane.ordinal)
                // Laid out as if falling and, reversed, flipped about the
                // receptor line: the body and its end cap then hang the
                // right way round, as StepMania's do in reverse.
                val isRoll = note.type == GlyphNoteType.ROLL
                val active = frame.isHeld(i)
                val body = art.holdBody(isRoll, active) ?: continue
                withTransform({ if (look.reverse) scale(1f, -1f, Offset(0f, receptorYPx)) }) {
                    drawHoldBody(
                        body = body,
                        tail = art.holdTail(isRoll, active),
                        lane = note.lane.ordinal,
                        headY = receptorYPx - frame.bodyHeadUnits(i) * pixelsPerUnit,
                        tailY = receptorYPx - frame.tailUnits(i) * pixelsPerUnit,
                        laneWidthPx = laneWidthPx,
                        noteSizePx = noteSizePx,
                        light = hitLight,
                        appearance = look.appearance,
                        receptorYPx = receptorYPx,
                        fieldLengthPx = fieldLengthPx,
                    )
                }
            }

            // Hold glow, under the receptors: a lane that is actively holding
            // gets a sustained mark, which is the only feedback distinguishing
            // "holding correctly" from "the head was hit and then nothing".
            if (!reducedMotion) {
                for (lane in 0 until LANE_COUNT) {
                    if (holdingMask and (1 shl lane) == 0) continue
                    val glow = art.holdGlow(lane) ?: continue
                    drawImage(
                        image = glow,
                        dstOffset = IntOffset(
                            (lane * laneWidthPx + (laneWidthPx - noteSizePx * 2) / 2f)
                                .roundToInt(),
                            (receptorYPx - noteSizePx).roundToInt(),
                        ),
                        dstSize = IntSize(noteSizePx * 2, noteSizePx * 2),
                        alpha = 0.7f,
                    )
                }
            }

            // Pass two: receptors, above the bodies so a hold reads as passing
            // through the target rather than over it. Dark takes them away.
            // They flash on each beat, as StepMania's receptors do: the same
            // glyph added over itself, brightest on the beat and gone by the
            // next. A flash, not a pulse in size.
            val beatFlash = if (reducedMotion || !look.graphics.beatFlash) 0f else {
                val beat = timing.secondsToBeat(position)
                val fade = 1f - (beat - kotlin.math.floor(beat))
                BEAT_FLASH * fade * fade * fade
            }
            if (!look.dark) for (lane in LANES) {
                val image = art.receptor(lane.ordinal, active = lane in heldLanes) ?: continue
                val x = lane.ordinal * laneWidthPx + (laneWidthPx - noteSizePx) / 2f
                val y = receptorYPx - noteSizePx / 2f
                drawSnapped(image = image, x = x, y = y, size = noteSizePx, light = hitLight)
                if (beatFlash > LIT_THRESHOLD) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                        dstSize = IntSize(noteSizePx, noteSizePx),
                        alpha = beatFlash,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            // Ghost markers, between the receptors and the notes. Drawn as a
            // thin bar per past judgement rather than as a second set of
            // arrows: two overlapping note streams are unreadable, and what the
            // player needs from a ghost is where the *timing* went, not a
            // replay of the chart they can already see.
            if (ghost != null && ghost.isConsistent) {
                drawGhostMarkers(
                    ghost = ghost,
                    position = position,
                    lookahead = lookahead,
                    scrollMode = scrollMode,
                    timing = timing,
                    pixelsPerUnit = pixelsPerUnit * direction,
                    receptorYPx = receptorYPx,
                    laneWidthPx = laneWidthPx,
                )
            }

            // Explosions, above the receptors: feedback must never be hidden
            // by the thing that produced it. Skipped entirely under reduced
            // motion, which is also the most expensive drawing on screen
            // during a dense chart, and when the graphics settings say so.
            if (!reducedMotion && look.graphics.explosions) {
                val now = System.nanoTime()
                val flashes = explosionProvider()
                for (lane in 0 until LANE_COUNT) {
                    if (!flashes.has(lane)) continue
                    val age = (now - flashes.atNanos(lane)) / 1_000_000_000f
                    if (age < 0f || age > EXPLOSION_SECONDS) continue
                    val isHit = flashes.isHit(lane)
                    // A miss streams red rays where there are rays to give
                    // (GlyphHitRays), and the crack is only their stand-in.
                    if (!isHit && raysOn) continue
                    val image = (if (isHit) art.tapExplosion(lane) else art.missCrack) ?: continue
                    drawExplosion(
                        image = image,
                        laneOrdinal = lane,
                        laneWidthPx = laneWidthPx,
                        sizePx = noteSizePx * 2,
                        receptorYPx = receptorYPx,
                        progress = age / EXPLOSION_SECONDS,
                    )
                }
            }

            // Combo burst: one mark across the playfield at a milestone, not
            // one per note. A burst on every hit would be constant during a
            // stream and would stop reading as an event at all.
            if (!reducedMotion && look.graphics.comboBurst) {
                val burstAt = comboBurstProvider()
                if (burstAt > 0L) {
                    val age = (System.nanoTime() - burstAt) / 1_000_000_000f
                    if (age in 0f..COMBO_BURST_SECONDS) {
                        val burst = art.comboBurst
                        if (burst != null) {
                            val eased = age / COMBO_BURST_SECONDS
                            val drawn = (noteSizePx * 2 * (1f + eased * 0.6f)).toInt()
                            drawImage(
                                image = burst,
                                dstOffset = IntOffset(
                                    ((size.width - drawn) / 2f).roundToInt(),
                                    (receptorYPx - drawn / 2f).roundToInt(),
                                ),
                                dstSize = IntSize(drawn, drawn),
                                alpha = (1f - eased).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }

            // Pass three: heads and taps, above the receptors. A hit hold's
            // head rides the top of its body, as StepMania's does: held, it
            // sits on the receptor and covers the body's cut end; let go, it
            // carries on past with the body. Hidden, the body's raw end
            // showed under the receptor for the whole hold.
            for (i in 0 until frame.count) {
                val note = notes[frame.noteIndex(i)]
                val units = if (note.type.isHoldLike && frame.isHeadJudged(i)) {
                    frame.bodyHeadUnits(i)
                } else {
                    frame.headUnits(i)
                }

                val travelled = units * pixelsPerUnit
                val y = receptorYPx - travelled * direction
                if (y < -noteSizePx || y > size.height + noteSizePx) continue
                // Hidden, Sudden and Stealth: how much of the note shows at
                // this distance from the receptors. It is judged either way.
                val shown = look.appearance.alphaAt(travelled / fieldLengthPx)
                if (shown <= 0f) continue

                val image = noteArt(art, note.type, note.lane.ordinal, note.division)
                val x = note.lane.ordinal * laneWidthPx + (laneWidthPx - noteSizePx) / 2f

                if (image != null) {
                    drawSnapped(image, x, y - noteSizePx / 2f, noteSizePx, hitLight, shown)
                } else if (!preview) {
                    // A missing asset costs its artwork, never the note: a
                    // filled square still tells the player where and when.
                    drawFallbackNote(
                        x = x,
                        y = y - noteSizePx / 2f,
                        sizePx = noteSizePx.toFloat(),
                        color = GlyphTheme.beatColor(note.division, palette),
                    )
                }
            }
        }

        // The god-ray burst over the receptors, on top of everything it lights.
        GlyphHitRays(
            art = warmArt,
            laneWidthPx = laneWidthPx,
            noteSizePx = noteSizePx,
            receptorYPx = receptorYPx,
            explosionProvider = explosionProvider,
            frameTick = frameTick,
            enabled = raysOn && engine != null && warmArt != null,
            comboProvider = comboProvider,
            quality = look.graphics.rayQuality,
        )
    }
}

/**
 * Each lane's most recent judgement, for the receptor flash: four fixed slots,
 * written as notes are judged and read while drawing. A map of them handed a
 * new iterator and an entry per lane to every loop of every frame.
 *
 * Carries the wall-clock instant rather than a song position: the explosion is
 * decoration and should fade in real time even while the song is paused, unlike
 * everything else on the playfield. Main thread, as the tick and the draw are.
 */
class LaneFlashes {
    private val at = LongArray(LANE_COUNT)
    private val hit = BooleanArray(LANE_COUNT)
    private val flashed = BooleanArray(LANE_COUNT)

    fun set(lane: GlyphLane, atNanos: Long, isHit: Boolean) {
        at[lane.ordinal] = atNanos
        hit[lane.ordinal] = isHit
        flashed[lane.ordinal] = true
    }

    fun clear() {
        flashed.fill(false)
    }

    /** Whether lane [lane] (an ordinal) has flashed since the last [clear]. */
    fun has(lane: Int): Boolean = flashed[lane]

    fun atNanos(lane: Int): Long = at[lane]

    fun isHit(lane: Int): Boolean = hit[lane]

    /** When the newest flash was, on the System.nanoTime clock; 0 with none. */
    fun newestNanos(): Long {
        var newest = 0L
        for (lane in 0 until LANE_COUNT) if (flashed[lane] && at[lane] > newest) newest = at[lane]
        return newest
    }
}

/** Ahead of [NO_FLASHES]: a file's values start in order, and its slots are sized by this. */
internal val LANE_COUNT = GlyphLane.entries.size

/** The lanes as an array: a loop over it allocates no iterator, frame after frame. */
private val LANES = GlyphLane.entries.toTypedArray()

/** No flashes, for a field nothing judges. Never written. */
internal val NO_FLASHES = LaneFlashes()

/** Fades and grows slightly over its life. Cheap: one alpha, one size. */
private fun DrawScope.drawExplosion(
    image: ImageBitmap,
    laneOrdinal: Int,
    laneWidthPx: Int,
    sizePx: Int,
    receptorYPx: Float,
    progress: Float,
) {
    val eased = progress.coerceIn(0f, 1f)
    val scale = 1f + eased * 0.25f
    val drawn = (sizePx * scale).toInt()
    val x = laneOrdinal * laneWidthPx + (laneWidthPx - drawn) / 2f
    drawImage(
        image = image,
        dstOffset = IntOffset(x.roundToInt(), (receptorYPx - drawn / 2f).roundToInt()),
        dstSize = IntSize(drawn, drawn),
        alpha = (1f - eased).coerceIn(0f, 1f),
    )
}

private const val EXPLOSION_SECONDS = 0.28f

private const val COMBO_BURST_SECONDS = 0.55f

/**
 * A previous run's judgements, as markers on the lane.
 *
 * Each is placed at the time the note was due and offset by how far off the
 * ghost was, so a bar sitting above the receptor line means that run was late
 * there. Colour carries the judgement and position carries the error, and
 * neither is the only cue: the marker's distance from the line is readable
 * without distinguishing the colours at all.
 */
private fun DrawScope.drawGhostMarkers(
    ghost: GlyphGhost,
    position: Float,
    lookahead: Float,
    scrollMode: GlyphScrollMode,
    timing: GlyphTiming,
    pixelsPerUnit: Float,
    receptorYPx: Float,
    laneWidthPx: Int,
) {
    val fromMs = ((position - PAST_WINDOW_SECONDS) * 1000f).toInt()
    val toMs = ((position + lookahead) * 1000f).toInt()

    // Walked in place rather than through GlyphGhost.between, which handed
    // back a new list of boxed indices every frame. The same notes: times
    // ascend, so the walk stops at the first past the window.
    val times = ghost.timesMs
    for (index in times.indices) {
        val time = times[index]
        if (time >= toMs) break
        if (time < fromMs) continue
        val noteSeconds = time / 1000f
        val offsetSeconds = ghost.offsetsMs[index] / 1000f
        val lane = ghost.lanes[index]
        if (lane !in 0 until LANE_COUNT) continue

        val y = receptorYPx -
            scrollMode.scrollUnits(timing, position, noteSeconds + offsetSeconds) * pixelsPerUnit
        if (y < 0f || y > size.height) continue

        val judgement = ghost.judgementAt(index)
        val left = lane * laneWidthPx + laneWidthPx * 0.08f
        drawRect(
            color = ghostColor(judgement),
            topLeft = Offset(left, y - 1.5f),
            size = Size(laneWidthPx * 0.84f, 3f),
        )
    }
}

private fun ghostColor(judgement: tf.monochrome.android.glyph.engine.GlyphJudgement): Color =
    when (judgement) {
        tf.monochrome.android.glyph.engine.GlyphJudgement.MARVELOUS,
        tf.monochrome.android.glyph.engine.GlyphJudgement.PERFECT,
        -> GlyphTheme.Positive.copy(alpha = 0.55f)
        tf.monochrome.android.glyph.engine.GlyphJudgement.GREAT,
        tf.monochrome.android.glyph.engine.GlyphJudgement.GOOD,
        -> GlyphTheme.Warning.copy(alpha = 0.5f)
        else -> GlyphTheme.Negative.copy(alpha = 0.5f)
    }

/**
 * A frame ticker that redraws without recomposing.
 *
 * Returns a `State<Long>` read inside a draw scope: writing it invalidates the
 * draw, not the composition, so the playfield redraws at the display's rate
 * while the composition stays still. Reading it in the composition instead
 * would recompose the whole tree at 165 Hz.
 *
 * While the song is not [running] (a paused run) it ticks only until the last
 * flash or burst, [lastFlashNanos] ago, has faded, then stops: the field is
 * still, and it was redrawn, through the TV shader too, at the display's rate
 * for as long as the pause lasted. Every flash fades in real time first, and
 * the ticks start again with the song.
 */
@Composable
private fun rememberFrameTick(enabled: Boolean, running: Boolean, lastFlashNanos: () -> Long): State<Long> {
    val tick = remember { mutableStateOf(0L) }
    val lastFlash by rememberUpdatedState(lastFlashNanos)
    LaunchedEffect(enabled, running) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos -> tick.value = nanos }
            if (!running && System.nanoTime() - lastFlash() > SETTLED_NANOS) break
        }
    }
    return tick
}

/** The longest anything on the field moves after its moment: the combo burst. */
private val SETTLED_NANOS = (COMBO_BURST_SECONDS * 1e9f).toLong()

/** Which artwork a note uses. Special notes ignore the beat palette. */
private fun noteArt(
    art: GlyphFieldArt,
    type: GlyphNoteType,
    lane: Int,
    division: GlyphBeatDivision,
): ImageBitmap? = when (type) {
    GlyphNoteType.MINE -> art.special(GlyphSpecialNote.MINE)
    GlyphNoteType.LIFT -> art.special(GlyphSpecialNote.LIFT)
    GlyphNoteType.FAKE -> art.special(GlyphSpecialNote.FAKE)
    GlyphNoteType.HOLD -> art.holdHead(lane, roll = false)
    GlyphNoteType.ROLL -> art.holdHead(lane, roll = true)
    GlyphNoteType.TAP -> art.tap(lane, division)
}

/**
 * Draw an image at whole physical pixels.
 *
 * `drawImage` with an integer offset and an integer size skips the filtering a
 * fractional placement would need, which is both faster and sharper — a
 * 64-pixel arrow at a 64-pixel size lands exactly on the grid it was drawn on.
 */
private fun DrawScope.drawSnapped(
    image: ImageBitmap,
    x: Float,
    y: Float,
    size: Int,
    light: GlyphHitLight? = null,
    alpha: Float = 1f,
) {
    drawWithLight(image, IntOffset(x.roundToInt(), y.roundToInt()), IntSize(size, size), light, alpha)
}

/**
 * [image] at [offset], and — when a hit flash nearby lights it — the same
 * glyph again over itself, in the flash's colour, added.
 */
private fun DrawScope.drawWithLight(
    image: ImageBitmap,
    offset: IntOffset,
    extent: IntSize,
    light: GlyphHitLight?,
    alpha: Float = 1f,
    /** How much of [image] is drawn, from its top-left: all of it unless cut. */
    srcSize: IntSize = IntSize(image.width, image.height),
) {
    drawImage(image = image, srcSize = srcSize, dstOffset = offset, dstSize = extent, alpha = alpha)
    if (light == null || !light.isLit) return
    val amount = light.at(offset.x + extent.width / 2f, offset.y + extent.height / 2f) * alpha
    if (amount <= LIT_THRESHOLD) return
    drawImage(
        image = image,
        srcSize = srcSize,
        dstOffset = offset,
        dstSize = extent,
        alpha = amount,
        colorFilter = light.filter,
        blendMode = BlendMode.Plus,
    )
}

/** Below this a flash's light is not worth a second draw. */
private const val LIT_THRESHOLD = 0.02f

/**
 * Tile a hold body between its head and its tail.
 *
 * Tiled at the body's own height with no gap and no scaling, because the pack's
 * body artwork is drawn to be seamless at its native proportions. Stretching a
 * single copy to the hold's length would smear it; leaving a gap between tiles
 * would show a hairline at every seam.
 */
private fun DrawScope.drawHoldBody(
    /** The body's tile: the held one (a skin may draw it brighter) while held. */
    body: ImageBitmap,
    tail: ImageBitmap?,
    lane: Int,
    /** Top of the body: the head, or the receptor line while it is held. */
    headY: Float,
    tailY: Float,
    laneWidthPx: Int,
    noteSizePx: Int,
    /** Hit flashes nearby, which light the body as they light everything. */
    light: GlyphHitLight?,
    /**
     * The appearance mods, faded by height on screen from the receptor line
     * over the field's length. Passed as values rather than a function of the
     * height: a lambda capturing them was a new object for every hold, every
     * frame.
     */
    appearance: GlyphAppearance,
    receptorYPx: Float,
    fieldLengthPx: Float,
) {
    // A held note's head stops at the receptor: the tail keeps travelling while
    // the head stays put, which is what makes holding legible. The engine
    // pins it (GlyphVisibleFrame.bodyHeadUnits).
    if (headY < tailY) return

    val x = lane * laneWidthPx + (laneWidthPx - noteSizePx) / 2f
    val left = x.roundToInt()

    // Tiles are laid from the tail down toward the head so the seam that gets
    // clipped is the one at the head, which the head artwork covers anyway.
    var y = tailY
    while (y < headY) {
        val remaining = (headY - y).roundToInt()
        if (remaining <= 0) break
        val shown = appearance.alphaAt((receptorYPx - (y + noteSizePx / 2f)) / fieldLengthPx)
        if (shown > 0f) {
            val tile = minOf(noteSizePx, remaining)
            drawWithLight(
                image = body,
                offset = IntOffset(left, y.roundToInt()),
                extent = IntSize(noteSizePx, tile),
                light = light,
                alpha = shown,
                // The short last tile is the art cut to fit, never the whole
                // tile squeezed into the gap: squeezed, it bunched up at the
                // receptor and shrank on every frame of a held note.
                srcSize = IntSize(body.width, (body.height * tile / noteSizePx).coerceIn(1, body.height)),
            )
        }
        y += noteSizePx
    }

    val tailShown = appearance.alphaAt((receptorYPx - (tailY - noteSizePx / 2f)) / fieldLengthPx)
    if (tail != null && tailShown > 0f) {
        drawWithLight(
            image = tail,
            offset = IntOffset(left, (tailY - noteSizePx).roundToInt()),
            extent = IntSize(noteSizePx, noteSizePx),
            light = light,
            alpha = tailShown,
        )
    }
}

/**
 * The line the receptors sit on, and nothing else: no lane grounds. The
 * notes are the lanes, over whatever is behind them, as StepMania draws it.
 * Grounds shaded on alternate lanes sliced the stage behind into strips.
 */
private fun DrawScope.drawReceptorLine(receptorYPx: Float) {
    drawLine(
        color = GlyphTheme.Hairline,
        start = Offset(0f, receptorYPx),
        end = Offset(size.width, receptorYPx),
        strokeWidth = 1f,
    )
}

/** Shape and colour only — used when an asset could not be loaded. */
private fun DrawScope.drawFallbackNote(x: Float, y: Float, sizePx: Float, color: Color) {
    val inset = sizePx * 0.15f
    translate(x, y) {
        drawRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(sizePx - inset * 2, sizePx - inset * 2),
        )
        drawRect(
            color = GlyphTheme.Ink,
            topLeft = Offset(inset, inset),
            size = Size(sizePx - inset * 2, sizePx - inset * 2),
            style = Stroke(width = 2f),
        )
    }
}

/** Notes stay drawable briefly after passing, so a late hit still has artwork. */
private const val PAST_WINDOW_SECONDS = 0.35f

/** The note is a little narrower than its lane, so lanes do not touch. */
private const val NOTE_SCALE = 0.82f

private val RECEPTOR_INSET = 96.dp

/** Reversed, the receptors sit under the HUD rather than behind it. */
private val RECEPTOR_TOP_INSET = 112.dp

/** How bright the receptors flash on the beat, as a share of a full copy. */
private const val BEAT_FLASH = 0.45f

/**
 * StepMania's options that change only how the playfield is drawn. None of
 * them changes a judgement: a hidden note is hit exactly as a visible one.
 */
data class GlyphFieldLook(
    val reverse: Boolean = false,
    val appearance: GlyphAppearance = GlyphAppearance.VISIBLE,
    val dark: Boolean = false,
    /** 0 to 0.5. */
    val mini: Float = 0f,
    /** Which effects are drawn. */
    val graphics: GlyphGraphics = GlyphGraphics(),
) {
    companion object {
        fun of(modifiers: GlyphModifiers) = GlyphFieldLook(
            reverse = modifiers.reverse,
            appearance = modifiers.appearance,
            dark = modifiers.dark,
            mini = modifiers.mini,
            graphics = modifiers.graphics,
        )
    }
}

/** Culling bounds. Generous either way; culling only has to be conservative. */
private const val MIN_LOOKAHEAD_SECONDS = 0.3f
private const val MAX_LOOKAHEAD_SECONDS = 6f
