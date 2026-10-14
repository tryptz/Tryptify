// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.audio.sampler.stems.KnownModels
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.SearchAction
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.bottomChromePadding
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Trypt Mania's front door: pick a song, pick a difficulty, play or practise.
 *
 * One screen rather than a menu tree. Everything the mode needs to start —
 * which song, whether it has a chart, which difficulty, and which of the two
 * ways to play — fits on a list and a panel, and burying any of it a level down
 * would only add taps.
 *
 * It is an ordinary pushed screen of the app, built the way the others are: a
 * top bar with Back, Search and the screen's one other action, the floating
 * search bar, song rows that are the app's song rows, and the tab bar and mini
 * player still there underneath. Only playing and practising take the whole
 * window (see [GlyphRoute]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphHomeScreen(
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    onEvent: (GlyphEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // MP3 and FLAC only, matching what the mode offers in the list. A picker
    // that accepted anything would hand the converter a file the flow does not
    // claim to support.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onEvent(GlyphEvent.GenerateChartFrom(uri))
    }

    // A model file has no MIME type Android knows (.onnx), so the picker takes
    // anything; the installer identifies the file by its hash and refuses what
    // it cannot run.
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onEvent(GlyphEvent.ImportModel(uri))
    }
    var modelOpen by rememberSaveable { mutableStateOf(false) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Closing clears the filter. A stale query left behind means reopening the
    // bar shows a filtered list with no visible reason for it.
    val closeSearch = {
        searchOpen = false
        onEvent(GlyphEvent.Search(""))
    }
    // Back closes the search before it leaves the mode, as on the maps: a Back
    // that throws away the whole screen to dismiss a search bar is a surprise.
    BackHandler(enabled = searchOpen) { closeSearch() }

    val glass = LocalMiniPlayerGlass.current
    val density = LocalDensity.current

    // The panels' backdrop is the page under them, whatever the page is
    // showing — the song list, or the loading and empty messages. They are its
    // SIBLINGS, never its descendants: a haze effect cannot sample a layer it
    // is drawn inside, and doing so paints the source's flat base colour. The
    // source used to be the list alone, so while the library loaded there was
    // no source drawn at all and the generation panel came out a solid slab.
    // SearchOverlay owns a separate source for the bar, for the same reason at
    // a different level.
    val panelHaze = rememberHazeState()

    var generationInset by remember { mutableStateOf(0.dp) }
    var selectionInset by remember { mutableStateOf(0.dp) }

    // The selection panel sits on the bar as it stands now, following its fold,
    // as the map panels do. The list pads by the bar open, as lists do.
    val chromeNow by animateDpAsState(
        targetValue = LocalBottomChromeNow.current,
        label = "glyphChromeNow",
    )
    val chromeInset = LocalBottomChromeInset.current
    val listBottom = bottomChromePadding

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.trypt_mania),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                SearchAction(
                    open = searchOpen,
                    onToggle = { if (searchOpen) closeSearch() else searchOpen = true },
                )
                IconButton(onClick = { modelOpen = !modelOpen }) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = "Drum separation model",
                    )
                }
                IconButton(
                    onClick = { picker.launch(arrayOf("audio/mpeg", "audio/flac", "audio/x-flac")) },
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = "Generate a chart from a file",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        SearchOverlay(
            open = searchOpen,
            query = state.songQuery,
            onQueryChange = { onEvent(GlyphEvent.Search(it)) },
            placeholder = "Search songs",
            onClose = closeSearch,
            modifier = Modifier.weight(1f),
        ) { searchInset ->
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(panelHaze),
                ) {
                    val messageTop = searchInset + generationInset
                    when {
                        state.isLoadingSongs -> CentredMessage("Reading the library…", messageTop)

                        state.songs.isEmpty() -> CentredMessage(
                            "No MP3 or FLAC files in your library yet. " +
                                "Import one, or generate a chart from a file.",
                            messageTop,
                        )

                        state.filteredSongs.isEmpty() -> CentredMessage(
                            "Nothing matches \"${state.songQuery}\".",
                            messageTop,
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // Content padding, not padding on the container: it
                            // starts the rows below the glass while leaving them
                            // free to travel up behind it. Padding the container
                            // moves its top edge and the list clips there instead.
                            contentPadding = PaddingValues(
                                top = searchInset + generationInset + MonoDimens.spacingXs,
                                bottom = if (state.selectedSong != null) {
                                    chromeInset + selectionInset + MonoDimens.spacingSm
                                } else {
                                    listBottom
                                },
                            ),
                        ) {
                            items(state.filteredSongs, key = { it.trackId }) { song ->
                                SongRow(
                                    song = song,
                                    selected = song.trackId == state.selectedSong?.trackId,
                                    onClick = { onEvent(GlyphEvent.SelectSong(song.trackId)) },
                                )
                            }
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
                    // Stays up while a copy runs, so the progress has somewhere
                    // to be even if the player closed it.
                    if (modelOpen || state.model.importing) {
                        ModelPanel(
                            model = state.model,
                            haze = panelHaze,
                            glass = glass,
                            onImport = { modelPicker.launch(arrayOf("*/*")) },
                            onEvent = onEvent,
                            onClose = {
                                modelOpen = false
                                onEvent(GlyphEvent.DismissModelMessage)
                            },
                        )
                    }

                    val generation = state.generation
                    if (generation != null) {
                        GenerationPanel(
                            generation = generation,
                            haze = panelHaze,
                            glass = glass,
                            onCancel = { onEvent(GlyphEvent.CancelGeneration) },
                        )
                    }

                    state.error?.let { message ->
                        ErrorPanel(
                            message = message,
                            haze = panelHaze,
                            glass = glass,
                            onDismiss = { onEvent(GlyphEvent.DismissError) },
                        )
                    }
                }

                val selected = state.selectedSong
                if (selected != null) {
                    SelectionPanel(
                        song = selected,
                        state = state,
                        haze = panelHaze,
                        glass = glass,
                        onEvent = onEvent,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Clear of the tab bar and the mini player, which
                            // are drawn over this screen. The navigation bar
                            // is already clear: the nav host stops pushed
                            // screens above it.
                            .padding(bottom = chromeNow)
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

/**
 * A song, as the app's song rows are: one shared row height, the cover, the
 * title over one line of detail, and the chart's state where a track row keeps
 * its quality badge.
 */
@Composable
private fun SongRow(
    song: GlyphSong,
    selected: Boolean,
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
    }
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            .bounceClick(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                // Spoken by the framework, never left to the tint.
                this.selected = selected
            }
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = if (selected) scheme.primary.copy(alpha = 0.18f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MonoDimens.listRowHeight)
                .padding(horizontal = MonoDimens.listItemPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                url = song.artworkUri,
                contentDescription = null,
                size = MonoDimens.coverList,
                cornerRadius = MonoDimens.radiusSm,
            )
            Spacer(Modifier.width(MonoDimens.spacingMd))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${song.artist} · ${song.formattedDuration} · ${song.codec.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(MonoDimens.spacingSm))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (song.hasChart) song.bpmLabel else song.chartState.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (song.chartState) {
                        GlyphChartState.READY -> scheme.primary
                        GlyphChartState.UNREADABLE -> scheme.error
                        GlyphChartState.NOT_GENERATED -> scheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                if (song.hasChart) {
                    Text(
                        text = song.difficulties.joinToString(" ") { it.sscName.take(1) },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
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
    haze: HazeState,
    glass: PlayerGlassSettings,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        hazeState = haze,
        glass = glass,
        // The nav host already stops this screen above the navigation bar;
        // holding clear of it here as well counted it twice.
        avoidNavigationBar = false,
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        // The chips and buttons are glass on glass, frosting the same page.
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            Column(modifier = Modifier.padding(MonoDimens.spacingLg)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))

                if (!song.hasChart) {
                    Text(
                        text = when (song.chartState) {
                            GlyphChartState.UNREADABLE ->
                                "The chart on disk could not be read. Generating again will replace it."
                            else ->
                                "This song has no chart yet. Generating one separates the drums " +
                                    "and reads the rhythm off them; it runs offline and takes a while."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(
                        text = "Generate chart",
                        primary = true,
                        enabled = state.generation == null,
                        onClick = { onEvent(GlyphEvent.GenerateChart) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    return@Column
                }

                GlyphSectionLabel("Difficulty")
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphChipRow(
                    options = song.difficulties,
                    selected = state.selectedDifficulty,
                    label = { difficulty -> "${difficulty.sscName} ${meterOf(state, difficulty)}" },
                    onSelect = { onEvent(GlyphEvent.SelectDifficulty(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(MonoDimens.spacingMd))

                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = "Play",
                        icon = Icons.Default.PlayArrow,
                        primary = true,
                        enabled = state.chart != null,
                        onClick = { onEvent(GlyphEvent.OpenOptions) },
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(
                        text = "Training Ground",
                        enabled = state.chart != null,
                        onClick = { onEvent(GlyphEvent.StartTraining) },
                        modifier = Modifier.weight(1f),
                    )
                }

                val chart = state.chart
                if (chart != null) {
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(
                        text = "${chart.notes.size} notes · ${song.bpmLabel} · meter ${chart.meter}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // This phone's best on the chosen difficulty, as StepMania's
                // music wheel shows the grade beside each chart.
                state.selectedDifficulty?.let { state.bestGrades[it] }?.let { best ->
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    PersonalBest(best)
                }
            }
        }
    }
}

/** The grade sprite and percent of this phone's best run, and whose it is. */
@Composable
private fun PersonalBest(best: GlyphAttempt) {
    val grade = GlyphStepGrade.fromName(best.stepGrade)
    val art = rememberStepArt(grade?.asset)
    val percent = if (best.hasStepScore) best.percent else best.accuracy
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        if (art != null) {
            androidx.compose.foundation.Image(
                bitmap = art,
                contentDescription = grade?.label,
                modifier = Modifier.height(24.dp),
            )
            Spacer(Modifier.width(MonoDimens.spacingSm))
        }
        Text(
            text = "Best ${StepManiaScoreKeeper.formatPercent(percent)}" +
                (best.playerName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun meterOf(state: GlyphUiState, difficulty: StepManiaDifficulty): Int =
    state.simfile?.chart(difficulty)?.meter ?: difficulty.meter

/** Decoding, separation, generation and writing, as the service reports them. */
@Composable
private fun GenerationPanel(
    generation: GlyphGenerationState,
    haze: HazeState,
    glass: PlayerGlassSettings,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = (generation.fraction * 100).toInt()
    // Anchored under the search bar rather than at the bottom of the screen, so
    // it holds no space clear of a navigation bar that is nowhere near it.
    GlassPanel(
        hazeState = haze,
        glass = glass,
        avoidNavigationBar = false,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            Column(modifier = Modifier.padding(MonoDimens.spacingLg)) {
                val scheme = MaterialTheme.colorScheme
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // In the glass's ink either way: the theme's error red is
                    // chosen for the page, not for this pane. The meter below
                    // turns red, and the words say what failed.
                    Text(
                        text = generation.failure ?: generation.stage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // A readout that changes while it is read: monospaced, so
                    // the digits do not shuffle as they count.
                    Text(
                        text = "$percent%",
                        style = GlyphTypography(rememberStepTechFontFamily()).mono,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphMeter(
                    fraction = generation.fraction,
                    color = if (generation.failure != null) scheme.error else scheme.primary,
                    description = "${generation.stage}, $percent percent",
                )
                // Which separator actually ran, verbatim from the service — a fallback
                // to the CPU should never pass itself off as the model.
                generation.backendName?.let { backend ->
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(
                        text = "Separated by $backend",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (!generation.isFinished) {
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(
                        text = "Cancel",
                        onClick = onCancel,
                        onClickLabel = "Cancel chart generation",
                        height = 40.dp,
                    )
                }
            }
        }
    }
}

/**
 * The drum separation model, which the app does not ship.
 *
 * Charts generate without one, from the built-in separator. The model makes
 * cleaner drum stems and so better charts, but it is a hundred and more
 * megabytes, so it is the player's to bring: a file from their own storage,
 * copied in once. Settings › Audio AI shows the same install.
 */
@Composable
private fun ModelPanel(
    model: GlyphModelUi,
    haze: HazeState,
    glass: PlayerGlassSettings,
    onImport: () -> Unit,
    onEvent: (GlyphEvent) -> Unit,
    onClose: () -> Unit,
) {
    GlassPanel(
        hazeState = haze,
        glass = glass,
        avoidNavigationBar = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            Column(
                modifier = Modifier.padding(
                    start = MonoDimens.spacingLg,
                    end = MonoDimens.spacingLg,
                    bottom = MonoDimens.spacingLg,
                ),
            ) {
                val scheme = MaterialTheme.colorScheme
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Drum separation model",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onClose, enabled = !model.importing) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                val installed = model.installedName
                Text(
                    text = if (installed != null) {
                        "$installed · ${StemModelManager.formatBytes(model.sizeBytes)}. " +
                            "New charts read the rhythm off its drum stem."
                    } else {
                        "None installed. Charts use the built-in separator, which hears drums " +
                            "less cleanly. Import a model file from your phone to use it instead."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )

                if (model.importing) {
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    val percent = model.progress?.let { (it * 100).toInt() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.stage ?: "Importing",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (percent != null) {
                            Text(
                                text = "$percent%",
                                style = GlyphTypography(rememberStepTechFontFamily()).mono,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphMeter(
                        fraction = model.progress ?: 0f,
                        color = scheme.primary,
                        description = listOfNotNull(model.stage, percent?.let { "$it percent" })
                            .joinToString(", "),
                    )
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    GlyphButton(
                        text = "Cancel",
                        onClick = { onEvent(GlyphEvent.CancelModelImport) },
                        onClickLabel = "Cancel the model import",
                        height = 40.dp,
                    )
                    return@Column
                }

                model.message?.let { message ->
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(MonoDimens.spacingMd))
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = if (installed != null) "Replace…" else "Import model…",
                        icon = Icons.Default.FolderOpen,
                        primary = installed == null,
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                    )
                    if (installed != null) {
                        GlyphButton(
                            text = "Remove",
                            onClick = { onEvent(GlyphEvent.RemoveModel) },
                            onClickLabel = "Remove $installed",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(MonoDimens.spacingSm))
                Text(
                    text = "Accepts " + KnownModels.supportedFileNames().joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What went wrong, on glass under the search bar. A bare line of text floating
 * there had the rows scrolling through it.
 */
@Composable
private fun ErrorPanel(
    message: String,
    haze: HazeState,
    glass: PlayerGlassSettings,
    onDismiss: () -> Unit,
) {
    GlassPanel(
        hazeState = haze,
        glass = glass,
        avoidNavigationBar = false,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = MonoDimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The glass's own ink, not the theme's error red: that is chosen
            // for the page, and on dark glass in a light theme it would not
            // read. The panel says it is an error by being here.
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = MonoDimens.spacingMd),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun CentredMessage(text: String, top: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top)
            .padding(MonoDimens.spacingXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
