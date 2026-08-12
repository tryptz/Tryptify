package tf.monochrome.android.ui.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.RadioCity
import tf.monochrome.android.domain.model.RadioStation
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.reduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * World radio — the Earth as one picture, with every city you can tune into.
 *
 * A globe rather than a flat map, and drawn rather than photographed. An
 * orthographic projection is the honest one for a sphere you spin with your
 * thumb: what you see is what you would see from space, the far side is behind
 * the near side rather than smeared across the edges, and nothing has to lie
 * about Greenland. The coastlines are Natural Earth's, ~5,100 points of public
 * domain vector, so the whole thing follows the app's palette and its light and
 * dark themes instead of shipping a photograph that follows neither.
 *
 * The dots are the point. Every one is a city that **actually broadcasts** —
 * verified at build time against the radio-browser directory, 1,611 of 6,298
 * candidate cities — and its size is how many stations were found there. A city
 * nobody broadcasts from is absent rather than drawn empty, because a dot on
 * this globe is a claim that you can tune into something, and an empty one would
 * be a lie you can tap.
 *
 * What the panel lists is fetched live, not baked. Stream URLs rot far faster
 * than an app ships — the directory has thousands go dark between releases — so
 * the geography travels in the APK and the stations are asked for when you tap.
 * That is also why the panel distinguishes "couldn't reach the directory" from
 * "nothing here": they are different claims and only one of them is about the
 * city.
 *
 * The panel is the genre map's own card, from the same shared component, and for
 * the same reason it exists there: it floats over a full-bleed canvas above the
 * mini player, and two sheets of glass tuned differently an inch apart look like
 * a bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldRadioScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: WorldRadioViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val globe by viewModel.globe.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val instant = reduceMotion()
    val scope = rememberCoroutineScope()

    var camera by remember { mutableStateOf(GlobeCamera()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Seeded rather than zero, for the same reason the genre map seeds its own:
    // the panel only exists once a city is selected, so a measured-only value
    // would make the very first selection spin to the wrong place and correct
    // itself a frame later.
    var panelHeightPx by remember { mutableIntStateOf(with(density) { 260.dp.roundToPx() }) }
    var flight by remember { mutableStateOf<Job?>(null) }

    // Read at gesture time rather than captured. Keying the pointer handlers on
    // the camera would rebuild them on every frame of a spin and drop the
    // pointer stream mid-gesture — the bug the genre map documents.
    val liveCamera = rememberUpdatedState(camera)
    val liveCities = rememberUpdatedState(globe.cities)
    val liveScale = rememberUpdatedState(globe.scale)

    val mapHaze = rememberHazeState()
    val playing by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val panelBottomInset = if (playing != null) MINI_PLAYER_RESERVE else 0.dp
    val glassSettings by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()

    var topBarHeightPx by remember { mutableIntStateOf(0) }

    fun spinTo(city: RadioCity) {
        flight?.cancel()
        if (canvasSize == IntSize.Zero) return
        flight = scope.launch {
            spinToCity(city, globe.scale, camera, instant) { camera = it }
        }
    }

    // Selecting a city — by tapping the globe, or a nearby chip — turns the
    // Earth to face it. Keyed on the panel's measured height as well as the id,
    // so opening the panel re-aims rather than leaving the city underneath it.
    LaunchedEffect(selected?.id, canvasSize, panelHeightPx / FLIGHT_HEIGHT_QUANTUM) {
        selected?.let { spinTo(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("World radio") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { flight?.cancel(); camera = GlobeCamera() }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recentre")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val ocean = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
            val land = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            val dot = MaterialTheme.colorScheme.primary
            val onSurface = MaterialTheme.colorScheme.onSurface

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(mapHaze)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            flight?.cancel()
                            camera = liveCamera.value.spun(pan, zoom, size)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { point ->
                            val hit = hitTest(
                                point,
                                liveCities.value,
                                liveScale.value,
                                size.width,
                                size.height,
                                liveCamera.value,
                            ) ?: return@detectTapGestures
                            viewModel.select(hit)
                        }
                    },
            ) {
                drawGlobe(
                    data = globe,
                    camera = camera,
                    ocean = ocean,
                    land = land,
                    dot = dot,
                    selectedId = selected?.id,
                )
            }

            Text(
                text = if (globe.cities.isEmpty()) "" else "Dots are cities on air · sized by how many stations",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingSm),
            )

            selected?.let { city ->
                CompositionLocalProvider(LocalPlayerGlass provides glassSettings) {
                    CityCard(
                        city = city,
                        stations = stations,
                        nearby = nearby,
                        favourites = favourites,
                        hazeState = mapHaze,
                        glass = glassSettings,
                        maxStationsHeight = with(density) {
                            (canvasSize.height * STATIONS_HEIGHT_FRACTION)
                                .coerceAtLeast(with(density) { 140.dp.toPx() })
                                .toDp()
                        },
                        onPlay = { viewModel.play(it, city, playerViewModel) },
                        onFavourite = { viewModel.toggleFavourite(it) },
                        onNearby = { viewModel.select(it) },
                        onDismiss = { viewModel.select(null) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { panelHeightPx = it.height }
                            .padding(bottom = panelBottomInset),
                    )
                }
            }

            if (globe.cities.isEmpty()) {
                Text(
                    text = "The globe didn't load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

// ── the panel ───────────────────────────────────────────────────────────────

/** The card for a tapped city — what plays there, and where to go next. */
@Composable
private fun CityCard(
    city: RadioCity,
    stations: StationsState,
    nearby: List<RadioCity>,
    favourites: Set<String>,
    hazeState: dev.chrisbanes.haze.HazeState,
    glass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    maxStationsHeight: androidx.compose.ui.unit.Dp,
    onPlay: (RadioStation) -> Unit,
    onFavourite: (RadioStation) -> Unit,
    onNearby: (RadioCity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = city.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(city.country)
                            append(" · ")
                            append(
                                if (city.stations == 1) "1 station"
                                else "${city.stations} stations",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (stations) {
                StationsState.Idle, StationsState.Loading -> Text(
                    text = "Tuning in…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Two different sentences on purpose. One is about the network,
                // the other about the city, and telling a listener their city
                // has no radio when in fact the request failed is the kind of
                // small lie that makes a map untrustworthy.
                StationsState.Unreachable -> Text(
                    text = "Couldn't reach the station directory. It's the listing " +
                        "that's missing, not the radio — try again in a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is StationsState.Ready -> if (stations.stations.isEmpty()) {
                    Text(
                        text = "Nothing is on air here right now. ${city.stations} " +
                            "station(s) were listed when this map was built; the " +
                            "directory has since dropped them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxStationsHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (station in stations.stations) {
                            StationRow(
                                station = station,
                                favourite = station.uuid in favourites,
                                onPlay = { onPlay(station) },
                                onFavourite = { onFavourite(station) },
                                onHomepage = station.homepage?.let {
                                    { uriHandler.openUri(it) }
                                },
                            )
                        }
                    }
                }
            }

            if (nearby.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Nearby on air",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nearby, key = { it.id }) { neighbour ->
                        CityChip(city = neighbour, onClick = { onNearby(neighbour) })
                    }
                }
            }
        }
    }
}

/** One station: what it is, how it sounds, and the two things you can do with it. */
@Composable
private fun StationRow(
    station: RadioStation,
    favourite: Boolean,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
    onHomepage: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onPlay)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play ${station.name}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = (listOfNotNull(station.qualityLabel) + station.topTags(2))
                .joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        onHomepage?.let {
            IconButton(onClick = it, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open ${station.name}'s website",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(onClick = onFavourite, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favourite) "Remove from your stations"
                else "Keep this station",
                tint = if (favourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A neighbouring city, as a chip that spins the globe to it. */
@Composable
private fun CityChip(city: RadioCity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = city.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = city.stations.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── projection, camera and drawing ──────────────────────────────────────────

private val MINI_PLAYER_RESERVE = 72.dp
private const val STATIONS_HEIGHT_FRACTION = 0.34f
private const val FLIGHT_HEIGHT_QUANTUM = 96
private const val SPIN_MILLIS = 700
private val SpinEasing = CubicBezierEasing(0.62f, 0f, 0.28f, 1f)

private const val MIN_ZOOM = 0.85f
private const val MAX_ZOOM = 9f

/** Fraction of the smaller viewport dimension the globe fills at zoom 1. */
private const val GLOBE_FIT = 0.42f

/**
 * Where the Earth is turned to, and how close.
 *
 * [yaw] and [pitch] are in radians and describe which point faces the viewer,
 * rather than an x/y offset: on a sphere, panning *is* rotating, and storing a
 * translation would let the globe be dragged off the screen with no way back.
 * Pitch is clamped short of the poles because passing one flips the world
 * upside down mid-drag, which reads as a glitch rather than as a rotation.
 */
private data class GlobeCamera(
    val yaw: Float = 0f,
    val pitch: Float = 0.35f,
    val zoom: Float = 1f,
) {
    fun spun(pan: Offset, zoomBy: Float, size: IntSize): GlobeCamera {
        val next = (zoom * zoomBy).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val radius = globeRadius(size.width.toFloat(), size.height.toFloat(), next)
        // Divide by the radius so a drag moves the surface under the finger at
        // roughly the same rate however far in you are: at 9× a pixel of drag is
        // a ninth of the arc it was at 1×.
        val dYaw = if (radius <= 0f) 0f else -pan.x / radius
        val dPitch = if (radius <= 0f) 0f else pan.y / radius
        return GlobeCamera(
            yaw = wrap(yaw + dYaw),
            pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH),
            zoom = next,
        )
    }

    private companion object {
        const val MAX_PITCH = 1.45f
    }
}

private fun wrap(radians: Float): Float {
    val twoPi = (2 * PI).toFloat()
    var value = radians % twoPi
    if (value > PI) value -= twoPi
    if (value < -PI) value += twoPi
    return value
}

private fun globeRadius(width: Float, height: Float, zoom: Float): Float =
    minOf(width, height) * GLOBE_FIT * zoom

/**
 * A point on the sphere in view space.
 *
 * [z] is the depth toward the viewer: positive is the near hemisphere, negative
 * the far one. Everything that reads this culls on it, which is what stops the
 * globe from drawing Australia through the Atlantic.
 */
private data class Projected(val x: Float, val y: Float, val z: Float) {
    val visible: Boolean get() = z >= 0f
}

/**
 * Latitude and longitude in hundredths of a degree to a point in view space.
 *
 * Orthographic: rotate the unit sphere by the camera, then drop the depth. No
 * perspective divide, because from orbit there effectively isn't one, and adding
 * it would make the centre of the globe swell.
 */
private fun project(
    latRaw: Int,
    lonRaw: Int,
    scale: Int,
    camera: GlobeCamera,
    cx: Float,
    cy: Float,
    radius: Float,
): Projected {
    val lat = Math.toRadians(latRaw.toDouble() / scale)
    val lon = Math.toRadians(lonRaw.toDouble() / scale) + camera.yaw

    val cosLat = cos(lat)
    val x = cosLat * sin(lon)
    val y = sin(lat)
    val z = cosLat * cos(lon)

    // Tilt about the horizontal axis, so dragging up and down walks the poles
    // toward the viewer instead of rolling the image.
    val cosP = cos(camera.pitch.toDouble())
    val sinP = sin(camera.pitch.toDouble())
    val y2 = y * cosP - z * sinP
    val z2 = y * sinP + z * cosP

    return Projected(
        x = cx + (x * radius).toFloat(),
        y = cy - (y2 * radius).toFloat(),
        z = z2.toFloat(),
    )
}

/** Turn the Earth so a city faces the viewer, on a curve. */
private suspend fun spinToCity(
    city: RadioCity,
    scale: Int,
    from: GlobeCamera,
    instant: Boolean,
    onFrame: (GlobeCamera) -> Unit,
) {
    val targetYaw = wrap((-Math.toRadians(city.lon.toDouble() / scale)).toFloat())
    val targetPitch = Math.toRadians(city.lat.toDouble() / scale).toFloat()
        .coerceIn(-1.45f, 1.45f)
    val targetZoom = maxOf(from.zoom, 2.2f)

    if (instant) {
        onFrame(GlobeCamera(targetYaw, targetPitch, targetZoom))
        return
    }

    // Take the short way round. Interpolating raw yaw from +170° to -170° spins
    // the globe the long way through the whole Pacific to travel twenty degrees.
    val deltaYaw = wrap(targetYaw - from.yaw)
    animate(0f, 1f, animationSpec = tween(SPIN_MILLIS, easing = SpinEasing)) { t, _ ->
        onFrame(
            GlobeCamera(
                yaw = wrap(from.yaw + deltaYaw * t),
                pitch = from.pitch + (targetPitch - from.pitch) * t,
                zoom = from.zoom + (targetZoom - from.zoom) * t,
            ),
        )
    }
}

/**
 * Which city a tap landed on.
 *
 * Projects forward and takes the nearest within a touch radius rather than
 * inverting the projection, exactly as the genre map does — and here the case
 * for it is stronger, because inverting an orthographic sphere projection is
 * ambiguous by construction: every screen point maps to two places, one on each
 * hemisphere. Only the near one is culled in, so going forwards answers the
 * question the tap actually asked.
 */
private fun hitTest(
    point: Offset,
    cities: List<RadioCity>,
    scale: Int,
    width: Int,
    height: Int,
    camera: GlobeCamera,
): RadioCity? {
    if (cities.isEmpty()) return null
    val radius = globeRadius(width.toFloat(), height.toFloat(), camera.zoom)
    val cx = width / 2f
    val cy = height / 2f

    var best: RadioCity? = null
    var bestDistance = TOUCH_RADIUS
    for (city in cities) {
        val p = project(city.lat, city.lon, scale, camera, cx, cy, radius)
        if (!p.visible) continue
        val distance = hypot(p.x - point.x, p.y - point.y)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = city
        }
    }
    return best
}

private const val TOUCH_RADIUS = 34f

/**
 * Draw the Earth.
 *
 * Ocean disc first so the far hemisphere's coastlines have something to be
 * hidden behind, then the coastlines, then the cities — biggest last, because
 * the asset is sorted by station count and the busiest city should not be
 * covered by a one-station neighbour.
 */
private fun DrawScope.drawGlobe(
    data: tf.monochrome.android.domain.model.WorldRadioData,
    camera: GlobeCamera,
    ocean: Color,
    land: Color,
    dot: Color,
    selectedId: String?,
) {
    if (data.cities.isEmpty() && data.coastline.isEmpty()) return

    val radius = globeRadius(size.width, size.height, camera.zoom)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val scale = data.scale.takeIf { it > 0 } ?: 100

    drawCircle(color = ocean, radius = radius, center = Offset(cx, cy))
    drawCircle(
        color = land.copy(alpha = 0.35f),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.2f),
    )

    for (line in data.coastline) {
        val path = Path()
        var drawing = false
        var index = 0
        while (index + 1 < line.size) {
            val p = project(line[index + 1], line[index], scale, camera, cx, cy, radius)
            if (p.visible) {
                if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                drawing = true
            } else {
                // The line went round the back. Lifting the pen is what keeps a
                // coast from being drawn as a chord straight across the disc
                // when it reappears on the other limb.
                drawing = false
            }
            index += 2
        }
        drawPath(path = path, color = land, style = Stroke(width = 1.1f))
    }

    for (city in data.cities) {
        val p = project(city.lat, city.lon, scale, camera, cx, cy, radius)
        if (!p.visible) continue
        if (p.x < -20f || p.y < -20f || p.x > size.width + 20f || p.y > size.height + 20f) continue

        // Log, because the counts run from 1 to several hundred: linear sizing
        // makes Berlin a blot and leaves every one-station town at the same
        // invisible speck.
        val weight = (ln(1f + city.stations) / LOG_CEILING).coerceIn(0f, 1f)
        val base = 1.6f + weight * 4.4f
        val selected = city.id == selectedId

        // Fade toward the limb, so the sphere reads as curved rather than as a
        // flat disc of dots.
        val edge = (p.z * 1.4f).coerceIn(0.25f, 1f)

        if (selected) {
            drawCircle(
                color = dot.copy(alpha = 0.30f),
                radius = base * 3.2f,
                center = Offset(p.x, p.y),
            )
        }
        drawCircle(
            color = dot.copy(alpha = edge),
            radius = if (selected) base * 1.7f else base,
            center = Offset(p.x, p.y),
        )
    }
}

/**
 * ln(1 + 2294) — the busiest city in the dataset, so weights land inside 0..1.
 *
 * Measured rather than guessed: the counts span three orders of magnitude, and a
 * ceiling set too low saturates every large city to the same maximum dot, which
 * is exactly the information the size was supposed to carry.
 */
private const val LOG_CEILING = 7.74f
