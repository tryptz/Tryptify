// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size as DecodeSize
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.performance.LocalPerformanceProfile

/**
 * The stage behind the play: an arcade dance pad, a metal slab tilted away
 * into the room, between two speaker cabinets, with the song's album art as
 * the room.
 *
 * Gameplay only. The pad's arrow panels light as the player steps: softly
 * while a lane is held, and a brighter flash when a note in it is hit, so the
 * room answers the feet the way a cabinet's pad does. The woofers pump on the
 * chart's beat. Nothing else moves: a stage that moved on its own would pull
 * the eye off the lanes.
 *
 * The pad is seen through a camera above it ([StageGeometry]): its face is
 * drawn flat and mapped onto the tilted slab, the near edge wider than the
 * far one, with its front lip showing under the near edge.
 *
 * The art is decoded small and blurred, then darkened, so any cover reads as
 * a room and never as a picture competing with the arrows; until it has
 * loaded, and for a song without one, the room is plain dark blue. The rig
 * and the pad are drawn once per size into their own layer and replayed; the
 * lights and the woofer cones are a second, thin layer.
 */
@Composable
fun GlyphDanceStage(
    heldLanes: Set<GlyphLane>,
    explosionProvider: () -> Map<GlyphLane, LaneFlash>,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    /** The song's picture for the room; the plain room without one. */
    artUri: String? = null,
    /** The song's beat now, counted from its first, which the woofers pump on. */
    beatProvider: () -> Float = { 0f },
) {
    val context = LocalContext.current
    val blurArt = LocalPerformanceProfile.current.allowHazeBlur
    val held by rememberUpdatedState(heldLanes)
    val still by rememberUpdatedState(reducedMotion)
    val beat by rememberUpdatedState(beatProvider)
    val flashes by rememberUpdatedState(explosionProvider)

    Box(modifier = modifier.fillMaxSize()) {
        // The room: dark blue, glowing where the pad stands.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glow = Offset(size.width / 2f, size.height * ROOM_GLOW_AT)
                    onDrawBehind {
                        drawRect(ROOM_DARK)
                        drawEllipseGlow(
                            centre = glow,
                            radiusX = size.width * ROOM_GLOW_WIDTH,
                            radiusY = size.height * ROOM_GLOW_HEIGHT,
                            stops = ROOM_STOPS,
                        )
                    }
                },
        )

        if (artUri != null) {
            val request = remember(artUri, context) {
                ImageRequest.Builder(context)
                    .data(artUri)
                    .size(DecodeSize(ART_DECODE_PX, ART_DECODE_PX))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurArt) Modifier.blur(ART_BLUR, BlurredEdgeTreatment.Rectangle) else Modifier),
            )
            Spacer(modifier = Modifier.fillMaxSize().background(ART_SCRIM))
        }

        // The rig and the pad, in their own layer: the playfield redraws
        // every frame above it, and without one this would be redrawn with it.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val stage = StageGeometry(size)
                    val arrow = arrowPath()
                    val horizon = artUri == null
                    onDrawBehind { drawStage(stage, arrow, horizon) }
                },
        )

        // The frame clock only schedules the next draw; the flashes are
        // timed on the wall clock, as the playfield's explosions are.
        val tick = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { tick.longValue = it }
        }
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val stage = StageGeometry(size)
                    val cones = stage.speakers.map { ConeBrushes(stage, it) }
                    onDrawBehind {
                        @Suppress("UNUSED_EXPRESSION") tick.longValue
                        val pump = if (still) 1f else wooferPump(beat())
                        cones.forEach { drawCone(stage, it, pump) }
                        drawLights(stage, held, flashes(), still, System.nanoTime())
                        // The veil: the stage is the room, the lanes are the game.
                        drawRect(VEIL)
                    }
                },
        )
    }
}

/**
 * Where the stage lands on a screen of [size]: the design's [FRAME_W] by
 * [FRAME_H] frame scaled to fit and centred across, its parts placed down the
 * height, and the pad tilted [PAD_TILT_DEGREES] away from a camera
 * [EYE_DISTANCE_UNITS] off the screen and [EYE_ABOVE_PAD_UNITS] above the
 * pad's far edge.
 */
internal class StageGeometry(val size: Size) {
    /** One unit of the design's frame, in pixels. */
    val unit: Float = min(size.width / FRAME_W, size.height / FRAME_H)
    val centreX: Float = size.width / 2f

    /** The pad's far edge, which it tilts about. */
    val padTop: Float = size.height * PAD_TOP_SHARE
    private val eyeY = padTop - EYE_ABOVE_PAD_UNITS * unit
    private val eyeDistance = EYE_DISTANCE_UNITS * unit
    private val cosTilt = cos(Math.toRadians(PAD_TILT_DEGREES.toDouble())).toFloat()
    private val sinTilt = sin(Math.toRadians(PAD_TILT_DEGREES.toDouble())).toFloat()

    /** The speakers' top-left corners, in pixels. */
    val speakers: List<Offset> = listOf(
        Offset(centreX + (SPEAKER_LEFT - FRAME_W / 2f) * unit, size.height * SPEAKER_TOP_SHARE),
        Offset(centreX + (FRAME_W / 2f - SPEAKER_LEFT - SPEAKER_W) * unit, size.height * SPEAKER_TOP_SHARE),
    )

    /**
     * A point of the pad on the screen. [x] runs across from the pad's
     * centre, [y] from its far edge toward the player, [z] up out of its
     * face, all in pad units.
     */
    fun project(x: Float, y: Float, z: Float = 0f): Offset {
        val worldX = centreX + x * unit
        val worldY = padTop + (y * cosTilt - z * sinTilt) * unit
        val worldZ = (y * sinTilt + z * cosTilt) * unit
        val k = eyeDistance / (eyeDistance - worldZ)
        return Offset(centreX + (worldX - centreX) * k, eyeY + (worldY - eyeY) * k)
    }

    /** The pad's face, drawn flat in pad units from its far left corner, laid onto the slab. */
    val face: Matrix by lazy {
        planeMatrix(PAD_UNITS, PAD_UNITS) { u, v -> project(u - PAD_UNITS / 2f, v) }
    }

    /** The front lip under the near edge, drawn flat from its top left, laid onto the slab. */
    val lip: Matrix by lazy {
        planeMatrix(PAD_UNITS, LIP_UNITS) { u, v -> project(u - PAD_UNITS / 2f, PAD_UNITS, -v) }
    }
}

/** The matrix that lays a flat [w] by [h] drawing onto the four points [at] gives its corners. */
private fun planeMatrix(w: Float, h: Float, at: (Float, Float) -> Offset): Matrix {
    val a = at(0f, 0f)
    val b = at(w, 0f)
    val c = at(w, h)
    val d = at(0f, h)
    return Matrix().apply {
        setPolyToPoly(
            floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0,
            floatArrayOf(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y), 0,
            4,
        )
    }
}

/** Draws [block] on the plane [matrix] lays out. */
private inline fun DrawScope.onPlane(matrix: Matrix, block: DrawScope.() -> Unit) {
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.concat(matrix)
        block()
        canvas.nativeCanvas.restore()
    }
}

/** A soft elliptical glow: [stops] from the centre out to the ellipse's edge. */
private fun DrawScope.drawEllipseGlow(
    centre: Offset,
    radiusX: Float,
    radiusY: Float,
    stops: Array<Pair<Float, Color>>,
) {
    withTransform({ scale(1f, radiusY / radiusX, pivot = centre) }) {
        drawCircle(
            brush = Brush.radialGradient(*stops, center = centre, radius = radiusX),
            radius = radiusX,
            center = centre,
        )
    }
}

/** Which of the pad's 3 by 3 panels a lane is: its column. */
private fun column(lane: GlyphLane): Int = when (lane) {
    GlyphLane.LEFT -> 0
    GlyphLane.DOWN, GlyphLane.UP -> 1
    GlyphLane.RIGHT -> 2
    else -> -1
}

/** Which of the pad's 3 by 3 panels a lane is: its row. */
private fun row(lane: GlyphLane): Int = when (lane) {
    GlyphLane.UP -> 0
    GlyphLane.LEFT, GlyphLane.RIGHT -> 1
    GlyphLane.DOWN -> 2
    else -> -1
}

/** Up and down pink, left and right cyan, as on a cabinet's pad. */
private fun panelColour(lane: GlyphLane): Color =
    if (lane == GlyphLane.UP || lane == GlyphLane.DOWN) PINK else CYAN

/** The pale top of an arrow's fill. */
private fun panelTint(lane: GlyphLane): Color =
    if (lane == GlyphLane.UP || lane == GlyphLane.DOWN) PINK_LIGHT else CYAN_LIGHT

private fun turn(lane: GlyphLane): Float = when (lane) {
    GlyphLane.LEFT -> -90f
    GlyphLane.DOWN -> 180f
    GlyphLane.UP -> 0f
    GlyphLane.RIGHT -> 90f
    else -> 0f
}

/** An arrow pointing up, on a 24-unit square. */
private fun arrowPath(): Path = Path().apply {
    moveTo(12f, 3.5f)
    lineTo(20.5f, 12.5f)
    lineTo(15f, 12.5f)
    lineTo(15f, 20.5f)
    lineTo(9f, 20.5f)
    lineTo(9f, 12.5f)
    lineTo(3.5f, 12.5f)
    close()
}

private fun DrawScope.drawStage(stage: StageGeometry, arrow: Path, horizon: Boolean) {
    val w = size.width
    val h = size.height
    val u = stage.unit

    if (horizon) {
        drawRect(HORIZON, topLeft = Offset(0f, h * HORIZON_AT), size = Size(w, 2f * u))
    }
    // The spotlight on the pad, and the light it leaves on the floor.
    val beamTop = -h * BEAM_TOP_SHARE
    val beamBottom = h * BEAM_BOTTOM_SHARE
    val beam = Path().apply {
        moveTo(stage.centreX - BEAM_TOP_HALF * u, beamTop)
        lineTo(stage.centreX + BEAM_TOP_HALF * u, beamTop)
        lineTo(stage.centreX + BEAM_BOTTOM_HALF * u, beamBottom)
        lineTo(stage.centreX - BEAM_BOTTOM_HALF * u, beamBottom)
        close()
    }
    drawPath(
        beam,
        Brush.verticalGradient(
            0f to BEAM,
            1f to BEAM.copy(alpha = 0f),
            startY = beamTop,
            endY = beamTop + (beamBottom - beamTop) * BEAM_FADE_SHARE,
        ),
    )
    drawEllipseGlow(
        centre = Offset(stage.centreX, h * FLOOR_GLOW_AT),
        radiusX = FLOOR_GLOW_W * u,
        radiusY = FLOOR_GLOW_H * u,
        stops = FLOOR_STOPS,
    )

    stage.speakers.forEachIndexed { i, at -> drawCabinet(at, u, towardCentre = if (i == 0) 1f else -1f) }

    // The pad: its shadow on the floor, the lip under its near edge, then its face.
    val nearLeft = stage.project(-PAD_UNITS / 2f, PAD_UNITS, -LIP_UNITS)
    val nearRight = stage.project(PAD_UNITS / 2f, PAD_UNITS, -LIP_UNITS)
    drawEllipseGlow(
        centre = Offset(stage.centreX, nearLeft.y),
        radiusX = (nearRight.x - nearLeft.x) * 0.58f,
        radiusY = PAD_SHADOW_H * u,
        stops = PAD_SHADOW_STOPS,
    )
    onPlane(stage.lip) {
        val lip = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(LIP_INSET, 0f, PAD_UNITS - LIP_INSET, LIP_UNITS),
                    bottomLeft = CornerRadius(10f),
                    bottomRight = CornerRadius(10f),
                ),
            )
        }
        drawPath(lip, Brush.verticalGradient(0f to LIP_TOP, 1f to LIP_BOTTOM, startY = 0f, endY = LIP_UNITS))
    }
    onPlane(stage.face) { drawPadFace(arrow) }
}

/** The pad's face, flat, in pad units: brushed metal, nine raised panels, four of them arrows. */
private fun DrawScope.drawPadFace(arrow: Path) {
    drawRoundRect(brush = FACE_BRUSH, size = Size(PAD_UNITS, PAD_UNITS), cornerRadius = CornerRadius(16f))
    // Lit along the far edge, in shade along the near one.
    drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(14f, 0f), size = Size(PAD_UNITS - 28f, 2f))
    drawRect(Color.Black.copy(alpha = 0.25f), topLeft = Offset(14f, PAD_UNITS - 3f), size = Size(PAD_UNITS - 28f, 3f))

    val well = Path().apply {
        addRoundRect(RoundRect(Rect(WELL_INSET, WELL_INSET, PANEL_SIZE - WELL_INSET, PANEL_SIZE - WELL_INSET), CornerRadius(18f)))
    }
    val glyph = (PANEL_SIZE - WELL_INSET * 2f) / 24f
    for (r in 0 until 3) for (c in 0 until 3) {
        translate(PANEL_INSET + c * PANEL_PITCH, PANEL_INSET + r * PANEL_PITCH) {
            // Raised: its shadow, its edge, then its top.
            drawRoundRect(
                Color.Black.copy(alpha = 0.2f),
                topLeft = Offset(-2f, 6f),
                size = Size(PANEL_SIZE + 4f, PANEL_SIZE + 6f),
                cornerRadius = CornerRadius(12f),
            )
            drawRoundRect(PANEL_EDGE, topLeft = Offset(0f, 5f), size = Size(PANEL_SIZE, PANEL_SIZE), cornerRadius = CornerRadius(9f))
            drawRoundRect(brush = PANEL_BRUSH, size = Size(PANEL_SIZE, PANEL_SIZE), cornerRadius = CornerRadius(9f))
            drawRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(8f, 0f), size = Size(PANEL_SIZE - 16f, 1f))
            for (bx in BOLTS) for (by in BOLTS) {
                translate(bx, by) { drawCircle(BOLT_BRUSH, radius = BOLT_R, center = Offset(BOLT_R, BOLT_R)) }
            }

            val lane = GlyphLane.DANCE.firstOrNull { column(it) == c && row(it) == r } ?: return@translate
            // The arrow's black well, sunk into the panel: lit on its near
            // lip, in shadow under its far one.
            translate(0f, 1f) { drawPath(well, Color.White.copy(alpha = 0.7f)) }
            drawPath(well, WELL_BRUSH)
            clipPath(well) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent, startY = WELL_INSET, endY = WELL_INSET + 10f),
                    topLeft = Offset(WELL_INSET, WELL_INSET),
                    size = Size(PANEL_SIZE - WELL_INSET * 2f, 10f),
                )
            }
            translate(WELL_INSET, WELL_INSET) {
                scale(glyph, glyph, pivot = Offset.Zero) {
                    translate(0f, 0.8f) {
                        rotate(turn(lane), pivot = Offset(12f, 12f)) { drawPath(arrow, Color.Black.copy(alpha = 0.45f)) }
                    }
                    rotate(turn(lane), pivot = Offset(12f, 12f)) {
                        drawPath(
                            arrow,
                            Brush.verticalGradient(0f to panelTint(lane), 1f to panelColour(lane), startY = 3.5f, endY = 20.5f),
                        )
                        drawPath(arrow, Color.White, style = Stroke(width = 0.9f, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/**
 * A cabinet, standing a little turned toward the pad: its side shows on the
 * [towardCentre] side (1 for the right, -1 for the left), a tweeter over the
 * woofer's surround. The woofer's cone is drawn with the lights, as it moves.
 */
private fun DrawScope.drawCabinet(at: Offset, u: Float, towardCentre: Float) {
    val extent = Size(SPEAKER_W * u, SPEAKER_H * u)
    val corner = CornerRadius(9f * u)
    // Its shadow, falling toward the pad and down.
    for (i in 1..3) {
        val spread = i * 6f * u
        drawRoundRect(
            Color.Black.copy(alpha = 0.12f),
            topLeft = Offset(at.x + towardCentre * 8f * u - spread / 2f, at.y + 16f * u - spread / 2f),
            size = Size(extent.width + spread, extent.height + spread),
            cornerRadius = CornerRadius(corner.x + spread / 2f),
        )
    }
    drawRoundRect(CABINET_SIDE, topLeft = Offset(at.x + towardCentre * 8f * u, at.y), size = extent, cornerRadius = corner)
    drawRoundRect(
        Brush.verticalGradient(0f to CABINET_TOP, 1f to CABINET_BOTTOM, startY = at.y, endY = at.y + extent.height),
        topLeft = at,
        size = extent,
        cornerRadius = corner,
    )
    drawRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(at.x + 7f * u, at.y), size = Size(extent.width - 14f * u, 2f * u))

    val tweeter = Offset(at.x + SPEAKER_W / 2f * u, at.y + 28f * u)
    drawCircle(TWEETER_RING_DARK, radius = 18f * u, center = tweeter)
    drawCircle(TWEETER_RING, radius = 17f * u, center = tweeter)
    drawCircle(
        Brush.radialGradient(
            0f to TWEETER_LIGHT,
            0.72f to TWEETER_DARK,
            center = tweeter + Offset(-2.8f * u, -4.2f * u),
            radius = 20f * u,
        ),
        radius = 14f * u,
        center = tweeter,
    )

    val woofer = wooferCentre(at, u)
    drawCircle(Color.Black.copy(alpha = 0.3f), radius = 40f * u, center = woofer + Offset(0f, 3f * u))
    drawCircle(
        Brush.radialGradient(
            0f to SURROUND_LIGHT,
            0.7f to SURROUND_DARK,
            center = woofer + Offset(-7.8f * u, -14f * u),
            radius = 50f * u,
        ),
        radius = 39f * u,
        center = woofer,
    )
}

private fun wooferCentre(at: Offset, u: Float): Offset = Offset(at.x + SPEAKER_W / 2f * u, at.y + 95f * u)

/** A woofer's cone and dust cap, as brushes laid out once per size. */
private class ConeBrushes(stage: StageGeometry, at: Offset) {
    val u = stage.unit
    val centre = wooferCentre(at, u)
    val cone = Brush.radialGradient(
        0f to CONE_LIGHT,
        0.55f to CONE_MID,
        1f to CONE_DARK,
        center = centre + Offset(-2.4f * u, -6f * u),
        radius = 34f * u,
    )
    val cap = Brush.radialGradient(
        0f to CAP_LIGHT,
        0.75f to CAP_DARK,
        center = centre + Offset(-2.2f * u, -3.2f * u),
        radius = 12f * u,
    )
}

private fun DrawScope.drawCone(stage: StageGeometry, brushes: ConeBrushes, pump: Float) {
    val u = stage.unit
    withTransform({ scale(pump, pump, pivot = brushes.centre) }) {
        drawCircle(brushes.cone, radius = 30f * u, center = brushes.centre)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 29f * u, center = brushes.centre, style = Stroke(3f * u))
        drawCircle(brushes.cap, radius = 9f * u, center = brushes.centre)
    }
}

/**
 * Each lane's panel light, on the pad's face: [HELD_LIGHT] while it is held,
 * and a flash from [HIT_LIGHT] down to nothing over [HIT_LIGHT_SECONDS] after
 * a hit in it. Reduced motion keeps the held light and drops the flash.
 */
private fun DrawScope.drawLights(
    stage: StageGeometry,
    heldLanes: Set<GlyphLane>,
    flashes: Map<GlyphLane, LaneFlash>,
    reducedMotion: Boolean,
    now: Long,
) {
    val lit = GlyphLane.DANCE.mapNotNull { lane ->
        val amount = stageLight(
            held = lane in heldLanes,
            flash = if (reducedMotion) null else flashes[lane],
            now = now,
        )
        if (amount > LIGHT_THRESHOLD) lane to amount else null
    }
    if (lit.isEmpty()) return
    onPlane(stage.face) {
        for ((lane, amount) in lit) {
            val left = PANEL_INSET + column(lane) * PANEL_PITCH
            val top = PANEL_INSET + row(lane) * PANEL_PITCH
            val colour = panelColour(lane)
            // The glow the panel throws on the pad round it, widest faintest.
            for ((spill, share) in GLOW_RINGS) {
                drawRoundRect(
                    colour,
                    topLeft = Offset(left - spill, top - spill),
                    size = Size(PANEL_SIZE + spill * 2f, PANEL_SIZE + spill * 2f),
                    cornerRadius = CornerRadius(9f + spill),
                    alpha = amount * share,
                    blendMode = BlendMode.Plus,
                )
            }
            drawRoundRect(
                colour,
                topLeft = Offset(left, top),
                size = Size(PANEL_SIZE, PANEL_SIZE),
                cornerRadius = CornerRadius(9f),
                alpha = amount,
            )
        }
    }
}

/** How lit a lane's panel is, 0 to 1: held, flashed by a hit [now], or both. */
internal fun stageLight(held: Boolean, flash: LaneFlash?, now: Long): Float {
    var amount = if (held) HELD_LIGHT else 0f
    if (flash != null && flash.isHit) {
        val age = (now - flash.atNanos) / 1_000_000_000f
        if (age in 0f..HIT_LIGHT_SECONDS) amount = max(amount, HIT_LIGHT * (1f - age / HIT_LIGHT_SECONDS))
    }
    return amount
}

/**
 * How far a woofer's cone stands out at [beat]: [WOOFER_PUMP] on each beat,
 * eased back to rest by [WOOFER_PUMP_SHARE] of the way to the next, and at
 * rest before the first.
 */
internal fun wooferPump(beat: Float): Float {
    if (beat.isNaN() || beat < 0f) return 1f
    val phase = beat - floor(beat)
    if (phase >= WOOFER_PUMP_SHARE) return 1f
    val t = phase / WOOFER_PUMP_SHARE
    val eased = 1f - (1f - t) * (1f - t)
    return WOOFER_PUMP - (WOOFER_PUMP - 1f) * eased
}

internal const val HELD_LIGHT = 0.3f
internal const val HIT_LIGHT = 0.6f
internal const val HIT_LIGHT_SECONDS = 0.3f
private const val LIGHT_THRESHOLD = 0.01f

internal const val WOOFER_PUMP = 1.05f
internal const val WOOFER_PUMP_SHARE = 0.35f

/** The design's frame, in units: the stage is laid out on it and scaled to fit. */
private const val FRAME_W = 370f
private const val FRAME_H = 816f

/** The pad: 260 units square, nine 76-unit panels on an 82-unit pitch, 26 units thick. */
internal const val PAD_UNITS = 260f
private const val PANEL_SIZE = 76f
private const val PANEL_PITCH = 82f
private const val PANEL_INSET = 10f
internal const val LIP_UNITS = 26f
private const val LIP_INSET = 16f
private const val WELL_INSET = 7f
private const val BOLT_R = 3.5f
private val BOLTS = floatArrayOf(5f, 64f)
private val GLOW_RINGS = listOf(3f to 0.16f, 7f to 0.12f, 11f to 0.09f, 16f to 0.06f, 22f to 0.04f, 29f to 0.02f)

/** The pad's far edge is this far down the screen, tilted this far away. */
private const val PAD_TOP_SHARE = 0.47f
internal const val PAD_TILT_DEGREES = 55f
/** The camera: this far off the screen, this far above the pad's far edge. */
private const val EYE_DISTANCE_UNITS = 900f
private const val EYE_ABOVE_PAD_UNITS = 126f
private const val PAD_SHADOW_H = 22f

/** The speakers: 92 by 150 units, 12 in from the frame's sides, this far down. */
private const val SPEAKER_W = 92f
private const val SPEAKER_H = 150f
private const val SPEAKER_LEFT = 12f
private const val SPEAKER_TOP_SHARE = 0.15f

private const val HORIZON_AT = 0.4f
private const val BEAM_TOP_SHARE = 0.08f
private const val BEAM_BOTTOM_SHARE = 0.84f
private const val BEAM_FADE_SHARE = 0.88f
private const val BEAM_TOP_HALF = 35f
private const val BEAM_BOTTOM_HALF = 220f
private const val FLOOR_GLOW_AT = 0.74f
private const val FLOOR_GLOW_W = 190f
private const val FLOOR_GLOW_H = 75f
private const val ROOM_GLOW_AT = 0.7f
private const val ROOM_GLOW_WIDTH = 0.85f
private const val ROOM_GLOW_HEIGHT = 0.48f

/** The art is decoded this small, which already softens it, then blurred. */
private const val ART_DECODE_PX = 160
private val ART_BLUR = 18.dp
private val ART_SCRIM = Color(0xFF070B1C).copy(alpha = 0.45f)

private val ROOM_DARK = Color(0xFF0A1230)
private val ROOM_STOPS = arrayOf(0f to Color(0xFF2F4C94), 0.42f to Color(0xFF182A5E), 0.78f to ROOM_DARK, 1f to ROOM_DARK)
private val HORIZON = Color(red = 120, green = 150, blue = 230, alpha = 56)
private val BEAM = Color(red = 170, green = 195, blue = 255, alpha = 46)
private val FLOOR_STOPS = arrayOf(
    0f to Color(red = 175, green = 205, blue = 255, alpha = 82),
    1f to Color(red = 175, green = 205, blue = 255, alpha = 0),
)
private val PAD_SHADOW_STOPS = arrayOf(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0f))

private val FACE_BRUSH = Brush.linearGradient(
    0f to Color(0xFFE6EAF1),
    0.55f to Color(0xFFAEB6C5),
    1f to Color(0xFF8F99AB),
    start = Offset(88.8f, -23.8f),
    end = Offset(171.2f, 283.8f),
)
private val PANEL_BRUSH = Brush.linearGradient(
    0f to Color(0xFFF3F5F9),
    1f to Color(0xFFC8CFDB),
    start = Offset(21.3f, -7.8f),
    end = Offset(54.7f, 83.8f),
)
private val PANEL_EDGE = Color(0xFF6E778A)
private val BOLT_BRUSH = Brush.radialGradient(
    0f to Color.White,
    0.6f to Color(0xFF8F99AB),
    1f to Color(0xFF5D667A),
    center = Offset(2.45f, 2.1f),
    radius = 5.5f,
)
private val WELL_BRUSH = Brush.radialGradient(
    0f to Color(0xFF232838),
    1f to Color(0xFF0B0D14),
    center = Offset(38f, 30.6f),
    radius = 49f,
)
private val LIP_TOP = Color(0xFF5B6476)
private val LIP_BOTTOM = Color(0xFF2A303D)

private val CABINET_TOP = Color(0xFFDFE4EC)
private val CABINET_BOTTOM = Color(0xFFA8B0BE)
private val CABINET_SIDE = Color(0xFF6D7689)
private val TWEETER_RING = Color(0xFFC3C9D4)
private val TWEETER_RING_DARK = Color(0xFF5B6374)
private val TWEETER_LIGHT = Color(0xFF9AA3B4)
private val TWEETER_DARK = Color(0xFF3A4150)
private val SURROUND_LIGHT = Color(0xFFD0D6E0)
private val SURROUND_DARK = Color(0xFF7A8396)
private val CONE_LIGHT = Color(0xFF6C768A)
private val CONE_MID = Color(0xFF3A4252)
private val CONE_DARK = Color(0xFF20252F)
private val CAP_LIGHT = Color(0xFF9AA4B8)
private val CAP_DARK = Color(0xFF2B313D)

private val PINK = Color(0xFFFF2FB4)
private val PINK_LIGHT = Color(0xFFFFC2E8)
private val CYAN = Color(0xFF3AE6FF)
private val CYAN_LIGHT = Color(0xFFC9F7FF)
private val VEIL = Color(0xFF060A1C).copy(alpha = 0.3f)
