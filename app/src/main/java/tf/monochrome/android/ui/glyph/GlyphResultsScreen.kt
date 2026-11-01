// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.ui.navigation.bottomChromePadding
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * How the run went, and what to do about it.
 *
 * The graph is the point of this screen. A grade tells a player whether the run
 * was good; the per-section graph tells them *where* it was not, and tapping a
 * weak section opens exactly that range as a practice loop. Making them find the
 * passage again by hand is the difference between a results screen and a
 * practice tool.
 *
 * A menu page of the app's, so it follows the theme: a top bar with Back, cards
 * of the app's glass, and the tab bar underneath. From the top: the run's
 * judgements as a ring around the grade, DDR's evaluation lines counting up,
 * this phone's high scores, the timing curve, then the graph.
 *
 * [recentLateMs] is how late the last few runs were hit, when enough agree to
 * say ([GlyphViewModel]'s calibration): the timing card offers to correct for
 * it, as Settings does, rather than for this one run alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphResultsScreen(
    results: GlyphResultsUi,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    recentLateMs: Int? = null,
    /** The room this run was played in, when it was one: its ranking goes on the page, and the way on is back to it. */
    room: GlyphMultiplayerUi? = null,
) {
    val attempt = results.attempt
    val scheme = MaterialTheme.colorScheme

    // Its own ground under the whole page, as the start menu has: what it
    // fades in over is the play, the cover and its glow, and nothing of that
    // may show through between the cards.
    Column(modifier = modifier.fillMaxSize().background(scheme.background)) {
        TopAppBar(
            title = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = attempt.songTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    // A run auto play made is shown, never kept: say so, or
                    // its perfect score reads as the player's own.
                    if (results.auto) {
                        Text(
                            text = stringResource(R.string.tm_results_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.BackToHome) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = MonoDimens.spacingLg, end = MonoDimens.spacingLg, top = MonoDimens.spacingSm),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingLg),
        ) {
            GlyphEvaluationPanel(
                results = results,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxWidth(),
            )

            GlyphHighScoresPanel(
                results = results,
                modifier = Modifier.fillMaxWidth(),
            )

            TimingPanel(
                attempt = attempt,
                recentLateMs = recentLateMs,
                onCorrect = { onEvent(GlyphEvent.ApplyRecentTiming) },
            )

            if (results.sections.isNotEmpty()) {
                AccuracyGraph(
                    results = results,
                    onSelect = { onEvent(GlyphEvent.SelectSection(it)) },
                )

                val selected = results.selectedSection?.let { results.sections.getOrNull(it) }
                if (selected != null) {
                    GlyphButton(
                        text = "Practise ${selected.label} in Training Ground",
                        primary = true,
                        onClick = { onEvent(GlyphEvent.PractiseSection(selected.index)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val weakest = results.sections.minByOrNull { it.accuracy }
                    if (weakest != null && weakest.isWeak) {
                        GlyphButton(
                            text = "Practise your weakest section (${weakest.label})",
                            primary = true,
                            onClick = { onEvent(GlyphEvent.PractiseSection(weakest.index)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            room?.let { RoomRankingPanel(it) }

            // The way on, as the song select's: thumb-sized pills of glass.
            // A room's run has one: back to the room, where the ranking is
            // and the host picks the next song.
            if (room != null) {
                GlyphButton(
                    text = stringResource(R.string.tm_mp_back_to_room),
                    primary = true,
                    height = PILL_HEIGHT,
                    onClick = { onEvent(GlyphEvent.BackToRoom) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = "Play again",
                        icon = Icons.Default.Replay,
                        height = PILL_HEIGHT,
                        onClick = { onEvent(GlyphEvent.StartPlay) },
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(
                        text = "Songs",
                        primary = true,
                        height = PILL_HEIGHT,
                        onClick = { onEvent(GlyphEvent.BackToHome) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Trailing space inside the scroll, so the last buttons come clear
            // of the tab bar while the page still runs under its glass.
            Spacer(Modifier.height(bottomChromePadding))
        }
    }
}

/**
 * How the run was timed: a normal curve from the run's mean offset and
 * spread, early shaded one way and late the other, the mean marked against
 * zero; the counts early and late, and the mean, spread and mines in figures.
 *
 * The curve is drawn from those two numbers, not from the hits one by one,
 * and says so. When the last few runs agree the player hits early or late,
 * [onCorrect] moves the audio offset by it, as Settings' Sync does.
 */
@Composable
private fun TimingPanel(attempt: GlyphAttempt, recentLateMs: Int?, onCorrect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mean = attempt.meanOffsetMs
    val spread = attempt.deviationMs
    val hits = attempt.early + attempt.late
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSectionLabel("Your timing")
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    hits == 0 -> "No hits to time"
                    mean.roundToInt() == 0 -> "Centred"
                    else -> "%.0f ms %s on average".format(abs(mean), if (mean > 0f) "late" else "early")
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        if (hits > 0 && spread > 0f) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            TimingCurve(mean = mean, spread = spread)
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "${attempt.early} hits early, ${attempt.late} hits late"
                },
        ) {
            Text(
                text = "${attempt.early} early",
                style = MaterialTheme.typography.bodyMedium,
                color = menuAccent(GlyphTheme.Early),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${attempt.late} late",
                style = MaterialTheme.typography.bodyMedium,
                color = menuAccent(GlyphTheme.Late),
            )
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GlyphStat("Mean", "%.0f ms".format(mean))
            GlyphStat("Spread", "±%.0f ms".format(spread))
            GlyphStat("Mines", attempt.minesHit.toString())
        }
        recentLateMs?.takeIf { it != 0 }?.let { late ->
            Spacer(Modifier.height(MonoDimens.spacingMd))
            GlyphButton(
                text = "Centre my timing (recent runs ${abs(late)} ms ${if (late > 0) "late" else "early"})",
                onClickLabel = "Move the audio offset by ${abs(late)} milliseconds",
                onClick = onCorrect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The bell of the run's timing over ±[TIMING_RANGE_MS] or wider, zero in the middle. */
@Composable
private fun TimingCurve(mean: Float, spread: Float) {
    val ink = MaterialTheme.colorScheme.onSurface
    val marker = menuAccent(GlyphTheme.Positive)
    val range = maxOf(TIMING_RANGE_MS, abs(mean) + spread * 3f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .semantics {
                contentDescription = "Timing curve: hits spread %.0f milliseconds either side of %.0f %s".format(
                    spread,
                    abs(mean),
                    if (mean >= 0f) "late" else "early",
                )
            },
    ) {
        val base = size.height - 4.dp.toPx()
        val peak = size.height - 12.dp.toPx()
        fun x(ms: Float) = size.width / 2f + ms / range * (size.width / 2f)
        fun y(ms: Float) = base - peak * exp(-0.5f * ((ms - mean) / spread).let { it * it })
        fun area(from: Float, to: Float) = Path().apply {
            moveTo(x(from), base)
            var ms = from
            while (ms <= to) {
                lineTo(x(ms), y(ms))
                ms += range / 90f
            }
            lineTo(x(to), y(to))
            lineTo(x(to), base)
            close()
        }
        drawPath(area(-range, 0f), GlyphTheme.Early.copy(alpha = 0.35f))
        drawPath(area(0f, range), GlyphTheme.Late.copy(alpha = 0.35f))
        val line = Path().apply {
            var ms = -range
            moveTo(x(ms), y(ms))
            while (ms <= range) {
                lineTo(x(ms), y(ms))
                ms += range / 90f
            }
        }
        drawPath(line, ink, style = Stroke(2.dp.toPx()))
        drawLine(
            color = ink.copy(alpha = 0.4f),
            start = Offset(x(0f), 0f),
            end = Offset(x(0f), base),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        drawLine(marker, Offset(x(mean), 4.dp.toPx()), Offset(x(mean), base), strokeWidth = 2.dp.toPx())
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("early", style = MaterialTheme.typography.labelSmall, color = menuAccent(GlyphTheme.Early))
        Spacer(Modifier.weight(1f))
        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("late", style = MaterialTheme.typography.labelSmall, color = menuAccent(GlyphTheme.Late))
    }
}

/** The curve shows at least this far either side of zero, a Judge 4 Miss's reach. */
private const val TIMING_RANGE_MS = 180f

/** The way on: the song select's thumb-sized pills. */
private val PILL_HEIGHT = 52.dp

/**
 * Accuracy over the song, one bar per section.
 *
 * Bars rather than a line: a line implies the values between two points mean
 * something, and they do not — each section is a separate measurement. Tapping
 * a bar selects it, and the selected bar is marked by an outline as well as by
 * brightness so the selection does not depend on colour alone.
 */
@Composable
private fun AccuracyGraph(
    results: GlyphResultsUi,
    onSelect: (Int) -> Unit,
) {
    val sections = results.sections
    val weakest = sections.minByOrNull { it.accuracy }
    val scheme = MaterialTheme.colorScheme
    val hairline = scheme.onSurface.copy(alpha = 0.12f)
    val outline = scheme.onSurface

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSectionLabel("Accuracy over time")
            Spacer(Modifier.weight(1f))
            val selected = results.selectedSection?.let { sections.getOrNull(it) }
            if (selected != null) {
                Text(
                    text = "${selected.label} · %.0f%% · ${selected.missCount} missed"
                        .format(selected.accuracy * 100),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(MonoDimens.spacingSm))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics {
                    contentDescription = buildString {
                        append("Accuracy by section. ")
                        if (weakest != null) {
                            append("Weakest at ${weakest.label}, ")
                            append("${(weakest.accuracy * 100).toInt()} percent. ")
                        }
                        append("Tap a bar to select a section to practise.")
                    }
                }
                .pointerInput(sections.size) {
                    detectTapGestures { offset ->
                        if (sections.isEmpty()) return@detectTapGestures
                        val index = ((offset.x / size.width) * sections.size)
                            .toInt()
                            .coerceIn(0, sections.lastIndex)
                        onSelect(index)
                    }
                },
        ) {
            if (sections.isEmpty()) return@Canvas
            val barWidth = size.width / sections.size

            // Reference lines at 90% and 70%, so a bar's height is readable
            // against a target rather than only against the other bars.
            for (reference in listOf(0.9f, 0.7f)) {
                val y = size.height * (1f - reference)
                drawRect(
                    color = hairline,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, 1f),
                )
            }

            for ((index, section) in sections.withIndex()) {
                val height = size.height * section.accuracy.coerceIn(0f, 1f)
                val left = index * barWidth
                val isSelected = index == results.selectedSection

                drawRect(
                    color = when {
                        section.accuracy >= 0.95f -> GlyphTheme.Positive
                        section.accuracy >= 0.85f -> Color(0xFF58D9FF)
                        section.accuracy >= 0.7f -> GlyphTheme.Warning
                        else -> GlyphTheme.Negative
                    }.copy(alpha = if (isSelected) 1f else 0.75f),
                    topLeft = Offset(left + barWidth * 0.15f, size.height - height),
                    size = Size(barWidth * 0.7f, height),
                )

                if (isSelected) {
                    drawRect(
                        color = outline,
                        topLeft = Offset(left + barWidth * 0.15f, 0f),
                        size = Size(barWidth * 0.7f, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
        }

        Spacer(Modifier.height(MonoDimens.spacingSm))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sections.firstOrNull()?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = sections.lastOrNull()?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** The room's ranking once every phone has finished; until then, the scores as they stand. */
@Composable
private fun RoomRankingPanel(room: GlyphMultiplayerUi) {
    val session = room.session
    val scheme = MaterialTheme.colorScheme
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel(stringResource(R.string.tm_mp_room_title))
        val ranking = session.ranking
        if (ranking == null) {
            Text(
                text = stringResource(R.string.tm_mp_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            room.opponents.forEach { player ->
                Text(
                    text = "${player.name}  ·  ${player.live.score}  ·  ${"%.1f".format(player.live.acc * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (player.id == session.myId) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        } else {
            ranking.forEach { placing ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "${placing.rank}.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(MonoDimens.spacingSm))
                    Text(
                        text = placing.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (placing.id == session.myId) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${placing.result.score}  ·  ${placing.result.grade}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (placing.result.failed) scheme.error else scheme.onSurface,
                    )
                }
            }
        }
    }
}
