package tf.monochrome.android.ui.stats

import tf.monochrome.android.ui.navigation.popBackStackSafe
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.data.db.dao.DayAggregate
import tf.monochrome.android.data.db.dao.HourAggregate
import tf.monochrome.android.data.db.dao.QualityAggregate
import tf.monochrome.android.data.db.dao.SourceAggregate
import tf.monochrome.android.data.db.dao.TopAlbumAggregate
import tf.monochrome.android.data.db.dao.TopArtistAggregate
import tf.monochrome.android.data.db.dao.TopTrackAggregate
import tf.monochrome.android.data.db.dao.WeekdayAggregate
import kotlin.math.roundToInt
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()

    val statsMsgContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(statsMsgContext, msg.resolve(statsMsgContext), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.listening_stats)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = state,
                contentKey = { it.range },
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "range-crossfade"
            ) { snapshot ->
                StatsContent(
                    state = snapshot,
                    lastSyncedAt = lastSyncedAt,
                    onPickRange = viewModel::setRange,
                )
            }
        }
    }
}

@Composable
private fun StatsContent(
    state: StatsUiState,
    lastSyncedAt: Long?,
    onPickRange: (StatsRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + LocalBottomChromeInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { tf.monochrome.android.devedit.DevEditable("range_picker", Modifier.fillMaxWidth()) { RangePicker(state.range, onPick = onPickRange) } }
        if (lastSyncedAt != null) {
            item { tf.monochrome.android.devedit.DevEditable("last_synced_label") { LastSyncedLabel(lastSyncedAt) } }
        }
        item { tf.monochrome.android.devedit.DevEditable("hero_minutes_card", Modifier.fillMaxWidth()) { StaggerEntry(0) { HeroMinutesCard(state) } } }
        item { tf.monochrome.android.devedit.DevEditable("highlight_row", Modifier.fillMaxWidth()) { StaggerEntry(1) { HighlightRow(state) } } }
        item {
            tf.monochrome.android.devedit.DevEditable("section_plays_over_time", Modifier.fillMaxWidth()) {
                StaggerEntry(2) {
                    SectionCard(stringResource(R.string.plays_over_time)) {
                        if (state.playsByDay.isNotEmpty()) DayLineChart(state.playsByDay)
                        else EmptyHint()
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_time_of_day", Modifier.fillMaxWidth()) {
                StaggerEntry(3) {
                    SectionCard(stringResource(R.string.time_of_day)) {
                        if (state.playsByHour.isNotEmpty()) HourBarChart(state.playsByHour, state.peakHour)
                        else EmptyHint()
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_day_of_week", Modifier.fillMaxWidth()) {
                StaggerEntry(4) {
                    SectionCard(stringResource(R.string.day_of_week)) {
                        if (state.playsByWeekday.isNotEmpty()) WeekdayBarChart(state.playsByWeekday)
                        else EmptyHint()
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_top_tracks", Modifier.fillMaxWidth()) {
                StaggerEntry(5) {
                    SectionCard(stringResource(R.string.top_tracks)) {
                        if (state.topTracks.isEmpty()) EmptyHint()
                        else TopTracksList(state.topTracks.take(10))
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_top_artists", Modifier.fillMaxWidth()) {
                StaggerEntry(6) {
                    SectionCard(stringResource(R.string.top_artists)) {
                        if (state.topArtists.isEmpty()) EmptyHint()
                        else TopArtistsList(state.topArtists.take(10))
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_top_albums", Modifier.fillMaxWidth()) {
                StaggerEntry(7) {
                    SectionCard(stringResource(R.string.top_albums)) {
                        if (state.topAlbums.isEmpty()) EmptyHint()
                        else TopAlbumsList(state.topAlbums.take(10))
                    }
                }
            }
        }
        item {
            tf.monochrome.android.devedit.DevEditable("section_audio_quality", Modifier.fillMaxWidth()) {
                StaggerEntry(8) {
                    SectionCard(stringResource(R.string.audio_quality)) {
                        if (state.playsByQuality.isEmpty()) EmptyHint()
                        else QualityBars(state.playsByQuality)
                    }
                }
            }
        }
        if (state.playsBySource.isNotEmpty()) {
            item {
                tf.monochrome.android.devedit.DevEditable("section_source", Modifier.fillMaxWidth()) {
                    StaggerEntry(9) {
                        SectionCard(stringResource(R.string.source)) { SourceBars(state.playsBySource) }
                    }
                }
            }
        }
    }
}

// ─── Stagger helper ─────────────────────────────────────────────────────────

@Composable
private fun StaggerEntry(index: Int, content: @Composable () -> Unit) {
    // rememberSaveable + a guard so the entrance plays ONCE. LazyColumn retains
    // saveable item state across scroll, so a section scrolling off and back no
    // longer resets to invisible, replaying the animation and flashing a blank
    // gap during the stagger delay.
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffectOnce {
        if (!visible) {
            kotlinx.coroutines.delay(60L * index + 40L)
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) +
            slideInVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                initialOffsetY = { it / 6 }
            ),
    ) { content() }
}

@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

// ─── Range picker ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(current: StatsRange, onPick: (StatsRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsRange.values().forEach { r ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(r.labelRes()),
                selected = current == r,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onPick(r) },
            )
        }
    }
}

// ─── Hero card — minutes counter + gradient pulse ───────────────────────────

@Composable
private fun HeroMinutesCard(state: StatsUiState) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    // Keep the animated value as State (no `by`) and read it inside drawBehind,
    // so the pulse animates in the draw phase instead of recomposing the whole
    // hero card 60×/second. Rests mid-sweep with animations off, so the card
    // keeps its gradient rather than pinning to one end of it.
    val t = tf.monochrome.android.ui.theme.rememberMotionFloat(
        initialValue = 0f,
        targetValue = 1f,
        durationMillis = 4000,
        label = "pulse-t",
        still = 0.5f,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val tv = t.value
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.22f + 0.10f * tv),
                                tertiary.copy(alpha = 0.18f + 0.10f * (1f - tv)),
                            )
                        )
                    )
                }
                .padding(20.dp),
        ) {
            Column {
                Text(
                    rangeSubtitle(state.range),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedCounter(
                        target = state.totalSeconds / 60L,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "min",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroPill(
                        icon = Icons.Default.MusicNote,
                        text = pluralStringResource(R.plurals.plays_count, state.totalPlays, java.text.NumberFormat.getIntegerInstance().format(state.totalPlays.toLong()))
                    )
                    HeroPill(
                        icon = Icons.Default.Schedule,
                        text = pluralStringResource(R.plurals.sessions_count, state.sessionCount, java.text.NumberFormat.getIntegerInstance().format(state.sessionCount.toLong()))
                    )
                    if (state.currentStreakDays > 0) {
                        HeroPill(
                            icon = Icons.Default.LocalFireDepartment,
                            text = pluralStringResource(R.plurals.day_streak, state.currentStreakDays, state.currentStreakDays),
                            highlight = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    highlight: Boolean = false,
) {
    val bg = if (highlight) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
             else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val fg = if (highlight) MaterialTheme.colorScheme.onTertiaryContainer
             else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Medium)
    }
}

// ─── Highlight row — unique counts + peak hour/weekday + longest streak ─────

@Composable
private fun HighlightRow(state: StatsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HighlightTile(stringResource(R.string.filter_tracks), state.uniqueTracks.toString())
        HighlightTile(stringResource(R.string.filter_artists), state.uniqueArtists.toString())
        HighlightTile(stringResource(R.string.filter_albums), state.uniqueAlbums.toString())
        if (state.peakHour != null) {
            HighlightTile(stringResource(R.string.peak_hour), hourLabel(state.peakHour!!))
        }
        if (state.peakWeekday != null) {
            HighlightTile(stringResource(R.string.peak_day), weekdayLabel(state.peakWeekday!!))
        }
        if (state.longestStreakDays > 0) {
            HighlightTile(stringResource(R.string.longest_streak), pluralStringResource(R.plurals.days_count, state.longestStreakDays, state.longestStreakDays))
        }
    }
}

@Composable
private fun HighlightTile(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.width(112.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Animated counter ───────────────────────────────────────────────────────

@Composable
private fun AnimatedCounter(
    target: Long,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val value by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "counter"
    )
    Text(
        formatWithGrouping(value.roundToInt().toLong()),
        style = style,
        fontWeight = fontWeight,
        color = color,
    )
}

private fun formatWithGrouping(n: Long): String =
    if (n < 1000) n.toString()
    else n.toString().reversed().chunked(3).joinToString(",").reversed()

// ─── Section card (shared) ──────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        stringResource(R.string.no_plays_for_range),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ─── Charts ─────────────────────────────────────────────────────────────────

@Composable
private fun DayLineChart(data: List<DayAggregate>) {
    val color = MaterialTheme.colorScheme.primary
    val faded = color.copy(alpha = 0.22f)
    val ghost = color.copy(alpha = 0.0f)
    val maxV = (data.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)
    val minDay = data.first().dayEpoch
    // No coerceAtLeast: a single day keeps span == 0 so the point centers at
    // w/2 (the old +1 fudge drew a stray gradient wedge with no line).
    val maxDay = data.last().dayEpoch
    val span = (maxDay - minDay).toFloat()

    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "line-grow"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
    ) {
        val w = size.width
        val h = size.height
        // A single data point can't stroke a line (moveTo with no lineTo) — draw
        // a short flat segment at its level instead of the empty stroke + stray
        // gradient wedge (no dot markers on graphs).
        if (data.size < 2) {
            val only = data.firstOrNull() ?: return@Canvas
            val y = h - (only.playCount / maxV.toFloat()) * h * grow
            drawLine(
                color = color,
                start = Offset(w / 2 - 16f, y),
                end = Offset(w / 2 + 16f, y),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val path = Path()
        val fill = Path()
        data.forEachIndexed { i, d ->
            val x = if (span == 0f) w / 2 else ((d.dayEpoch - minDay) / span) * w
            val y = h - (d.playCount / maxV.toFloat()) * h * grow
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(faded, ghost),
                startY = 0f,
                endY = h
            )
        )
        drawPath(path, color = color, style = Stroke(width = 5f))
    }
}

@Composable
private fun HourBarChart(data: List<HourAggregate>, peakHour: Int?) {
    val color = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.tertiary
    val counts = IntArray(24)
    data.forEach { if (it.hour in 0..23) counts[it.hour] = it.playCount }
    val maxV = (counts.maxOrNull() ?: 1).coerceAtLeast(1)

    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "hour-grow"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp)
    ) {
        val w = size.width
        val h = size.height
        val slot = w / 24f
        val barW = slot * 0.7f
        counts.forEachIndexed { i, v ->
            val bh = (v / maxV.toFloat()) * h * grow
            val c = if (i == peakHour) peakColor else color
            val x = i * slot + (slot - barW) / 2f
            // rounded top: approximate with a 2dp corner via drawRoundRect
            drawRoundRect(
                color = c,
                topLeft = Offset(x, h - bh),
                size = Size(barW, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "23").forEach {
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeekdayBarChart(data: List<WeekdayAggregate>) {
    val color = MaterialTheme.colorScheme.secondary
    val counts = IntArray(7)
    data.forEach { if (it.weekday in 0..6) counts[it.weekday] = it.playCount }
    val maxV = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    val labels = (0..6).map { weekdayLabel(it) }

    val grow by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "weekday-grow"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
    ) {
        val w = size.width
        val h = size.height
        val slot = w / 7f
        val barW = slot * 0.58f
        counts.forEachIndexed { i, v ->
            val bh = (v / maxV.toFloat()) * h * grow
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barW) / 2f, h - bh),
                size = Size(barW, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        labels.forEach {
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QualityBars(data: List<QualityAggregate>) {
    val total = data.sumOf { it.playCount }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data.forEach { q ->
            val pct = q.playCount.toFloat() / total
            HBarRow(label = q.quality, value = "${q.playCount}", fraction = pct)
        }
    }
}

@Composable
private fun SourceBars(data: List<SourceAggregate>) {
    val total = data.sumOf { it.playCount }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data.forEach { s ->
            val pct = s.playCount.toFloat() / total
            HBarRow(label = prettySource(s.source), value = "${s.playCount}", fraction = pct)
        }
    }
}

@Composable
private fun HBarRow(label: String, value: String, fraction: Float) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val grow by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "hbar-$label"
    )
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            drawRect(color = track, size = size)
            drawRect(color = color, size = Size(size.width * grow, size.height))
        }
    }
}

// ─── Top lists ──────────────────────────────────────────────────────────────

@Composable
private fun TopTracksList(items: List<TopTrackAggregate>) {
    val maxPlays = (items.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { idx, t ->
            TopListRow(
                rank = idx + 1,
                primary = t.title,
                secondary = t.artistName,
                playCount = t.playCount,
                maxPlays = maxPlays,
                cover = t.albumCover,
            )
        }
    }
}

@Composable
private fun TopArtistsList(items: List<TopArtistAggregate>) {
    val maxPlays = (items.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { idx, a ->
            TopListRow(
                rank = idx + 1,
                primary = a.name,
                secondary = pluralStringResource(R.plurals.track_count, a.uniqueTracks, a.uniqueTracks),
                playCount = a.playCount,
                maxPlays = maxPlays,
                cover = null,
            )
        }
    }
}

@Composable
private fun TopAlbumsList(items: List<TopAlbumAggregate>) {
    val maxPlays = (items.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { idx, a ->
            TopListRow(
                rank = idx + 1,
                primary = a.title,
                secondary = a.artistName,
                playCount = a.playCount,
                maxPlays = maxPlays,
                cover = a.albumCover,
            )
        }
    }
}

@Composable
private fun TopListRow(
    rank: Int,
    primary: String,
    secondary: String,
    playCount: Int,
    maxPlays: Int,
    cover: String?,
) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val frac by animateFloatAsState(
        targetValue = playCount.toFloat() / maxPlays,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "toplist-$rank"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RankBadge(rank)
        Spacer(Modifier.width(10.dp))
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            ) {
                drawRect(color = track, size = size)
                drawRect(color = color, size = Size(size.width * frac, size.height))
            }
        }
        Spacer(Modifier.width(10.dp))
        PlayCountPill(playCount)
    }
}

@Composable
private fun RankBadge(rank: Int) {
    val medalColor = when (rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    }
    val bg = medalColor.copy(alpha = if (rank <= 3) 0.18f else 0.10f)
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = medalColor
        )
    }
}

@Composable
private fun PlayCountPill(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun rangeSubtitle(r: StatsRange): String = stringResource(
    when (r) {
        StatsRange.Week -> R.string.range_last_7d
        StatsRange.Month -> R.string.range_last_30d
        StatsRange.Quarter -> R.string.range_last_90d
        StatsRange.Year -> R.string.range_last_12m
        StatsRange.AllTime -> R.string.chart_window_all
    }
)

@androidx.annotation.StringRes
private fun StatsRange.labelRes(): Int = when (this) {
    StatsRange.Week -> R.string.stats_range_7d
    StatsRange.Month -> R.string.stats_range_30d
    StatsRange.Quarter -> R.string.stats_range_90d
    StatsRange.Year -> R.string.stats_range_1y
    StatsRange.AllTime -> R.string.stats_range_all
}

/** 0 = Sunday. The platform's own short name in the reader's language: 日, Mo., lun., Pzt. */
@Composable
private fun weekdayLabel(w: Int): String {
    if (w !in 0..6) return "—"
    val day = java.time.DayOfWeek.SUNDAY.plus(w.toLong())
    return day.getDisplayName(java.time.format.TextStyle.SHORT, currentLocale())
}

/** An hour of the day the way the reader writes times: 3 PM, 15:00, 下午3:00. */
@Composable
private fun hourLabel(hour: Int): String =
    java.time.LocalTime.of(hour.coerceIn(0, 23), 0).format(
        java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            .withLocale(currentLocale())
    )

@Composable
private fun currentLocale(): java.util.Locale =
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

@Composable
private fun prettySource(s: String): String = when (s.lowercase()) {
    "tidal" -> "TIDAL"
    "collection" -> stringResource(R.string.source_collection)
    "local" -> stringResource(R.string.source_local)
    "unknown", "" -> stringResource(R.string.unknown)
    else -> s.replaceFirstChar { it.uppercase() }
}

@Composable
private fun LastSyncedLabel(syncedAtMs: Long) {
    val now = System.currentTimeMillis()
    val text = formatRelative(now - syncedAtMs)
    Text(
        stringResource(R.string.synced_when, text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * "just now", "5 min ago", "il y a 2 h" — ICU's own relative-time wording in
 * the app's language. Not DateUtils: that formats with the *system* language,
 * which under a per-app language would put English inside a French sentence.
 */
@Composable
private fun formatRelative(deltaMs: Long): String {
    val seconds = (deltaMs / 1000L).coerceAtLeast(0)
    val fmt = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.forLocale(currentLocale()),
        null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    val last = android.icu.text.RelativeDateTimeFormatter.Direction.LAST
    return when {
        seconds < 60 -> fmt.format(
            android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
            android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW,
        )
        seconds < 3600 -> fmt.format((seconds / 60).toDouble(), last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        seconds < 86_400 -> fmt.format((seconds / 3600).toDouble(), last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> fmt.format((seconds / 86_400).toDouble(), last, android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}
