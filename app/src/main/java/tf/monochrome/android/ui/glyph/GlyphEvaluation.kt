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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The results' top card: StepMania's grade, percent, full combo banner and
 * judgement counts, on the app's glass.
 *
 * A menu card like any other — the theme's ink and type, a card of the app's
 * glass — with StepMania's own art where StepMania has art: the grade and the
 * FULL COMBO banner. The judgement colours are StepMania's, moved by
 * [menuAccent] only as far as they must to read on the page.
 *
 * It arrives in StepMania's order: the grade stamps down, the percent counts
 * up, the counts count up with it, and the banner sweeps in last. Flat — size,
 * fade and slide, nothing turned in depth — and skipped under reduced motion.
 */
@Composable
fun GlyphEvaluationPanel(
    results: GlyphResultsUi,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val attempt = results.attempt
    val scheme = MaterialTheme.colorScheme
    val grade = GlyphStepGrade.fromName(attempt.stepGrade)
    val fullCombo = attempt.fullCombo?.let { name -> GlyphFullCombo.entries.firstOrNull { it.name == name } }
    val percent = if (attempt.hasStepScore) attempt.percent else attempt.accuracy

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
    val shown = count.value.coerceIn(0f, 1f)

    // Top of this phone's table, and above whatever was there before.
    val isRecord = attempt.isFullRun && results.machineScores.firstOrNull()?.id == attempt.id &&
        results.machineScores.size > 1

    GlyphPanel(modifier = modifier) {
        Text(
            text = "${attempt.difficulty.sscName} · ${attempt.playerName.ifBlank { "PLAYER" }}",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
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
            Spacer(Modifier.width(MonoDimens.spacingMd))
            Column {
                Text(
                    text = StepManiaScoreKeeper.formatPercent(percent * shown),
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.onSurface,
                    modifier = Modifier.semantics {
                        contentDescription = "Score ${StepManiaScoreKeeper.formatPercent(percent)}"
                    },
                )
                Text(
                    text = "%,d points".format((attempt.score * shown).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                if (isRecord) {
                    Text(
                        text = "New record",
                        style = MaterialTheme.typography.labelLarge,
                        color = menuAccent(StepMaxComboColour),
                    )
                }
                results.previousBest?.let { best ->
                    Text(
                        text = bestLine(attempt, best),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (fullCombo != null) {
            val sprite = rememberStepArt(fullCombo.asset)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MonoDimens.spacingSm)
                    .graphicsLayer {
                        translationX = (1f - banner.value) * -size.width * 0.3f
                        alpha = banner.value
                    }
                    .semantics { contentDescription = fullCombo.label },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (sprite != null) {
                    Image(
                        bitmap = sprite,
                        contentDescription = null,
                        modifier = Modifier.height(44.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = fullCombo.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = menuAccent(comboColour(fullCombo)),
                    )
                }
            }
        }

        if (attempt.failed) {
            Text(
                text = "Failed",
                style = MaterialTheme.typography.titleLarge,
                color = menuAccent(stepJudgementColour(GlyphJudgement.MISS)),
            )
        }

        Spacer(Modifier.height(MonoDimens.spacingMd))
        JudgementCounts(attempt = attempt, progress = shown)

        if (!attempt.isFullRun) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Text(
                text = "Training segment — not filed as a full run.",
                style = MaterialTheme.typography.labelMedium,
                color = menuAccent(GlyphTheme.Warning),
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
            Image(
                bitmap = sprite,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = grade?.label ?: fallback.orEmpty(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** StepMania's judgement lines, counting up with the percent. */
@Composable
private fun JudgementCounts(attempt: GlyphAttempt, progress: Float) {
    val rows = GlyphJudgement.entries.map { judgement ->
        Triple(stepJudgementName(judgement), attempt.judgementCount(judgement), stepJudgementColour(judgement))
    } + listOf(
        Triple("OK", attempt.holdsHeld, MaterialTheme.colorScheme.onSurface),
        Triple("Max Combo", attempt.maxCombo, StepMaxComboColour),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, value, colour) in rows) {
            val ink = menuAccent(colour)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (value * progress).roundToInt().toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
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
    val attempt = results.attempt
    GlyphPanel(modifier = modifier) {
        Text(
            text = "High scores",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "${attempt.difficulty.sscName} · this phone",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphScoreTable(runs = results.machineScores, highlightId = attempt.id)
    }
}

/** Ranked runs, best first, with [highlightId]'s row lit. */
@Composable
fun GlyphScoreTable(runs: List<GlyphAttempt>, highlightId: String? = null) {
    if (runs.isEmpty()) {
        Text(
            text = "No full runs of this chart yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    runs.forEachIndexed { index, run ->
        val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(run.playedAtEpochMs))
        ScoreLine(
            rank = index + 1,
            name = run.playerName.ifBlank { "PLAYER" },
            grade = GlyphStepGrade.fromName(run.stepGrade),
            percent = if (run.hasStepScore) run.percent else run.accuracy,
            detail = listOf(run.modsLabel, date).filter { it.isNotEmpty() }.joinToString(" · "),
            mine = run.id == highlightId,
        )
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
) {
    val scheme = MaterialTheme.colorScheme
    val gradeArt = rememberStepArt(grade?.asset)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeSm)
            .background(if (mine) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = MonoDimens.spacingSm, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.CenterStart) {
            if (gradeArt != null) {
                Image(
                    bitmap = gradeArt,
                    contentDescription = grade?.label,
                    modifier = Modifier.height(22.dp),
                    contentScale = ContentScale.Fit,
                )
            } else if (grade != null) {
                Text(grade.label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = StepManiaScoreKeeper.formatPercent(percent),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
            Text(text = detail, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

private val GRADE_WIDTH = 120.dp
private const val COUNT_MILLIS = 900
