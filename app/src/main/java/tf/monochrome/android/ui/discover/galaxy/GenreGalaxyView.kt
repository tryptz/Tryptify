package tf.monochrome.android.ui.discover.galaxy

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** The sky's base: deep space, the same in every theme — the galaxy is a window, not a page. */
internal val GALAXY_SPACE = Color(0xFF04060E)
internal val GALAXY_INK = Color(0xFFEEF0FF)
private val SCAN = Color(0xFF86F6FF)
private val WARM = Color(0xFFFFCF7A)
private val CORE_WARM = Color(0xFFFFD9A8)
private val HALO_STAR = Color(0x66D8DEFF)

/**
 * The genre galaxy, drawn in 3D on Compose canvases.
 *
 * Canvas rather than OpenGL because the map's panels are glass: a haze pane
 * can only frost what is drawn in this window's own layers, and a SurfaceView
 * or GLSurfaceView is not — glass over one paints as a flat slab. So the
 * projection is done here, per frame, for 771 genres and a few thousand grains
 * of dust, and drawn in batches: one `drawPoints` per family and depth band,
 * one `drawLines` per family, a sprite per star.
 *
 * Layers, back to front, all inside the panels' haze source:
 * 1. the deep sky — the far stars, in a layer of its own that keeps its
 *    pixels: redrawn only when the camera turns or the twinkle steps;
 * 2. the galaxy — nebulae, the core, family links, dust, the timeline's year
 *    rings and the path from "you are here" to what you have selected, with
 *    the gas over it at a third of the resolution;
 * 3. the light — the god rays ([GalaxyLight]): the core's, and the star's
 *    whose planets are showing, each a light pass with everything in the
 *    light's way drawn over it in black, so its shadows stream out with it;
 * 4. the stars on top — twinkling glow sprites, the lock-on bracket, "you are
 *    here", and the labels, in the app's own font.
 *
 * Every size is in dp, so the map is the size it should be on any screen —
 * the old map's raw-pixel dots came out a third of the size on a phone.
 */
@Composable
internal fun GenreGalaxyView(
    scene: GalaxyScene,
    camera: GalaxyCamera,
    morph: () -> Float,
    time: () -> Float,
    explored: Set<String>,
    hearted: Set<String>,
    hereId: String?,
    selectedId: String?,
    familyColors: Map<String, Color>,
    hazeState: HazeState,
    reserveTopPx: Float,
    reserveBottomPx: Float,
    travelBlurPx: () -> Float,
    spin: () -> Float,
    rays: Boolean,
    spaceShader: Boolean,
    /**
     * Whether any glass is over the map, reading it for its backdrop; with
     * none (full screen, nothing open) the galaxy draws straight to the screen.
     */
    glassOver: Boolean = true,
    /** Whether the map is resting: nobody steering it and no camera move under way. */
    idle: () -> Boolean = { false },
    /** The galaxy's gas, moved by [bands]; off on devices that asked for less. */
    smoke: Boolean = false,
    bands: AudioBands? = null,
    /** The listener's look for the map (the parts the view draws itself). */
    visuals: tf.monochrome.android.domain.model.GalaxyVisualSettings =
        tf.monochrome.android.domain.model.GalaxyVisualSettings.DEFAULT,
    labelStyle: TextStyle,
    hereLabel: String,
    system: PlanetSystem?,
    systemAppear: () -> Float,
    onTap: (String) -> Unit,
    /** A tap that lands on nothing. */
    onTapEmpty: () -> Unit = {},
    onTapPlanet: (Int) -> Unit,
    onTapMoon: (Int, Int) -> Unit,
    onInteract: () -> Unit,
    /** A long press on a star, or on a planet or moon of the star it belongs to. */
    onLongPress: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dp = density.density
    val context = LocalContext.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val moonStyle = remember(labelStyle) { labelStyle.copy(fontSize = 9.sp, letterSpacing = 0.3.sp) }
    val yearStyle = remember(labelStyle) { labelStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    val art = remember(scene, familyColors) { GalaxyArt(scene, familyColors) }
    val bodies = remember { SystemArt() }
    val space = rememberSpaceSky(spaceShader)
    art.look(visuals)
    val gas = rememberGalaxySmoke(smoke)
    val coreLight = rememberGalaxyLight(rays)
    val starLight = rememberGalaxyLight(rays)
    val starRays = rememberGalaxyStarRays(rays)
    val coreSpot = remember { LightSpot() }
    val starSpot = remember { LightSpot() }
    val starLights = remember { StarLights() }
    // The listener's ray length, as the march's falloff a sample.
    val rayDecay = GalaxyLight.decayFor(visuals.rayLength)

    // Read live by the gesture handlers, which outlive the composition that
    // made them: captured, a tap would be hit-tested against the view as it
    // was before the panel opened, and land on nothing.
    val liveTop = rememberUpdatedState(reserveTopPx)
    val liveBottom = rememberUpdatedState(reserveBottomPx)
    val liveSystem = rememberUpdatedState(system)
    val liveOnTap = rememberUpdatedState(onTap)
    val liveOnTapEmpty = rememberUpdatedState(onTapEmpty)
    val liveOnPlanet = rememberUpdatedState(onTapPlanet)
    val liveOnMoon = rememberUpdatedState(onTapMoon)
    val liveOnInteract = rememberUpdatedState(onInteract)
    val liveOnLongPress = rememberUpdatedState(onLongPress)

    // Each planet wears its artist's cover, when the chart has one.
    // By genre: the system is rebuilt as its planets' sizes come in, and the
    // covers are the same ones.
    var covers by remember(system?.genreId) { mutableStateOf<List<Bitmap?>>(emptyList()) }
    LaunchedEffect(system?.genreId) {
        val planets = system?.planets ?: return@LaunchedEffect
        covers = planets.map { planet -> planet.artworkUrl?.let { loadCover(context, it) } }
    }

    // A tapped moon rings once, so the tap reads as heard before the music starts.
    val ping = remember { Animatable(0f) }
    val pinged = remember { IntArray(2) { -1 } }
    val scope = rememberCoroutineScope()
    // Masks rather than sets: looked up for every star on every frame.
    val exploredMask = remember(scene, explored) { maskOf(scene, explored) }
    val heartedMask = remember(scene, hearted) { maskOf(scene, hearted) }
    val here = hereId?.let { scene.index[it] } ?: -1
    val selected = selectedId?.let { scene.index[it] } ?: -1

    fun centerYFor(size: Size): Float {
        val top = liveTop.value.coerceIn(0f, size.height / 2f)
        val bottom = liveBottom.value.coerceIn(0f, size.height / 2f)
        return top + (size.height - top - bottom) / 2f
    }

    fun frameFor(size: Size, spun: Float = spin()): CameraFrame =
        camera.frame(size.width, size.height, centerY = centerYFor(size), spin = spun)

    /**
     * The view for the deep sky alone: which way the camera looks and the
     * viewport, and nothing else. The sky is at infinity and does not turn
     * with the galaxy, so where the camera is, how far out, and the turn
     * leave it exactly as it was — and reading none of them, the sky's layer
     * is not redrawn when they change.
     */
    fun skyFrameFor(size: Size): CameraFrame =
        CameraFrame(0f, 0f, 0f, camera.yaw, camera.pitch, 1f, size.width, size.height, centerYFor(size))

    // Clocks for the layers that need not move every frame (see the sky's and
    // the gas's layers below). Each changes only when its layer should redraw.
    val liveTime = rememberUpdatedState(time)
    val liveIdle = rememberUpdatedState(idle)
    val liveBands = rememberUpdatedState(bands)
    val skyTime = remember {
        androidx.compose.runtime.derivedStateOf { stepped(liveTime.value(), SKY_TWINKLE_HZ) }
    }
    val smokeTime = remember {
        androidx.compose.runtime.derivedStateOf {
            val t = liveTime.value()
            if (!liveIdle.value() || driving(liveBands.value)) t else stepped(t, SMOKE_IDLE_HZ)
        }
    }

    Box(
        modifier = modifier
            .hazeSource(hazeState)
            .graphicsLayer {
                // Rendered once a frame into its own layer, which every pane of
                // glass over the map then reads. Without it each of them — the
                // dock's pills, the chips, the mini player, the tab bar — made
                // the render thread replay the whole galaxy, thousands of stars
                // and grains and the light passes with them, to frost what is
                // behind it: the half-second frames in the logs.
                //
                // With no glass over the map (full screen, nothing open) nobody
                // reads that layer, and it would be one more full-screen copy a
                // frame: then the galaxy draws straight to the screen. The sky
                // under it is opaque and everything over the sky adds, so the
                // pixels are the same either way.
                compositingStrategy = if (glassOver) {
                    androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                } else {
                    androidx.compose.ui.graphics.CompositingStrategy.Auto
                }
                // The travel blur: Android's own, so it is cheap and smooth,
                // and only while the camera glides. Ignored below API 31.
                val b = travelBlurPx()
                renderEffect = if (b > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BlurEffect(b, b, TileMode.Decal)
                } else {
                    null
                }
            },
    ) {
        // The deep sky, in a layer of its own that keeps its pixels between
        // frames. It reads only which way the camera looks and its twinkle
        // clock, which steps SKY_TWINKLE_HZ times a second, so it is redrawn
        // when the camera turns and at that rate otherwise; every other frame
        // the layer's last picture is laid down instead of the sky shader
        // running over every pixel of the screen again. One twinkle takes
        // about four seconds, so steps that size are not seen.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
        ) {
            GalaxyProbe.skyDraws++
            drawDeepSky(art, scene, skyFrameFor(size), skyTime.value, dp, space)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawSky(art, scene, frameFor(size), morph(), time(), here, selected, dp, bands)
        }
        if (gas != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The gas, at a third of the resolution: measured that small and
            // put through an offscreen layer scaled back up, so the shader
            // runs for a ninth of the pixels. Smoke has no finer detail.
            Canvas(
                Modifier.layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val small = measurable.measure(
                        androidx.compose.ui.unit.Constraints.fixed(
                            (w + SMOKE_SCALE - 1) / SMOKE_SCALE,
                            (h + SMOKE_SCALE - 1) / SMOKE_SCALE,
                        ),
                    )
                    layout(w, h) {
                        small.placeWithLayer(0, 0) {
                            scaleX = SMOKE_SCALE.toFloat()
                            scaleY = SMOKE_SCALE.toFloat()
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        }
                    }
                },
            ) {
                // Its own clock: every frame while you steer or the music moves
                // it, SMOKE_IDLE_HZ times a second when it only drifts (a swirl
                // takes minutes). The galaxy's turn is read as it is but not
                // watched, so the turn alone does not redraw it between steps;
                // the camera is watched, so it never lags a move.
                val t = smokeTime.value
                val full = Size(size.width * SMOKE_SCALE, size.height * SMOKE_SCALE)
                val f = frameFor(full, androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { spin() })
                GalaxyProbe.smokeDraws++
                gas.draw(drawContext.canvas.nativeCanvas, f, SMOKE_SCALE.toFloat(), t, bands, visuals.smokeAmount)
            }
        }
        // The light passes run at half the resolution (LIGHT_SCALE): each is
        // drawn small, in full-size coordinates scaled down, and its layer is
        // scaled back up. The scatter runs on a quarter of the pixels, and
        // shafts of light have nothing finer than that in them.
        val lightK = 1f / LIGHT_SCALE
        if (coreLight != null) {
            Canvas(
                Modifier.reducedLayer(LIGHT_SCALE) { full ->
                    val f = frameFor(full)
                    litStar(art, scene, camera, selected, f, morph(), dp, starSpot)
                    coreSpot.strength = 1f - starSpot.strength
                    renderEffect = if (lightOn(coreSpot, coreSpot.strength > 0.01f && coreSpotAt(art, f, coreSpot), full)) {
                        GalaxyProbe.coreRays++
                        coreLight.effect(coreSpot, time(), visuals.rayStrength * beat(bands), visuals.rayShade, lightK, rayDecay)
                    } else {
                        null
                    }
                },
            ) {
                val full = Size(size.width * LIGHT_SCALE, size.height * LIGHT_SCALE)
                val f = frameFor(full)
                if (coreSpot.strength > 0.01f && coreSpotAt(art, f, coreSpot) && GalaxyLight.reachesView(coreSpot, full.width, full.height)) {
                    scale(lightK, lightK, pivot = Offset.Zero) {
                        drawCoreLight(art, scene, f, morph(), time(), dp, bands, coreSpot)
                    }
                }
            }
        }
        if (starLight != null) {
            Canvas(
                Modifier.reducedLayer(LIGHT_SCALE) { full ->
                    val f = frameFor(full)
                    litStar(art, scene, camera, selected, f, morph(), dp, starSpot)
                    renderEffect = if (lightOn(starSpot, starSpot.strength > 0.01f, full)) {
                        GalaxyProbe.starRays++
                        starLight.effect(starSpot, time(), visuals.rayStrength * beat(bands), visuals.rayShade, lightK, rayDecay)
                    } else {
                        null
                    }
                },
            ) {
                val full = Size(size.width * LIGHT_SCALE, size.height * LIGHT_SCALE)
                val f = frameFor(full)
                val lit = litStar(art, scene, camera, selected, f, morph(), dp, starSpot)
                if (lit >= 0 && starSpot.strength > 0.01f && GalaxyLight.reachesView(starSpot, full.width, full.height)) {
                    // Its planets stand in its light once they are up.
                    val sys = liveSystem.value?.takeIf { scene.index[it.genreId] == lit }
                    scale(lightK, lightK, pivot = Offset.Zero) {
                        drawStarLight(art, bodies, scene, sys, f, morph(), time(), systemAppear(), lit, dp, starSpot)
                    }
                }
            }
        }
        if (starRays != null && visuals.starRays > 0) {
            // The other stars' rays: the biggest few on screen, all in one
            // pass (GalaxyStarRays), the star you are at left to its own.
            fun pickStars(f: CameraFrame) {
                val m = morph()
                val lit = litStar(art, scene, camera, selected, f, m, dp, null)
                val skip = if (lit >= 0 && starSpot.strength > 0.01f) lit else -1
                val sys = liveSystem.value?.takeIf { selected >= 0 && scene.index[it.genreId] == selected }
                // They step back with the rest while a system is up.
                val dim = if (sys != null) STAR_DIM_NEAR_SYSTEM * systemAppear().coerceIn(0f, 1f) * bodies.fade else 0f
                pickStarLights(art, scene, f, m, dp, visuals.starRays, skip, dim, starLights)
            }
            Canvas(
                Modifier.reducedLayer(LIGHT_SCALE) { full ->
                    pickStars(frameFor(full))
                    renderEffect = if (starLights.count > 0) {
                        GalaxyProbe.starRayPasses++
                        GalaxyProbe.starRayLights = starLights.count
                        starRays.effect(starLights, time(), visuals.rayStrength * beat(bands), visuals.rayShade, rayDecay, lightK)
                    } else {
                        null
                    }
                },
            ) {
                val full = Size(size.width * LIGHT_SCALE, size.height * LIGHT_SCALE)
                val f = frameFor(full)
                pickStars(f)
                if (starLights.count > 0) {
                    scale(lightK, lightK, pivot = Offset.Zero) {
                        drawStarRays(art, scene, f, morph(), dp, starLights)
                    }
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scene) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        liveOnInteract.value()
                        var travelled = 0f
                        var dragging = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            travelled += pan.getDistance()
                            if (!dragging && travelled > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                if (pressed >= 2) {
                                    camera.zoom(event.calculateZoom())
                                    camera.pan(pan.x, pan.y, frameFor(Size(size.width.toFloat(), size.height.toFloat())))
                                } else {
                                    camera.orbit(pan.x, pan.y)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(scene) {
                    /** The nearest star within reach of [at], nearest the camera first; -1 for none. */
                    fun starAt(at: Offset): Int {
                        val f = frameFor(Size(size.width.toFloat(), size.height.toFloat()))
                        val m = morph()
                        val p = art.tmp
                        var best = -1
                        var bestDepth = Float.MAX_VALUE
                        val reach = TAP_RADIUS_DP * dp
                        for (i in 0 until scene.size) {
                            scene.position(i, m, p, 0)
                            if (!f.project(p[0], p[1], p[2], p, 0)) continue
                            if (hypot(p[0] - at.x, p[1] - at.y) <= reach && p[2] < bestDepth) {
                                best = i; bestDepth = p[2]
                            }
                        }
                        return best
                    }
                    detectTapGestures(
                        onLongPress = { at ->
                            // A planet or a moon stands for its star: the
                            // press is for the system, whichever body it hit.
                            val sys = liveSystem.value
                            val body = bodies.hit(sys, at.x, at.y, TAP_RADIUS_DP * dp * 0.8f)
                            val id = if (body != null && sys != null) sys.genreId
                            else starAt(at).takeIf { it >= 0 }?.let { scene.genres[it].id }
                            if (id != null) liveOnLongPress.value(id)
                        },
                    ) { at ->
                        // A moon or a planet first: they sit on top of the
                        // stars, and are what a tap near the selected one means.
                        val body = bodies.hit(liveSystem.value, at.x, at.y, TAP_RADIUS_DP * dp * 0.8f)
                        if (body != null) {
                            val (p, m) = body
                            if (m >= 0) {
                                pinged[0] = p; pinged[1] = m
                                scope.launch { ping.snapTo(1f); ping.animateTo(0f, tween(900)) }
                                liveOnMoon.value(p, m)
                            } else {
                                liveOnPlanet.value(p)
                            }
                            return@detectTapGestures
                        }
                        val best = starAt(at)
                        if (best >= 0) liveOnTap.value(scene.genres[best].id) else liveOnTapEmpty.value()
                    }
                },
        ) {
            val f = frameFor(size)
            val m = morph()
            val t = time()
            val sys = system?.takeIf { selected >= 0 && scene.index[it.genreId] == selected }
            // The system's star is drawn with its planets, in depth order, so a
            // planet passing behind it is behind it.
            // While a solar system is up, the stars around it step back so its
            // planets are what you see.
            val dim = if (sys != null) STAR_DIM_NEAR_SYSTEM * systemAppear().coerceIn(0f, 1f) * bodies.fade else 0f
            // The star you are at is a sun, lit, whether or not its planets
            // have come yet: drawn with them when they are up, on its own if not.
            val lit = litStar(art, scene, camera, selected, f, m, dp, null)
            drawStars(art, scene, f, m, t, exploredMask, heartedMask, selected, if (sys != null) selected else lit, dim, dp)
            val shown = sys != null && drawSystem(
                art, bodies, sys, covers, scene, f, m, t, systemAppear(), selected,
                exploredMask, heartedMask, if (pinged[0] >= 0) ping.value else 0f, pinged, dp,
            )
            if (!shown) bodies.clear()
            if (sys == null && lit >= 0) {
                val s3 = art.genreScreen
                if (s3[lit * 3 + 2] > 0f) {
                    drawSun(art, scene, lit, s3[lit * 3], s3[lit * 3 + 1], s3[lit * 3 + 2], f, t, exploredMask, heartedMask, dp)
                }
            }
            drawMarks(art, scene, f, m, here, selected, dp)
            drawLabels(
                art, bodies, if (shown) sys else null, scene, f, m, here, selected, dp, measurer, labelStyle, moonStyle,
                yearStyle, hereLabel, liveTop.value, size.height - liveBottom.value,
            )
        }
    }
}

/**
 * A layer [divisor] times smaller than the space it fills, scaled back up to
 * fill it: what is drawn in it is drawn at that resolution, and so is any
 * render effect on it, which works in the layer's own pixels. [layer] sets
 * the layer up each frame, given the full size it stands for.
 */
private fun Modifier.reducedLayer(
    divisor: Int,
    layer: androidx.compose.ui.graphics.GraphicsLayerScope.(full: Size) -> Unit,
): Modifier = this.layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val small = measurable.measure(
        androidx.compose.ui.unit.Constraints.fixed((w + divisor - 1) / divisor, (h + divisor - 1) / divisor),
    )
    layout(w, h) {
        small.placeWithLayer(0, 0) {
            scaleX = divisor.toFloat()
            scaleY = divisor.toFloat()
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            layer(Size(w.toFloat(), h.toFloat()))
        }
    }
}

/**
 * Whether a light that is [up] runs its pass: only when its rays can reach
 * the view at all. One that is up but cannot is counted, for the report.
 */
private fun lightOn(spot: LightSpot, up: Boolean, full: Size): Boolean {
    if (!up) return false
    if (!GalaxyLight.reachesView(spot, full.width, full.height)) {
        GalaxyProbe.raysUnreachable++
        return false
    }
    GalaxyProbe.light(spot, full.width, full.height)
    return true
}

/** [t] seconds held to steps of 1 / [hz]: a clock that ticks [hz] times a second. */
internal fun stepped(t: Float, hz: Float): Float = kotlin.math.floor(t * hz) / hz

/** Whether the music is moving the gas this instant: any band above its usual level. */
private fun driving(bands: AudioBands?): Boolean =
    bands != null && bands.bassLift + bands.midLift + bands.trebLift > 0.001f

private fun maskOf(scene: GalaxyScene, ids: Set<String>): BooleanArray =
    BooleanArray(scene.size).also { mask -> ids.forEach { id -> scene.index[id]?.let { mask[it] = true } } }

/** The smoke's resolution, as a divisor of the view's. */
private const val SMOKE_SCALE = 3

/** The god rays' resolution, as a divisor of the view's: shafts are soft, and it is a quarter of the pixels. */
private const val LIGHT_SCALE = 2

/** How often the deep sky's twinkle steps, a second. */
private const val SKY_TWINKLE_HZ = 20f

/** How often the gas is redrawn, a second, while nothing but time moves it. */
private const val SMOKE_IDLE_HZ = 30f

/** Dashes one run may hold, how far past the screen they are still laid, and a hearted ring's sides. */
private const val DASHES = 4096
private const val DASH_MARGIN_DP = 24f
private const val RING_SIDES = 28

/** How much brighter the black hole burns at full bass. */
private const val HOLE_BASS_BOOST = 0.45f

/** How close to a star a tap has to land to pick it. */
private const val TAP_RADIUS_DP = 28f

/** Most names on screen at once. */
private const val MAX_LABELS = 24

/**
 * Everything the galaxy draws with that is worth making once: sprites, paints
 * and the scratch arrays the projection writes into, so a frame allocates
 * nothing.
 */
private class GalaxyArt(scene: GalaxyScene, familyColors: Map<String, Color>) {
    val famColor: List<Color> = scene.families.map { familyColors[it] ?: Color(0xFFBDBDBD) }
    val famDust: IntArray = famColor.map { lerp(it, Color.White, 0.35f).toArgb() }.toIntArray()
    val famLabel: List<Color> = famColor.map { lerp(it, Color.White, 0.55f) }
    /** Each family's light in a ray pass: its colour, most of the way to white, as the star you are at shines. */
    val rayTint: IntArray = famColor.map { lerp(it, Color.White, 0.55f).toArgb() }.toIntArray()

    val starSprite: List<Bitmap> = famColor.map { sprite(64, it, coreWhite = true) }
    val nebulaSprite: List<Bitmap> = famColor.map { sprite(128, it, coreWhite = false) }
    val coreSprite: Bitmap = sprite(128, CORE_WARM, coreWhite = false)
    val emitterSprite: Bitmap = sprite(32, Color.White, coreWhite = true)
    val hole = BlackHoleArt()

    // The light passes: a light's glow, tinted as it is drawn, and the paints
    // that draw what stands in its way — black, or nothing at all.
    val lightGlow: Bitmap = glowSprite(128)

    // A sun's disc per family, made the first time a system of that family is visited.
    val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val suns = arrayOfNulls<Bitmap>(famColor.size)
    fun sunDisc(family: Int): Bitmap = suns[family] ?: sunSprite(128, famColor[family]).also { suns[family] = it }
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val shadowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    // Square: these are never seen, only their shadows are, and a square
    // point is one quad where a round one is a circle the render thread builds.
    val shadowPoints = Paint().apply {
        color = android.graphics.Color.BLACK
        alpha = (0.85f * 255).toInt()
        strokeCap = Paint.Cap.SQUARE
    }
    val shadowCloud = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
    }
    val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    val occluders = FloatArray((scene.dustCount + scene.core.size / 3) * 2)
    private val filters = HashMap<Int, android.graphics.ColorFilter>()

    /** A white mask multiplied into [argb]. Cached: the lights have a handful of colours. */
    fun tint(argb: Int): android.graphics.ColorFilter =
        filters.getOrPut(argb) { android.graphics.LightingColorFilter(argb and 0xFFFFFF, 0) }

    // The listener's look, set from the view each composition (see look).
    var nebulae = 1f; private set
    var blackHole = true; private set
    var twinkle = true; private set
    var starScale = 1f; private set
    var maxLabels = MAX_LABELS; private set
    var bloom = 1f; private set

    fun look(v: tf.monochrome.android.domain.model.GalaxyVisualSettings) {
        bloom = v.bloom
        nebulae = v.nebulae
        blackHole = v.blackHole
        twinkle = v.twinkle
        starScale = v.starSize
        maxLabels = when (v.labels) {
            tf.monochrome.android.domain.model.GalaxyAmount.LESS -> MAX_LABELS / 2
            tf.monochrome.android.domain.model.GalaxyAmount.NORMAL -> MAX_LABELS
            tf.monochrome.android.domain.model.GalaxyAmount.MORE -> MAX_LABELS * 5 / 3
        }
    }
    val haloPoints = FloatArray(scene.halo.size / 3 * 2)

    val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { additive() }
    val points = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; additive() }
    val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; additive() }
    val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** Dashed lines as plain segments: the timeline's spiral, the way from here, the hearts' rings. */
    val dasher = Dasher(DASHES)
    val dashDp = android.content.res.Resources.getSystem().displayMetrics.density
    val rect = RectF()

    val tmp = FloatArray(3)
    val tmp2 = FloatArray(4)
    val genreScreen = FloatArray(scene.size * 3)
    val sky = FloatArray(scene.sky.size / 3 * 2)

    // Dust, bucketed by family and by depth: near, middle, far.
    val dustBucket: Array<FloatArray>
    val dustCount = IntArray(scene.families.size * 3)
    // The energy along the links, bucketed by family: the stardust of the
    // cable, the smoke behind each comet (x, y, radius, alpha), the sparkles
    // round it, and its head.
    val flowFamily: IntArray
    val flowBucket: Array<FloatArray>
    val motes: FloatArray

    /** Each family's stars, so they are drawn a family at a time (see drawStars). */
    val starsByFamily: Array<IntArray> =
        Array(scene.families.size) { fam -> (0 until scene.size).filter { scene.family[it] == fam }.toIntArray() }
    val flowCount = IntArray(scene.families.size)
    val puffBucket: Array<FloatArray>
    val puffCount = IntArray(scene.families.size)
    val sparkBucket: Array<FloatArray>
    val sparkCount = IntArray(scene.families.size)
    val headBucket: Array<FloatArray>
    val headCount = IntArray(scene.families.size)
    val flowA = FloatArray(3)
    val flowB = FloatArray(3)
    val core = Array(2) { FloatArray(scene.core.size / 3 * 2) }
    val coreCount = IntArray(2)
    val track = android.graphics.Path()
    val labelPick = IntArray(LABEL_CANDIDATES)
    val labelScore = FloatArray(LABEL_CANDIDATES)

    init {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val perFamilyDust = IntArray(scene.families.size)
        for (k in 0 until scene.dustCount) perFamilyDust[scene.family[scene.dustOwner[k]]]++
        dustBucket = Array(scene.families.size * 3) { FloatArray(perFamilyDust[it / 3] * 2) }
        // A flow is coloured by where it arrives: the newer genre's family.
        flowFamily = IntArray(scene.flows.size / 2) { scene.family[scene.flows[it * 2 + 1]] }
        val perFamily = IntArray(scene.families.size)
        for (f in flowFamily) perFamily[f]++
        flowBucket = Array(scene.families.size) { FloatArray(perFamily[it] * FLOW_MOTES * 2) }
        // Every link's dust, its randomness drawn once: where along it each
        // mote starts, how fast it drifts, how far off the line it sits
        // sideways and up, and its flicker's phase. It was six hashes a mote a
        // frame, each a sine.
        motes = FloatArray(scene.flows.size / 2 * FLOW_MOTES * MOTE_FIELDS).also { mt ->
            for (k in 0 until scene.flows.size / 2) {
                val seed = scene.flowPhase[k]
                for (j in 0 until FLOW_MOTES) {
                    val o = (k * FLOW_MOTES + j) * MOTE_FIELDS
                    val h = trailHash(seed, j)
                    mt[o] = (j + h) / FLOW_MOTES
                    mt[o + 1] = 0.6f + 0.8f * trailHash(seed, j + 31)
                    mt[o + 2] = trailHash(seed, j + 7) + trailHash(seed, j + 11) - 1f
                    mt[o + 3] = (trailHash(seed, j + 13) + trailHash(seed, j + 19) - 1f) * 0.6f
                    mt[o + 4] = h * 5f
                }
            }
        }
        puffBucket = Array(scene.families.size) { FloatArray(perFamily[it] * TRAIL_PUFFS * 4) }
        sparkBucket = Array(scene.families.size) { FloatArray(perFamily[it] * TRAIL_PUFFS * SPARKS_PER_PUFF * 2) }
        headBucket = Array(scene.families.size) { FloatArray(perFamily[it] * 2) }
    }

    companion object {
        const val TRACK_SEGMENTS = 160

        /** Names tried each frame, best first, before giving up on the rest. */
        const val LABEL_CANDIDATES = 64

        fun Paint.additive() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.PLUS
            else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        /**
         * A star's disc: opaque, white-hot in the middle, warming to [color] and
         * darkening a little toward the limb, as a real star's does.
         */
        fun sunSprite(px: Int, color: Color): Bitmap {
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bitmap)
            val r = px / 2f
            val colors = intArrayOf(
                0xFFFFFFFF.toInt(),
                lerp(color, Color.White, 0.82f).toArgb(),
                lerp(color, Color.White, 0.5f).toArgb(),
                lerp(color, Color.Black, 0.12f).toArgb(),
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, colors, floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawCircle(r, r, r - 0.5f, paint)
            return bitmap
        }

        /** A light's glow: white, falling off as exp(-3 r²) and reaching nothing at its edge. */
        fun glowSprite(px: Int): Bitmap {
            val out = IntArray(px * px)
            val half = px / 2f
            val edge = kotlin.math.exp(-3f)
            for (j in 0 until px) for (i in 0 until px) {
                val x = (i + 0.5f) / half - 1f
                val y = (j + 0.5f) / half - 1f
                val d2 = x * x + y * y
                if (d2 >= 1f) continue
                val a = ((kotlin.math.exp(-3f * d2) - edge) / (1f - edge)).coerceIn(0f, 1f)
                out[j * px + i] = ((a * 255f).toInt() shl 24) or 0xFFFFFF
            }
            return Bitmap.createBitmap(out, px, px, Bitmap.Config.ARGB_8888)
        }

        /** A soft round sprite: a white-hot centre for a star, or only colour for a cloud. */
        fun sprite(px: Int, color: Color, coreWhite: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bitmap)
            val r = px / 2f
            val base = color.toArgb()
            fun withAlpha(a: Float) = (base and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
            val colors = if (coreWhite) {
                intArrayOf(0xFFFFFFFF.toInt(), withAlpha(1f), withAlpha(0.32f), withAlpha(0f))
            } else {
                intArrayOf(withAlpha(1f), withAlpha(0.45f), withAlpha(0.12f), withAlpha(0f))
            }
            val stops = if (coreWhite) floatArrayOf(0f, 0.16f, 0.42f, 1f) else floatArrayOf(0f, 0.3f, 0.65f, 1f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(r, r, r, colors, stops, Shader.TileMode.CLAMP) }
            c.drawCircle(r, r, r, paint)
            return bitmap
        }
    }
}

/** Whether the projected point in [p] lies more than [margin] px off the screen of [f]. */
private fun offScreen(p: FloatArray, f: CameraFrame, margin: Float): Boolean =
    p[0] < -margin || p[0] > f.width + margin || p[1] < -margin || p[1] > f.height + margin

/** How far off the screen a point may sit and still be drawn: more than the widest grain's half. */
private const val POINT_MARGIN_PX = 8f

/** How much a thing at [depth] fades into the distance, 0.15..1. */
private fun fog(depth: Float): Float = (1f - (depth - 3200f) / 7000f).coerceIn(0.15f, 1f)

/**
 * The deep sky: the space shader where there is one, else the flat colour and
 * the far stars as points. [f] need only face the right way (see skyFrameFor).
 */
private fun DrawScope.drawDeepSky(art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, t: Float, dp: Float, space: SpaceSky?) {
    val canvas = drawContext.canvas.nativeCanvas
    if (space != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        space.draw(canvas, f, t, dp)
    } else {
        drawRect(GALAXY_SPACE)
        var n = 0
        for (k in 0 until scene.sky.size / 3) {
            if (f.projectDirection(scene.sky[k * 3], scene.sky[k * 3 + 1], scene.sky[k * 3 + 2], art.sky, n * 2)) n++
        }
        art.points.color = Color.White.copy(alpha = 0.42f).toArgb()
        art.points.strokeWidth = 1.3f * dp
        canvas.drawPoints(art.sky, 0, n * 2, art.points)
    }
}

private fun DrawScope.drawSky(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float, here: Int, selected: Int, dp: Float,
    bands: AudioBands?,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val p = art.tmp

    // Nebulae behind each family, then the core's glow.
    for ((k, i) in scene.nebulaAnchors.withIndex()) {
        scene.position(i, m, p, 0)
        p[0] += scene.nebulaJitter[k * 3]; p[1] += scene.nebulaJitter[k * 3 + 1]; p[2] += scene.nebulaJitter[k * 3 + 2]
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        val r = scene.nebulaRadius[k] * f.scaleAt(p[2])
        // Off the screen it would be clipped to nothing; skipped, it is not even sent.
        if (p[0] + r < 0f || p[0] - r > f.width || p[1] + r < 0f || p[1] - r > f.height) continue
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = (NEBULA_ALPHA * art.nebulae * 255 * fog(p[2])).toInt().coerceIn(0, 255)
        canvas.drawBitmap(art.nebulaSprite[scene.family[i]], null, art.rect, art.add)
    }
    if (f.project(0f, 0f, 0f, p, 0)) {
        // The bulge's glow, kept low when there is a black hole in the middle
        // of it, which has to read as dark.
        val r = 380f * f.scaleAt(p[2])
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = ((if (art.blackHole) 0.16f else 0.3f) * 255).toInt()
        canvas.drawBitmap(art.coreSprite, null, art.rect, art.add)
    }

    // The halo: old stars and globular clusters round the whole disc.
    var hn = 0
    for (k in 0 until scene.halo.size / 3) {
        if (!f.project(scene.halo[k * 3], scene.halo[k * 3 + 1], scene.halo[k * 3 + 2], p, 0)) continue
        if (offScreen(p, f, POINT_MARGIN_PX)) continue
        art.haloPoints[hn] = p[0]; art.haloPoints[hn + 1] = p[1]; hn += 2
    }
    art.points.color = HALO_STAR.toArgb()
    art.points.strokeWidth = 1.3f * dp
    canvas.drawPoints(art.haloPoints, 0, hn, art.points)

    // Family links, as energy: each a cable that swirls between its two
    // genres and undulates as it goes, with a pulse running along it from the
    // older genre to the newer — the way time runs, so the whole map flows
    // forward. The cable is faint; the moving light is what the eye follows.
    drawFlows(art, scene, f, m, t, dp)

    // Dust, in depth bands: near grains bigger and brighter than far ones.
    art.dustCount.fill(0)
    for (k in 0 until scene.dustCount) {
        scene.dustPosition(k, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        if (offScreen(p, f, POINT_MARGIN_PX)) continue
        val band = if (p[2] < 1400f) 0 else if (p[2] < 3200f) 1 else 2
        val bucket = scene.family[scene.dustOwner[k]] * 3 + band
        val c = art.dustCount[bucket]
        val arr = art.dustBucket[bucket]
        if (c + 2 > arr.size) continue
        arr[c] = p[0]; arr[c + 1] = p[1]
        art.dustCount[bucket] = c + 2
    }
    for (bucket in art.dustBucket.indices) {
        if (art.dustCount[bucket] == 0) continue
        val band = bucket % 3
        art.points.color = art.famDust[bucket / 3]
        art.points.alpha = (DUST_ALPHA[band] * 255).toInt()
        art.points.strokeWidth = DUST_SIZE_DP[band] * dp
        canvas.drawPoints(art.dustBucket[bucket], 0, art.dustCount[bucket], art.points)
    }

    // The core's own grains, warm.
    art.coreCount.fill(0)
    for (k in 0 until scene.core.size / 3) {
        if (!f.project(scene.core[k * 3], scene.core[k * 3 + 1], scene.core[k * 3 + 2], p, 0)) continue
        if (offScreen(p, f, POINT_MARGIN_PX)) continue
        val band = if (p[2] < 2000f) 0 else 1
        val c = art.coreCount[band]
        art.core[band][c] = p[0]; art.core[band][c + 1] = p[1]
        art.coreCount[band] = c + 2
    }
    for (band in 0..1) {
        art.points.color = CORE_WARM.copy(alpha = if (band == 0) 0.55f else 0.4f).toArgb()
        art.points.strokeWidth = (if (band == 0) 2f else 1.4f) * dp
        canvas.drawPoints(art.core[band], 0, art.coreCount[band], art.points)
    }

    // The black hole, over the dust and the bulge behind it.
    if (art.blackHole) art.hole.draw(canvas, f, t, dp, boost = 1f + HOLE_BASS_BOOST * (bands?.bassLift ?: 0f))

    // The timeline's own spiral: a track wound at the arms' pitch, with a
    // mark at each year, fading in with the morph.
    if (m > 0.02f) {
        art.plain.color = SCAN.copy(alpha = 0.32f * m).toArgb()
        art.plain.strokeWidth = 1.2f * dp
        // One run of dashes, so they carry on along the whole spiral instead
        // of starting again at every segment.
        val dash = art.dasher
        dash.start(10f * dp, 6f * dp, f.width, f.height, DASH_MARGIN_DP * dp)
        val from = GalaxyScene.timeRadius(GalaxyScene.TRACK_YEARS.first())
        for (k in 0..GalaxyArt.TRACK_SEGMENTS) {
            scene.trackPointAt(from + (1f - from) * k / GalaxyArt.TRACK_SEGMENTS, p, 0)
            if (f.project(p[0], p[1], p[2], p, 0)) dash.to(p[0], p[1]) else dash.lift()
        }
        canvas.drawLines(dash.out, 0, dash.count, art.plain)
        art.add.alpha = (m * 255).toInt()
        for (year in GalaxyScene.TRACK_YEARS) {
            scene.trackPoint(year, p, 0)
            if (!f.project(p[0], p[1], p[2], p, 0)) continue
            val r = 3.5f * dp
            art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
            canvas.drawBitmap(art.emitterSprite, null, art.rect, art.add)
        }
    }

    // The way from where you are to what you have selected.
    val q = art.tmp2
    if (here >= 0 && selected >= 0 && here != selected) {
        scene.position(here, m, p, 0)
        scene.position(selected, m, q, 0)
        if (f.project(p[0], p[1], p[2], p, 0) && f.project(q[0], q[1], q[2], q, 0)) {
            art.plain.color = SCAN.copy(alpha = 0.8f).toArgb()
            art.plain.strokeWidth = 1.4f * dp
            val dash = art.dasher
            dash.start(7f * dp, 5f * dp, f.width, f.height, DASH_MARGIN_DP * dp)
            dash.line(p[0], p[1], q[0], q[1])
            canvas.drawLines(dash.out, 0, dash.count, art.plain)
        }
    }
}

/**
 * The links as starry, smoky trails. Per link, the cable is a scatter of
 * stardust along its swirl — no line at all — and a comet runs it from the
 * older genre to the newer: a bright star at the head, soft smoke behind it
 * that grows and fades as it ages, and sparkles twinkling round the smoke.
 * All of it batched by family: the dust, the sparkles and the heads are one
 * `drawPoints` each, the smoke one run of the family's own cloud sprite.
 */
private fun DrawScope.drawFlows(art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float, dp: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    art.flowCount.fill(0)
    art.puffCount.fill(0)
    art.sparkCount.fill(0)
    art.headCount.fill(0)
    val a = art.flowA
    val b = art.flowB
    val p = art.tmp
    for (k in 0 until scene.flows.size / 2) {
        scene.position(scene.flows[k * 2], m, a, 0)
        scene.position(scene.flows[k * 2 + 1], m, b, 0)
        val ax = a[0]; val ay = a[1]; val az = a[2]
        val dx = b[0] - ax; val dy = b[1] - ay; val dz = b[2] - az
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1f) continue
        // Off the screen at both ends and to one side of it: nothing of it shows.
        if (offToOneSide(f, a, b, art.tmp2, len * FLOW_SWIRL)) continue
        // As much dust as the link is long on screen: a short one far off is
        // a few motes, not the full count stacked on one another.
        val onScreen = art.tmp2[3]
        val stride = (FLOW_MOTES / (onScreen / (FLOW_MOTE_SPACING_DP * dp)).coerceIn(FLOW_MIN_MOTES.toFloat(), FLOW_MOTES.toFloat()))
            .toInt().coerceAtLeast(1)
        // Sideways in the plane, and up: the two ways the trail swirls.
        val hl = kotlin.math.sqrt(dx * dx + dz * dz)
        val sx = if (hl > 1e-3f) -dz / hl else 1f
        val sz = if (hl > 1e-3f) dx / hl else 0f
        val amp = len * FLOW_SWIRL
        val seed = scene.flowPhase[k]
        val phase = seed * 6.2831855f
        val fam = art.flowFamily[k]

        // Where along the trail u (0 older, 1 newer) is, on screen. False when
        // that point is behind the camera.
        // [off] and [up] push a point off the line, sideways and up, as
        // shares of the swirl: how the dust spreads into a band.
        fun at(u: Float, off: Float = 0f, up: Float = 0f): Boolean {
            val bend = fastSin(PI.toFloat() * u)
            val side = amp * (bend * (0.55f + 0.45f * fastSin(9.42f * u + t * FLOW_WAVE + phase)) + off)
            val lift = amp * (0.6f * bend * fastSin(6.2831855f * u + t * FLOW_WAVE * 0.75f + phase) + up)
            return f.project(ax + dx * u + sx * side, ay + dy * u + lift, az + dz * u + sz * side, p, 0)
        }

        // The cable: a band of fine dust, each mote a little off the line,
        // drifting slowly the way time runs and fading in and out as it goes —
        // dust, not a string of beads.
        val drift = t * FLOW_DRIFT_UNITS_S / len
        val dust = art.flowBucket[fam]
        var c = art.flowCount[fam]
        // Each mote's own randomness, worked out once when the art was made.
        val motes = art.motes
        var j = 0
        while (j < FLOW_MOTES) {
            val o = (k * FLOW_MOTES + j) * MOTE_FIELDS
            j += stride
            // A slow flicker: each mote is out for a part of its own cycle.
            if (((t * FLOW_FLICKER + motes[o + 4]) % 1f) > 0.82f) continue
            val u = (motes[o] + drift * motes[o + 1]) % 1f
            // Thin at the ends, where the link meets its stars.
            val spread = FLOW_BAND * fastSin(PI.toFloat() * u)
            if (c + 2 <= dust.size && at(u, spread * motes[o + 2], spread * motes[o + 3])) {
                dust[c] = p[0]; dust[c + 1] = p[1]
                c += 2
            }
        }
        art.flowCount[fam] = c

        // The comet: at a steady speed through space, not once round its
        // link in a set time, so a long link is a long journey and nothing
        // races across the screen when the camera is close.
        val head = ((t * FLOW_UNITS_S / len + seed) % 1f + 1f) % 1f
        if (!at(head)) continue
        val hx = p[0]; val hy = p[1]; val hDepth = p[2]
        val heads = art.headBucket[fam]
        val hc = art.headCount[fam]
        if (hc + 2 <= heads.size) {
            heads[hc] = hx; heads[hc + 1] = hy
            art.headCount[fam] = hc + 2
        }
        // Smoke behind it, toward the past, spreading and thinning with age;
        // sparkles round each puff, twinkling.
        val puffs = art.puffBucket[fam]
        val sparks = art.sparkBucket[fam]
        var pc = art.puffCount[fam]
        var sc = art.sparkCount[fam]
        for (n in 0 until TRAIL_PUFFS) {
            val age = (n + 1f) / TRAIL_PUFFS
            val u = head - FLOW_TAIL * age
            if (u < 0f || !at(u)) break
            val scale = f.scaleAt(p[2])
            val r = (TRAIL_PUFF_UNITS * (0.5f + age) * scale).coerceIn(3f * dp, 30f * dp)
            if (pc + 4 <= puffs.size) {
                puffs[pc] = p[0]; puffs[pc + 1] = p[1]; puffs[pc + 2] = r; puffs[pc + 3] = (1f - age) * fog(hDepth)
                pc += 4
            }
            for (q in 0 until SPARKS_PER_PUFF) {
                // A sparkle shows for part of its own cycle: a twinkle.
                val h = trailHash(seed, n * 7 + q)
                if (((t * SPARK_TWINKLE + h * 3f) % 1f) > 0.65f) continue
                if (sc + 2 > sparks.size) continue
                val ox = r * 0.9f * (trailHash(seed, n * 13 + q + 5) - 0.5f) * 2f
                val oy = r * 0.9f * (trailHash(seed, n * 11 + q + 9) - 0.5f) * 2f
                sparks[sc] = p[0] + ox; sparks[sc + 1] = p[1] + oy
                sc += 2
            }
        }
        art.puffCount[fam] = pc
        art.sparkCount[fam] = sc
    }

    for (fam in art.flowBucket.indices) {
        val color = art.famColor[fam]
        // The smoke first, under its sparkles.
        val puffs = art.puffBucket[fam]
        var n = 0
        while (n < art.puffCount[fam]) {
            val x = puffs[n]; val y = puffs[n + 1]; val r = puffs[n + 2]
            art.rect.set(x - r, y - r, x + r, y + r)
            art.add.alpha = (TRAIL_SMOKE_ALPHA * puffs[n + 3] * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(art.nebulaSprite[fam], null, art.rect, art.add)
            n += 4
        }
        if (art.flowCount[fam] > 0) {
            art.points.strokeWidth = FLOW_MOTE_DP * dp
            art.points.color = lerp(color, Color.White, 0.5f).copy(alpha = FLOW_DUST_ALPHA).toArgb()
            canvas.drawPoints(art.flowBucket[fam], 0, art.flowCount[fam], art.points)
        }
        if (art.sparkCount[fam] > 0) {
            art.points.strokeWidth = 1.6f * dp
            art.points.color = lerp(color, Color.White, 0.75f).copy(alpha = 0.85f).toArgb()
            canvas.drawPoints(art.sparkBucket[fam], 0, art.sparkCount[fam], art.points)
        }
        if (art.headCount[fam] > 0) {
            // The head: a halo, then the star.
            art.points.strokeWidth = 7f * dp
            art.points.color = color.copy(alpha = 0.35f).toArgb()
            canvas.drawPoints(art.headBucket[fam], 0, art.headCount[fam], art.points)
            art.points.strokeWidth = 3f * dp
            art.points.color = lerp(color, Color.White, 0.8f).copy(alpha = 0.95f).toArgb()
            canvas.drawPoints(art.headBucket[fam], 0, art.headCount[fam], art.points)
        }
    }
}

/**
 * Whether a link from [a] to [b] lies wholly off one side of the screen, its
 * swirl [margin] scene units included: both ends projected and both past the
 * same edge. Most links are, close in, and drawing none of their dust is most
 * of what a frame saves.
 */
private fun offToOneSide(f: CameraFrame, a: FloatArray, b: FloatArray, out: FloatArray, margin: Float): Boolean {
    // How long it is on screen, for the dust to be spread to: unknown (long)
    // when an end is behind the camera.
    out[3] = Float.MAX_VALUE
    if (!f.project(a[0], a[1], a[2], out, 0)) return false
    val ax = out[0]; val ay = out[1]; val ma = margin * f.scaleAt(out[2])
    if (!f.project(b[0], b[1], b[2], out, 0)) return false
    val bx = out[0]; val by = out[1]; val mb = margin * f.scaleAt(out[2])
    out[3] = hypot(bx - ax, by - ay) + ma + mb
    val m = maxOf(ma, mb)
    return (ax < -m && bx < -m) || (ax > f.width + m && bx > f.width + m) ||
        (ay < -m && by < -m) || (ay > f.height + m && by > f.height + m)
}

/** A steady 0..1 for a trail's [seed] and an index along it: where its dust and sparkles fall. */
private fun trailHash(seed: Float, i: Int): Float {
    val v = sin(seed * 127.1f + i * 311.7f) * 43758.547f
    return v - kotlin.math.floor(v)
}

/** How far a trail swirls out, as a share of its length. */
private const val FLOW_SWIRL = 0.12f

/**
 * The dust along a cable: how many motes, how wide the band is as a share of
 * the swirl, how fast it drifts and how often a mote flickers out.
 */
private const val FLOW_MOTES = 26
private const val FLOW_BAND = 0.35f
private const val FLOW_DRIFT_UNITS_S = 2.5f
private const val FLOW_FLICKER = 0.12f
private const val FLOW_MOTE_DP = 1.05f

/** On screen, one mote every this many dp of a link at the least, and never fewer than this many. */
private const val FLOW_MOTE_SPACING_DP = 7f
private const val FLOW_MIN_MOTES = 4

/** What [GalaxyArt.motes] holds for each mote. */
private const val MOTE_FIELDS = 5

/** How fast a comet travels, scene units a second, and how far behind it its smoke trails, as a share of the link. */
private const val FLOW_UNITS_S = 9f
private const val FLOW_TAIL = 0.2f

/** How often a sparkle round the smoke twinkles, a second. */
private const val SPARK_TWINKLE = 0.55f

/** Smoke puffs behind a comet, their size in scene units, and the sparkles round each. */
private const val TRAIL_PUFFS = 4
private const val TRAIL_PUFF_UNITS = 16f
private const val SPARKS_PER_PUFF = 2

/** How fast the trail's swirl moves along it. Slow: it undulates, it does not wriggle. */
private const val FLOW_WAVE = 0.22f

/** The stardust is faint and the smoke soft: the comets are what the eye follows. */
private const val FLOW_DUST_ALPHA = 0.26f
private const val TRAIL_SMOKE_ALPHA = 0.22f

/** A nebula's strength: the demo's, faint enough that a family is a haze, not a fill. */
private const val NEBULA_ALPHA = 0.07f

private val DUST_ALPHA = floatArrayOf(0.5f, 0.36f, 0.22f)
private val DUST_SIZE_DP = floatArrayOf(2f, 1.5f, 1.1f)

/**
 * A star's radius on screen: bigger the better known, and bigger lit.
 *
 * Distance shrinks it more gently than perspective would (to the power
 * 0.75, not 1). With true perspective the overview's stars came out the size
 * of its dust, at a distance where they are the only thing worth seeing.
 * Tuned so the overview runs about 2 dp for the least known star to 8 dp for
 * the best known, with the disc's stars about 11 dp apart, and so a star you
 * have travelled to tops out at [MAX_STAR_DP].
 */
private fun starSizePx(prominence: Float, lit: Boolean, depth: Float, dp: Float, scale: Float = 1f): Float =
    ((3.7f + 12f * prominence) * (if (lit) 1.35f else 1f) * (STAR_REF_DEPTH / depth).pow(0.75f) * dp * scale)
        // The listener's star size moves the floor and the cap with it. The cap
        // used to grow above 100 % and never shrink below it, so the near stars
        // — the ones at the cap — looked the same at 78 % as at 160 %.
        .coerceIn(STAR_MIN_DP * dp * scale, MAX_STAR_DP * dp * scale)

private const val STAR_REF_DEPTH = 1400f
private const val STAR_MIN_DP = 1.2f
private const val MAX_STAR_DP = 26f

/** How much the music lifts the light: the bass, as it swells the black hole. */
private fun beat(bands: AudioBands?): Float = 1f + HOLE_BASS_BOOST * (bands?.bassLift ?: 0f)

/** The core's light this frame, into [spot]: false when the core is behind the camera. */
private fun coreSpotAt(art: GalaxyArt, f: CameraFrame, spot: LightSpot): Boolean {
    val p = art.tmp
    if (!f.project(0f, 0f, 0f, p, 0)) return false
    val scale = f.scaleAt(p[2])
    spot.x = p[0]; spot.y = p[1]
    spot.glowR = CORE_LIGHT_GLOW * scale
    // What shines in its pass: the glow, and the black hole's disk (which can
    // reach past the glow close up) or the bulge's sprite.
    val shines = if (art.blackHole) art.hole.lightExtent(f) else CORE_SPRITE_UNITS * scale
    spot.reach = maxOf(GalaxyLight.SHADE_REACH * spot.glowR, shines) + LIGHT_REACH_MARGIN_PX
    return true
}

/**
 * The star you are at, and its light this frame into [spot] when given: the
 * selected star, else the one the camera is following, else whichever star
 * the camera sits inside the system of. Returns -1 for none.
 *
 * It is lit as soon as you are near it — its planets need not have come — and
 * fades in with how big its system would be on screen, taking over from the
 * core as you arrive, so there is one light at a time where it matters.
 */
private fun litStar(
    art: GalaxyArt, scene: GalaxyScene, camera: GalaxyCamera, selected: Int, f: CameraFrame, m: Float,
    dp: Float, spot: LightSpot?,
): Int {
    spot?.strength = 0f
    val p = art.tmp
    var star = if (selected >= 0) selected else camera.follow
    if (star < 0 || star >= scene.size) {
        star = -1
        var best = Float.MAX_VALUE
        for (i in 0 until scene.size) {
            scene.position(i, m, p, 0)
            val dx = p[0] - camera.targetX; val dy = p[1] - camera.targetY; val dz = p[2] - camera.targetZ
            val d = dx * dx + dy * dy + dz * dz
            if (d < best) { best = d; star = i }
        }
        if (star < 0) return -1
        val reach = PlanetSystem.reachFor(GalaxyScene.starRadius(scene.prominence[star]))
        if (best > reach * reach) return -1
    }
    if (spot == null) return star
    scene.position(star, m, p, 0)
    if (!f.project(p[0], p[1], p[2], p, 0)) return star
    val depth = p[2]
    val scale = f.scaleAt(depth)
    val reach = PlanetSystem.reachFor(GalaxyScene.starRadius(scene.prominence[star]))
    spot.x = p[0]; spot.y = p[1]
    spot.glowR = (reach * scale * LIT_GLOW_REACH).coerceAtLeast(LIGHT_MIN_GLOW_DP * dp)
    spot.strength = ((reach * scale - SYSTEM_MIN_DP * dp) / (SYSTEM_FADE_DP * dp)).coerceIn(0f, 1f)
    // The glow and the star's white-hot middle are all that shine in its pass.
    spot.reach = maxOf(GalaxyLight.SHADE_REACH * spot.glowR, starEmitterPx(art, scene, star, depth, f, dp)) +
        LIGHT_REACH_MARGIN_PX
    return star
}

/** The radius of a lit star's white-hot middle in its light pass, px, at [depth]. */
private fun starEmitterPx(art: GalaxyArt, scene: GalaxyScene, star: Int, depth: Float, f: CameraFrame, dp: Float): Float =
    maxOf(
        starSizePx(scene.prominence[star], true, depth, dp, art.starScale) * STAR_LIGHT_CORE,
        (GalaxyScene.starRadius(scene.prominence[star]) * f.scaleAt(depth)).coerceAtMost(SUN_MAX_PX) * 1.15f,
    )

/** How far a system is faded in at [scale] px a unit: nothing until it is more than a smudge on screen. */
private fun systemFade(sys: PlanetSystem, scale: Float, appear: Float, dp: Float): Float =
    (((sys.outerOrbit * scale) - SYSTEM_MIN_DP * dp) / (SYSTEM_FADE_DP * dp)).coerceIn(0f, 1f) * appear.coerceIn(0f, 1f)

/** A body's size in scene units, as the system says it is. */
private fun bodyUnits(sys: PlanetSystem, p: Int, mi: Int): Float =
    if (mi < 0) sys.planets[p].radius else sys.planets[p].moons[mi].radius

/**
 * A body's radius on screen, px, for [units] scene units at [scale] px a
 * unit: at least a speck to see and tap, never filling the screen.
 */
private fun bodyRadius(units: Float, mi: Int, spread: Float, scale: Float, dp: Float, size: Float = 1f): Float {
    // A speck, not a disc, from the system's own distance: planets are small
    // beside their star, and you zoom in to see one. The listener's planet
    // size ([size]) moves the speck too, so it reads from far off as well.
    val minR = (if (mi < 0) PLANET_MIN_DP else MOON_MIN_DP) * dp * spread.coerceAtMost(1f) * size
    return (units * spread.coerceAtMost(1f) * scale).coerceIn(minR, MAX_BODY_DP * dp)
}

/**
 * The core's light pass: its glow and the core itself (or the accretion
 * disk, round a hole that gives none), then everything standing in that glow
 * in black — the nebulae as soft shade, the dust and the bulge's grains as
 * fine streaks, and the stars.
 */
private fun DrawScope.drawCoreLight(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float, dp: Float, bands: AudioBands?,
    spot: LightSpot,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val p = art.tmp
    drawLightGlow(art, spot, CORE_WARM.toArgb())
    if (art.blackHole) {
        art.hole.draw(canvas, f, t, dp, rays = true, boost = beat(bands))
        // The shadow gives no light and casts none: cleared, not blacked
        // out, or the hole would throw a dark ring over its own disk.
        if (f.project(0f, 0f, 0f, p, 0)) {
            canvas.drawCircle(p[0], p[1], GalaxyScene.HOLE_SHADOW * f.scaleAt(p[2]) * 0.98f, art.clear)
        }
    } else if (f.project(0f, 0f, 0f, p, 0)) {
        val scale = f.scaleAt(p[2])
        var r = CORE_SPRITE_UNITS * scale
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = 255
        canvas.drawBitmap(art.coreSprite, null, art.rect, art.add)
        r = 36f * scale
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        canvas.drawBitmap(art.emitterSprite, null, art.rect, art.add)
    }
    drawOccluders(art, scene, f, m, dp, spot, 0f, 0f, 0f, CORE_LIGHT_GLOW)
}

/**
 * A star's light pass, for the planets round it: its glow and its white-hot
 * middle, with every planet and moon in black — those beyond the star drawn
 * before it, so it covers them, and those this side after — and the dust and
 * stars in its glow.
 */
private fun DrawScope.drawStarLight(
    art: GalaxyArt, bodies: SystemArt, scene: GalaxyScene, sys: PlanetSystem?, f: CameraFrame, m: Float, t: Float,
    appear: Float, star: Int, dp: Float, spot: LightSpot,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val c = art.tmp2
    scene.position(star, m, c, 0)
    val cx = c[0]; val cy = c[1]; val cz = c[2]
    val p = art.tmp
    if (!f.project(cx, cy, cz, p, 0)) return
    val starDepth = p[2]
    drawLightGlow(art, spot, lerp(art.famColor[scene.family[star]], Color.White, 0.55f).toArgb())
    val spread = appear.coerceIn(0f, 1.2f)
    for (pass in 0..1) {
        if (pass == 1) {
            val r = starEmitterPx(art, scene, star, starDepth, f, dp)
            art.rect.set(spot.x - r, spot.y - r, spot.x + r, spot.y + r)
            art.add.alpha = 255
            canvas.drawBitmap(art.emitterSprite, null, art.rect, art.add)
        }
        if (sys == null) continue
        for (pi in sys.planets.indices) {
            for (mi in -1 until sys.planets[pi].moons.size) {
                if (mi < 0) sys.planetPosition(pi, t, cx, cy, cz, p, 0, spread)
                else sys.moonPosition(pi, mi, t, cx, cy, cz, p, 0, spread)
                if (!f.project(p[0], p[1], p[2], p, 0)) continue
                // Beyond the star on the first pass, this side of it on the second.
                if ((p[2] > starDepth) != (pass == 0)) continue
                val units = bodies.easedOr(sys, pi, mi, bodyUnits(sys, pi, mi))
                canvas.drawCircle(p[0], p[1], bodyRadius(units, mi, spread, f.scaleAt(p[2]), dp, sys.bodyScale), art.shadowFill)
            }
        }
    }
    drawOccluders(art, scene, f, m, dp, spot, cx, cy, cz, PlanetSystem.reachFor(GalaxyScene.starRadius(scene.prominence[star])) * LIT_GLOW_REACH)
}

/** The light's glow, the medium its shadows show in: a soft disc in [argb], as far as [spot] reaches. */
private fun DrawScope.drawLightGlow(art: GalaxyArt, spot: LightSpot, argb: Int) =
    drawLightGlow(art, spot.x, spot.y, spot.glowR, argb)

/**
 * A light's glow at ([x], [y]), [glowR] px across, in [argb], as strong as the
 * listener's bloom: more glow is more light for the rays to carry, so a
 * brighter halo and brighter shafts; none leaves the white-hot middle alone.
 */
private fun DrawScope.drawLightGlow(art: GalaxyArt, x: Float, y: Float, glowR: Float, argb: Int) {
    art.glowPaint.colorFilter = art.tint(argb)
    art.glowPaint.alpha = (LIGHT_GLOW_ALPHA * art.bloom * 255).toInt().coerceIn(0, 255)
    art.rect.set(x - glowR, y - glowR, x + glowR, y + glowR)
    drawContext.canvas.nativeCanvas.drawBitmap(art.lightGlow, null, art.rect, art.glowPaint)
}

/**
 * The [want] stars that shine rays of their own this frame, into [out]: the
 * biggest on screen, leaving out [skip] (the star whose own light pass is up).
 * Each fades over the gap to the next star in line, so one moving into or out
 * of the few dims rather than popping, and [dim] steps them back while a
 * system is showing.
 */
private fun pickStarLights(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, dp: Float, want: Int, skip: Int, dim: Float,
    out: StarLights,
) {
    out.count = 0
    val keep = want.coerceIn(0, StarLights.MAX)
    if (keep == 0) return
    val p = art.tmp
    val pick = out.pick
    val score = out.score
    val slots = keep + 1
    val least = STAR_RAY_MIN_DP * dp
    var picked = 0
    for (i in 0 until scene.size) {
        if (i == skip) continue
        scene.position(i, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        if (p[0] < 0f || p[0] > f.width || p[1] < 0f || p[1] > f.height) continue
        val size = starSizePx(scene.prominence[i], true, p[2], dp, art.starScale)
        if (size < least) continue
        if (picked == slots && size <= score[picked - 1]) continue
        var at = if (picked < slots) picked++ else picked - 1
        while (at > 0 && score[at - 1] < size) {
            pick[at] = pick[at - 1]; score[at] = score[at - 1]; at--
        }
        pick[at] = i; score[at] = size
    }
    val cut = if (picked == slots) score[keep] else least
    var n = 0
    for (k in 0 until minOf(picked, keep)) {
        val i = pick[k]
        scene.position(i, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        val size = score[k]
        val power = ((size - cut) / (size * STAR_RAY_FADE)).coerceIn(0f, 1f) * fog(p[2]) * (1f - dim) * STAR_RAY_POWER
        if (power < 0.01f) continue
        val glow = (size * STAR_RAY_GLOW).coerceIn(STAR_RAY_MIN_GLOW_DP * dp, STAR_RAY_MAX_GLOW_DP * dp)
        out.star[n] = i
        out.x[n] = p[0]; out.y[n] = p[1]; out.depth[n] = p[2]
        out.glowR[n] = glow
        out.core[n] = size * STAR_RAY_CORE
        out.reach[n] = maxOf(GalaxyLight.SHADE_REACH * glow, out.core[n]) + LIGHT_REACH_MARGIN_PX
        out.power[n] = power
        n++
    }
    out.count = n
}

/**
 * The other stars' light pass: each one's glow and white-hot middle, and in
 * black the dust and the bulge's grains standing in a glow — near it in depth
 * as well as on screen — for the fine dark streaks. No planets: only the star
 * whose system is up has those, and it has its own pass.
 */
private fun DrawScope.drawStarRays(art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, dp: Float, lights: StarLights) {
    val canvas = drawContext.canvas.nativeCanvas
    for (k in 0 until lights.count) {
        val fam = scene.family[lights.star[k]]
        drawLightGlow(art, lights.x[k], lights.y[k], lights.glowR[k], art.rayTint[fam])
        val r = lights.core[k]
        art.rect.set(lights.x[k] - r, lights.y[k] - r, lights.x[k] + r, lights.y[k] + r)
        art.add.alpha = 255
        canvas.drawBitmap(art.emitterSprite, null, art.rect, art.add)
    }
    fun standsIn(x: Float, y: Float, depth: Float): Boolean {
        for (k in 0 until lights.count) {
            val dx = x - lights.x[k]; val dy = y - lights.y[k]
            val g = lights.glowR[k]
            if (dx * dx + dy * dy > g * g) continue
            // As deep as the glow is wide, in scene units at the star.
            if (kotlin.math.abs(depth - lights.depth[k]) <= g / f.scaleAt(lights.depth[k])) return true
        }
        return false
    }
    val p = art.tmp
    val pts = art.occluders
    var n = 0
    for (k in 0 until scene.dustCount) {
        if (n + 2 > pts.size) break
        scene.dustPosition(k, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        if (!standsIn(p[0], p[1], p[2])) continue
        pts[n] = p[0]; pts[n + 1] = p[1]; n += 2
    }
    for (k in 0 until scene.core.size / 3) {
        if (n + 2 > pts.size) break
        if (!f.project(scene.core[k * 3], scene.core[k * 3 + 1], scene.core[k * 3 + 2], p, 0)) continue
        if (!standsIn(p[0], p[1], p[2])) continue
        pts[n] = p[0]; pts[n + 1] = p[1]; n += 2
    }
    art.shadowPoints.strokeWidth = OCCLUDER_DUST_DP * dp
    canvas.drawPoints(pts, 0, n, art.shadowPoints)
}

/**
 * The other stars' rays. Only stars at least [STAR_RAY_MIN_DP] across shine
 * them; a star's glow is [STAR_RAY_GLOW] times its size (within the dp
 * bounds), its white-hot middle [STAR_RAY_CORE] of it, and its light
 * [STAR_RAY_POWER] of the main star's at most — they are the background.
 * Each fades in over [STAR_RAY_FADE] of its size above the next in line.
 */
private const val STAR_RAY_MIN_DP = 3f
private const val STAR_RAY_GLOW = 3f
private const val STAR_RAY_MIN_GLOW_DP = 18f
private const val STAR_RAY_MAX_GLOW_DP = 150f
private const val STAR_RAY_CORE = 0.35f
private const val STAR_RAY_POWER = 0.8f
private const val STAR_RAY_FADE = 0.3f

/**
 * The dust and the bulge's grains inside [spot]'s glow, in black, as one batch
 * of points: the fine rays. A grain further than [worldR] from the light at
 * ([wx], [wy], [wz]) cannot be inside its glow, and is passed over before it
 * is projected — most of them, for a star's light.
 */
private fun DrawScope.drawOccluders(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, dp: Float, spot: LightSpot,
    wx: Float, wy: Float, wz: Float, worldR: Float,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val p = art.tmp
    val reach = spot.glowR * spot.glowR
    val near = worldR * worldR
    val pts = art.occluders
    var n = 0
    for (k in 0 until scene.dustCount) {
        if (n + 2 > pts.size) break
        scene.dustPosition(k, m, p, 0)
        val ex = p[0] - wx; val ey = p[1] - wy; val ez = p[2] - wz
        if (ex * ex + ey * ey + ez * ez > near) continue
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        val dx = p[0] - spot.x; val dy = p[1] - spot.y
        if (dx * dx + dy * dy > reach) continue
        pts[n] = p[0]; pts[n + 1] = p[1]; n += 2
    }
    for (k in 0 until scene.core.size / 3) {
        if (n + 2 > pts.size) break
        val cx = scene.core[k * 3]; val cy = scene.core[k * 3 + 1]; val cz = scene.core[k * 3 + 2]
        val ex = cx - wx; val ey = cy - wy; val ez = cz - wz
        if (ex * ex + ey * ey + ez * ez > near) continue
        if (!f.project(cx, cy, cz, p, 0)) continue
        val dx = p[0] - spot.x; val dy = p[1] - spot.y
        if (dx * dx + dy * dy > reach) continue
        pts[n] = p[0]; pts[n + 1] = p[1]; n += 2
    }
    art.shadowPoints.strokeWidth = OCCLUDER_DUST_DP * dp
    canvas.drawPoints(pts, 0, n, art.shadowPoints)
    // Not the stars: a star is a light of its own, and one standing in front
    // of another threw a broad black beam across the map that read as wrong.
}

/** The core's glow, in galaxy units: past the bulge, so the inner arms' dust stands in it. */
private const val CORE_LIGHT_GLOW = 560f

/** The bulge's sprite in the core's light pass, galaxy units (drawCoreLight, without a black hole). */
private const val CORE_SPRITE_UNITS = 120f

/** Past a light's reach, px: its sprites' filtering, and a pixel's own size. */
private const val LIGHT_REACH_MARGIN_PX = 4f

/** A star's glow, as a share of its system's widest reach: every planet stands in it. */
private const val LIT_GLOW_REACH = 0.95f
private const val LIGHT_MIN_GLOW_DP = 60f
private const val LIGHT_GLOW_ALPHA = 0.32f

/** A lit star's white-hot middle in its light pass, as a share of its size on screen. */
private const val STAR_LIGHT_CORE = 1.1f

/** How wide a dust grain stands in the light: the fine rays. */
private const val OCCLUDER_DUST_DP = 2f

private fun DrawScope.drawStars(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float,
    explored: BooleanArray, hearted: BooleanArray, selected: Int, skip: Int, dim: Float, dp: Float,
) {
    val s = art.genreScreen
    for (i in 0 until scene.size) {
        scene.position(i, m, art.tmp, 0)
        f.project(art.tmp[0], art.tmp[1], art.tmp[2], s, i * 3)
    }
    // Family by family: the sprites add, so their order changes nothing on
    // screen, and one family's run is one texture the GPU can batch where the
    // genres' own order switched texture at nearly every star. The hearts'
    // rings, which do not add, go on after, over them all.
    fun visible(i: Int): Boolean {
        if (i == skip) return false
        val depth = s[i * 3 + 2]
        if (depth < 0f) return false
        val x = s[i * 3]; val y = s[i * 3 + 1]
        return !(x < -40f * dp || x > f.width + 40f * dp || y < -40f * dp || y > f.height + 40f * dp)
    }
    for (members in art.starsByFamily) {
        for (i in members) {
            if (visible(i)) drawStar(art, scene, i, s[i * 3], s[i * 3 + 1], s[i * 3 + 2], t, explored, hearted, selected, dp, dim, ring = false)
        }
    }
    for (i in 0 until scene.size) {
        if (hearted[i] && visible(i)) drawHeartRing(art, scene, i, s[i * 3], s[i * 3 + 1], s[i * 3 + 2], t, dp)
    }
}

/**
 * One star: a twinkling glow sprite in its family's colour, ringed if hearted.
 *
 * Every star is lit, always. They used to stay small and dim until you had
 * listened to their genre ("still dark"), which made most of the galaxy look
 * switched off — and a star you flew to look broken until it lit.
 */
private fun DrawScope.drawStar(
    art: GalaxyArt, scene: GalaxyScene, i: Int, x: Float, y: Float, depth: Float, t: Float,
    @Suppress("UNUSED_PARAMETER") explored: BooleanArray, hearted: BooleanArray, selected: Int, dp: Float, dim: Float = 0f,
    ring: Boolean = true,
) {
    val canvas = drawContext.canvas.nativeCanvas
    // The sprite carries the halo, so it is drawn well past the star's own size.
    val r = starRadiusPx(art, scene, i, depth, t, dp)
    art.rect.set(x - r, y - r, x + r, y + r)
    art.add.alpha = (fog(depth) * (1f - dim) * 255).toInt()
    canvas.drawBitmap(art.starSprite[scene.family[i]], null, art.rect, art.add)
    if (ring && hearted[i]) drawHeartRing(art, scene, i, x, y, depth, t, dp)
}

/** A star's sprite radius this frame, twinkle and all. */
private fun starRadiusPx(art: GalaxyArt, scene: GalaxyScene, i: Int, depth: Float, t: Float, dp: Float): Float {
    val twinkle = if (art.twinkle) 0.8f + 0.2f * fastSin(t * 1.6f + scene.phase[i]) else 0.9f
    return starSizePx(scene.prominence[i], true, depth, dp, art.starScale) * 1.25f * twinkle
}

/** A hearted star's dashed ring, in its family's colour. */
private fun DrawScope.drawHeartRing(art: GalaxyArt, scene: GalaxyScene, i: Int, x: Float, y: Float, depth: Float, t: Float, dp: Float) {
    art.plain.color = art.famColor[scene.family[i]].copy(alpha = 0.9f).toArgb()
    art.plain.strokeWidth = 1.4f * dp
    // The ring as a polygon fine enough to read as a circle, dashed.
    val ring = starRadiusPx(art, scene, i, depth, t, dp) * 0.55f + 4f * dp
    val dash = art.dasher
    dash.start(4f * dp, 3f * dp, Float.MAX_VALUE / 4f, Float.MAX_VALUE / 4f, 0f)
    for (k in 0..RING_SIDES) {
        val a = k * (6.2831855f / RING_SIDES)
        dash.to(x + ring * kotlin.math.cos(a), y + ring * kotlin.math.sin(a))
    }
    drawContext.canvas.nativeCanvas.drawLines(dash.out, 0, dash.count, art.plain)
}

/** The lock-on bracket round the selected star, and "you are here". */
private fun DrawScope.drawMarks(art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, here: Int, selected: Int, dp: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    val s = art.genreScreen
    if (selected >= 0 && s[selected * 3 + 2] > 0f) {
        val x = s[selected * 3]; val y = s[selected * 3 + 1]
        val half = (9000f / s[selected * 3 + 2]).coerceIn(14f, 36f) * dp
        val arm = 9f * dp
        art.plain.color = SCAN.toArgb()
        art.plain.strokeWidth = 1.6f * dp
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val cx = x + sx * half; val cy = y + sy * half
            canvas.drawLine(cx, cy, cx - sx * arm, cy, art.plain)
            canvas.drawLine(cx, cy, cx, cy - sy * arm, art.plain)
        }
    }
    if (here >= 0 && s[here * 3 + 2] > 0f) {
        val x = s[here * 3]; val y = s[here * 3 + 1]
        val d = 8f * dp
        art.plain.color = WARM.toArgb()
        art.plain.strokeWidth = 1.6f * dp
        canvas.drawLine(x, y - d, x + d, y, art.plain)
        canvas.drawLine(x + d, y, x, y + d, art.plain)
        canvas.drawLine(x, y + d, x - d, y, art.plain)
        canvas.drawLine(x - d, y, x, y - d, art.plain)
    }
}

// ── Planets and moons ─────────────────────────────────────────────────────

/**
 * What a planet system draws with, made once, and where each of its bodies
 * landed on screen last frame — which is what a tap is tested against.
 *
 * Every body is a sprite lit from one side, and the night side is a second
 * sprite turned to face away from the star: one rotation per body, no
 * gradients built per frame.
 */
private class SystemArt {
    private val stride = 1 + PlanetSystem.MAX_MOONS
    private val n = PlanetSystem.MAX_PLANETS * stride
    val x = FloatArray(n)
    val y = FloatArray(n)
    val r = FloatArray(n)
    val depth = FloatArray(n)
    val shown = BooleanArray(n)
    /** Where each body is in the scene, for the light's direction to it. */
    val wx = FloatArray(n)
    val wy = FloatArray(n)
    val wz = FloatArray(n)

    /** How far in the system was faded last frame, 0..1. */
    var fade = 0f

    // Each body's size as drawn, in scene units, easing toward the system's.
    private val units = FloatArray(n) { -1f }
    private var unitsFor: String? = null

    /** Body [k]'s size this frame: a step of the way to [target], or [target] on a system's first sight. */
    fun ease(sys: PlanetSystem, k: Int, target: Float): Float {
        if (unitsFor != sys.genreId) {
            unitsFor = sys.genreId
            units.fill(-1f)
        }
        val now = units[k]
        val next = if (now < 0f) target else now + (target - now) * SIZE_EASE
        units[k] = next
        return next
    }

    /** Body ([p], moon [mi]) as last drawn, for the light pass drawn before it; [target] if it has not been. */
    fun easedOr(sys: PlanetSystem, p: Int, mi: Int, target: Float): Float =
        units[slot(p, mi)].takeIf { unitsFor == sys.genreId && it > 0f } ?: target
    val order = IntArray(n + 1)
    val orbit = FloatArray(ORBIT_SEGMENTS * 4)
    val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val cover = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val matrix = android.graphics.Matrix()
    val src = RectF()

    // ── Lightweight global illumination, baked ──
    //
    // Three masks per phase of the light, made once: the shadow (what the star
    // does not reach, down to an ambient floor rather than black), the
    // starlight (a highlight and a Fresnel rim, tinted with the star's colour)
    // and the bounce (the night side lit faintly by the galaxy around it, in
    // the family's colour). Five phases, from the light behind the body to
    // the light behind the camera, so a planet between you and its star is a
    // crescent and one beside it is half lit. Each is drawn turned toward the
    // star: three bitmap draws a body, no gradients built per frame.
    val shade: Array<Bitmap> = Array(PHASES) { bakeSphere(LIGHT_PX, phaseZ(it), SHADE) }
    val light: Array<Bitmap> = Array(PHASES) { bakeSphere(LIGHT_PX, phaseZ(it), LIGHT) }
    val bounce: Array<Bitmap> = Array(PHASES) { bakeSphere(LIGHT_PX, phaseZ(it), BOUNCE) }

    /** A soft white glow, tinted when drawn: the atmosphere's scatter round a planet. */
    val glow: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
        val c = android.graphics.Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                32f, 32f, 32f,
                intArrayOf(0x66FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
                floatArrayOf(0.45f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(32f, 32f, 32f, paint)
    }

    /** Additive, tinted per draw through a cached colour filter. */
    val tinted = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.PLUS
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val filters = HashMap<Int, android.graphics.ColorFilter>()

    /** A white mask multiplied into [argb]. Cached: a system has a handful of colours. */
    fun tint(argb: Int): android.graphics.ColorFilter =
        filters.getOrPut(argb) { android.graphics.LightingColorFilter(argb and 0xFFFFFF, 0) }

    /** A moon: pale rock, a little lighter where the star is. */
    val moon: Bitmap = Bitmap.createBitmap(SPRITE / 2, SPRITE / 2, Bitmap.Config.ARGB_8888).also { b ->
        val c = android.graphics.Canvas(b)
        val h = SPRITE / 4f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                h * 1.35f, h * 0.9f, h * 1.4f,
                intArrayOf(0xFFF2F1F6.toInt(), 0xFFB4B3BE.toInt(), 0xFF6E6D78.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(h, h, h, paint)
    }

    private var planetsFor: PlanetSystem? = null
    private var planetSprites: List<Bitmap> = emptyList()
    private var coversFor: List<Bitmap?> = emptyList()
    private var coverShaders: List<android.graphics.BitmapShader?> = emptyList()

    /** A banded planet in its own colour, for an artist the chart has no cover for. */
    fun planetSprite(system: PlanetSystem, p: Int): Bitmap {
        if (planetsFor !== system) {
            planetsFor = system
            planetSprites = system.planets.map { bandedPlanet(it.hue) }
        }
        return planetSprites[p]
    }

    fun coverShader(covers: List<Bitmap?>, p: Int): android.graphics.BitmapShader? {
        if (coversFor !== covers) {
            coversFor = covers
            coverShaders = covers.map { b ->
                b?.let { android.graphics.BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            }
        }
        return coverShaders.getOrNull(p)
    }

    fun slot(p: Int, m: Int): Int = p * stride + m + 1

    fun clear() {
        shown.fill(false)
        fade = 0f
    }

    /** The body nearest [px], [py] within [reach] of its edge, as (planet, moon or -1). */
    fun hit(system: PlanetSystem?, px: Float, py: Float, reach: Float): Pair<Int, Int>? {
        system ?: return null
        var best: Pair<Int, Int>? = null
        var bestGap = Float.MAX_VALUE
        for (p in system.planets.indices) {
            for (m in -1 until system.planets[p].moons.size) {
                val k = slot(p, m)
                if (!shown[k]) continue
                // A moon is a small target beside a big one, so it is given
                // the benefit of a near tie.
                val gap = hypot(px - x[k], py - y[k]) - r[k] - (if (m >= 0) reach * 0.25f else 0f)
                if (gap <= reach && gap < bestGap) {
                    best = p to m; bestGap = gap
                }
            }
        }
        return best
    }

    private fun bandedPlanet(hue: Float): Bitmap {
        val b = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val h = SPRITE / 2f
        fun hsv(dh: Float, sat: Float, v: Float) =
            android.graphics.Color.HSVToColor(floatArrayOf(((hue + dh) % 360f + 360f) % 360f, sat, v))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, SPRITE.toFloat(),
                intArrayOf(
                    hsv(0f, 0.38f, 0.95f), hsv(14f, 0.55f, 0.78f), hsv(-6f, 0.32f, 0.92f),
                    hsv(22f, 0.6f, 0.72f), hsv(4f, 0.42f, 0.9f), hsv(-12f, 0.5f, 0.8f),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(h, h, h, paint)
        return b
    }

    companion object {
        const val SPRITE = 128
        const val ORBIT_SEGMENTS = 64

        /** The share of the way a body's size moves toward a new one each frame: about half a second. */
        const val SIZE_EASE = 0.08f
        const val PHASES = 5
        const val LIGHT_PX = 96
        private const val SHADE = 0
        private const val LIGHT = 1
        private const val BOUNCE = 2

        /** The light's pull toward the camera for phase [k]: -0.8 behind the body to 0.8 behind you. */
        fun phaseZ(k: Int): Float = -0.8f + 1.6f * k / (PHASES - 1)

        /** The phase nearest a light whose pull toward the camera is [z]. */
        fun phaseOf(z: Float): Int = kotlin.math.round((z.coerceIn(-0.8f, 0.8f) + 0.8f) / 1.6f * (PHASES - 1)).toInt()

        /**
         * One mask of a unit sphere lit from +x (and [lz] toward the viewer):
         * wrapped Lambert for the shadow, its fourth power plus a Fresnel rim
         * for the light, and the unlit side's Fresnel for the bounce. The edge
         * is anti-aliased across one pixel.
         */
        fun bakeSphere(px: Int, lz: Float, mode: Int): Bitmap {
            val lx = kotlin.math.sqrt((1f - lz * lz).coerceAtLeast(0f))
            val out = IntArray(px * px)
            val half = px / 2f
            for (j in 0 until px) for (i in 0 until px) {
                val x = (i + 0.5f) / half - 1f
                val y = (j + 0.5f) / half - 1f
                val rr = x * x + y * y
                val cover = ((1f - kotlin.math.sqrt(rr)) * half).coerceIn(0f, 1f)
                if (cover <= 0f) continue
                val nz = kotlin.math.sqrt((1f - rr).coerceAtLeast(0f))
                val ndl = x * lx + nz * lz
                val diffuse = ((ndl + WRAP) / (1f + WRAP)).coerceIn(0f, 1f)
                val fresnel = (1f - nz).let { it * it * it }
                val (rgb, a) = when (mode) {
                    SHADE -> 0x000000 to (1f - (AMBIENT + (1f - AMBIENT) * diffuse))
                    LIGHT -> 0xFFFFFF to (0.5f * diffuse * diffuse * diffuse * diffuse +
                        0.9f * fresnel * (0.15f + 0.85f * (ndl + 0.35f).coerceIn(0f, 1f)))
                    else -> 0xFFFFFF to (0.6f * (1f - diffuse) * (0.35f + 0.65f * fresnel))
                }
                val alpha = (a.coerceIn(0f, 1f) * cover * 255f).toInt()
                out[j * px + i] = (alpha shl 24) or rgb
            }
            return Bitmap.createBitmap(out, px, px, Bitmap.Config.ARGB_8888)
        }

        /** How far round the terminator the light wraps: soft, like a world with air. */
        private const val WRAP = 0.2f

        /** The least light anything gets: the galaxy is bright, nothing goes black. */
        private const val AMBIENT = 0.16f
    }
}

/**
 * A system's star as a sun: the usual glowing sprite, and — once the camera is
 * near enough for it to have a size — its disc at its true radius, white-hot
 * in the middle and darkening to its family's colour at the limb, with a
 * corona round it in proportion. The planets are a few hundredths of this
 * across; it is the giant of its system.
 */
private fun DrawScope.drawSun(
    art: GalaxyArt, scene: GalaxyScene, star: Int, x: Float, y: Float, depth: Float,
    f: CameraFrame, t: Float, explored: BooleanArray, hearted: BooleanArray, dp: Float,
) {
    drawStar(art, scene, star, x, y, depth, t, explored, hearted, star, dp)
    val rs = (GalaxyScene.starRadius(scene.prominence[star]) * f.scaleAt(depth)).coerceAtMost(SUN_MAX_PX)
    val show = ((rs - SUN_DISC_DP * dp) / (SUN_DISC_DP * dp)).coerceIn(0f, 1f)
    if (show <= 0f) return
    val canvas = drawContext.canvas.nativeCanvas
    val fam = scene.family[star]
    // The corona: two soft layers, each a little stretched, turning and
    // breathing slowly against each other, so the glow is uneven the way a
    // star's is rather than a perfect ring.
    for (layer in 0..1) {
        val breath = 1f + 0.05f * sin(t * (0.7f + 0.4f * layer) + layer * 2.1f)
        val corona = rs * SUN_CORONA * breath * (1f - 0.12f * layer)
        canvas.save()
        canvas.rotate(t * (4f - 7f * layer) + layer * 63f, x, y)
        canvas.scale(1.12f - 0.2f * layer, 0.9f + 0.18f * layer, x, y)
        art.rect.set(x - corona, y - corona, x + corona, y + corona)
        art.add.alpha = ((0.36f - 0.08f * layer) * show * 255).toInt()
        canvas.drawBitmap(art.starSprite[fam], null, art.rect, art.add)
        canvas.restore()
    }
    art.rect.set(x - rs, y - rs, x + rs, y + rs)
    art.sunPaint.alpha = (show * 255).toInt()
    canvas.drawBitmap(art.sunDisc(fam), null, art.rect, art.sunPaint)
}

/**
 * The selected star with its planets and moons, back to front.
 *
 * The star is always drawn here when it has a system (the star pass skips
 * it); the planets only once the system is big enough on screen to be more
 * than a smudge, fading in from there and growing out of the star as
 * [appear] runs. Returns whether any planet was drawn.
 */
private fun DrawScope.drawSystem(
    art: GalaxyArt, bodies: SystemArt, sys: PlanetSystem, covers: List<Bitmap?>, scene: GalaxyScene,
    f: CameraFrame, m: Float, t: Float, appear: Float, star: Int,
    explored: BooleanArray, hearted: BooleanArray, ping: Float, pinged: IntArray, dp: Float,
): Boolean {
    val canvas = drawContext.canvas.nativeCanvas
    val s = art.genreScreen
    val sx = s[star * 3]; val sy = s[star * 3 + 1]; val sDepth = s[star * 3 + 2]
    val c = art.tmp2
    scene.position(star, m, c, 0)
    val cx = c[0]; val cy = c[1]; val cz = c[2]

    // How big the system is on screen decides whether it is drawn at all.
    val scale = if (sDepth > 0f) f.scaleAt(sDepth) else Float.MAX_VALUE
    val fade = systemFade(sys, scale, appear, dp)
    bodies.fade = fade
    if (fade <= 0.01f || sys.planets.isEmpty()) {
        if (sDepth > 0f) drawSun(art, scene, star, sx, sy, sDepth, f, t, explored, hearted, dp)
        return false
    }
    val spread = appear.coerceIn(0f, 1.2f)
    val p3 = art.tmp

    // Orbits first, faint, behind everything: the shape of the system.
    bodies.rim.color = Color.White.toArgb()
    bodies.rim.alpha = (0.13f * fade * 255).toInt()
    bodies.rim.strokeWidth = 1f * dp
    for (p in sys.planets.indices) {
        var n = 0
        var has = false
        var lx = 0f; var ly = 0f
        for (k in 0..SystemArt.ORBIT_SEGMENTS) {
            val a = k / SystemArt.ORBIT_SEGMENTS.toFloat() * 6.2831855f
            sys.orbitPoint(p, a, cx, cy, cz, p3, 0, spread)
            if (f.project(p3[0], p3[1], p3[2], p3, 0)) {
                if (has && n + 4 <= bodies.orbit.size) {
                    bodies.orbit[n] = lx; bodies.orbit[n + 1] = ly; bodies.orbit[n + 2] = p3[0]; bodies.orbit[n + 3] = p3[1]; n += 4
                }
                lx = p3[0]; ly = p3[1]; has = true
            } else {
                has = false
            }
        }
        canvas.drawLines(bodies.orbit, 0, n, bodies.rim)
    }

    // Project every body, then draw back to front with the star among them.
    var count = 0
    bodies.shown.fill(false)
    for (p in sys.planets.indices) {
        val planet = sys.planets[p]
        for (mi in -1 until planet.moons.size) {
            val k = bodies.slot(p, mi)
            if (mi < 0) sys.planetPosition(p, t, cx, cy, cz, p3, 0, spread)
            else sys.moonPosition(p, mi, t, cx, cy, cz, p3, 0, spread)
            bodies.wx[k] = p3[0]; bodies.wy[k] = p3[1]; bodies.wz[k] = p3[2]
            if (!f.project(p3[0], p3[1], p3[2], p3, 0)) continue
            bodies.x[k] = p3[0]; bodies.y[k] = p3[1]; bodies.depth[k] = p3[2]
            // Its size eases to a new one: a catalogue count that arrives late
            // grows the planet rather than popping it.
            bodies.r[k] = bodyRadius(bodies.ease(sys, k, bodyUnits(sys, p, mi)), mi, spread, f.scaleAt(p3[2]), dp, sys.bodyScale)
            if (p3[0] < -bodies.r[k] || p3[0] > f.width + bodies.r[k] || p3[1] < -bodies.r[k] || p3[1] > f.height + bodies.r[k]) continue
            bodies.shown[k] = true
            bodies.order[count++] = k
        }
    }
    val starSlot = -1
    bodies.order[count++] = starSlot
    // Insertion sort, far to near: two dozen bodies at most.
    for (a in 1 until count) {
        val v = bodies.order[a]
        val dv = if (v == starSlot) sDepth else bodies.depth[v]
        var b = a
        while (b > 0) {
            val u = bodies.order[b - 1]
            val du = if (u == starSlot) sDepth else bodies.depth[u]
            if (du >= dv) break
            bodies.order[b] = u; b--
        }
        bodies.order[b] = v
    }

    val alpha = (fade * 255).toInt()
    // The star's light, white-hot with its family's colour in it, and the
    // galaxy's, the family's own colour, for the bounce on the night side.
    val family = art.famColor[scene.family[star]]
    val starLight = lerp(family, Color.White, 0.55f).toArgb()
    val galaxyLight = family.toArgb()
    for (o in 0 until count) {
        val k = bodies.order[o]
        if (k == starSlot) {
            if (sDepth > 0f) drawSun(art, scene, star, sx, sy, sDepth, f, t, explored, hearted, dp)
            continue
        }
        // slot = planet * stride + moon + 1, the planet itself being moon -1.
        val p = k / (1 + PlanetSystem.MAX_MOONS)
        val mi = k % (1 + PlanetSystem.MAX_MOONS) - 1
        val x = bodies.x[k]; val y = bodies.y[k]; val r = bodies.r[k]
        // The light: from the body to its star, in the camera's terms. Its
        // turn on screen is which way the lit side faces; its pull toward the
        // camera is the phase, from a crescent (backlit) to full.
        var lx = cx - bodies.wx[k]; var ly = cy - bodies.wy[k]; var lz = cz - bodies.wz[k]
        val ll = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-4f)
        lx /= ll; ly /= ll; lz /= ll
        val toward = kotlin.math.atan2(-(lx * f.ux + ly * f.uy + lz * f.uz), lx * f.rx + ly * f.ry + lz * f.rz)
        val phase = SystemArt.phaseOf(-(lx * f.fx + ly * f.fy + lz * f.fz))
        art.rect.set(x - r, y - r, x + r, y + r)
        // The atmosphere's scatter, behind the body, in its own colour.
        val bodyColor = if (mi < 0) android.graphics.Color.HSVToColor(floatArrayOf(sys.planets[p].hue, 0.45f, 1f)) else starLight
        bodies.tinted.colorFilter = bodies.tint(bodyColor)
        bodies.tinted.alpha = (GLOW_ALPHA * fade * 255).toInt()
        val g = r * 1.55f
        bodies.src.set(x - g, y - g, x + g, y + g)
        canvas.drawBitmap(bodies.glow, null, bodies.src, bodies.tinted)
        if (mi < 0) {
            val shader = bodies.coverShader(covers, p)
            val cover = covers.getOrNull(p)
            if (shader != null && cover != null) {
                bodies.src.set(0f, 0f, cover.width.toFloat(), cover.height.toFloat())
                bodies.matrix.setRectToRect(bodies.src, art.rect, android.graphics.Matrix.ScaleToFit.FILL)
                shader.setLocalMatrix(bodies.matrix)
                bodies.cover.shader = shader
                bodies.cover.alpha = alpha
                canvas.drawCircle(x, y, r, bodies.cover)
            } else {
                bodies.sprite.alpha = alpha
                canvas.drawBitmap(bodies.planetSprite(sys, p), null, art.rect, bodies.sprite)
            }
        } else {
            bodies.sprite.alpha = alpha
            canvas.drawBitmap(bodies.moon, null, art.rect, bodies.sprite)
        }
        canvas.save()
        canvas.rotate(Math.toDegrees(toward.toDouble()).toFloat(), x, y)
        bodies.sprite.alpha = alpha
        canvas.drawBitmap(bodies.shade[phase], null, art.rect, bodies.sprite)
        bodies.tinted.colorFilter = bodies.tint(starLight)
        bodies.tinted.alpha = alpha
        canvas.drawBitmap(bodies.light[phase], null, art.rect, bodies.tinted)
        bodies.tinted.colorFilter = bodies.tint(galaxyLight)
        bodies.tinted.alpha = (BOUNCE_ALPHA * fade * 255).toInt()
        canvas.drawBitmap(bodies.bounce[phase], null, art.rect, bodies.tinted)
        canvas.restore()
        if (ping > 0f && mi >= 0 && pinged[0] == p && pinged[1] == mi) {
            art.plain.color = SCAN.copy(alpha = ping).toArgb()
            art.plain.strokeWidth = 1.5f * dp
            canvas.drawCircle(x, y, r + (1f - ping) * 22f * dp + 3f * dp, art.plain)
        }
    }
    return true
}

/** The atmosphere's glow round a body, and the galaxy's bounce on its night side. */
private const val GLOW_ALPHA = 0.32f
private const val BOUNCE_ALPHA = 0.55f

/** How far the other stars dim while a solar system is showing. */
private const val STAR_DIM_NEAR_SYSTEM = 0.55f

/** Below this, on screen, a system is a smudge and only its star is drawn. */
private const val SYSTEM_MIN_DP = 28f
private const val SYSTEM_FADE_DP = 60f
private const val MAX_BODY_DP = 420f

/** The least a planet or a moon is drawn at, dp: enough to see, and a target to tap. */
private const val PLANET_MIN_DP = 2.4f
private const val MOON_MIN_DP = 1.3f

/** A sun's disc is drawn once it is this big, dp, fading in over the next as much again. */
private const val SUN_DISC_DP = 2f

/** The corona round a sun, in radii of its disc. */
private const val SUN_CORONA = 2.6f

/** The most a sun's disc is drawn at, px: past this it is off every side of the screen anyway. */
private const val SUN_MAX_PX = 6000f

/** A cover for a planet, small and in software, so it can be painted through a shader. */
private suspend fun loadCover(context: android.content.Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .size(COVER_PX, COVER_PX)
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    // A cover that will not load leaves the planet in its own colours.
    null
}

private const val COVER_PX = 192

/** How far a moon has to be from its planet's edge before its track gets a name. */
private const val MOON_LABEL_GAP_DP = 14f

/**
 * Names, as many as fit: the selected star, its planets (artists) and moons
 * (tracks), "you are here", then the best known and nearest stars. Never
 * under the title or the panels — a name under glass is two pieces of text on
 * top of each other.
 */
private fun DrawScope.drawLabels(
    art: GalaxyArt, bodies: SystemArt, sys: PlanetSystem?, scene: GalaxyScene, f: CameraFrame, m: Float,
    here: Int, selected: Int, dp: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, moonStyle: TextStyle, yearStyle: TextStyle,
    hereLabel: String, top: Float, bottom: Float,
) {
    val s = art.genreScreen
    val maxLabels = art.maxLabels
    val taken = ArrayList<FloatArray>(maxLabels + 8)
    var used = 0

    /** Draws [text] with its top-left at [x], [y] if it is clear of everything already placed. */
    fun place(text: String, textStyle: TextStyle, color: Color, x0: Float, y0: Float, centred: Boolean, alpha: Float): Boolean {
        if (used >= maxLabels) return false
        val layout: TextLayoutResult = measurer.measure(text, textStyle)
        val w = layout.size.width.toFloat(); val h = layout.size.height.toFloat()
        val x = if (centred) x0 - w / 2f else x0
        val y = y0 - (if (centred) h else h / 2f)
        if (y < top || y + h > bottom || x < 0f || x + w > f.width) return false
        if (taken.any { x < it[2] && x + w > it[0] && y < it[3] && y + h > it[1] }) return false
        taken.add(floatArrayOf(x - 4f * dp, y, x + w + 4f * dp, y + h))
        drawText(layout, color = color, topLeft = Offset(x, y), alpha = alpha)
        used++
        return true
    }

    fun put(i: Int, color: Color, text: String = scene.genres[i].name.uppercase()): Boolean {
        val depth = s[i * 3 + 2]
        if (depth < 0f) return false
        return place(text, style, color, s[i * 3], s[i * 3 + 1] - 10f * dp, centred = true, alpha = (2600f / depth).coerceIn(0.3f, 1f))
    }

    if (selected >= 0) put(selected, SCAN)

    // The system's own names: artists above their planets, then tracks beside
    // their moons once a moon is far enough from its planet to be told apart.
    if (sys != null) {
        for (p in sys.planets.indices) {
            val k = bodies.slot(p, -1)
            if (!bodies.shown[k]) continue
            place(
                sys.planets[p].artist.uppercase(), style, GALAXY_INK,
                bodies.x[k], bodies.y[k] - bodies.r[k] - 4f * dp, centred = true, alpha = 0.95f,
            )
        }
        for (p in sys.planets.indices) {
            val pk = bodies.slot(p, -1)
            for (mi in sys.planets[p].moons.indices) {
                val k = bodies.slot(p, mi)
                if (!bodies.shown[k]) continue
                if (bodies.shown[pk] && hypot(bodies.x[k] - bodies.x[pk], bodies.y[k] - bodies.y[pk]) < bodies.r[pk] + MOON_LABEL_GAP_DP * dp) continue
                place(
                    sys.planets[p].moons[mi].entry.title, moonStyle, GALAXY_INK.copy(alpha = 0.85f),
                    bodies.x[k] + bodies.r[k] + 4f * dp, bodies.y[k], centred = false, alpha = 0.9f,
                )
            }
        }
    }

    // The years along the timeline's spiral: the axis, so before the stars.
    if (m > 0.05f) {
        val p = art.tmp
        for (year in GalaxyScene.TRACK_YEARS) {
            scene.trackPoint(year, p, 0)
            if (!f.project(p[0], p[1], p[2], p, 0)) continue
            place(year.toString(), yearStyle, SCAN.copy(alpha = m), p[0] + 7f * dp, p[1], centred = false, alpha = 0.95f)
        }
    }

    if (here >= 0 && here != selected) put(here, WARM, "${scene.genres[here].name} · $hereLabel".uppercase())
    // Then the best known and nearest. A running top list rather than a sort:
    // this is every frame, and only the first few dozen can ever be drawn.
    val best = art.labelPick
    val score = art.labelScore
    var picked = 0
    for (i in 0 until scene.size) {
        val depth = s[i * 3 + 2]
        if (depth <= 0f || i == selected || i == here) continue
        val x = s[i * 3]; val y = s[i * 3 + 1]
        if (x < 0f || x > f.width || y < top || y > bottom) continue
        val v = scene.prominence[i] * 0.7f + 380f / depth
        if (picked == best.size && v <= score[picked - 1]) continue
        var at = if (picked < best.size) picked++ else picked - 1
        while (at > 0 && score[at - 1] < v) {
            best[at] = best[at - 1]; score[at] = score[at - 1]; at--
        }
        best[at] = i; score[at] = v
    }
    for (n in 0 until picked) {
        if (used >= maxLabels) break
        put(best[n], art.famLabel[scene.family[best[n]]])
    }

}
