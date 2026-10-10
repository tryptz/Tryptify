package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.normalizeForMatch
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassPanel

/**
 * A star's system as a list: what a long press on a star opens, over the map
 * or in full screen.
 *
 * Each planet is a row — its artist's picture as a lit sphere, the name, and
 * a line about them (the opening of their Last.fm bio, or where they stand in
 * this genre's chart when there is none) — and opens onto its moons, the
 * artist's charted tracks, hung from it on a dashed orbit line. A moon is a tap
 * to play. The first planet starts open, so the press shows music at once.
 *
 * The UI panels glass, like every pane on the map, so its text follows the
 * glass ([tf.monochrome.android.ui.components.GlassInkScope]).
 */
@Composable
internal fun GalaxySystemSheet(
    genreName: String,
    since: Int?,
    /** Null while its chart is still coming. */
    system: PlanetSystem?,
    bios: Map<String, String>,
    playingTitle: String?,
    playingArtist: String?,
    onPlayMoon: (ChartEntry) -> Unit,
    onClose: () -> Unit,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier, avoidNavigationBar = false) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(end = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        genreName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val counts = system?.takeIf { it.planets.isNotEmpty() }?.let { sys ->
                        val moons = sys.planets.sumOf { it.moons.size }
                        pluralStringResource(R.plurals.galaxy_sheet_planets, sys.planets.size, sys.planets.size) +
                            " · " + pluralStringResource(R.plurals.galaxy_sheet_moons, moons, moons)
                    }
                    val line = listOfNotNull(counts, since?.let { stringResource(R.string.discover_genre_since, it) })
                        .joinToString(" · ")
                    if (line.isNotEmpty()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.galaxy_close))
                }
            }
            if (playingTitle != null) {
                NowPlayingLine(playingTitle, playingArtist, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
            }
            Column(
                modifier = Modifier
                    .padding(top = 6.dp, end = 8.dp)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when {
                    system == null -> Note(stringResource(R.string.galaxy_sheet_loading))
                    system.planets.isEmpty() -> Note(stringResource(R.string.galaxy_sheet_empty))
                    else -> {
                        val open = remember(system.genreId) { mutableStateMapOf(0 to true) }
                        system.planets.forEachIndexed { p, planet ->
                            PlanetRow(
                                planet = planet,
                                description = bios[planet.artist] ?: chartLine(p, planet, genreName),
                                open = open[p] == true,
                                onToggle = { open[p] = open[p] != true },
                            )
                            AnimatedVisibility(visible = open[p] == true) {
                                MoonTree(planet, playingTitle, playingArtist, onPlayMoon)
                            }
                        }
                        if (system.planets.any { it.artist in bios }) {
                            Text(
                                stringResource(R.string.galaxy_bios_credit),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Where a planet stands in its genre's chart, for one with no bio. */
@Composable
private fun chartLine(p: Int, planet: PlanetSystem.Planet, genreName: String): String =
    stringResource(R.string.galaxy_planet_rank, p + 1, genreName) + " · " +
        pluralStringResource(R.plurals.galaxy_planet_tracks, planet.moons.size, planet.moons.size)

@Composable
private fun NowPlayingLine(title: String, artist: String?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.38f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.now_playing),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (artist.isNullOrBlank()) title else "$title — $artist",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
    )
}

@Composable
private fun PlanetRow(planet: PlanetSystem.Planet, description: String, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "planetChevron")
    val moons = pluralStringResource(R.plurals.galaxy_sheet_moons, planet.moons.size, planet.moons.size)
    val toggleLabel = if (open) stringResource(R.string.action_collapse) else stringResource(R.string.galaxy_show_moons)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = "${planet.artist}, $moons. $description"
                stateDescription = toggleLabel
            }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        // The picture is the planet, so it is as big as the planet is: a
        // bigger catalogue, a bigger world, and a short sheet when they are small.
        Box(Modifier.size(PLANET_SLOT_DP.dp), contentAlignment = Alignment.Center) {
            PlanetImage(planet, planetImageDp(planet).dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                planet.artist,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).rotate(turn),
        )
    }
}

/**
 * The artist's picture as a planet: the cover cropped round, lit from the top
 * left with its night side falling away, the way the map lights its planets.
 * Banded in the planet's own colour when the chart has no picture.
 */
@Composable
private fun PlanetImage(planet: PlanetSystem.Planet, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .drawWithContent {
                drawContent()
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                        center = Offset(this.size.width * 0.32f, this.size.height * 0.28f),
                        radius = this.size.width * 0.42f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.7f),
                        center = Offset(this.size.width * 0.38f, this.size.height * 0.34f),
                        radius = this.size.width * 0.82f,
                    ),
                )
            },
    ) {
        val url = planet.artworkUrl
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Canvas(Modifier.matchParentSize()) {
                fun hsv(dh: Float, sat: Float, v: Float) = Color.hsv(((planet.hue + dh) % 360f + 360f) % 360f, sat, v)
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            hsv(0f, 0.38f, 0.95f), hsv(14f, 0.55f, 0.78f), hsv(-6f, 0.32f, 0.92f),
                            hsv(22f, 0.6f, 0.72f), hsv(4f, 0.42f, 0.9f), hsv(-12f, 0.5f, 0.8f),
                        ),
                    ),
                )
            }
        }
    }
}

/** The planet's moons, hung from it on a dashed orbit line, each a row to tap and play. */
@Composable
private fun MoonTree(
    planet: PlanetSystem.Planet,
    playingTitle: String?,
    playingArtist: String?,
    onPlayMoon: (ChartEntry) -> Unit,
) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val count = planet.moons.size
    Column(
        modifier = Modifier
            .padding(start = (4 + PLANET_SLOT_DP / 2).dp)
            .drawBehind {
                if (count == 0) return@drawBehind
                val row = MOON_ROW_DP.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                val stroke = 1.5.dp.toPx()
                val last = row * (count - 1) + row / 2f
                drawLine(line, Offset(0f, 0f), Offset(0f, last), strokeWidth = stroke, pathEffect = dash)
                for (k in 0 until count) {
                    val y = row * k + row / 2f
                    drawLine(line, Offset(0f, y), Offset(14.dp.toPx(), y), strokeWidth = stroke, pathEffect = dash)
                }
            }
            .padding(start = 20.dp),
    ) {
        val playingKey = playingTitle?.let { normalizeForMatch(it) }
        val playingBy = playingArtist?.let { normalizeForMatch(it) }.orEmpty()
        planet.moons.forEach { moon ->
            val entry = moon.entry
            val on = playingKey != null && normalizeForMatch(entry.title) == playingKey &&
                (playingBy.isEmpty() || playingBy.contains(normalizeForMatch(entry.artistName)))
            MoonRow(entry.title, on, onClick = { onPlayMoon(entry) })
        }
    }
}

@Composable
private fun MoonRow(title: String, on: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(MOON_ROW_DP.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.galaxy_play_track, title),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFF4F3F8), Color(0xFFB3B2BE), Color(0xFF5E5D68)),
                    ),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (on) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The room a planet's picture has in its row, and the smallest and largest it is drawn there. */
private const val PLANET_SLOT_DP = 40
private const val PLANET_MIN_DP = 24f
private const val PLANET_MAX_DP = 40f

/** A planet's picture size in the sheet: its catalogue's share, as the map sizes the planet. */
private fun planetImageDp(planet: PlanetSystem.Planet): Float =
    PLANET_MIN_DP + (PLANET_MAX_DP - PLANET_MIN_DP) * PlanetSystem.catalogShare(planet.releases)

private const val MOON_ROW_DP = 44
