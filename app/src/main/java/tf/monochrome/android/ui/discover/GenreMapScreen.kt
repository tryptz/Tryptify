package tf.monochrome.android.ui.discover

import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.navigation.switchSafe
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import tf.monochrome.android.ui.components.GlassPanel
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.domain.model.PlayerGlassSettings
import coil3.compose.AsyncImage
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.reduceMotion
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.compose.currentStateAsState
import android.content.res.Configuration
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.ui.discover.galaxy.AudioBands
import tf.monochrome.android.ui.discover.galaxy.GALAXY_ARRIVE_DISTANCE
import tf.monochrome.android.ui.discover.galaxy.GALAXY_INK
import tf.monochrome.android.ui.discover.galaxy.PlanetSystem
import tf.monochrome.android.ui.discover.galaxy.GALAXY_SPACE
import tf.monochrome.android.ui.discover.galaxy.GalaxyCamera
import tf.monochrome.android.ui.discover.galaxy.GalaxyFrameStats
import tf.monochrome.android.ui.discover.galaxy.GalaxyPacer
import tf.monochrome.android.ui.discover.galaxy.GalaxyProbe
import tf.monochrome.android.ui.discover.galaxy.GalaxyScene
import tf.monochrome.android.ui.discover.galaxy.GenreGalaxyView
import tf.monochrome.android.ui.discover.galaxy.GenreStarField
import tf.monochrome.android.ui.discover.galaxy.MoonRef
import kotlin.math.exp

/**
 * The genre map — all 771 genres as a galaxy you can fly through.
 *
 * Every genre is a star, in 3D (see [GalaxyScene]). Two layouts, and the
 * stars glide between them:
 *
 * - **Galaxy**: the map's own baked layout as a disc. Twelve clusters, one per
 *   family, arranged so the families sharing the most genres end up next to
 *   each other — electronic beside hip-hop and pop, metal beside rock, folk
 *   beside country — and fusion genres leaning out of their cluster toward
 *   whatever else they belong to. Where a genre lies is an argument about what
 *   it is. Coordinates are baked into the asset at build time
 *   (`tools/build_genre_graph.py`), not simulated here — a map whose landmarks
 *   move between visits is one you can never learn.
 * - **Timeline**: a spiral. Distance from the core is when a genre began, the
 *   oldest music at the centre and the newest at the rim, and each family is
 *   an arm starting from where its cluster sits in the galaxy, so the morph
 *   turns the map rather than shuffling it.
 *
 * One finger orbits, two pinch and pan. Tapping a star selects it: the camera
 * travels there — a glide that pulls back over long distances, with a light
 * blur while it moves — and the panel opens. The panel names the subgenres
 * rather than counting them, and each of those is a tap to the next star.
 *
 * Drawn on Compose canvases, never OpenGL: the panels are glass, and a haze
 * pane cannot frost a SurfaceView. On API 33 and up the core, or the star you
 * are visiting, lights the map with god rays that its planets, the dust and the
 * stars cast shadows through (`GalaxyLight`).
 *
 * The panel expands. Collapsed it says what the curated dataset knows — family,
 * tempo, era, subgenres — which is a description of a genre's *shape* and never
 * of where it came from. Expanded it carries the genre's researched history:
 * its origins as the source states them, the genres either side of it in time,
 * and the article's own account of what happened, with the article and revision
 * it was taken from named and linked. That text is researched at build time and
 * verified to be about the genre it is filed under (`tools/fetch_genre_history.py`);
 * a genre with nothing verifiable written about it says so rather than being
 * given a plausible paragraph, because a map that invents history is worse than
 * one that admits a gap.
 *
 * The panel's four actions: *Play top* queues the genre's chart in rank order,
 * so it opens on the record that genre is actually known for. *Radio* opens a
 * few of those tracks and then hands over to the station planner, which keeps
 * going. *Top 100* asks the outside world what this genre actually plays, over
 * a window. *Explore in Discover* hands the genre to the feed, which rebuilds
 * around it and its graph neighbours.
 *
 * The panel is drawn from the mini player's own glass settings — the Studio's
 * "Player Glass" tab — because it floats directly above the bar and two sheets
 * of glass tuned differently an inch apart looked like a bug.
 */
@Composable
fun GenreMapScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: DiscoverViewModel = rememberDiscoverViewModel(),
) {
    val graph = viewModel.genreGraph
    val selected by viewModel.mapSelection.collectAsStateWithLifecycle()
    val expanded by viewModel.mapExpanded.collectAsStateWithLifecycle()
    val history by viewModel.mapHistory.collectAsStateWithLifecycle()
    val chartOpen by viewModel.mapChartOpen.collectAsStateWithLifecycle()
    val chart by viewModel.mapChart.collectAsStateWithLifecycle()
    val chartMessage by viewModel.mapChartMessage.collectAsStateWithLifecycle()

    // A tapped chart row that no catalogue can match says so. The row stays in
    // the list either way — the chart is a record of what was listened to, and
    // dropping the rows this app happens not to stock would quietly rewrite it.
    val chartToastContext = LocalContext.current
    LaunchedEffect(chartMessage) {
        chartMessage?.let {
            Toast.makeText(chartToastContext, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMapChartMessage()
        }
    }

    // The listener's look for the map, from its settings sheet.
    val visuals by viewModel.galaxyVisuals.collectAsStateWithLifecycle()
    val field = remember(graph) { if (graph.size == 0) null else GenreStarField.from(graph) }
    val familyColors = remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

    val hearted by viewModel.heartedGenres.collectAsStateWithLifecycle()
    val explored by viewModel.exploredGenres.collectAsStateWithLifecycle()
    val here by viewModel.mapHere.collectAsStateWithLifecycle()
    val chartSystem by viewModel.mapSystem.collectAsStateWithLifecycle()
    val facts by viewModel.planetFacts.collectAsStateWithLifecycle()
    val bios = remember(facts) { facts.mapNotNull { (artist, f) -> f.bio?.let { artist to it } }.toMap() }
    val playing by playerViewModel.currentTrack.collectAsStateWithLifecycle()

    // Finding a genre: a glass bar under the title, its matches as pills.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun closeSearch() {
        searchOpen = false
        query = ""
        keyboard?.hide()
    }

    /**
     * A star's music, and its planets as a list: what a long press on the star
     * does, and the dock's system glyph.
     */
    fun openSystem(stage: GalaxyStageScope, id: String) {
        if (id != selected?.id) viewModel.selectOnMap(id)
        viewModel.playGenre(id, playerViewModel)
        stage.openSheet(id)
    }

    GalaxyStage(
        navController = navController,
        playerViewModel = playerViewModel,
        field = field,
        familyColors = familyColors,
        visuals = visuals,
        onVisuals = viewModel::setGalaxyVisuals,
        selectedId = selected?.id,
        hereId = here?.id,
        explored = explored,
        hearted = hearted,
        system = chartSystem,
        // The genre you are at, once there is one: the title is where the
        // panel's heading went.
        title = selected?.name ?: stringResource(R.string.genre_galaxy),
        caption = { stage ->
            // The selected genre's facts, or what a star's place and size mean
            // in this layout.
            val facts = selected?.let {
                listOfNotNull(
                    graph.family(it.family)?.name ?: it.family,
                    if (it.hasTempo) stringResource(R.string.shelf_tempo, it.bpmLow, it.bpmHigh) else null,
                    it.era.getOrNull(0)?.let { year -> stringResource(R.string.discover_genre_since, year) },
                ).joinToString(" · ")
            }
            when {
                facts != null && stage.system != null && stage.visuals.planets ->
                    facts + "\n" + stringResource(R.string.galaxy_planets_caption)
                facts != null -> facts
                // What a star's place and size mean, and — now the panel that
                // said them is two pills — how much is explored and what the
                // dashed ring is.
                else -> listOfNotNull(
                    stringResource(if (stage.timeline) R.string.galaxy_time_caption else R.string.map_weight_popularity),
                    stringResource(R.string.discover_galaxy_count, explored.size, graph.size),
                    if (hearted.isNotEmpty()) stringResource(R.string.galaxy_legend_hearted_ring) else null,
                ).joinToString("\n")
            }
        },
        emptyText = if (graph.size == 0) stringResource(R.string.genre_map_failed) else null,
        onSelect = { viewModel.selectOnMap(it) },
        onLongPress = { stage, id -> openSystem(stage, id) },
        onPlayMoon = { ref -> (ref as? MoonRef.Chart)?.let { viewModel.playChartEntry(it.entry, playerViewModel) } },
        actions = { stage ->
            val node = selected
            buildList {
                add(
                    MapAction(
                        Icons.Default.Search,
                        stringResource(if (searchOpen) R.string.close_genre_search else R.string.search_genres),
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary else GALAXY_INK,
                    ) { if (searchOpen) closeSearch() else searchOpen = true },
                )
                if (node == null) {
                    add(MapAction(Icons.Default.AutoAwesome, stringResource(R.string.galaxy_surprise)) { viewModel.surpriseMe() })
                } else {
                    // Keeping a genre pins it to Discover's genre rail.
                    val isHearted = node.id in hearted
                    add(
                        MapAction(
                            if (isHearted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            stringResource(if (isHearted) R.string.genre_unkeep else R.string.genre_keep),
                        ) { viewModel.toggleHeartGenre(node.id) },
                    )
                }
                // The library galaxy, at the same place in the same sky.
                add(MapAction(Icons.Default.LibraryMusic, stringResource(R.string.galaxy_switch_to_library)) {
                    stage.handOff()
                    navController.switchSafe(tf.monochrome.android.ui.navigation.Screen.LibraryGalaxy.route)
                })
                add(stage.fullScreenAction)
                if (node != null) {
                    // Its researched history, as the dock's sheet.
                    add(
                        MapAction(
                            Icons.AutoMirrored.Filled.MenuBook,
                            if (expanded) stringResource(R.string.hide_history) else stringResource(R.string.read_history_of, node.name),
                            tint = if (expanded) familyColors[node.family] ?: GALAXY_INK else GALAXY_INK,
                        ) {
                            if (chartOpen) viewModel.toggleMapChart()
                            viewModel.toggleMapExpanded()
                        },
                    )
                }
                add(stage.lookAction)
                add(stage.recentreAction)
            }
        },
        sheet = { stage, id ->
            val sheetNode = selected
            if (sheetNode != null && sheetNode.id == id) {
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    tf.monochrome.android.ui.discover.galaxy.GalaxySystemSheet(
                        title = sheetNode.name,
                        detail = sheetNode.era.getOrNull(0)?.let { stringResource(R.string.discover_genre_since, it) },
                        system = stage.system?.takeIf { it.starId == sheetNode.id },
                        describe = { p, planet -> bios[planet.label] ?: tf.monochrome.android.ui.discover.galaxy.chartLine(p, planet, sheetNode.name) },
                        credit = if (stage.system?.planets?.any { it.label in bios } == true) stringResource(R.string.galaxy_bios_credit) else null,
                        playingTitle = playing?.title,
                        playingArtist = playing?.displayArtist,
                        isPlaying = { tf.monochrome.android.ui.discover.galaxy.chartMoonPlaying(it, playing?.title, playing?.displayArtist) },
                        onPlayMoon = { ref -> (ref as? MoonRef.Chart)?.let { viewModel.playChartEntry(it.entry, playerViewModel) } },
                        onClose = { stage.closeSheet() },
                        hazeState = stage.hazeState,
                        glass = stage.glass,
                        maxHeight = stage.sheetMaxHeight,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(stage.sheetModifier),
                    )
                }
            }
        },
        panel = { stage ->
            val node = selected
            if (node == null) {
                if (graph.size > 0) {
                    CompositionLocalProvider(
                        LocalPlayerGlass provides stage.glass,
                        tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                    ) {
                        GalaxyLayoutPills(
                            timeline = stage.timeline,
                            onTimeline = { stage.setTimeline(it) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .then(stage.panelModifier),
                        )
                    }
                }
            } else {
                val related = remember(graph, node.id) { relatedTo(graph, node) }
                // The shader modifier reads its parameters from this local, so
                // the dock has to provide it — this route sits outside the
                // nav host's provider, which only wraps the mini player.
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    GenreDock(
                        node = node,
                        related = related.nodes.take(MAX_RELATED_PILLS),
                        familyColor = familyColors[node.family] ?: MaterialTheme.colorScheme.primary,
                        hazeState = stage.hazeState,
                        glass = stage.glass,
                        expanded = expanded,
                        history = history,
                        chartOpen = chartOpen,
                        chart = chart,
                        sheetMaxHeight = stage.dockSheetMaxHeight,
                        onPlay = { viewModel.playGenre(node.id, playerViewModel) },
                        onRadio = { viewModel.radioGenre(node.id, playerViewModel) },
                        onToggleChart = {
                            // One sheet at a time: the dock is meant to stay small.
                            if (expanded) viewModel.toggleMapExpanded()
                            viewModel.toggleMapChart()
                        },
                        onPlayChartEntry = { viewModel.playChartEntry(it, playerViewModel) },
                        onRelated = { child -> viewModel.selectOnMap(child.id) },
                        onSystem = { openSystem(stage, node.id) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(stage.panelModifier),
                    )
                }
            }
        },
        underTitle = { stage ->
            // Over the map, not above it, so its glass frosts the sky.
            AnimatedVisibility(
                visible = searchOpen && graph.size > 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    GalaxySearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.search_genres_hint),
                        noMatch = stringResource(R.string.no_genre_match),
                        // Its other names too: "dnb" is drum and bass.
                        matches = remember(field, query) {
                            field?.let { f -> matchStars(f, query) { i -> graph.allGenres[i].aka } }.orEmpty()
                        },
                        hazeState = stage.hazeState,
                        glass = stage.glass,
                        onPick = { id ->
                            viewModel.selectOnMap(id)
                            closeSearch()
                        },
                        onClose = { closeSearch() },
                    )
                }
            }
        },
    )

    // Back closes the search before anything else on the map.
    androidx.activity.compose.BackHandler(enabled = searchOpen) { closeSearch() }
}

/**
 * What to offer next to a genre: its subgenres, or — for a leaf — its closest
 * relatives elsewhere on the map.
 *
 * A leaf with an empty list would be a dead end in the one place the map is
 * meant to keep you moving, and "drift phonk has no children" is not an
 * interesting fact about drift phonk.
 */
private data class Related(val nodes: List<GenreNode>, val areChildren: Boolean)

private fun relatedTo(graph: tf.monochrome.android.domain.model.GenreGraph, node: GenreNode): Related {
    val children = graph.children(node.id)
    if (children.isNotEmpty()) return Related(children, areChildren = true)
    return Related(
        graph.neighbours(node.id, maxHops = 1).map { it.node }.take(8),
        areChildren = false,
    )
}

/**
 * What a selected genre offers, as a few pills of glass that pop up over the
 * map rather than a panel over half of it: three glyphs — play, radio, its
 * Top 100 — and up to three genres next to it, by name. The Top 100 and the
 * history open as a compact sheet above the pills, one at a time.
 *
 * It replaced a panel that carried the path, the title, the facts, the
 * subgenres, four buttons in three rows and a close: a page over the map
 * rather than a control on it. The title and the facts are the screen's title
 * now, the way out is a tap on empty space or Back, and the heart and the
 * history are in the top bar.
 */
@Composable
private fun GenreDock(
    node: GenreNode,
    related: List<GenreNode>,
    familyColor: Color,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    expanded: Boolean,
    history: GenreHistoryState,
    chartOpen: Boolean,
    chart: GenreChartState,
    sheetMaxHeight: Dp,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onToggleChart: () -> Unit,
    onPlayChartEntry: (ChartEntry) -> Unit,
    onRelated: (GenreNode) -> Unit,
    /** Play the star and open its planets as a list, as a long press on it does. */
    onSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val instant = reduceMotion()
    // The last thing worth showing is held rather than read live: the sheet
    // goes back to Idle the instant it is told to close, and the fold-away
    // still has a moment to run — long enough to watch a finished article
    // turn back into "Looking it up…" on its way out.
    var shownHistory by remember(node.id) { mutableStateOf<GenreHistoryState>(history) }
    LaunchedEffect(history) { if (history != GenreHistoryState.Idle) shownHistory = history }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = chartOpen || expanded,
            enter = if (instant) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (instant) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            GlassPanel(hazeState = hazeState, glass = glass, avoidNavigationBar = false) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    if (chartOpen) {
                        GenreChartBody(state = chart, accent = familyColor, maxHeight = sheetMaxHeight, onPlay = onPlayChartEntry)
                    } else {
                        GenreHistoryBody(node = node, state = shownHistory, accent = familyColor, maxHeight = sheetMaxHeight)
                    }
                }
            }
        }

        // Where to go next, by name: three at most, and no glyph — the
        // glyphs are the actions, a name is a place.
        if (related.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                related.forEachIndexed { index, child ->
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = child.name,
                        selected = false,
                        accent = familyColor,
                        onClick = { onRelated(child) },
                        height = 36.dp,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .popIn(node.id, index),
                    )
                }
            }
        }

        // Four pills as wide as they are elsewhere, narrowing on a small phone
        // so the row always fits beside its margins.
        val room = LocalConfiguration.current.screenWidthDp.toFloat()
        val pillWidth = ((room - 2 * DOCK_MARGIN_DP - 3 * DOCK_GAP_DP) / 4f).coerceIn(56f, GLYPH_PILL_WIDTH.value).dp
        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(DOCK_GAP_DP.dp),
        ) {
            val base = related.size
            GlyphPill(Icons.Default.PlayArrow, stringResource(R.string.play_top), onPlay, hazeState, Modifier.popIn(node.id, base), width = pillWidth)
            GlyphPill(Icons.Default.Radio, stringResource(R.string.tab_radio), onRadio, hazeState, Modifier.popIn(node.id, base + 1), width = pillWidth)
            GlyphPill(
                Icons.Default.BarChart,
                stringResource(R.string.top_100),
                onToggleChart,
                hazeState,
                Modifier.popIn(node.id, base + 2),
                selected = chartOpen,
                accent = familyColor,
                width = pillWidth,
            )
            // Its system: three planets of different sizes.
            GlyphPill(
                tf.monochrome.android.ui.discover.galaxy.SystemGlyph,
                stringResource(R.string.galaxy_system_button),
                onSystem,
                hazeState,
                Modifier.popIn(node.id, base + 3),
                width = pillWidth,
            )
        }
    }
}

/**
 * One of the dock's actions: a glyph cut out of a pill of glass, the way the
 * tab bar's are — a hole the shader bevels, with the frost showing through —
 * and nothing else. Lit in [accent] over its hole while [selected], as a
 * selected tab is. Where the shader does not run there is no slab to cut, and
 * the glyph is an ordinary icon.
 */
@Composable
internal fun GlyphPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    width: Dp = GLYPH_PILL_WIDTH,
) {
    val painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon)
    val carved = tf.monochrome.android.ui.components.glassPunches()
    tf.monochrome.android.ui.components.GlassPill(
        onClick = onClick,
        hazeState = hazeState,
        height = GLYPH_PILL_HEIGHT,
        onClickLabel = label,
        modifier = modifier
            .width(width)
            .semantics { contentDescription = label },
        punch = if (carved) {
            {
                val g = GLYPH_DP.dp.toPx()
                // Whole pixels, or the hole's edge comes out soft.
                translate(kotlin.math.round((size.width - g) / 2f), kotlin.math.round((size.height - g) / 2f)) {
                    with(painter) { draw(androidx.compose.ui.geometry.Size(g, g)) }
                }
            }
        } else {
            null
        },
    ) {
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.5.dp, accent.copy(alpha = 0.85f), CircleShape),
            )
        }
        if (!carved || selected) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(GLYPH_DP.dp),
            )
        }
    }
}

/**
 * Pops a pill in when the genre it belongs to is selected: from a little small
 * and clear to full size with a spring's overshoot, [index] steps after the
 * first so a row arrives one pill at a time. Still with reduced motion.
 */
@Composable
internal fun Modifier.popIn(key: Any, index: Int): Modifier {
    val instant = reduceMotion()
    val pop = remember(key) { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(key) {
        if (instant) return@LaunchedEffect
        kotlinx.coroutines.delay(index * POP_STAGGER_MILLIS)
        pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
    }
    return graphicsLayer {
        val v = pop.value
        val sc = 0.6f + 0.4f * v
        scaleX = sc
        scaleY = sc
        alpha = v.coerceIn(0f, 1f)
    }
}


/**
 * The expanded half of the panel: what this genre is, where it came from, and
 * what the record says happened to it.
 *
 * Scrolls inside a bounded height rather than growing the panel to fit. Techno's
 * article would otherwise push the panel past the top of the screen, and the
 * genre's own dot — the thing the whole map exists to point at — off it.
 *
 * Every genre here is one of two states and never a third: there is a verified
 * article, or there is nothing and it says so. The dataset carries no
 * paragraphs that were written to fill the gap.
 */
/**
 * The genre's chart, inside the panel.
 *
 * A LazyColumn in a bounded height, the same shape the world globe's station
 * list settled on and for the same two reasons: a hundred rows composed eagerly
 * to show six is work nobody asked for, and the panel must never grow tall
 * enough to cover the genre dot it is describing.
 *
 * Artwork comes with the chart row itself, so the list draws in full without
 * resolving anything. Tapping is what costs a catalogue search, and only for
 * the row tapped.
 */
@Composable
private fun GenreChartBody(
    state: GenreChartState,
    accent: Color,
    maxHeight: Dp,
    onPlay: (ChartEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        when (state) {
            // Idle is the frame between the tap and the flow noticing. Drawn as
            // the loading line rather than as nothing, so the section does not
            // flicker empty on its way open.
            GenreChartState.Idle, GenreChartState.Loading -> Text(
                text = stringResource(R.string.counting_them_up),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GenreChartState.Unreachable -> Text(
                text = stringResource(R.string.chart_sources_unreachable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is GenreChartState.Ready -> {
                val entries = state.chart.entries
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_chart_for_genre),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = maxHeight)) {
                        // Unkeyed: the rank would be the obvious key, and two
                        // sources joined into one chart can tie. A duplicated
                        // row beats a crash.
                        items(entries) { entry ->
                            PanelChartRow(
                                entry = entry,
                                accent = accent,
                                onPlay = { onPlay(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One chart row, sized for the panel rather than for a full screen. */
@Composable
private fun PanelChartRow(entry: ChartEntry, accent: Color, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = entry.rank.toString(),
            style = MaterialTheme.typography.labelLarge,
            // Monospace so a 1 beside an 8 doesn't make the column wander,
            // which is very visible down a hundred rows.
            fontFamily = FontFamily.Monospace,
            color = accent,
            modifier = Modifier.width(26.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(MonoDimens.spacingXs))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (entry.artworkUrl != null) {
                AsyncImage(
                    model = entry.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GenreHistoryBody(
    node: GenreNode,
    state: GenreHistoryState,
    accent: Color,
    maxHeight: Dp,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.padding(top = 12.dp)) {
        when (state) {
            // Idle only happens for the frame between the tap and the flow
            // noticing, so it draws as the loading line rather than as nothing —
            // a panel that flickers empty on the way open looks broken.
            GenreHistoryState.Idle, GenreHistoryState.Loading -> Text(
                text = stringResource(R.string.looking_it_up),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GenreHistoryState.Missing -> Text(
                text = stringResource(R.string.no_verified_history, node.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is GenreHistoryState.Ready -> {
                val history = state.history
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // The infobox facts first: "1985, Chicago" and the genres
                    // either side of it are the whole history in three lines,
                    // for a reader who isn't going to read the paragraphs.
                    history.cultural?.let { HistoryFact(stringResource(R.string.history_origins), it, accent) }
                    if (history.stylistic.isNotEmpty()) {
                        HistoryFact(stringResource(R.string.history_grew_out_of), history.stylistic.joinToString(stringResource(R.string.list_separator)), accent)
                    }
                    if (history.derivatives.isNotEmpty()) {
                        HistoryFact(stringResource(R.string.history_led_to), history.derivatives.joinToString(stringResource(R.string.list_separator)), accent)
                    }
                    if (history.hasOrigins) Spacer(Modifier.height(10.dp))

                    Text(
                        text = history.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    for (section in history.sections) {
                        // Subsections step in, so a "1990s" under "History"
                        // reads as part of it rather than as a peer. Capped at
                        // two steps: articles nest headings four deep and a
                        // fourth indent on a phone leaves no line left.
                        val indent = (section.level - 1).coerceIn(0, 2) * 10
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = section.heading,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (section.level > 1) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(start = indent.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = section.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = indent.dp),
                        )
                    }

                    // The source, and a way to go and check it. Required by the
                    // licence the text ships under, and the thing that makes
                    // the difference between a claim and a citation.
                    Spacer(Modifier.height(14.dp))
                    Text(
                        // The article is English Wikipedia's, and the translations
                        // say so, since the paragraph above is in English.
                        text = history.fragment?.let {
                            stringResource(R.string.wikipedia_attribution_section, it, history.title)
                        } ?: stringResource(R.string.wikipedia_attribution, history.title),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier
                            .bounceClick(onClick = { uriHandler.openUri(history.url) })
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** One infobox fact — a short label above the thing it names. */
@Composable
private fun HistoryFact(label: String, value: String, accent: Color) {
    Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** At most this many genres next to the selected one, as pills. */
private const val MAX_RELATED_PILLS = 3

internal val GLYPH_PILL_WIDTH = 72.dp
private val GLYPH_PILL_HEIGHT = 48.dp

/** A dock glyph's size, dp: the hole it is cut as, and the icon where it is not. */
private const val GLYPH_DP = 24

/** The dock's glyph row: its side margins and the gap between pills, dp. */
internal const val DOCK_MARGIN_DP = 16f
internal const val DOCK_GAP_DP = 12f

/** How long after the previous pill each pops in. */
private const val POP_STAGGER_MILLIS = 45L

/**
 * A stable colour per family.
 *
 * Hand-picked rather than generated: twelve evenly-spaced hues collide badly at
 * small sizes, and these are chosen to stay distinguishable as 4-pixel dots on
 * both light and dark backgrounds.
 */
internal fun familyPalette(families: List<String>): Map<String, Color> {
    val palette = listOf(
        "electronic" to Color(0xFF4FC3F7),
        "hiphop" to Color(0xFFFFB74D),
        "rock" to Color(0xFFE57373),
        "metal" to Color(0xFF9575CD),
        "pop" to Color(0xFFF06292),
        "jazz" to Color(0xFF4DB6AC),
        "classical" to Color(0xFFA1887F),
        "folk" to Color(0xFFAED581),
        "soul" to Color(0xFFFFD54F),
        "latin" to Color(0xFFFF8A65),
        "global" to Color(0xFF64B5F6),
        "experimental" to Color(0xFF90A4AE),
    ).toMap()
    return families.associateWith { palette[it] ?: Color(0xFFBDBDBD) }
}


