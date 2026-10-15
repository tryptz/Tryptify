package tf.monochrome.android.ui.stats

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import tf.monochrome.android.R
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass

// ─── Words and numbers, in the reader's language ────────────────────────────

@Composable
internal fun statsLocale(): Locale = LocalConfiguration.current.locales[0]

/** "40h 12m" or "35 min". */
@Composable
internal fun durationText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) stringResource(R.string.stats_duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.stats_duration_m, minutes)
}

internal fun percentText(fraction: Float, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).format(fraction.coerceIn(0f, 1f).toDouble())

internal fun signedPercentText(percent: Int): String = (if (percent >= 0) "+" else "−") + kotlin.math.abs(percent) + "%"

internal fun shortDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))

internal fun weekdayName(day: DayOfWeek, locale: Locale, style: TextStyle = TextStyle.FULL): String =
    day.getDisplayName(style, locale).replaceFirstChar { it.titlecase(locale) }

/** An hour the way the reader writes times: 3 PM, 15:00, 下午3:00. */
internal fun hourText(hour: Int, locale: Locale): String =
    LocalTime.of(hour.coerceIn(0, 23), 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

internal fun mmss(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Up to two letters of a name, for an artist block with no picture. */
internal fun initials(name: String): String =
    name.split(' ', '-', '/').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

// ─── Glass bars ─────────────────────────────────────────────────────────────

/**
 * Whether bars are drawn as glass here. With "Remove liquid glass" on they
 * are flat Material bars instead: glass is never drawn as nothing, and never
 * half-drawn either.
 */
@Composable
internal fun glassBarsOn(): Boolean = !LocalLowPerformance.current.disableLiquidGlass

/**
 * One bar of glass: a translucent tint of [tint], a light rim, a bright inner
 * top edge and, on a bar wide enough to carry it, a thin highlight streak. No
 * gradients: every layer is one flat colour, the way the app's panes are
 * built. A selected bar is a stronger tint with a brighter rim.
 *
 * Drawn on the canvas rather than as a [GlassPanel] per bar: a chart is up to
 * 168 cells, and a shader pane each would be a frame budget spent on squares
 * a few dp wide.
 */
internal fun DrawScope.glassBar(
    topLeft: Offset,
    size: Size,
    tint: Color,
    selected: Boolean,
    glass: Boolean,
    corner: Float = 6.dp.toPx(),
) {
    if (size.width <= 0f || size.height <= 0f) return
    val r = min(corner, min(size.width, size.height) / 2f)
    val radius = CornerRadius(r)
    if (!glass) {
        drawRoundRect(tint.copy(alpha = if (selected) 1f else 0.6f), topLeft, size, radius)
        return
    }
    val px = 1.dp.toPx()
    drawRoundRect(tint.copy(alpha = if (selected) 0.82f else 0.38f), topLeft, size, radius)
    if (size.width > 2 * px && size.height > 2 * px) {
        drawRoundRect(
            Color.White.copy(alpha = if (selected) 0.7f else 0.28f),
            topLeft + Offset(px / 2, px / 2), Size(size.width - px, size.height - px), CornerRadius(max(0f, r - px / 2)),
            style = Stroke(px),
        )
    }
    if (size.height > 4 * px && size.width > 2 * r + 2 * px) {
        drawLine(
            Color.White.copy(alpha = 0.45f),
            Offset(topLeft.x + r, topLeft.y + 1.5f * px), Offset(topLeft.x + size.width - r, topLeft.y + 1.5f * px),
            strokeWidth = px,
        )
    }
    if (size.width >= 16.dp.toPx() && size.height > 12 * px) {
        drawRoundRect(
            Color.White.copy(alpha = 0.26f),
            Offset(topLeft.x + size.width * 0.2f, topLeft.y + 3 * px), Size(2 * px, size.height - 6 * px), CornerRadius(px),
        )
    }
}

// ─── Cards ──────────────────────────────────────────────────────────────────

/**
 * A pane of the app's glass, as a card in the Stats list: the UI panels
 * material over the page's own backdrop, letting a swipe that starts on it
 * scroll the list, the way Discover's feed cards do.
 */
@Composable
internal fun StatsCard(haze: HazeState, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(
        hazeState = haze,
        glass = LocalMiniPlayerGlass.current,
        modifier = modifier,
        avoidNavigationBar = false,
        blockTouchesBelow = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** A card's caption, and the glass button that opens its chart. */
@Composable
internal fun CardHeader(title: String, onAnalyze: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Kicker(title, Modifier.weight(1f))
        if (onAnalyze != null) {
            val label = stringResource(R.string.stats_analyze, title)
            PressableGlass(
                onClick = onAnalyze,
                modifier = Modifier.size(44.dp).semantics { contentDescription = label },
                shape = CircleShape,
                hazeState = LocalGlassBackdrop.current,
            ) {
                Icon(Icons.Default.NorthEast, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text.uppercase(statsLocale()),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The poster-size words: "NIGHT OWL", "12". The listener's own font, made big. */
@Composable
internal fun BigWords(text: String, modifier: Modifier = Modifier, scale: Float = 1f) {
    val base = MaterialTheme.typography.displayMedium
    Text(
        text = text.uppercase(statsLocale()),
        style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.fontSize * scale * 1.02f),
        fontWeight = FontWeight.Black,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
internal fun Headline(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
internal fun LegendDot(label: String, color: Color, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 14.dp, height = 10.dp)
                .clip(RoundedCornerShape(3.dp))
                .then(
                    if (dashed) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp))
                    else Modifier.background(color).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Overview charts ────────────────────────────────────────────────────────

/** Time per bucket against the same span just before it (dashed). */
@Composable
internal fun TimelineChart(buckets: List<TimeBucket>, showPrevious: Boolean, description: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val ground = MaterialTheme.colorScheme.surface
    val prevInk = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        if (buckets.isEmpty()) return@Canvas
        val maxV = max(1L, buckets.maxOf { max(it.seconds, if (showPrevious) it.prevSeconds ?: 0 else 0) }).toFloat()
        val pad = 6.dp.toPx()
        val w = size.width
        val h = size.height - pad
        fun x(i: Int) = if (buckets.size == 1) w / 2 else i * w / (buckets.size - 1)
        fun y(v: Long) = pad + h - v / maxV * (h - pad)
        val line = Path().apply { buckets.forEachIndexed { i, b -> if (i == 0) moveTo(x(i), y(b.seconds)) else lineTo(x(i), y(b.seconds)) } }
        val area = Path().apply { addPath(line); lineTo(x(buckets.lastIndex), size.height); lineTo(x(0), size.height); close() }
        drawLine(prevInk.copy(alpha = 0.25f), Offset(0f, size.height - 0.5f), Offset(w, size.height - 0.5f), 1.dp.toPx())
        drawPath(area, tint.copy(alpha = 0.16f))
        if (showPrevious) {
            val prev = Path().apply { buckets.forEachIndexed { i, b -> val v = b.prevSeconds ?: 0; if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            drawPath(prev, prevInk.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))))
        }
        drawPath(line, tint, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val peak = buckets.indices.maxByOrNull { buckets[it].seconds } ?: 0
        val p = Offset(x(peak), y(buckets[peak].seconds))
        drawCircle(ground, 5.dp.toPx(), p)
        drawCircle(tint, 5.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
    }
}

/** Seven rows of 24 hours, Monday first, shaded by listening. */
@Composable
internal fun HeatGrid(heat: HeatStat, description: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val locale = statsLocale()
    val peak = heat.seconds.maxOf { it.max() }.coerceAtLeast(1L).toFloat()
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DayOfWeek.entries.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    weekdayName(day, locale, TextStyle.NARROW), modifier = Modifier.width(18.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    val gap = 2.dp.toPx()
                    val cw = (size.width - gap * 23) / 24
                    for (h in 0 until 24) {
                        val t = heat.at(day, h) / peak
                        drawRoundRect(
                            if (t < 0.06f) empty else tint.copy(alpha = 0.14f + 0.86f * t),
                            Offset(h * (cw + gap), 0f), Size(cw, size.height), CornerRadius(3.dp.toPx()),
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(start = 18.dp)) {
            listOf(0, 6, 12, 18).forEach {
                Text(hourText(it, locale), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Parts of a whole as glass segments side by side: the top-5 share, quality. */
@Composable
internal fun ShareBar(parts: List<Pair<Float, Color>>, description: String, modifier: Modifier = Modifier) {
    val glass = glassBarsOn()
    Canvas(modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = description }) {
        val gap = 2.dp.toPx()
        val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val usable = size.width - gap * (parts.size - 1).coerceAtLeast(0)
        var x = 0f
        parts.forEach { (f, c) ->
            val w = usable * f / total
            if (w > 0.5f) glassBar(Offset(x, 0f), Size(w, size.height), c, selected = true, glass = glass, corner = 11.dp.toPx())
            x += w + gap
        }
    }
}

/** New (top) on familiar (bottom) plays per bucket, with the count of new ones above each. */
@Composable
internal fun DiscoveryBars(buckets: List<DiscoveryBucket>, labels: List<String>, description: String, modifier: Modifier = Modifier) {
    val glass = glassBarsOn()
    val newTint = MaterialTheme.colorScheme.primary
    val knownTint = MaterialTheme.colorScheme.secondary
    val maxV = buckets.maxOfOrNull { it.newPlays + it.knownPlays }?.coerceAtLeast(1) ?: 1
    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buckets.forEach { b ->
                Text(
                    if (b.newPlays > 0) "+${b.newPlays}" else "", modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = newTint,
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val gap = 8.dp.toPx()
            val cw = (size.width - gap * (buckets.size - 1).coerceAtLeast(0)) / buckets.size.coerceAtLeast(1)
            buckets.forEachIndexed { i, b ->
                val x = i * (cw + gap)
                val knownH = size.height * b.knownPlays / maxV
                val newH = size.height * b.newPlays / maxV
                glassBar(Offset(x, size.height - knownH), Size(cw, knownH), knownTint, selected = true, glass = glass, corner = 8.dp.toPx())
                if (newH > 0f) glassBar(Offset(x, size.height - knownH - newH - 2.dp.toPx()), Size(cw, newH), newTint, selected = true, glass = glass, corner = 8.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Five Monday-first weeks, each day shaded by its listening; today ringed. */
@Composable
internal fun StreakCalendar(days: List<CalendarDay>, today: LocalDate, description: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val onTint = MaterialTheme.colorScheme.onPrimary
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = statsLocale()
    val peak = days.maxOfOrNull { it.seconds }?.coerceAtLeast(1L)?.toFloat() ?: 1f
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach {
                Text(weekdayName(it, locale, TextStyle.NARROW), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { d ->
                    val t = d.seconds / peak
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(shape)
                            .background(if (d.future || d.seconds == 0L) ink.copy(alpha = 0.05f) else tint.copy(alpha = 0.18f + 0.72f * t))
                            .then(if (d.date == today) Modifier.border(2.dp, ink, shape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                d.future -> muted.copy(alpha = 0.5f)
                                t > 0.55f && d.seconds > 0 -> onTint
                                d.seconds == 0L -> muted
                                else -> ink
                            },
                        )
                    }
                }
            }
        }
    }
}
