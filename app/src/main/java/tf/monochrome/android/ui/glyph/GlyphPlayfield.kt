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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetId
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphEffectArt
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.asset.GlyphSpecialNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine

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
    scrollSeconds: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** A previous run's timing, drawn as markers beside the receptors. */
    ghost: GlyphGhost? = null,
    /**
     * The most recent judgement per lane, for the receptor explosion. Read
     * inside the draw scope, so it is a provider rather than a value.
     */
    explosionProvider: () -> Map<GlyphLane, LaneFlash> = { emptyMap() },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Lane width is snapped to whole pixels and every note is drawn at that
        // exact size. A fractional lane width would put each lane on a
        // different sub-pixel phase and the arrows would shimmer as they moved.
        val laneWidthPx = (widthPx / GlyphLane.entries.size).toInt().coerceAtLeast(1)
        val noteSizePx = (laneWidthPx * NOTE_SCALE).toInt().coerceAtLeast(1)
        val receptorYPx = heightPx - with(density) { RECEPTOR_INSET.toPx() } - noteSizePx / 2f

        // Warm the exact pixel size this layout needs. Re-runs on rotation or a
        // window resize, and drops the old rasters so stale sizes cannot pile up.
        var warmed by remember { mutableStateOf(false) }
        LaunchedEffect(engine, noteSizePx) {
            warmed = false
            if (engine == null || noteSizePx <= 1) return@LaunchedEffect
            assets.releaseImages()
            assets.prewarm(
                ids = GlyphAssetCatalog.playfieldAssets(engine.chart.divisionsUsed),
                widthPx = noteSizePx,
                heightPx = noteSizePx,
            )
            // The explosion is drawn larger than a note — the pack's effects
            // are 128-unit artwork with deliberate padding — so it needs its
            // own warm size. Warming it at the note size and scaling up would
            // throw away the resolution the pack exists to provide.
            assets.prewarm(
                ids = GlyphEffectArt.entries.map(GlyphAssetCatalog::effect),
                widthPx = noteSizePx * 2,
                heightPx = noteSizePx * 2,
            )
            warmed = true
        }

        // The frame clock drives *redrawing* only. What is drawn comes from the
        // audio clock through positionProvider, so a dropped frame costs a
        // frame of animation and never a judgement.
        val frameTick = rememberFrameTick(enabled = engine != null)

        val laneNames = remember {
            GlyphLane.entries.joinToString(", ") { it.label }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        "Playfield with four lanes: $laneNames. " +
                            "Notes fall toward the receptors at the bottom."
                },
        ) {
            // Reading the tick inside the draw scope is what schedules the next
            // frame without recomposing anything.
            @Suppress("UNUSED_EXPRESSION") frameTick.value

            drawLaneChrome(laneWidthPx.toFloat(), receptorYPx, noteSizePx.toFloat())

            val running = engine ?: return@Canvas
            if (!warmed) return@Canvas

            val position = positionProvider()
            val lookahead = scrollSeconds
            val visible = running.visibleNotes(position - PAST_WINDOW_SECONDS, position + lookahead)

            val pixelsPerSecond = receptorYPx / lookahead

            // Pass one: hold and roll bodies, underneath everything.
            for (entry in visible) {
                val note = entry.note
                if (!note.type.isHoldLike) continue
                drawHoldBody(
                    assets = assets,
                    note = note,
                    isRoll = note.type == GlyphNoteType.ROLL,
                    position = position,
                    pixelsPerSecond = pixelsPerSecond,
                    receptorYPx = receptorYPx,
                    laneWidthPx = laneWidthPx,
                    noteSizePx = noteSizePx,
                    isHeld = entry.isHeld,
                )
            }

            // Pass two: receptors, above the bodies so a hold reads as passing
            // through the target rather than over it.
            for (lane in GlyphLane.entries) {
                val id = GlyphAssetCatalog.receptor(lane, active = lane in heldLanes)
                val image = assets.image(id, noteSizePx, noteSizePx) ?: continue
                drawSnapped(
                    image = image,
                    x = lane.ordinal * laneWidthPx + (laneWidthPx - noteSizePx) / 2f,
                    y = receptorYPx - noteSizePx / 2f,
                    size = noteSizePx,
                )
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
                    pixelsPerSecond = pixelsPerSecond,
                    receptorYPx = receptorYPx,
                    laneWidthPx = laneWidthPx,
                )
            }

            // Explosions, above the receptors: feedback must never be hidden
            // by the thing that produced it. Skipped entirely under reduced
            // motion, which is also the most expensive drawing on screen
            // during a dense chart.
            if (!reducedMotion) {
                val now = System.nanoTime()
                for ((lane, flash) in explosionProvider()) {
                    val age = (now - flash.atNanos) / 1_000_000_000f
                    if (age < 0f || age > EXPLOSION_SECONDS) continue
                    val art = if (flash.isHit) {
                        GlyphEffectArt.TAP_EXPLOSION
                    } else {
                        GlyphEffectArt.MISS_CRACK
                    }
                    val image = assets.image(
                        GlyphAssetCatalog.effect(art), noteSizePx * 2, noteSizePx * 2,
                    ) ?: continue
                    drawExplosion(
                        image = image,
                        laneOrdinal = lane.ordinal,
                        laneWidthPx = laneWidthPx,
                        sizePx = noteSizePx * 2,
                        receptorYPx = receptorYPx,
                        progress = age / EXPLOSION_SECONDS,
                    )
                }
            }

            // Pass three: heads and taps, above the receptors.
            for (entry in visible) {
                val note = entry.note
                if (entry.isHeadJudged && note.type.isHoldLike) continue

                val secondsAway = note.timeSeconds - position
                val y = receptorYPx - secondsAway * pixelsPerSecond
                if (y < -noteSizePx || y > size.height + noteSizePx) continue

                val id = noteAssetFor(note.type, note.lane, note.division)
                val image = assets.image(id, noteSizePx, noteSizePx)
                val x = note.lane.ordinal * laneWidthPx + (laneWidthPx - noteSizePx) / 2f

                if (image != null) {
                    drawSnapped(image, x, y - noteSizePx / 2f, noteSizePx)
                } else {
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
    }
}

/**
 * One lane's most recent judgement, for the receptor flash.
 *
 * Carries the wall-clock instant rather than a song position: the explosion is
 * decoration and should fade in real time even while the song is paused, unlike
 * everything else on the playfield.
 */
data class LaneFlash(val atNanos: Long, val isHit: Boolean)

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
    pixelsPerSecond: Float,
    receptorYPx: Float,
    laneWidthPx: Int,
) {
    val fromMs = ((position - PAST_WINDOW_SECONDS) * 1000f).toInt()
    val toMs = ((position + lookahead) * 1000f).toInt()

    for (index in ghost.between(fromMs, toMs)) {
        val noteSeconds = ghost.timesMs[index] / 1000f
        val offsetSeconds = ghost.offsetsMs[index] / 1000f
        val lane = ghost.lanes[index]
        if (lane !in GlyphLane.entries.indices) continue

        val y = receptorYPx - (noteSeconds + offsetSeconds - position) * pixelsPerSecond
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
 */
@Composable
private fun rememberFrameTick(enabled: Boolean): State<Long> {
    val tick = remember { mutableStateOf(0L) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos -> tick.value = nanos }
        }
    }
    return tick
}

/** Which artwork a note uses. Special notes ignore the beat palette. */
private fun noteAssetFor(
    type: GlyphNoteType,
    lane: GlyphLane,
    division: GlyphBeatDivision,
): GlyphAssetId = when (type) {
    GlyphNoteType.MINE -> GlyphAssetCatalog.special(GlyphSpecialNote.MINE)
    GlyphNoteType.LIFT -> GlyphAssetCatalog.special(GlyphSpecialNote.LIFT)
    GlyphNoteType.FAKE -> GlyphAssetCatalog.special(GlyphSpecialNote.FAKE)
    GlyphNoteType.HOLD -> GlyphAssetCatalog.holdHead(lane, roll = false)
    GlyphNoteType.ROLL -> GlyphAssetCatalog.holdHead(lane, roll = true)
    GlyphNoteType.TAP -> GlyphAssetCatalog.tap(lane, division)
}

/**
 * Draw an image at whole physical pixels.
 *
 * `drawImage` with an integer offset and an integer size skips the filtering a
 * fractional placement would need, which is both faster and sharper — a
 * 64-pixel arrow at a 64-pixel size lands exactly on the grid it was drawn on.
 */
private fun DrawScope.drawSnapped(image: ImageBitmap, x: Float, y: Float, size: Int) {
    drawImage(
        image = image,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(size, size),
    )
}

/**
 * Tile a hold body between its head and its tail.
 *
 * Tiled at the body's own height with no gap and no scaling, because the pack's
 * body artwork is drawn to be seamless at its native proportions. Stretching a
 * single copy to the hold's length would smear it; leaving a gap between tiles
 * would show a hairline at every seam.
 */
private fun DrawScope.drawHoldBody(
    assets: GlyphAssetRepository,
    note: tf.monochrome.android.glyph.chart.GlyphNote,
    isRoll: Boolean,
    position: Float,
    pixelsPerSecond: Float,
    receptorYPx: Float,
    laneWidthPx: Int,
    noteSizePx: Int,
    isHeld: Boolean,
) {
    val body = assets.image(GlyphAssetCatalog.holdBody(isRoll), noteSizePx, noteSizePx) ?: return
    val tail = assets.image(GlyphAssetCatalog.holdTail(isRoll), noteSizePx, noteSizePx)

    // A held note's head stops at the receptor: the tail keeps travelling while
    // the head stays put, which is what makes holding legible.
    val headSeconds = if (isHeld) minOf(note.timeSeconds, position) else note.timeSeconds
    val headY = receptorYPx - (headSeconds - position) * pixelsPerSecond
    val tailY = receptorYPx - (note.endTimeSeconds - position) * pixelsPerSecond
    if (headY < tailY) return

    val x = note.lane.ordinal * laneWidthPx + (laneWidthPx - noteSizePx) / 2f
    val left = x.roundToInt()

    // Tiles are laid from the tail down toward the head so the seam that gets
    // clipped is the one at the head, which the head artwork covers anyway.
    var y = tailY
    while (y < headY) {
        val remaining = (headY - y).roundToInt()
        if (remaining <= 0) break
        drawImage(
            image = body,
            dstOffset = IntOffset(left, y.roundToInt()),
            dstSize = IntSize(noteSizePx, minOf(noteSizePx, remaining)),
        )
        y += noteSizePx
    }

    if (tail != null) {
        drawImage(
            image = tail,
            dstOffset = IntOffset(left, (tailY - noteSizePx).roundToInt()),
            dstSize = IntSize(noteSizePx, noteSizePx),
        )
    }
}

/** Lane separators and the receptor line. Deliberately quiet. */
private fun DrawScope.drawLaneChrome(laneWidthPx: Float, receptorYPx: Float, noteSizePx: Float) {
    for (lane in GlyphLane.entries) {
        val left = lane.ordinal * laneWidthPx
        // Alternating lane grounds give the eye a boundary without a line for
        // every lane, which at four lanes reads as a cage.
        if (lane.ordinal % 2 == 1) {
            drawRect(
                color = GlyphTheme.InkPanel.copy(alpha = 0.5f),
                topLeft = Offset(left, 0f),
                size = Size(laneWidthPx, size.height),
            )
        }
    }
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
