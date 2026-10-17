// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import tf.monochrome.android.audio.stepmania.ChartMode
import tf.monochrome.android.audio.stepmania.ChartPace
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.SearchAction
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.bottomChromePadding
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.motionMillis
import tf.monochrome.android.ui.theme.reduceMotion

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
    previewPosition: () -> Float? = { null },
) {
    // MP3 and FLAC only, matching what the mode offers in the list. A picker
    // that accepted anything would hand the converter a file the flow does not
    // claim to support.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onEvent(GlyphEvent.GenerateChartFrom(uri))
    }

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
    val reducedMotion = state.gameplay.modifiers.reducedMotion || reduceMotion()

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

    // The glass floating over the top of the wheel: the card, the chart progress
    // and any error, under the search bar.
    var floatingInset by remember { mutableStateOf(0.dp) }
    // The panel's height, kept across trips to the options and back, so the
    // wheel does not rise when the first panel after a return arrives.
    var selectionInsetDp by rememberSaveable { mutableFloatStateOf(0f) }

    // The selection panel sits on the bar as it stands now, following its fold,
    // as the map panels do. The list pads by the bar open, as lists do.
    val chromeNow by animateDpAsState(
        targetValue = LocalBottomChromeNow.current,
        label = "glyphChromeNow",
    )
    val chromeInset = LocalBottomChromeInset.current
    val listBottom = bottomChromePadding

    // StepMania's music wheel: every folder the sort makes, and the open one's
    // songs after it. A search is one open folder of its results. A lone
    // folder — a library with no packs — is open already: a folder with
    // nothing beside it is only a tap in the way.
    val searching = state.songQuery.isNotBlank()
    val sections = remember(state.songs, state.songSort, state.songQuery) {
        GlyphWheel.sections(state.songs, state.songSort, state.songQuery)
    }
    // Before the wheel has reported a centre, the chosen song is it.
    val focusKey = state.wheelFocus ?: state.selectedSong?.let { GlyphWheel.songKey(it.trackId) }
    val focusedTrack = GlyphWheel.trackIdOf(focusKey)
    val focusedSong = focusedTrack?.let { id -> state.songs.firstOrNull { it.trackId == id } }
    val focusedSection = focusedSong?.let { GlyphWheel.sectionOf(it, state.songSort.key) }
    // A folder name left over from before the songs changed opens nothing.
    val namedFolder = state.wheelFolder?.takeIf { name -> sections.any { it.name == name } }
    val openFolder = when {
        searching -> sections.firstOrNull()?.name
        // The centred song's folder is open: that is where the song is. So a
        // song that changes folder — a chart made under the Mapped sort, a
        // search closed, a pack arriving beside a lone library — is never left
        // behind a closed one.
        focusedSection != null -> focusedSection
        else -> namedFolder ?: sections.singleOrNull()?.name
    }
    // A search's folder and a lone folder cannot close.
    val foldersLocked = searching || sections.size <= 1
    val items = remember(sections, openFolder) { GlyphWheel.items(sections, openFolder) }
    // Back closes the open folder before the screen goes, as StepMania's Back
    // on a song inside one; a folder that cannot close takes no Back, which
    // would read as the screen ignoring it.
    BackHandler(enabled = !searchOpen && !foldersLocked && openFolder != null) {
        openFolder?.let { onEvent(GlyphEvent.CloseFolder(it)) }
    }
    val anchor = run {
        val at = GlyphWheel.anchor(items, focusKey, focusedSection)
        // An open folder's heading is only the place to land when it was the
        // thing focused (it was just opened or closed). Otherwise — a search,
        // a lone folder — land on its first song.
        val heading = items.getOrNull(at) as? GlyphWheel.Item.Folder
        if (heading != null && heading.open && heading.key != focusKey && at + 1 < items.size) at + 1 else at
    }
    val focusedItem = GlyphWheel.indexOf(items, focusKey).takeIf { it >= 0 }?.let(items::get)
        ?: items.getOrNull(anchor)
    // The panel is the centred song's, and only once it is chosen: the wheel
    // resting on it for the sample delay. A panel left up while the wheel
    // turned past would offer Play for a song that is no longer in the middle.
    val panelSong = state.selectedSong?.takeIf { selected ->
        (focusedItem as? GlyphWheel.Item.Song)?.song?.trackId == selected.trackId
    }
    // A folder heading's own pill charts every song in it that has none. Its
    // room is kept while any folder has such a song, not only while one is
    // centred, for the same reason as the panel's below.
    val folderToChart = (focusedItem as? GlyphWheel.Item.Folder)?.section?.takeIf { panelSong == null }
    val folderUncharted = folderToChart?.songs?.count { isChartable(it, state) } ?: 0
    val anyFolderPill = remember(sections, state.queuedTracks) {
        sections.any { section -> section.songs.any { isChartable(it, state) } }
    }
    // Room for the panel is kept while a song is chosen, whatever is centred:
    // sized to the centred item instead, the wheel rose and fell by half a
    // panel at every folder heading it turned past.
    val wheelBottom by animateDpAsState(
        targetValue = if (state.selectedSong != null || anyFolderPill) {
            chromeInset + selectionInsetDp.dp + MonoDimens.spacingSm
        } else {
            listBottom
        },
        animationSpec = tween(motionMillis(240)),
        label = "glyphWheelBottom",
    )
    val showWheel = !state.isLoadingSongs && items.isNotEmpty()

    // The card takes what height the screen can spare: whole on a phone held
    // upright, without its radar and graph on a short one, and not at all in
    // landscape, where the wheel and the panel's Play need all of it.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cardRoom = when {
            maxHeight >= FULL_CARD_HEIGHT -> CardRoom.FULL
            maxHeight >= COMPACT_CARD_HEIGHT -> CardRoom.COMPACT
            else -> CardRoom.NONE
        }
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.songListMode) {
                            GlyphSongListMode.EDIT -> "Map editor: pick a song"
                            GlyphSongListMode.ROOM -> stringResource(R.string.tm_mp_pick_title)
                            GlyphSongListMode.PLAY -> stringResource(R.string.trypt_mania)
                        },
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
                    // The leaderboard on top, as StepMania puts its rankings on
                    // the music wheel's own screen.
                    IconButton(onClick = { onEvent(GlyphEvent.OpenLeaderboard) }) {
                        Icon(
                            Icons.Default.Leaderboard,
                            contentDescription = "Leaderboard",
                        )
                    }
                    GlyphSortButton(sort = state.songSort, onPick = { onEvent(GlyphEvent.SortSongs(it)) })
                    // The two actions taken now and then, under one menu, so the
                    // title keeps its room.
                    Box {
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Generate a chart from a file") },
                                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                                onClick = {
                                    more = false
                                    picker.launch(arrayOf("audio/mpeg", "audio/flac", "audio/x-flac"))
                                },
                            )
                            // Every song without a chart, asked first: how many,
                            // how long and what it costs, Fast or Slow.
                            val uncharted = state.songs.count { song ->
                                song.chartState == GlyphChartState.NOT_GENERATED && song.filePath.isNotBlank() &&
                                    song.trackId !in state.queuedTracks
                            }
                            if (uncharted > 0) {
                                DropdownMenuItem(
                                    text = { Text("Chart every song without one ($uncharted)") },
                                    leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                                    onClick = {
                                        more = false
                                        onEvent(GlyphEvent.ChartEverything)
                                    },
                                )
                            }
                        }
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
                        // The song up's picture, under the wheel and inside the
                        // haze source, so the glass over the wheel frosts it.
                        GlyphSongBackdrop(
                            backdrop = state.backdrop,
                            reducedMotion = reducedMotion,
                            modifier = Modifier.fillMaxSize(),
                        )
                        val messageTop = searchInset + floatingInset
                        when {
                            state.isLoadingSongs -> CentredMessage("Reading the library…", messageTop)

                            state.songs.isEmpty() -> CentredMessage(
                                "No MP3 or FLAC files in your library yet. " +
                                    "Import one, or generate a chart from a file.",
                                messageTop,
                            )

                            items.isEmpty() -> CentredMessage(
                                "Nothing matches \"${state.songQuery}\".",
                                messageTop,
                            )

                            else -> GlyphMusicWheel(
                                items = items,
                                anchor = anchor,
                                selectedTrackId = state.selectedSong?.trackId,
                                onFocus = { onEvent(GlyphEvent.WheelFocus(it.key)) },
                                onActivate = { item ->
                                    when (item) {
                                        is GlyphWheel.Item.Folder ->
                                            if (!foldersLocked) onEvent(GlyphEvent.ToggleFolder(item.section.name))
                                        is GlyphWheel.Item.Song -> {
                                            val chosen = state.selectedSong?.trackId == item.song.trackId
                                            when {
                                                // The centred, chosen song plays, as Start on
                                                // StepMania's wheel; the panel's Play asks no more.
                                                chosen && state.songListMode == GlyphSongListMode.PLAY &&
                                                    state.chart != null -> onEvent(GlyphEvent.OpenOptions)
                                                // Already chosen: choosing it again would only
                                                // restart its preview and reset the panel.
                                                chosen -> Unit
                                                else -> onEvent(GlyphEvent.SelectSong(item.song.trackId))
                                            }
                                        }
                                    }
                                },
                                // A song tapped away from the centre is chosen
                                // now, not after the wheel rests on it.
                                onPick = { item ->
                                    if (item is GlyphWheel.Item.Song && state.selectedSong?.trackId != item.song.trackId) {
                                        onEvent(GlyphEvent.SelectSong(item.song.trackId))
                                    }
                                },
                                topInset = { searchInset + floatingInset },
                                bottomInset = { wheelBottom },
                                foldersLocked = foldersLocked,
                                sound = state.wheelSound,
                                requestFocus = !searchOpen,
                                modifier = Modifier.fillMaxSize(),
                            )
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
                                floatingInset = with(density) { it.height.toDp() }
                            },
                    ) {
                        // The card is glass over the wheel, as the search bar is:
                        // the rows travel up beneath it and read through it, and
                        // the wheel's centre is the middle of what it leaves.
                        // Laid above the wheel instead, it was a flat card with
                        // nothing behind it to be glass over.
                        if (showWheel && cardRoom != CardRoom.NONE) {
                            GlyphSongInfoCard(
                                item = focusedItem,
                                state = state,
                                reducedMotion = reducedMotion,
                                haze = panelHaze,
                                glass = glass,
                                previewPosition = previewPosition,
                                compact = cardRoom == CardRoom.COMPACT,
                                modifier = Modifier.padding(horizontal = 4.dp),
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

                    // The last song the panel showed, so it fades out with its own
                    // contents rather than going blank first.
                    val shownSong = remember { mutableStateOf<GlyphSong?>(null) }
                    SideEffect { if (panelSong != null) shownSong.value = panelSong }
                    // By its full name: inside the screen's Column the scoped overload would be picked.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = panelSong != null,
                        enter = fadeIn(tween(motionMillis(160))),
                        exit = fadeOut(tween(motionMillis(160))),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Clear of the tab bar and the mini player, which
                            // are drawn over this screen. The navigation bar
                            // is already clear: the nav host stops pushed
                            // screens above it.
                            .padding(bottom = chromeNow)
                            // Kept when the panel goes, so the wheel's room for it
                            // does not collapse and reopen on every turn.
                            .onSizeChanged {
                                if (it.height > 0) selectionInsetDp = with(density) { it.height.toDp() }.value
                            },
                    ) {
                        val song = panelSong ?: shownSong.value
                        if (song != null) {
                            SelectionPanel(
                                song = song,
                                state = state,
                                haze = panelHaze,
                                // A panel on its way out takes no presses: its Play
                                // is for a song the wheel has turned away from.
                                onEvent = { event -> if (panelSong != null) onEvent(event) },
                            )
                        }
                    }

                    // The folder's pill, in the panel's place while a heading is centred.
                    val shownFolder = remember { mutableStateOf<Pair<String, Int>?>(null) }
                    SideEffect {
                        if (folderToChart != null && folderUncharted > 0) shownFolder.value = folderToChart.name to folderUncharted
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = folderToChart != null && folderUncharted > 0,
                        enter = fadeIn(tween(motionMillis(160))),
                        exit = fadeOut(tween(motionMillis(160))),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = chromeNow)
                            .onSizeChanged {
                                // Room for it before any song has been chosen to size the panel's.
                                if (it.height > 0 && selectionInsetDp == 0f) {
                                    selectionInsetDp = with(density) { it.height.toDp() }.value
                                }
                            },
                    ) {
                        shownFolder.value?.let { (name, count) ->
                            FolderChartPill(
                                count = count,
                                haze = panelHaze,
                                onClick = { if (folderToChart != null) onEvent(GlyphEvent.ChartFolder(name)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class CardRoom { FULL, COMPACT, NONE }

/** Whether the batch buttons would chart [song]: a file to read, no chart, and not already in the line. */
private fun isChartable(song: GlyphSong, state: GlyphUiState): Boolean =
    song.chartState == GlyphChartState.NOT_GENERATED && song.filePath.isNotBlank() &&
        song.trackId !in state.queuedTracks

/**
 * A folder heading's action, a free pill of glass as the selection's are:
 * chart every song in the folder that has no chart, after the batch's
 * question of how long and how much.
 */
@Composable
private fun FolderChartPill(count: Int, haze: HazeState, onClick: () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides haze) {
        GlyphButton(
            text = if (count == 1) "Chart 1 song" else "Chart $count songs",
            primary = true,
            height = ACTION_HEIGHT,
            onClickLabel = "Chart every song in this folder that has no chart",
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
        )
    }
}

/** Screen heights for the card: the whole card, or one without its radar and graph. */
private val FULL_CARD_HEIGHT = 720.dp
private val COMPACT_CARD_HEIGHT = 600.dp

/**
 * The bottom of the song select: free pills of glass over the wheel, no pane
 * around them — the difficulties on one line, Training Ground and Play under
 * them. The card above names the song, draws the chart and shows the best
 * grade, so nothing here repeats it. A pane holding the pills read as a slab
 * over the wheel, and a single row squeezed the difficulties until one was cut
 * in half between its neighbours.
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
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each pill frosts the wheel under it. The wheel's haze source is this
    // panel's sibling, never its parent, so the frost has a real backdrop.
    CompositionLocalProvider(LocalGlassBackdrop provides haze) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        ) {
            // The course type on a line of its own above the charts, since
            // which charts a song has depends on it: Dance's four panels or
            // pop'n's nine buttons.
            GlyphSegmented(
                options = CourseType.entries.toList(),
                selected = state.course,
                label = { "${it.label} · ${it.buttonsLabel}" },
                onSelect = { onEvent(GlyphEvent.SetCourse(it)) },
                description = "Course type",
                height = 40.dp,
                textStyle = MaterialTheme.typography.labelMedium,
            )
            when {
                state.songListMode == GlyphSongListMode.EDIT -> EditChoice(song = song, state = state, onEvent = onEvent)
                // Picking for a room: a song without a chart is charted first, as for Play.
                state.songListMode == GlyphSongListMode.ROOM && song.hasChart -> RoomChoice(song = song, state = state, onEvent = onEvent)
                // The card says why there is no chart; the pill makes one, or
                // puts it in the line behind the songs being charted.
                !song.hasChart -> {
                    val (label, enabled) = generateLabel(state, song, short = false)
                    GlyphButton(
                        text = label,
                        primary = true,
                        enabled = enabled,
                        height = ACTION_HEIGHT,
                        onClick = { onEvent(GlyphEvent.GenerateChart) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    // The whole width, scrolling when a song has more charts
                    // than fit, so none is cut off by the pills beside it.
                    GlyphChipRow(
                        options = song.difficulties,
                        selected = state.selectedDifficulty,
                        label = { difficulty -> "${state.course.difficultyLabel(difficulty)} ${meterOf(state, difficulty)}" },
                        onSelect = { onEvent(GlyphEvent.SelectDifficulty(it)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                        GlyphButton(
                            text = "Train",
                            enabled = state.chart != null,
                            height = ACTION_HEIGHT,
                            onClickLabel = "Practise in Training Ground",
                            onClick = { onEvent(GlyphEvent.StartTraining) },
                            modifier = Modifier.weight(1f),
                        )
                        GlyphButton(
                            text = "Play",
                            icon = Icons.Default.PlayArrow,
                            primary = true,
                            enabled = state.chart != null,
                            height = ACTION_HEIGHT,
                            onClick = { onEvent(GlyphEvent.OpenOptions) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The actions' pills: thumb-sized. */
private val ACTION_HEIGHT = 52.dp


/**
 * The selection pills in the map editor's list: any difficulty, a new one
 * included, then Edit. A song with no chart can be generated first, or charted
 * from nothing at its tempo.
 */
@Composable
private fun EditChoice(song: GlyphSong, state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    var difficulty by remember(song.trackId) {
        mutableStateOf(state.selectedDifficulty ?: StepManiaDifficulty.MEDIUM)
    }
    LaunchedEffect(state.selectedDifficulty) { state.selectedDifficulty?.let { difficulty = it } }
    if (song.chartState == GlyphChartState.UNREADABLE) {
        // The card says the chart could not be read; generating replaces it.
        val (label, enabled) = generateLabel(state, song, short = false)
        GlyphButton(
            text = label,
            primary = true,
            enabled = enabled,
            height = ACTION_HEIGHT,
            onClick = { onEvent(GlyphEvent.GenerateChart) },
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    GlyphChipRow(
        options = StepManiaDifficulty.entries.toList(),
        selected = difficulty,
        label = { option ->
            val name = state.course.difficultyLabel(option)
            if (option in song.difficulties) "$name ${meterOf(state, option)}" else "$name · new"
        },
        onSelect = { difficulty = it },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
        GlyphButton(
            text = if (difficulty in song.difficulties) "Edit chart" else "New chart",
            primary = true,
            height = ACTION_HEIGHT,
            onClick = { onEvent(GlyphEvent.OpenEditor(difficulty)) },
            modifier = Modifier.weight(1f),
        )
        if (!song.hasChart) {
            val (label, enabled) = generateLabel(state, song, short = true)
            GlyphButton(
                text = label,
                enabled = enabled,
                height = ACTION_HEIGHT,
                onClick = { onEvent(GlyphEvent.GenerateChart) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Generate's words, and whether it can be pressed. With nothing charting it
 * makes the chart; with a batch running it joins the line; a song already in
 * the line says so rather than offering to queue it twice.
 */
private fun generateLabel(state: GlyphUiState, song: GlyphSong, short: Boolean): Pair<String, Boolean> = when {
    song.trackId in state.queuedTracks ->
        (if (state.generation?.trackId == song.trackId) "Charting…" else "Queued") to false
    state.generation?.active == true -> (if (short) "Queue" else "Add to queue") to true
    else -> (if (short) "Generate" else "Generate chart") to true
}

/** The grade sprite and percent of this phone's best run, and whose it is. */
@Composable
internal fun PersonalBest(best: GlyphAttempt) {
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
    state.simfile?.chart(difficulty)?.meter ?: state.course.meter(difficulty)

/**
 * Charting, as the line has it: one song's separation, generation and
 * writing as the service reports them, or a batch's count and how far it is.
 */
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
                val headline = when {
                    !generation.isBatch -> generation.failure ?: generation.stage
                    generation.active -> "${generation.done} of ${generation.total} charted"
                    generation.failed == 0 -> "${generation.total} charts ready"
                    else -> "${generation.done - generation.failed} of ${generation.total} charts ready"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // In the glass's ink either way: the theme's error red is
                    // chosen for the page, not for this pane. The meter below
                    // turns red, and the words say what failed.
                    Text(
                        text = headline,
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
                val failed = generation.failure != null || (generation.isBatch && !generation.active && generation.failed == generation.total)
                GlyphMeter(
                    fraction = generation.fraction,
                    color = if (failed) scheme.error else scheme.primary,
                    description = "$headline, $percent percent",
                )
                // The song in flight and what it is at, under a batch's count.
                if (generation.isBatch && generation.active) {
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(
                        text = listOf(generation.title, generation.stage).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                generation.firstFailure?.let { first ->
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(
                        text = "Not made: $first" + if (generation.failed > 1) ", and ${generation.failed - 1} more" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // Which server actually separated it, verbatim from the service —
                // a fallback to the CPU server never passes itself off as the GPU.
                generation.backendName?.let { backend ->
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    Text(
                        text = "Separated by $backend",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // Cancel while it runs; Close once it has finished, since the
                // job outlives the screen and its result would otherwise sit
                // here over the wheel.
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = when {
                        generation.isFinished -> "Close"
                        generation.isBatch -> "Cancel all"
                        else -> "Cancel"
                    },
                    onClick = onCancel,
                    onClickLabel = when {
                        generation.isFinished -> "Close"
                        generation.isBatch -> "Cancel every chart in the line"
                        else -> "Cancel chart generation"
                    },
                    height = 40.dp,
                )
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

/**
 * "Chart these songs?": how many, how much music, and what each mode would
 * take — Fast on a GPU, Slow on a CPU server — before a folder or the whole
 * library joins the line. A mode with no server says what it needs instead.
 */
@Composable
internal fun GlyphBatchDialog(offer: GlyphBatchOffer, onEvent: (GlyphEvent) -> Unit) {
    val count = offer.trackIds.size
    val songsLabel = if (count == 1) "1 song" else "$count songs"
    val chosen = if (offer.mode == ChartMode.FAST) offer.fast else offer.slow
    AlertDialog(
        onDismissRequest = { onEvent(GlyphEvent.DismissBatch) },
        title = { Text(if (offer.folder != null) "Chart $songsLabel in ${offer.folder}?" else "Chart $songsLabel?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                Text(
                    text = "${ChartPace.describeDuration(offer.audioSeconds).removePrefix("about ").replaceFirstChar { it.uppercase() }} of music.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (mode in ChartMode.entries) {
                    val estimate = if (mode == ChartMode.FAST) offer.fast else offer.slow
                    BatchModeRow(
                        mode = mode,
                        estimate = estimate,
                        selected = offer.mode == mode,
                        onSelect = { onEvent(GlyphEvent.ChooseBatchMode(mode)) },
                    )
                }
                Text(
                    text = "Charted in the background, two at a time. You can keep playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(GlyphEvent.ConfirmBatch) }, enabled = chosen.available) {
                Text("Chart $songsLabel")
            }
        },
        dismissButton = { TextButton(onClick = { onEvent(GlyphEvent.DismissBatch) }) { Text("Cancel") } },
    )
}

@Composable
private fun BatchModeRow(mode: ChartMode, estimate: GlyphModeEstimate, selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = estimate.available, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = estimate.available)
        Spacer(Modifier.width(MonoDimens.spacingSm))
        Column {
            Text(
                text = "${mode.label} · ${mode.hardware}",
                style = MaterialTheme.typography.titleSmall,
                color = if (estimate.available) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Text(
                text = if (estimate.available) {
                    listOfNotNull(estimate.time, estimate.cost, estimate.note).joinToString(" · ")
                } else {
                    estimate.note.replaceFirstChar { it.uppercase() }
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The selection pills when picking for a room: the difficulty, then Pick,
 * which sends the chart to the room and goes back to it. No Train and no
 * mods: the room plays one chart under one set of rules.
 */
@Composable
private fun RoomChoice(song: GlyphSong, state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    GlyphChipRow(
        options = song.difficulties,
        selected = state.selectedDifficulty,
        label = { difficulty -> "${difficulty.sscName} ${meterOf(state, difficulty)}" },
        onSelect = { onEvent(GlyphEvent.SelectDifficulty(it)) },
        modifier = Modifier.fillMaxWidth(),
    )
    GlyphButton(
        text = stringResource(R.string.tm_mp_pick_for_room),
        icon = Icons.Default.Groups,
        primary = true,
        enabled = state.chart != null,
        height = ACTION_HEIGHT,
        onClick = { onEvent(GlyphEvent.PickForRoom) },
        modifier = Modifier.fillMaxWidth(),
    )
}
