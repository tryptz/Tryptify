package tf.monochrome.android.ui.stats

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.RADIO_PAGE_ID
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.settings.settingsTabIndex

/**
 * Stats: two pages under one glass pill. Stats is the listening, as glass
 * cards whose charts open their own page to be looked at closely;
 * Achievements is what has been tried, with each goal saying where to go to
 * reach it.
 *
 * [onOpenPage] switches the app's main pager (Discover, World radio) from
 * here, the way a tab tap does: the nav host closes this screen first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavController,
    onOpenPage: (String) -> Unit = {},
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val achievements by viewModel.achievements.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(0) }
    val locale = statsLocale()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg.resolve(context), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val openChart: (StatsChart) -> Unit = { navController.navigateSafe(statsChartRoute(it, range)) }
    val openTarget: (AchievementTarget, AchievementState) -> Unit = { target, state ->
        when (target) {
            AchievementTarget.DISCOVER -> onOpenPage(Screen.Discover.route)
            AchievementTarget.RADIO -> onOpenPage(RADIO_PAGE_ID)
            AchievementTarget.SETTINGS_MISSING_TAB -> navController.navigateTool(
                Screen.Settings,
                Screen.Settings.createRoute(state.remaining.firstOrNull()?.let { runCatching { settingsTabIndex(it) }.getOrNull() } ?: 0),
            )
            AchievementTarget.SETTINGS_AUDIO -> navController.navigateTool(Screen.Settings, Screen.Settings.createRoute(settingsTabIndex("Audio")))
            AchievementTarget.MIXER -> navController.navigateTool(Screen.Mixer)
            AchievementTarget.LYRICS_STUDIO -> navController.navigateTool(Screen.LyricsFxStudio)
            AchievementTarget.EQUALIZER -> navController.navigateTool(Screen.Equalizer)
        }
    }

    // The page's own backdrop for its glass, a sibling of everything drawn
    // over it — never the app-wide source this screen is itself inside.
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(haze))
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            Column(Modifier.fillMaxSize()) {
                val shareText = report?.let { shareTextFor(it) }
                TopAppBar(
                    title = {
                        Column {
                            Kicker(if (page == 0) rangeSubtitle(range) else stringResource(R.string.chart_window_all))
                            Text(
                                stringResource(if (page == 0) titleFor(range) else R.string.stats_title_trophies).uppercase(locale),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackSafe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (page == 0 && shareText != null) {
                            IconButton(onClick = { share(context, shareText) }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.stats_share))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                val unlocked = achievements.count { it.unlocked }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChoiceChip(
                        label = stringResource(R.string.stats_tab_stats),
                        selected = page == 0,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { page = 0 },
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                    GlassChoiceChip(
                        label = stringResource(R.string.stats_tab_achievements) +
                            if (achievements.isNotEmpty()) "  " + stringResource(R.string.stats_achievements_count, unlocked, achievements.size) else "",
                        selected = page == 1,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { page = 1 },
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                }
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    if (page == 0) {
                        StatsPage(report, range, lastSyncedAt, haze, viewModel::setRange, openChart, shareText)
                    } else {
                        AchievementsPage(achievements, haze, openTarget)
                    }
                }
            }
        }
    }
}

// ─── Stats ──────────────────────────────────────────────────────────────────

@Composable
private fun StatsPage(
    report: StatsReport?,
    range: StatsRange,
    lastSyncedAt: Long?,
    haze: HazeState,
    onPickRange: (StatsRange) -> Unit,
    openChart: (StatsChart) -> Unit,
    shareText: String?,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp + LocalBottomChromeInset.current),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsRange.entries.forEach { r ->
                    GlassChoiceChip(
                        label = stringResource(r.labelRes()),
                        selected = r == range,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onPickRange(r) },
                        height = 40.dp,
                    )
                }
            }
        }
        if (lastSyncedAt != null) item { LastSyncedLabel(lastSyncedAt) }
        when {
            report == null -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            report.isEmpty -> item {
                StatsCard(haze) { Muted(stringResource(R.string.no_plays_for_range)) }
            }
            else -> {
                item { HeroCard(report, haze) { openChart(StatsChart.MINUTES) } }
                report.heat?.let { heat -> item { ListeningTypeCard(heat, haze) { openChart(StatsChart.CLOCK) } } }
                if (report.topArtists.isNotEmpty()) item { TopArtistCard(report, haze) { openChart(StatsChart.ARTISTS) } }
                if (report.topTracks.isNotEmpty()) item { OnRepeatCard(report, haze) { openChart(StatsChart.TRACKS) } }
                item { DiscoveryCard(report, haze) { openChart(StatsChart.DISCOVERY) } }
                item { StreakCard(report, haze) { openChart(StatsChart.STREAK) } }
                if (report.quality.isNotEmpty()) item { QualityCard(report, haze) { openChart(StatsChart.QUALITY) } }
                item { DigDeeperCard(report, haze, openChart) }
                if (shareText != null) item {
                    Button(
                        onClick = { share(context, shareText) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.stats_share_stats), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    val locale = statsLocale()
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_time_listened), onAnalyze)
        val inHours = report.seconds >= 2 * 3600
        val big = (if (inHours) report.seconds / 3600 else report.seconds / 60).toInt()
        Row(verticalAlignment = Alignment.Bottom) {
            BigWords(big.toString(), scale = 1.9f)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    pluralStringResource(if (inHours) R.plurals.stats_hours_unit else R.plurals.stats_minutes_unit, big).uppercase(locale),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                )
                report.changePercent?.let { ChangeChip(it) }
            }
        }
        val prev = report.prevSeconds
        val busiest = report.timeline.maxByOrNull { it.seconds }
        val parts = buildList {
            if (prev != null && prev > 0) {
                val diff = report.seconds - prev
                add(stringResource(if (diff >= 0) R.string.stats_hero_more else R.string.stats_hero_less, durationText(kotlin.math.abs(diff))))
            }
            if (busiest != null && busiest.seconds > 0) {
                add(stringResource(R.string.stats_hero_busiest, bucketLabel(busiest, report.timelineUnit, locale), durationText(busiest.seconds)))
            }
        }
        if (parts.isNotEmpty()) Muted(parts.joinToString(" "))
        TimelineChart(report.timeline, showPrevious = prev != null, description = stringResource(R.string.stats_time_listened))
        val tl = report.timeline
        if (tl.size >= 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(tl.first(), tl[tl.size / 2], tl.last()).forEach {
                    Text(bucketAxisLabel(it, report.timelineUnit, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (prev != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(stringResource(R.string.stats_legend_this_period), MaterialTheme.colorScheme.primary)
                LegendDot(stringResource(R.string.stats_legend_previous), Color.Transparent, dashed = true)
            }
        }
    }
}

@Composable
private fun ChangeChip(percent: Int) {
    val up = percent >= 0
    Row(
        Modifier.clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp),
        )
        Text(signedPercentText(percent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ListeningTypeCard(heat: HeatStat, haze: HazeState, onAnalyze: () -> Unit) {
    val locale = statsLocale()
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_listening_type), onAnalyze)
        BigWords(stringResource(personaRes(heat.persona)), scale = 1.1f)
        Headline(stringResource(R.string.stats_peak_headline, weekdayName(heat.peakDay, locale), hourText(heat.peakHour, locale)))
        Muted(stringResource(R.string.stats_night_share, percentText(heat.nightShare, locale)))
        Spacer(Modifier.height(4.dp))
        HeatGrid(heat, description = stringResource(R.string.stats_peak_headline, weekdayName(heat.peakDay, locale), hourText(heat.peakHour, locale)))
    }
}

@Composable
private fun TopArtistCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    val locale = statsLocale()
    val top = report.topArtists.first()
    val five = report.topArtists.take(5)
    val share = five.sumOf { it.plays }.toFloat() / report.plays
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_number_one_artist), onAnalyze)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(top.name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSecondary)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BigWords(top.name, scale = 0.75f)
                Muted(stringResource(R.string.stats_artist_line, pluralStringResource(R.plurals.plays_count, top.plays, top.plays.toString()), percentText(top.plays.toFloat() / report.plays, locale)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Headline(stringResource(R.string.stats_top5_share, percentText(share, locale)))
        val tint = MaterialTheme.colorScheme.primary
        val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
        val shades = listOf(1f, 0.8f, 0.62f, 0.46f, 0.32f)
        ShareBar(
            five.mapIndexed { i, a -> a.plays.toFloat() to tint.copy(alpha = shades[i]) } + ((report.plays - five.sumOf { it.plays }).toFloat() to rest),
            description = five.joinToString { it.name + " " + percentText(it.plays.toFloat() / report.plays, locale) },
        )
        five.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}", modifier = Modifier.width(28.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                    color = if (i == 0) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(a.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.plays_count, a.plays, a.plays.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(percentText(a.plays.toFloat() / report.plays, locale), modifier = Modifier.width(52.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
private fun OnRepeatCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_on_repeat), onAnalyze)
        report.topTracks.take(5).forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BigWords("${i + 1}", modifier = Modifier.width(52.dp), scale = 0.9f)
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(t.artist + " · " + pluralStringResource(R.plurals.plays_count, t.plays, t.plays.toString()))
                }
            }
        }
    }
}

@Composable
private fun DiscoveryCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    val locale = statsLocale()
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_discovery), onAnalyze)
        Headline(
            pluralStringResource(R.plurals.stats_artists_count, report.uniqueArtists, report.uniqueArtists) + " " +
                stringResource(R.string.stats_new_count, report.newArtists.size),
        )
        Muted(
            if (report.newArtistPlays > 0) stringResource(R.string.stats_discovery_one_in, Math.round(report.plays.toFloat() / report.newArtistPlays))
            else stringResource(R.string.stats_discovery_none),
        )
        DiscoveryBars(
            report.discovery,
            report.discovery.map { bucketAxisLabel(TimeBucket(it.start, it.days, 0, 0, null), report.discoveryUnit, locale) },
            description = stringResource(R.string.stats_discovery),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(stringResource(R.string.stats_new_to_you), MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
            LegendDot(stringResource(R.string.stats_already_knew), MaterialTheme.colorScheme.secondary.copy(alpha = 0.82f))
        }
    }
}

@Composable
private fun StreakCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    val s = report.streak
    val locale = statsLocale()
    StatsCard(haze) {
        CardHeader(stringResource(R.string.stats_on_a_roll), onAnalyze)
        Row(verticalAlignment = Alignment.Bottom) {
            BigWords(s.current.toString(), scale = 1.4f)
            Spacer(Modifier.width(10.dp))
            Text(
                pluralStringResource(R.plurals.stats_days_straight, s.current).uppercase(locale),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Muted(stringResource(R.string.stats_streak_sub, s.daysListened, s.windowDays, pluralStringResource(R.plurals.days_count, s.longestInWindow, s.longestInWindow)))
        StreakCalendar(s.calendar, report.window.today, description = pluralStringResource(R.plurals.day_streak, s.current, s.current))
    }
}

@Composable
private fun QualityCard(report: StatsReport, haze: HazeState, onAnalyze: () -> Unit) {
    val locale = statsLocale()
    val total = report.quality.sumOf { it.seconds }.coerceAtLeast(1)
    val good = report.quality.filter { it.tier == QualityTier.HI_RES || it.tier == QualityTier.LOSSLESS }.sumOf { it.seconds }
    StatsCard(haze) {
        CardHeader(stringResource(R.string.audio_quality), onAnalyze)
        Headline(stringResource(R.string.stats_lossless_or_better, percentText(good.toFloat() / total, locale)))
        Muted(stringResource(R.string.stats_quality_sub))
        val colors = tierColors()
        ShareBar(report.quality.map { it.seconds.toFloat() to colors.getValue(it.tier) }, description = stringResource(R.string.audio_quality))
        FlowRowOf(report.quality.map { it.tier }) { tier ->
            val share = report.quality.first { it.tier == tier }.seconds.toFloat() / total
            LegendDot(stringResource(tierRes(tier)) + " " + percentText(share, locale), colors.getValue(tier).copy(alpha = 0.82f))
        }
    }
}

@Composable
private fun DigDeeperCard(report: StatsReport, haze: HazeState, openChart: (StatsChart) -> Unit) {
    val locale = statsLocale()
    StatsCard(haze) {
        Kicker(stringResource(R.string.stats_more_stats))
        BigWords(stringResource(R.string.stats_dig_deeper), scale = 0.8f)
        Muted(stringResource(R.string.stats_dig_deeper_hint))
        Spacer(Modifier.height(4.dp))
        val month = report.months.last()
        val streamed = report.sources.firstOrNull { it.source == PlaySource.STREAMED }?.seconds ?: 0L
        val bestDay = report.weekdays.maxByOrNull { it.averageSeconds }
        val tiles = buildList {
            add(Tile(StatsChart.SESSIONS, stringResource(R.string.stats_sessions), report.sessions.count.toString(), stringResource(R.string.stats_avg_each, durationText(report.sessions.averageSeconds))))
            add(Tile(StatsChart.MONTHS, stringResource(R.string.stats_months_trend), durationText(month.seconds), month.paceSeconds?.let { stringResource(R.string.stats_on_pace, durationText(it)) } ?: ""))
            add(Tile(StatsChart.VARIETY, stringResource(R.string.stats_repeat_plays), percentText(report.variety.repeatPlays.toFloat() / report.plays.coerceAtLeast(1), locale), pluralStringResource(R.plurals.track_count, report.variety.uniqueTracks, report.variety.uniqueTracks)))
            add(Tile(StatsChart.LENGTH, stringResource(R.string.stats_avg_length), mmss(report.lengths.averageSeconds), stringResource(R.string.stats_per_play)))
            add(Tile(StatsChart.SOURCES, stringResource(R.string.source), percentText(streamed.toFloat() / report.seconds.coerceAtLeast(1), locale), stringResource(R.string.stats_source_streamed)))
            if (bestDay != null) add(Tile(StatsChart.WEEKDAY, stringResource(R.string.stats_best_weekday), weekdayName(bestDay.day, locale, TextStyle.SHORT), stringResource(R.string.stats_on_average, durationText(bestDay.averageSeconds))))
            report.topAlbums.firstOrNull()?.let { add(Tile(StatsChart.ALBUMS, stringResource(R.string.stats_top_album), it.title, it.artist)) }
            report.topTracks.firstOrNull()?.let { add(Tile(StatsChart.TRACKS, stringResource(R.string.stats_top_track), it.title, it.artist)) }
        }
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> StatTile(t, Modifier.weight(1f)) { openChart(t.chart) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private data class Tile(val chart: StatsChart, val label: String, val value: String, val sub: String)

@Composable
private fun StatTile(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .clip(shape)
            .background(ink.copy(alpha = 0.05f))
            .border(1.dp, ink.copy(alpha = 0.11f), shape)
            .clickable(onClickLabel = tile.label, onClick = onClick)
            .heightIn(min = 100.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tile.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.NorthEast, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(tile.value.uppercase(statsLocale()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tile.sub.isNotEmpty()) Text(tile.sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Achievements ───────────────────────────────────────────────────────────

@Composable
private fun AchievementsPage(states: List<AchievementState>, haze: HazeState, onTarget: (AchievementTarget, AchievementState) -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle: (AchievementState) -> Unit = { s -> open = if (open == s.achievement.name) null else s.achievement.name }
    val next = Achievements.nextUp(states)
    val rest = Achievements.inProgress(states, next)
    val done = Achievements.completed(states)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp + LocalBottomChromeInset.current),
    ) {
        if (states.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        if (next != null) item {
            StatsCard(haze) {
                AchievementRow(next, expanded = open == next.achievement.name, featured = true, onClick = { toggle(next) })
                AnimatedVisibility(open == next.achievement.name) { AchievementDetails(next) { onTarget(it, next) } }
            }
        } else item {
            StatsCard(haze) { Headline(stringResource(R.string.stats_all_done)) }
        }
        if (rest.isNotEmpty()) item {
            StatsCard(haze) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigWords(stringResource(R.string.stats_in_progress), modifier = Modifier.weight(1f), scale = 0.6f)
                    Text(stringResource(R.string.stats_to_go, rest.size + (if (next != null) 1 else 0)), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Muted(stringResource(R.string.stats_tap_for_details))
                rest.forEach { s ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    AchievementRow(s, expanded = open == s.achievement.name, featured = false, onClick = { toggle(s) })
                    AnimatedVisibility(open == s.achievement.name) { AchievementDetails(s) { onTarget(it, s) } }
                }
            }
        }
        item {
            StatsCard(haze) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigWords(stringResource(R.string.stats_completed), modifier = Modifier.weight(1f), scale = 0.6f)
                    Text(stringResource(R.string.stats_earned, done.size), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (done.isEmpty()) Muted(stringResource(R.string.stats_none_completed))
                done.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { CompletedBadge(it, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementRow(state: AchievementState, expanded: Boolean, featured: Boolean, onClick: () -> Unit) {
    val copy = copyFor(state.achievement)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (featured) 60.dp else 44.dp)
                .clip(CircleShape)
                .background(if (featured) accent else accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(copy.icon, contentDescription = null, tint = if (featured) MaterialTheme.colorScheme.onPrimary else accent, modifier = Modifier.size(if (featured) 30.dp else 22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (featured) Kicker(stringResource(R.string.stats_next_up))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(copy.title).let { if (featured) it.uppercase(statsLocale()) else it },
                    modifier = Modifier.weight(1f),
                    style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = if (featured) FontWeight.Black else FontWeight.Bold,
                )
                Text(stringResource(R.string.stats_progress, state.done, state.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ProgressGlass(state.fraction)
            Text(goalText(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressGlass(fraction: Float) {
    val glass = glassBarsOn()
    val tint = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0.5f) glassBar(Offset.Zero, Size(w.coerceAtLeast(size.height), size.height), tint, selected = true, glass = glass, corner = size.height / 2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementDetails(state: AchievementState, onTarget: (AchievementTarget) -> Unit) {
    val copy = copyFor(state.achievement)
    val locale = statsLocale()
    val ink = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = ink.copy(alpha = 0.1f))
        Kicker(stringResource(R.string.stats_what_it_is))
        Text(stringResource(copy.what), style = MaterialTheme.typography.bodyMedium)
        if (copy.path.isNotEmpty()) {
            Kicker(stringResource(R.string.stats_where_to_find))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                copy.path.forEachIndexed { i, res ->
                    if (i > 0) Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Chip(stringResource(res), filled = true)
                }
            }
        }
        todoFor(state, locale)?.let { (heading, items) ->
            Kicker(heading)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { Chip(it, filled = false) }
            }
        }
        val target = copy.target
        val place = copy.place
        if (target != null && place != null && !state.unlocked) {
            Button(
                onClick = { onTarget(target) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.stats_open_place, stringResource(place)), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Chip(text: String, filled: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
        color = if (filled) ink else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(shape)
            .then(if (filled) Modifier.background(ink.copy(alpha = 0.08f)) else Modifier)
            .border(1.dp, ink.copy(alpha = if (filled) 0.14f else 0.24f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun CompletedBadge(state: AchievementState, modifier: Modifier) {
    val copy = copyFor(state.achievement)
    val accent = MaterialTheme.colorScheme.primary
    val locale = statsLocale()
    val shape = RoundedCornerShape(22.dp)
    val date = state.unlockedAt?.let { shortDate(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate(), locale) }.orEmpty()
    Column(
        modifier.clip(shape).background(accent.copy(alpha = 0.16f)).border(1.dp, accent.copy(alpha = 0.45f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(copy.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
        }
        Text(stringResource(copy.title).uppercase(locale), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(goalText(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(date.uppercase(locale), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
    }
}

// ─── Shared helpers ─────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FlowRowOf(items: List<T>, item: @Composable (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item(it) }
    }
}

@Composable
internal fun tierColors(): Map<QualityTier, Color> = mapOf(
    QualityTier.HI_RES to MaterialTheme.colorScheme.primary,
    QualityTier.LOSSLESS to MaterialTheme.colorScheme.secondary,
    QualityTier.LOSSY to MaterialTheme.colorScheme.onSurfaceVariant,
    QualityTier.UNKNOWN to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
)

internal fun tierRes(tier: QualityTier): Int = when (tier) {
    QualityTier.HI_RES -> R.string.stats_tier_hi_res
    QualityTier.LOSSLESS -> R.string.stats_tier_lossless
    QualityTier.LOSSY -> R.string.stats_tier_lossy
    QualityTier.UNKNOWN -> R.string.unknown
}

internal fun sourceRes(source: PlaySource): Int = when (source) {
    PlaySource.STREAMED -> R.string.stats_source_streamed
    PlaySource.LOCAL -> R.string.stats_source_local
}

internal fun personaRes(p: Persona): Int = when (p) {
    Persona.NIGHT_OWL -> R.string.stats_persona_night_owl
    Persona.EVENING -> R.string.stats_persona_evening
    Persona.AFTERNOON -> R.string.stats_persona_afternoon
    Persona.EARLY_BIRD -> R.string.stats_persona_early_bird
}

private fun titleFor(r: StatsRange): Int = when (r) {
    StatsRange.Week -> R.string.stats_title_week
    StatsRange.Month -> R.string.stats_title_month
    StatsRange.Quarter -> R.string.stats_title_quarter
    StatsRange.Year -> R.string.stats_title_year
    StatsRange.AllTime -> R.string.stats_title_all
}

@Composable
internal fun rangeSubtitle(r: StatsRange): String = stringResource(
    when (r) {
        StatsRange.Week -> R.string.range_last_7d
        StatsRange.Month -> R.string.range_last_30d
        StatsRange.Quarter -> R.string.range_last_90d
        StatsRange.Year -> R.string.range_last_12m
        StatsRange.AllTime -> R.string.chart_window_all
    }
)

@androidx.annotation.StringRes
internal fun StatsRange.labelRes(): Int = when (this) {
    StatsRange.Week -> R.string.stats_range_7d
    StatsRange.Month -> R.string.stats_range_30d
    StatsRange.Quarter -> R.string.stats_range_90d
    StatsRange.Year -> R.string.stats_range_1y
    StatsRange.AllTime -> R.string.stats_range_all
}

/** "Sat, Oct 3", "Week of Sep 29", "October". */
@Composable
internal fun bucketLabel(b: TimeBucket, unit: BucketUnit, locale: Locale): String = when (unit) {
    BucketUnit.DAY -> weekdayName(b.start.dayOfWeek, locale, TextStyle.SHORT) + ", " + shortDate(b.start, locale)
    BucketUnit.WEEK -> stringResource(R.string.stats_week_of, shortDate(b.start, locale))
    BucketUnit.MONTH -> b.start.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + b.start.year
}

/** The short form under a chart: "Oct 3", "Sep 29", "Oct". */
internal fun bucketAxisLabel(b: TimeBucket, unit: BucketUnit, locale: Locale): String = when (unit) {
    BucketUnit.DAY, BucketUnit.WEEK -> shortDate(b.start, locale)
    BucketUnit.MONTH -> b.start.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
}

@Composable
private fun shareTextFor(report: StatsReport): String? {
    if (report.isEmpty) return null
    return stringResource(
        R.string.stats_share_text,
        durationText(report.seconds),
        report.topArtists.firstOrNull()?.name ?: "—",
        report.topTracks.firstOrNull()?.title ?: "—",
    )
}

private fun share(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun LastSyncedLabel(syncedAtMs: Long) {
    Text(
        stringResource(R.string.synced_when, formatRelative(System.currentTimeMillis() - syncedAtMs)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
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
        android.icu.util.ULocale.forLocale(statsLocale()),
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
