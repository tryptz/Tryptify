package tf.monochrome.android.ui.discover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.hypot

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
 * Two things a node does. Tapping plays it — the same path Flow uses, straight
 * into the real player and queue, because "quickly listen to each genre" is the
 * whole point. Tapping the panel's *Explore* hands the genre to Discover, which
 * rebuilds its feed around it and its neighbours. Long-pressing a branch
 * collapses it, so you can fold electronic away and actually see folk.
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // What's actually drawn: everything except the descendants of a collapsed
    // branch. Derived rather than stored so collapsing can't desync the two.
    val visible by remember(graph, collapsed) {
        derivedStateOf { visibleNodes(graph, collapsed) }
    }

    val density = LocalDensity.current
    val labelPx = with(density) { 11.dp.toPx() }
    val bounds = remember(graph) { boundsOf(graph.allGenres) }

    val familyColors = remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Genre map") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { collapsed = graph.roots.map { it.id }.toSet() }) {
                    Icon(Icons.Default.UnfoldLess, contentDescription = "Collapse everything")
                }
                IconButton(onClick = { collapsed = emptySet() }) {
                    Icon(Icons.Default.UnfoldMore, contentDescription = "Expand everything")
                }
                IconButton(onClick = { scale = 1f; offset = Offset.Zero }) {
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
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Clamped so the map can't be flicked into empty
                            // space or zoomed past the point where labels stop
                            // being legible.
                            scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            offset += pan
                        }
                    }
                    .pointerInput(visible, scale, offset) {
                        detectTapGestures(
                            onTap = { point ->
                                hitTest(point, visible, size.width, size.height, bounds, scale, offset)
                                    ?.let { viewModel.selectOnMap(it.id) }
                            },
                            onLongPress = { point ->
                                hitTest(point, visible, size.width, size.height, bounds, scale, offset)
                                    ?.let { node ->
                                        // Only a branch can collapse; folding a
                                        // leaf would do nothing and feel broken.
                                        if (graph.children(node.id).isNotEmpty()) {
                                            collapsed = if (node.id in collapsed) collapsed - node.id
                                            else collapsed + node.id
                                        }
                                    }
                            },
                        )
                    },
            ) {
                drawMap(
                    nodes = visible,
                    positions = positionsFor(visible, size.width, size.height, bounds, scale, offset),
                    collapsed = collapsed,
                    selectedId = selected?.id,
                    familyColors = familyColors,
                    edgeColor = edgeColor,
                    labelColor = onSurface,
                    labelSizePx = labelPx,
                    scale = scale,
                )
            }

            selected?.let { node ->
                GenreCard(
                    node = node,
                    childCount = graph.children(node.id).size,
                    familyName = graph.family(node.family)?.name ?: node.family,
                    onPlay = { viewModel.playGenre(node.id, playerViewModel) },
                    onExplore = {
                        viewModel.selectGenre(node.id)
                        navController.popBackStack()
                    },
                    onDismiss = { viewModel.selectOnMap(null) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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

/** The detail panel for a tapped genre — what it is, and the two things to do with it. */
@Composable
private fun GenreCard(
    node: GenreNode,
    childCount: Int,
    familyName: String,
    onPlay: () -> Unit,
    onExplore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .navigationBarsPadding(),
        shape = MonoDimens.shapeLg,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                    if (childCount > 0) append(" · $childCount subgenres")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.aka.isNotEmpty()) {
                Text(
                    text = "also called " + node.aka.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f).bounceClick(onClick = onPlay),
                    shape = MonoDimens.shapePill,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Play",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).bounceClick(onClick = onExplore),
                    shape = MonoDimens.shapePill,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "Explore in Discover",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.UnfoldLess,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    val hidden = HashSet<String>()
    var frontier = collapsed.toList()
    while (frontier.isNotEmpty()) {
        val next = ArrayList<String>()
        for (id in frontier) {
            for (child in graph.children(id)) {
                if (hidden.add(child.id)) next.add(child.id)
            }
        }
        frontier = next
    }
    return graph.allGenres.filterNot { it.id in hidden }
}

private fun positionsFor(
    nodes: List<GenreNode>,
    width: Float,
    height: Float,
    bounds: MapBounds,
    scale: Float,
    offset: Offset,
): Map<String, Offset> {
    // Fit the whole layout at scale 1, uniformly on both axes so the clusters
    // stay circular rather than being squashed into ellipses in portrait.
    val fit = minOf(width / bounds.spanX, height / bounds.spanY) * FIT_MARGIN
    val k = fit * scale
    val cx = width / 2f + offset.x
    val cy = height / 2f + offset.y
    return nodes.associate { node ->
        node.id to Offset(
            x = cx + (node.x - bounds.centreX) * k,
            y = cy + (node.y - bounds.centreY) * k,
        )
    }
}

private fun hitTest(
    point: Offset,
    nodes: List<GenreNode>,
    width: Int,
    height: Int,
    bounds: MapBounds,
    scale: Float,
    offset: Offset,
): GenreNode? {
    val positions = positionsFor(nodes, width.toFloat(), height.toFloat(), bounds, scale, offset)
    // Generous radius: these are small targets on a zoomable canvas, and
    // missing by four pixels should still select the thing you aimed at.
    val touchRadius = 28f
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
    familyColors: Map<String, Color>,
    edgeColor: Color,
    labelColor: Color,
    labelSizePx: Float,
    scale: Float,
) {
    val byId = nodes.associateBy { it.id }

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
        for (parentId in node.parents) {
            val from = positions[parentId] ?: continue
            if (parentId !in byId) continue
            if (!onScreen(to) && !onScreen(from)) continue
            drawLine(color = edgeColor, start = from, end = to, strokeWidth = 1.5f)
        }
        for ((otherId, weight) in node.nearEdges()) {
            val other = byId[otherId] ?: continue
            if (other.family == node.family) continue
            // Each pair once — otherwise every bridge is drawn twice, at double
            // the intended opacity.
            if (otherId < node.id) continue
            val from = positions[otherId] ?: continue
            if (!onScreen(to) && !onScreen(from)) continue
            drawLine(
                color = (familyColors[node.family] ?: edgeColor).copy(alpha = 0.10f + 0.18f * weight),
                start = from,
                end = to,
                strokeWidth = 1f,
            )
        }
    }

    for (node in nodes) {
        val centre = positions[node.id] ?: continue
        if (!onScreen(centre)) continue
        val color = familyColors[node.family] ?: labelColor
        // Roots read as anchors, so they stay larger at every zoom level.
        val radius = when {
            node.ring == 0 -> 9f
            node.ring == 1 -> 6.5f
            else -> 4.5f
        }
        val folded = node.id in collapsed

        drawCircle(color = color, radius = radius, center = centre)
        if (folded) {
            // A ring around a folded branch: the one piece of state on the map
            // that isn't visible from its children, so it has to be marked.
            drawCircle(
                color = color,
                radius = radius + 5f,
                center = centre,
                style = Stroke(width = 2f),
            )
        }
        if (node.id == selectedId) {
            drawCircle(
                color = labelColor,
                radius = radius + 9f,
                center = centre,
                style = Stroke(width = 2.5f),
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
        for (node in nodes) {
            if (node.ring > labelThreshold) continue
            val centre = positions[node.id] ?: continue
            if (!onScreen(centre)) continue
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
