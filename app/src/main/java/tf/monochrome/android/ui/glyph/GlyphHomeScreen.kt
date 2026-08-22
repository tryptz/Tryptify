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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
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

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val glass = LocalMiniPlayerGlass.current
    val density = LocalDensity.current

    // The panels' backdrop is the song list. They are its SIBLINGS, never its
    // descendants: a haze effect cannot sample a layer it is drawn inside, and
    // doing so paints the source's flat base colour — the solid slab these were
    // before. SearchOverlay owns a separate source for the bar, for the same
    // reason at a different level.
    val panelHaze = rememberHazeState()

    var generationInset by remember { mutableStateOf(0.dp) }
    var selectionInset by remember { mutableStateOf(0.dp) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlyphTheme.Ink)
            .systemBarsPadding(),
    ) {
        // Fixed chrome, above the bar rather than under it. Back is the way out
        // of the mode and the picker is how audio gets in; a search bar that
        // covered either would strand someone who opened it by accident.
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
                // The pack ships no magnifier; filter is the honest one of what
                // it does have, and the label says what it is for.
                icon = GlyphIcon.FILTER,
                label = if (searchOpen) "Close search" else "Search songs",
                assets = assets,
                active = searchOpen,
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) onEvent(GlyphEvent.Search(""))
                },
            )
            GlyphIconButton(
                icon = GlyphIcon.AUDIO_STEM,
                label = "Generate a chart from a file",
                assets = assets,
                onClick = { picker.launch(arrayOf("audio/mpeg", "audio/flac", "audio/x-flac")) },
            )
        }

        SearchOverlay(
            open = searchOpen,
            query = state.songQuery,
            onQueryChange = { onEvent(GlyphEvent.Search(it)) },
            placeholder = "Search songs",
            onClose = {
                searchOpen = false
                onEvent(GlyphEvent.Search(""))
            },
            modifier = Modifier.weight(1f),
        ) { searchInset ->
            Box(modifier = Modifier.fillMaxSize()) {
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
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(panelHaze),
                        // Content padding, not padding on the container: it
                        // starts the rows below the glass while leaving them
                        // free to travel up behind it. Padding the container
                        // moves its top edge and the list clips there instead.
                        contentPadding = PaddingValues(
                            start = GlyphTheme.Grid * 2,
                            end = GlyphTheme.Grid * 2,
                            top = searchInset + generationInset + GlyphTheme.Grid,
                            bottom = selectionInset + GlyphTheme.Grid,
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

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Below the search bar rather than under it: both float
                        // at the top, and stacked they would overlap.
                        .padding(top = searchInset)
                        .onSizeChanged {
                            generationInset = with(density) { it.height.toDp() }
                        },
                ) {
                    val generation = state.generation
                    if (generation != null) {
                        GenerationPanel(
                            generation = generation,
                            typography = typography,
                            haze = panelHaze,
                            glass = glass,
                            onCancel = { onEvent(GlyphEvent.CancelGeneration) },
                        )
                    }

                    state.error?.let { message ->
                        Text(
                            text = message,
                            style = typography.body,
                            color = GlyphTheme.Negative,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEvent(GlyphEvent.DismissError) }
                                .padding(
                                    horizontal = GlyphTheme.Grid * 2,
                                    vertical = GlyphTheme.Grid,
                                ),
                        )
                    }
                }

                val selected = state.selectedSong
                if (selected != null) {
                    SelectionPanel(
                        song = selected,
                        state = state,
                        assets = assets,
                        typography = typography,
                        haze = panelHaze,
                        glass = glass,
                        onEvent = onEvent,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged {
                                selectionInset = with(density) { it.height.toDp() }
                            },
                    )
                } else {
                    LaunchedEffect(Unit) { selectionInset = 0.dp }
                }
            }
        }
    }
}

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
    haze: HazeState,
    glass: PlayerGlassSettings,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        hazeState = haze,
        glass = glass,
        // GlassPanel's own 12dp is an outer margin; the rows below sit 16dp
        // from the screen edge, so 4 more lines the panel up with them.
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
      Column(modifier = Modifier.padding(GlyphTheme.Grid * 2)) {
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
                color = GlyphTheme.Paper,
            )
            Spacer(Modifier.height(GlyphTheme.Grid + 4.dp))
            GlyphPrimaryButton(
                text = "Generate chart",
                typography = typography,
                enabled = state.generation == null,
                onClick = { onEvent(GlyphEvent.GenerateChart) },
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
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
}

private fun meterOf(state: GlyphUiState, difficulty: StepManiaDifficulty): Int =
    state.simfile?.chart(difficulty)?.meter ?: difficulty.meter

/** Decoding, separation, generation and writing, as the service reports them. */
@Composable
private fun GenerationPanel(
    generation: GlyphGenerationState,
    typography: GlyphTypography,
    haze: HazeState,
    glass: PlayerGlassSettings,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Anchored under the search bar rather than at the bottom of the screen, so
    // it holds no space clear of a navigation bar that is nowhere near it.
    GlassPanel(
        hazeState = haze,
        glass = glass,
        avoidNavigationBar = false,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
      Column(modifier = Modifier.padding(GlyphTheme.Grid * 2)) {
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
                color = GlyphTheme.Paper,
                modifier = Modifier
                    .clickable(onClickLabel = "Cancel chart generation", onClick = onCancel)
                    .padding(vertical = 8.dp),
            )
        }
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
