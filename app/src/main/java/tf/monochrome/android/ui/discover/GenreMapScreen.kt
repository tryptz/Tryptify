package tf.monochrome.android.ui.discover

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.PI
import androidx.compose.ui.graphics.Path
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * The genre map — all 355 genres as one picture you can move around in.
 *
 * Twelve radial clusters, one per family, arranged so that the families sharing
 * the most genres end up next to each other — electronic beside hip-hop and pop,
 * metal beside rock, folk beside country. Inside each cluster a genre fans out
 * from its family root, and the fusion genres lean out of their cluster toward
 * whatever else they belong to, so jazz house comes to rest between electronic
 * and jazz rather than pretending to sit wholly inside one of them. Where a
 * genre lies is an argument about what it is.
 *
 * Coordinates are baked into the asset at build time
 * (`tools/build_genre_graph.py`), not simulated here — a force layout would
 * spend battery arriving at the same picture on every launch, and a map whose
 * landmarks move between visits is one you can never learn.
 *
 * Cluster size grows with the square root of its population, which is why
 * electronic is the biggest without being the whole picture: it holds half the
 * dataset but takes a third of the canvas.
 *
 * Tapping a genre does two things at once: it opens the panel for it and flies
 * the camera over to centre it, and it folds or unfolds whatever grows below it
 * — the subtree scaling out of its parent, or shrinking back into it. So you can
 * fold electronic away and actually see folk, and you can walk down into a
 * family one tap at a time. The panel names the subgenres rather than counting
 * them, and each of those is a tap to the next one.
 *
 * The panel's four actions: *Play top* queues the genre's chart in rank order,
 * so it opens on the record that genre is actually known for. It used to search
 * the catalogue for the genre's name and shuffle the results, which ranked by
 * how well a title or album matched the words — the one query machine-generated
 * filler reliably wins. *Radio* opens a few of those tracks and then hands over
 * to the station planner, which keeps going. *Top 100* leaves the catalogue
 * behind and asks the outside world what this genre actually plays, over a
 * window. *Explore in Discover* hands the genre to the feed, which rebuilds
 * around it and its graph neighbours.
 *
 * The panel is drawn from the mini player's own glass settings — the Studio's
 * "Player Glass" tab — because it floats directly above the bar and two sheets
 * of glass tuned differently an inch apart looked like a bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreMapScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: DiscoverViewModel = rememberDiscoverViewModel(),
) {
    val graph = viewModel.genreGraph
    val selected by viewModel.mapSelection.collectAsStateWithLifecycle()

    // Collapsed branches, by node id. Starts empty: the first thing you should
    // see is the whole thing, and folding is the exception.
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    // The fold currently playing out, if any. A collapse holds off on updating
    // `collapsed` until the animation finishes — otherwise the subtree would be
    // gone from the first frame and there'd be nothing left to shrink.
    var fold by remember { mutableStateOf<Fold?>(null) }
    var foldEpoch by remember { mutableIntStateOf(0) }

    var camera by remember { mutableStateOf(Camera()) }

    // What's actually drawn: everything except the descendants of a collapsed
    // branch. Derived rather than stored so collapsing can't desync the two.
    val visible by remember(graph, collapsed) {
        derivedStateOf { visibleNodes(graph, collapsed) }
    }

    val density = LocalDensity.current
    val labelPx = with(density) { 11.dp.toPx() }
    var layout by remember { mutableStateOf(MapLayout.RADIAL) }
    val timeline = remember(graph) { timelineFor(graph) }
    // 0 is the radial map, 1 the timeline. Everything that differs between the
    // two reads this, so the transition is one number rather than a mode flag
    // and a pile of branches.
    val morph = remember { Animatable(0f) }
    LaunchedEffect(layout) {
        morph.animateTo(
            targetValue = if (layout == MapLayout.TIMELINE) 1f else 0f,
            animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
        )
    }

    val radialBounds = remember(graph) { boundsOf(graph.allGenres) }
    // Lerped rather than swapped: snapping the frame at the start of the
    // animation would jolt every node before any of them had moved.
    val bounds = lerpBounds(radialBounds, timeline.bounds, morph.value)

    val familyColors = remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

    // Read at gesture time rather than captured, so the pointer handlers can be
    // keyed on Unit. Keying them on the camera would rebuild both gesture
    // detectors on every frame of a pan, a pinch or a camera flight — which
    // drops the pointer stream mid-gesture.
    val liveCamera = rememberUpdatedState(camera)
    val liveVisible = rememberUpdatedState(visible)
    val liveFold = rememberUpdatedState(fold)
    // These two have to be read live for the same reason, and did not before the
    // timeline existed: `bounds` used to be a constant computed once from the
    // graph, so capturing it was harmless. It is now interpolated between the
    // two layouts and changes every frame, and `morph` decides where a node
    // actually is. Captured, the hit test goes on computing radial positions
    // while the screen shows something else, and taps land on nothing.
    val liveBounds = rememberUpdatedState(bounds)
    val liveMorph = rememberUpdatedState(morph.value)

    // How much of the bottom the detail panel is covering, so a genre can be
    // centred in the part of the map you can actually see.
    //
    // Seeded with an estimate rather than zero and deliberately not a key of the
    // flight below: the panel only exists once something is selected, so a
    // measured-only value would make the very first selection fly to the canvas
    // centre and then fly again to correct itself.
    var panelHeightPx by remember { mutableIntStateOf(with(density) { 230.dp.roundToPx() }) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val scope = rememberCoroutineScope()
    // The in-flight camera move, so a touch can take the controls back off it.
    var flight by remember { mutableStateOf<Job?>(null) }
    var folding by remember { mutableStateOf<Job?>(null) }
    val instant = reduceMotion()

    // The map blurs itself behind the panel. A local haze source rather than the
    // shared app one: the panel sits inside the same subtree, and pointing it at
    // the app-wide state would have it sampling its own output. (The map also
    // feeds the *app* haze — it runs full-bleed under the mini player, whose
    // glass needs real content behind it — but that source is declared once by
    // the nav host around everything, not here.)
    val mapHaze = rememberHazeState()

    // The map runs under the mini player so the bar has something to lens, so
    // the panel has to clear the bar itself.
    val playing by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val panelBottomInset = if (playing != null) MINI_PLAYER_RESERVE else 0.dp

    // A selected genre swells and springs back — bouncy enough to read as a
    // response to the tap, and it settles larger than it started so the node
    // you're looking at stays the obvious one on a map of 355 dots.
    val selectPop = remember { Animatable(SELECTED_SCALE) }
    LaunchedEffect(selected?.id) {
        if (selected == null) return@LaunchedEffect
        if (instant) { selectPop.snapTo(SELECTED_SCALE); return@LaunchedEffect }
        selectPop.snapTo(SELECTED_SCALE * 1.7f)
        selectPop.animateTo(
            targetValue = SELECTED_SCALE,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    // The same settings the mini player is drawn from, straight off the
    // Studio's "Player Glass" tab. Read from the player rather than through
    // LocalPlayerGlass because the nav host only provides that local around the
    // mini player and the page indicator, not around detail routes.
    val glassSettings by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val hearted by viewModel.heartedGenres.collectAsStateWithLifecycle()

    fun focusOn(node: GenreNode) {
        flight?.cancel()
        if (canvasSize == IntSize.Zero) return
        flight = scope.launch {
            flyTo(node, canvasSize, panelHeightPx, bounds, camera, instant) { camera = it }
        }
    }

    /** Unfold a genre's subtree, or fold it back, growing it out of the genre itself. */
    fun toggleBranch(id: String) {
        val subtree = descendantsOf(graph, id)
        if (subtree.isEmpty()) return

        // Cancelling a fold kills the coroutine before it can commit, so the
        // interrupted one is settled here rather than left half-applied. Doing
        // it before reading `collapsed` below is also what makes tapping twice
        // mid-animation reverse the fold instead of restarting it.
        folding?.cancel()
        fold?.let { if (it.collapsing) collapsed = collapsed + it.rootId }
        fold = null

        val collapsing = id !in collapsed
        if (!collapsing) collapsed = collapsed - id
        if (instant) {
            if (collapsing) collapsed = collapsed + id
            return
        }
        // Seeded synchronously: `collapsed` has already changed, and a frame
        // drawn before the coroutine's first dispatch would show the subtree
        // at full size — the pop this exists to avoid.
        foldEpoch += 1
        val epoch = foldEpoch
        fold = Fold(epoch, id, subtree, collapsing, if (collapsing) 1f else 0f)
        folding = scope.launch {
            animate(
                initialValue = if (collapsing) 1f else 0f,
                targetValue = if (collapsing) 0f else 1f,
                animationSpec = tween(
                    if (collapsing) FOLD_IN_MILLIS else FOLD_OUT_MILLIS,
                    // Growing overshoots a little and settles back — the
                    // difference between a subtree appearing and one unfurling.
                    easing = if (collapsing) FoldInEasing else FoldOutEasing,
                ),
            ) { value, _ ->
                fold?.takeIf { it.epoch == epoch }?.let { fold = it.copy(progress = value) }
            }
            if (fold?.epoch == epoch) {
                if (collapsing) collapsed = collapsed + id
                fold = null
            }
        }
    }

    // Selecting a genre — by tapping it, or by tapping a subgenre chip in the
    // panel — flies the camera to it. Keyed on the id so re-selecting the same
    // genre doesn't re-fly, and on the canvas size so a selection made before
    // the first measurement still lands.
    LaunchedEffect(selected?.id, canvasSize) {
        selected?.let { focusOn(it) }
    }

    // Constellations are off by default. They explain the map's structure,
    // which is worth seeing once and then not every time.
    var showRings by remember { mutableStateOf(false) }
    val constellations = remember(graph) { constellationsFor(graph) }
    // Popularity leads: with 771 nodes, which genres are big is the first thing
    // that makes the picture navigable.
    var weighting by remember { mutableStateOf(MapWeight.POPULARITY) }
    val weightScale = remember(graph) { WeightScale(graph) }


    // Measured rather than assumed: the bar's height moves with font scale and
    // with the display cutout, and a hard-coded inset would be wrong on exactly
    // the devices where the labels have least room.
    var topBarHeightPx by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            // "Genre map" wrapped to two lines once the bar carried five
            // actions, and a wrapped title crowds the first row of labels.
            title = { Text("Genres") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // These set every branch at once, so they cancel any single fold
                // in flight — letting it commit afterwards would re-fold one
                // branch a moment after you asked for all of them.
                IconButton(onClick = {
                    folding?.cancel(); fold = null
                    collapsed = graph.roots.map { it.id }.toSet()
                }) {
                    Icon(Icons.Default.UnfoldLess, contentDescription = "Collapse everything")
                }
                IconButton(onClick = {
                    folding?.cancel(); fold = null
                    collapsed = emptySet()
                }) {
                    Icon(Icons.Default.UnfoldMore, contentDescription = "Expand everything")
                }
                IconButton(onClick = {
                    // A width fit leaves the timeline's height running off the
                    // bottom, so it has to arrive at its *top* — the oldest
                    // music, where the story starts — rather than centred on
                    // some row in the middle of the 1990s. Reset before the
                    // morph so the two settle together.
                    flight?.cancel()
                    camera = Camera()
                    layout = if (layout == MapLayout.RADIAL) MapLayout.TIMELINE else MapLayout.RADIAL
                }) {
                    Icon(
                        if (layout == MapLayout.TIMELINE) Icons.Default.BubbleChart
                        else Icons.Default.Timeline,
                        contentDescription = if (layout == MapLayout.TIMELINE) {
                            "Back to the constellation map"
                        } else {
                            "Arrange by year"
                        },
                    )
                }
                IconButton(onClick = { weighting = weighting.next() }) {
                    Icon(Icons.Default.Tune, contentDescription = "Change what size means")
                }
                IconButton(onClick = { showRings = !showRings }) {
                    Icon(
                        Icons.Default.TrackChanges,
                        contentDescription = if (showRings) "Hide constellations" else "Show constellations",
                        tint = if (showRings) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
                IconButton(onClick = { flight?.cancel(); camera = Camera() }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recentre")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(mapHaze)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            // Touching the map takes it back from any camera
                            // move in progress — being dragged around while
                            // you're trying to steer is the worst kind of
                            // animation.
                            flight?.cancel()
                            camera = liveCamera.value
                                .zoomedAt(centroid, pan, zoom, size, liveBounds.value)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { point ->
                            val hit = hitTest(
                                point, liveVisible.value, size.width, size.height,
                                liveBounds.value, liveCamera.value, liveFold.value,
                                timeline = timeline.positions,
                                morph = liveMorph.value,
                            ) ?: return@detectTapGestures
                            viewModel.selectOnMap(hit.id)
                            toggleBranch(hit.id)
                        }
                    },
            ) {
                if (morph.value < 0.999f) {
                    // The timeline chrome takes over as these fade; drawing
                    // both at full strength mid-flight is unreadable.
                    if (showRings) {
                        drawConstellations(
                            constellations = constellations,
                            bounds = bounds,
                            camera = camera,
                            familyColors = familyColors,
                            fade = 1f - morph.value,
                            morphFit = morph.value,
                        )
                    }
                }
                if (morph.value > 0.001f) {
                    drawTimelineChrome(
                        timeline = timeline,
                        bounds = bounds,
                        camera = camera,
                        labelColor = onSurface,
                        labelSizePx = labelPx,
                        fade = morph.value,
                        morphFit = morph.value,
                    )
                }
                drawMap(
                    nodes = visible,
                    positions = positionsFor(
                        visible, size.width, size.height, bounds, camera, fold,
                        timeline = timeline.positions,
                        morph = morph.value,
                    ),
                    collapsed = collapsed,
                    selectedId = selected?.id,
                    selectedScale = selectPop.value,
                    fold = fold,
                    familyColors = familyColors,
                    edgeColor = edgeColor,
                    labelColor = onSurface,
                    labelSizePx = labelPx,
                    scale = camera.scale,
                    weighting = weighting,
                    weights = weightScale,
                    morph = morph.value,
                    // The map runs full-bleed under the transparent bar on
                    // purpose, so its dots showing through are intended — but a
                    // *name* under the bar is two pieces of text on top of each
                    // other, which is just unreadable.
                    topInset = maxOf(
                        topBarHeightPx.toFloat(),
                        TIMELINE_AXIS_INSET * morph.value,
                    ),
                    bottomInset = if (selected != null) panelBottomInset.toPx() + panelHeightPx else 0f,
                )
            }

            // Names the active weighting. Without it a dot's size is a claim
            // with no stated units — the listener can see that one genre is
            // bigger without being able to find out in what sense.
            Text(
                text = if (morph.value > 0.5f) {
                    "Oldest left, newest right — each subgenre branches under its parent"
                } else {
                    weighting.caption
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = MonoDimens.spacingLg,
                        end = MonoDimens.spacingLg,
                        bottom = MonoDimens.spacingSm,
                        // The timeline reserves a strip at the very top for its
                        // year axis, and the caption was landing inside it —
                        // two lines of text in the same place. Slide below the
                        // strip in step with the morph so it moves with the
                        // layout rather than jumping when it arrives.
                        top = MonoDimens.spacingSm +
                            with(density) { TIMELINE_AXIS_INSET.toDp() } * morph.value,
                    ),
            )

            selected?.let { node ->
                val related = remember(graph, node.id) { relatedTo(graph, node) }
                // The shader modifier reads its parameters from this local, so
                // the panel has to provide it — this route sits outside the
                // nav host's provider, which only wraps the mini player.
                CompositionLocalProvider(LocalPlayerGlass provides glassSettings) {
                GenreCard(
                    node = node,
                    related = related,
                    familyName = graph.family(node.family)?.name ?: node.family,
                    familyColor = familyColors[node.family] ?: MaterialTheme.colorScheme.primary,
                    hazeState = mapHaze,
                    glass = glassSettings,
                    hearted = node.id in hearted,
                    onHeart = { viewModel.toggleHeartGenre(node.id) },
                    onPlay = { viewModel.playGenre(node.id, playerViewModel) },
                    onRadio = { viewModel.radioGenre(node.id, playerViewModel) },
                    onCharts = {
                        navController.navigateSafe(
                            Screen.GenreChart.createRoute(node.id, node.name)
                        )
                    },
                    onExplore = {
                        viewModel.selectGenre(node.id)
                        navController.popBackStack()
                    },
                    onRelated = { child ->
                        // A subgenre under a folded branch is not on the map, so
                        // flying to it would land on nothing. Unfold on the way —
                        // through the same animation, not by snapping it open.
                        if (node.id in collapsed) toggleBranch(node.id)
                        viewModel.selectOnMap(child.id)
                    },
                    onDismiss = { viewModel.selectOnMap(null) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Measured outside the reserve, not inside it: the
                        // camera centres a genre in what's left of the map, and
                        // the mini player occludes that too.
                        .onSizeChanged { panelHeightPx = it.height }
                        .padding(bottom = panelBottomInset),
                )
                }
            }

            if (graph.size == 0) {
                Text(
                    text = "The genre map didn't load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
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

/** The detail panel for a tapped genre — what it is, where to go next, what to do with it. */
@Composable
private fun GenreCard(
    node: GenreNode,
    related: Related,
    familyName: String,
    familyColor: Color,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    hearted: Boolean,
    onHeart: () -> Unit,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onCharts: () -> Unit,
    onExplore: () -> Unit,
    onRelated: (GenreNode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Exactly the mini player's glass, from exactly the same settings: the
    // panel floats directly above the bar, and two sheets of glass with
    // different tints and different tuning an inch apart looked like a mistake.
    // On the shader path that means the frosted haze backdrop with the tunable
    // slab relit on top; below API 33, with button glass switched off, or on a
    // low tier it falls back to the app's plain glassmorphism, and with liquid
    // glass removed entirely to an opaque surface — a no-op modifier would
    // otherwise leave the panel with no background at all.
    val allowHaze = LocalPerformanceProfile.current.allowHazeBlur
    val flat = LocalLowPerformance.current.disableLiquidGlass
    val shaderGlass = !flat && glass.enabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else MaterialTheme.colorScheme.primary
    val frostBg = MaterialTheme.colorScheme.background
    val isDark = frostBg.luminance() <= 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .navigationBarsPadding()
            .clip(MonoDimens.shapeLg)
            .then(
                when {
                    shaderGlass -> Modifier
                    allowHaze && !flat ->
                        Modifier.liquidGlass(hazeState = hazeState, shape = MonoDimens.shapeLg)
                    else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            ),
    ) {
        if (shaderGlass) {
            // Frost first. The slab body goes down to 0.2 opacity, and without
            // this the map's edges and labels read straight through it and
            // fight the panel's own text.
            if (allowHaze && glass.hazeBlurDp > 0f) {
                val frostTint = (if (isDark) Color.Black.copy(alpha = 0.32f)
                    else Color.White.copy(alpha = 0.45f))
                    .let { it.copy(alpha = (it.alpha * glass.hazeTint).coerceIn(0f, 1f)) }
                Box(
                    Modifier
                        .matchParentSize()
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = frostBg,
                                blurRadius = glass.hazeBlurDp.dp,
                                tints = listOf(HazeTint(frostTint)),
                                noiseFactor = 0f,
                            ),
                        ),
                )
            }
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .playerGlass(tint = tint),
            ) {
                // Round, not a rect. The glass shader builds its bevel and rim
                // from the gradient of what this canvas draws, so a full-bleed
                // rect gives it straight edges only: the parent clip then cuts
                // the square corners away and each corner is left with no rim
                // at all — four visible gaps in the outline. Drawing the same
                // radius the panel is clipped to puts an alpha edge on the arc,
                // so the rim runs continuously around the corner.
                val r = MonoDimens.radiusLg.toPx()
                drawRoundRect(color = tint, cornerRadius = CornerRadius(r, r))
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(familyName)
                            if (node.hasTempo) append(" · ${node.bpmLow}–${node.bpmHigh} BPM")
                            node.era.getOrNull(0)?.let { append(" · from $it") }
                            if (related.areChildren) append(" · ${related.nodes.size} subgenres")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Hearting a genre pins it to Discover's genre rail, which is
                // the only place the map's choices survive leaving the map.
                IconButton(onClick = onHeart, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (hearted) Icons.Default.Favorite
                        else Icons.Default.FavoriteBorder,
                        contentDescription = if (hearted) "Remove from your genres"
                        else "Keep in your genres",
                        tint = if (hearted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                // Close moves up here out of the action row, which now has to
                // hold three things and had no room left for a fourth.
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (node.aka.isNotEmpty()) {
                Text(
                    text = "also called " + node.aka.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Where to go next. Naming the subgenres rather than counting them
            // is the difference between "Dub has 3 subgenres" and being one tap
            // from dub techno — and each tap flies the map to it, so the panel
            // doubles as a way to steer.
            if (related.nodes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (related.areChildren) "Subgenres" else "Closest to it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(related.nodes, key = { it.id }) { child ->
                        RelativeChip(
                            node = child,
                            accent = familyColor,
                            onClick = { onRelated(child) },
                        )
                    }
                }
            }

            // Three actions instead of two, so they get two rows rather than
            // being squeezed until "Explore in Discover" ellipsises itself into
            // "Explore in Disco…". Shuffle and Radio share the top row — both
            // start music, both are one word — and Explore takes the full width
            // below, since it's the one that leaves the map.
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    icon = Icons.Default.PlayArrow,
                    label = "Play top",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                )
                ActionPill(
                    icon = Icons.Default.Radio,
                    label = "Radio",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onRadio,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                ActionPill(
                    icon = Icons.Default.BarChart,
                    label = "Top 100",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onCharts,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                ActionPill(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    label = "Explore in Discover",
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onExplore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MonoDimens.shapePill),
                )
            }
        }
    }
}

/** One action in the panel's button rows — icon, then label, centred. */
@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One subgenre (or close relative) in the panel's rail.
 *
 * Tinted with the family colour so the chips read as the same thing as the dots
 * on the map behind them, and carrying its tempo because on a map about genres
 * "138–142 BPM" is often the fastest way to know whether you want it.
 */
@Composable
private fun RelativeChip(node: GenreNode, accent: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = accent.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (node.hasTempo) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${node.bpmLow}–${node.bpmHigh}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── layout & drawing ───────────────────────────────────────────────────────

private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 14f

/** Leaves a little air around the map at scale 1 instead of running it to the bezel. */
private const val FIT_MARGIN = 0.92f

/** Breathing room around a label when testing it against its neighbours. */
private const val LABEL_GAP = 3f

/**
 * How close the camera gets when you select a genre.
 *
 * Only ever a floor: flying to a node never pulls you *back* from a zoom you
 * chose yourself, it just guarantees that whatever you tapped ends up close
 * enough to read its neighbours' labels.
 */
private const val FOCUS_SCALE = 2.6f

/** Resting size of the selected dot, relative to its neighbours. */
private const val SELECTED_SCALE = 1.45f

/** Height the floating mini player needs, matching the nav host's own reserve. */
private val MINI_PLAYER_RESERVE = 72.dp
private const val FLIGHT_MILLIS = 620

/**
 * The camera over the map: how far in, and where it's looking.
 *
 * One object rather than two pieces of state because zooming about a point
 * changes both together, and a frame that applied the new scale with the old
 * offset would visibly kick sideways.
 */
private data class Camera(val scale: Float = 1f, val offset: Offset = Offset.Zero) {

    /**
     * Pinch about [centroid] — the point between the fingers stays under them.
     *
     * Zooming about the *screen centre* instead is the thing that makes a map
     * feel broken: you pinch on the corner you're interested in and the map
     * runs away from your fingers. The ratio is taken from the clamped scale,
     * not the raw gesture, so the anchor still holds at the zoom limits.
     */
    fun zoomedAt(centroid: Offset, pan: Offset, zoom: Float, size: IntSize, bounds: MapBounds): Camera {
        val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = if (scale == 0f) 1f else next / scale
        val rel = Offset(centroid.x - size.width / 2f, centroid.y - size.height / 2f)
        return Camera(next, rel * (1f - ratio) + offset * ratio + pan).clampedTo(size, bounds)
    }

    /**
     * Keeps a readable amount of map on screen.
     *
     * Without this the map can be flung into empty space — trivially easy at
     * 14× on something 355 nodes wide — and the only way back is the recentre
     * button, which you have to know is there. The limit lets the map's edge
     * come inward as far as a quarter of the viewport and no further.
     */
    fun clampedTo(size: IntSize, bounds: MapBounds): Camera {
        if (size.width == 0 || size.height == 0) return this
        val k = fitFor(size.width.toFloat(), size.height.toFloat(), bounds) * scale
        val margin = minOf(size.width, size.height) * 0.25f
        val maxX = (bounds.spanX * k / 2f + size.width / 2f - margin).coerceAtLeast(0f)
        val maxY = (bounds.spanY * k / 2f + size.height / 2f - margin).coerceAtLeast(0f)
        return copy(offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY)))
    }
}

/**
 * A camera move that curves.
 *
 * Two things make it read as a move rather than a jump. The timing is a
 * symmetric ease — slow to leave, fast across the middle, slow to arrive — and
 * the path bows sideways, peaking at half-flight, so the camera arcs into
 * position instead of sliding down a ruled line. The bow is proportional to the
 * distance travelled and capped, so a nudge to a neighbouring genre stays
 * nearly straight while a jump across the map genuinely swings.
 *
 * Scale is interpolated on the same curve, which is what keeps the destination
 * growing smoothly under you rather than snapping to size on arrival.
 */
private suspend fun flyTo(
    node: GenreNode,
    canvas: IntSize,
    panelHeightPx: Int,
    bounds: MapBounds,
    from: Camera,
    instant: Boolean,
    onFrame: (Camera) -> Unit,
) {
    val targetScale = maxOf(from.scale, FOCUS_SCALE)
    val target = Camera(targetScale, centreOffsetFor(node, canvas, panelHeightPx, bounds, targetScale))
    if (instant) {
        onFrame(target)
        return
    }

    val delta = target.offset - from.offset
    val distance = hypot(delta.x, delta.y)
    // Perpendicular to the direction of travel, so the bow is always across the
    // path rather than along it.
    val bow = if (distance < 1f) Offset.Zero else {
        Offset(-delta.y / distance, delta.x / distance) * (distance * 0.16f).coerceAtMost(220f)
    }

    animate(0f, 1f, animationSpec = tween(FLIGHT_MILLIS, easing = FlightEasing)) { t, _ ->
        onFrame(
            Camera(
                scale = from.scale + (target.scale - from.scale) * t,
                offset = from.offset + delta * t + bow * sin(t * PI.toFloat()),
            ),
        )
    }
}

private val FlightEasing = CubicBezierEasing(0.62f, 0f, 0.28f, 1f)

/**
 * Where the camera has to sit for [node] to land in the middle of the map you
 * can actually see — which is the space above the detail panel, not the middle
 * of the canvas. Centring on the canvas would park every genre you select
 * underneath its own information.
 */
private fun centreOffsetFor(
    node: GenreNode,
    canvas: IntSize,
    panelHeightPx: Int,
    bounds: MapBounds,
    scale: Float,
): Offset {
    val k = fitFor(canvas.width.toFloat(), canvas.height.toFloat(), bounds) * scale
    val focusY = (canvas.height - panelHeightPx) / 2f
    return Offset(
        x = -(node.x - bounds.centreX) * k,
        y = focusY - canvas.height / 2f - (node.y - bounds.centreY) * k,
    )
}

/**
 * The extent of the baked layout, in layout units.
 *
 * Taken from the *whole* graph rather than from the visible nodes: measuring
 * what's on screen would make the map silently rescale every time a branch is
 * folded, so collapsing one cluster would shift every other one under your
 * finger.
 */
private data class MapBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val spanX: Float get() = (maxX - minX).coerceAtLeast(1f)
    val spanY: Float get() = (maxY - minY).coerceAtLeast(1f)
    val centreX: Float get() = (minX + maxX) / 2f
    val centreY: Float get() = (minY + maxY) / 2f
}

private fun boundsOf(nodes: List<GenreNode>): MapBounds {
    if (nodes.isEmpty()) return MapBounds(0f, 0f, 1f, 1f)
    return MapBounds(
        minX = nodes.minOf { it.x },
        minY = nodes.minOf { it.y },
        maxX = nodes.maxOf { it.x },
        maxY = nodes.maxOf { it.y },
    )
}

/** Nodes to draw: everything not sitting under a collapsed branch. */
private fun visibleNodes(
    graph: tf.monochrome.android.domain.model.GenreGraph,
    collapsed: Set<String>,
): List<GenreNode> {
    if (collapsed.isEmpty()) return graph.allGenres
    val hidden = descendantsOf(graph, collapsed)
    return graph.allGenres.filterNot { it.id in hidden }
}

private fun descendantsOf(
    graph: tf.monochrome.android.domain.model.GenreGraph,
    id: String,
): Set<String> = descendantsOf(graph, setOf(id))

private fun descendantsOf(
    graph: tf.monochrome.android.domain.model.GenreGraph,
    roots: Set<String>,
): Set<String> {
    val found = HashSet<String>()
    var frontier = roots.toList()
    while (frontier.isNotEmpty()) {
        val next = ArrayList<String>()
        for (id in frontier) {
            for (child in graph.children(id)) {
                if (found.add(child.id)) next.add(child.id)
            }
        }
        frontier = next
    }
    return found
}

/**
 * A branch being unfolded or folded back, and how far through it is.
 *
 * The whole subtree scales out of the branch's own position, so unfolding reads
 * as the genres growing out of the genre they came from rather than as a set of
 * dots appearing. Folding runs the same thing backwards and slightly faster —
 * putting something away should not take as long as opening it.
 */
private data class Fold(
    /**
     * Which fold this is. Cancelling a coroutine only takes effect at its next
     * suspension point, so without a token to check, the frame callback of a
     * fold that has just been superseded can stamp its own progress onto the
     * new one — and then commit the wrong branch to `collapsed` on the way out.
     */
    val epoch: Int,
    val rootId: String,
    val subtree: Set<String>,
    val collapsing: Boolean,
    val progress: Float,
)

private const val FOLD_OUT_MILLIS = 380
private const val FOLD_IN_MILLIS = 240
private val FoldOutEasing = CubicBezierEasing(0.2f, 1.5f, 0.4f, 1f)
private val FoldInEasing = CubicBezierEasing(0.5f, 0f, 0.9f, 0.4f)

/**
 * Layout units to pixels at scale 1 — the whole map fitted to the viewport.
 *
 * One factor for both axes, so the clusters stay circular rather than being
 * squashed into ellipses in portrait.
 */
/**
 * Scale that puts the layout on screen.
 *
 * The radial map is roughly square and fits whole. The timeline is not — 268
 * rows against a single span of years makes it about three times taller than it
 * is wide, and fitting *that* whole shrinks every row until nothing is readable,
 * which is what the first build of it did. Past the halfway point of the morph
 * it fits to width instead and lets the height run off the bottom, the way any
 * long document opens: legible at the top, and scrollable.
 */
private fun fitFor(width: Float, height: Float, bounds: MapBounds, morph: Float = 0f): Float {
    val whole = minOf(width / bounds.spanX, height / bounds.spanY)
    val toWidth = width / bounds.spanX
    return lerp(whole, toWidth, morph.coerceIn(0f, 1f)) * FIT_MARGIN
}

/**
 * What a dot's size means.
 *
 * Size was previously depth in the parent forest, which is an artefact of how
 * the tree was authored rather than a fact about the music: techno and Xtra Raw
 * are both one hop below their parent, and drawing them the same size says the
 * scene and the footnote are equally significant. Making the encoding
 * switchable — and naming the active one on screen — is what turns a dot's size
 * from decoration into a claim you can check.
 */
private enum class MapWeight(val caption: String) {
    POPULARITY("Sized by listeners on Last.fm"),
    ERA("Sized by how recent the genre is"),
    DEPTH("Sized by depth in the genre tree"),
    ;

    fun next(): MapWeight = entries[(ordinal + 1) % entries.size]
}

/**
 * The ranges the weightings normalise against, measured once from the graph.
 *
 * Reach spans four orders of magnitude — techno has 72,677 taggers, Xtra Raw
 * has 12 — so it is normalised on a log, without which every genre below the
 * few giants would round to the same smallest dot.
 */
private class WeightScale(graph: GenreGraph) {
    private val maxLogReach = graph.allGenres
        .mapNotNull { it.reach }
        .maxOrNull()
        ?.let { ln(1f + it) }
        ?.takeIf { it > 0f } ?: 1f
    private val earliest = graph.allGenres.mapNotNull { it.era.getOrNull(0) }.minOrNull() ?: 1900
    private val latest = graph.allGenres.mapNotNull { it.era.getOrNull(0) }.maxOrNull() ?: 2025

    /** 0 for the least prominent genre under this weighting, 1 for the most. */
    fun prominence(node: GenreNode, weight: MapWeight): Float = when (weight) {
        // Depth keeps the exact steps the map has always drawn, so switching
        // back to it is a return rather than a different-looking map.
        MapWeight.DEPTH -> depthProminence(node)
        MapWeight.POPULARITY -> node.reach
            ?.let { (ln(1f + it) / maxLogReach).coerceIn(0f, 1f) }
            ?: depthProminence(node)
        MapWeight.ERA -> node.era.getOrNull(0)
            ?.let { ((it - earliest).toFloat() / (latest - earliest).coerceAtLeast(1)).coerceIn(0f, 1f) }
            ?: depthProminence(node)
    }

    private fun depthProminence(node: GenreNode): Float = when (node.ring) {
        0 -> 1f
        1 -> 0.47f
        2 -> 0.2f
        else -> 0f
    }
}


// ─── Timeline layout ─────────────────────────────────────────────────────────

/** Which arrangement the map is in. */
private enum class MapLayout { RADIAL, TIMELINE }

private class Timeline(
    val positions: Map<String, Offset>,
    val bounds: MapBounds,
)

private const val TIMELINE_WIDTH = 3600f
private const val TIMELINE_ROW = 40f

/** Minimum horizontal clearance before two genres may share a row. */
private const val TIMELINE_X_GAP = 30f

/**
 * Layout units per character, used to reserve room for a genre's name.
 *
 * The timeline is meant to show all 771 names at once, which a dot-sized gap
 * cannot deliver: "Uptempo Frenchcore" needs an order of magnitude more room
 * than its dot does, so packing on dot width alone produces a compact layout
 * whose labels then have to be thrown away to stay readable. Reserving the
 * text's own width instead makes the layout exactly as tall as naming
 * everything requires — taller, and complete.
 */
private const val TIMELINE_CHAR_WIDTH = 13.5f

private const val TIMELINE_MIN_YEAR = 400
private const val TIMELINE_MAX_YEAR = 2025
private const val TIMELINE_PIVOT_YEAR = 1900

/**
 * Share of the width given to everything before 1900.
 *
 * The dataset is 100 genres spread over the fifteen centuries to 1899 and 671
 * packed into the 123 years since. On a straight linear axis those 671 would
 * occupy eight percent of the map and be unreadable, so the pre-modern era is
 * compressed into a fixed band. That is a distortion, which is why the axis
 * draws the break as a labelled boundary rather than pretending to be uniform —
 * a compressed scale you can see is a scale; a hidden one is a lie.
 */
private const val TIMELINE_PRE_SPAN = 0.18f

/** Screen-space strip at the top reserved for the year axis. */
private const val TIMELINE_AXIS_INSET = 46f

/**
 * Tick spacings the axis will choose between, coarsest first.
 *
 * Ends at 1 so that zooming into a single scene resolves to individual years —
 * at that magnification "1990" spanning a third of the screen is no longer
 * telling you anything the position hadn't already.
 */
private val TIMELINE_TICK_STEPS = intArrayOf(100, 50, 25, 10, 5, 2, 1)

/** Room a labelled tick needs before the axis will subdivide further. */
private const val TIMELINE_TICK_MIN_PX = 74f

/** Minor rules may sit closer, since they carry no text. */
private const val TIMELINE_MINOR_MIN_PX = 16f

private fun timelineX(year: Int): Float {
    val y = year.coerceIn(TIMELINE_MIN_YEAR, TIMELINE_MAX_YEAR)
    val fraction = if (y < TIMELINE_PIVOT_YEAR) {
        TIMELINE_PRE_SPAN * (y - TIMELINE_MIN_YEAR) /
            (TIMELINE_PIVOT_YEAR - TIMELINE_MIN_YEAR).toFloat()
    } else {
        TIMELINE_PRE_SPAN + (1f - TIMELINE_PRE_SPAN) * (y - TIMELINE_PIVOT_YEAR) /
            (TIMELINE_MAX_YEAR - TIMELINE_PIVOT_YEAR).toFloat()
    }
    return fraction * TIMELINE_WIDTH
}

private fun startYear(node: GenreNode): Int = node.era.getOrNull(0) ?: TIMELINE_MAX_YEAR

/**
 * The whole graph as one genealogy: time along x, descent down y.
 *
 * Every genre sits at the year it appeared, so the picture reads left to right
 * as history. One tree, not twelve — the families are not separated into bands,
 * because the interesting thing about this dataset is where they cross. Jazz
 * house descends from both jazz and house; nu metal from both metal and hip-hop.
 * Banding by family puts those two parents on opposite sides of the page and
 * draws a long wire between them; letting the whole forest share one set of rows
 * puts a child directly under whichever parent it was reached from, and the
 * colour change along a branch is then visible as the crossing it is.
 *
 * Genres are walked depth first from the oldest roots, and each is placed on the
 * first row **at or below its parent's** that has room at that year, so descent
 * always reads downward. Rows are shared whenever the years don't collide, so
 * the picture is as tall as the music's actual overlap in time requires rather
 * than one row per genre.
 */
private fun timelineFor(graph: GenreGraph): Timeline {
    val children = HashMap<String, MutableList<GenreNode>>()
    val roots = ArrayList<GenreNode>()
    for (node in graph.allGenres) {
        val parent = node.parents.firstOrNull { graph[it] != null }
        if (parent == null) roots.add(node) else {
            children.getOrPut(parent) { ArrayList() }.add(node)
        }
    }

    val seen = HashSet<String>()
    val order = ArrayList<GenreNode>()
    fun visit(node: GenreNode, guard: Int) {
        if (guard > 40 || !seen.add(node.id)) return
        order.add(node)
        children[node.id]?.sortedBy { startYear(it) }?.forEach { visit(it, guard + 1) }
    }
    roots.sortedBy { startYear(it) }.forEach { visit(it, 0) }
    // Anything a cycle or a missing parent kept out of the walk still has to be
    // placed; dropping a genre off the map would be the worst of the options.
    graph.allGenres.filterNot { it.id in seen }.sortedBy { startYear(it) }.forEach { visit(it, 0) }

    val positions = HashMap<String, Offset>()
    val rowOf = HashMap<String, Int>()
    val rowLastX = ArrayList<Float>()
    for (node in order) {
        val x = timelineX(startYear(node))
        val room = maxOf(TIMELINE_X_GAP, node.name.length * TIMELINE_CHAR_WIDTH)
        // Never above the parent: descent has to read downward for the shape to
        // mean anything.
        var row = node.parents.firstNotNullOfOrNull { rowOf[it] } ?: 0
        while (row < rowLastX.size && rowLastX[row] > x - room) row++
        while (rowLastX.size <= row) rowLastX.add(Float.NEGATIVE_INFINITY)
        // Store the right edge of this genre's *label*, not of its dot, so the
        // next genre on this row clears the text as well as the circle.
        rowLastX[row] = x + room
        rowOf[node.id] = row
        positions[node.id] = Offset(x, row * TIMELINE_ROW)
    }

    val bounds = if (positions.isEmpty()) MapBounds(0f, 0f, 1f, 1f) else MapBounds(
        minX = positions.values.minOf { it.x },
        minY = positions.values.minOf { it.y },
        maxX = positions.values.maxOf { it.x },
        maxY = positions.values.maxOf { it.y },
    )
    return Timeline(positions, bounds)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpBounds(a: MapBounds, b: MapBounds, t: Float) = MapBounds(
    minX = lerp(a.minX, b.minX, t),
    minY = lerp(a.minY, b.minY, t),
    maxX = lerp(a.maxX, b.maxX, t),
    maxY = lerp(a.maxY, b.maxY, t),
)

/**
 * How far a node bows off the straight line on its way between layouts.
 *
 * Everything travelling in straight lines at once reads as a diagram being
 * redrawn; the same nodes on arcs read as a thing rearranging itself. The sign
 * alternates on the id so the field fans out rather than sweeping one way.
 */
private const val MORPH_ARC = 0.22f

/** Where a node is right now, [morph] of the way from the radial map to the timeline. */
private fun morphedPoint(node: GenreNode, timeline: Map<String, Offset>?, morph: Float): Offset {
    val from = Offset(node.x, node.y)
    val to = timeline?.get(node.id) ?: return from
    if (morph <= 0.001f) return from
    if (morph >= 0.999f) return to

    val delta = to - from
    val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
    val sign = if (node.id.hashCode() and 1 == 0) 1f else -1f
    val control = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f) +
        Offset(-delta.y / length, delta.x / length) * (length * MORPH_ARC * sign)

    val inv = 1f - morph
    return from * (inv * inv) + control * (2f * inv * morph) + to * (morph * morph)
}

/**
 * One depth level of one family, as the shape its genres actually make.
 *
 * [points] are the members in layout units, ordered by bearing from the family
 * root, so stroking through them traces the real outline of that depth rather
 * than a circle fitted to it.
 */
private data class Constellation(
    val familyId: String,
    val depth: Int,
    val points: List<Offset>,
)

/**
 * The map's depth structure, drawn from the data rather than idealised.
 *
 * This started as concentric circles at each depth's median radius and that was
 * wrong, because the depths are not circles. Measured from a family's root the
 * medians do order cleanly — rock at 83 → 124 → 157 → 214 layout units — but
 * the spread around them is enormous: electronic's first depth runs from 102 to
 * 488, because it holds the sub-family roots (house, techno, drum & bass) that
 * are spread wide on purpose to seed clusters of their own. A circle through
 * the median of that would pass through almost none of the genres it claimed to
 * describe.
 *
 * So the outline follows the members themselves, sorted by bearing and closed
 * into a loop. It comes out lopsided and lumpy — bulging where a family has
 * grown a dense branch, pinched where it hasn't — which is the point: the shape
 * is evidence about how the music is distributed, and smoothing it into a
 * circle would throw exactly that away.
 *
 * Needs three points to enclose anything; depths with fewer are skipped.
 */
private fun constellationsFor(graph: GenreGraph): List<Constellation> =
    graph.allGenres.groupBy { it.family }.flatMap { (family, nodes) ->
        val root = nodes.firstOrNull { it.ring == 0 } ?: return@flatMap emptyList()
        nodes.asSequence()
            .filter { it.ring > 0 }
            .groupBy { it.ring }
            .filterValues { it.size >= 3 }
            .map { (depth, members) ->
                Constellation(
                    familyId = family,
                    depth = depth,
                    points = members
                        .sortedBy { atan2(it.y - root.y, it.x - root.x) }
                        .map { Offset(it.x, it.y) },
                )
            }
    }

/**
 * Trace each depth's outline under the map, in its family's own colour.
 *
 * Smoothed through the midpoints between neighbours rather than drawn as
 * straight segments: a polygon through several hundred points reads as noise,
 * while the same points as one continuous curve read as a shape. The curve
 * still passes near every member, so the lumpiness survives the smoothing.
 */
private fun DrawScope.drawConstellations(
    constellations: List<Constellation>,
    bounds: MapBounds,
    camera: Camera,
    familyColors: Map<String, Color>,
    fade: Float = 1f,
    morphFit: Float = 0f,
) {
    val k = fitFor(size.width, size.height, bounds, morphFit) * camera.scale
    val cx = size.width / 2f + camera.offset.x
    val cy = size.height / 2f + camera.offset.y

    for (constellation in constellations) {
        val colour = familyColors[constellation.familyId] ?: continue
        val screen = constellation.points.map { point ->
            Offset(
                x = cx + (point.x - bounds.centreX) * k,
                y = cy + (point.y - bounds.centreY) * k,
            )
        }
        if (screen.size < 3) continue

        val path = Path()
        fun midpoint(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        var previous = screen.last()
        path.moveTo(midpoint(previous, screen.first()).x, midpoint(previous, screen.first()).y)
        for (point in screen) {
            val mid = midpoint(previous, point)
            // Each member is the control point, so the curve bends toward every
            // genre without being forced to pass exactly through it.
            path.quadraticBezierTo(previous.x, previous.y, mid.x, mid.y)
            previous = point
        }
        path.close()

        drawPath(
            path = path,
            color = colour,
            alpha = (0.30f - (constellation.depth - 1) * 0.045f).coerceAtLeast(0.07f) * fade,
            style = Stroke(width = 1.4f),
        )
    }
}


/**
 * The timeline's axis and lanes.
 *
 * Decade rules so a position on the page converts back into a year, and an
 * explicit marker at 1900 where the scale changes. The pre-modern era is compressed roughly
 * fifteen-to-one against the modern one; drawing that boundary is the
 * difference between a compressed axis and a dishonest one.
 */
private fun DrawScope.drawTimelineChrome(
    timeline: Timeline,
    bounds: MapBounds,
    camera: Camera,
    labelColor: Color,
    labelSizePx: Float,
    fade: Float,
    morphFit: Float,
) {
    val k = fitFor(size.width, size.height, bounds, morphFit) * camera.scale
    val cx = size.width / 2f + camera.offset.x
    val cy = size.height / 2f + camera.offset.y
    fun sx(x: Float) = cx + (x - bounds.centreX) * k
    fun sy(y: Float) = cy + (y - bounds.centreY) * k

    val top = sy(timeline.bounds.minY - TIMELINE_ROW)
    val bottom = sy(timeline.bounds.maxY + TIMELINE_ROW)

    // The year axis is pinned to the screen rather than drawn in world space.
    // In world space it scrolls away the moment you pan down — an axis you have
    // to scroll back to is no longer telling you where you are — and it slides
    // under the first lane's genre labels on the way. A reserved strip is both
    // always visible and never in anything's way; [TIMELINE_AXIS_INSET] keeps
    // the genre labels out of it.
    val axisBaseline = labelSizePx + 10f
    drawRect(
        color = Color.Black,
        topLeft = Offset(0f, 0f),
        size = androidx.compose.ui.geometry.Size(size.width, TIMELINE_AXIS_INSET),
        alpha = 0.55f * fade,
    )

    // The rules follow the zoom: centuries when the whole span is on screen,
    // single years once there is room for them. A fixed decade grid is wrong at
    // both ends — unreadable stripes zoomed out, and zoomed in on one scene it
    // stops resolving exactly where the year is the thing you came to read.
    val pixelsPerYear = (TIMELINE_WIDTH * (1f - TIMELINE_PRE_SPAN) /
        (TIMELINE_MAX_YEAR - TIMELINE_PIVOT_YEAR)) * k
    val step = TIMELINE_TICK_STEPS.firstOrNull { it * pixelsPerYear >= TIMELINE_TICK_MIN_PX } ?: 1
    // Minor rules subdivide the labelled ones, but only while they stay apart.
    val minorStep = TIMELINE_TICK_STEPS.firstOrNull {
        it < step && it * pixelsPerYear >= TIMELINE_MINOR_MIN_PX
    }

    fun rule(year: Int, alpha: Float) {
        val x = sx(timelineX(year))
        if (x <= -60f || x >= size.width + 60f) return
        drawLine(
            color = labelColor,
            start = Offset(x, maxOf(top, TIMELINE_AXIS_INSET)),
            end = Offset(x, bottom),
            strokeWidth = 1f,
            alpha = alpha * fade,
        )
    }

    if (minorStep != null) {
        var year = TIMELINE_PIVOT_YEAR
        while (year <= TIMELINE_MAX_YEAR) {
            if (year % step != 0) rule(year, 0.035f)
            year += minorStep
        }
    }
    var year = TIMELINE_PIVOT_YEAR
    while (year <= TIMELINE_MAX_YEAR) {
        rule(year, 0.09f)
        year += step
    }

    // The scale break, drawn rather than hidden.
    val pivotX = sx(timelineX(TIMELINE_PIVOT_YEAR))
    drawLine(
        color = labelColor,
        start = Offset(pivotX, maxOf(top, TIMELINE_AXIS_INSET)),
        end = Offset(pivotX, bottom),
        strokeWidth = 1.6f,
        alpha = 0.22f * fade,
    )

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = labelSizePx
            isAntiAlias = true
            alpha = (150 * fade).toInt()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        var tick = TIMELINE_PIVOT_YEAR
        while (tick <= TIMELINE_MAX_YEAR) {
            val x = sx(timelineX(tick))
            if (x > 20f && x < size.width - 20f) {
                drawText(tick.toString(), x, axisBaseline, paint)
            }
            tick += step
        }
        // The pre-1900 caption sits left of the scale break, but the break also
        // carries its own "1900" tick centred on it, and the two were printed
        // straight through each other. Only draw it when it clears that label
        // and still starts on screen.
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        val pivotLabelHalf = paint.measureText(TIMELINE_PIVOT_YEAR.toString()) / 2f
        val preLabel = "before 1900"
        val preRight = sx(timelineX(TIMELINE_PIVOT_YEAR)) - pivotLabelHalf - 14f
        if (preRight - paint.measureText(preLabel) > 12f) {
            drawText(preLabel, preRight, axisBaseline, paint)
        }

    }
}

private fun positionsFor(
    nodes: List<GenreNode>,
    width: Float,
    height: Float,
    bounds: MapBounds,
    camera: Camera,
    fold: Fold? = null,
    timeline: Map<String, Offset>? = null,
    morph: Float = 0f,
): Map<String, Offset> {
    val k = fitFor(width, height, bounds, morph) * camera.scale
    val cx = width / 2f + camera.offset.x
    val cy = height / 2f + camera.offset.y
    fun place(node: GenreNode): Offset {
        val point = morphedPoint(node, timeline, morph)
        return Offset(
            x = cx + (point.x - bounds.centreX) * k,
            y = cy + (point.y - bounds.centreY) * k,
        )
    }
    // A folding subtree is drawn part of the way home to its branch root, so
    // hit-testing lands where the dots actually are mid-animation too.
    val anchor = fold?.let { f -> nodes.firstOrNull { it.id == f.rootId }?.let(::place) }
    return nodes.associate { node ->
        val p = place(node)
        node.id to if (anchor != null && node.id in fold!!.subtree) {
            anchor + (p - anchor) * fold.progress
        } else {
            p
        }
    }
}

private fun hitTest(
    point: Offset,
    nodes: List<GenreNode>,
    width: Int,
    height: Int,
    bounds: MapBounds,
    camera: Camera,
    fold: Fold? = null,
    timeline: Map<String, Offset>? = null,
    morph: Float = 0f,
): GenreNode? {
    val positions = positionsFor(
        nodes, width.toFloat(), height.toFloat(), bounds, camera, fold, timeline, morph,
    )
    // Generous radius: these are small targets on a zoomable canvas, and
    // missing by four pixels should still select the thing you aimed at.
    val touchRadius = 34f
    return nodes
        .mapNotNull { node ->
            val p = positions[node.id] ?: return@mapNotNull null
            val d = hypot(p.x - point.x, p.y - point.y)
            if (d <= touchRadius) node to d else null
        }
        .minByOrNull { it.second }
        ?.first
}

private fun DrawScope.drawMap(
    nodes: List<GenreNode>,
    positions: Map<String, Offset>,
    collapsed: Set<String>,
    selectedId: String?,
    selectedScale: Float,
    fold: Fold?,
    familyColors: Map<String, Color>,
    edgeColor: Color,
    labelColor: Color,
    labelSizePx: Float,
    scale: Float,
    weighting: MapWeight,
    weights: WeightScale,
    morph: Float = 0f,
    topInset: Float = 0f,
    bottomInset: Float = 0f,
) {
    val byId = nodes.associateBy { it.id }

    // How present a node is right now: 1 for everything settled, and the fold's
    // progress for the subtree currently growing out of — or shrinking back
    // into — its branch. Clamped because the grow easing overshoots past 1 on
    // purpose, and an alpha above 1 is not a brighter dot, it's an exception.
    fun presence(id: String): Float =
        if (fold != null && id in fold.subtree) fold.progress.coerceIn(0f, 1f) else 1f

    // Dots grow a little with the zoom, within limits. Fixed-pixel dots have to
    // be sized for one of the two views and are wrong in the other: big enough
    // to hit comfortably when you're in among them turns the whole-map view
    // into a solid blob, and small enough for the overview leaves nothing to
    // aim at up close. Clamped at both ends so neither view runs away.
    val dotScale = (0.62f + 0.38f * scale).coerceIn(0.72f, 1.7f)

    // Everything below is culled to the viewport. Zoomed in, most of a
    // 355-node map is off-screen, and drawing it anyway costs a full pass of
    // circles and text per frame for pixels nobody can see. The margin keeps
    // edges whose far end is just outside from popping.
    val margin = 120f
    fun onScreen(p: Offset) =
        p.x > -margin && p.x < size.width + margin && p.y > -margin && p.y < size.height + margin

    // Edges first, so nodes sit on top of their own lines.
    //
    // Cross-family sideways links are drawn too, and only those: a genre's
    // neighbours inside its own cluster are already obvious from the fact that
    // they're adjacent, whereas the link from jazz house back to jazz is the
    // thing the clustered layout exists to show. Drawing all ~900 near-edges
    // instead would be a grey haze over the whole map.
    for (node in nodes) {
        val to = positions[node.id] ?: continue
        val here = presence(node.id)
        if (here <= 0.01f) continue
        for (parentId in node.parents) {
            val from = positions[parentId] ?: continue
            if (parentId !in byId) continue
            if (!onScreen(to) && !onScreen(from)) continue
            if (morph <= 0.01f) {
                drawLine(
                    color = edgeColor.copy(alpha = edgeColor.alpha * here),
                    start = from,
                    end = to,
                    strokeWidth = 1.5f,
                )
            } else {
                // On the timeline a parent link is a branch, and a branch that
                // leaves horizontally and arrives horizontally reads as descent
                // along the time axis. A straight diagonal reads as a wire.
                val reach = (to.x - from.x).coerceAtLeast(18f) * 0.5f * morph
                drawPath(
                    path = Path().apply {
                        moveTo(from.x, from.y)
                        cubicTo(from.x + reach, from.y, to.x - reach, to.y, to.x, to.y)
                    },
                    color = edgeColor.copy(alpha = edgeColor.alpha * here),
                    style = Stroke(width = 1.5f),
                )
            }
        }
        for ((otherId, weight) in node.nearEdges()) {
            val other = byId[otherId] ?: continue
            if (other.family == node.family) continue
            // Each pair once — otherwise every bridge is drawn twice, at double
            // the intended opacity.
            if (otherId < node.id) continue
            val from = positions[otherId] ?: continue
            if (!onScreen(to) && !onScreen(from)) continue
            val alpha = (0.10f + 0.18f * weight) * here * presence(otherId)
            drawLine(
                color = (familyColors[node.family] ?: edgeColor).copy(alpha = alpha),
                start = from,
                end = to,
                strokeWidth = 1f,
            )
        }
    }

    for (node in nodes) {
        val centre = positions[node.id] ?: continue
        if (!onScreen(centre)) continue
        val here = presence(node.id)
        if (here <= 0.01f) continue
        val color = (familyColors[node.family] ?: labelColor).copy(alpha = here)
        // Roots read as anchors, so they stay larger at every zoom level. A dot
        // growing in scales with the fold, so the subtree swells into place
        // rather than sliding out at full size, and the selected one carries
        // the spring that fired when it was tapped.
        val selected = node.id == selectedId
        val prominence = weights.prominence(node, weighting)
        // 6.5..14 px, the range depth sizing has always used, so no weighting
        // can produce a dot too small to hit or big enough to swamp its cluster.
        val radius = (6.5f + 7.5f * prominence) *
            dotScale * here * if (selected) selectedScale else 1f
        // Mid-fold the branch root's ring would flicker on for one frame at the
        // end, so it waits until the subtree is actually gone.
        val folded = node.id in collapsed

        // A soft halo under the bigger dots keeps them from disappearing into
        // the edges crossing behind them.
        drawCircle(color = color.copy(alpha = color.alpha * 0.16f), radius = radius * 1.6f, center = centre)
        drawCircle(color = color, radius = radius, center = centre)
        if (folded) {
            // A ring around a folded branch: the one piece of state on the map
            // that isn't visible from its children, so it has to be marked.
            drawCircle(
                color = color,
                radius = radius + 6f,
                center = centre,
                style = Stroke(width = 2.5f),
            )
        }
        if (selected) {
            drawCircle(
                color = labelColor,
                radius = radius + 10f,
                center = centre,
                style = Stroke(width = 3f),
            )
        }
    }

    // Labels last and only when they'd be readable. Drawing 355 of them at
    // every zoom level is both illegible and the single most expensive thing
    // on this canvas.
    // Scale 1 fits all twelve clusters on screen at once, so only the family
    // roots can be named there; the thresholds below track how much room a
    // label actually has as you zoom in.
    val labelThreshold = when {
        scale >= 5f -> 99
        scale >= 2.8f -> 2
        scale >= 1.5f -> 1
        else -> 0
    }
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color = labelColor.toArgb()
            textSize = labelSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val baseAlpha = paint.alpha
        // Which names survive has to follow the same weighting as the dots —
        // labelling by depth while sizing by popularity would name a genre
        // nobody tags and leave the biggest dot on screen anonymous.
        //
        // The threshold only decides which labels are *offered*; collision
        // decides which are drawn. A threshold alone cannot do this job at 771
        // nodes, and tuning it was a choice between two failures: strict enough
        // to keep the zoomed-in view legible left the whole-map view with three
        // names on it, and loose enough to orient by named a hundred genres
        // stacked into an unreadable smear. Offering generously and dropping
        // whatever doesn't fit gives a view that stays legible at every zoom
        // and fills the space it has.
        val labelCut = when {
            // The timeline reserves each name's own width when it packs rows,
            // so every genre has somewhere to put its label and all 771 are
            // offered. Collision still arbitrates when zoomed far enough out
            // that the text no longer fits the space the layout gave it.
            morph > 0.5f -> 0f
            scale >= 5f -> 0f
            scale >= 2.8f -> 0.20f
            scale >= 1.5f -> 0.40f
            else -> 0.55f
        }
        val taken = ArrayList<android.graphics.RectF>()
        val candidates = nodes.asSequence()
            .filter { node ->
                if (weighting == MapWeight.DEPTH) node.ring <= labelThreshold
                else weights.prominence(node, weighting) >= labelCut
            }
            // Most prominent first, so when two names collide the one that
            // survives is the one the weighting says matters more.
            .sortedByDescending { weights.prominence(it, weighting) }

        for (node in candidates) {
            val centre = positions[node.id] ?: continue
            if (!onScreen(centre)) continue
            val here = presence(node.id)
            if (here <= 0.01f) continue

            val halfWidth = paint.measureText(node.name) / 2f
            val baseline = centre.y - 14f
            val box = android.graphics.RectF(
                centre.x - halfWidth - LABEL_GAP,
                baseline - labelSizePx - LABEL_GAP,
                centre.x + halfWidth + LABEL_GAP,
                baseline + LABEL_GAP,
            )
            // Off the side of the canvas is its own kind of collision: a name
            // sliced in half by the edge is worse than no name.
            if (box.left < 0f || box.right > size.width) continue
            // Reserved strips: the app bar above, and the genre panel below
            // when one is open. A label drawn into either is either sitting on
            // other text or hidden behind a sheet — both are wasted ink, and
            // the first is unreadable.
            if (box.top < topInset) continue
            if (bottomInset > 0f && box.bottom > size.height - bottomInset) continue
            if (taken.any { android.graphics.RectF.intersects(it, box) }) continue

            taken.add(box)
            paint.alpha = (baseAlpha * here).toInt()
            drawText(node.name, centre.x, baseline, paint)
        }
    }
}

/**
 * A stable colour per family.
 *
 * Hand-picked rather than generated: twelve evenly-spaced hues collide badly at
 * small sizes, and these are chosen to stay distinguishable as 4-pixel dots on
 * both light and dark backgrounds.
 */
private fun familyPalette(families: List<String>): Map<String, Color> {
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
