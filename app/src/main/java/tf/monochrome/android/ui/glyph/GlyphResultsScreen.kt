// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * How the run went, and what to do about it.
 *
 * The graph is the point of this screen. A grade tells a player whether the run
 * was good; the per-section graph tells them *where* it was not, and tapping a
 * weak section opens exactly that range as a practice loop. Making them find the
 * passage again by hand is the difference between a results screen and a
 * practice tool.
 */
@Composable
fun GlyphResultsScreen(
    results: GlyphResultsUi,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)
    val attempt = results.attempt
    val grade = tf.monochrome.android.glyph.engine.GlyphGrade.forAccuracy(attempt.accuracy)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(GlyphTheme.Grid * 2),
        verticalArrangement = Arrangement.spacedBy(GlyphTheme.Grid * 2),
    ) {
        Text(
            text = attempt.songTitle,
            style = typography.title,
            color = GlyphTheme.Paper,
            modifier = Modifier.semantics { heading() },
        )

        GlyphPanel(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeBadge(grade = grade, assets = assets, typography = typography)
                Spacer(Modifier.size(GlyphTheme.Grid * 2))
                Column {
                    Text(
                        text = "%.2f%%".format(attempt.accuracy * 100),
                        style = typography.readout,
                        color = GlyphTheme.Paper,
                    )
                    Text(
                        text = "%,d points · ${attempt.difficulty.sscName}".format(attempt.score),
                        style = typography.label,
                        color = GlyphTheme.Muted,
                    )
                }
            }

            Spacer(Modifier.height(GlyphTheme.Grid * 2))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                GlyphStat("Max combo", attempt.maxCombo.toString(), typography = typography)
                GlyphStat("Mean", "%.0f ms".format(attempt.meanOffsetMs), typography = typography)
                GlyphStat("Spread", "±%.0f ms".format(attempt.deviationMs), typography = typography)
            }

            results.previousBest?.let { best ->
                Spacer(Modifier.height(GlyphTheme.Grid))
                val delta = attempt.score - best.score
                Text(
                    text = when {
                        delta > 0 -> "New best, up %,d points".format(delta)
                        delta == 0 -> "Matched your best"
                        else -> "Best stands: %,d points".format(best.score)
                    },
                    style = typography.label,
                    color = if (delta > 0) GlyphTheme.Positive else GlyphTheme.Muted,
                )
            }

            if (!attempt.isFullRun) {
                Spacer(Modifier.height(GlyphTheme.Grid))
                Text(
                    text = "Training segment — not filed as a full run.",
                    style = typography.label,
                    color = GlyphTheme.Warning,
                )
            }
        }

        JudgementBreakdown(results = results, typography = typography)

        EarlyLateSummary(
            early = attempt.early,
            late = attempt.late,
            typography = typography,
        )

        if (results.sections.isNotEmpty()) {
            AccuracyGraph(
                results = results,
                typography = typography,
                onSelect = { onEvent(GlyphEvent.SelectSection(it)) },
            )

            val selected = results.selectedSection?.let { results.sections.getOrNull(it) }
            if (selected != null) {
                GlyphPrimaryButton(
                    text = "Practise ${selected.label} in Training Ground",
                    typography = typography,
                    onClick = { onEvent(GlyphEvent.PractiseSection(selected.index)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val weakest = results.sections.minByOrNull { it.accuracy }
                if (weakest != null && weakest.isWeak) {
                    GlyphSecondaryButton(
                        text = "Practise your weakest section (${weakest.label})",
                        typography = typography,
                        accent = GlyphTheme.Positive,
                        onClick = { onEvent(GlyphEvent.PractiseSection(weakest.index)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid)) {
            GlyphSecondaryButton(
                text = "Play again",
                typography = typography,
                onClick = { onEvent(GlyphEvent.StartPlay) },
                modifier = Modifier.weight(1f),
            )
            GlyphSecondaryButton(
                text = "Songs",
                typography = typography,
                onClick = { onEvent(GlyphEvent.BackToHome) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GradeBadge(
    grade: tf.monochrome.android.glyph.engine.GlyphGrade,
    assets: GlyphAssetRepository,
    typography: GlyphTypography,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { GRADE_SIZE.roundToPx() }
    var image by remember(grade) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(grade, sizePx) {
        image = assets.load(GlyphAssetCatalog.grade(grade.art), sizePx, sizePx)
    }

    Box(
        modifier = Modifier
            .size(GRADE_SIZE)
            .semantics { contentDescription = "Grade ${grade.label}" },
        contentAlignment = Alignment.Center,
    ) {
        val current = image
        if (current != null) {
            Image(bitmap = current, contentDescription = null, modifier = Modifier.size(GRADE_SIZE))
        } else {
            // The letter is the grade; the badge is decoration around it.
            Text(text = grade.label, style = typography.readout, color = GlyphTheme.Paper)
        }
    }
}

private val GRADE_SIZE = 88.dp

/** Counts per judgement, with a bar that is never the only reading. */
@Composable
private fun JudgementBreakdown(results: GlyphResultsUi, typography: GlyphTypography) {
    val attempt = results.attempt
    val total = GlyphJudgement.entries.sumOf { attempt.judgementCount(it) }.coerceAtLeast(1)

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Text("JUDGEMENTS", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        for (judgement in GlyphJudgement.entries) {
            val count = attempt.judgementCount(judgement)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${judgement.label}: $count"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = judgement.label,
                    style = typography.mono,
                    color = judgementColor(judgement),
                    modifier = Modifier.weight(0.4f),
                )
                GlyphMeter(
                    fraction = count.toFloat() / total,
                    color = judgementColor(judgement),
                    modifier = Modifier.weight(0.4f),
                )
                Text(
                    text = count.toString(),
                    style = typography.mono,
                    color = GlyphTheme.Paper,
                    modifier = Modifier
                        .weight(0.2f)
                        .padding(start = GlyphTheme.Grid),
                )
            }
        }
    }
}

private fun judgementColor(judgement: GlyphJudgement): Color = when (judgement) {
    GlyphJudgement.MARVELOUS -> Color(0xFF63F2A2)
    GlyphJudgement.PERFECT -> Color(0xFF52E6D8)
    GlyphJudgement.GREAT -> Color(0xFF58D9FF)
    GlyphJudgement.GOOD -> Color(0xFFFFD95A)
    GlyphJudgement.BOO -> Color(0xFFFF9659)
    GlyphJudgement.MISS -> Color(0xFFFF5F6D)
}

@Composable
private fun EarlyLateSummary(early: Int, late: Int, typography: GlyphTypography) {
    val total = (early + late).coerceAtLeast(1)
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Text("EARLY / LATE", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$early early", style = typography.mono, color = GlyphTheme.Early)
            Spacer(Modifier.weight(1f))
            Text("$late late", style = typography.mono, color = GlyphTheme.Late)
        }
        Spacer(Modifier.height(GlyphTheme.Grid))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics {
                    contentDescription = "$early hits early, $late hits late"
                },
        ) {
            val lateShare = late.toFloat() / total
            drawRect(GlyphTheme.Early, size = Size(size.width * (1f - lateShare), size.height))
            drawRect(
                color = GlyphTheme.Late,
                topLeft = Offset(size.width * (1f - lateShare), 0f),
                size = Size(size.width * lateShare, size.height),
            )
        }
    }
}

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
    typography: GlyphTypography,
    onSelect: (Int) -> Unit,
) {
    val sections = results.sections
    val weakest = sections.minByOrNull { it.accuracy }

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ACCURACY OVER TIME", style = typography.label, color = GlyphTheme.Muted)
            Spacer(Modifier.weight(1f))
            val selected = results.selectedSection?.let { sections.getOrNull(it) }
            if (selected != null) {
                Text(
                    text = "${selected.label} · %.0f%% · ${selected.missCount} missed"
                        .format(selected.accuracy * 100),
                    style = typography.label,
                    color = GlyphTheme.Paper,
                )
            }
        }

        Spacer(Modifier.height(GlyphTheme.Grid))

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
                    color = GlyphTheme.Hairline,
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
                        color = GlyphTheme.Paper,
                        topLeft = Offset(left + barWidth * 0.15f, 0f),
                        size = Size(barWidth * 0.7f, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
        }

        Spacer(Modifier.height(GlyphTheme.Grid))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sections.firstOrNull()?.label.orEmpty(),
                style = typography.label,
                color = GlyphTheme.Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = sections.lastOrNull()?.label.orEmpty(),
                style = typography.label,
                color = GlyphTheme.Muted,
            )
        }
    }
}
