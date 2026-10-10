package tf.monochrome.android.ui.discover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.DailyDiscovery
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.glassSqueeze
import tf.monochrome.android.ui.components.rememberGlassPress
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import kotlin.math.min

/*
 * Discover's daily layer: the sky behind the page, today's discovery at the top
 * of it, and the cards set between the shelves.
 *
 * Every pane here is the app's glass, not this screen's: [DiscoverGlassCard] is
 * a GlassPanel cut from the UI panels material that Visual Studio tunes
 * (LocalMiniPlayerGlass), frosting the page's own backdrop as a sibling, and
 * "Remove liquid glass" flattens it like everywhere else.
 */

/** Colour per family for the whole graph, worked out once. */
@Composable
internal fun rememberFamilyColors(graph: GenreGraph): Map<String, Color> =
    remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

/**
 * Where each genre sits, fitted into a rectangle.
 *
 * The map's own baked coordinates, so the sky behind Discover and the galaxy
 * card are the genre map — the same clusters in the same places — and not a
 * decoration that merely looks like one.
 */
private class GalaxyFit(graph: GenreGraph) {
    private val nodes = graph.allGenres
    private val minX = nodes.minOfOrNull { it.x } ?: 0f
    private val maxX = nodes.maxOfOrNull { it.x } ?: 1f
    private val minY = nodes.minOfOrNull { it.y } ?: 0f
    private val maxY = nodes.maxOfOrNull { it.y } ?: 1f

    /** Every genre's position inside [size], less [inset] on each side. */
    fun place(size: Size, inset: Float): Map<String, Offset> {
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val k = min((size.width - 2 * inset) / spanX, (size.height - 2 * inset) / spanY)
        val ox = (size.width - spanX * k) / 2f
        val oy = (size.height - spanY * k) / 2f
        return nodes.associate { it.id to Offset(ox + (it.x - minX) * k, oy + (it.y - minY) * k) }
    }
}

/** One `drawPoints` call: stars of one colour and size. */
private class StarBatch(val color: Color, val width: Float, val points: List<Offset>)

/** The galaxy, sorted into draw calls once per size and state, not per frame. */
private class GalaxyStars(val batches: List<StarBatch>, val ring: Offset?, val ringColor: Color, val starPx: Float)

/**
 * Sorts the galaxy into draws: every genre a point in its family's colour, the
 * explored ones brighter and larger, and [highlight] ringed.
 *
 * Batched by colour into one `drawPoints` per family and state rather than 771
 * circles, and built inside `drawWithCache` so a redraw — the glass above
 * re-frosting as the feed scrolls — allocates nothing.
 */
private fun galaxyStars(
    points: Map<String, Offset>,
    graph: GenreGraph,
    colors: Map<String, Color>,
    explored: Set<String>,
    highlight: String?,
    dim: Float,
    starPx: Float,
): GalaxyStars {
    val groups = HashMap<Pair<Color, Boolean>, MutableList<Offset>>()
    for (node in graph.allGenres) {
        val at = points[node.id] ?: continue
        val colour = colors[node.family] ?: Color.Gray
        groups.getOrPut(colour to (node.id in explored)) { ArrayList() }.add(at)
    }
    val batches = groups.map { (key, offsets) ->
        val (colour, lit) = key
        StarBatch(
            color = if (lit) colour else colour.copy(alpha = dim),
            width = if (lit) starPx * 2.2f else starPx,
            points = offsets,
        )
    }
    val ringColour = highlight?.let { graph[it] }?.let { colors[it.family] } ?: Color.White
    return GalaxyStars(batches, highlight?.let { points[it] }, ringColour, starPx)
}

private fun DrawScope.drawGalaxy(stars: GalaxyStars) {
    for (batch in stars.batches) {
        drawPoints(
            points = batch.points,
            pointMode = PointMode.Points,
            color = batch.color,
            strokeWidth = batch.width,
            cap = StrokeCap.Round,
        )
    }
    stars.ring?.let { at ->
        val r = stars.starPx
        drawCircle(stars.ringColor.copy(alpha = 0.25f), radius = r * 7f, center = at)
        drawCircle(stars.ringColor, radius = r * 4.5f, center = at, style = Stroke(width = r * 0.9f))
        drawCircle(stars.ringColor, radius = r * 1.6f, center = at)
    }
}

/**
 * A pane of the page's glass, as a card in the feed.
 *
 * Passes touches through to the list, so a swipe that lands on a card still
 * scrolls the feed. With [onClick] the whole card is a button that gives
 * under the finger.
 */
@Composable
internal fun DiscoverGlassCard(
    haze: dev.chrisbanes.haze.HazeState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val press = rememberGlassPress()
    GlassPanel(
        hazeState = haze,
        glass = LocalMiniPlayerGlass.current,
        modifier = modifier.then(
            if (onClick != null) Modifier.glassSqueeze(press = press, onClick = onClick) else Modifier,
        ),
        avoidNavigationBar = false,
        blockTouchesBelow = false,
    ) {
        Box(modifier = Modifier.padding(18.dp)) { content() }
    }
}

/** A small caption above a card's title, in the card's own colour. */
@Composable
private fun Kicker(text: String, color: Color, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** "Electronic · 135–145 BPM · Since 1985", for one genre. */
@Composable
private fun genreFacts(graph: GenreGraph, node: GenreNode): String {
    val parts = buildList {
        add(graph.family(node.family)?.name ?: node.family)
        if (node.hasTempo) add(stringResource(R.string.shelf_tempo, node.bpmLow, node.bpmHigh))
        node.era.getOrNull(0)?.let { add(stringResource(R.string.discover_genre_since, it)) }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/** Play, with a spinner in place of the icon while the genre's tracks are fetched. */
@Composable
private fun PlayGenreButton(label: String, starting: Boolean, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !starting,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = if (accent.luminance() > 0.5f) Color.Black else Color.White,
        ),
    ) {
        if (starting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/**
 * Today's discovery: one genre, picked for this listener, for today.
 *
 * The top of the page and the reason to open it tomorrow — it changes at
 * midnight and says so. Its reason line is a claim about the listener's own
 * genres, so it names them, or says plainly that this one is somewhere new.
 */
@Composable
internal fun TodayHero(
    graph: GenreGraph,
    pick: DailyDiscovery.Pick,
    hearted: Boolean,
    starting: Boolean,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onHeart: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val colors = rememberFamilyColors(graph)
    val accent = colors[pick.genre.family] ?: MaterialTheme.colorScheme.primary
    Column {
        Kicker(
            text = stringResource(R.string.discover_today_title),
            color = accent,
            trailing = stringResource(R.string.discover_today_daily),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = pick.genre.name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (pick.via.size) {
                0 -> stringResource(R.string.discover_today_fresh)
                1 -> stringResource(R.string.discover_today_via_one, pick.via[0].name)
                else -> stringResource(R.string.discover_today_via_two, pick.via[0].name, pick.via[1].name)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = genreFacts(graph, pick.genre),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayGenreButton(stringResource(R.string.action_play), starting, accent, onPlay)
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onRadio, enabled = !starting) {
                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tab_radio))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHeart) {
                Icon(
                    imageVector = if (hearted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(if (hearted) R.string.genre_unkeep else R.string.genre_keep),
                    tint = if (hearted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenMap) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = stringResource(R.string.genre_map),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The listener's galaxy: the whole map, with the genres they have been to lit.
 *
 * A collection that fills in as they listen, which is the part of this page
 * meant to be come back to. The ringed star is today's discovery — the next
 * one to light — so the card and the top of the page point at each other.
 */
@Composable
internal fun GalaxyCard(
    graph: GenreGraph,
    explored: Set<String>,
    next: GenreNode?,
) {
    val colors = rememberFamilyColors(graph)
    val fit = remember(graph) { GalaxyFit(graph) }
    val accent = MaterialTheme.colorScheme.primary
    val total = graph.size.coerceAtLeast(1)
    Column {
        Kicker(text = stringResource(R.string.discover_galaxy_title), color = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.discover_galaxy_count, explored.size, graph.size),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .drawWithCache {
                    val points = fit.place(size, inset = 6.dp.toPx())
                    val stars = galaxyStars(points, graph, colors, explored, next?.id, dim = 0.28f, starPx = 1.4.dp.toPx())
                    onDrawBehind { drawGalaxy(stars) }
                },
        ) {}
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { explored.size.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                explored.isEmpty() -> stringResource(R.string.discover_galaxy_empty)
                next != null -> stringResource(R.string.discover_galaxy_next, next.name)
                else -> stringResource(R.string.browse_the_map)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The genre of the day: a genre worth knowing, with the start of its history.
 *
 * The text is English Wikipedia's under CC BY-SA, so it carries its source and
 * a link to the article, exactly as the map's history panel does; "Read the
 * history" opens that panel on this genre for the rest of it.
 */
@Composable
internal fun SpotlightCard(
    graph: GenreGraph,
    spotlight: GenreSpotlight,
    starting: Boolean,
    onPlay: () -> Unit,
    onReadHistory: () -> Unit,
) {
    val colors = rememberFamilyColors(graph)
    val accent = colors[spotlight.genre.family] ?: MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val history = spotlight.history
    Column {
        Kicker(text = stringResource(R.string.discover_spotlight_title), color = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            text = spotlight.genre.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = genreFacts(graph, spotlight.genre),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = history.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = history.fragment?.let {
                stringResource(R.string.wikipedia_attribution_section, it, history.title)
            } ?: stringResource(R.string.wikipedia_attribution, history.title),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier
                .bounceClick(onClick = { if (history.url.isNotBlank()) uriHandler.openUri(history.url) })
                .padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayGenreButton(stringResource(R.string.play_top), starting, accent, onPlay)
            TextButton(onClick = onReadHistory) {
                Text(
                    text = stringResource(R.string.read_history_of, spotlight.genre.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The way to the World radio page, as a card in the feed. */
@Composable
internal fun WorldRadioCard() {
    val accent = MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.world_radio),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.cities_on_air),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The way into Swipe to discover, as a card in the feed: what it is before
 * today's stack is dealt, how far through it the listener is after.
 */
@Composable
internal fun DeckEntryCard(progress: DeckProgress?) {
    val accent = MaterialTheme.colorScheme.secondary
    Column {
        Kicker(text = stringResource(R.string.deck_title), color = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                progress == null -> stringResource(R.string.deck_card_body, tf.monochrome.android.domain.model.SwipeDeck.SIZE)
                progress.done -> stringResource(R.string.deck_card_done, progress.kept)
                else -> stringResource(R.string.deck_card_progress, progress.swiped, progress.size, progress.kept)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (progress.size == 0) 0f else progress.swiped.toFloat() / progress.size },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                when {
                    progress == null -> R.string.deck_start
                    progress.done -> R.string.deck_go_again
                    else -> R.string.action_continue
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}
