// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphGrade
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The results' top half: a ring of the run's judgements with the grade and
 * percent in the middle, then StepMania's judgement lines counting up one
 * after another, DDR's evaluation.
 *
 * Menu cards like any other — the theme's ink and type, the app's glass —
 * with StepMania's own art where StepMania has art: the grade and the FULL
 * COMBO banner. The judgement colours are StepMania's: drawn as they are in
 * the ring (a fill), moved by [menuAccent] as text.
 *
 * It arrives in order: the grade stamps down, the ring unrolls as the percent
 * counts up, the lines slide in and count one at a time, the banner sweeps in
 * last. A tap on the lines while they count shows them all; once they have, a
 * tap on a judgement lights its share of the ring and puts its count in the
 * middle. Flat — size, fade and slide — and all at once under reduced motion.
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
    val lines = evaluationLines(attempt)

    val stamp = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val ring = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val counted = remember { Animatable(if (reducedMotion) lines.size.toFloat() else 0f) }
    val banner = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt.id) {
        if (reducedMotion) return@LaunchedEffect
        launch { stamp.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        launch { ring.animateTo(1f, tween(RING_MILLIS, easing = FastOutSlowInEasing)) }
        counted.animateTo(lines.size.toFloat(), tween(lines.size * LINE_MILLIS, easing = LinearEasing))
        if (fullCombo != null) {
            delay(80)
            banner.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
    }
    val counting = counted.value < lines.size
    val showAll: () -> Unit = {
        scope.launch {
            stamp.snapTo(1f)
            ring.snapTo(1f)
            counted.snapTo(lines.size.toFloat())
            banner.snapTo(1f)
        }
    }
    var focus by remember(attempt.id) { mutableStateOf<GlyphJudgement?>(null) }
    val shown = ring.value.coerceIn(0f, 1f)

    // Top of this phone's table, and above whatever was there before.
    val isRecord = attempt.isFullRun && results.machineScores.firstOrNull()?.id == attempt.id &&
        results.machineScores.size > 1

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingLg)) {
        GlyphPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${attempt.course.difficultyLabel(attempt.difficulty)} · ${attempt.playerName.ifBlank { "PLAYER" }}",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MonoDimens.spacingSm))
            JudgementRing(
                attempt = attempt,
                progress = shown,
                focus = focus,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                val picked = focus
                if (picked != null) {
                    val count = attempt.judgementCount(picked)
                    val total = GlyphJudgement.entries.sumOf { attempt.judgementCount(it) }.coerceAtLeast(1)
                    Text(
                        text = stepJudgementName(picked).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = menuAccent(stepJudgementColour(picked)),
                    )
                    Text(text = "$count", style = MaterialTheme.typography.displaySmall, color = scheme.onSurface)
                    Text(
                        text = "${count * 100 / total}% of $total steps",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    GradeSprite(
                        grade = grade,
                        fallback = if (grade == null) GlyphGrade.forAccuracy(attempt.accuracy).label else null,
                        modifier = Modifier
                            .height(GRADE_HEIGHT)
                            .graphicsLayer {
                                val s = 1f + (1f - stamp.value) * 1.4f
                                scaleX = s
                                scaleY = s
                                alpha = stamp.value.coerceIn(0f, 1f)
                            },
                    )
                    Text(
                        text = StepManiaScoreKeeper.formatPercent(percent * shown),
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.semantics {
                            contentDescription = "Score ${StepManiaScoreKeeper.formatPercent(percent)}"
                        },
                    )
                    Text(
                        text = "%,d points".format((attempt.score * shown).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (attempt.failed) {
                    Text(
                        text = "Failed",
                        style = MaterialTheme.typography.titleLarge,
                        color = menuAccent(stepJudgementColour(GlyphJudgement.MISS)),
                    )
                }
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
                if (fullCombo != null) {
                    val sprite = rememberStepArt(fullCombo.asset)
                    Box(
                        modifier = Modifier
                            .padding(top = MonoDimens.spacingSm)
                            .graphicsLayer {
                                translationX = (1f - banner.value) * -size.width * 0.3f
                                alpha = banner.value
                            }
                            .semantics { contentDescription = fullCombo.label },
                        contentAlignment = Alignment.Center,
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
                if (!attempt.isFullRun) {
                    Text(
                        text = "Training segment — not filed as a full run.",
                        style = MaterialTheme.typography.labelMedium,
                        color = menuAccent(GlyphTheme.Warning),
                    )
                }
            }
        }

        GlyphPanel(
            modifier = Modifier
                .fillMaxWidth()
                // While the lines count, a tap anywhere on them shows them all.
                .then(
                    if (counting) {
                        Modifier.pointerInput(Unit) { detectTapGestures { showAll() } }
                            .semantics { onClick(label = "Show every count now") { showAll(); true } }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEachIndexed { index, line ->
                    EvaluationLine(
                        line = line,
                        progress = (counted.value - index).coerceIn(0f, 1f),
                        focused = line.judgement != null && line.judgement == focus,
                        onClick = line.judgement?.takeIf { !counting }?.let { judgement ->
                            { focus = if (focus == judgement) null else judgement }
                        },
                    )
                }
            }
        }
    }
}

/** One line of the evaluation: a judgement and its count, or OK, or the max combo. */
private class EvalLine(val label: String, val value: Int, val colour: Color, val judgement: GlyphJudgement?)

@Composable
private fun evaluationLines(attempt: GlyphAttempt): List<EvalLine> =
    GlyphJudgement.entries.map { judgement ->
        EvalLine(stepJudgementName(judgement), attempt.judgementCount(judgement), stepJudgementColour(judgement), judgement)
    } + listOf(
        EvalLine("OK", attempt.holdsHeld, MaterialTheme.colorScheme.onSurface, null),
        EvalLine("Max Combo", attempt.maxCombo, StepMaxComboColour, null),
    )

/**
 * DDR's evaluation line: it slides in, then its count runs up. [progress] is
 * this line's own, 0 before its turn and 1 once counted. A judgement's line
 * can be tapped to light its share of the ring.
 */
@Composable
private fun EvaluationLine(line: EvalLine, progress: Float, focused: Boolean, onClick: (() -> Unit)?) {
    val ink = menuAccent(line.colour)
    val slide = with(LocalDensity.current) { 28.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
            .graphicsLayer {
                alpha = if (progress > 0f) 1f else 0f
                translationX = -(1f - (progress * 3f).coerceAtMost(1f)) * slide
            }
            .clip(MonoDimens.shapeMd)
            .background(
                Brush.horizontalGradient(
                    listOf(line.colour.copy(alpha = if (focused) 0.34f else 0.16f), Color.Transparent),
                ),
            )
            .then(if (onClick != null) Modifier.bounceClick(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { selected = focused }
            .padding(horizontal = MonoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = (line.value * progress).roundToInt().toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
            modifier = Modifier.semantics { contentDescription = "${line.label}: ${line.value}" },
        )
    }
}

/**
 * The run's judgements as one ring, in StepMania's order clockwise from the
 * top, each arc its share of the steps. It unrolls with [progress]; a
 * [focus]ed judgement's arc is thicker and the others dim. [centre] sits on a
 * disc of glass in the middle.
 */
@Composable
private fun JudgementRing(
    attempt: GlyphAttempt,
    progress: Float,
    focus: GlyphJudgement?,
    modifier: Modifier = Modifier,
    centre: @Composable ColumnScope.() -> Unit,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val counts = GlyphJudgement.entries.map { it to attempt.judgementCount(it) }
    val total = counts.sumOf { it.second }
    val description = "Judgements: " + counts.joinToString(", ") { (j, n) -> "$n ${stepJudgementName(j).lowercase()}" } +
        ", of $total"
    Box(modifier = modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().semantics { contentDescription = description }) {
            val thin = RING_STROKE.toPx()
            val thick = RING_STROKE_FOCUSED.toPx()
            val inset = thick / 2f
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(thin))
            if (total == 0) return@Canvas
            var start = 0f
            for ((judgement, count) in counts) {
                if (count == 0) continue
                val sweep = 360f * count / total
                val drawn = (sweep - GAP_DEGREES).coerceAtLeast(0.5f) * progress
                val lit = focus == null || focus == judgement
                drawArc(
                    color = stepJudgementColour(judgement).copy(alpha = if (lit) 1f else 0.25f),
                    startAngle = -90f + start * progress,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(if (focus == judgement) thick else thin, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(
            modifier = Modifier
                .padding(RING_STROKE_FOCUSED + 10.dp)
                .fillMaxSize()
                .liquidGlass(shape = CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = centre,
        )
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
                modifier = Modifier.fillMaxHeight(),
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
            text = "${attempt.course.difficultyLabel(attempt.difficulty)} · this phone",
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

private val GRADE_HEIGHT = 64.dp
private val RING_SIZE = 248.dp
private val RING_STROKE = 20.dp
private val RING_STROKE_FOCUSED = 26.dp
private const val GAP_DEGREES = 1.5f
private val LINE_HEIGHT = 44.dp
private const val RING_MILLIS = 1000
private const val LINE_MILLIS = 240
