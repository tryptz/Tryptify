// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphGrade
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper

/**
 * StepMania's evaluation, top half: the grade, the percent, the full combo
 * banner and the judgement counts, in the default theme's art and colours.
 *
 * It arrives in StepMania's order: the grade stamps down, the percent counts
 * up to the score, the counts count up beside it, and the full combo banner
 * sweeps in last. All of it flat — sizes, fades and slides, nothing turned in
 * depth — and all of it skipped under reduced motion, which shows the final
 * numbers at once.
 */
@Composable
fun GlyphEvaluationPanel(
    results: GlyphResultsUi,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val attempt = results.attempt
    val fonts = rememberStepFonts()
    val grade = GlyphStepGrade.fromName(attempt.stepGrade)
    val fullCombo = attempt.fullCombo?.let { name -> GlyphFullCombo.entries.firstOrNull { it.name == name } }
    val percent = if (attempt.hasStepScore) attempt.percent else attempt.accuracy

    // The grade stamps: from large and clear to its size, with a little give.
    val stamp = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val count = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val banner = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(attempt.id) {
        if (reducedMotion) return@LaunchedEffect
        stamp.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        count.animateTo(1f, tween(COUNT_MILLIS, easing = FastOutSlowInEasing))
        if (fullCombo != null) {
            delay(80)
            banner.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
    }

    // Top of this phone's table, and above whatever was there before.
    val isRecord = attempt.isFullRun && results.machineScores.firstOrNull()?.id == attempt.id &&
        results.machineScores.size > 1

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(HeaderColour.copy(alpha = 0.9f), Color(0xFF14182A))))
            .padding(16.dp),
    ) {
        Text(
            text = "${attempt.difficulty.sscName.uppercase()}  ·  ${attempt.playerName.ifBlank { "PLAYER" }}",
            style = fonts.rowTitle,
            color = GlyphTheme.Paper.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradeSprite(
                grade = grade,
                fallback = if (grade == null) GlyphGrade.forAccuracy(attempt.accuracy).label else null,
                modifier = Modifier
                    .width(GRADE_WIDTH)
                    .graphicsLayer {
                        val s = 1f + (1f - stamp.value) * 1.4f
                        scaleX = s
                        scaleY = s
                        alpha = stamp.value.coerceIn(0f, 1f)
                    },
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = StepManiaScoreKeeper.formatPercent(percent * count.value.coerceIn(0f, 1f)),
                    style = fonts.bigScore,
                    color = GlyphTheme.Paper,
                    modifier = Modifier.semantics {
                        contentDescription = "Score ${StepManiaScoreKeeper.formatPercent(percent)}"
                    },
                )
                Text(
                    text = "%,d points".format((attempt.score * count.value.coerceIn(0f, 1f)).roundToInt()),
                    style = fonts.value,
                    color = GlyphTheme.Paper.copy(alpha = 0.75f),
                )
                if (isRecord) {
                    Text(text = "NEW RECORD!", style = fonts.rowTitle, color = StepMaxComboColour)
                }
                results.previousBest?.let { best ->
                    Text(
                        text = bestLine(attempt, best),
                        style = fonts.label,
                        color = GlyphTheme.Paper.copy(alpha = 0.8f),
                    )
                }
            }
        }

        if (fullCombo != null) {
            val sprite = rememberStepArt(fullCombo.asset)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .graphicsLayer {
                        translationX = (1f - banner.value) * -size.width * 0.3f
                        alpha = banner.value
                    }
                    .semantics { contentDescription = fullCombo.label },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (sprite != null) {
                    Image(bitmap = sprite, contentDescription = null, modifier = Modifier.height(48.dp), contentScale = ContentScale.Fit)
                } else {
                    Text("FULL COMBO!", style = fonts.screenTitle, color = comboColour(fullCombo))
                }
            }
        }

        if (attempt.failed) {
            Text(text = "FAILED", style = fonts.screenTitle, color = Color(0xFFF97E7E))
        }

        Spacer(Modifier.height(12.dp))
        JudgementCounts(attempt = attempt, progress = count.value, fonts = fonts)

        if (!attempt.isFullRun) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Training segment — not filed as a full run.",
                style = fonts.label,
                color = GlyphTheme.Warning,
            )
        }
    }
}

/**
 * This run against the best before it, plainly: by StepMania's percent when
 * both have one, by points when either predates it.
 */
private fun bestLine(attempt: GlyphAttempt, best: GlyphAttempt): String {
    if (attempt.hasStepScore && best.hasStepScore) {
        val delta = attempt.percent - best.percent
        return when {
            delta > 0f -> "New best, up ${StepManiaScoreKeeper.formatPercent(delta)}"
            delta == 0f -> "Matched your best"
            else -> "Best stands: ${StepManiaScoreKeeper.formatPercent(best.percent)}"
        }
    }
    val delta = attempt.score - best.score
    return when {
        delta > 0 -> "New best, up %,d points".format(delta)
        delta == 0 -> "Matched your best"
        else -> "Best stands: %,d points".format(best.score)
    }
}

@Composable
private fun GradeSprite(grade: GlyphStepGrade?, fallback: String?, modifier: Modifier = Modifier) {
    val sprite = rememberStepArt(grade?.asset)
    Box(
        modifier = modifier.semantics { contentDescription = "Grade ${grade?.label ?: fallback.orEmpty()}" },
        contentAlignment = Alignment.Center,
    ) {
        if (sprite != null) {
            Image(bitmap = sprite, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        } else {
            Text(text = grade?.label ?: fallback.orEmpty(), style = rememberStepFonts().banner, color = GlyphTheme.Paper)
        }
    }
}

/** StepMania's judgement lines, counting up with the percent. */
@Composable
private fun JudgementCounts(attempt: GlyphAttempt, progress: Float, fonts: GlyphStepFonts) {
    val shown = progress.coerceIn(0f, 1f)
    val rows = GlyphJudgement.entries.map { judgement ->
        Triple(stepJudgementName(judgement), attempt.judgementCount(judgement), stepJudgementColour(judgement))
    } + listOf(
        Triple("OK", attempt.holdsHeld, StepHeldColour),
        Triple("Max Combo", attempt.maxCombo, StepMaxComboColour),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, value, colour) in rows) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label.uppercase(),
                    style = fonts.rowTitle,
                    color = colour,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (value * shown).roundToInt().toString().padStart(4, '0'),
                    style = fonts.count,
                    color = colour,
                    modifier = Modifier.semantics { contentDescription = "$label: $value" },
                )
            }
        }
    }
}

/**
 * This phone's high score table for the chart just played: the ten best full
 * runs, best first, as StepMania keeps machine scores. The run just played is
 * lit if it made the table. Everything stays on the phone.
 */
@Composable
fun GlyphHighScoresPanel(
    results: GlyphResultsUi,
    modifier: Modifier = Modifier,
) {
    val fonts = rememberStepFonts()
    val attempt = results.attempt

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14182A))
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = "HIGH SCORES",
            style = fonts.screenTitle,
            color = GlyphTheme.Paper,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Text(
            text = "${attempt.difficulty.sscName} · this phone",
            style = fonts.label,
            color = GlyphTheme.Muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (results.machineScores.isEmpty()) {
            EmptyLine("No full runs of this chart yet.", fonts)
        }
        results.machineScores.forEachIndexed { index, run ->
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(run.playedAtEpochMs))
            ScoreLine(
                rank = index + 1,
                name = run.playerName.ifBlank { "PLAYER" },
                grade = GlyphStepGrade.fromName(run.stepGrade),
                percent = if (run.hasStepScore) run.percent else run.accuracy,
                detail = listOf(run.modsLabel, date).filter { it.isNotEmpty() }.joinToString(" · "),
                mine = run.id == attempt.id,
                fonts = fonts,
            )
        }
    }
}

@Composable
private fun ScoreLine(
    rank: Int,
    name: String,
    grade: GlyphStepGrade?,
    percent: Float,
    detail: String,
    mine: Boolean,
    fonts: GlyphStepFonts,
) {
    val gradeArt = rememberStepArt(grade?.asset)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (mine) PlayerColour.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$rank", style = fonts.count, color = GlyphTheme.Paper.copy(alpha = 0.7f), modifier = Modifier.width(32.dp))
        Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.CenterStart) {
            if (gradeArt != null) {
                Image(bitmap = gradeArt, contentDescription = grade?.label, modifier = Modifier.height(22.dp), contentScale = ContentScale.Fit)
            } else if (grade != null) {
                Text(grade.label, style = fonts.rowTitle, color = GlyphTheme.Paper)
            }
        }
        Text(
            text = name,
            style = fonts.rowTitle,
            color = if (mine) GlyphTheme.Paper else GlyphTheme.Paper.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(text = StepManiaScoreKeeper.formatPercent(percent), style = fonts.count, color = GlyphTheme.Paper)
            Text(text = detail, style = fonts.label, color = GlyphTheme.Muted)
        }
    }
}

@Composable
private fun EmptyLine(text: String, fonts: GlyphStepFonts, warn: Boolean = false) {
    Text(
        text = text,
        style = fonts.value,
        color = if (warn) GlyphTheme.Warning else GlyphTheme.Muted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private val GRADE_WIDTH = 120.dp
private const val COUNT_MILLIS = 900
