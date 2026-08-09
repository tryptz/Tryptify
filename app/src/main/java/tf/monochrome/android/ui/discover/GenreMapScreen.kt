package tf.monochrome.android.ui.discover

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
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
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * The panel's three actions: *Shuffle* drops a shuffled page of that genre into
 * the real player and queue — the same path Flow uses, because "quickly listen to
 * each genre" is the whole point, and shuffled because the same genre tapped
 * twice returning the same song in the same order reads as the map being broken.
 * *Radio* opens a few of those tracks and then hands over to the station planner,
 * which keeps going. *Explore in Discover* hands the genre to the feed, which
 * rebuilds around it and its graph neighbours.
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
    val bounds = remember(graph) { boundsOf(graph.allGenres) }

    val familyColors = remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

    // Read at gesture time rather than captured, so the pointer handlers can be
    // keyed on Unit. Keying them on the camera would rebuild both gesture
    // detectors on every frame of a pan, a pinch or a camera flight — which
    // drops the pointer stream mid-gesture.
    val liveCamera = rememberUpdatedState(camera)
    val liveVisible = rememberUpdatedState(visible)
    val liveFold = rememberUpdatedState(fold)

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Genre map") },
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
                IconButton(onClick = { flight?.cancel(); camera = Camera() }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recentre")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                            camera = liveCamera.value.zoomedAt(centroid, pan, zoom, size, bounds)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { point ->
                            val hit = hitTest(
                                point, liveVisible.value, size.width, size.height,
                                bounds, liveCamera.value, liveFold.value,
                            ) ?: return@detectTapGestures
                            viewModel.selectOnMap(hit.id)
                            toggleBranch(hit.id)
                        }
                    },
            ) {
                drawMap(
                    nodes = visible,
                    positions = positionsFor(visible, size.width, size.height, bounds, camera, fold),
                    collapsed = collapsed,
                    selectedId = selected?.id,
                    selectedScale = selectPop.value,
                    fold = fold,
                    familyColors = familyColors,
                    edgeColor = edgeColor,
                    labelColor = onSurface,
                    labelSizePx = labelPx,
                    scale = camera.scale,
                )
            }

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
                drawRect(color = tint)
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
                    icon = Icons.Default.Shuffle,
                    label = "Shuffle",
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
private fun fitFor(width: Float, height: Float, bounds: MapBounds): Float =
    minOf(width / bounds.spanX, height / bounds.spanY) * FIT_MARGIN

private fun positionsFor(
    nodes: List<GenreNode>,
    width: Float,
    height: Float,
    bounds: MapBounds,
    camera: Camera,
    fold: Fold? = null,
): Map<String, Offset> {
    val k = fitFor(width, height, bounds) * camera.scale
    val cx = width / 2f + camera.offset.x
    val cy = height / 2f + camera.offset.y
    fun place(node: GenreNode) = Offset(
        x = cx + (node.x - bounds.centreX) * k,
        y = cy + (node.y - bounds.centreY) * k,
    )
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
): GenreNode? {
    val positions = positionsFor(nodes, width.toFloat(), height.toFloat(), bounds, camera, fold)
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
            drawLine(
                color = edgeColor.copy(alpha = edgeColor.alpha * here),
                start = from,
                end = to,
                strokeWidth = 1.5f,
            )
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
        val radius = when {
            node.ring == 0 -> 14f
            node.ring == 1 -> 10f
            node.ring == 2 -> 8f
            else -> 6.5f
        } * dotScale * here * if (selected) selectedScale else 1f
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
        for (node in nodes) {
            if (node.ring > labelThreshold) continue
            val centre = positions[node.id] ?: continue
            if (!onScreen(centre)) continue
            val here = presence(node.id)
            if (here <= 0.01f) continue
            paint.alpha = (baseAlpha * here).toInt()
            drawText(node.name, centre.x, centre.y - 14f, paint)
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
