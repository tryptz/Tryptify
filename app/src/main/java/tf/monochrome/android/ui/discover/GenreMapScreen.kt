package tf.monochrome.android.ui.discover

import tf.monochrome.android.ui.navigation.popBackStackSafe
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.ui.discover.galaxy.AudioBands
import tf.monochrome.android.ui.discover.galaxy.GALAXY_ARRIVE_DISTANCE
import tf.monochrome.android.ui.discover.galaxy.GALAXY_INK
import tf.monochrome.android.ui.discover.galaxy.PlanetSystem
import tf.monochrome.android.ui.discover.galaxy.GALAXY_SPACE
import tf.monochrome.android.ui.discover.galaxy.GalaxyCamera
import tf.monochrome.android.ui.discover.galaxy.GalaxyScene
import tf.monochrome.android.ui.discover.galaxy.GenreGalaxyView
import tf.monochrome.android.ui.player.sceneGodRays
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
 * pane cannot frost a SurfaceView. The core and the bright stars shine through
 * the lyric god rays ([sceneGodRays]) on API 33 and up.
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
@OptIn(ExperimentalMaterial3Api::class)
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

    val density = LocalDensity.current
    val instant = reduceMotion()
    val lowPower = LocalLowPerformance.current.disableLiquidGlass
    // Twinkle and the galaxy's turn redraw every frame, so they go with the rest
    // of the motion when the device or the listener asks for less.
    val alive = !instant && !lowPower

    // The listener's look for the map, from its settings sheet. Low-performance
    // mode still has the last word on the heavy effects.
    val visuals by viewModel.galaxyVisuals.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val dustCount = when {
        lowPower -> LOW_POWER_DUST
        visuals.dust == tf.monochrome.android.domain.model.GalaxyAmount.LESS -> GalaxyScene.DEFAULT_DUST * 2 / 5
        visuals.dust == tf.monochrome.android.domain.model.GalaxyAmount.MORE -> GalaxyScene.DEFAULT_DUST * 3 / 2
        else -> GalaxyScene.DEFAULT_DUST
    }
    val scene = remember(graph, dustCount) {
        if (graph.size == 0) {
            null
        } else {
            GalaxyScene(graph, dustCount = dustCount)
        }
    }
    val camera = remember { GalaxyCamera() }
    val familyColors = remember(graph) { familyPalette(graph.allGenres.map { it.family }.distinct()) }

    var timeline by rememberSaveable { mutableStateOf(false) }
    // 0 the galaxy, 1 the timeline. Read through the state, never in
    // composition, so the morph redraws the map without recomposing the screen.
    val morph = animateFloatAsState(
        targetValue = if (timeline) 1f else 0f,
        animationSpec = if (instant) snap() else tween(MORPH_MILLIS, easing = FastOutSlowInEasing),
        label = "galaxyMorph",
    )

    val glassSettings by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val hearted by viewModel.heartedGenres.collectAsStateWithLifecycle()
    val explored by viewModel.exploredGenres.collectAsStateWithLifecycle()
    val here by viewModel.mapHere.collectAsStateWithLifecycle()
    val system by viewModel.mapSystem.collectAsStateWithLifecycle()

    // Planets grow out of their star when the chart arrives, rather than
    // popping into orbit.
    val systemAppear = remember { Animatable(0f) }
    LaunchedEffect(system) {
        if (system == null || instant) {
            systemAppear.snapTo(if (system == null) 0f else 1f)
        } else {
            systemAppear.snapTo(0f)
            systemAppear.animateTo(1f, tween(SYSTEM_APPEAR_MILLIS, easing = FastOutSlowInEasing))
        }
    }

    // The map blurs itself behind the panels. A local haze source rather than
    // the shared app one: the panels sit inside the same subtree, and pointing
    // them at the app-wide state would have them sampling their own output.
    val mapHaze = rememberHazeState()

    // The map runs under the tab bar and mini player so they have something to
    // lens, so the panel has to clear them itself. The inset includes the
    // system bar, which this full-bleed route also runs under — the panel's
    // own navigationBarsPadding is consumed below so it is not counted twice.
    val panelBottomInset = tf.monochrome.android.ui.navigation.LocalBottomChromeInset.current

    // Measured rather than assumed: the bar moves with font scale and the
    // display cutout, the panel with whatever it is showing.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var topChromePx by remember { mutableIntStateOf(0) }
    var hudHeightPx by remember { mutableIntStateOf(0) }
    var lookHeightPx by remember { mutableIntStateOf(0) }
    var panelHeightPx by remember { mutableIntStateOf(with(density) { 230.dp.roundToPx() }) }

    // The middle of the view is the middle of what the panels leave visible,
    // or every genre you look at would sit under its own panel. Eased, so the
    // star you are reading about slides up as the panel grows instead of
    // jumping.
    val reserveBottom by animateFloatAsState(
        targetValue = with(density) { panelBottomInset.toPx() } +
            when {
                settingsOpen -> lookHeightPx
                selected != null -> panelHeightPx
                else -> hudHeightPx
            },
        animationSpec = if (instant) snap() else tween(RESERVE_MILLIS, easing = FastOutSlowInEasing),
        label = "galaxyReserve",
    )

    val scope = rememberCoroutineScope()
    // The camera move in progress, so a touch can take the controls back off it.
    var travel by remember { mutableStateOf<Job?>(null) }
    var travelBlur by remember { mutableFloatStateOf(0f) }
    val maxBlurPx = with(density) { TRAVEL_BLUR.toPx() }
    val scratch = remember { FloatArray(3) }

    // Seconds of the clock, for twinkle and the god rays' shimmer.
    var clock by remember { mutableFloatStateOf(0f) }
    // The music, read the way a MilkDrop preset reads it, for the gas and the
    // black hole. The spectrum tap only runs while the map is up and moving.
    val spectrum = playerViewModel.spectrumAnalyzer
    val listening = alive && spectrum != null && visuals.musicReactive && visuals.smoke
    val bands = remember { AudioBands() }
    // Read by the clock, which outlives the composition that started it.
    val liveVisuals = rememberUpdatedState(visuals)
    if (listening) {
        DisposableEffect(spectrum) {
            spectrum.acquire()
            onDispose { spectrum.release() }
        }
    }

    // How far the galaxy has turned on its axis, radians. It turns on its
    // own, slowly, the whole time the map is up.
    var spin by remember { mutableFloatStateOf(0f) }

    /**
     * Glides the camera to a [goal] and distance: a straight line in space,
     * distance eased on a log scale (so a zoom feels the same at every depth),
     * pulled back mid-flight in proportion to how far it is going — the way you
     * see where you are headed. The goal is asked again every frame, because a
     * star moves while the layout morphs and a planet is always moving.
     * [arrive] runs on landing, to say what the camera follows from there.
     */
    fun glide(
        goal: (FloatArray) -> Unit,
        toDistance: Float,
        toPitch: Float = camera.pitch,
        arrive: () -> Unit = {},
    ) {
        travel?.cancel()
        camera.follow = -1
        camera.followPlanet = -1
        val fromX = camera.targetX; val fromY = camera.targetY; val fromZ = camera.targetZ
        val fromD = ln(camera.distance); val toD = ln(toDistance)
        val fromPitch = camera.pitch
        goal(scratch)
        val span = hypot(hypot(scratch[0] - fromX, scratch[1] - fromY), scratch[2] - fromZ)
        val arc = (span / GalaxyScene.RADIUS).coerceIn(0f, MAX_ARC)
        val blurs = !lowPower && visuals.travelBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        travel = scope.launch {
            try {
                if (!instant) {
                    animate(0f, 1f, animationSpec = tween(TRAVEL_MILLIS, easing = TravelEasing)) { p, _ ->
                        goal(scratch)
                        camera.targetX = fromX + (scratch[0] - fromX) * p
                        camera.targetY = fromY + (scratch[1] - fromY) * p
                        camera.targetZ = fromZ + (scratch[2] - fromZ) * p
                        val lift = sin(PI.toFloat() * p)
                        camera.distance = exp(fromD + (toD - fromD) * p) * (1f + arc * lift)
                        camera.pitch = fromPitch + (toPitch - fromPitch) * p
                        travelBlur = if (blurs) maxBlurPx * lift else 0f
                    }
                }
                goal(scratch)
                camera.targetX = scratch[0]; camera.targetY = scratch[1]; camera.targetZ = scratch[2]
                camera.distance = toDistance
                camera.pitch = toPitch
                arrive()
            } finally {
                travelBlur = 0f
            }
        }
    }

    /** tan of half the view's width, for fitting something across it. */
    fun tanHalfWidth(): Float =
        if (viewport == IntSize.Zero) 0.28f
        else kotlin.math.tan(Math.toRadians(GalaxyCamera.FOV_DEGREES / 2.0)).toFloat() * viewport.width / viewport.height

    fun overviewDistance(): Float =
        if (viewport == IntSize.Zero) {
            camera.distance
        } else {
            GalaxyCamera.fitDistance(
                GalaxyScene.RADIUS,
                viewport.width / viewport.height.toFloat(),
                fill = if (timeline) GalaxyCamera.SPIRAL_FILL else GalaxyCamera.DISC_FILL,
            )
        }

    fun recentre() = glide(
        goal = { it[0] = 0f; it[1] = 0f; it[2] = 0f },
        toDistance = overviewDistance(),
        toPitch = GalaxyCamera.OVERVIEW_PITCH,
    )

    // The first measurement frames the whole galaxy, before anything flies.
    val framed = remember { BooleanArray(1) }
    LaunchedEffect(viewport) {
        if (viewport != IntSize.Zero && !framed[0]) {
            framed[0] = true
            camera.distance = overviewDistance()
        }
    }

    // Near enough that the star's planets fill the width when they come.
    fun starDistance(): Float = maxOf(GALAXY_ARRIVE_DISTANCE, PlanetSystem.MAX_REACH * 1.1f / tanHalfWidth())

    fun travelTo(id: String) {
        val s = scene ?: return
        val i = s.index[id] ?: return
        glide(
            goal = { s.position(i, morph.value, it, 0) },
            toDistance = starDistance(),
            arrive = { camera.follow = i },
        )
    }

    /** Planet [p] of the selected star's system, near enough to read its moons. */
    fun travelToPlanet(p: Int) {
        val s = scene ?: return
        val sys = system ?: return
        val i = s.index[sys.genreId] ?: return
        val planet = sys.planets.getOrNull(p) ?: return
        val centre = FloatArray(3)
        glide(
            goal = { out ->
                s.position(i, morph.value, centre, 0)
                sys.planetPosition(p, clock, centre[0], centre[1], centre[2], out, 0)
            },
            toDistance = (planet.reach * 1.25f / tanHalfWidth()).coerceIn(GalaxyCamera.MIN_DISTANCE, 90f),
            arrive = { camera.follow = i; camera.followPlanet = p },
        )
    }

    // Selecting a genre — by tapping it, from a chip in the panel, or by
    // Surprise — travels there. Keyed on the id, so re-selecting the same
    // genre does not travel again.
    LaunchedEffect(selected?.id, scene, viewport != IntSize.Zero) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        selected?.let { travelTo(it.id) }
    }

    // The clock: twinkle, the galaxy's turn, and keeping the camera on a
    // star that is moving because the layout is. (The turn needs nothing
    // here: the camera follows a star in the galaxy's own coordinates, and
    // those do not change as it turns — see CameraFrame.)
    LaunchedEffect(scene, alive) {
        val s = scene ?: return@LaunchedEffect
        val p = FloatArray(3)
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                val moving = travel?.isActive == true
                val follow = camera.follow
                if (!moving && follow >= 0) {
                    s.position(follow, morph.value, p, 0)
                    val sys = system
                    val planet = camera.followPlanet
                    if (planet >= 0 && sys != null && s.index[sys.genreId] == follow && planet < sys.planets.size) {
                        sys.planetPosition(planet, clock, p[0], p[1], p[2], p, 0)
                    }
                    camera.targetX = p[0]; camera.targetY = p[1]; camera.targetZ = p[2]
                }
                if (alive) {
                    clock += dt
                    spin += dt * GALAXY_TURN_RAD_S * liveVisuals.value.spin
                }
                bands.gain = liveVisuals.value.reactivity
                if (listening) bands.update(spectrum.spectrumBins.value, dt) else bands.quiet(dt)
            }
        }
    }

    // Deep space is dark in every theme, so the status bar's icons are light
    // while the map is up.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val had = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { had?.let { controller?.isAppearanceLightStatusBars = it } }
    }

    // Back puts the genre away before it leaves the map; so does a tap on
    // empty space. The dock has no close of its own.
    androidx.activity.compose.BackHandler(enabled = selected != null || settingsOpen) {
        if (settingsOpen) settingsOpen = false else viewModel.selectOnMap(null)
    }

    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GALAXY_SPACE)
            .onSizeChanged { viewport = it },
    ) {
        if (scene != null) {
            GenreGalaxyView(
                scene = scene,
                camera = camera,
                morph = { morph.value },
                time = { clock },
                explored = explored,
                hearted = hearted,
                hereId = here?.id,
                selectedId = selected?.id,
                familyColors = familyColors,
                hazeState = mapHaze,
                reserveTopPx = topChromePx.toFloat(),
                reserveBottomPx = reserveBottom,
                travelBlurPx = { travelBlur },
                spin = { spin },
                rays = !lowPower && visuals.godRays,
                spaceShader = !lowPower && visuals.deepSky,
                smoke = !lowPower && visuals.smoke,
                bands = if (listening) bands else null,
                visuals = visuals,
                labelStyle = labelStyle,
                hereLabel = stringResource(R.string.galaxy_you_are_here),
                system = system.takeIf { visuals.planets },
                systemAppear = { systemAppear.value },
                onTapPlanet = { p -> travelToPlanet(p) },
                // A moon is a track: tapping it plays it.
                onTapMoon = { p, m ->
                    system?.planets?.getOrNull(p)?.moons?.getOrNull(m)?.let {
                        viewModel.playChartEntry(it.entry, playerViewModel)
                    }
                },
                onTapEmpty = { viewModel.selectOnMap(null) },
                onTap = { id ->
                    // The same star again is a way back to it after looking
                    // around; selecting it would change nothing.
                    if (id == selected?.id) travelTo(id) else viewModel.selectOnMap(id)
                },
                onInteract = {
                    // Touching the map takes it back from any camera move in
                    // progress — being steered while you are trying to steer
                    // is the worst kind of animation.
                    travel?.cancel()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { topChromePx = it.height },
        ) {
            TopAppBar(
                // The genre you are at, once there is one: the title is where
                // the panel's heading went.
                title = {
                    Text(
                        text = selected?.name ?: stringResource(R.string.genre_galaxy),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackSafe() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val node = selected
                    if (node == null) {
                        IconButton(onClick = { viewModel.surpriseMe() }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.galaxy_surprise))
                        }
                    } else {
                        // Keeping a genre pins it to Discover's genre rail.
                        val isHearted = node.id in hearted
                        IconButton(onClick = { viewModel.toggleHeartGenre(node.id) }) {
                            Icon(
                                if (isHearted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(if (isHearted) R.string.genre_unkeep else R.string.genre_keep),
                            )
                        }
                        // Its researched history, as the dock's sheet.
                        IconButton(onClick = {
                            if (chartOpen) viewModel.toggleMapChart()
                            viewModel.toggleMapExpanded()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = if (expanded) stringResource(R.string.hide_history)
                                else stringResource(R.string.read_history_of, node.name),
                                tint = if (expanded) familyColors[node.family] ?: GALAXY_INK else GALAXY_INK,
                            )
                        }
                    }
                    // The galaxy's look, tuned on the map itself.
                    IconButton(onClick = { settingsOpen = !settingsOpen }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.galaxy_look),
                            tint = if (settingsOpen) MaterialTheme.colorScheme.primary else GALAXY_INK,
                        )
                    }
                    IconButton(onClick = { recentre() }) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.recentre))
                    }
                },
                // Light on the dark sky whatever the theme: the map is space,
                // not a page in the app's colours.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = GALAXY_INK,
                    titleContentColor = GALAXY_INK,
                    actionIconContentColor = GALAXY_INK,
                ),
            )
            // What a star's place and size mean in this layout. Without it a
            // star's size is a claim with no stated units.
            // Under the title: the selected genre's facts, or what a star's
            // place and size mean in this layout — without it a star's size is
            // a claim with no stated units.
            val node = selected
            val facts = node?.let {
                listOfNotNull(
                    graph.family(it.family)?.name ?: it.family,
                    if (it.hasTempo) stringResource(R.string.shelf_tempo, it.bpmLow, it.bpmHigh) else null,
                    it.era.getOrNull(0)?.let { year -> stringResource(R.string.discover_genre_since, year) },
                ).joinToString(" · ")
            }
            Text(
                text = when {
                    facts != null && system != null && visuals.planets ->
                        facts + "\n" + stringResource(R.string.galaxy_planets_caption)
                    facts != null -> facts
                    timeline -> stringResource(R.string.galaxy_time_caption)
                    else -> stringResource(R.string.map_weight_popularity)
                },
                style = MaterialTheme.typography.labelSmall,
                color = GALAXY_INK.copy(alpha = 0.7f),
                modifier = Modifier.padding(
                    start = MonoDimens.spacingLg,
                    end = MonoDimens.spacingLg,
                    bottom = MonoDimens.spacingSm,
                ),
            )
        }

        if (settingsOpen && graph.size > 0) {
            CompositionLocalProvider(
                LocalPlayerGlass provides glassSettings,
                tf.monochrome.android.ui.components.LocalGlassBackdrop provides mapHaze,
            ) {
                tf.monochrome.android.ui.discover.galaxy.GalaxyLookSheet(
                    visuals = visuals,
                    onChange = viewModel::setGalaxyVisuals,
                    lowPower = lowPower,
                    hazeState = mapHaze,
                    glass = glassSettings,
                    // Half of what is between the title and the mini player, so
                    // the galaxy it is tuning stays in view above it.
                    maxHeight = with(density) {
                        ((viewport.height - topChromePx - panelBottomInset.toPx()) * 0.5f)
                            .coerceAtLeast(MIN_HISTORY_HEIGHT.toPx()).toDp()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { lookHeightPx = it.height }
                        .padding(bottom = panelBottomInset)
                        .consumeWindowInsets(WindowInsets.navigationBars),
                )
            }
        } else if (selected == null && graph.size > 0) {
            GalaxyHud(
                timeline = timeline,
                onTimeline = {
                    if (it != timeline) {
                        timeline = it
                        // Looking at the whole galaxy, the framing changes with
                        // it: the spiral reaches further out than the disc.
                        if (camera.follow < 0 && travel?.isActive != true) recentre()
                    }
                },
                exploredCount = explored.size,
                total = graph.size,
                hazeState = mapHaze,
                glass = glassSettings,
                onSurprise = { viewModel.surpriseMe() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { hudHeightPx = it.height }
                    .padding(bottom = panelBottomInset)
                    .consumeWindowInsets(WindowInsets.navigationBars),
            )
        }

        if (!settingsOpen) selected?.let { node ->
            val related = remember(graph, node.id) { relatedTo(graph, node) }
            // The shader modifier reads its parameters from this local, so
            // the dock has to provide it — this route sits outside the
            // nav host's provider, which only wraps the mini player.
            CompositionLocalProvider(
                LocalPlayerGlass provides glassSettings,
                tf.monochrome.android.ui.components.LocalGlassBackdrop provides mapHaze,
            ) {
                GenreDock(
                    node = node,
                    related = related.nodes.take(MAX_RELATED_PILLS),
                    familyColor = familyColors[node.family] ?: MaterialTheme.colorScheme.primary,
                    hazeState = mapHaze,
                    glass = glassSettings,
                    expanded = expanded,
                    history = history,
                    chartOpen = chartOpen,
                    chart = chart,
                    // The sheet is as tall as the map lets it be and then
                    // scrolls, measured between the title and the mini player:
                    // counting the bottom chrome as room is how the old panel
                    // climbed under the status bar.
                    sheetMaxHeight = with(density) {
                        val canvas = viewport.height - topChromePx - panelBottomInset.toPx()
                        minOf(
                            canvas * HISTORY_HEIGHT_FRACTION,
                            canvas - DOCK_PILLS_RESERVE.toPx() - MIN_MAP_STRIP.toPx(),
                        ).coerceAtLeast(MIN_HISTORY_HEIGHT.toPx()).toDp()
                    },
                    onPlay = { viewModel.playGenre(node.id, playerViewModel) },
                    onRadio = { viewModel.radioGenre(node.id, playerViewModel) },
                    onToggleChart = {
                        // One sheet at a time: the dock is meant to stay small.
                        if (expanded) viewModel.toggleMapExpanded()
                        viewModel.toggleMapChart()
                    },
                    onPlayChartEntry = { viewModel.playChartEntry(it, playerViewModel) },
                    onRelated = { child -> viewModel.selectOnMap(child.id) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Measured outside the reserve, not inside it: the
                        // camera centres a genre in what's left of the map, and
                        // the mini player occludes that too.
                        .onSizeChanged { panelHeightPx = it.height }
                        .padding(bottom = panelBottomInset)
                        .consumeWindowInsets(WindowInsets.navigationBars),
                )
            }
        }

        if (graph.size == 0) {
            Text(
                text = stringResource(R.string.genre_map_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = GALAXY_INK.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Dust grains on a device that asked for less work. */
private const val LOW_POWER_DUST = 1600

private const val TRAVEL_MILLIS = 1700
private const val MORPH_MILLIS = 1700
private const val RESERVE_MILLIS = 420
private const val SYSTEM_APPEAR_MILLIS = 900

/** Ease in and out, cubic: the travel starts gently and lands gently. */
private val TravelEasing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/** Most a long journey pulls back mid-flight, as a share of its distance. */
private const val MAX_ARC = 1.2f

/** The blur at the middle of a journey. Light: a sense of speed, not a smear. */
private val TRAVEL_BLUR = 6.dp

/** How fast the galaxy turns on its axis: once round in about three and a half minutes. */
private const val GALAXY_TURN_RAD_S = 0.03f

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

        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val base = related.size
            GlyphPill(Icons.Default.PlayArrow, stringResource(R.string.play_top), onPlay, hazeState, Modifier.popIn(node.id, base))
            GlyphPill(Icons.Default.Radio, stringResource(R.string.tab_radio), onRadio, hazeState, Modifier.popIn(node.id, base + 1))
            GlyphPill(
                Icons.Default.BarChart,
                stringResource(R.string.top_100),
                onToggleChart,
                hazeState,
                Modifier.popIn(node.id, base + 2),
                selected = chartOpen,
                accent = familyColor,
            )
        }
    }
}

/** One of the dock's actions: a glyph in a pill of glass, nothing else. */
@Composable
private fun GlyphPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    tf.monochrome.android.ui.components.GlassPill(
        onClick = onClick,
        hazeState = hazeState,
        height = GLYPH_PILL_HEIGHT,
        onClickLabel = label,
        modifier = modifier.width(GLYPH_PILL_WIDTH),
    ) {
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.5.dp, accent.copy(alpha = 0.85f), CircleShape),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Pops a pill in when the genre it belongs to is selected: from a little small
 * and clear to full size with a spring's overshoot, [index] steps after the
 * first so a row arrives one pill at a time. Still with reduced motion.
 */
@Composable
private fun Modifier.popIn(key: Any, index: Int): Modifier {
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

/**
 * How much of the map the expanded history may take before it starts scrolling.
 *
 * Just over half: enough that a genre's origins and first section are on screen
 * together, and little enough that the map — including the dot the panel is
 * about, which the camera keeps in the remaining strip — is still visibly there
 * behind it. A panel that covers everything is a page, and this is not a page.
 */
private const val HISTORY_HEIGHT_FRACTION = 0.52f

/** Roughly what the dock's two rows of pills take, under its sheet. */
private val DOCK_PILLS_RESERVE = 130.dp

/** At most this many genres next to the selected one, as pills. */
private const val MAX_RELATED_PILLS = 3

private val GLYPH_PILL_WIDTH = 72.dp
private val GLYPH_PILL_HEIGHT = 48.dp

/** How long after the previous pill each pops in. */
private const val POP_STAGGER_MILLIS = 45L

/** Below this the history isn't worth opening, so it scrolls in a smaller box. */
private val MIN_HISTORY_HEIGHT = 120.dp

/**
 * The map the panel always leaves showing under the title: enough to see
 * the genre it is about, and what keeps the panel's top row, close and all,
 * clear of the title bar and the status bar above it.
 */
private val MIN_MAP_STRIP = 96.dp


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

/** The dashes of a hearted genre's ring. */
private val HEART_RING = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

/**
 * The galaxy's controls, on the map's glass when no genre is open: Galaxy or
 * Timeline (the disc or the spiral), how much of it the listener has explored,
 * what lit, ringed and dark mean, and a way somewhere new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalaxyHud(
    timeline: Boolean,
    onTimeline: (Boolean) -> Unit,
    exploredCount: Int,
    total: Int,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    tf.monochrome.android.ui.components.GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    listOf(false to R.string.galaxy_layout_galaxy, true to R.string.galaxy_layout_timeline)
                        .forEachIndexed { index, (option, label) ->
                            SegmentedButton(
                                selected = timeline == option,
                                onClick = { onTimeline(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(stringResource(label), maxLines = 1) },
                            )
                        }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onSurprise) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.galaxy_surprise), maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.discover_galaxy_count, exploredCount, total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendMark(LegendKind.LIT)
                LegendText(stringResource(R.string.galaxy_legend_lit))
                LegendMark(LegendKind.HEARTED)
                LegendText(stringResource(R.string.galaxy_legend_hearted))
                LegendMark(LegendKind.DARK)
                LegendText(stringResource(R.string.galaxy_legend_dark))
            }
        }
    }
}

private enum class LegendKind { LIT, HEARTED, DARK }

/** The legend's marks, drawn the way the map draws them. */
@Composable
private fun LegendMark(kind: LegendKind) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(14.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.24f
        when (kind) {
            LegendKind.LIT -> {
                drawCircle(color.copy(alpha = 0.3f), radius = r * 2f, center = c)
                drawCircle(color, radius = r, center = c)
            }
            LegendKind.HEARTED -> {
                drawCircle(color.copy(alpha = 0.5f), radius = r * 0.85f, center = c)
                drawCircle(color, radius = r + 3f, center = c, style = Stroke(width = 1.6f, pathEffect = HEART_RING))
            }
            LegendKind.DARK -> drawCircle(color.copy(alpha = 0.4f), radius = r * 0.85f, center = c)
        }
    }
}

@Composable
private fun LegendText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, end = 10.dp),
    )
}
