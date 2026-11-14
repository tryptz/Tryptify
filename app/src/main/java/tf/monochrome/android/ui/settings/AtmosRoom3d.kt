package tf.monochrome.android.ui.settings

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableFloatState
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
 * loudest speakers a ray runs to the listener. The sound itself is grain:
 * fine specks in the room's air, dense round a speaker that plays and
 * reaching further the harder it is driven ([RoomGrain]). With [roomAmount]
 * of room there is grain too where the loudest speakers' sound turns on a
 * wall towards the listener, and thinly through the whole room for what it
 * still holds, which dies away over [decaySeconds] as the room's sound does.
 * The grain follows each speaker's level averaged over a second or so
 * ([SlowLevels]), so it swells and thins and does not flicker with the meter.
 *
 * It is drawn by the GPU ([RoomShader]: a camera, the room's surfaces, grain
 * in the air) on Android 13 and up, and on the canvas ([AtmosRoomCanvas]) where that
 * shader cannot run. Drag sideways to walk round it. [layout] is
 * [AtmosNative.nativeRoomLayout]'s, [speakers] the processor's levels in the
 * same order (null: nothing plays through the room).
 *
 * With [sound] off the sound is not drawn: no grain and no rays, and nothing
 * moves between the meter's readings; the speakers still light as they play
 * and the objects are where the mix puts them. [compact] is the room as a
 * small picture: no numbers, no measurements, and it is not turned by a
 * drag. [yaw] is how far it is turned, the page's to keep when the room is
 * drawn in two places.
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
    sound: Boolean = true,
    compact: Boolean = false,
    yaw: MutableFloatState = remember { mutableFloatStateOf(RoomCamera.START_YAW) },
) {
    val shader = rememberRoomShader()
    val options = RoomViewOptions(sound, compact, yaw)
    if (shader != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        AtmosRoomGpu(shader, layout, speakers, objects, shape, roomAmount, decaySeconds, options, modifier)
    } else {
        AtmosRoomCanvas(layout, speakers, objects, shape, roomAmount, decaySeconds, options, modifier)
    }
}

/** How the room is shown, whichever way it is drawn: see [AtmosRoom3d]. */
private class RoomViewOptions(val sound: Boolean, val compact: Boolean, val yaw: MutableFloatState)

/** Turns the room with a sideways drag (up and down still scrolls the page); a [compact] room is not turned. */
private fun Modifier.turnsRoom(view: RoomViewOptions): Modifier =
    if (view.compact) {
        this
    } else {
        pointerInput(view.yaw) {
            detectHorizontalDragGestures { change, drag ->
                change.consume()
                view.yaw.floatValue += drag / size.width * TURN
            }
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
 * The grain's clock: the time since it started, in nanoseconds, thirty times
 * a second while [running], and held still otherwise. Nothing is drawn from
 * the time itself: reading it where the room is drawn is what draws the room
 * again between the meter's ten readings a second, so the averaged levels
 * ([SlowLevels]) move smoothly; nothing else is redrawn by it.
 */
@Composable
private fun rememberGrainClock(running: Boolean): androidx.compose.runtime.LongState {
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
    options: RoomViewOptions,
    modifier: Modifier = Modifier,
) {
    val liveSpeakers by rememberUpdatedState(speakers)
    val liveObjects by rememberUpdatedState(objects)
    val liveShape by rememberUpdatedState(shape)
    val liveAmount by rememberUpdatedState(roomAmount)
    val liveDecay by rememberUpdatedState(decaySeconds)
    val liveOptions by rememberUpdatedState(options)
    val yaw = options.yaw
    val accent = MaterialTheme.colorScheme.primary
    val heightTint = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val count = layout.size / AtmosNative.ROOM_FIELDS
    val labels = rememberRoomLabels(shape, onSurface)
    val scene = remember { RoomScene() }
    val loudest = remember { IntArray(RAYS) }
    val afterglow = remember { Afterglow() }
    val glows = remember { FloatArray(AtmosNative.ROOM_MAX_SPEAKERS) }
    val air = remember { SlowLevels() }
    val clock = rememberGrainClock(options.sound && speakers != null && !tf.monochrome.android.ui.theme.reduceMotion())

    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .turnsRoom(options)
                .drawBehind {
                    val room = liveShape
                    val amount = liveAmount.coerceIn(0f, 1f)
                    val levels = liveSpeakers
                    val o = liveObjects
                    val sound = liveOptions.sound
                    val camera = RoomCamera(yaw.floatValue, room, size.width, size.height)
                    clock.longValue  // read: see rememberGrainClock

                    scene.clear()
                    air.tick(playing = levels != null)
                    var peak = 0f
                    for (i in 0 until count) {
                        val f = i * AtmosNative.ROOM_FIELDS
                        val glow = levels?.getOrNull(i)?.let(::atmosSpeakerGlow) ?: 0f
                        glows[i] = glow
                        peak = maxOf(peak, glow)
                        val s = room.place(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                        val kind = when {
                            isLfe(layout, f) -> RoomScene.KIND_LFE
                            layout[f + 2] > 0f -> RoomScene.KIND_HEIGHT
                            else -> RoomScene.KIND_EAR
                        }
                        scene.addSpeaker(
                            s[0], s[1], if (kind == RoomScene.KIND_LFE) LFE_HEIGHT else s[2],
                            level = glow, air = if (sound) air.follow(i, glow) else 0f, kind = kind,
                        )
                    }
                    if (o != null) {
                        for (k in 0 until o.count) {
                            if (o.isLfe(k)) continue  // the LFE has no place: its speaker shows it
                            val s = room.place(o.x(k), o.y(k), o.z(k))
                            scene.addObject(s[0], s[1], s[2], atmosObjectLevel(o.level(k)), high = o.z(k) > 0.25f)
                        }
                    }
                    // The loudest speakers now: their ray to the listener.
                    if (sound) {
                        pickLoudest(glows, layout, count, loudest)
                        loudest.forEachIndexed { slot, i ->
                            if (i < 0) return@forEachIndexed
                            val f = i * AtmosNative.ROOM_FIELDS
                            val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                            scene.setRay(slot, s[0], s[1], s[2], glows[i])
                        }
                    }
                    // And the loudest of late: with some room, grain where their
                    // sound turns on a surface towards the listener.
                    if (sound && amount > 0f) {
                        pickLoudest(air.levels, layout, count, loudest)
                        for (i in loudest) {
                            if (i < 0) continue
                            val f = i * AtmosNative.ROOM_FIELDS
                            val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                            for (r in room.firstReflections(s[0], s[1], s[2])) {
                                scene.addSource(r.x, r.y, r.z, air.levels[i] * amount * room.directOver(s, r))
                            }
                        }
                    }
                    for (src in room.sources) scene.addMark(src[0], src[1], src[2])

                    val held = afterglow.follow(peak, liveDecay)
                    shader.draw(
                        drawContext.canvas.nativeCanvas, size.width, size.height, camera, room, scene,
                        glow = if (sound) amount * held else 0f,
                        primary = accent, tertiary = heightTint, ink = onSurface,
                    )

                    // Over it: each object's number beside it, and the room's measurements.
                    if (liveOptions.compact) return@drawBehind
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
 * whose driver refuses the shader: the same room and the same grain, as
 * lines and points.
 */
@Composable
private fun AtmosRoomCanvas(
    layout: FloatArray,
    speakers: FloatArray?,
    objects: AtmosAudioProcessor.ObjectMeter?,
    shape: RoomShape,
    roomAmount: Float,
    decaySeconds: Float,
    options: RoomViewOptions,
    modifier: Modifier = Modifier,
) {
    val liveSpeakers by rememberUpdatedState(speakers)
    val liveObjects by rememberUpdatedState(objects)
    val liveShape by rememberUpdatedState(shape)
    val liveAmount by rememberUpdatedState(roomAmount)
    val liveDecay by rememberUpdatedState(decaySeconds)
    val liveOptions by rememberUpdatedState(options)
    val yaw = options.yaw
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
    val glows = remember { FloatArray(AtmosNative.ROOM_MAX_SPEAKERS) }
    val air = remember { SlowLevels() }
    val grain = remember { CanvasGrain() }
    val clock = rememberGrainClock(options.sound && speakers != null && !tf.monochrome.android.ui.theme.reduceMotion())

    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .turnsRoom(options)
                .drawBehind {
                    val room = liveShape
                    val amount = liveAmount.coerceIn(0f, 1f)
                    val sound = liveOptions.sound
                    val compact = liveOptions.compact
                    val view = AtmosRoomView(yaw.floatValue, room)
                    clock.longValue  // read: see rememberGrainClock
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
                    air.tick(playing = levels != null)
                    for (i in 0 until count) {
                        glows[i] = levels?.getOrNull(i)?.let(::atmosSpeakerGlow) ?: 0f
                        air.follow(i, glows[i])
                        peak = maxOf(peak, glows[i])
                    }
                    val held = afterglow.follow(peak, liveDecay)

                    // The floor and its grid, and the walls: they go up through
                    // rings, the floor's outline, the same at ear height, then
                    // drawn in as they lean.
                    val heights = room.wallHeights()
                    val rings = heights.map { room.ring(it) }
                    val floorLine = rings.first()
                    val ceilingLine = rings.last()
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
                    if (!compact) {
                        sizeLabels.getOrNull(0)?.let { drawLabel(it, at(room.width / 2f, room.depth, 0f), 0f, 14f) }
                        sizeLabels.getOrNull(1)?.let { drawLabel(it, at(room.width, room.depth / 2f, 0f), 26f, 4f) }
                        sizeLabels.getOrNull(2)?.let {
                            val z = room.height / 2f
                            drawLabel(it, at(room.width * (1f - room.leanX(z)), room.depth * (1f - room.leanY(z)), z), 26f, 0f)
                        }
                    }

                    // The sound's way to the listener, for the loudest speakers:
                    // straight, and by way of each surface it turns on.
                    val ear = floatArrayOf(room.listenerX, room.listenerY, room.earHeight)
                    val seat = at(ear[0], ear[1], ear[2])
                    // The sound, as grain: round each speaker that plays, where the
                    // loudest of late turn on a surface, and what the room holds.
                    grain.clear(RoomGrain.reach(room), if (sound) amount * held else 0f)
                    if (sound) for (i in 0 until count) {
                        val f = i * AtmosNative.ROOM_FIELDS
                        val s = room.place(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                        grain.addSpeaker(s[0], s[1], if (isLfe(layout, f)) LFE_HEIGHT else s[2], air.levels[i], high = layout[f + 2] > 0f)
                    }
                    if (sound && amount > 0f) {
                        pickLoudest(air.levels, layout, count, loudest)
                        for (i in loudest) {
                            if (i < 0) continue
                            val f = i * AtmosNative.ROOM_FIELDS
                            val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                            for (r in room.firstReflections(s[0], s[1], s[2])) {
                                grain.addTurn(r.x, r.y, r.z, air.levels[i] * amount * room.directOver(s, r))
                            }
                        }
                    }
                    grain.draw(drawContext.canvas.nativeCanvas, room, unit * 0.004f, accent, heightTint) { x, y, z -> at(x, y, z) }

                    if (sound) pickLoudest(glows, layout, count, loudest) else loudest.fill(-1)
                    for (i in loudest) {
                        if (i < 0) continue
                        val f = i * AtmosNative.ROOM_FIELDS
                        val glow = glows[i]
                        val tint = if (layout[f + 2] > 0f) heightTint else accent
                        val s = room.place(layout[f], layout[f + 1], layout[f + 2])
                        val from = at(s[0], s[1], s[2])
                        drawLine(tint.copy(alpha = 0.18f * glow), from, seat, strokeWidth = 5f)
                        drawLine(tint.copy(alpha = 0.75f * glow), from, seat, strokeWidth = 1.5f)
                        if (amount <= 0f) continue
                        for (r in room.firstReflections(s[0], s[1], s[2])) {
                            val strength = glow * amount * room.directOver(s, r)
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
                            if (!compact) numbers.getOrNull(k)?.let { label ->
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

/**
 * Each speaker's level as the grain shows it: averaged over a second or so,
 * quicker up than down, so a cloud swells with a passage and thins after it
 * and does not jump with every reading of the meter.
 */
private class SlowLevels {
    /** Each speaker's averaged level, 0..1, in the layout's order. */
    val levels = FloatArray(AtmosNative.ROOM_MAX_SPEAKERS)
    private var last = 0L
    private var up = 1f
    private var down = 1f

    /** Once a frame, before [follow]. With nothing [playing] there is no next frame to settle in: all is still at once. */
    fun tick(playing: Boolean) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.25f)
        last = now
        if (!playing) levels.fill(0f)
        up = 1f - kotlin.math.exp(-dt / RISE)
        down = 1f - kotlin.math.exp(-dt / FALL)
    }

    /** Moves speaker [i]'s average towards [level], and gives it. */
    fun follow(i: Int, level: Float): Float {
        if (i !in levels.indices) return 0f
        levels[i] += (level - levels[i]) * (if (level > levels[i]) up else down)
        return levels[i]
    }

    private companion object {
        const val RISE = 0.45f  // seconds to come most of the way up to a louder level
        const val FALL = 1.1f   // and down to a quieter one
    }
}

/**
 * The grain on the canvas: specks that stand at fixed places in the room,
 * each with a rank; a speck is drawn when there is more sound where it is
 * than its rank. What "sound" is, is the shader's `sound` again, with
 * [RoomGrain]'s measures.
 */
private class CanvasGrain {
    // Each speck: where in the room, 0..1 of its width, depth and height, and its rank.
    private val specks = FloatArray(SPECKS * 4).also { all ->
        val random = java.util.Random(28)
        for (i in all.indices) all[i] = random.nextFloat()
    }
    // What sounds: x, y, z, how much, and one over the square of its reach.
    private val sounds = FloatArray(MAX_SOUNDS * 5)
    private val high = BooleanArray(MAX_SOUNDS)
    private var count = 0
    private var reach = 1f
    private var held = 0f
    private val low = FloatArray(SPECKS * 2)       // the specks to draw in the speakers' colour, on the page
    private val above = FloatArray(SPECKS * 2)     // and in the height speakers'
    private val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /** A new frame: a full-level speaker's [reach] in this room, and how much the room [held]s, 0..1. */
    fun clear(reach: Float, held: Float) {
        count = 0
        this.reach = reach
        this.held = held * RoomGrain.HELD
    }

    fun addSpeaker(x: Float, y: Float, z: Float, level: Float, high: Boolean) {
        if (level > 0.01f) add(x, y, z, level * kotlin.math.sqrt(level), reach * (RoomGrain.NEAR + level), high)
    }

    /** A spot where sound turns on a surface towards the listener. */
    fun addTurn(x: Float, y: Float, z: Float, strength: Float) {
        if (strength > 0.01f) add(x, y, z, strength, reach * RoomGrain.TURNED, false)
    }

    private fun add(x: Float, y: Float, z: Float, amount: Float, reach: Float, high: Boolean) {
        if (count == MAX_SOUNDS) return
        val at = count * 5
        sounds[at] = x; sounds[at + 1] = y; sounds[at + 2] = z; sounds[at + 3] = amount
        sounds[at + 4] = 1f / (reach * reach)
        this.high[count++] = high
    }

    fun draw(
        canvas: android.graphics.Canvas,
        room: RoomShape,
        size: Float,
        tint: Color,
        highTint: Color,
        at: (Float, Float, Float) -> Offset,
    ) {
        if (count == 0 && held <= 0f) return
        var lows = 0
        var highs = 0
        for (i in 0 until SPECKS) {
            val f = i * 4
            val x = specks[f] * room.width
            val y = specks[f + 1] * room.depth
            val z = specks[f + 2] * room.height
            // Only what is inside the walls where they are at that height.
            val lx = room.leanX(z) * room.width
            val ly = room.leanY(z) * room.depth
            if (x < lx || x > room.width - lx || y < ly || y > room.depth - ly) continue
            var sum = held
            var fromHigh = 0f
            for (k in 0 until count) {
                val s = k * 5
                val dx = x - sounds[s]
                val dy = y - sounds[s + 1]
                val dz = z - sounds[s + 2]
                val far = (dx * dx + dy * dy + dz * dz) * sounds[s + 4]
                if (far > 12f) continue
                val w = sounds[s + 3] * kotlin.math.exp(-far)
                sum += w
                if (high[k]) fromHigh += w
            }
            if (sum <= 0.9f * specks[f + 3]) continue  // the shader's rank
            val o = at(x, y, z)
            if (fromHigh > sum / 2f) {
                above[highs++] = o.x; above[highs++] = o.y
            } else {
                low[lows++] = o.x; low[lows++] = o.y
            }
        }
        paint.strokeWidth = size
        paint.color = android.graphics.Color.argb(
            (RoomGrain.COVER * 255).toInt(), (tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt(),
        )
        canvas.drawPoints(low, 0, lows, paint)
        paint.color = android.graphics.Color.argb(
            (RoomGrain.COVER * 255).toInt(),
            (highTint.red * 255).toInt(), (highTint.green * 255).toInt(), (highTint.blue * 255).toInt(),
        )
        canvas.drawPoints(above, 0, highs, paint)
    }

    private companion object {
        const val SPECKS = 6000
        const val MAX_SOUNDS = AtmosNative.ROOM_MAX_SPEAKERS + RAYS * 6   // every speaker, and each loud one's six turns
    }
}

/** What the room still holds of the loudest sound, for the grain through it and the canvas's walls: it falls 60 dB in the room's decay. */
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

/**
 * The [RAYS] speakers with the most of [glows] (each 0..1, in the layout's
 * order) into [into], -1 for none: not the LFE, and only ones that are heard.
 */
private fun pickLoudest(glows: FloatArray, layout: FloatArray, count: Int, into: IntArray) {
    into.fill(-1)
    for (i in 0 until min(count, glows.size)) {
        val f = i * AtmosNative.ROOM_FIELDS
        if (isLfe(layout, f) || glows[i] < 0.15f) continue
        var slot = -1
        for (k in into.indices) {
            if (into[k] < 0) { slot = k; break }
            if (slot < 0 || glows[into[k]] < glows[into[slot]]) slot = k
        }
        if (into[slot] < 0 || glows[i] > glows[into[slot]]) into[slot] = i
    }
}

/**
 * How much of a speaker's level a first reflection [r] of it keeps: less the
 * longer its way round is than the straight one from the speaker at [s].
 */
private fun RoomShape.directOver(s: FloatArray, r: RoomShape.Reflection): Float {
    val dx = s[0] - listenerX
    val dy = s[1] - listenerY
    val dz = s[2] - earHeight
    return (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) / r.path).coerceIn(0f, 1f)
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
private const val LFE_HEIGHT = 0.25f   // metres: the LFE stands on the floor
