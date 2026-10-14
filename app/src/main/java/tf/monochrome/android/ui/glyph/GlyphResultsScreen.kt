// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
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
 * of the app's glass, and the tab bar and mini player back underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphResultsScreen(
    results: GlyphResultsUi,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val attempt = results.attempt
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = attempt.songTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
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

            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    GlyphStat("Mean", "%.0f ms".format(attempt.meanOffsetMs))
                    GlyphStat("Spread", "±%.0f ms".format(attempt.deviationMs))
                    GlyphStat("Mines", attempt.minesHit.toString())
                }
            }

            EarlyLateSummary(early = attempt.early, late = attempt.late)

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

            Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                GlyphButton(
                    text = "Play again",
                    icon = Icons.Default.Replay,
                    onClick = { onEvent(GlyphEvent.StartPlay) },
                    modifier = Modifier.weight(1f),
                )
                GlyphButton(
                    text = "Songs",
                    onClick = { onEvent(GlyphEvent.BackToHome) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Trailing space inside the scroll, so the last buttons come clear
            // of the tab bar and the mini player while the page still runs
            // under their glass.
            Spacer(Modifier.height(bottomChromePadding))
        }
    }
}

@Composable
private fun EarlyLateSummary(early: Int, late: Int) {
    val total = (early + late).coerceAtLeast(1)
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel("Early / late")
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$early early",
                style = MaterialTheme.typography.bodyMedium,
                color = menuAccent(GlyphTheme.Early),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$late late",
                style = MaterialTheme.typography.bodyMedium,
                color = menuAccent(GlyphTheme.Late),
            )
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
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
