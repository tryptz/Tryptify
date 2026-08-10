package tf.monochrome.android.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.ChartSource
import tf.monochrome.android.data.charts.ChartWindow
import tf.monochrome.android.data.charts.GenreChart
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A genre's Top 100, over a window.
 *
 * The screen's one non-obvious job is telling the truth about its own numbers.
 * The two chart sources measure different things over different spans, and a
 * window can fall through to the all-time ranking when a genre is too small to
 * fill it — so every chart carries a line saying what it is actually showing.
 * Without that, "7 days" on a hard techno chart would be indistinguishable from
 * "all time", and the listener would have no way to know which they were reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreChartScreen(
    genreId: String,
    genreName: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: GenreChartViewModel = hiltViewModel(),
) {
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val window by viewModel.window.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(genreId) { viewModel.load(genreId) }
    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(chart?.genreName?.ifBlank { genreName } ?: genreName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh(force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            WindowRail(
                selected = window,
                onSelect = viewModel::selectWindow,
            )

            val entries = chart?.entries.orEmpty()
            when {
                loading && entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                entries.isEmpty() -> EmptyChart(chart)

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = MonoDimens.spacingXl),
                ) {
                    item {
                        chart?.let { Provenance(it) }
                        Button(
                            onClick = { viewModel.playAll(playerViewModel) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MonoDimens.spacingLg,
                                    vertical = MonoDimens.spacingSm,
                                ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(MonoDimens.spacingSm))
                            Text("Play the chart")
                        }
                    }
                    items(entries, key = { "${it.rank}:${it.matchKey}" }) { entry ->
                        ChartRow(
                            entry = entry,
                            onPlay = { viewModel.play(entry, playerViewModel) },
                        )
                    }
                }
            }
        }
    }
}

/** The window selector — the four spans asked for, plus the source's native one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowRail(selected: ChartWindow, onSelect: (ChartWindow) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = MonoDimens.spacingLg),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        modifier = Modifier.padding(vertical = MonoDimens.spacingSm),
    ) {
        items(ChartWindow.entries.toList(), key = { it.id }) { window ->
            FilterChip(
                selected = window == selected,
                onClick = { onSelect(window) },
                label = { Text(window.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

/**
 * What this chart is, in one line.
 *
 * Spelling out the fallback is the point: a genre with too little global
 * listening in a week gets the all-time ranking instead, and saying so is the
 * difference between a chart and a guess.
 */
@Composable
private fun Provenance(chart: GenreChart) {
    val text = buildString {
        if (chart.fellBack) {
            append("Not enough listening data for ${chart.genreName} in the last ")
            append(chart.requested.label.lowercase())
            append(" — showing all time instead")
        } else {
            append(
                when (chart.source) {
                    ChartSource.TAG_CHART -> "Ranked by global scrobbles, all time"
                    ChartSource.WINDOWED_LISTENS -> "Ranked by listens"
                }
            )
            chart.range()?.let { append(", $it") }
            // Crowd tags put popular artists in genres they don't belong to, so
            // whether this list was checked against a curated source changes how
            // much of it to believe.
            if (chart.crossChecked) append(" · cross-checked with MusicBrainz")
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = MonoDimens.spacingLg,
            vertical = MonoDimens.spacingXs,
        ),
    )
}

/** The real window boundaries the source reported, when it reported any. */
private fun GenreChart.range(): String? {
    val from = fromTs ?: return null
    val to = toTs ?: return null
    val format = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "${format.format(Date(from * 1000))} – ${format.format(Date(to * 1000))}"
}

@Composable
private fun EmptyChart(chart: GenreChart?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MonoDimens.spacingXl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No chart for this window",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Text(
            // Say which way the data thinned out, so an empty screen reads as a
            // fact about this genre in this window rather than a broken feature.
            text = "Windowed charts are drawn from global listening data, which " +
                "thins out for smaller genres. Try a longer window, or All time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartRow(entry: ChartEntry, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingSm),
    ) {
        Text(
            text = entry.rank.toString(),
            style = MaterialTheme.typography.titleMedium,
            // Tabular-ish: a proportional 1 next to a proportional 8 makes the
            // column wander, which is very visible down a hundred rows.
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(MonoDimens.spacingXs))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (entry.artworkUrl != null) {
                AsyncImage(
                    model = entry.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(MonoDimens.spacingMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.listenCount > 0) {
            Spacer(Modifier.width(MonoDimens.spacingSm))
            Text(
                text = entry.listenCount.abbreviated(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 1234 → "1.2k". Chart counts run to millions and the column is narrow. */
private fun Long.abbreviated(): String = when {
    this >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", this / 1_000_000.0)
    this >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", this / 1_000.0)
    else -> toString()
}
