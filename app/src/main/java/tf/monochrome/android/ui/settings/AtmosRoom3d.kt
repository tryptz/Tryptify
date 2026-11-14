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
 * loudest speakers a ray runs to the listener, and with [roomAmount] of room
 * so do their first reflections, each by way of the spot on the wall where
 * it turns; the walls themselves glow with what the room still holds, and
 * that glow dies away over [decaySeconds], as the room's sound does.
 *
 * Drag sideways to walk round it. [layout] is
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

    val textMeasurer = rememberTextMeasurer()
    val numberStyle = remember(onSurface) {
        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = onSurface.copy(alpha = 0.8f))
    }
    // The objects' numbers, measured once.
    val numbers = remember(numberStyle, textMeasurer) {
        List(MAX_NUMBERS) { textMeasurer.measure((it + 1).toString(), style = numberStyle) }
    }
    // The room's measurements, measured when the room changes.
    val metres = stringResource(R.string.atmos_room_metres, "%s")
    val sizeLabels = remember(shape.width, shape.depth, shape.height, shape.dimensioned, shape.heightKnown, numberStyle) {
        if (!shape.dimensioned) {
            emptyList()
        } else {
            val style = numberStyle.copy(fontSize = 10.sp)
            listOf(shape.width, shape.depth, shape.height.takeIf { shape.heightKnown }).map { m ->
                m?.let { textMeasurer.measure(metres.replace("%s", String.format(java.util.Locale.getDefault(), "%.1f", it)), style = style) }
            }
        }
    }
    // Far to near, so what is nearer is drawn over what is behind it.
    val order = remember { IntArray(MAX_ITEMS) }
    val depths = remember { FloatArray(MAX_ITEMS) }
    val loudest = remember { IntArray(RAYS) }
    val path = remember { Path() }
    val afterglow = remember { Afterglow() }

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
                    val floorLine = room.outline()
                    val ceilingLine = room.ceiling()  // drawn in, when the walls lean
                    val wallGlow = accent.copy(alpha = 0.16f * amount * held)
                    for (i in floorLine.indices) {
                        val j = (i + 1) % floorLine.size
                        path.rewind()
                        at(floorLine[i][0], floorLine[i][1], 0f).let { path.moveTo(it.x, it.y) }
                        at(floorLine[j][0], floorLine[j][1], 0f).let { path.lineTo(it.x, it.y) }
                        at(ceilingLine[j][0], ceilingLine[j][1], room.height).let { path.lineTo(it.x, it.y) }
                        at(ceilingLine[i][0], ceilingLine[i][1], room.height).let { path.lineTo(it.x, it.y) }
                        path.close()
                        if (wallGlow.alpha > 0.004f) drawPath(path, wallGlow)
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
                    // A room with corners has four edges up them; a round room none.
                    if (floorLine.size == 4) {
                        for (i in floorLine.indices) {
                            drawLine(
                                faint,
                                at(floorLine[i][0], floorLine[i][1], 0f),
                                at(ceilingLine[i][0], ceilingLine[i][1], room.height),
                                strokeWidth = 1.5f,
                            )
                        }
                    }
                    // The screen, on the wall ahead, leaning as the wall does.
                    path.rewind()
                    val screenLow = 0.15f * room.height
                    val screenHigh = 0.8f * room.height
                    fun onFrontWall(fromSide: Float, z: Float): Offset {
                        val l = room.lean(z)
                        val side = maxOf(fromSide, l * room.width + 0.04f * room.width, inset)
                        return at(side, l * room.depth, z)
                    }
                    fun onFrontWallRight(fromSide: Float, z: Float): Offset {
                        val l = room.lean(z)
                        val side = maxOf(fromSide, l * room.width + 0.04f * room.width, inset)
                        return at(room.width - side, l * room.depth, z)
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
                        val l = room.lean(room.height / 2f)
                        drawLabel(it, at(room.width * (1f - l), room.depth * (1f - l), room.height / 2f), 26f, 0f)
                    }

                    // The sound's way to the listener, for the loudest speakers:
                    // straight, and by way of each surface it turns on.
                    val ear = floatArrayOf(room.listenerX, room.listenerY, room.earHeight)
                    val seat = at(ear[0], ear[1], ear[2])
                    pickLoudest(levels, layout, count, loudest)
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
private const val LFE_HEIGHT = 0.25f   // metres: the LFE stands on the floor
