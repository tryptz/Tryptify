package tf.monochrome.android.ui.mixer.spatial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.spatial.ChannelPlacement
import tf.monochrome.android.audio.dsp.spatial.SpatialLayout
import tf.monochrome.android.audio.dsp.spatial.SpatialPlacement
import tf.monochrome.android.audio.dsp.spatial.clamped
import tf.monochrome.android.domain.model.SpeakerChannel
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The spatial map: a room seen from above with you in the middle, facing up,
 * and every channel of the song as a dot you can drag. Where a dot sits is
 * where that channel is heard — its angle round you, and its distance, which
 * is level (the solid ring is where the speakers stand; pulled in to the inner
 * ring is 6 dB louder, out to the edge 6 dB quieter).
 *
 * While a multichannel song plays the map shows its own layout, and each dot
 * glows with its channel's level. With nothing multichannel playing, the chips
 * pick which layout to arrange; each is remembered separately.
 *
 * Height channels are the warmer dots, placed round you like the rest and kept
 * at their height. The LFE is the small dot below you: bass has no direction,
 * so it is not moved.
 */
@Composable
fun SpatialMapPanel(
    placement: SpatialPlacement,
    channelState: ChannelDetectorProcessor.ChannelState?,
    stereoFoldEnabled: Boolean,
    atmosRenderingObjects: Boolean,
    accent: Color,
    onEnabledChange: (Boolean) -> Unit,
    onBinauralChange: (Boolean) -> Unit,
    onMove: (count: Int, index: Int, placement: ChannelPlacement) -> Unit,
    onResetLayout: (count: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // The mixer's backdrop, which this pane is a sibling of: frosting it hides
    // the busy strips beneath the map rather than showing them through.
    hazeState: dev.chrisbanes.haze.HazeState? = null,
) {
    val colors = MaterialTheme.colorScheme
    val live = channelState?.channelCount?.takeIf { it >= 3 }
    var editing by rememberSaveable { mutableIntStateOf(12) }
    val count = live ?: editing
    val speakers = remember(count) { SpatialLayout.speakers(count) }
    val placed = placement.placementFor(count)
    var dragging by remember { mutableStateOf(-1) }

    Column(
        modifier = modifier
            .liquidGlass(hazeState = hazeState, shape = MonoDimens.shapeMd, tintAlpha = 0.30f)
            .padding(MonoDimens.spacingSm),
        verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Spatial map",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (live != null) "${SpatialLayout.layoutName(count)} · playing now"
                    else "${SpatialLayout.layoutName(count)} · nothing multichannel playing",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (placement.isMoved(count)) {
                TextButton(onClick = { onResetLayout(count) }) { Text("Reset") }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close spatial map", tint = colors.onSurfaceVariant)
            }
        }

        // ── Which layout, when the song does not decide ────────────────
        if (live == null) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpatialLayout.EDITABLE_COUNTS.forEach { c ->
                    GlassChoiceChip(
                        label = SpatialLayout.layoutName(c),
                        selected = c == editing,
                        accent = accent,
                        onClick = { editing = c },
                    )
                }
            }
        }

        // ── On/off and what it plays through ───────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Place channels",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = placement.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = accent),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChoiceChip(
                label = "Headphones",
                selected = placement.binaural,
                accent = accent,
                onClick = { onBinauralChange(true) },
                modifier = Modifier.weight(1f),
                description = "Headphones: binaural, heard where placed",
            )
            GlassChoiceChip(
                label = "Speakers",
                selected = !placement.binaural,
                accent = accent,
                onClick = { onBinauralChange(false) },
                modifier = Modifier.weight(1f),
                description = "Speakers: panned left to right",
            )
        }

        // ── The map ────────────────────────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val side = min(maxWidth.value, maxHeight.value).dp
            Box(modifier = Modifier.size(side)) {
                SpatialMapCanvas(
                    count = count,
                    speakers = speakers,
                    placed = placed,
                    levels = channelState?.takeIf { it.channelCount == count }?.peaksDb,
                    enabled = placement.enabled,
                    accent = accent,
                    dragging = dragging,
                    onDragging = { dragging = it },
                    onMove = { i, p ->
                        // Moving a channel is asking for the map: switch it on.
                        if (!placement.enabled) onEnabledChange(true)
                        onMove(count, i, p)
                    },
                )
            }
        }

        // ── What is happening, in words ────────────────────────────────
        val readout = if (dragging in speakers.indices) {
            val s = speakers[dragging]
            val p = placed[dragging]
            val db = 20f * log10(SpatialLayout.gainFor(p.distance))
            "${s.label}  ${angleText(p.azimuthDeg)} · ${if (db >= 0f) "+" else "−"}${"%.1f".format(abs(db))} dB"
        } else {
            spatialNote(placement, channelState, stereoFoldEnabled, atmosRenderingObjects)
        }
        Text(
            text = readout,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 3,
        )
    }
}

/** What the map is doing, or why it is not, in one or two lines. */
private fun spatialNote(
    placement: SpatialPlacement,
    state: ChannelDetectorProcessor.ChannelState?,
    stereoFold: Boolean,
    atmosObjects: Boolean,
): String = when {
    atmosObjects ->
        "This Atmos track's objects are placed by the Atmos renderer. The map applies in Direct mode and to multichannel files."
    !stereoFold ->
        "Multichannel is going to Android unfolded. Turn the stereo fold on in audio settings for the map to play."
    !placement.enabled ->
        "Off: the song folds to stereo as mixed. Drag a channel to start."
    placement.binaural && (state?.sampleRate ?: 0) > 48000 ->
        "Binaural needs 48 kHz or less; this ${state!!.sampleRate / 1000} kHz stream is panned instead."
    placement.binaural -> "Drag a channel to move it. Double-tap one to put it back."
    else -> "Speakers: channels are panned left to right; front and back fold together."
}

private fun angleText(az: Float): String {
    val a = az.roundToInt()
    return when {
        a == 0 -> "ahead"
        abs(a) == 180 -> "behind"
        a < 0 -> "${-a}° left"
        else -> "$a° right"
    }
}

/**
 * The room itself. Distances are drawn to scale: the outer edge is
 * [SpatialLayout.MAX_DISTANCE], the solid ring distance 1.
 */
@Composable
private fun SpatialMapCanvas(
    count: Int,
    speakers: List<SpeakerChannel>,
    placed: List<ChannelPlacement>,
    levels: FloatArray?,
    enabled: Boolean,
    accent: Color,
    dragging: Int,
    onDragging: (Int) -> Unit,
    onMove: (index: Int, placement: ChannelPlacement) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val heightTint = colors.tertiary
    val onSurface = colors.onSurface
    val outline = colors.outline
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val touchRadius = with(LocalDensity.current) { 30.dp.toPx() }
    val dotRadius = with(LocalDensity.current) { 13.dp.toPx() }

    // The gesture handlers outlive recompositions; they read the latest.
    val currentPlaced by rememberUpdatedState(placed)
    val currentMove by rememberUpdatedState(onMove)
    val currentDragging by rememberUpdatedState(onDragging)

    val description = speakers.mapIndexedNotNull { i, s ->
        if (s.isLfe) null else "${s.label} ${angleText(placed[i].azimuthDeg)}"
    }.joinToString(", ")

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Spatial map: $description" }
            .pointerInput(count) {
                var picked = -1
                detectDragGestures(
                    onDragStart = { at ->
                        picked = nearestDot(at, size.width / 2f, size.height / 2f, speakers, currentPlaced, touchRadius)
                        if (picked >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentDragging(picked)
                    },
                    onDragEnd = { picked = -1; currentDragging(-1) },
                    onDragCancel = { picked = -1; currentDragging(-1) },
                    onDrag = { change, _ ->
                        if (picked < 0) return@detectDragGestures
                        change.consume()
                        val p = placementAt(change.position, size.width / 2f, size.height / 2f, speakers[picked])
                        currentMove(picked, p)
                    },
                )
            }
            .pointerInput(count) {
                detectTapGestures(onDoubleTap = { at ->
                    val i = nearestDot(at, size.width / 2f, size.height / 2f, speakers, currentPlaced, touchRadius)
                    if (i >= 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentMove(i, ChannelPlacement(speakers[i].azimuthDeg, 1f))
                    }
                })
            },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val half = min(size.width, size.height) / 2f * RING_FILL

        drawRoom(c, half, outline, onSurface, textMeasurer)

        speakers.forEachIndexed { i, s ->
            if (s.isLfe) return@forEachIndexed
            val p = placed[i]
            val tint = if (s.isHeight) heightTint else accent
            // Where it came from, once it has been moved.
            val home = pointFor(s.azimuthDeg, 1f, c, half)
            val here = pointFor(p.azimuthDeg, p.distance, c, half)
            if ((home - here).getDistance() > 2f) {
                drawCircle(tint.copy(alpha = 0.35f), radius = dotRadius * 0.35f, center = home, style = Stroke(2f))
                drawLine(tint.copy(alpha = 0.25f), home, here, strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            val level = levels?.getOrNull(i)?.let { ((it + 60f) / 60f).coerceIn(0f, 1f) }
            drawChannelDot(
                center = here,
                listener = c,
                radius = dotRadius * (if (i == dragging) 1.25f else 1f),
                tint = tint,
                level = level,
                dim = !enabled,
                label = s.label,
                height = s.isHeight,
                textMeasurer = textMeasurer,
                labelColor = colors.surface,
            )
        }

        // The LFE, beneath the listener: bass, so not directional.
        val lfe = speakers.indexOfFirst { it.isLfe }
        if (lfe >= 0) {
            val at = Offset(c.x, c.y + half * 0.22f)
            val level = levels?.getOrNull(lfe)?.let { ((it + 60f) / 60f).coerceIn(0f, 1f) } ?: 0.4f
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.5f * level), Color.Transparent), at, dotRadius * 2f),
                radius = dotRadius * 2f,
                center = at,
            )
            drawCircle(accent.copy(alpha = 0.6f), radius = dotRadius * 0.45f, center = at)
            val t = textMeasurer.measure("LFE", TextStyle(fontSize = 9.sp, color = onSurface.copy(alpha = 0.7f)))
            drawText(t, topLeft = Offset(at.x - t.size.width / 2f, at.y + dotRadius * 0.6f))
        }
    }
}

/** Fraction of the canvas's half-width the outer ring takes. */
internal const val RING_FILL = 0.94f

/** Screen point for an angle (0 up, + clockwise) and a distance. */
internal fun pointFor(azDeg: Float, distance: Float, c: Offset, half: Float): Offset {
    val r = distance / SpatialLayout.MAX_DISTANCE * half
    val a = Math.toRadians(azDeg.toDouble())
    return Offset(c.x + (sin(a) * r).toFloat(), c.y - (cos(a) * r).toFloat())
}

/**
 * The placement under a finger at [at], snapped to the speaker ring and to the
 * channel's own angle when close, so both are easy to find again.
 */
internal fun placementAt(at: Offset, cx: Float, cy: Float, speaker: SpeakerChannel): ChannelPlacement {
    val dx = at.x - cx
    val dy = at.y - cy
    val half = min(cx, cy) * RING_FILL
    var az = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    var d = hypot(dx, dy) / half * SpatialLayout.MAX_DISTANCE
    if (abs(d - 1f) < 0.06f) d = 1f
    if (abs(angleBetween(az, speaker.azimuthDeg)) < 3f) az = speaker.azimuthDeg
    return ChannelPlacement(az, d).clamped()
}

private fun angleBetween(a: Float, b: Float): Float {
    var d = (a - b) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** The draggable channel nearest [at], within [radius], or -1. */
internal fun nearestDot(
    at: Offset,
    cx: Float,
    cy: Float,
    speakers: List<SpeakerChannel>,
    placed: List<ChannelPlacement>,
    radius: Float,
): Int {
    val c = Offset(cx, cy)
    val half = min(cx, cy) * RING_FILL
    var best = -1
    var bestD = radius
    speakers.forEachIndexed { i, s ->
        if (s.isLfe || i !in placed.indices) return@forEachIndexed
        val d = (pointFor(placed[i].azimuthDeg, placed[i].distance, c, half) - at).getDistance()
        if (d <= bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

private fun DrawScope.drawRoom(
    c: Offset,
    half: Float,
    outline: Color,
    onSurface: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    // Distance rings: +6 dB, the speakers, -6 dB (the edge).
    val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
    drawCircle(outline.copy(alpha = 0.18f), radius = half * 0.25f, center = c, style = Stroke(2f, pathEffect = dashed))
    drawCircle(outline.copy(alpha = 0.45f), radius = half * 0.5f, center = c, style = Stroke(2.5f))
    drawCircle(outline.copy(alpha = 0.22f), radius = half, center = c, style = Stroke(2f))
    // Cross-hairs: ahead/behind, left/right.
    drawLine(outline.copy(alpha = 0.12f), Offset(c.x, c.y - half), Offset(c.x, c.y + half), 1.5f)
    drawLine(outline.copy(alpha = 0.12f), Offset(c.x - half, c.y), Offset(c.x + half, c.y), 1.5f)

    // You: a head facing up, with a nose to say which way.
    val head = half * 0.075f
    val nose = Path().apply {
        moveTo(c.x - head * 0.45f, c.y - head * 0.8f)
        lineTo(c.x, c.y - head * 1.55f)
        lineTo(c.x + head * 0.45f, c.y - head * 0.8f)
        close()
    }
    drawPath(nose, onSurface.copy(alpha = 0.55f))
    drawCircle(onSurface.copy(alpha = 0.55f), radius = head, center = c)
    drawCircle(onSurface.copy(alpha = 0.45f), radius = head * 0.3f, center = Offset(c.x - head, c.y))
    drawCircle(onSurface.copy(alpha = 0.45f), radius = head * 0.3f, center = Offset(c.x + head, c.y))

    val style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = onSurface.copy(alpha = 0.5f))
    val front = textMeasurer.measure("FRONT", style)
    drawText(front, topLeft = Offset(c.x - front.size.width / 2f, c.y - half - front.size.height * 0.1f))
    val back = textMeasurer.measure("BACK", style)
    drawText(back, topLeft = Offset(c.x - back.size.width / 2f, c.y + half - back.size.height * 0.9f))
}

private fun DrawScope.drawChannelDot(
    center: Offset,
    listener: Offset,
    radius: Float,
    tint: Color,
    level: Float?,
    dim: Boolean,
    label: String,
    height: Boolean,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelColor: Color,
) {
    val alpha = if (dim) 0.45f else 1f
    // Live level: a bloom and a beam towards you, as on the Atmos page.
    if (level != null && level > 0.02f && !dim) {
        val toListener = listener - center
        val dist = toListener.getDistance()
        if (dist > 1f) {
            val reach = dist * (0.25f + 0.5f * level)
            drawLine(
                brush = Brush.linearGradient(
                    listOf(tint.copy(alpha = 0.55f * level), Color.Transparent),
                    start = center,
                    end = center + toListener / dist * reach,
                ),
                start = center,
                end = center + toListener / dist * reach,
                strokeWidth = radius * (0.6f + 0.8f * level),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(tint.copy(alpha = 0.6f * level), Color.Transparent), center, radius * (1.6f + 1.4f * level)),
            radius = radius * (1.6f + 1.4f * level),
            center = center,
        )
    }
    drawCircle(tint.copy(alpha = alpha), radius = radius, center = center)
    // Heights carry a ring: above you, not beside.
    if (height) drawCircle(tint.copy(alpha = 0.7f * alpha), radius = radius * 1.35f, center = center, style = Stroke(2f))
    val t = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = labelColor))
    drawText(t, topLeft = Offset(center.x - t.size.width / 2f, center.y - t.size.height / 2f))
}
