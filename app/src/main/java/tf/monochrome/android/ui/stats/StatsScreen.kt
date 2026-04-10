package tf.monochrome.android.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { RangePicker(state.range, onPick = viewModel::setRange) }
            item { OverviewCard(state) }
            item {
                SectionCard("Plays over time") {
                    if (state.playsByDay.isNotEmpty()) {
                        DayLineChart(state.playsByDay)
                    } else EmptyHint()
                }
            }
            item {
                SectionCard("Time of day") {
                    if (state.playsByHour.isNotEmpty()) {
                        HourBarChart(state.playsByHour)
                    } else EmptyHint()
                }
            }
            item {
                SectionCard("Day of week") {
                    if (state.playsByWeekday.isNotEmpty()) {
                        WeekdayBarChart(state.playsByWeekday)
                    } else EmptyHint()
                }
            }
            item {
                SectionCard("Top tracks") {
                    if (state.topTracks.isEmpty()) EmptyHint() else TopTracksList(state.topTracks.take(10))
                }
            }
            item {
                SectionCard("Top artists") {
                    if (state.topArtists.isEmpty()) EmptyHint() else TopArtistsList(state.topArtists.take(10))
                }
            }
            item {
                SectionCard("Top albums") {
                    if (state.topAlbums.isEmpty()) EmptyHint() else TopAlbumsList(state.topAlbums.take(10))
                }
            }
            item {
                SectionCard("Audio quality") {
                    if (state.playsByQuality.isEmpty()) EmptyHint()
                    else QualityBars(state.playsByQuality)
                }
            }
            if (state.playsBySource.isNotEmpty()) {
                item {
                    SectionCard("Source") {
                        SourceBars(state.playsBySource)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(current: StatsRange, onPick: (StatsRange) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun OverviewCard(state: StatsUiState) {
    SectionCard("Overview") {
        val hours = state.totalSeconds / 3600
        val mins = (state.totalSeconds % 3600) / 60
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatTile("Plays", state.totalPlays.toString())
            StatTile("Listening", "${hours}h ${mins}m")
            StatTile("Tracks", state.uniqueTracks.toString())
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatTile("Artists", state.uniqueArtists.toString())
            StatTile("Albums", state.uniqueAlbums.toString())
            StatTile(
                "Avg/day",
                if (state.range.days != null && state.range.days > 0)
                    (state.totalPlays / state.range.days).toString()
                else "—"
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp)
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

// --- Charts ---

@Composable
private fun DayLineChart(data: List<DayAggregate>) {
    val color = MaterialTheme.colorScheme.primary
    val faded = color.copy(alpha = 0.2f)
    val maxV = (data.maxOfOrNull { it.playCount } ?: 1).coerceAtLeast(1)
    val minDay = data.first().dayEpoch
    val maxDay = data.last().dayEpoch.coerceAtLeast(minDay + 1)
    val span = (maxDay - minDay).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        val w = size.width
        val h = size.height
        val path = Path()
        val fill = Path()
        data.forEachIndexed { i, d ->
            val x = if (span == 0f) w / 2 else ((d.dayEpoch - minDay) / span) * w
            val y = h - (d.playCount / maxV.toFloat()) * h
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
        drawPath(fill, color = faded)
        drawPath(path, color = color, style = Stroke(width = 4f))
    }
}

@Composable
private fun HourBarChart(data: List<HourAggregate>) {
    val color = MaterialTheme.colorScheme.primary
    val counts = IntArray(24)
    data.forEach { if (it.hour in 0..23) counts[it.hour] = it.playCount }
    val maxV = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val w = size.width
        val h = size.height
        val barW = w / 24f * 0.7f
        val gap = w / 24f * 0.3f
        counts.forEachIndexed { i, v ->
            val bh = (v / maxV.toFloat()) * h
            drawRect(
                color = color,
                topLeft = Offset(i * (barW + gap) + gap / 2, h - bh),
                size = Size(barW, bh)
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
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val w = size.width
        val h = size.height
        val slot = w / 7f
        val barW = slot * 0.6f
        counts.forEachIndexed { i, v ->
            val bh = (v / maxV.toFloat()) * h
            drawRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barW) / 2, h - bh),
                size = Size(barW, bh)
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
            HBarRow(label = s.source, value = "${s.playCount}", fraction = pct)
        }
    }
}

@Composable
private fun HBarRow(label: String, value: String, fraction: Float) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            drawRect(color = track, size = size)
            drawRect(color = color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}

// --- Top lists ---

@Composable
private fun TopTracksList(items: List<TopTrackAggregate>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { idx, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankBadge(idx + 1)
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = t.albumCover,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        t.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PlayCountPill(t.playCount)
            }
        }
    }
}

@Composable
private fun TopArtistsList(items: List<TopArtistAggregate>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { idx, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankBadge(idx + 1)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${a.uniqueTracks} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PlayCountPill(a.playCount)
            }
        }
    }
}

@Composable
private fun TopAlbumsList(items: List<TopAlbumAggregate>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { idx, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankBadge(idx + 1)
                Spacer(Modifier.width(10.dp))
                AsyncImage(
                    model = a.albumCover,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        a.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        a.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PlayCountPill(a.playCount)
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PlayCountPill(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .padding(horizontal = 2.dp)
    ) {
        Text(
            "$count plays",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
