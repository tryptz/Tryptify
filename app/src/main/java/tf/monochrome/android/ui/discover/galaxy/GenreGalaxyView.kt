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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import tf.monochrome.android.ui.player.sceneGodRays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** The sky's base: deep space, the same in every theme — the galaxy is a window, not a page. */
internal val GALAXY_SPACE = Color(0xFF04060E)
internal val GALAXY_INK = Color(0xFFEEF0FF)
private val SCAN = Color(0xFF86F6FF)
private val WARM = Color(0xFFFFCF7A)
private val CORE_WARM = Color(0xFFFFD9A8)

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
 * Three layers, back to front, all inside the panels' haze source:
 * 1. the sky — far stars, nebulae, the core, family links, dust, the timeline's
 *    year rings and the path from "you are here" to what you have selected;
 * 2. the emitters — the core and the bright stars, put through the lyric god
 *    rays ([sceneGodRays]) so only their shafts come out;
 * 3. the stars on top — twinkling glow sprites, the lock-on bracket, "you are
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
    labelStyle: TextStyle,
    hereLabel: String,
    system: PlanetSystem?,
    systemAppear: () -> Float,
    onTap: (String) -> Unit,
    onTapPlanet: (Int) -> Unit,
    onTapMoon: (Int, Int) -> Unit,
    onInteract: () -> Unit,
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

    // Read live by the gesture handlers, which outlive the composition that
    // made them: captured, a tap would be hit-tested against the view as it
    // was before the panel opened, and land on nothing.
    val liveTop = rememberUpdatedState(reserveTopPx)
    val liveBottom = rememberUpdatedState(reserveBottomPx)
    val liveSystem = rememberUpdatedState(system)
    val liveOnTap = rememberUpdatedState(onTap)
    val liveOnPlanet = rememberUpdatedState(onTapPlanet)
    val liveOnMoon = rememberUpdatedState(onTapMoon)
    val liveOnInteract = rememberUpdatedState(onInteract)

    // Each planet wears its artist's cover, when the chart has one.
    var covers by remember(system) { mutableStateOf<List<Bitmap?>>(emptyList()) }
    LaunchedEffect(system) {
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

    fun frameFor(size: Size): CameraFrame {
        val top = liveTop.value.coerceIn(0f, size.height / 2f)
        val bottom = liveBottom.value.coerceIn(0f, size.height / 2f)
        return camera.frame(size.width, size.height, centerY = top + (size.height - top - bottom) / 2f, spin = spin())
    }

    Box(
        modifier = modifier
            .hazeSource(hazeState)
            .graphicsLayer {
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
        Canvas(Modifier.fillMaxSize()) {
            drawSky(art, scene, frameFor(size), morph(), time(), here, selected, dp, space)
        }
        if (rays) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .sceneGodRays(
                        light = { size ->
                            val f = frameFor(size)
                            val out = art.tmp
                            if (f.project(0f, 0f, 0f, out, 0)) Offset(out[0], out[1]) else null
                        },
                        time = time,
                    ),
            ) {
                drawEmitters(art, scene, frameFor(size), morph(), exploredMask, dp)
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
                    detectTapGestures { at ->
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
                        if (best >= 0) liveOnTap.value(scene.genres[best].id)
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
            drawStars(art, scene, f, m, t, exploredMask, heartedMask, selected, if (sys != null) selected else -1, dim, dp)
            val shown = sys != null && drawSystem(
                art, bodies, sys, covers, scene, f, m, t, systemAppear(), selected,
                exploredMask, heartedMask, if (pinged[0] >= 0) ping.value else 0f, pinged, dp,
            )
            if (!shown) bodies.clear()
            drawMarks(art, scene, f, m, here, selected, dp)
            drawLabels(
                art, bodies, if (shown) sys else null, scene, f, m, here, selected, dp, measurer, labelStyle, moonStyle,
                yearStyle, hereLabel, liveTop.value, size.height - liveBottom.value,
            )
        }
    }
}

private fun maskOf(scene: GalaxyScene, ids: Set<String>): BooleanArray =
    BooleanArray(scene.size).also { mask -> ids.forEach { id -> scene.index[id]?.let { mask[it] = true } } }

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

    val starSprite: List<Bitmap> = famColor.map { sprite(64, it, coreWhite = true) }
    val nebulaSprite: List<Bitmap> = famColor.map { sprite(128, it, coreWhite = false) }
    val coreSprite: Bitmap = sprite(128, CORE_WARM, coreWhite = false)
    val emitterSprite: Bitmap = sprite(32, Color.White, coreWhite = true)

    val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { additive() }
    val points = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; additive() }
    val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; additive() }
    val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val heartDash: android.graphics.PathEffect
    val pathDash: android.graphics.PathEffect
    val trackDash: android.graphics.PathEffect
    val rect = RectF()

    val tmp = FloatArray(3)
    val tmp2 = FloatArray(3)
    val genreScreen = FloatArray(scene.size * 3)
    val sky = FloatArray(scene.sky.size / 3 * 2)

    // Dust, bucketed by family and by depth: near, middle, far.
    val dustBucket: Array<FloatArray>
    val dustCount = IntArray(scene.families.size * 3)
    val linkBucket: Array<FloatArray>
    val linkCount = IntArray(scene.families.size)
    val core = Array(2) { FloatArray(scene.core.size / 3 * 2) }
    val coreCount = IntArray(2)
    val track = android.graphics.Path()
    val labelPick = IntArray(LABEL_CANDIDATES)
    val labelScore = FloatArray(LABEL_CANDIDATES)

    init {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        heartDash = android.graphics.DashPathEffect(floatArrayOf(4f * dp, 3f * dp), 0f)
        pathDash = android.graphics.DashPathEffect(floatArrayOf(7f * dp, 5f * dp), 0f)
        trackDash = android.graphics.DashPathEffect(floatArrayOf(10f * dp, 6f * dp), 0f)
        val perFamilyDust = IntArray(scene.families.size)
        for (k in 0 until scene.dustCount) perFamilyDust[scene.family[scene.dustOwner[k]]]++
        dustBucket = Array(scene.families.size * 3) { FloatArray(perFamilyDust[it / 3] * 2) }
        val perFamilyLinks = IntArray(scene.families.size)
        for (n in 0 until scene.links.size / 2) perFamilyLinks[scene.family[scene.links[n * 2]]]++
        linkBucket = Array(scene.families.size) { FloatArray(perFamilyLinks[it] * 4) }
    }

    companion object {
        const val TRACK_SEGMENTS = 160

        /** Names tried each frame, best first, before giving up on the rest. */
        const val LABEL_CANDIDATES = 64

        fun Paint.additive() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.PLUS
            else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
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

/** How much a thing at [depth] fades into the distance, 0.15..1. */
private fun fog(depth: Float): Float = (1f - (depth - 3200f) / 7000f).coerceIn(0.15f, 1f)

private fun DrawScope.drawSky(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float, here: Int, selected: Int, dp: Float,
    space: SpaceSky?,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val p = art.tmp

    // The deep sky: the space shader where there is one, else the flat colour
    // and the far stars as points.
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

    // Nebulae behind each family, then the core's glow.
    for ((k, i) in scene.nebulaAnchors.withIndex()) {
        scene.position(i, m, p, 0)
        p[0] += scene.nebulaJitter[k * 3]; p[1] += scene.nebulaJitter[k * 3 + 1]; p[2] += scene.nebulaJitter[k * 3 + 2]
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        val r = scene.nebulaRadius[k] * f.scaleAt(p[2])
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = (NEBULA_ALPHA * 255 * fog(p[2])).toInt()
        canvas.drawBitmap(art.nebulaSprite[scene.family[i]], null, art.rect, art.add)
    }
    if (f.project(0f, 0f, 0f, p, 0)) {
        val r = 380f * f.scaleAt(p[2])
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = (0.3f * 255).toInt()
        canvas.drawBitmap(art.coreSprite, null, art.rect, art.add)
    }

    // Family links, faint: the structure, not the subject.
    art.linkCount.fill(0)
    val q = art.tmp2
    for (k in 0 until scene.links.size / 2) {
        val a = scene.links[k * 2]
        scene.position(a, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        scene.position(scene.links[k * 2 + 1], m, q, 0)
        if (!f.project(q[0], q[1], q[2], q, 0)) continue
        val fam = scene.family[a]
        val arr = art.linkBucket[fam]
        val c = art.linkCount[fam]
        if (c + 4 > arr.size) continue
        arr[c] = p[0]; arr[c + 1] = p[1]; arr[c + 2] = q[0]; arr[c + 3] = q[1]
        art.linkCount[fam] = c + 4
    }
    art.lines.strokeWidth = 0.8f * dp
    for (fam in art.linkBucket.indices) {
        if (art.linkCount[fam] == 0) continue
        art.lines.color = art.famColor[fam].copy(alpha = 0.16f).toArgb()
        canvas.drawLines(art.linkBucket[fam], 0, art.linkCount[fam], art.lines)
    }

    // Dust, in depth bands: near grains bigger and brighter than far ones.
    art.dustCount.fill(0)
    for (k in 0 until scene.dustCount) {
        scene.dustPosition(k, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        if (p[0] < -8f || p[0] > f.width + 8f || p[1] < -8f || p[1] > f.height + 8f) continue
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

    // The timeline's own spiral: a track wound at the arms' pitch, with a
    // mark at each year, fading in with the morph.
    if (m > 0.02f) {
        art.plain.color = SCAN.copy(alpha = 0.32f * m).toArgb()
        art.plain.strokeWidth = 1.2f * dp
        art.plain.pathEffect = art.trackDash
        // One path, so the dashes run on along the whole spiral instead of
        // starting again at every segment.
        val path = art.track
        path.rewind()
        var open = false
        val from = GalaxyScene.timeRadius(GalaxyScene.TRACK_YEARS.first())
        for (k in 0..GalaxyArt.TRACK_SEGMENTS) {
            scene.trackPointAt(from + (1f - from) * k / GalaxyArt.TRACK_SEGMENTS, p, 0)
            if (f.project(p[0], p[1], p[2], p, 0)) {
                if (open) path.lineTo(p[0], p[1]) else path.moveTo(p[0], p[1])
                open = true
            } else {
                open = false
            }
        }
        canvas.drawPath(path, art.plain)
        art.plain.pathEffect = null
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
    if (here >= 0 && selected >= 0 && here != selected) {
        scene.position(here, m, p, 0)
        scene.position(selected, m, q, 0)
        if (f.project(p[0], p[1], p[2], p, 0) && f.project(q[0], q[1], q[2], q, 0)) {
            art.plain.color = SCAN.copy(alpha = 0.8f).toArgb()
            art.plain.strokeWidth = 1.4f * dp
            art.plain.pathEffect = art.pathDash
            canvas.drawLine(p[0], p[1], q[0], q[1], art.plain)
            art.plain.pathEffect = null
        }
    }
}

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
private fun starSizePx(prominence: Float, lit: Boolean, depth: Float, dp: Float): Float =
    ((3.7f + 12f * prominence) * (if (lit) 1.35f else 1f) * (STAR_REF_DEPTH / depth).pow(0.75f) * dp)
        .coerceIn(1.2f * dp, MAX_STAR_DP * dp)

private const val STAR_REF_DEPTH = 1400f
private const val MAX_STAR_DP = 26f

/** What shines for the god rays: the core and the brighter stars, white-hot, on nothing. */
private fun DrawScope.drawEmitters(art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, explored: BooleanArray, dp: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    val p = art.tmp
    if (f.project(0f, 0f, 0f, p, 0)) {
        val r = 120f * f.scaleAt(p[2])
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = 255
        canvas.drawBitmap(art.coreSprite, null, art.rect, art.add)
    }
    for (i in 0 until scene.size) {
        val lit = explored[i]
        if (!lit && scene.prominence[i] < 0.35f) continue
        scene.position(i, m, p, 0)
        if (!f.project(p[0], p[1], p[2], p, 0)) continue
        val r = starSizePx(scene.prominence[i], lit, p[2], dp) * 0.6f
        art.rect.set(p[0] - r, p[1] - r, p[0] + r, p[1] + r)
        art.add.alpha = (255 * fog(p[2])).toInt()
        canvas.drawBitmap(art.emitterSprite, null, art.rect, art.add)
    }
}

private fun DrawScope.drawStars(
    art: GalaxyArt, scene: GalaxyScene, f: CameraFrame, m: Float, t: Float,
    explored: BooleanArray, hearted: BooleanArray, selected: Int, skip: Int, dim: Float, dp: Float,
) {
    val s = art.genreScreen
    for (i in 0 until scene.size) {
        scene.position(i, m, art.tmp, 0)
        f.project(art.tmp[0], art.tmp[1], art.tmp[2], s, i * 3)
    }
    for (i in 0 until scene.size) {
        if (i == skip) continue
        val depth = s[i * 3 + 2]
        if (depth < 0f) continue
        val x = s[i * 3]; val y = s[i * 3 + 1]
        if (x < -40f * dp || x > f.width + 40f * dp || y < -40f * dp || y > f.height + 40f * dp) continue
        drawStar(art, scene, i, x, y, depth, t, explored, hearted, selected, dp, dim)
    }
}

/** One star: a twinkling glow sprite in its family's colour, ringed if hearted. */
private fun DrawScope.drawStar(
    art: GalaxyArt, scene: GalaxyScene, i: Int, x: Float, y: Float, depth: Float, t: Float,
    explored: BooleanArray, hearted: BooleanArray, selected: Int, dp: Float, dim: Float = 0f,
) {
    val canvas = drawContext.canvas.nativeCanvas
    val lit = explored[i]
    val twinkle = 0.8f + 0.2f * sin(t * 1.6f + scene.phase[i])
    // The sprite carries the halo, so it is drawn well past the star's own size.
    val r = starSizePx(scene.prominence[i], lit || i == selected, depth, dp) * 1.25f * twinkle
    art.rect.set(x - r, y - r, x + r, y + r)
    art.add.alpha = ((if (lit) 1f else 0.7f) * fog(depth) * (1f - dim) * 255).toInt()
    canvas.drawBitmap(art.starSprite[scene.family[i]], null, art.rect, art.add)
    if (hearted[i]) {
        art.plain.color = art.famColor[scene.family[i]].copy(alpha = 0.9f).toArgb()
        art.plain.strokeWidth = 1.4f * dp
        art.plain.pathEffect = art.heartDash
        canvas.drawCircle(x, y, r * 0.55f + 4f * dp, art.plain)
        art.plain.pathEffect = null
    }
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

    /** How far in the system was faded last frame, 0..1. */
    var fade = 0f
    val order = IntArray(n + 1)
    val orbit = FloatArray(ORBIT_SEGMENTS * 4)
    val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val cover = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val matrix = android.graphics.Matrix()
    val src = RectF()

    /** The dark side of a sphere lit from +x. */
    val shade: Bitmap = Bitmap.createBitmap(SPRITE, SPRITE, Bitmap.Config.ARGB_8888).also { b ->
        val c = android.graphics.Canvas(b)
        val h = SPRITE / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                h + h * 0.55f, h, SPRITE * 0.82f,
                intArrayOf(0x00000000, 0x00000000, 0x66000000, 0xEE000000.toInt()),
                floatArrayOf(0f, 0.36f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(h, h, h, paint)
    }

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
    }
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
    val fade = (((sys.outerOrbit * scale) - SYSTEM_MIN_DP * dp) / (SYSTEM_FADE_DP * dp)).coerceIn(0f, 1f) *
        appear.coerceIn(0f, 1f)
    bodies.fade = fade
    if (fade <= 0.01f || sys.planets.isEmpty()) {
        if (sDepth > 0f) drawStar(art, scene, star, sx, sy, sDepth, t, explored, hearted, star, dp)
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
            if (!f.project(p3[0], p3[1], p3[2], p3, 0)) continue
            val units = if (mi < 0) planet.radius else planet.moons[mi].radius
            // Never specks: a planet is a disc with a cover, a moon a target.
            val minR = (if (mi < 0) 5f else 2f) * dp * spread.coerceAtMost(1f)
            bodies.x[k] = p3[0]; bodies.y[k] = p3[1]; bodies.depth[k] = p3[2]
            bodies.r[k] = (units * spread.coerceAtMost(1f) * f.scaleAt(p3[2])).coerceIn(minR, MAX_BODY_DP * dp)
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
    for (o in 0 until count) {
        val k = bodies.order[o]
        if (k == starSlot) {
            if (sDepth > 0f) drawStar(art, scene, star, sx, sy, sDepth, t, explored, hearted, star, dp)
            continue
        }
        // slot = planet * stride + moon + 1, the planet itself being moon -1.
        val p = k / (1 + PlanetSystem.MAX_MOONS)
        val mi = k % (1 + PlanetSystem.MAX_MOONS) - 1
        val x = bodies.x[k]; val y = bodies.y[k]; val r = bodies.r[k]
        // The lit side faces the star, wherever it is on screen.
        val toward = if (sDepth > 0f) kotlin.math.atan2(sy - y, sx - x) else -PI.toFloat() / 2f
        art.rect.set(x - r, y - r, x + r, y + r)
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
        canvas.drawBitmap(bodies.shade, null, art.rect, bodies.sprite)
        canvas.restore()
        if (mi < 0) {
            // A thin atmosphere, so a dark cover still reads as a planet.
            bodies.rim.color = android.graphics.Color.HSVToColor(floatArrayOf(sys.planets[p].hue, 0.35f, 1f))
            bodies.rim.alpha = (0.5f * fade * 255).toInt()
            bodies.rim.strokeWidth = (r * 0.07f).coerceIn(0.8f * dp, 2.5f * dp)
            canvas.drawCircle(x, y, r, bodies.rim)
        } else if (ping > 0f && pinged[0] == p && pinged[1] == mi) {
            art.plain.color = SCAN.copy(alpha = ping).toArgb()
            art.plain.strokeWidth = 1.5f * dp
            canvas.drawCircle(x, y, r + (1f - ping) * 22f * dp + 3f * dp, art.plain)
        }
    }
    return true
}

/** How far the other stars dim while a solar system is showing. */
private const val STAR_DIM_NEAR_SYSTEM = 0.55f

/** Below this, on screen, a system is a smudge and only its star is drawn. */
private const val SYSTEM_MIN_DP = 28f
private const val SYSTEM_FADE_DP = 60f
private const val MAX_BODY_DP = 140f

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
    val taken = ArrayList<FloatArray>(MAX_LABELS + 8)
    var used = 0

    /** Draws [text] with its top-left at [x], [y] if it is clear of everything already placed. */
    fun place(text: String, textStyle: TextStyle, color: Color, x0: Float, y0: Float, centred: Boolean, alpha: Float): Boolean {
        if (used >= MAX_LABELS) return false
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
        if (used >= MAX_LABELS) break
        put(best[n], art.famLabel[scene.family[best[n]]])
    }

}
