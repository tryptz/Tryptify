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
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * The view of the 15.1.12 room: where a point of the room lands on the page.
 * Room coordinates are the renderer's (x 0..1 left to right, y 0..1 front to
 * back, z 0 at ear level to 1 at the ceiling). The eye is behind and above
 * the listener, turned [yaw] radians about the room's centre.
 */
internal class AtmosRoomView(yaw: Float) {
    private val cy = cos(yaw)
    private val sy = sin(yaw)
    private val cp = cos(PITCH)
    private val sp = sin(PITCH)

    /** Sideways (right is up), upwards, and how big things there are drawn (1 at the centre). */
    class Point(val x: Float, val y: Float, val scale: Float, val depth: Float)

    fun project(x: Float, y: Float, z: Float): Point {
        val px = x - 0.5f
        val py = 0.5f - y                       // towards the front
        val pz = z * HEIGHT - HEIGHT / 2f       // up, about the room's middle
        val rx = px * cy - py * sy
        val ry = px * sy + py * cy
        val up = pz * cp + ry * sp
        val depth = ry * cp - pz * sp
        val scale = EYE / (EYE + depth)
        return Point(rx * scale, up * scale, scale, depth)
    }

    companion object {
        /** The ceiling's height against the room's width. */
        const val HEIGHT = 0.55f
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
 * The 15.1.12 room in three dimensions: its 28 speakers where they stand,
 * each as bright as it is driven, and the mix's objects floating where the
 * mix puts them. Drag sideways to walk round it.
 *
 * [layout] is [AtmosNative.nativeRoomLayout]'s, [speakers] the processor's
 * levels in the same order (null: nothing plays through the room).
 */
@Composable
internal fun AtmosRoom3d(
    layout: FloatArray,
    speakers: FloatArray?,
    objects: AtmosAudioProcessor.ObjectMeter?,
    modifier: Modifier = Modifier,
) {
    val liveSpeakers by rememberUpdatedState(speakers)
    val liveObjects by rememberUpdatedState(objects)
    val yaw = remember { mutableFloatStateOf(AtmosRoomView.START_YAW) }
    val accent = MaterialTheme.colorScheme.primary
    val heightTint = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val count = layout.size / AtmosNative.ROOM_FIELDS

    // The objects' numbers, measured once.
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = remember(onSurface) {
        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = onSurface.copy(alpha = 0.8f))
    }
    val numbers = remember(numberStyle, textMeasurer) {
        List(MAX_NUMBERS) { textMeasurer.measure((it + 1).toString(), style = numberStyle) }
    }
    // Far to near, so what is nearer is drawn over what is behind it.
    val order = remember { IntArray(MAX_ITEMS) }
    val depths = remember { FloatArray(MAX_ITEMS) }
    val floor = remember { Path() }

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
                    val view = AtmosRoomView(yaw.floatValue)
                    // The whole room at any turn: its near corners come out
                    // 0.7 of a room to the side and 0.57 below the middle.
                    val unit = min(size.width * 0.54f, size.height * 0.84f)
                    val cx = size.width / 2f
                    val cy = size.height / 2f - 0.09f * unit
                    fun at(p: AtmosRoomView.Point) = Offset(cx + p.x * unit, cy - p.y * unit)
                    fun at(x: Float, y: Float, z: Float) = at(view.project(x, y, z))

                    // The room: the floor, the walls' edges, the screen ahead.
                    floor.rewind()
                    at(0f, 0f, 0f).let { floor.moveTo(it.x, it.y) }
                    at(1f, 0f, 0f).let { floor.lineTo(it.x, it.y) }
                    at(1f, 1f, 0f).let { floor.lineTo(it.x, it.y) }
                    at(0f, 1f, 0f).let { floor.lineTo(it.x, it.y) }
                    floor.close()
                    drawPath(floor, onSurface.copy(alpha = 0.05f))
                    val edge = outline.copy(alpha = 0.35f)
                    val faint = outline.copy(alpha = 0.16f)
                    for (i in 0 until 4) {
                        val x0 = if (i == 0 || i == 3) 0f else 1f
                        val y0 = if (i < 2) 0f else 1f
                        val x1 = if (i == 0 || i == 1) 1f else 0f
                        val y1 = if (i == 1 || i == 2) 1f else 0f
                        drawLine(edge, at(x0, y0, 0f), at(x1, y1, 0f), strokeWidth = 2f)
                        drawLine(faint, at(x0, y0, 1f), at(x1, y1, 1f), strokeWidth = 1.5f)
                        drawLine(faint, at(x0, y0, 0f), at(x0, y0, 1f), strokeWidth = 1.5f)
                    }
                    floor.rewind()
                    at(0.12f, 0f, 0.12f).let { floor.moveTo(it.x, it.y) }
                    at(0.88f, 0f, 0.12f).let { floor.lineTo(it.x, it.y) }
                    at(0.88f, 0f, 0.78f).let { floor.lineTo(it.x, it.y) }
                    at(0.12f, 0f, 0.78f).let { floor.lineTo(it.x, it.y) }
                    floor.close()
                    drawPath(floor, onSurface.copy(alpha = 0.06f))
                    drawPath(floor, faint, style = Stroke(width = 1.5f))
                    // The listener, at ear level in the middle.
                    val seat = view.project(0.5f, 0.5f, 0f)
                    drawCircle(onSurface.copy(alpha = 0.55f), unit * 0.022f * seat.scale, at(seat))

                    val levels = liveSpeakers
                    val o = liveObjects
                    val objectCount = min(o?.count ?: 0, MAX_ITEMS - count)
                    val items = count + objectCount
                    for (i in 0 until items) {
                        order[i] = i
                        depths[i] = if (i < count) {
                            val f = i * AtmosNative.ROOM_FIELDS
                            view.project(speakerX(layout, f), speakerY(layout, f), layout[f + 2]).depth
                        } else {
                            val k = i - count
                            view.project(o!!.x(k).coerceIn(0f, 1f), o.y(k).coerceIn(0f, 1f), o.z(k).coerceIn(0f, 1f)).depth
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
                            val p = view.project(speakerX(layout, f), speakerY(layout, f), layout[f + 2])
                            val glow = levels?.getOrNull(i)?.let(::atmosSpeakerGlow) ?: 0f
                            drawRoomSpeaker(at(p), unit * p.scale, if (high) heightTint else accent, glow, lfe)
                        } else {
                            val k = i - count
                            if (o!!.isLfe(k)) continue  // the LFE has no place: its speaker shows it
                            val x = o.x(k).coerceIn(0f, 1f)
                            val y = o.y(k).coerceIn(0f, 1f)
                            val z = o.z(k).coerceIn(0f, 1f)
                            val level = atmosObjectLevel(o.level(k))
                            val p = view.project(x, y, z)
                            val pos = at(p)
                            val tint = if (z > 0.25f) heightTint else onSurface
                            // A line down to the floor says how high it is.
                            if (z > 0.02f) {
                                val foot = at(x, y, 0f)
                                drawLine(tint.copy(alpha = 0.22f), pos, foot, strokeWidth = 1.5f)
                                drawCircle(tint.copy(alpha = 0.25f), unit * 0.008f * p.scale, foot)
                            }
                            val r = unit * (0.02f + 0.022f * level) * p.scale
                            drawCircle(tint.copy(alpha = 0.16f * level), r * 2.3f, pos)
                            drawCircle(tint.copy(alpha = 0.4f + 0.6f * level), r, pos)
                            numbers.getOrNull(k)?.let { label ->
                                drawText(label, topLeft = Offset(pos.x + r + 2f, pos.y - label.size.height / 2f))
                            }
                        }
                    }
                },
        )
    }
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
