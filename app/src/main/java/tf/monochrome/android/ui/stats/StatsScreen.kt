package tf.monochrome.android.ui.stats

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Listening Stats") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        AnimatedContent(
            targetState = state.range,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 8 })
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "range-crossfade"
        ) { _ ->
            StatsContent(state = state, onPickRange = viewModel::setRange)
        }
    }
}

@Composable
private fun StatsContent(
    state: StatsUiState,
    onPickRange: (StatsRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { RangePicker(state.range, onPick = onPickRange) }
        item { StaggerEntry(0) { HeroMinutesCard(state) } }
        item { StaggerEntry(1) { HighlightRow(state) } }
        item {
            StaggerEntry(2) {
                SectionCard("Plays over time") {
                    if (state.playsByDay.isNotEmpty()) DayLineChart(state.playsByDay)
                    else EmptyHint()
                }
            }
        }
        item {
            StaggerEntry(3) {
                SectionCard("Time of day") {
                    if (state.playsByHour.isNotEmpty()) HourBarChart(state.playsByHour, state.peakHour)
                    else EmptyHint()
                }
            }
        }
        item {
            StaggerEntry(4) {
                SectionCard("Day of week") {
                    if (state.playsByWeekday.isNotEmpty()) WeekdayBarChart(state.playsByWeekday)
                    else EmptyHint()
                }
            }
        }
        item {
            StaggerEntry(5) {
                SectionCard("Top tracks") {
                    if (state.topTracks.isEmpty()) EmptyHint()
                    else TopTracksList(state.topTracks.take(10))
                }
            }
        }
        item {
            StaggerEntry(6) {
                SectionCard("Top artists") {
                    if (state.topArtists.isEmpty()) EmptyHint()
                    else TopArtistsList(state.topArtists.take(10))
                }
            }
        }
        item {
            StaggerEntry(7) {
                SectionCard("Top albums") {
                    if (state.topAlbums.isEmpty()) EmptyHint()
                    else TopAlbumsList(state.topAlbums.take(10))
                }
            }
        }
        item {
            StaggerEntry(8) {
                SectionCard("Audio quality") {
                    if (state.playsByQuality.isEmpty()) EmptyHint()
                    else QualityBars(state.playsByQuality)
                }
            }
        }
        if (state.playsBySource.isNotEmpty()) {
            item {
                StaggerEntry(9) {
                    SectionCard("Source") { SourceBars(state.playsBySource) }
                }
            }
        }
    }
}

// ─── Stagger helper ─────────────────────────────────────────────────────────

@Composable
private fun StaggerEntry(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffectOnce {
        kotlinx.coroutines.delay(60L * index + 40L)
        visible = true
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
            FilterChip(
                selected = current == r,
                onClick = { onPick(r) },
                label = { Text(r.label) }
            )
        }
    }
}

// ─── Hero card — minutes counter + gradient pulse ───────────────────────────

@Composable
private fun HeroMinutesCard(state: StatsUiState) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val pulse = rememberInfiniteTransition(label = "hero-pulse")
    val t by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-t"
    )
    val bg = Brush.linearGradient(
        colors = listOf(
            primary.copy(alpha = 0.22f + 0.10f * t),
            tertiary.copy(alpha = 0.18f + 0.10f * (1f - t)),
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
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
                        text = "${state.totalPlays} plays"
                    )
                    HeroPill(
                        icon = Icons.Default.Schedule,
                        text = "${state.sessionCount} sessions"
                    )
                    if (state.currentStreakDays > 0) {
                        HeroPill(
                            icon = Icons.Default.LocalFireDepartment,
                            text = "${state.currentStreakDays}-day streak",
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
        HighlightTile("Tracks", state.uniqueTracks.toString())
        HighlightTile("Artists", state.uniqueArtists.toString())
        HighlightTile("Albums", state.uniqueAlbums.toString())
        if (state.peakHour != null) {
            HighlightTile("Peak hour", "${state.peakHour}:00")
        }
        if (state.peakWeekday != null) {
            HighlightTile("Peak day", weekdayLabel(state.peakWeekday!!))
        }
        if (state.longestStreakDays > 0) {
            HighlightTile("Longest streak", "${state.longestStreakDays}d")
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
        "No plays yet for this range.",
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
    val maxDay = data.last().dayEpoch.coerceAtLeast(minDay + 1)
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
    val labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

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
                secondary = "${a.uniqueTracks} tracks",
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
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
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

private fun rangeSubtitle(r: StatsRange): String = when (r) {
    StatsRange.Week -> "Last 7 days"
    StatsRange.Month -> "Last 30 days"
    StatsRange.Quarter -> "Last 90 days"
    StatsRange.Year -> "Last 12 months"
    StatsRange.AllTime -> "All time"
}

private fun weekdayLabel(w: Int): String = when (w) {
    0 -> "Sun"; 1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"
    4 -> "Thu"; 5 -> "Fri"; 6 -> "Sat"
    else -> "—"
}

private fun prettySource(s: String): String = when (s.lowercase()) {
    "tidal" -> "TIDAL"
    "collection" -> "Collection"
    "local" -> "Local"
    "unknown", "" -> "Unknown"
    else -> s.replaceFirstChar { it.uppercase() }
}
