package tf.monochrome.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.EarTune
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The ear tuner: the listener's own ears on top of the HRTF in use
 * ([EarTune]; `render/virtual_room.h` says what it does to the sound).
 *
 * One pad holds three of its four values. The dot is the ear's size across
 * and the height band's level up and down; the ring round it is the band's
 * width (Q), pulled by the handle on its diagonal. The graph over it is not a
 * drawing of the settings: it is the filter that plays, asked of the renderer
 * ([curve]) for a sound from above at the nearer ear, as the head has it and
 * as the tuning makes it. Everything is heard while it is dragged ([onTune]).
 */
@Composable
internal fun EarTunerSection(
    tune: EarTune,
    head: String?,
    onTune: (EarTune) -> Unit,
    curve: suspend (EarTune, FloatArray) -> FloatArray?,
) {
    val current = tune.clamped()
    FoldingList(
        title = stringResource(R.string.atmos_ear_tuner),
        chosen = stringResource(if (current.shapes) R.string.atmos_ear_on else R.string.atmos_ear_as_head),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.atmos_ear_tuner_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var held by remember { mutableStateOf(PadHold.NONE) }
            var curves by remember { mutableStateOf<FloatArray?>(null) }
            LaunchedEffect(current, head) { curves = curve(current, EAR_GRAPH_HZ) ?: curves }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EarGraph(current, curves)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.atmos_ear_graph),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        LegendMark(dashed = true, text = stringResource(R.string.atmos_ear_head))
                        Spacer(Modifier.width(10.dp))
                        LegendMark(dashed = false, text = stringResource(R.string.atmos_ear_tuned))
                    }
                    EarPad(current, onTune = onTune, onHold = { held = it })
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    val percent = (current.size * 100f).roundToInt() - 100
                    Readout(
                        stringResource(R.string.atmos_ear_size),
                        if (percent == 0) stringResource(R.string.atmos_ear_head) else String.format(Locale.getDefault(), "%+d %%", percent),
                        lit = held == PadHold.DOT,
                        modifier = Modifier.weight(1f),
                    )
                    Readout(
                        stringResource(R.string.atmos_ear_volume),
                        String.format(Locale.getDefault(), "%+.1f dB", current.gainDb),
                        lit = held == PadHold.DOT,
                        modifier = Modifier.weight(1f),
                    )
                    Readout(
                        stringResource(R.string.atmos_ear_q),
                        String.format(Locale.getDefault(), "%.1f", current.q),
                        lit = held == PadHold.RING,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // The band's place, evenly by pitch: 3 to 14 kHz is over two octaves.
            val span = ln(EarTune.MAX_HZ / EarTune.MIN_HZ)
            LabeledSlider(
                title = stringResource(R.string.atmos_ear_band),
                valueText = khz(current.freqHz),
                value = ln(current.freqHz / EarTune.MIN_HZ) / span,
                range = 0f..1f,
                onValueChange = { onTune(current.copy(freqHz = EarTune.MIN_HZ * kotlin.math.exp(span * it))) },
            )
            TextButton(onClick = { onTune(EarTune()) }, enabled = current != EarTune()) {
                Text(stringResource(R.string.action_reset))
            }
        }
    }
}

/** What the finger has on the pad, for the readout that lights up. */
private enum class PadHold { NONE, DOT, RING }

@Composable
private fun LegendMark(dashed: Boolean, text: String) {
    val color = if (dashed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 4.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
                strokeWidth = (if (dashed) 1.5f else 3f).dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Readout(label: String, value: String, lit: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (lit) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

private fun khz(hz: Float): String = String.format(Locale.getDefault(), "%.1f kHz", hz / 1000f)

// ── The graph ────────────────────────────────────────────────────────────────

/** Where the graph asks for the response: 1 to 20 kHz, evenly by pitch. */
private val EAR_GRAPH_HZ = FloatArray(96) { 1000f * 20f.pow(it / 95f) }
private const val GRAPH_DB = 42f       // top to bottom
private const val GRAPH_ZERO = 0.36f   // where the head's own level sits, from the top

/**
 * [curves] is the head's response and then the tuned one, in dB at each of
 * [EAR_GRAPH_HZ]; null until the renderer has answered. Both are drawn
 * against the head's own level under 2.5 kHz, where no ear does anything.
 */
@Composable
private fun EarGraph(tune: EarTune, curves: FloatArray?) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = quiet)
    val pill = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = onPrimary, fontWeight = FontWeight.SemiBold)
    val bandText = khz(tune.freqHz)
    Canvas(Modifier.fillMaxWidth().height(116.dp)) {
        val n = EAR_GRAPH_HZ.size
        val top = 20.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val zero = top + (bottom - top) * GRAPH_ZERO
        val perDb = (bottom - top) / GRAPH_DB
        fun x(hz: Float) = size.width * ln(hz / 1000f) / ln(20f)
        drawLine(line, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())

        if (curves != null && curves.size == 2 * n) {
            var level = 0f
            var counted = 0
            for (i in 0 until n) if (EAR_GRAPH_HZ[i] < 2500f) { level += curves[i]; counted++ }
            level /= counted.coerceAtLeast(1)
            fun y(db: Float) = (zero - (db - level) * perDb).coerceIn(top, bottom)
            fun path(from: Int) = Path().apply {
                for (i in 0 until n) {
                    val px = x(EAR_GRAPH_HZ[i])
                    val py = y(curves[from + i])
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            val tuned = path(n)
            val under = Path().apply {
                addPath(tuned)
                lineTo(size.width, bottom)
                lineTo(0f, bottom)
                close()
            }
            drawPath(under, Brush.verticalGradient(listOf(primary.copy(alpha = 0.34f), Color.Transparent), top, bottom))
            drawPath(
                path(0), quiet,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
            )
            drawPath(tuned, primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Where the height band is, with its pitch on it.
        val text = measurer.measure(bandText, pill)
        val pillWidth = text.size.width + 16.dp.toPx()
        val pillHeight = 16.dp.toPx()
        val at = x(tune.freqHz).coerceIn(pillWidth / 2f, size.width - pillWidth / 2f)
        drawLine(primary.copy(alpha = 0.5f), Offset(at, pillHeight), Offset(at, bottom), strokeWidth = 1.dp.toPx())
        drawRoundRect(primary, Offset(at - pillWidth / 2f, 0f), Size(pillWidth, pillHeight), CornerRadius(pillHeight / 2f))
        drawText(text, topLeft = Offset(at - text.size.width / 2f, (pillHeight - text.size.height) / 2f))

        for ((hz, name) in GRAPH_TICKS) {
            val t = measurer.measure(name, label)
            val px = (x(hz) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(px, size.height - t.size.height))
        }
    }
}

private val GRAPH_TICKS = listOf(1000f to "1k", 2000f to "2k", 5000f to "5k", 10000f to "10k", 20000f to "20k")

// ── The pad ──────────────────────────────────────────────────────────────────

/**
 * Where the pad puts a tuning and what a touch on it means, in fractions of
 * its side so that it can be held to numbers by a test.
 */
internal object EarPadMap {
    private val SIZE_SPAN = ln(EarTune.MAX_SIZE)             // 0.8 and 1.25 are the same step either way
    private val Q_SPAN = ln(EarTune.MAX_Q / EarTune.MIN_Q)
    const val RING_WIDE = 0.29f                              // the ring's radius at the lowest Q
    const val RING_NARROW = 0.095f                           // and at the highest
    private const val FLIP = 0.6f                            // past this the handle is on the dot's other side
    private const val SQRT_HALF = 0.70710677f

    /** The dot: ear size across (the set's own in the middle), the band's level up. */
    fun dotX(tune: EarTune) = (ln(tune.size) / SIZE_SPAN + 1f) / 2f
    fun dotY(tune: EarTune) = (EarTune.MAX_DB - tune.gainDb) / (2f * EarTune.MAX_DB)

    /** The ring's radius: wide for a low Q, small for a high one. */
    fun ring(tune: EarTune) = RING_WIDE - (RING_WIDE - RING_NARROW) * ln(tune.q / EarTune.MIN_Q) / Q_SPAN

    /** Which way the handle's diagonal points: into the pad, so it is never off its edge. */
    fun sideX(tune: EarTune) = if (dotX(tune) > FLIP) -1f else 1f
    fun sideY(tune: EarTune) = if (dotY(tune) > FLIP) -1f else 1f

    fun handleX(tune: EarTune) = dotX(tune) + sideX(tune) * ring(tune) * SQRT_HALF
    fun handleY(tune: EarTune) = dotY(tune) + sideY(tune) * ring(tune) * SQRT_HALF

    /** [tune] with the dot at ([x], [y]): the size in whole percent, the level in half decibels. */
    fun withDot(tune: EarTune, x: Float, y: Float): EarTune {
        val size = kotlin.math.exp(SIZE_SPAN * (2f * x.coerceIn(0f, 1f) - 1f))
        val gain = EarTune.MAX_DB - 2f * EarTune.MAX_DB * y.coerceIn(0f, 1f)
        return tune.copy(size = (size * 100f).roundToInt() / 100f, gainDb = (gain * 2f).roundToInt() / 2f).clamped()
    }

    /** [tune] with the handle pulled to ([x], [y]): how far along its diagonal is the ring. */
    fun withHandle(tune: EarTune, x: Float, y: Float): EarTune {
        val along = (sideX(tune) * (x - dotX(tune)) + sideY(tune) * (y - dotY(tune))) * SQRT_HALF
        val radius = along.coerceIn(RING_NARROW, RING_WIDE)
        val q = EarTune.MIN_Q * kotlin.math.exp(Q_SPAN * (RING_WIDE - radius) / (RING_WIDE - RING_NARROW))
        return tune.copy(q = q).clamped()
    }
}

@Composable
private fun EarPad(tune: EarTune, onTune: (EarTune) -> Unit, onHold: (PadHold) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val ground = MaterialTheme.colorScheme.surfaceContainerLowest
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = quiet)
    val smaller = stringResource(R.string.atmos_ear_smaller)
    val larger = stringResource(R.string.atmos_ear_larger)
    val more = stringResource(R.string.atmos_ear_more)
    val less = stringResource(R.string.atmos_ear_less)
    val help = stringResource(R.string.atmos_ear_pad_help)
    var hold by remember { mutableStateOf(PadHold.NONE) }
    // The gesture outlives a recomposition: it reads what is current through these.
    val latest by rememberUpdatedState(tune)
    val send by rememberUpdatedState(onTune)
    val tell by rememberUpdatedState(onHold)

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(ground)
            .semantics { contentDescription = help }
            .pointerInput(Unit) {
                val reach = 26.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val side = size.width.toFloat()
                    val onHandle = hypot(
                        down.position.x - EarPadMap.handleX(latest) * side,
                        down.position.y - EarPadMap.handleY(latest) * side,
                    ) < reach
                    hold = if (onHandle) PadHold.RING else PadHold.DOT
                    tell(hold)
                    fun apply(at: Offset) {
                        val x = at.x / side
                        val y = at.y / side
                        val next = if (hold == PadHold.RING) EarPadMap.withHandle(latest, x, y) else EarPadMap.withDot(latest, x, y)
                        if (next != latest) send(next)
                    }
                    apply(down.position)
                    // Every move is taken, so the page under the pad does not scroll with it.
                    drag(down.id) { change ->
                        change.consume()
                        apply(change.position)
                    }
                    hold = PadHold.NONE
                    tell(hold)
                }
            },
    ) {
        val side = size.width
        val dot = Offset(EarPadMap.dotX(tune) * side, EarPadMap.dotY(tune) * side)
        val handle = Offset(EarPadMap.handleX(tune) * side, EarPadMap.handleY(tune) * side)
        val ring = EarPadMap.ring(tune) * side
        val centre = Offset(side / 2f, side / 2f)
        val sx = EarPadMap.sideX(tune)
        val sy = EarPadMap.sideY(tune)

        // A fine grid of points, and a glow that goes with the dot and its level.
        val step = side / 12f
        for (i in 0 until 12) for (j in 0 until 12) {
            drawCircle(line, radius = 1.dp.toPx(), center = Offset(step * (i + 0.5f), step * (j + 0.5f)))
        }
        val glow = 0.10f + 0.16f * abs(tune.gainDb) / EarTune.MAX_DB
        drawRect(Brush.radialGradient(listOf(primary.copy(alpha = glow), Color.Transparent), center = dot, radius = side * 0.6f))

        val inset = 10.dp.toPx()
        drawLine(line, Offset(centre.x, inset), Offset(centre.x, side - inset), strokeWidth = 1.dp.toPx())
        drawLine(line, Offset(inset, centre.y), Offset(side - inset, centre.y), strokeWidth = 1.dp.toPx())
        // The middle is the head as it is: the line from it is how far the tuning has gone.
        drawCircle(quiet, radius = 5.dp.toPx(), center = centre, style = Stroke(1.5.dp.toPx()))
        drawLine(primary.copy(alpha = 0.45f), centre, dot, strokeWidth = 1.5.dp.toPx())

        // The diagonal the handle runs on.
        val reach = 0.25f * side
        drawLine(
            ink.copy(alpha = if (hold == PadHold.RING) 0.9f else 0.35f),
            dot + Offset(sx, sy) * (0.063f * side), dot + Offset(sx, sy) * reach,
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
        )
        drawCircle(primary.copy(alpha = 0.09f), radius = ring, center = dot)
        drawCircle(primary.copy(alpha = 0.9f), radius = ring, center = dot, style = Stroke(1.5.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.25f), radius = ring * 0.62f, center = dot, style = Stroke(1.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.22f), radius = 17.dp.toPx(), center = dot)
        drawCircle(ground, radius = 11.dp.toPx(), center = dot)
        drawCircle(primary, radius = 9.dp.toPx(), center = dot)

        drawHandle(handle, sx, sy, ink, ground)

        drawLabel(measurer, smaller, label, Offset(12.dp.toPx(), centre.y - 6.dp.toPx()), above = true)
        drawLabel(measurer, larger, label, Offset(side - 12.dp.toPx(), centre.y - 6.dp.toPx()), above = true, end = true)
        drawLabel(measurer, more, label, Offset(centre.x + 6.dp.toPx(), 8.dp.toPx()))
        drawLabel(measurer, less, label, Offset(centre.x + 6.dp.toPx(), side - 8.dp.toPx()), above = true)
    }
}

/** The ring's handle: a white button with an arrow along its diagonal. */
private fun DrawScope.drawHandle(at: Offset, sx: Float, sy: Float, ink: Color, ground: Color) {
    drawCircle(ground, radius = 15.dp.toPx(), center = at)
    drawCircle(ink, radius = 13.dp.toPx(), center = at)
    val a = 4.5.dp.toPx()
    val head = 3.dp.toPx()
    val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val arrow = Path().apply {
        moveTo(at.x - sx * a, at.y - sy * a)
        lineTo(at.x + sx * a, at.y + sy * a)
        moveTo(at.x - sx * a, at.y - sy * (a - head))
        lineTo(at.x - sx * a, at.y - sy * a)
        lineTo(at.x - sx * (a - head), at.y - sy * a)
        moveTo(at.x + sx * a, at.y + sy * (a - head))
        lineTo(at.x + sx * a, at.y + sy * a)
        lineTo(at.x + sx * (a - head), at.y + sy * a)
    }
    drawPath(arrow, ground, style = stroke)
}

/** [text] with its corner at [at]: over it for [above], ending at it for [end]. */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    at: Offset,
    above: Boolean = false,
    end: Boolean = false,
) {
    val measured = measurer.measure(text, style)
    drawText(
        measured,
        topLeft = Offset(
            if (end) at.x - measured.size.width else at.x,
            if (above) at.y - measured.size.height else at.y,
        ),
    )
}
