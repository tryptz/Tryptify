package tf.monochrome.android.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.popBackStackSafe

/** Every chart a Stats card or tile opens. */
enum class StatsChart { MINUTES, CLOCK, WEEKDAY, ARTISTS, TRACKS, ALBUMS, DISCOVERY, QUALITY, SOURCES, STREAK, SESSIONS, MONTHS, VARIETY, LENGTH }

/** The chart page's route, carrying the range its Stats page had. */
fun statsChartRoute(chart: StatsChart, range: StatsRange): String = "stats_chart/${chart.name}?range=${range.name}"

// ─── The model a chart page draws ───────────────────────────────────────────

internal enum class ChartKind { BARS, GRID, RANKED }

internal class Readout(val kicker: String, val value: String, val unit: String = "", val lines: List<String> = emptyList(), val chips: List<String> = emptyList())

internal class ChartEntry(
    /** Under a bar, or nothing. */
    val label: String,
    val value: Float,
    /** The same span the period before, drawn as a dashed outline. */
    val prev: Float? = null,
    /** A second segment stacked on top (discovery's new plays). */
    val top: Float = 0f,
    /** Inside a grid cell (the calendar's day number). */
    val text: String = "",
    val disabled: Boolean = false,
    val description: String,
    /** A ranked row's right-hand number. */
    val valueText: String = "",
    /** A ranked row's second line. */
    val sub: String = "",
    val readout: Readout,
)

internal class ChartModel(
    val eyebrow: String,
    val title: String,
    val headline: String,
    val kind: ChartKind,
    val entries: List<ChartEntry>,
    val defaultSel: Int,
    val views: List<String> = emptyList(),
    /** Shows the compare switch, with this label. */
    val compareLabel: String? = null,
    /** Labels spread under the chart; with [perBar] each bar has its own instead. */
    val axis: List<String> = emptyList(),
    val perBar: Boolean = false,
    val average: Float? = null,
    val averageLabel: String = "",
    val stacked: Boolean = false,
    val legend: List<Pair<String, Color?>> = emptyList(),
    val gridCols: Int = 24,
    val gridRows: List<Pair<String, List<Int>>> = emptyList(),
    val colHeads: List<String> = emptyList(),
    val cellHeight: Dp = 16.dp,
    val summary: List<Triple<String, String, String>> = emptyList(),
    val insights: List<String> = emptyList(),
    val table: Pair<String, List<Triple<String, String, String>>>? = null,
)

// ─── The page ───────────────────────────────────────────────────────────────

/**
 * One chart, opened to be looked at closely: tap a bar, square or row (or
 * step with the arrows) and the readout below says exactly what it holds and
 * how it compares. A page of its own route, so Back is the system's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsChartScreen(
    navController: NavController,
    chart: StatsChart,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableIntStateOf(0) }
    var compare by rememberSaveable { mutableStateOf(true) }
    var sel by rememberSaveable(view) { mutableIntStateOf(-1) }

    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(haze))
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            val r = report
            // No rows (an album chart once no play has an album) reads as the empty page, not a crash.
            val model = if (r == null || r.isEmpty) null else buildChart(chart, r, view).takeIf { it.entries.isNotEmpty() }
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            if (model != null) Kicker(model.eyebrow)
                            Text(
                                (model?.title ?: stringResource(R.string.listening_stats)).uppercase(statsLocale()),
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackSafe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                when {
                    r == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    model == null -> Muted(stringResource(R.string.no_plays_for_range), Modifier.padding(24.dp))
                    else -> {
                        val n = model.entries.size
                        val current = sel.takeIf { it in 0 until n && !model.entries[it].disabled } ?: model.defaultSel.coerceIn(0, (n - 1).coerceAtLeast(0))
                        ChartPage(
                            model = model, haze = haze, selected = current,
                            onSelect = { sel = it },
                            view = view, onView = { view = it },
                            compare = compare, onCompare = { compare = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartPage(
    model: ChartModel,
    haze: HazeState,
    selected: Int,
    onSelect: (Int) -> Unit,
    view: Int,
    onView: (Int) -> Unit,
    compare: Boolean,
    onCompare: (Boolean) -> Unit,
) {
    val n = model.entries.size
    val step: (Int) -> Unit = { dir ->
        var k = selected
        for (c in 0 until n) {
            k = (k + dir + n) % n
            if (!model.entries[k].disabled) break
        }
        onSelect(k)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 16.dp + LocalBottomChromeInset.current),
    ) {
        item { Muted(model.headline, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (model.views.size > 1 || model.compareLabel != null) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.views.size > 1) model.views.forEachIndexed { i, label ->
                    GlassChoiceChip(label = label, selected = i == view, accent = MaterialTheme.colorScheme.primary, onClick = { onView(i) }, height = 44.dp)
                }
                model.compareLabel?.let {
                    GlassChoiceChip(
                        label = it, selected = compare, accent = MaterialTheme.colorScheme.primary,
                        onClick = { onCompare(!compare) }, height = 44.dp,
                        leadingIcon = if (compare) Icons.Default.Check else null,
                    )
                }
            }
        }
        item {
            StatsCard(haze) {
                Text(stringResource(R.string.stats_chart_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (model.kind) {
                    ChartKind.BARS -> BarsChart(model, selected, compare, onSelect)
                    ChartKind.GRID -> GridChart(model, selected, onSelect)
                    ChartKind.RANKED -> RankedChart(model, selected, onSelect)
                }
                // The dashed entry is the overlay the compare switch draws.
                val legend = model.legend.filter { compare || it.second != null }
                if (legend.isNotEmpty()) {
                    FlowRowOf(legend) { (label, color) -> LegendDot(label, color ?: Color.Transparent, dashed = color == null) }
                }
            }
        }
        item { ReadoutCard(model.entries[selected].readout, selected, model, haze, step) }
        if (model.summary.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                model.summary.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, value, sub) -> SummaryTile(label, value, sub, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (model.insights.isNotEmpty()) item {
            StatsCard(haze) {
                BigWords(stringResource(R.string.stats_what_stands_out), scale = 0.5f)
                model.insights.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        model.table?.let { (title, rows) ->
            if (rows.isNotEmpty()) item {
                StatsCard(haze) {
                    BigWords(title, scale = 0.5f)
                    rows.forEach { (label, value, sub) ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, modifier = Modifier.width(84.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadoutCard(readout: Readout, selected: Int, model: ChartModel, haze: HazeState, step: (Int) -> Unit) {
    val enabled = model.entries.count { !it.disabled }
    val position = model.entries.take(selected + 1).count { !it.disabled }
    StatsCard(haze) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(readout.kicker, Modifier.weight(1f))
                Text(stringResource(R.string.stats_position, position, enabled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { step(-1) }) { Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.action_previous)) }
                IconButton(onClick = { step(1) }) { Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_next)) }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                BigWords(readout.value, scale = 1.1f)
                if (readout.unit.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(readout.unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            readout.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (readout.chips.isNotEmpty()) ChipFlow(readout.chips)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(chips: List<String>) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach {
            Text(
                it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(shape).background(ink.copy(alpha = 0.1f)).border(1.dp, ink.copy(alpha = 0.16f), shape).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, sub: String, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(ink.copy(alpha = 0.06f)).border(1.dp, ink.copy(alpha = 0.12f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value.uppercase(statsLocale()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

// ─── The three kinds of chart ───────────────────────────────────────────────

private val BAR_AREA = 176.dp

@Composable
private fun BarsChart(model: ChartModel, selected: Int, compare: Boolean, onSelect: (Int) -> Unit) {
    val glass = glassBarsOn()
    val tint = MaterialTheme.colorScheme.primary
    val lower = if (model.stacked) MaterialTheme.colorScheme.secondary else tint
    val ink = MaterialTheme.colorScheme.onSurface
    val entries = model.entries
    val n = entries.size
    val max = entries.maxOf { maxOf(it.value + it.top, if (compare) it.prev ?: 0f else 0f) }.coerceAtLeast(0.0001f)
    val gapDp = when { n > 20 -> 3.dp; n > 8 -> 6.dp; else -> 12.dp }
    if (model.average != null) {
        Text(model.averageLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(BAR_AREA)
            .semantics { contentDescription = model.title }
            .pointerInput(n) {
                detectTapGestures { p ->
                    val i = (p.x / size.width * n).toInt().coerceIn(0, n - 1)
                    if (!entries[i].disabled) onSelect(i)
                }
            },
    ) {
        val gap = gapDp.toPx()
        val cw = (size.width - gap * (n - 1)) / n
        val inset = cw * (if (n > 20) 0.06f else 0.12f)
        val corner = if (n > 20) 3.dp.toPx() else if (n > 8) 6.dp.toPx() else 10.dp.toPx()
        entries.forEachIndexed { i, e ->
            val x = i * (cw + gap)
            val on = i == selected
            val prev = e.prev
            if (compare && prev != null && prev > 0f) {
                val h = size.height * prev / max
                drawRoundRect(
                    ink.copy(alpha = 0.45f), Offset(x, size.height - h), Size(cw, h), CornerRadius(corner),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            val lowH = if (e.value > 0f) (size.height * e.value / max).coerceAtLeast(3.dp.toPx()) else 2.dp.toPx()
            val bx = x + inset
            val bw = cw - 2 * inset
            if (e.value > 0f) glassBar(Offset(bx, size.height - lowH), Size(bw, lowH), lower, on, glass, corner)
            else drawRoundRect(ink.copy(alpha = 0.2f), Offset(bx, size.height - lowH), Size(bw, lowH), CornerRadius(1.dp.toPx()))
            var stackTop = size.height - lowH
            if (e.top > 0f) {
                val th = size.height * e.top / max
                stackTop -= th + 2.dp.toPx()
                glassBar(Offset(bx, stackTop), Size(bw, th), tint, on, glass, corner)
            }
            if (on) {
                drawRoundRect(
                    ink, Offset(bx - 3.dp.toPx(), stackTop - 3.dp.toPx()), Size(bw + 6.dp.toPx(), size.height - stackTop + 3.dp.toPx()),
                    CornerRadius(corner + 3.dp.toPx()), style = Stroke(2.dp.toPx()),
                )
            }
        }
        model.average?.let { avg ->
            val y = size.height - size.height * avg / max
            drawLine(ink.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        }
    }
    if (model.perBar) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gapDp)) {
            entries.forEachIndexed { i, e ->
                Text(
                    e.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else if (model.axis.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            model.axis.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun GridChart(model: ChartModel, selected: Int, onSelect: (Int) -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    val onTint = MaterialTheme.colorScheme.onPrimary
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = model.entries.maxOf { it.value }.coerceAtLeast(0.0001f)
    val labelW = if (model.gridRows.any { it.first.isNotEmpty() }) 30.dp else 0.dp
    val gap = if (model.gridCols > 7) 2.dp else 6.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        if (model.colHeads.isNotEmpty()) {
            Row(Modifier.padding(start = labelW), horizontalArrangement = Arrangement.spacedBy(gap)) {
                model.colHeads.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = muted) }
            }
        }
        model.gridRows.forEach { (label, idx) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (labelW > 0.dp) Text(label, modifier = Modifier.width(labelW), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    idx.forEach { k ->
                        val e = model.entries[k]
                        val t = e.value / peak
                        val shape = RoundedCornerShape(if (model.gridCols > 7) 3.dp else 10.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(model.cellHeight)
                                .clip(shape)
                                .background(
                                    when {
                                        e.disabled -> Color.Transparent
                                        t < 0.05f -> ink.copy(alpha = 0.06f)
                                        else -> tint.copy(alpha = 0.14f + 0.86f * t)
                                    },
                                )
                                .then(
                                    when {
                                        k == selected -> Modifier.border(2.dp, ink, shape)
                                        e.disabled -> Modifier.border(1.dp, ink.copy(alpha = 0.12f), shape)
                                        else -> Modifier
                                    },
                                )
                                .then(if (e.disabled) Modifier else Modifier.clickable(onClickLabel = e.description) { onSelect(k) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (e.text.isNotEmpty()) {
                                Text(
                                    e.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        e.disabled -> muted.copy(alpha = 0.5f)
                                        t > 0.55f -> onTint
                                        e.value == 0f -> muted
                                        else -> ink
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (model.axis.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = labelW), horizontalArrangement = Arrangement.SpaceBetween) {
                model.axis.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
            }
        }
    }
}

@Composable
private fun RankedChart(model: ChartModel, selected: Int, onSelect: (Int) -> Unit) {
    val glass = glassBarsOn()
    val tint = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val peak = model.entries.maxOf { it.value }.coerceAtLeast(0.0001f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        model.entries.forEachIndexed { i, e ->
            val on = i == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (on) tint.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (on) tint.copy(alpha = 0.5f) else ink.copy(alpha = 0.06f), shape)
                    .clickable(onClickLabel = e.description) { onSelect(i) }
                    .heightIn(min = 58.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", modifier = Modifier.width(26.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = if (on) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                        drawRoundRect(ink.copy(alpha = 0.06f), cornerRadius = CornerRadius(size.height / 2))
                        val w = (size.width * e.value / peak).coerceAtLeast(size.height)
                        glassBar(Offset.Zero, Size(w, size.height), tint, on, glass, size.height / 2)
                    }
                    if (e.sub.isNotEmpty()) Text(e.sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─── What each chart holds ──────────────────────────────────────────────────

@Composable
private fun buildChart(chart: StatsChart, r: StatsReport, view: Int): ChartModel {
    val locale = statsLocale()
    return when (chart) {
        StatsChart.MINUTES -> minutesChart(r, view, locale)
        StatsChart.CLOCK -> clockChart(r, view, locale)
        StatsChart.WEEKDAY -> weekdayChart(r, view, locale)
        StatsChart.ARTISTS -> artistsChart(r, view, locale)
        StatsChart.TRACKS -> tracksChart(r, locale)
        StatsChart.ALBUMS -> albumsChart(r, locale)
        StatsChart.DISCOVERY -> discoveryChart(r, locale)
        StatsChart.QUALITY -> qualityChart(r, view, locale)
        StatsChart.SOURCES -> qualityChart(r, if (view == 0) 1 else 0, locale, sourcesFirst = true)
        StatsChart.STREAK -> streakChart(r, locale)
        StatsChart.SESSIONS -> sessionsChart(r, view, locale)
        StatsChart.MONTHS -> monthsChart(r, locale)
        StatsChart.VARIETY -> varietyChart(r, view, locale)
        StatsChart.LENGTH -> lengthChart(r, locale)
    }
}

@Composable
private fun signedDuration(seconds: Long): String = (if (seconds >= 0) "+" else "−") + durationText(kotlin.math.abs(seconds))

@Composable
private fun plays(n: Int): String = pluralStringResource(R.plurals.plays_count, n, n.toString())

@Composable
private fun minutesChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val weekly = view == 1 && r.timelineUnit == BucketUnit.DAY && r.timeline.size > 7
    val buckets = if (!weekly) r.timeline else r.timeline.chunked(7).map { c ->
        TimeBucket(c.first().start, c.sumOf { it.days }, c.sumOf { it.seconds }, c.sumOf { it.plays }, if (c.any { it.prevSeconds != null }) c.sumOf { it.prevSeconds ?: 0 } else null)
    }
    val unit = if (weekly) BucketUnit.WEEK else r.timelineUnit
    val avg = r.seconds / buckets.size.coerceAtLeast(1)
    val best = buckets.indices.maxByOrNull { buckets[it].seconds } ?: 0
    val wk = r.weekdays
    val weekendAvg = wk.filter { it.day == DayOfWeek.SATURDAY || it.day == DayOfWeek.SUNDAY }.let { l -> l.sumOf { it.seconds } / l.sumOf { it.dates.size }.coerceAtLeast(1) }
    val weekdayAvg = wk.filter { it.day != DayOfWeek.SATURDAY && it.day != DayOfWeek.SUNDAY }.let { l -> l.sumOf { it.seconds } / l.sumOf { it.dates.size }.coerceAtLeast(1) }
    val prev = r.prevSeconds
    return ChartModel(
        eyebrow = stringResource(R.string.stats_time_listened),
        title = stringResource(R.string.stats_time_listened),
        headline = durationText(r.seconds) + (r.changePercent?.let { " · " + stringResource(R.string.stats_vs_previous_pct, signedPercentText(it)) } ?: ""),
        kind = ChartKind.BARS,
        entries = buckets.map { b ->
            ChartEntry(
                label = bucketAxisLabel(b, unit, locale), value = b.seconds / 60f, prev = b.prevSeconds?.let { it / 60f },
                description = bucketLabel(b, unit, locale) + ", " + durationText(b.seconds),
                readout = Readout(
                    kicker = bucketLabel(b, unit, locale), value = durationText(b.seconds),
                    lines = buildList {
                        add(stringResource(R.string.stats_vs_average, signedDuration(b.seconds - avg)))
                        b.prevSeconds?.let { add(stringResource(R.string.stats_vs_previous, durationText(it))) }
                        add(plays(b.plays))
                    },
                ),
            )
        },
        defaultSel = best,
        views = if (r.timelineUnit == BucketUnit.DAY && r.timeline.size > 7) listOf(stringResource(R.string.stats_view_day), stringResource(R.string.stats_view_week)) else emptyList(),
        compareLabel = if (prev != null) stringResource(R.string.stats_compare_previous) else null,
        axis = if (buckets.size > 12) listOf(buckets.first(), buckets[buckets.size / 2], buckets.last()).map { bucketAxisLabel(it, unit, locale) } else emptyList(),
        perBar = buckets.size <= 12,
        average = avg / 60f,
        averageLabel = stringResource(R.string.stats_average_line, durationText(avg)),
        legend = if (prev != null) listOf(stringResource(R.string.stats_legend_this_period) to MaterialTheme.colorScheme.primary, stringResource(R.string.stats_legend_previous) to null) else emptyList(),
        summary = listOf(
            Triple(stringResource(R.string.stats_total), durationText(r.seconds), r.changePercent?.let { stringResource(R.string.stats_vs_previous_pct, signedPercentText(it)) } ?: ""),
            Triple(stringResource(R.string.stats_daily_average), durationText(r.dailyAverageSeconds), ""),
            Triple(stringResource(R.string.stats_best), bucketAxisLabel(buckets[best], unit, locale), durationText(buckets[best].seconds)),
            Triple(stringResource(R.string.stats_days_listened), stringResource(R.string.stats_progress, r.streak.daysListened, r.streak.windowDays), ""),
        ),
        insights = buildList {
            if (prev != null && prev > 0) add(stringResource(if (r.seconds >= prev) R.string.stats_hero_more else R.string.stats_hero_less, durationText(kotlin.math.abs(r.seconds - prev))))
            add(stringResource(R.string.stats_insight_weekend, durationText(weekendAvg), durationText(weekdayAvg)))
            if (r.streak.daysOff.isNotEmpty()) add(stringResource(R.string.stats_insight_days_off, r.streak.daysOff.size))
        },
        table = if (!weekly && r.timelineUnit == BucketUnit.DAY && r.timeline.size > 7) {
            stringResource(R.string.stats_by_week) to r.timeline.chunked(7).map { c ->
                val s = c.sumOf { it.seconds }
                Triple(stringResource(R.string.stats_week_of, shortDate(c.first().start, locale)), durationText(s), stringResource(R.string.stats_per_day, durationText(s / c.size)))
            }
        } else null,
    )
}

@Composable
private fun clockChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val heat = r.heat!!
    val total = heat.total.coerceAtLeast(1)
    val days = DayOfWeek.entries
    val cells = days.flatMap { d -> (0 until 24).map { h -> Triple(d, h, heat.at(d, h)) } }
    val rank = cells.sortedByDescending { it.third }.mapIndexed { i, c -> (c.first to c.second) to i + 1 }.toMap()
    val hours = (0 until 24).map { h -> days.sumOf { heat.at(it, h) } }
    val dayTotals = days.map { d -> (0 until 24).sumOf { heat.at(d, it) } }
    fun share(lo: Int, hi: Int) = (0 until 24).filter { h -> val hh = if (h < 5) h + 24 else h; hh in lo until hi }.sumOf { hours[it] }.toFloat() / total
    val common = @Composable { entries: List<ChartEntry>, kind: ChartKind, default: Int ->
        ChartModel(
            eyebrow = stringResource(R.string.stats_listening_type),
            title = stringResource(personaRes(heat.persona)),
            headline = stringResource(R.string.stats_peak_headline, weekdayName(heat.peakDay, locale), hourText(heat.peakHour, locale)) + ". " +
                stringResource(R.string.stats_night_share, percentText(heat.nightShare, locale)),
            kind = kind, entries = entries, defaultSel = default,
            views = listOf(stringResource(R.string.stats_view_grid), stringResource(R.string.stats_view_by_hour)),
            axis = listOf(0, 6, 12, 18).map { hourText(it, locale) },
            gridCols = 24,
            gridRows = days.mapIndexed { i, d -> weekdayName(d, locale, TextStyle.SHORT) to (0 until 24).map { i * 24 + it } },
            summary = listOf(
                Triple(stringResource(R.string.peak_hour), weekdayName(heat.peakDay, locale, TextStyle.SHORT) + " " + hourText(heat.peakHour, locale), ""),
                Triple(stringResource(R.string.stats_after_nine), percentText(heat.nightShare, locale), ""),
                Triple(stringResource(R.string.stats_quietest_hour), hourText(hours.indexOf(hours.min()), locale), ""),
                Triple(stringResource(R.string.stats_busiest_day), weekdayName(days[dayTotals.indexOf(dayTotals.max())], locale), ""),
            ),
            table = stringResource(R.string.time_of_day) to listOf(
                Triple(stringResource(R.string.stats_morning), percentText(share(5, 12), locale), ""),
                Triple(stringResource(R.string.stats_afternoon), percentText(share(12, 17), locale), ""),
                Triple(stringResource(R.string.stats_evening), percentText(share(17, 21), locale), ""),
                Triple(stringResource(R.string.stats_night), percentText(share(21, 29), locale), ""),
            ),
        )
    }
    return if (view == 0) {
        val entries = cells.map { (d, h, v) ->
            val label = weekdayName(d, locale) + ", " + hourText(h, locale)
            ChartEntry(
                label = "", value = v.toFloat(), description = label,
                readout = Readout(
                    kicker = label, value = percentText(v.toFloat() / total, locale), unit = stringResource(R.string.stats_of_listening),
                    lines = listOf(stringResource(R.string.stats_about_time, durationText(v)), stringResource(R.string.stats_hour_rank, rank.getValue(d to h), 168)),
                ),
            )
        }
        common(entries, ChartKind.GRID, (heat.peakDay.value - 1) * 24 + heat.peakHour)
    } else {
        val entries = hours.mapIndexed { h, v ->
            ChartEntry(
                label = "", value = v.toFloat(), description = hourText(h, locale),
                readout = Readout(
                    kicker = hourText(h, locale), value = percentText(v.toFloat() / total, locale), unit = stringResource(R.string.stats_of_listening),
                    lines = listOf(stringResource(R.string.stats_about_time, durationText(v))),
                ),
            )
        }
        common(entries, ChartKind.BARS, hours.indexOf(hours.max()))
    }
}

@Composable
private fun weekdayChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val wk = r.weekdays
    val values = wk.map { if (view == 0) it.averageSeconds else it.seconds }
    val best = values.indexOf(values.max())
    val low = values.indexOf(values.min())
    val daily = r.dailyAverageSeconds
    val weekendShare = wk.filter { it.day == DayOfWeek.SATURDAY || it.day == DayOfWeek.SUNDAY }.sumOf { it.seconds }.toFloat() / r.seconds.coerceAtLeast(1)
    return ChartModel(
        eyebrow = stringResource(R.string.day_of_week),
        title = stringResource(R.string.stats_best_weekday),
        headline = stringResource(R.string.stats_weekday_headline, weekdayName(wk[best].day, locale), weekdayName(wk[low].day, locale)),
        kind = ChartKind.BARS,
        entries = wk.mapIndexed { i, w ->
            ChartEntry(
                label = weekdayName(w.day, locale, TextStyle.SHORT), value = values[i].toFloat(), description = weekdayName(w.day, locale),
                readout = Readout(
                    kicker = weekdayName(w.day, locale), value = durationText(values[i]),
                    unit = stringResource(if (view == 0) R.string.stats_on_average_unit else R.string.stats_in_total_unit),
                    lines = listOf(
                        stringResource(R.string.stats_weekday_total, durationText(w.seconds), pluralStringResource(R.plurals.days_count, w.dates.size, w.dates.size)),
                        stringResource(R.string.stats_vs_average, signedDuration(w.averageSeconds - daily)),
                    ),
                ),
            )
        },
        defaultSel = best,
        views = listOf(stringResource(R.string.stats_view_average), stringResource(R.string.stats_view_total)),
        perBar = true,
        average = if (view == 0) daily.toFloat() else null,
        averageLabel = stringResource(R.string.stats_average_line, durationText(daily)),
        summary = listOf(
            Triple(stringResource(R.string.stats_best), weekdayName(wk[best].day, locale, TextStyle.SHORT), durationText(values[best])),
            Triple(stringResource(R.string.stats_quietest), weekdayName(wk[low].day, locale, TextStyle.SHORT), durationText(values[low])),
            Triple(stringResource(R.string.stats_weekend_share), percentText(weekendShare, locale), ""),
            Triple(stringResource(R.string.stats_daily_average), durationText(daily), ""),
        ),
    )
}

@Composable
private fun artistsChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val a = r.topArtists
    val top5 = a.take(5).sumOf { it.plays }.toFloat() / r.plays
    val newInTop = a.filter { it.isNew }.map { it.name }
    return ChartModel(
        eyebrow = stringResource(R.string.stats_number_one_artist),
        title = stringResource(R.string.top_artists),
        headline = stringResource(R.string.stats_top5_share, percentText(top5, locale)),
        kind = ChartKind.RANKED,
        entries = a.map { s ->
            ChartEntry(
                label = s.name, value = (if (view == 0) s.plays else s.seconds).toFloat(),
                valueText = if (view == 0) plays(s.plays) else durationText(s.seconds),
                sub = if (s.isNew) stringResource(R.string.stats_new_this_period) else s.topTrack,
                description = s.name,
                readout = Readout(
                    kicker = s.name, value = if (view == 0) s.plays.toString() else durationText(s.seconds),
                    unit = if (view == 0) pluralStringResource(R.plurals.stats_plays_unit, s.plays) else "",
                    lines = buildList {
                        add(stringResource(R.string.stats_share_of_plays, percentText(s.plays.toFloat() / r.plays, locale)))
                        if (s.topTrack.isNotEmpty()) add(stringResource(R.string.stats_top_track_line, s.topTrack, s.topTrackPlays))
                        add(if (s.isNew) stringResource(R.string.stats_new_this_period) else stringResource(R.string.stats_vs_previous_plays, (if (s.plays - s.prevPlays >= 0) "+" else "−") + kotlin.math.abs(s.plays - s.prevPlays)))
                    },
                ),
            )
        },
        defaultSel = 0,
        views = listOf(stringResource(R.string.stats_view_plays), stringResource(R.string.stats_view_time)),
        summary = listOf(
            Triple(stringResource(R.string.stats_artists), r.uniqueArtists.toString(), r.prevUniqueArtists?.let { stringResource(R.string.stats_vs_previous, it.toString()) } ?: ""),
            Triple(stringResource(R.string.stats_new_to_you), r.newArtists.size.toString(), ""),
            Triple(stringResource(R.string.stats_top5_label), percentText(top5, locale), ""),
            Triple(stringResource(R.string.stats_one_play_only), r.oneOffArtists.toString(), ""),
        ),
        insights = buildList {
            if (a.size >= 2) add(stringResource(R.string.stats_insight_lead, a[0].name, a[0].plays - a[1].plays))
            if (newInTop.isNotEmpty()) add(stringResource(R.string.stats_insight_new_in_top, newInTop.joinToString(", ")))
        },
    )
}

@Composable
private fun tracksChart(r: StatsReport, locale: Locale): ChartModel {
    val t = r.topTracks
    @Composable
    fun move(rank: Int, prevRank: Int?): String = when {
        prevRank == null -> stringResource(R.string.stats_not_played_before)
        prevRank == rank -> stringResource(R.string.stats_same_place)
        prevRank > rank -> stringResource(R.string.stats_up_places, prevRank - rank)
        else -> stringResource(R.string.stats_down_places, rank - prevRank)
    }
    return ChartModel(
        eyebrow = stringResource(R.string.stats_on_repeat),
        title = stringResource(R.string.top_tracks),
        headline = stringResource(R.string.stats_tracks_headline, t.first().title, plays(t.first().plays)),
        kind = ChartKind.RANKED,
        entries = t.mapIndexed { i, s ->
            ChartEntry(
                label = s.title, value = s.plays.toFloat(), valueText = plays(s.plays), sub = s.artist + " · " + move(i + 1, s.prevRank),
                description = s.title,
                readout = Readout(
                    kicker = s.artist, value = s.plays.toString(), unit = pluralStringResource(R.plurals.stats_plays_unit, s.plays),
                    lines = buildList {
                        add(s.title + (s.album?.let { " · $it" } ?: "") + " (" + mmss(s.lengthSeconds) + ")")
                        add(stringResource(R.string.stats_time_spent, durationText(s.seconds)))
                        add(move(i + 1, s.prevRank))
                    },
                ),
            )
        },
        defaultSel = 0,
        summary = listOf(
            Triple(stringResource(R.string.stats_different_tracks), r.variety.uniqueTracks.toString(), ""),
            Triple(stringResource(R.string.stats_played_once), r.variety.buckets.first().tracks.toString(), ""),
            Triple(stringResource(R.string.stats_top_track), t.first().title, plays(t.first().plays)),
            Triple(stringResource(R.string.stats_repeat_plays), percentText(r.variety.repeatPlays.toFloat() / r.plays, locale), ""),
        ),
    )
}

@Composable
private fun albumsChart(r: StatsReport, locale: Locale): ChartModel {
    val al = r.topAlbums
    return ChartModel(
        eyebrow = stringResource(R.string.stats_top_album),
        title = stringResource(R.string.top_albums),
        headline = al.firstOrNull()?.let { stringResource(R.string.stats_albums_headline, it.title, it.artist) } ?: "",
        kind = ChartKind.RANKED,
        entries = al.map { s ->
            ChartEntry(
                label = s.title, value = s.plays.toFloat(), valueText = plays(s.plays), sub = s.artist, description = s.title,
                readout = Readout(
                    kicker = s.artist, value = s.plays.toString(), unit = pluralStringResource(R.plurals.stats_plays_unit, s.plays),
                    lines = listOf(s.title, stringResource(R.string.stats_share_of_plays, percentText(s.plays.toFloat() / r.plays, locale)), stringResource(R.string.stats_most_played, s.topTrack)),
                ),
            )
        },
        defaultSel = 0,
        summary = listOf(
            Triple(stringResource(R.string.stats_albums_played), r.albumsPlayed.toString(), ""),
            Triple(stringResource(R.string.stats_top_album), al.firstOrNull()?.title ?: "", al.firstOrNull()?.let { plays(it.plays) } ?: ""),
            Triple(stringResource(R.string.stats_top3_share), percentText(al.take(3).sumOf { it.plays }.toFloat() / r.plays, locale), ""),
            Triple(stringResource(R.string.stats_time_listened), durationText(al.sumOf { it.seconds }), ""),
        ),
    )
}

@Composable
private fun discoveryChart(r: StatsReport, locale: Locale): ChartModel {
    val d = r.discovery
    val most = d.indices.maxByOrNull { d[it].newArtists.size } ?: 0
    return ChartModel(
        eyebrow = stringResource(R.string.stats_discovery),
        title = stringResource(R.string.stats_new_vs_familiar),
        headline = pluralStringResource(R.plurals.stats_artists_count, r.uniqueArtists, r.uniqueArtists) + " " + stringResource(R.string.stats_new_count, r.newArtists.size),
        kind = ChartKind.BARS,
        stacked = true,
        entries = d.map { b ->
            val bucket = TimeBucket(b.start, b.days, 0, 0, null)
            val shown = b.newArtists.take(4)
            ChartEntry(
                label = bucketAxisLabel(bucket, r.discoveryUnit, locale), value = b.knownPlays.toFloat(), top = b.newPlays.toFloat(),
                description = bucketLabel(bucket, r.discoveryUnit, locale),
                readout = Readout(
                    kicker = bucketLabel(bucket, r.discoveryUnit, locale), value = b.newArtists.size.toString(),
                    unit = pluralStringResource(R.plurals.stats_new_artists_unit, b.newArtists.size),
                    lines = listOf(
                        stringResource(R.string.stats_new_plays_line, b.newPlays),
                        stringResource(R.string.stats_known_plays_line, b.knownPlays),
                    ),
                    chips = shown + if (b.newArtists.size > shown.size) listOf(stringResource(R.string.stats_and_more, b.newArtists.size - shown.size)) else emptyList(),
                ),
            )
        },
        defaultSel = most,
        perBar = d.size <= 12,
        axis = if (d.size > 12) listOf(d.first(), d.last()).map { bucketAxisLabel(TimeBucket(it.start, it.days, 0, 0, null), r.discoveryUnit, locale) } else emptyList(),
        legend = listOf(stringResource(R.string.stats_new_to_you) to MaterialTheme.colorScheme.primary, stringResource(R.string.stats_already_knew) to MaterialTheme.colorScheme.secondary),
        summary = listOf(
            Triple(stringResource(R.string.stats_new_artists), r.newArtists.size.toString(), ""),
            Triple(stringResource(R.string.stats_new_plays), percentText(r.newArtistPlays.toFloat() / r.plays, locale), ""),
            Triple(stringResource(R.string.stats_most_curious), bucketAxisLabel(TimeBucket(d[most].start, d[most].days, 0, 0, null), r.discoveryUnit, locale), pluralStringResource(R.plurals.stats_artists_count, d[most].newArtists.size, d[most].newArtists.size)),
            Triple(stringResource(R.string.stats_artists), r.uniqueArtists.toString(), ""),
        ),
        insights = buildList {
            if (r.newArtistPlays > 0) add(stringResource(R.string.stats_discovery_one_in, Math.round(r.plays.toFloat() / r.newArtistPlays)))
            else add(stringResource(R.string.stats_discovery_none))
        },
    )
}

@Composable
private fun qualityChart(r: StatsReport, view: Int, locale: Locale, sourcesFirst: Boolean = false): ChartModel {
    val total = r.seconds.coerceAtLeast(1)
    val good = r.quality.filter { it.tier == QualityTier.HI_RES || it.tier == QualityTier.LOSSLESS }.sumOf { it.seconds }
    val local = r.sources.firstOrNull { it.source == PlaySource.LOCAL }?.seconds ?: 0L
    val hiRes = r.quality.firstOrNull { it.tier == QualityTier.HI_RES }?.seconds ?: 0L
    val rows: List<Triple<String, Long, Int>> = if (view == 0) r.quality.map { Triple(stringResource(tierRes(it.tier)), it.seconds, it.plays) }
    else r.sources.map { Triple(stringResource(sourceRes(it.source)), it.seconds, it.plays) }
    val views = listOf(stringResource(R.string.audio_quality), stringResource(R.string.source))
    return ChartModel(
        eyebrow = stringResource(R.string.stats_how_it_played),
        title = if (view == 0) stringResource(R.string.audio_quality) else stringResource(R.string.source),
        headline = if (view == 0) stringResource(R.string.stats_lossless_or_better, percentText(good.toFloat() / total, locale))
        else stringResource(R.string.stats_local_share, percentText(local.toFloat() / total, locale)),
        kind = ChartKind.RANKED,
        entries = rows.map { (name, s, p) ->
            ChartEntry(
                label = name, value = s.toFloat(), valueText = percentText(s.toFloat() / total, locale), sub = plays(p), description = name,
                readout = Readout(kicker = name, value = durationText(s), lines = listOf(plays(p), stringResource(R.string.stats_share_of_listening, percentText(s.toFloat() / total, locale)))),
            )
        },
        defaultSel = 0,
        views = if (sourcesFirst) views.reversed() else views,
        summary = listOf(
            Triple(stringResource(R.string.stats_lossless_label), percentText(good.toFloat() / total, locale), ""),
            Triple(stringResource(R.string.stats_tier_hi_res), durationText(hiRes), ""),
            Triple(stringResource(R.string.stats_source_local), percentText(local.toFloat() / total, locale), ""),
            Triple(stringResource(R.string.stats_source_streamed), percentText((total - local).toFloat() / total, locale), ""),
        ),
    )
}

@Composable
private fun streakChart(r: StatsReport, locale: Locale): ChartModel {
    val s = r.streak
    val today = r.window.today
    val entries = s.calendar.map { d ->
        val label = weekdayName(d.date.dayOfWeek, locale, TextStyle.SHORT) + ", " + shortDate(d.date, locale)
        ChartEntry(
            label = "", value = d.seconds.toFloat(), text = d.date.dayOfMonth.toString(), disabled = d.future, description = label,
            readout = Readout(
                kicker = label + if (d.date == today) " · " + stringResource(R.string.stats_today) else "",
                value = durationText(d.seconds),
                lines = if (d.plays == 0) listOf(stringResource(R.string.stats_no_listening_day)) else listOf(plays(d.plays)),
            ),
        )
    }
    return ChartModel(
        eyebrow = stringResource(R.string.stats_on_a_roll),
        title = pluralStringResource(R.plurals.day_streak, s.current, s.current),
        headline = stringResource(R.string.stats_streak_sub, s.daysListened, s.windowDays, pluralStringResource(R.plurals.days_count, s.longestInWindow, s.longestInWindow)),
        kind = ChartKind.GRID,
        entries = entries,
        defaultSel = s.calendar.indexOfFirst { it.date == today }.coerceAtLeast(0),
        gridCols = 7,
        gridRows = (0 until 5).map { w -> "" to (0 until 7).map { w * 7 + it } },
        colHeads = DayOfWeek.entries.map { weekdayName(it, locale, TextStyle.NARROW) },
        cellHeight = 44.dp,
        summary = listOf(
            Triple(stringResource(R.string.stats_current_streak), pluralStringResource(R.plurals.days_count, s.current, s.current), ""),
            Triple(stringResource(R.string.longest_streak), pluralStringResource(R.plurals.days_count, s.longestInWindow, s.longestInWindow), s.longestStart?.let { shortDate(it, locale) } ?: ""),
            Triple(stringResource(R.string.stats_days_listened), stringResource(R.string.stats_progress, s.daysListened, s.windowDays), ""),
            Triple(stringResource(R.string.stats_days_off), s.daysOff.size.toString(), ""),
        ),
    )
}

@Composable
private fun bucketName(lo: Int, hi: Int?, minutesOnly: Boolean): String = when {
    lo == 0 && hi != null -> stringResource(R.string.stats_under_minutes, hi)
    hi == null && !minutesOnly && lo >= 60 -> stringResource(R.string.stats_over_hours, lo / 60)
    hi == null -> stringResource(R.string.stats_over_minutes, lo)
    !minutesOnly && lo >= 60 -> stringResource(R.string.stats_range_hours, lo / 60, hi / 60)
    else -> stringResource(R.string.stats_range_minutes, lo, hi)
}

@Composable
private fun sessionsChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val s = r.sessions
    val n = s.count.coerceAtLeast(1)
    val time = s.seconds.coerceAtLeast(1)
    return ChartModel(
        eyebrow = stringResource(R.string.stats_sessions),
        title = stringResource(R.string.stats_session_length),
        headline = pluralStringResource(R.plurals.sessions_count, s.count, s.count.toString()) + " · " + stringResource(R.string.stats_avg_each, durationText(s.averageSeconds)),
        kind = ChartKind.BARS,
        entries = s.buckets.map { b ->
            val name = bucketName(b.minMinutes, b.maxMinutes, minutesOnly = false)
            ChartEntry(
                label = if (b.maxMinutes == null) "${b.minMinutes / 60}h+" else if (b.minMinutes >= 60) "${b.minMinutes / 60}–${b.maxMinutes / 60}h" else if (b.minMinutes == 0) "<${b.maxMinutes}m" else "${b.minMinutes}–${b.maxMinutes}",
                value = (if (view == 0) b.sessions else b.seconds / 60).toFloat(), description = name,
                readout = Readout(
                    kicker = name,
                    value = if (view == 0) b.sessions.toString() else durationText(b.seconds),
                    unit = if (view == 0) pluralStringResource(R.plurals.stats_sessions_unit, b.sessions) else "",
                    lines = buildList {
                        add(stringResource(R.string.stats_share_of_sessions, percentText(b.sessions.toFloat() / n, locale)))
                        add(stringResource(R.string.stats_share_of_listening, percentText(b.seconds.toFloat() / time, locale)))
                        if (b.sessions > 0) add(stringResource(R.string.stats_avg_each, durationText(b.seconds / b.sessions)))
                    },
                ),
            )
        },
        defaultSel = s.buckets.indices.maxByOrNull { s.buckets[it].sessions } ?: 0,
        views = listOf(stringResource(R.string.stats_sessions), stringResource(R.string.stats_view_time)),
        perBar = true,
        summary = listOf(
            Triple(stringResource(R.string.stats_sessions), s.count.toString(), stringResource(R.string.stats_per_day_count, "%.1f".format(locale, s.count.toFloat() / r.window.days))),
            Triple(stringResource(R.string.stats_average), durationText(s.averageSeconds), s.prevAverageSeconds?.let { stringResource(R.string.stats_vs_previous, durationText(it)) } ?: ""),
            Triple(stringResource(R.string.stats_longest), durationText(s.longestSeconds), s.longestDate?.let { shortDate(it, locale) } ?: ""),
            Triple(stringResource(R.string.stats_quick_plays), s.buckets.first().sessions.toString(), stringResource(R.string.stats_under_minutes, s.buckets.first().maxMinutes ?: 15)),
        ),
        insights = buildList {
            if (s.longestDate != null) add(stringResource(R.string.stats_insight_longest_session, durationText(s.longestSeconds), shortDate(s.longestDate, locale)))
        },
    )
}

@Composable
private fun monthsChart(r: StatsReport, locale: Locale): ChartModel {
    val m = r.months
    val full = m.dropLast(1).filter { it.seconds > 0 }
    val avg = if (full.isEmpty()) 0L else full.sumOf { it.seconds } / full.size
    val best = m.dropLast(1).maxByOrNull { it.seconds }
    val current = m.last()
    fun name(ms: MonthStat, style: TextStyle = TextStyle.FULL_STANDALONE) = ms.month.month.getDisplayName(style, locale).replaceFirstChar { it.titlecase(locale) }
    return ChartModel(
        eyebrow = stringResource(R.string.stats_long_view),
        title = stringResource(R.string.stats_months_trend),
        headline = current.paceSeconds?.let { stringResource(R.string.stats_on_pace_sentence, name(current), durationText(it)) } ?: "",
        kind = ChartKind.BARS,
        entries = m.map { ms ->
            ChartEntry(
                label = name(ms, TextStyle.NARROW_STANDALONE), value = ms.seconds / 60f, prev = ms.paceSeconds?.let { it / 60f },
                description = name(ms) + " " + ms.month.year,
                readout = Readout(
                    kicker = name(ms) + " " + ms.month.year, value = durationText(ms.seconds),
                    lines = buildList {
                        add(stringResource(R.string.stats_per_day, durationText(ms.seconds / (ms.daysElapsed ?: ms.month.lengthOfMonth()))))
                        ms.paceSeconds?.let { add(stringResource(R.string.stats_on_pace, durationText(it))) }
                        ms.topArtist?.let { add(stringResource(R.string.stats_top_artist_line, it)) }
                    },
                ),
            )
        },
        defaultSel = m.lastIndex,
        compareLabel = stringResource(R.string.stats_show_pace),
        perBar = true,
        average = if (avg > 0) avg / 60f else null,
        averageLabel = stringResource(R.string.stats_average_line, durationText(avg)),
        legend = listOf(stringResource(R.string.stats_time_listened) to MaterialTheme.colorScheme.primary, stringResource(R.string.stats_projected) to null),
        summary = listOf(
            Triple(stringResource(R.string.range_last_12m), durationText(m.sumOf { it.seconds }), ""),
            Triple(stringResource(R.string.stats_monthly_average), durationText(avg), ""),
            Triple(stringResource(R.string.stats_best), best?.let { name(it, TextStyle.SHORT_STANDALONE) } ?: "—", best?.let { durationText(it.seconds) } ?: ""),
            Triple(stringResource(R.string.stats_this_month_pace), current.paceSeconds?.let { durationText(it) } ?: "—", ""),
        ),
        insights = buildList {
            if (best != null && best.seconds > 0) add(stringResource(R.string.stats_insight_best_month, name(best), durationText(best.seconds)))
            val pace = current.paceSeconds
            if (pace != null && best != null && pace > best.seconds) add(stringResource(R.string.stats_insight_record_pace))
        },
    )
}

@Composable
private fun varietyChart(r: StatsReport, view: Int, locale: Locale): ChartModel {
    val v = r.variety
    val tracks = v.uniqueTracks.coerceAtLeast(1)
    val playsTotal = v.plays.coerceAtLeast(1)
    val top = v.buckets.last()
    return ChartModel(
        eyebrow = stringResource(R.string.stats_variety),
        title = stringResource(R.string.stats_repeat_plays),
        headline = stringResource(R.string.stats_variety_headline, percentText(v.repeatPlays.toFloat() / playsTotal, locale)),
        kind = ChartKind.BARS,
        entries = v.buckets.map { b ->
            val name = when {
                b.maxPlays == 1 -> stringResource(R.string.stats_played_once)
                b.maxPlays == null -> stringResource(R.string.stats_played_or_more, b.minPlays)
                else -> stringResource(R.string.stats_played_range, b.minPlays, b.maxPlays)
            }
            ChartEntry(
                label = if (b.maxPlays == null) "${b.minPlays}×+" else if (b.maxPlays == b.minPlays) "${b.minPlays}×" else "${b.minPlays}–${b.maxPlays}×",
                value = (if (view == 0) b.tracks else b.plays).toFloat(), description = name,
                readout = Readout(
                    kicker = name, value = (if (view == 0) b.tracks else b.plays).toString(),
                    unit = if (view == 0) pluralStringResource(R.plurals.stats_tracks_unit, b.tracks) else pluralStringResource(R.plurals.stats_plays_unit, b.plays),
                    lines = listOf(
                        stringResource(R.string.stats_share_of_tracks, percentText(b.tracks.toFloat() / tracks, locale)),
                        stringResource(R.string.stats_share_of_plays, percentText(b.plays.toFloat() / playsTotal, locale)),
                    ),
                    chips = b.examples.map { (t, n) -> "$t ×$n" },
                ),
            )
        },
        defaultSel = v.buckets.lastIndex,
        views = listOf(stringResource(R.string.stats_view_tracks), stringResource(R.string.stats_view_plays)),
        perBar = true,
        summary = listOf(
            Triple(stringResource(R.string.stats_different_tracks), v.uniqueTracks.toString(), ""),
            Triple(stringResource(R.string.stats_repeat_plays), percentText(v.repeatPlays.toFloat() / playsTotal, locale), ""),
            Triple(stringResource(R.string.stats_played_once), v.buckets.first().tracks.toString(), ""),
            Triple(stringResource(R.string.stats_most_replayed), r.topTracks.firstOrNull()?.title ?: "—", r.topTracks.firstOrNull()?.let { plays(it.plays) } ?: ""),
        ),
        insights = buildList {
            if (top.tracks > 0) add(stringResource(R.string.stats_insight_few_tracks, top.tracks, percentText(top.plays.toFloat() / playsTotal, locale)))
            add(stringResource(R.string.stats_insight_once, v.buckets.first().tracks, percentText(v.buckets.first().tracks.toFloat() / tracks, locale)))
        },
    )
}

@Composable
private fun lengthChart(r: StatsReport, locale: Locale): ChartModel {
    val l = r.lengths
    val total = l.buckets.sumOf { it.plays }.coerceAtLeast(1)
    val sweet = l.buckets.maxByOrNull { it.plays }!!
    @Composable
    fun name(b: LengthBucket): String = bucketName(b.minSeconds / 60, b.maxSeconds?.div(60), minutesOnly = true)
    val long = l.buckets.filter { it.minSeconds >= 300 }.sumOf { it.plays }
    return ChartModel(
        eyebrow = stringResource(R.string.stats_track_length),
        title = stringResource(R.string.stats_avg_length),
        headline = stringResource(R.string.stats_length_headline, mmss(l.averageSeconds), name(sweet)),
        kind = ChartKind.BARS,
        entries = l.buckets.map { b ->
            ChartEntry(
                label = if (b.maxSeconds == null) "${b.minSeconds / 60}+" else if (b.minSeconds == 0) "<${b.maxSeconds / 60}" else "${b.minSeconds / 60}–${b.maxSeconds / 60}",
                value = b.plays.toFloat(), description = name(b),
                readout = Readout(
                    kicker = name(b), value = b.plays.toString(), unit = pluralStringResource(R.plurals.stats_plays_unit, b.plays),
                    lines = listOf(stringResource(R.string.stats_share_of_plays, percentText(b.plays.toFloat() / total, locale))),
                    chips = b.examples.map { (t, s) -> "$t ${mmss(s)}" },
                ),
            )
        },
        defaultSel = l.buckets.indexOf(sweet),
        perBar = true,
        summary = listOf(
            Triple(stringResource(R.string.stats_avg_length), mmss(l.averageSeconds), stringResource(R.string.stats_per_play)),
            Triple(stringResource(R.string.stats_sweet_spot), name(sweet), percentText(sweet.plays.toFloat() / total, locale)),
            Triple(stringResource(R.string.stats_over_minutes, 5), percentText(long.toFloat() / total, locale), ""),
            Triple(stringResource(R.string.stats_under_minutes, 2), l.buckets.first().plays.toString(), ""),
        ),
    )
}
