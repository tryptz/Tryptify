// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphIcon
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong

/**
 * Glyph's front door: pick a song, pick a difficulty, play or practise.
 *
 * One screen rather than a menu tree. Everything the mode needs to start —
 * which song, whether it has a chart, which difficulty, and which of the two
 * ways to play — fits on a list and a panel, and burying any of it a level down
 * would only add taps.
 */
@Composable
fun GlyphHomeScreen(
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamily = rememberStepTechFontFamily()
    val typography = GlyphTypography(fontFamily)

    // MP3 and FLAC only, matching what the mode offers in the list. A picker
    // that accepted anything would hand the converter a file the flow does not
    // claim to support.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onEvent(GlyphEvent.GenerateChartFrom(uri))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlyphTheme.Grid, vertical = GlyphTheme.Grid),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid),
        ) {
            GlyphIconButton(
                icon = GlyphIcon.ARROW_BACK,
                label = "Back",
                assets = assets,
                onClick = onBack,
            )
            Text(
                text = "GLYPH",
                style = typography.title,
                color = GlyphTheme.Paper,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            GlyphIconButton(
                icon = GlyphIcon.AUDIO_STEM,
                label = "Generate a chart from a file",
                assets = assets,
                onClick = { picker.launch(arrayOf("audio/mpeg", "audio/flac", "audio/x-flac")) },
            )
        }

        SearchField(
            query = state.songQuery,
            onQueryChange = { onEvent(GlyphEvent.Search(it)) },
            typography = typography,
            modifier = Modifier.padding(horizontal = GlyphTheme.Grid * 2),
        )

        Spacer(Modifier.height(GlyphTheme.Grid))

        val generation = state.generation
        if (generation != null) {
            GenerationPanel(
                generation = generation,
                typography = typography,
                onCancel = { onEvent(GlyphEvent.CancelGeneration) },
                modifier = Modifier.padding(horizontal = GlyphTheme.Grid * 2),
            )
            Spacer(Modifier.height(GlyphTheme.Grid))
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = typography.body,
                color = GlyphTheme.Negative,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(GlyphEvent.DismissError) }
                    .padding(horizontal = GlyphTheme.Grid * 2, vertical = GlyphTheme.Grid),
            )
        }

        when {
            state.isLoadingSongs -> CentredMessage("Reading the library…", typography)

            state.songs.isEmpty() -> CentredMessage(
                "No MP3 or FLAC files in your library yet. " +
                    "Import one, or generate a chart from a file.",
                typography,
            )

            state.filteredSongs.isEmpty() -> CentredMessage(
                "Nothing matches \"${state.songQuery}\".",
                typography,
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = GlyphTheme.Grid * 2,
                    end = GlyphTheme.Grid * 2,
                    bottom = GlyphTheme.Grid * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(GlyphTheme.Grid),
            ) {
                items(state.filteredSongs, key = { it.trackId }) { song ->
                    SongRow(
                        song = song,
                        selected = song.trackId == state.selectedSong?.trackId,
                        typography = typography,
                        onClick = { onEvent(GlyphEvent.SelectSong(song.trackId)) },
                    )
                }
            }
        }

        val selected = state.selectedSong
        if (selected != null) {
            SelectionPanel(
                song = selected,
                state = state,
                assets = assets,
                typography = typography,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    typography: GlyphTypography,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(GlyphTheme.Grid))
            .background(GlyphTheme.InkPanel),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = typography.mono.copy(color = GlyphTheme.Paper),
            cursorBrush = SolidColor(GlyphTheme.Paper),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "Search songs" },
        )
        if (query.isEmpty()) {
            Text(
                text = "Search songs",
                style = typography.mono,
                color = GlyphTheme.Muted,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * One song.
 *
 * Chart availability is stated in words as well as being implied by the
 * difficulty chips: "No chart" is the row's most important fact when it is
 * true, and leaving it to the absence of something is not a way to say it.
 */
@Composable
private fun SongRow(
    song: GlyphSong,
    selected: Boolean,
    typography: GlyphTypography,
    onClick: () -> Unit,
) {
    val description = buildString {
        append(song.title)
        append(", ").append(song.artist)
        append(", ").append(song.formattedDuration)
        append(", ").append(song.codec.name)
        if (song.hasChart) {
            append(", ").append(song.bpmLabel)
            append(", ").append(song.difficulties.size).append(" difficulties")
        } else {
            append(", ").append(song.chartState.label)
        }
        if (selected) append(", selected")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlyphTheme.PanelCorner))
            .background(if (selected) GlyphTheme.InkRaised else GlyphTheme.InkPanel)
            .clickable(onClick = onClick)
            .padding(GlyphTheme.Grid + 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid + 4.dp),
    ) {
        AsyncImage(
            model = song.artworkUri,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(GlyphTheme.Grid))
                .background(GlyphTheme.InkRaised),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = typography.body,
                color = GlyphTheme.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.artist} · ${song.formattedDuration} · ${song.codec.name}",
                style = typography.label,
                color = GlyphTheme.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (song.hasChart) song.bpmLabel else song.chartState.label,
                style = typography.label,
                color = when (song.chartState) {
                    GlyphChartState.READY -> GlyphTheme.Positive
                    GlyphChartState.UNREADABLE -> GlyphTheme.Warning
                    GlyphChartState.NOT_GENERATED -> GlyphTheme.Muted
                },
                maxLines = 1,
            )
            if (song.hasChart) {
                Text(
                    text = song.difficulties.joinToString(" ") { it.sscName.take(1) },
                    style = typography.label,
                    color = GlyphTheme.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The bottom panel: difficulty, then the two ways to start.
 *
 * Play and Train are peers rather than one being behind the other, because
 * Training Ground is not an advanced option — for most of a chart's life it is
 * the more useful of the two.
 */
@Composable
private fun SelectionPanel(
    song: GlyphSong,
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    typography: GlyphTypography,
    onEvent: (GlyphEvent) -> Unit,
) {
    GlyphPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(GlyphTheme.Grid * 2),
    ) {
        Text(
            text = song.title,
            style = typography.title,
            color = GlyphTheme.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(GlyphTheme.Grid))

        if (!song.hasChart) {
            Text(
                text = when (song.chartState) {
                    GlyphChartState.UNREADABLE ->
                        "The chart on disk could not be read. Generating again will replace it."
                    else ->
                        "This song has no chart yet. Generating one separates the drums " +
                            "and reads the rhythm off them; it runs offline and takes a while."
                },
                style = typography.body,
                color = GlyphTheme.Muted,
            )
            Spacer(Modifier.height(GlyphTheme.Grid + 4.dp))
            GlyphPrimaryButton(
                text = "Generate chart",
                typography = typography,
                enabled = state.generation == null,
                onClick = { onEvent(GlyphEvent.GenerateChart) },
                modifier = Modifier.fillMaxWidth(),
            )
            return@GlyphPanel
        }

        Text("DIFFICULTY", style = typography.label, color = GlyphTheme.Muted)
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphChipRow(
            options = song.difficulties,
            selected = state.selectedDifficulty,
            label = { difficulty -> "${difficulty.sscName} ${meterOf(state, difficulty)}" },
            onSelect = { onEvent(GlyphEvent.SelectDifficulty(it)) },
            typography = typography,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(GlyphTheme.Grid + 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(GlyphTheme.Grid)) {
            GlyphPrimaryButton(
                text = "Play",
                typography = typography,
                enabled = state.chart != null,
                onClick = { onEvent(GlyphEvent.StartPlay) },
                modifier = Modifier.weight(1f),
            )
            GlyphSecondaryButton(
                text = "Training Ground",
                typography = typography,
                enabled = state.chart != null,
                onClick = { onEvent(GlyphEvent.StartTraining) },
                modifier = Modifier.weight(1f),
            )
        }

        val chart = state.chart
        if (chart != null) {
            Spacer(Modifier.height(GlyphTheme.Grid))
            Text(
                text = "${chart.notes.size} notes · ${song.bpmLabel} · meter ${chart.meter}",
                style = typography.label,
                color = GlyphTheme.Muted,
            )
        }
    }
}

private fun meterOf(state: GlyphUiState, difficulty: StepManiaDifficulty): Int =
    state.simfile?.chart(difficulty)?.meter ?: difficulty.meter

/** Decoding, separation, generation and writing, as the service reports them. */
@Composable
private fun GenerationPanel(
    generation: GlyphGenerationState,
    typography: GlyphTypography,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlyphPanel(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = generation.failure ?: generation.stage,
                style = typography.body,
                color = if (generation.failure != null) GlyphTheme.Negative else GlyphTheme.Paper,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(generation.fraction * 100).toInt()}%",
                style = typography.mono,
                color = GlyphTheme.Muted,
            )
        }
        Spacer(Modifier.height(GlyphTheme.Grid))
        GlyphMeter(
            fraction = generation.fraction,
            color = if (generation.failure != null) GlyphTheme.Negative else GlyphTheme.Positive,
            description = "${generation.stage}, ${(generation.fraction * 100).toInt()} percent",
        )
        // Which separator actually ran, verbatim from the service — a fallback
        // to the CPU should never pass itself off as the model.
        generation.backendName?.let { backend ->
            Spacer(Modifier.height(GlyphTheme.Grid))
            Text("Separated by $backend", style = typography.label, color = GlyphTheme.Muted)
        }
        if (!generation.isFinished) {
            Spacer(Modifier.height(GlyphTheme.Grid))
            Text(
                text = "Cancel",
                style = typography.mono,
                color = GlyphTheme.Muted,
                modifier = Modifier
                    .clickable(onClickLabel = "Cancel chart generation", onClick = onCancel)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CentredMessage(text: String, typography: GlyphTypography) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(GlyphTheme.Grid * 4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = typography.body, color = GlyphTheme.Muted)
    }
}

@Composable
fun GlyphPrimaryButton(
    text: String,
    typography: GlyphTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(GlyphTheme.Grid))
            .background(if (enabled) GlyphTheme.Paper else GlyphTheme.InkRaised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.mono,
            color = if (enabled) GlyphTheme.Ink else GlyphTheme.Muted,
            maxLines = 1,
        )
    }
}

@Composable
fun GlyphSecondaryButton(
    text: String,
    typography: GlyphTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = GlyphTheme.Paper,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(GlyphTheme.Grid))
            .background(GlyphTheme.InkRaised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.mono,
            color = if (enabled) accent else GlyphTheme.Muted,
            maxLines = 1,
        )
    }
}
