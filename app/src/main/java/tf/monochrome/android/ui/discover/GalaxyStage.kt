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
import tf.monochrome.android.ui.discover.galaxy.MoonRef
import tf.monochrome.android.ui.discover.galaxy.StarField
import tf.monochrome.android.domain.model.GalaxyVisualSettings
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Stable
import kotlin.math.exp

/**
 * What every galaxy screen shares, whatever its stars are: the map itself,
 * the camera and its glides, the clock, full screen and the phone's turn, the
 * glass over the map, the look sheet, and the title bar. The genre map
 * ([GenreMapScreen]) and the library map are two screens on this one stage;
 * each brings its own stars ([StarField]), its own system, its own actions in
 * the title bar, and its own panels — the dock, the HUD, a star's system as a
 * list — through the slots.
 *
 * Everything here moved out of the genre map as it was, so every rule in
 * `docs/ui-invariants.md` about "The genre galaxy" holds for both: drawn on
 * Compose canvases, sizes in dp, the reserves measured, nothing running while
 * nobody can see it.
 *
 * Slots get a [GalaxyStageScope]: what the stage knows, and what it can do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalaxyStage(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    /** The stars, or null for none: nothing to draw yet, or nothing to draw at all. */
    field: StarField?,
    familyColors: Map<String, Color>,
    visuals: GalaxyVisualSettings,
    onVisuals: (GalaxyVisualSettings) -> Unit,
    selectedId: String?,
    hereId: String?,
    explored: Set<String>,
    hearted: Set<String>,
    /** The selected (or followed) star's planets, at their own size; the stage applies the listener's. */
    system: PlanetSystem?,
    /** The title bar's title: the selected star's name, or the map's. */
    title: String,
    /** The line under the title. */
    caption: @Composable (GalaxyStageScope) -> String,
    /** Said in the middle of an empty sky, when [field] is null. */
    emptyText: String?,
    /** A star tapped (its id), or empty space (null). */
    onSelect: (String?) -> Unit,
    /** A long press on a star, or a body of its system: the screen plays it, then usually [GalaxyStageScope.openSheet]. */
    onLongPress: (GalaxyStageScope, String) -> Unit,
    /** A moon tapped on the map. */
    onPlayMoon: (MoonRef) -> Unit,
    /** The title bar's actions, in order; the stage's own are on the scope ([GalaxyStageScope.fullScreenAction] and so on). */
    actions: @Composable (GalaxyStageScope) -> List<MapAction>,
    /** The open star's system as a list, for the star whose id is given. Use [GalaxyStageScope.sheetModifier]. */
    sheet: @Composable BoxScope.(GalaxyStageScope, String) -> Unit,
    /** The panel at the bottom when nothing else is open: the HUD, or the selected star's dock. Use [GalaxyStageScope.panelModifier]. */
    panel: @Composable BoxScope.(GalaxyStageScope) -> Unit,
    /**
     * Under the title and its caption, in the top chrome: a search bar. Its
     * height counts in the top reserve, so the camera centres a star below it
     * and no name is drawn under its glass.
     */
    underTitle: @Composable ColumnScope.(GalaxyStageScope) -> Unit = {},
) {
    val density = LocalDensity.current
    val instant = reduceMotion()
    val lowPower = LocalLowPerformance.current.disableLiquidGlass
    // Whether anyone can see the map: started means on screen, in front. When
    // the app goes to the background, the screen turns off, or another screen
    // covers this one, every frame of work below stops — the clock, so the
    // shaders, the twinkle and the turn; and the music tap with it.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateAsState()
    val seen = lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    // Twinkle and the galaxy's turn redraw every frame, so they go with the rest
    // of the motion when the device or the listener asks for less.
    val alive = !instant && !lowPower && seen

    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Full screen: nothing on the map but the map. Turning the phone on its
    // side goes there, and turning it back comes out; the button in the bar
    // goes there upright, and Back comes out.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var manualFullScreen by rememberSaveable { mutableStateOf(false) }
    val fullScreen = manualFullScreen || landscape
    val wasLandscape = remember { BooleanArray(1) { landscape } }
    LaunchedEffect(landscape) {
        if (wasLandscape[0] && !landscape) manualFullScreen = false
        wasLandscape[0] = landscape
    }
    // The map turns with the phone, even with rotation locked — that is how
    // full screen is reached — and the app goes back to its own rule after.
    // Borrowed, not saved and restored here: switching galaxies overlaps two
    // stages, and the one leaving must not undo the one arriving (WindowLoan).
    tf.monochrome.android.ui.components.BorrowOrientation(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR,
    )
    tf.monochrome.android.ui.components.HideAppChrome(fullScreen)
    tf.monochrome.android.ui.main.SystemBarsHidden(fullScreen)
    // The mini player and the tab bar float over space here, whatever the theme.
    tf.monochrome.android.ui.components.AppChromeGround(GALAXY_SPACE)

    // The star a long press opened as a list of its planets, while it is still
    // the one selected.
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

    val dustCount = when {
        lowPower -> LOW_POWER_DUST
        visuals.dust == tf.monochrome.android.domain.model.GalaxyAmount.LESS -> GalaxyScene.DEFAULT_DUST * 2 / 5
        visuals.dust == tf.monochrome.android.domain.model.GalaxyAmount.MORE -> GalaxyScene.DEFAULT_DUST * 3 / 2
        else -> GalaxyScene.DEFAULT_DUST
    }
    val scene = remember(field, dustCount) {
        if (field == null || field.size == 0) null else GalaxyScene(field, dustCount = dustCount)
    }
    val camera = remember { GalaxyCamera() }

    // The disc unless the map switched from was showing the spiral.
    var timeline by rememberSaveable { mutableStateOf(GalaxyHandoff.peekTimeline()) }
    // 0 the galaxy, 1 the timeline. Read through the state, never in
    // composition, so the morph redraws the map without recomposing the screen.
    val morph = animateFloatAsState(
        targetValue = if (timeline) 1f else 0f,
        animationSpec = if (instant) snap() else tween(MORPH_MILLIS, easing = FastOutSlowInEasing),
        label = "galaxyMorph",
    )

    val glassSettings by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    // The system at the listener's planet and moon size. Everything downstream
    // reads this one — the drawing, the taps, the zoom's stop short of a
    // planet — so they all agree on how big a world is.
    val sized = remember(system, visuals.bodySize) { system?.withBodySize(visuals.bodySize) }
    // Read by the clock and the glides, which outlive the composition that started them.
    val liveSystem = rememberUpdatedState(sized)
    val sheetOpen = sheetFor != null && sheetFor == selectedId

    // Planets grow out of their star when they arrive, rather than popping
    // into orbit.
    val systemAppear = remember { Animatable(0f) }
    // Keyed on the star, not the system: the system is rebuilt as its
    // planets' sizes come in, and that must not make them grow out again.
    LaunchedEffect(sized?.starId) {
        if (sized == null || instant) {
            systemAppear.snapTo(if (sized == null) 0f else 1f)
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
    // The bar as it stands now, folded or open, and following its fold: the
    // open bar's height left a tab bar's worth of empty space under every
    // panel while it was folded.
    val panelBottomInset by animateDpAsState(
        targetValue = tf.monochrome.android.ui.navigation.LocalBottomChromeNow.current,
        animationSpec = if (instant) snap() else tween(CHROME_FOLD_MILLIS),
        label = "galaxyPanelInset",
    )

    // Measured rather than assumed: the bar moves with font scale and the
    // display cutout, the panel with whatever it is showing.
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var topChromePx by remember { mutableIntStateOf(0) }
    var lookHeightPx by remember { mutableIntStateOf(0) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var panelHeightPx by remember { mutableIntStateOf(with(density) { 230.dp.roundToPx() }) }

    // The middle of the view is the middle of what the panels leave visible,
    // or every star you look at would sit under its own panel. Eased, so the
    // star you are reading about slides up as the panel grows instead of
    // jumping.
    val reserveBottom by animateFloatAsState(
        targetValue = with(density) { panelBottomInset.toPx() } +
            when {
                sheetOpen -> sheetHeightPx
                fullScreen -> 0
                settingsOpen -> lookHeightPx
                else -> panelHeightPx
            },
        animationSpec = if (instant) snap() else tween(RESERVE_MILLIS, easing = FastOutSlowInEasing),
        label = "galaxyReserve",
    )

    // The planet the camera went to, while it is still there: a screen can
    // offer what that planet is (an album, say) in its dock.
    var focusedPlanet by remember { mutableIntStateOf(-1) }

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

    // Idle frame pacing, fed by every touch on the map and its panels.
    val pacer = remember { GalaxyPacer() }

    // Glass is over the map unless it is in full screen with nothing open.
    // Let go of a moment after the chrome has faded, not as it starts to: the
    // fading panes still read the map until they are gone.
    val wantsGlass = !fullScreen || sheetOpen
    var glassOver by remember { mutableStateOf(true) }
    LaunchedEffect(wantsGlass) {
        if (wantsGlass) {
            glassOver = true
        } else {
            kotlinx.coroutines.delay(GLASS_FADE_MILLIS)
            glassOver = false
        }
    }

    // Lighter glass, when asked for: every pane over the map, the bars
    // included, runs its lens at half resolution (see LocalLensDivisor).
    val lensDivisor = if (visuals.lightGlass) LIGHT_GLASS_DIVISOR else 1
    tf.monochrome.android.ui.components.AppChromeLens(lensDivisor)
    androidx.compose.runtime.SideEffect { GalaxyProbe.lensDivisor = lensDivisor }

    // One line in the debug log every few seconds while the map is up: the
    // frame times and what the galaxy drew (Settings › Debug Log).
    GalaxyFrameStats { seconds ->
        GalaxyFrameStats.passes(
            seconds,
            smokeOn = !lowPower && visuals.smoke,
            raysOn = !lowPower && visuals.godRays,
        ) + " | " + buildString {
            append(if (fullScreen) "full screen" else "chrome")
            if (sheetOpen) append(", system sheet")
            if (settingsOpen) append(", look sheet")
            append(if (glassOver) ", glass reads map" else ", direct")
            if (!alive) append(", still")
        }
    }

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
        focusedPlanet = -1
        camera.follow = -1
        camera.followPlanet = -1
        camera.nearLimit = GalaxyCamera.MIN_DISTANCE
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
            // Arriving from the other map by its switch: the same view of the
            // same sky, since both are laid out in one frame. Otherwise the
            // whole galaxy.
            val pose = GalaxyHandoff.take()
            if (pose != null) {
                camera.targetX = pose[0]; camera.targetY = pose[1]; camera.targetZ = pose[2]
                camera.distance = pose[3]; camera.yaw = pose[4]; camera.pitch = pose[5]
            } else {
                camera.distance = overviewDistance()
            }
        }
    }

    // The star's own size: what its system is laid out round.
    fun starRadiusOf(i: Int): Float = scene?.let { GalaxyScene.starRadius(it.prominence[i]) } ?: PlanetSystem.DEFAULT_STAR_RADIUS

    // Near enough that the star's planets fill the width when they come — a
    // giant's system is wide, a dwarf's tight, so each is framed for its own.
    fun starDistance(i: Int): Float {
        val limits = scene?.starField?.systemLimits ?: tf.monochrome.android.ui.discover.galaxy.SystemLimits.CHART
        // The planets, not the belt past them: framed for the belt, every
        // world came out smaller, and the belt's grains can run off the edge.
        return maxOf(GALAXY_ARRIVE_DISTANCE, PlanetSystem.reachFor(starRadiusOf(i), limits.copy(belt = false)) * 1.1f / tanHalfWidth())
    }

    fun travelTo(id: String) {
        val s = scene ?: return
        val i = s.index[id] ?: return
        glide(
            goal = { s.position(i, morph.value, it, 0) },
            toDistance = starDistance(i),
            arrive = {
                camera.follow = i
                // A pinch can come close, but never into the star.
                camera.nearLimit = starRadiusOf(i) * STAR_NEAR_RADII
            },
        )
    }

    /** Planet [p] of the selected star's system, near enough to read its moons. */
    fun travelToPlanet(p: Int) {
        val s = scene ?: return
        val sys = liveSystem.value ?: return
        val i = s.index[sys.starId] ?: return
        val planet = sys.planets.getOrNull(p) ?: return
        val centre = FloatArray(3)
        // The planet and its nearest moons fill the view; the outer moons pass
        // in and out of it. Framed for all of them, the world itself was a
        // small disc in the middle of its orbits.
        val framed = planet.moons.sortedBy { it.orbit }.getOrNull(PLANET_FRAME_MOON)?.let { it.orbit + it.radius } ?: planet.reach
        glide(
            goal = { out ->
                s.position(i, morph.value, centre, 0)
                sys.planetPosition(p, clock, centre[0], centre[1], centre[2], out, 0)
            },
            toDistance = (framed * 1.1f / tanHalfWidth()).coerceIn(planet.radius * PLANET_NEAR_RADII, 90f),
            arrive = {
                camera.follow = i
                camera.followPlanet = p
                camera.nearLimit = planet.radius * PLANET_NEAR_RADII
            },
        )
        focusedPlanet = p
    }

    // Another star's system is another set of planets.
    LaunchedEffect(sized?.starId) { if (camera.followPlanet < 0) focusedPlanet = -1 }

    val stage = GalaxyStageScope(
        scene = scene,
        system = sized,
        visuals = visuals,
        timeline = timeline,
        fullScreen = fullScreen,
        settingsOpen = settingsOpen,
        sheetOpen = sheetOpen,
        hazeState = mapHaze,
        glass = glassSettings,
        bottomInset = panelBottomInset,
        dockSheetMaxHeight = with(density) {
            // The sheet is as tall as the map lets it be and then scrolls,
            // measured between the title and the mini player: counting the
            // bottom chrome as room is how the old panel climbed under the
            // status bar.
            val canvas = viewport.height - topChromePx - panelBottomInset.toPx()
            minOf(
                canvas * HISTORY_HEIGHT_FRACTION,
                canvas - DOCK_PILLS_RESERVE.toPx() - MIN_MAP_STRIP.toPx(),
            ).coerceAtLeast(MIN_HISTORY_HEIGHT.toPx()).toDp()
        },
        sheetMaxHeight = with(density) {
            // At most half of what is left of the map, so the star it came
            // from stays in view above it.
            val top = if (fullScreen) 0f else topChromePx.toFloat()
            ((viewport.height - top - panelBottomInset.toPx()) * SYSTEM_SHEET_FRACTION)
                .coerceAtLeast(MIN_HISTORY_HEIGHT.toPx()).toDp()
        },
        panelModifier = Modifier
            // Measured outside the reserve, not inside it: the camera centres
            // a star in what's left of the map, and the mini player occludes
            // that too.
            .onSizeChanged { panelHeightPx = it.height }
            .padding(bottom = panelBottomInset)
            .consumeWindowInsets(WindowInsets.navigationBars),
        sheetModifier = Modifier
            .widthIn(max = MAX_SHEET_WIDTH)
            .onSizeChanged { sheetHeightPx = it.height }
            .padding(bottom = panelBottomInset)
            .consumeWindowInsets(WindowInsets.navigationBars),
        onTravelTo = { travelTo(it) },
        onRecentre = { recentre() },
        onTimeline = {
            if (it != timeline) {
                timeline = it
                // Looking at the whole galaxy, the framing changes with it:
                // the spiral reaches further out than the disc.
                if (camera.follow < 0 && travel?.isActive != true) recentre()
            }
        },
        onOpenSheet = { id ->
            settingsOpen = false
            if (id == selectedId) travelTo(id)
            sheetFor = id
        },
        onCloseSheet = { sheetFor = null },
        onFullScreen = {
            settingsOpen = false
            manualFullScreen = true
        },
        onToggleLook = { settingsOpen = !settingsOpen },
        focusedPlanet = focusedPlanet,
        onHandOff = {
            travel?.cancel()
            GalaxyHandoff.give(
                floatArrayOf(camera.targetX, camera.targetY, camera.targetZ, camera.distance, camera.yaw, camera.pitch),
                timeline,
            )
        },
    )

    // Selecting a star — by tapping it, from a pill, or by Surprise — travels
    // there. Keyed on the id, so re-selecting the same star does not travel
    // again.
    LaunchedEffect(selectedId, scene, viewport != IntSize.Zero) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        selectedId?.let { travelTo(it) }
    }

    // The clock: twinkle, the galaxy's turn, and keeping the camera on a
    // star that is moving because the layout is. (The turn needs nothing
    // here: the camera follows a star in the galaxy's own coordinates, and
    // those do not change as it turns — see CameraFrame.)
    LaunchedEffect(scene, alive, seen) {
        // Nobody is looking: no frames at all, so nothing redraws.
        if (!seen) return@LaunchedEffect
        val s = scene ?: return@LaunchedEffect
        val p = FloatArray(3)
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                GalaxyProbe.vsyncs++
                // At rest on a fast display, every other vsync moves nothing
                // (GalaxyPacer): nothing is redrawn for it, and the next one
                // moves by both vsyncs' time, since dt runs from the last
                // vsync that moved.
                val animating = travel?.isActive == true || systemAppear.isRunning ||
                    morph.value != (if (timeline) 1f else 0f)
                if (pacer.hold(now, animating)) {
                    GalaxyProbe.paced++
                    return@withFrameNanos
                }
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                val moving = travel?.isActive == true
                val follow = camera.follow
                if (!moving && follow >= 0) {
                    s.position(follow, morph.value, p, 0)
                    val sys = liveSystem.value
                    val planet = camera.followPlanet
                    if (planet >= 0 && sys != null && s.index[sys.starId] == follow && planet < sys.planets.size) {
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
    // while the map is up. Borrowed, for the same reason as the orientation.
    tf.monochrome.android.ui.components.BorrowLightStatusBars(light = false)

    // Back puts the star away before it leaves the map; so does a tap on
    // empty space. The dock has no close of its own.
    androidx.activity.compose.BackHandler(enabled = sheetOpen || settingsOpen || manualFullScreen || selectedId != null) {
        when {
            sheetOpen -> sheetFor = null
            settingsOpen -> settingsOpen = false
            manualFullScreen -> manualFullScreen = false
            else -> onSelect(null)
        }
    }

    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
    )

    CompositionLocalProvider(tf.monochrome.android.ui.player.LocalLensDivisor provides lensDivisor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GALAXY_SPACE)
                .onSizeChanged { viewport = it }
                // Every touch, on the map or on a panel over it, keeps it at full
                // frame rate; seen first and never consumed.
                .pointerInput(pacer) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            pacer.touch(pressed = event.changes.any { it.pressed })
                        }
                    }
                },
        ) {
            if (scene != null) {
                GenreGalaxyView(
                    scene = scene,
                    camera = camera,
                    morph = { morph.value },
                    time = { clock },
                    explored = explored,
                    hearted = hearted,
                    hereId = hereId,
                    selectedId = selectedId,
                    familyColors = familyColors,
                    hazeState = mapHaze,
                    reserveTopPx = if (fullScreen) 0f else topChromePx.toFloat(),
                    reserveBottomPx = reserveBottom,
                    travelBlurPx = { travelBlur },
                    spin = { spin },
                    rays = !lowPower && visuals.godRays,
                    spaceShader = !lowPower && visuals.deepSky,
                    glassOver = glassOver,
                    idle = { pacer.idle },
                    smoke = !lowPower && visuals.smoke,
                    bands = if (listening) bands else null,
                    visuals = visuals,
                    labelStyle = labelStyle,
                    hereLabel = stringResource(R.string.galaxy_you_are_here),
                    system = sized.takeIf { visuals.planets },
                    systemAppear = { systemAppear.value },
                    onTapPlanet = { p -> travelToPlanet(p) },
                    // A moon is a track: tapping it plays it.
                    onTapMoon = { p, m ->
                        sized?.planets?.getOrNull(p)?.moons?.getOrNull(m)?.let { onPlayMoon(it.ref) }
                    },
                    onTapEmpty = { onSelect(null) },
                    onTap = { id ->
                        // The same star again is a way back to it after looking
                        // around; selecting it would change nothing.
                        if (id == selectedId) travelTo(id) else onSelect(id)
                    },
                    onInteract = {
                        // Touching the map takes it back from any camera move in
                        // progress — being steered while you are trying to steer
                        // is the worst kind of animation.
                        travel?.cancel()
                    },
                    // A long press is "play this": the star's music starts, and its
                    // planets open as a list to pick from.
                    onLongPress = { id ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(stage, id)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = !fullScreen,
                enter = if (instant) EnterTransition.None else fadeIn(),
                exit = if (instant) ExitTransition.None else fadeOut(),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topChromePx = it.height },
            ) {
                // As many actions as leave the title room, the rest behind ⋮: a
                // narrow phone or a large font had the title squeezed to a few
                // letters by five icons.
                val all = actions(stage)
                val room = LocalConfiguration.current.screenWidthDp / LocalDensity.current.fontScale.coerceAtLeast(1f)
                val slots = ((room - NAV_ICON_DP - MIN_TITLE_DP) / ACTION_DP).toInt().coerceAtLeast(1)
                val inline = if (all.size <= slots) all else all.take(slots - 1)
                val overflow = all.drop(inline.size)
                TopAppBar(
                    // The star you are at, once there is one: the title is where
                    // the panel's heading went.
                    title = {
                        Text(
                            text = title,
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
                        inline.forEach { action ->
                            IconButton(onClick = action.onClick) {
                                Icon(action.icon, contentDescription = action.label, tint = action.tint)
                            }
                        }
                        if (overflow.isNotEmpty()) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.galaxy_more))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    overflow.forEach { action ->
                                        DropdownMenuItem(
                                            text = { Text(action.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = { Icon(action.icon, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                action.onClick()
                                            },
                                        )
                                    }
                                }
                            }
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
                // Under the title: the selected star's facts, or what a star's
                // place and size mean in this layout — without it a star's size
                // is a claim with no stated units.
                Text(
                    text = caption(stage),
                    style = MaterialTheme.typography.labelSmall,
                    color = GALAXY_INK.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = MonoDimens.spacingLg,
                        end = MonoDimens.spacingLg,
                        bottom = MonoDimens.spacingSm,
                    ),
                )
                underTitle(stage)
            }
            }

            // Full screen says how to use it, once, and then gets out of the way.
            var hint by remember { mutableStateOf(false) }
            LaunchedEffect(fullScreen) {
                hint = fullScreen
                if (fullScreen) {
                    kotlinx.coroutines.delay(FULL_SCREEN_HINT_MILLIS)
                    hint = false
                }
            }
            AnimatedVisibility(
                visible = hint && !sheetOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    stringResource(R.string.galaxy_full_screen_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = GALAXY_INK.copy(alpha = 0.85f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(GALAXY_SPACE.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            val openSheet = sheetFor
            if (sheetOpen && openSheet != null) {
                sheet(stage, openSheet)
            } else if (fullScreen) {
                // Nothing else in full screen.
            } else if (settingsOpen && scene != null) {
                CompositionLocalProvider(
                    LocalPlayerGlass provides glassSettings,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides mapHaze,
                ) {
                    tf.monochrome.android.ui.discover.galaxy.GalaxyLookSheet(
                        visuals = visuals,
                        onChange = onVisuals,
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
            } else {
                panel(stage)
            }

            if (scene == null && emptyText != null) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GALAXY_INK.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/**
 * What a screen on the [GalaxyStage] can read from it, and ask of it. Made
 * fresh each composition, so what it says is what the stage is now.
 */
@Stable
internal class GalaxyStageScope(
    val scene: GalaxyScene?,
    /** The system as drawn: at the listener's planet and moon size. */
    val system: PlanetSystem?,
    val visuals: GalaxyVisualSettings,
    val timeline: Boolean,
    val fullScreen: Boolean,
    val settingsOpen: Boolean,
    val sheetOpen: Boolean,
    val hazeState: HazeState,
    val glass: PlayerGlassSettings,
    /** The bar under the map as it stands now: what a panel sits on. */
    val bottomInset: Dp,
    /** How tall a dock's own sheet may grow before it scrolls. */
    val dockSheetMaxHeight: Dp,
    /** How tall a system sheet may grow before it scrolls. */
    val sheetMaxHeight: Dp,
    /** For the bottom panel, after its alignment: measures it for the camera's reserve and sits it on the bar. */
    val panelModifier: Modifier,
    /** The same for the system sheet. */
    val sheetModifier: Modifier,
    private val onTravelTo: (String) -> Unit,
    private val onRecentre: () -> Unit,
    private val onTimeline: (Boolean) -> Unit,
    private val onOpenSheet: (String) -> Unit,
    private val onCloseSheet: () -> Unit,
    private val onFullScreen: () -> Unit,
    private val onToggleLook: () -> Unit,
    /** The planet of [system] the camera went to and is still at, or -1. */
    val focusedPlanet: Int,
    private val onHandOff: () -> Unit,
) {
    /**
     * Leaves the camera where it is for the next map to pick up: call just
     * before switching to the other map, which opens on the same view.
     */
    fun handOff() = onHandOff()

    /** Flies to star [id]. */
    fun travelTo(id: String) = onTravelTo(id)

    /** Back out to the whole galaxy. */
    fun recentre() = onRecentre()

    /** The disc, or the spiral. */
    fun setTimeline(on: Boolean) = onTimeline(on)

    /** Opens star [id]'s system as a list, flying back to it if it is the one selected. */
    fun openSheet(id: String) = onOpenSheet(id)

    fun closeSheet() = onCloseSheet()

    /** The title bar's full screen action. */
    val fullScreenAction: MapAction
        @Composable get() = MapAction(Icons.Default.Fullscreen, stringResource(R.string.galaxy_full_screen), onClick = onFullScreen)

    /** The galaxy's look, tuned on the map itself. */
    val lookAction: MapAction
        @Composable get() = MapAction(
            Icons.Default.Tune,
            stringResource(R.string.galaxy_look),
            tint = if (settingsOpen) MaterialTheme.colorScheme.primary else GALAXY_INK,
            onClick = onToggleLook,
        )

    val recentreAction: MapAction
        @Composable get() = MapAction(Icons.Default.CenterFocusStrong, stringResource(R.string.recentre), onClick = onRecentre)
}

/** One of the top bar's actions, inline or behind ⋮ as the width allows. */
internal class MapAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val tint: Color = GALAXY_INK,
    val onClick: () -> Unit,
)

/** The bottom chrome's fold, which the panels over the map follow (TabChrome's own 240 ms). */
private const val CHROME_FOLD_MILLIS = 240

/** How long the chrome takes to fade out of full screen, with room: the galaxy's glass layer is kept until it has. */
private const val GLASS_FADE_MILLIS = 700L

/** The lens's resolution divisor with lighter glass on: half. */
private const val LIGHT_GLASS_DIVISOR = 2

/** Dust grains on a device that asked for less work. */
private const val LOW_POWER_DUST = 1600

/** The top bar's budget, dp: the back arrow, the least the title is squeezed to, and an action. */
private const val NAV_ICON_DP = 56f
private const val MIN_TITLE_DP = 136f
private const val ACTION_DP = 48f

/** The nearest a pinch brings the camera to a star, and to a planet, in their own radii. */
private const val STAR_NEAR_RADII = 2.2f
private const val PLANET_NEAR_RADII = 2.6f

/** Arriving at a planet frames it out to this moon (0 the nearest): the planet big, the rest of its moons round the edges. */
private const val PLANET_FRAME_MOON = 2

/** How long full screen's one hint stays up. */
private const val FULL_SCREEN_HINT_MILLIS = 3200L

/** The long-press sheet: at most this share of the map's height, and never wider than this. */
private const val SYSTEM_SHEET_FRACTION = 0.42f
private val MAX_SHEET_WIDTH = 560.dp

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
 * How much of the map a dock's sheet may take before it starts scrolling.
 *
 * Just over half: enough that a genre's origins and first section are on screen
 * together, and little enough that the map — including the dot the panel is
 * about, which the camera keeps in the remaining strip — is still visibly there
 * behind it. A panel that covers everything is a page, and this is not a page.
 */
private const val HISTORY_HEIGHT_FRACTION = 0.52f

/** Roughly what the dock's two rows of pills take, under its sheet. */
private val DOCK_PILLS_RESERVE = 130.dp

/** Below this the history isn't worth opening, so it scrolls in a smaller box. */
internal val MIN_HISTORY_HEIGHT = 120.dp

/**
 * The map the panel always leaves showing under the title: enough to see
 * the star it is about, and what keeps the panel's top row, close and all,
 * clear of the title bar and the status bar above it.
 */
private val MIN_MAP_STRIP = 96.dp

/**
 * The view the map that is closing leaves for the one that opens next, when
 * one switches to the other: the genre galaxy and the library galaxy are laid
 * out in one frame, so the same camera sees the same part of the sky in both.
 * Taken once; a map opened any other way frames the whole galaxy.
 */
internal object GalaxyHandoff {
    private var pose: FloatArray? = null
    private var timeline = false

    /** The camera's target (x, y, z), distance, yaw and pitch, and which layout was showing. */
    fun give(pose: FloatArray, timeline: Boolean) {
        this.pose = pose
        this.timeline = timeline
    }

    /** Whether the map handing over was showing the spiral; false with nothing handed over. */
    fun peekTimeline(): Boolean = pose != null && timeline

    fun take(): FloatArray? = pose.also { pose = null }
}
