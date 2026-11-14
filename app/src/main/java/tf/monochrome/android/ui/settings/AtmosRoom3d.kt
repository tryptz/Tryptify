package tf.monochrome.android.ui.settings

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.atmos.AtmosAudioProcessor
import tf.monochrome.android.audio.atmos.AtmosNative
import tf.monochrome.android.domain.model.RoomShape
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * The view of a room: where a point of it, in metres, lands on the page. The
 * eye is behind and above the room, turned [yaw] radians about its middle,
 * and the room is drawn as wide as its longer side allows.
 */
internal class AtmosRoomView(yaw: Float, private val shape: RoomShape) {
    private val cy = cos(yaw)
    private val sy = sin(yaw)
    private val cp = cos(PITCH)
    private val sp = sin(PITCH)
    private val scale = 1f / maxOf(shape.width, shape.depth)

    /** Sideways (right is up), upwards, and how big things there are drawn (1 at the middle). */
    class Point(val x: Float, val y: Float, val scale: Float, val depth: Float)

    /** A point [x] metres from the left wall, [y] from the wall ahead and [z] from the floor. */
    fun project(x: Float, y: Float, z: Float): Point {
        val px = (x - shape.width / 2f) * scale
        val py = (shape.depth / 2f - y) * scale    // towards the front
        val pz = (z - shape.height / 2f) * scale   // up, about the room's middle
        val rx = px * cy - py * sy
        val ry = px * sy + py * cy
        val up = pz * cp + ry * sp
        val depth = ry * cp - pz * sp
        val size = EYE / (EYE + depth)
        return Point(rx * size, up * size, size, depth)
    }

    companion object {
        const val EYE = 2.6f
        const val PITCH = 0.42f          // 24 degrees down
        const val START_YAW = -0.5f      // from behind the listener's right shoulder
    }
}

/** 0..1 from a linear peak, over the meters' 60 dB. */
internal fun atmosMeterLevel(peak: Float): Float =
    ((20f * log10(peak.coerceAtLeast(1e-6f)) + 60f) / 60f).coerceIn(0f, 1f)

/** How bright an object is drawn: its level, or half for one that is watched and has none. */
internal fun atmosObjectLevel(peak: Float): Float = if (peak < 0f) 0.5f else atmosMeterLevel(peak)

/**
 * How bright a room speaker is drawn: its level when the room plays, half as
 * much of its share when the objects are only watched (a value under zero),
 * nothing when it is silent.
 */
internal fun atmosSpeakerGlow(value: Float): Float = when {
    value < 0f -> 0.25f + 0.45f * (-value).coerceIn(0f, 1f)
    else -> atmosMeterLevel(value)
}

/**
 * The room's speakers lit from a plain stream's own channels, for when no
 * object plays through or towards the room (stereo, or a 5.1 or 7.1 bed that
 * is not rendered): each named channel on the speaker a bed channel of that
 * name plays from. [names] and [peaksDb] are the channel detector's. Null
 * when no channel has a speaker.
 */
internal fun roomLevelsFromChannels(layout: FloatArray, names: List<String>, peaksDb: FloatArray): FloatArray? {
    val count = layout.size / AtmosNative.ROOM_FIELDS
    if (count == 0) return null
    val sides = "SL" in names || "SR" in names
    val out = FloatArray(count)
    var any = false
    names.forEachIndexed { c, name ->
        // A 5.1 bed's surrounds stand at 110 degrees, a 7.1 bed's rears at the back.
        val back = if (sides) 1f else 0.75f
        val place: Triple<Float, Float, Float>? = when (name) {
            "FL" -> Triple(0f, 0f, 0f)
            "FR" -> Triple(1f, 0f, 0f)
            "FC" -> Triple(0.5f, 0f, 0f)
            "SL" -> Triple(0f, 0.5f, 0f)
            "SR" -> Triple(1f, 0.5f, 0f)
            "BL" -> Triple(0f, back, 0f)
            "BR" -> Triple(1f, back, 0f)
            else -> null
        }
        val speaker = (0 until count).firstOrNull { s ->
            val f = s * AtmosNative.ROOM_FIELDS
            if (name == "LFE") isLfe(layout, f)
            else place != null && !isLfe(layout, f) &&
                layout[f] == place.first && layout[f + 1] == place.second && layout[f + 2] == place.third
        } ?: return@forEachIndexed
        val db = peaksDb.getOrElse(c) { -120f }
        out[speaker] = Math.pow(10.0, db / 20.0).toFloat().coerceIn(0f, 1f)
        any = true
    }
    return if (any) out else null
}

/**
 * The room the headphone render plays in, in three dimensions.
 *
 * What is drawn is what is heard. The room has [shape]'s proportions, with a
 * metre grid on its floor and, when the size is a real one, its measurements
 * along its edges. The 28 speakers of the 15.1.12 layout stand on its walls,
 * each as bright as it is driven, the mix's objects float where the mix puts
 * them, and the listener sits where the room's listener sat. From the three
 * loudest speakers a ray runs to the listener, and their waves fill the room
 * as a glow: bright where the waves from those speakers and from the walls
 * meet in step, dark where they cancel. With [roomAmount] of room the walls'
 * reflections join the waves, and the walls themselves glow with what the
 * room still holds, which dies away over [decaySeconds] as the room's sound
 * does. The waves move only while something plays, and not at all when the
 * listener has asked for less motion.
 *
 * It is drawn by the GPU ([RoomShader]: a camera, lit surfaces, light in the
 * air) on Android 13 and up, and on the canvas ([AtmosRoomCanvas]) where that
 * shader cannot run. Drag sideways to walk round it. [layout] is
 * [AtmosNative.nativeRoomLayout]'s, [speakers] the processor's levels in the
 * same order (null: nothing plays through the room).
 */
@Composable
internal fun AtmosRoom3d(
    layout: FloatArray,
    speakers: FloatArray?,
    objects: AtmosAudioProcessor.ObjectMeter?,
    shape: RoomShape,
    roomAmount: Float,
    decaySeconds: Float,
    modifier: Modifier = Modifier,
) {
    val shader = rememberRoomShader()
    if (shader != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        AtmosRoomGpu(shader, layout, speakers, objects, shape, roomAmount, decaySeconds, modifier)
    } else {
        AtmosRoomCanvas(layout, speakers, objects, shape, roomAmount, decaySeconds, modifier)
    }
}

/** The room's labels: the objects' numbers, and its measurements (width, depth, height; empty when it has none). */
private class RoomLabels(
    val numbers: List<androidx.compose.ui.text.TextLayoutResult>,
    val sizes: List<androidx.compose.ui.text.TextLayoutResult?>,
)

/** Measured when the room or the theme changes, never while drawing. */
@Composable
private fun rememberRoomLabels(shape: RoomShape, ink: Color): RoomLabels {
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = remember(ink) {
        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.8f))
    }
    val numbers = remember(numberStyle, textMeasurer) {
        List(MAX_NUMBERS) { textMeasurer.measure((it + 1).toString(), style = numberStyle) }
    }
    val metres = stringResource(R.string.atmos_room_metres, "%s")
    val sizes = remember(shape.width, shape.depth, shape.height, shape.dimensioned, shape.heightKnown, numberStyle) {
        if (!shape.dimensioned) {
            emptyList()
        } else {
            val style = numberStyle.copy(fontSize = 10.sp)
            listOf(shape.width, shape.depth, shape.height.takeIf { shape.heightKnown }).map { m ->
                m?.let { textMeasurer.measure(metres.replace("%s", String.format(java.util.Locale.getDefault(), "%.1f", it)), style = style) }
            }
        }
    }
    return RoomLabels(numbers, sizes)
}

/**
 * The waves' clock: the time since it started, in nanoseconds, thirty times
 * a second while [running], and held still otherwise. Read only where the
 * waves are drawn, so nothing else is redrawn by it.
 */
@Composable
private fun rememberWaveClock(running: Boolean): androidx.compose.runtime.LongState {
    val clock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    if (running) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val start = System.nanoTime() - clock.longValue
            while (true) {
                clock.longValue = System.nanoTime() - start
                kotlinx.coroutines.delay(33)
            }
        }
    }
    return clock
}

/**
 * The room drawn by the GPU. Each frame the page says what is in the room
 * ([RoomScene]) and where the camera is ([RoomCamera]); the shader draws it,
 * and the labels go over it by the same camera.
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AtmosRoomGpu(
    shader: RoomShader,
    layout: FloatArray,
    speakers: FloatArray?,
    objects: AtmosAudioProcessor.ObjectMeter?,
    shape: RoomShape,
    roomAmount: Float,
    decaySeconds: Float,
    modifier: Modifier = Modifier,
) {
    val liveSpeakers by rememberUpdatedState(speakers)
    val liveObjects by rememberUpdatedState(objects)
    val liveShape by rememberUpdatedState(shape)
    val liveAmount by rememberUpdatedState(roomAmount)
    val liveDecay by rememberUpdatedState(decaySeconds)
    val yaw = remember { mutableFloatStateOf(RoomCamera.START_YAW) }
    val accent = MaterialTheme.colorScheme.primary
    val heightTint = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val count = layout.size / AtmosNative.ROOM_FIELDS
    val labels = rememberRoomLabels(shape, onSurface)
    val scene = remember { RoomScene() }
    val loudest = remember { IntArray(RAYS) }
    val afterglow = remember { Afterglow() }
    val clock = rememberWaveClock(speakers != null && !tf.monochrome.android.ui.theme.reduceMotion())

    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // Sideways only: up and down still scrolls the page.
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        yaw.floatValue += drag / size.width * TURN
                    }
                }
                .drawBehind {
                    val room = liveShape
                    val amount = liveAmount.coerceIn(0f, 1f)
                    val levels = liveSpeakers
                    val o = liveObjects
                    val camera = RoomCamera(yaw.floatValue, room, size.width, size.height)

                    scene.clear()
                    var peak = 0f
                    for (i in 0 until count) {
                        val f = i * AtmosNative.ROOM_FIELDS
                        val glow = levels?.getOrNull(i)?.let(::atmosSpeakerGlow) ?: 0f
                        peak = maxOf(peak, glow)
                        val s = room.place(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                        val kind = when {
                            isLfe(layout, f) -> RoomScene.KIND_LFE
                            layout[f + 2] > 0f -> RoomScene.KIND_HEIGHT
                            else -> RoomScene.KIND_EAR
                        }
                        scene.addSpeaker(s[0], s[1], if (kind == RoomScene.KIND_LFE) LFE_HEIGHT else s[2], glow, kind)
                    }
                    if (o != null) {
                        for (k in 0 until o.count) {
                            if (o.isLfe(k)) continue  // the LFE has no place: its speaker shows it
                            val s = room.place(o.x(k), o.y(k), o.z(k))
                            scene.addObject(s[0], s[1], s[2], atmosObjectLevel(o.level(k)), high = o.z(k) > 0.25f)
                        }
                    }
                    // The waves: each loud speaker and, with some room, its image
                    // in every surface, which is that surface's reflection of it.
                    pickLoudest(levels, layout, count, loudest)
                    loudest.forEachIndexed { slot, i ->
                        if (i < 0) return@forEachIndexed
                        val f = i * AtmosNative.ROOM_FIELDS
                        val glow = atmosSpeakerGlow(levels!![i])
                        val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                        scene.setRay(slot, s[0], s[1], s[2], glow)
                        scene.addSource(s[0], s[1], s[2], glow)
                        if (amount > 0f) {
                            for (image in room.mirrorImages(s[0], s[1], s[2])) {
                                scene.addSource(image[0], image[1], image[2], glow * amount * REFLECTED)
                            }
                        }
                    }
                    for (src in room.sources) scene.addMark(src[0], src[1], src[2])

                    val held = afterglow.follow(peak, liveDecay)
                    val seconds = clock.longValue / 1e9
                    val phase = (2.0 * Math.PI * seconds * WAVE_SPEED / RoomShader.WAVELENGTH) % (2.0 * Math.PI)
                    shader.draw(
                        drawContext.canvas.nativeCanvas, size.width, size.height, camera, room, scene,
                        seconds = (seconds % 64.0).toFloat(),
                        phase = phase.toFloat(),
                        field = FIELD_ALPHA * scene.strongest(),
                        glow = amount * held,
                        primary = accent, tertiary = heightTint, ink = onSurface,
                    )

                    // Over it: each object's number beside it, and the room's measurements.
                    if (o != null) {
                        for (k in 0 until o.count) {
                            if (o.isLfe(k)) continue
                            val s = room.place(o.x(k), o.y(k), o.z(k))
                            val at = camera.project(s[0], s[1], s[2]) ?: continue
                            labels.numbers.getOrNull(k)?.let { label ->
                                drawText(label, topLeft = Offset(at.x + 9f * density, at.y - label.size.height / 2f))
                            }
                        }
                    }
                    if (labels.sizes.isNotEmpty()) {
                        val centre = camera.project(room.width / 2f, room.depth / 2f, 0f)
                        // Each along the floor edge nearer the camera, a little outside it.
                        fun beside(x: Float, y: Float, z: Float): Offset? {
                            val at = camera.project(x, y, z) ?: return null
                            val from = centre ?: return at
                            val dx = at.x - from.x
                            val dy = at.y - from.y
                            val far = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                            return Offset(at.x + dx / far * 16f * density, at.y + dy / far * 12f * density)
                        }
                        val nearY = if (camera.distanceTo(room.width / 2f, 0f, 0f) < camera.distanceTo(room.width / 2f, room.depth, 0f)) 0f else room.depth
                        val nearX = if (camera.distanceTo(0f, room.depth / 2f, 0f) < camera.distanceTo(room.width, room.depth / 2f, 0f)) 0f else room.width
                        labels.sizes.getOrNull(0)?.let { label -> beside(room.width / 2f, nearY, 0f)?.let { drawLabel(label, it, 0f, 0f) } }
                        labels.sizes.getOrNull(1)?.let { label -> beside(nearX, room.depth / 2f, 0f)?.let { drawLabel(label, it, 0f, 0f) } }
                        labels.sizes.getOrNull(2)?.let { label ->
                            // Up the corner that is furthest to the side on the page.
                            val z = room.height / 2f
                            var best: Offset? = null
                            var bestOut = -1f
                            for (cx in 0..1) for (cy in 0..1) {
                                val x = if (cx == 0) room.leanX(z) * room.width else room.width * (1f - room.leanX(z))
                                val y = if (cy == 0) room.leanY(z) * room.depth else room.depth * (1f - room.leanY(z))
                                val at = camera.project(x, y, z) ?: continue
                                val out = kotlin.math.abs(at.x - size.width / 2f)
                                if (out > bestOut) { bestOut = out; best = at }
                            }
                            best?.let {
                                val side = if (it.x >= size.width / 2f) 1f else -1f
                                drawLabel(label, it, side * 20f * density, 0f)
                            }
                        }
                    }
                },
        )
    }
}

/**
 * The room drawn on the canvas, for Android 12 and older and for a phone
 * whose driver refuses the shader: the same room and the same waves, as
 * lines and flat cells.
 */
@Composable
private fun AtmosRoomCanvas(

    layout: FloatArray,
    speakers: FloatArray?,
    objects: AtmosAudioProcessor.ObjectMeter?,
    shape: RoomShape,
    roomAmount: Float,
    decaySeconds: Float,
    modifier: Modifier = Modifier,
) {
    val liveSpeakers by rememberUpdatedState(speakers)
    val liveObjects by rememberUpdatedState(objects)
    val liveShape by rememberUpdatedState(shape)
    val liveAmount by rememberUpdatedState(roomAmount)
    val liveDecay by rememberUpdatedState(decaySeconds)
    val yaw = remember { mutableFloatStateOf(AtmosRoomView.START_YAW) }
    val accent = MaterialTheme.colorScheme.primary
    val heightTint = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val count = layout.size / AtmosNative.ROOM_FIELDS

    val labels = rememberRoomLabels(shape, onSurface)
    val numbers = labels.numbers
    val sizeLabels = labels.sizes
    // Far to near, so what is nearer is drawn over what is behind it.
    val order = remember { IntArray(MAX_ITEMS) }
    val depths = remember { FloatArray(MAX_ITEMS) }
    val loudest = remember { IntArray(RAYS) }
    val path = remember { Path() }
    val afterglow = remember { Afterglow() }
    val waves = speakers != null && !tf.monochrome.android.ui.theme.reduceMotion()
    val clock = rememberWaveClock(waves)
    val liveWaves by rememberUpdatedState(waves)
    val field = remember { WaveField() }

    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // Sideways only: up and down still scrolls the page.
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        yaw.floatValue += drag / size.width * TURN
                    }
                }
                .drawBehind {
                    val room = liveShape
                    val amount = liveAmount.coerceIn(0f, 1f)
                    val view = AtmosRoomView(yaw.floatValue, room)
                    // The whole room at any turn: its near corners come out
                    // 0.7 of a room to the side and 0.57 below the middle.
                    val unit = min(size.width * 0.54f, size.height * 0.84f)
                    val cx = size.width / 2f
                    val cy = size.height / 2f - 0.09f * unit
                    fun at(p: AtmosRoomView.Point) = Offset(cx + p.x * unit, cy - p.y * unit)
                    fun at(x: Float, y: Float, z: Float) = at(view.project(x, y, z))

                    val levels = liveSpeakers
                    // What the room still holds: the loudest speaker, falling
                    // away over the room's own decay.
                    var peak = 0f
                    for (i in 0 until count) levels?.getOrNull(i)?.let { peak = maxOf(peak, atmosSpeakerGlow(it)) }
                    val held = afterglow.follow(peak, liveDecay)

                    // The walls, lit by what the room holds; the floor and its grid.
                    // The walls go up through rings: the floor's outline, the
                    // same at ear height, then drawn in as they lean.
                    val heights = room.wallHeights()
                    val rings = heights.map { room.ring(it) }
                    val floorLine = rings.first()
                    val ceilingLine = rings.last()
                    val wallGlow = accent.copy(alpha = 0.16f * amount * held)
                    if (wallGlow.alpha > 0.004f) {
                        for (k in 0 until rings.size - 1) {
                            val low = rings[k]
                            val high = rings[k + 1]
                            for (i in low.indices) {
                                val j = (i + 1) % low.size
                                path.rewind()
                                at(low[i][0], low[i][1], heights[k]).let { path.moveTo(it.x, it.y) }
                                at(low[j][0], low[j][1], heights[k]).let { path.lineTo(it.x, it.y) }
                                at(high[j][0], high[j][1], heights[k + 1]).let { path.lineTo(it.x, it.y) }
                                at(high[i][0], high[i][1], heights[k + 1]).let { path.lineTo(it.x, it.y) }
                                path.close()
                                drawPath(path, wallGlow)
                            }
                        }
                    }
                    path.rewind()
                    floorLine.forEachIndexed { i, p ->
                        val o = at(p[0], p[1], 0f)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    path.close()
                    drawPath(path, onSurface.copy(alpha = 0.05f))
                    // A line every metre, so the room's size can be read off it.
                    val grid = onSurface.copy(alpha = 0.07f)
                    val inset = room.cornerRadius
                    var m = 1f
                    while (m < room.width - 0.01f) {
                        val cut = cornerCut(m, room.width, inset)
                        drawLine(grid, at(m, cut, 0f), at(m, room.depth - cut, 0f), strokeWidth = 1f)
                        m += 1f
                    }
                    m = 1f
                    while (m < room.depth - 0.01f) {
                        val cut = cornerCut(m, room.depth, inset)
                        drawLine(grid, at(cut, m, 0f), at(room.width - cut, m, 0f), strokeWidth = 1f)
                        m += 1f
                    }
                    val edge = outline.copy(alpha = 0.4f)
                    val faint = outline.copy(alpha = 0.16f)
                    for (i in floorLine.indices) {
                        val j = (i + 1) % floorLine.size
                        drawLine(edge, at(floorLine[i][0], floorLine[i][1], 0f), at(floorLine[j][0], floorLine[j][1], 0f), strokeWidth = 2f)
                        drawLine(
                            faint,
                            at(ceilingLine[i][0], ceilingLine[i][1], room.height),
                            at(ceilingLine[j][0], ceilingLine[j][1], room.height),
                            strokeWidth = 1.5f,
                        )
                    }
                    // A room with corners has four edges up them, bending where
                    // the walls do; a round room none. Where walls begin to
                    // lean, at ear height, a faint line says so.
                    if (floorLine.size == 4) {
                        for (k in 0 until rings.size - 1) {
                            for (i in floorLine.indices) {
                                drawLine(
                                    faint,
                                    at(rings[k][i][0], rings[k][i][1], heights[k]),
                                    at(rings[k + 1][i][0], rings[k + 1][i][1], heights[k + 1]),
                                    strokeWidth = 1.5f,
                                )
                            }
                        }
                    }
                    if (!room.upright) {
                        val earLine = rings[1]
                        for (i in earLine.indices) {
                            val j = (i + 1) % earLine.size
                            drawLine(
                                faint.copy(alpha = 0.09f),
                                at(earLine[i][0], earLine[i][1], heights[1]),
                                at(earLine[j][0], earLine[j][1], heights[1]),
                                strokeWidth = 1f,
                            )
                        }
                    }
                    // The screen, on the wall ahead, leaning as the wall does.
                    path.rewind()
                    val screenLow = 0.15f * room.height
                    val screenHigh = 0.8f * room.height
                    fun onFrontWall(fromSide: Float, z: Float): Offset {
                        val side = maxOf(fromSide, (room.leanX(z) + 0.04f) * room.width, inset)
                        return at(side, room.leanY(z) * room.depth, z)
                    }
                    fun onFrontWallRight(fromSide: Float, z: Float): Offset {
                        val side = maxOf(fromSide, (room.leanX(z) + 0.04f) * room.width, inset)
                        return at(room.width - side, room.leanY(z) * room.depth, z)
                    }
                    val screenIn = 0.12f * room.width
                    onFrontWall(screenIn, screenLow).let { path.moveTo(it.x, it.y) }
                    onFrontWallRight(screenIn, screenLow).let { path.lineTo(it.x, it.y) }
                    onFrontWallRight(screenIn, screenHigh).let { path.lineTo(it.x, it.y) }
                    onFrontWall(screenIn, screenHigh).let { path.lineTo(it.x, it.y) }
                    path.close()
                    drawPath(path, onSurface.copy(alpha = 0.05f))
                    drawPath(path, faint, style = Stroke(width = 1.5f))
                    // Its measurements, along the near floor edges and up the near corner.
                    sizeLabels.getOrNull(0)?.let { drawLabel(it, at(room.width / 2f, room.depth, 0f), 0f, 14f) }
                    sizeLabels.getOrNull(1)?.let { drawLabel(it, at(room.width, room.depth / 2f, 0f), 26f, 4f) }
                    sizeLabels.getOrNull(2)?.let {
                        val z = room.height / 2f
                        drawLabel(it, at(room.width * (1f - room.leanX(z)), room.depth * (1f - room.leanY(z)), z), 26f, 0f)
                    }

                    // The sound's way to the listener, for the loudest speakers:
                    // straight, and by way of each surface it turns on.
                    val ear = floatArrayOf(room.listenerX, room.listenerY, room.earHeight)
                    val seat = at(ear[0], ear[1], ear[2])
                    pickLoudest(levels, layout, count, loudest)
                    if (liveWaves) {
                        // The waves in the room: each loud speaker, and with some
                        // room its image in every surface, which is its reflection.
                        field.clear()
                        for (i in loudest) {
                            if (i < 0) continue
                            val f = i * AtmosNative.ROOM_FIELDS
                            val glow = atmosSpeakerGlow(levels!![i])
                            val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                            field.add(s[0], s[1], s[2], glow)
                            if (amount > 0f) {
                                for (image in room.mirrorImages(s[0], s[1], s[2])) {
                                    field.add(image[0], image[1], image[2], glow * amount * REFLECTED)
                                }
                            }
                        }
                        drawWaveField(field, view, room, clock.longValue / 1e9f, accent, ::at)
                    }
                    for (i in loudest) {
                        if (i < 0) continue
                        val f = i * AtmosNative.ROOM_FIELDS
                        val glow = atmosSpeakerGlow(levels!![i])
                        val tint = if (layout[f + 2] > 0f) heightTint else accent
                        val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                        val from = at(s[0], s[1], s[2])
                        drawLine(tint.copy(alpha = 0.18f * glow), from, seat, strokeWidth = 5f)
                        drawLine(tint.copy(alpha = 0.75f * glow), from, seat, strokeWidth = 1.5f)
                        if (amount <= 0f) continue
                        val direct = kotlin.math.sqrt(
                            (s[0] - ear[0]) * (s[0] - ear[0]) + (s[1] - ear[1]) * (s[1] - ear[1]) +
                                (s[2] - ear[2]) * (s[2] - ear[2])
                        )
                        for (r in room.firstReflections(s[0], s[1], s[2])) {
                            // Fainter the longer its way round is than the straight one.
                            val strength = glow * amount * (direct / r.path).coerceIn(0f, 1f)
                            if (strength < 0.03f) continue
                            val p = view.project(r.x, r.y, r.z)
                            val spot = at(p)
                            val line = tint.copy(alpha = 0.42f * strength)
                            drawLine(line, from, spot, strokeWidth = 1.2f)
                            drawLine(line, spot, seat, strokeWidth = 1.2f)
                            val rad = unit * 0.011f * p.scale
                            drawCircle(tint.copy(alpha = 0.30f * strength), rad * 3.2f, spot)
                            drawCircle(tint.copy(alpha = 0.95f * strength), rad, spot)
                        }
                    }

                    // The loudspeakers the room was measured with.
                    for (src in room.sources) {
                        val p = view.project(src[0], src[1], src[2])
                        val o = at(p)
                        val d = unit * 0.014f * p.scale
                        drawLine(onSurface.copy(alpha = 0.16f), o, at(src[0], src[1], 0f), strokeWidth = 1f)
                        path.rewind()
                        path.moveTo(o.x, o.y - d)
                        path.lineTo(o.x + d, o.y)
                        path.lineTo(o.x, o.y + d)
                        path.lineTo(o.x - d, o.y)
                        path.close()
                        drawPath(path, onSurface.copy(alpha = 0.6f), style = Stroke(width = 1.5f))
                    }

                    val o = liveObjects
                    val objectCount = min(o?.count ?: 0, MAX_ITEMS - count)
                    val items = count + objectCount
                    for (i in 0 until items) {
                        order[i] = i
                        depths[i] = if (i < count) {
                            val f = i * AtmosNative.ROOM_FIELDS
                            val s = room.place(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                            view.project(s[0], s[1], if (isLfe(layout, f)) LFE_HEIGHT else s[2]).depth
                        } else {
                            val k = i - count
                            val s = room.place(o!!.x(k), o.y(k), o.z(k))
                            view.project(s[0], s[1], s[2]).depth
                        }
                    }
                    for (i in 1 until items) {  // a handful of items: insertion sort
                        val v = order[i]
                        var j = i - 1
                        while (j >= 0 && depths[order[j]] < depths[v]) { order[j + 1] = order[j]; j-- }
                        order[j + 1] = v
                    }
                    for (n in 0 until items) {
                        val i = order[n]
                        if (i < count) {
                            val f = i * AtmosNative.ROOM_FIELDS
                            val lfe = isLfe(layout, f)
                            val high = layout[f + 2] > 0f
                            val s = room.place(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                            val p = view.project(s[0], s[1], if (lfe) LFE_HEIGHT else s[2])
                            val glow = levels?.getOrNull(i)?.let(::atmosSpeakerGlow) ?: 0f
                            drawRoomSpeaker(at(p), unit * p.scale, if (high) heightTint else accent, glow, lfe)
                        } else {
                            val k = i - count
                            if (o!!.isLfe(k)) continue  // the LFE has no place: its speaker shows it
                            val s = room.place(o.x(k), o.y(k), o.z(k))
                            val level = atmosObjectLevel(o.level(k))
                            val p = view.project(s[0], s[1], s[2])
                            val pos = at(p)
                            val tint = if (o.z(k) > 0.25f) heightTint else onSurface
                            // A line down to the floor says how high it is.
                            val foot = at(s[0], s[1], 0f)
                            drawLine(tint.copy(alpha = 0.2f), pos, foot, strokeWidth = 1.2f)
                            drawCircle(tint.copy(alpha = 0.22f), unit * 0.007f * p.scale, foot)
                            val r = unit * (0.02f + 0.022f * level) * p.scale
                            drawCircle(tint.copy(alpha = 0.16f * level), r * 2.3f, pos)
                            drawCircle(tint.copy(alpha = 0.4f + 0.6f * level), r, pos)
                            numbers.getOrNull(k)?.let { label ->
                                drawText(label, topLeft = Offset(pos.x + r + 2f, pos.y - label.size.height / 2f))
                            }
                        }
                    }
                    // The listener: a head at ear height over its place on the floor.
                    val headAt = view.project(ear[0], ear[1], ear[2])
                    val head = unit * 0.024f * headAt.scale
                    val feet = at(ear[0], ear[1], 0f)
                    drawLine(onSurface.copy(alpha = 0.3f), seat, feet, strokeWidth = 1.5f)
                    drawOval(
                        onSurface.copy(alpha = 0.16f),
                        topLeft = Offset(feet.x - head, feet.y - head * 0.4f),
                        size = androidx.compose.ui.geometry.Size(head * 2f, head * 0.8f),
                    )
                    drawCircle(onSurface.copy(alpha = 0.85f), head, seat)
                },
        )
    }
}

/** The sources of the wave field: where each is, in metres, and how strong. */
private class WaveField {
    val sources = FloatArray(MAX_SOURCES * 4)
    var count = 0
    val points = FloatArray((FIELD_CELLS + 1) * (FIELD_CELLS + 1) * 2)  // a slice's corners, on the page
    val path = Path()
    val paint = androidx.compose.ui.graphics.Paint().apply { isAntiAlias = false }

    fun clear() { count = 0 }

    fun add(x: Float, y: Float, z: Float, strength: Float) {
        if (count == MAX_SOURCES || strength <= 0f) return
        val at = count * 4
        sources[at] = x; sources[at + 1] = y; sources[at + 2] = z; sources[at + 3] = strength
        count++
    }

    /**
     * The field at a point, -1..1: every source's wave as it is there at
     * [phase], weaker the further it has come, over the most they could add
     * up to. 1 is all of them in step, -1 all of them against.
     */
    fun at(x: Float, y: Float, z: Float, phase: Float): Float {
        var sum = 0f
        var most = 0f
        for (i in 0 until count) {
            val s = i * 4
            val dx = x - sources[s]
            val dy = y - sources[s + 1]
            val dz = z - sources[s + 2]
            val far = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val reach = sources[s + 3] / (NEAR + far)
            sum += reach * cos(TWO_PI * far / WAVELENGTH - phase)
            most += reach
        }
        return if (most > 0f) sum / most else 0f
    }

    /** The strongest source, 0..1: how bright the whole field is. */
    fun strongest(): Float {
        var top = 0f
        for (i in 0 until count) top = maxOf(top, sources[i * 4 + 3])
        return top.coerceIn(0f, 1f)
    }

    private companion object {
        const val MAX_SOURCES = RAYS * 7   // each speaker and its six images
        const val NEAR = 0.6f              // metres: keeps a source from blinding the cells next to it
        const val TWO_PI = 6.2831855f
    }
}

/**
 * The waves in the room, as a faint glow on two slices of it: one lying flat
 * at ear height, one standing across the room at the listener's depth. Each
 * slice is a grid of cells; a cell is lit by how far the waves that reach it
 * are in step (see [WaveField.at]), so the picture is the interference of the
 * speakers that play with the walls' reflections of them. The waves are drawn
 * a few hundred times slower and longer than sound, which would cross the
 * room in the time of one frame.
 */
private fun DrawScope.drawWaveField(
    field: WaveField,
    view: AtmosRoomView,
    room: RoomShape,
    seconds: Float,
    tint: Color,
    at: (AtmosRoomView.Point) -> Offset,
) {
    if (field.count == 0) return
    val brightness = FIELD_ALPHA * field.strongest()
    if (brightness < 0.01f) return
    val phase = 6.2831855f * seconds * WAVE_SPEED / WAVELENGTH
    val n = FIELD_CELLS
    for (slice in 0..1) {
        // A slice's corner (u, v): flat, across and along the room at ear
        // height; upright, across the room and up it at the listener's depth.
        val flat = slice == 0
        val alongV = if (flat) room.depth else room.height
        for (v in 0..n) for (u in 0..n) {
            val x = u * room.width / n
            val along = v * alongV / n
            val o = at(if (flat) view.project(x, along, room.earHeight) else view.project(x, room.listenerY, along))
            val i = (v * (n + 1) + u) * 2
            field.points[i] = o.x
            field.points[i + 1] = o.y
        }
        for (v in 0 until n) for (u in 0 until n) {
            val x = (u + 0.5f) * room.width / n
            val along = (v + 0.5f) * alongV / n
            val y = if (flat) along else room.listenerY
            val z = if (flat) room.earHeight else along
            // Only what is inside the walls where they are at that height.
            val lx = room.leanX(z) * room.width
            val ly = room.leanY(z) * room.depth
            if (x < lx || x > room.width - lx || y < ly || y > room.depth - ly) continue
            // Many waves are seldom all in step: a third of that is already a crest.
            val crest = (field.at(x, y, z, phase) * FIELD_GAIN).coerceAtMost(1f)
            if (crest <= 0.05f) continue
            val alpha = brightness * crest * kotlin.math.sqrt(crest)
            if (alpha < 0.012f) continue
            val p = field.points
            val i00 = (v * (n + 1) + u) * 2
            val i10 = i00 + 2
            val i01 = ((v + 1) * (n + 1) + u) * 2
            val i11 = i01 + 2
            field.path.rewind()
            field.path.moveTo(p[i00], p[i00 + 1])
            field.path.lineTo(p[i10], p[i10 + 1])
            field.path.lineTo(p[i11], p[i11 + 1])
            field.path.lineTo(p[i01], p[i01 + 1])
            field.path.close()
            // Not anti-aliased: softened edges of cells side by side would show as a grid.
            field.paint.color = tint.copy(alpha = alpha)
            drawContext.canvas.drawPath(field.path, field.paint)
        }
    }
}

/** What the room still holds of the loudest sound, for the walls' glow: it falls 60 dB in the room's decay. */
private class Afterglow {
    private var held = 0f
    private var last = 0L

    /** [peak] is the loudest speaker now, 0..1 over the meters' 60 dB. */
    fun follow(peak: Float, decaySeconds: Float): Float {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.5f)
        last = now
        held = maxOf(peak, held - dt / decaySeconds.coerceAtLeast(0.1f))
        return held.coerceIn(0f, 1f)
    }
}

/** How far in from a rounded corner a grid line [m] metres along a side of [length] starts. */
private fun cornerCut(m: Float, length: Float, radius: Float): Float {
    if (radius <= 0f) return 0f
    val d = if (m < radius) radius - m else if (m > length - radius) m - (length - radius) else return 0f
    return radius - kotlin.math.sqrt((radius * radius - d * d).coerceAtLeast(0f))
}

/** The [RAYS] speakers driven hardest (not the LFE, and only ones that are heard) into [into]; -1 for none. */
private fun pickLoudest(levels: FloatArray?, layout: FloatArray, count: Int, into: IntArray) {
    into.fill(-1)
    if (levels == null) return
    for (i in 0 until min(count, levels.size)) {
        val f = i * AtmosNative.ROOM_FIELDS
        if (isLfe(layout, f) || atmosSpeakerGlow(levels[i]) < 0.15f) continue
        var slot = -1
        for (k in into.indices) {
            if (into[k] < 0) { slot = k; break }
            if (slot < 0 || kotlin.math.abs(levels[into[k]]) < kotlin.math.abs(levels[into[slot]])) slot = k
        }
        if (into[slot] < 0 || kotlin.math.abs(levels[i]) > kotlin.math.abs(levels[into[slot]])) into[slot] = i
    }
}

private fun DrawScope.drawLabel(label: androidx.compose.ui.text.TextLayoutResult, at: Offset, dx: Float, dy: Float) {
    drawText(label, topLeft = Offset(at.x + dx - label.size.width / 2f, at.y + dy - label.size.height / 2f))
}

/** The LFE has no place of its own in the mix: it stands on the floor under the screen. */
private fun speakerX(layout: FloatArray, f: Int) = if (isLfe(layout, f)) 0.36f else layout[f]
private fun speakerY(layout: FloatArray, f: Int) = if (isLfe(layout, f)) 0f else layout[f + 1]

/** Whether the speaker whose fields start at [f] is the LFE. */
private fun isLfe(layout: FloatArray, f: Int) = layout[f + 3] == 1f

/** One speaker: a ring that is always there, filled and haloed as it plays. */
private fun DrawScope.drawRoomSpeaker(pos: Offset, unit: Float, tint: Color, glow: Float, lfe: Boolean) {
    val r = unit * (if (lfe) 0.021f else 0.016f)
    if (glow > 0.02f) {
        drawCircle(tint.copy(alpha = 0.10f + 0.25f * glow), r * (1.6f + 2.2f * glow), pos)
        drawCircle(tint.copy(alpha = 0.25f + 0.75f * glow), r, pos)
    }
    drawCircle(tint.copy(alpha = 0.55f), r, pos, style = Stroke(width = if (lfe) 3f else 2f))
}

private const val TURN = 3.2f          // radians a drag across the whole room turns it
private const val MAX_NUMBERS = 32
private const val MAX_ITEMS = AtmosNative.ROOM_MAX_SPEAKERS + 32
private const val RAYS = 3             // speakers whose way to the listener is drawn
private const val FIELD_CELLS = 26     // cells across a slice of the wave field
private const val FIELD_ALPHA = 0.42f  // the field at its brightest: a glow, never a surface
private const val FIELD_GAIN = 2.6f    // how soon waves that are partly in step count as a crest
private const val WAVELENGTH = 1.3f    // metres between the canvas field's wave fronts (the shader's: RoomShader.WAVELENGTH)
private const val WAVE_SPEED = 1.1f    // metres a second on the page: sound itself would cross the room in a frame
private const val REFLECTED = 0.65f    // what a wall gives back of a wave, at Room 100%
private const val LFE_HEIGHT = 0.25f   // metres: the LFE stands on the floor
