package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.performance.LocalLowPerformance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * God rays through the lyrics: volumetric light scattering as a screen-space
 * post-process (GPU Gems 3, ch. 13), the way Community Play 3D's "God rays,
 * what's that?" and the Shadertoy "Crepuscular light" (ls2Xzd) do it.
 *
 * The light is a direction in 3D — an azimuth around the screen and an
 * elevation out of it — projected onto the lyric surface ([GodRayGeometry]).
 * Every pixel then marches toward that point and gathers what it passes:
 *
 *  - **Letters** ([LyricsFxSettings.GOD_RAYS_LETTERS]): the sung line is the
 *    light. Samples inside its band are summed, so each letter streams a shaft
 *    away from the light — the Shadertoy's image-as-emitter, with the line as
 *    the image.
 *  - **Backlight** ([LyricsFxSettings.GOD_RAYS_BACKLIGHT]): the article's
 *    occlusion pass. A disc of light sits at the projected point, every lyric
 *    blocks it by its alpha, and the letters' shadows stream through the
 *    shafts.
 *
 * This modifier is the lyric view's own rays, for a screen with no
 * [LyricBackdropFx] under it (the legacy player). Apply it OUTSIDE the lyric
 * surface's side padding and outside the glass, so the shafts are computed
 * from the finished glass letters and can run to the screen edge (see
 * `SyncedLyricsView`). The render effect cannot draw past its own layer, so a
 * layer inset from the edge would cut every shaft off at the inset in a hard
 * vertical line.
 *
 * The light's `band` ([rememberLyricRayLight]) is what is being sung, in root
 * px: the line, running
 * the full width ([GodRayGeometry.UNBOUNDED] either side), or with
 * [LyricsFxSettings.godRaysFollowWord] the one word. It decides what shines in
 * Letters mode, where the light sits when it faces you head-on, and where the
 * shafts are dimmed so the words stay readable. Null makes the whole surface
 * shine, with no legibility guard.
 *
 * Requires API 33 (RuntimeShader). Below that, with the low-performance glass
 * switch on, or if the shader will not compile, this is a no-op.
 */
@Composable
internal fun Modifier.lyricGodRays(
    light: LyricRayLight?,
    output: RayOutput = RayOutput.LETTERS_AND_LIGHT,
): Modifier {
    if (light == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(godRaysModifier(light, output))
}

/**
 * The god rays' light over one lyric surface, shared by the rays and by the
 * glass letters under them, so the letters catch the very light the shafts
 * come from (see the `uRay*` uniforms in LiquidGlass.kt).
 *
 * Built in composition and read in each layer's draw phase. Everything that
 * moves the light — the clock, the tilt, the beat, the sung band, the lyric
 * surface's box — is snapshot state, so every layer reruns when it moves and
 * nothing recomposes.
 *
 * It is computed once, in root px, and each layer that draws it — the
 * backdrop's shafts, the light on the letters, the glass, the shadow — only
 * moves it into its own pixels ([frameFor]). Nothing in it depends on how big
 * the layer drawing it is. It used to be worked out as shares of the rays
 * layer's long side, which is the preview's width in the Studio and the
 * screen's height in the player, so the same settings put the light more than
 * twice as far from the sung line in the player and the two looked nothing
 * alike (seen on device). Every distance is a share of [scale], the window's
 * short side, instead: the same on every screen the lyrics are shown on, and
 * about the long side of the lyric surface the rays were tuned on.
 */
@Stable
internal class LyricRayLight(
    val fx: LyricsFxSettings,
    accent: Color,
    val moving: Boolean,
    private val time: State<Float>,
    private val tilt: State<Offset>,
    private val pulse: State<Float>?,
    /** What is being sung, in root px. */
    private val band: () -> Rect?,
    /**
     * The lyric surface the light is set over, in root px: the light centres
     * on it when nothing is sung, or all of it is. Null until it is laid out.
     */
    private val lettersBox: () -> Rect?,
    /** The length every distance in the light is a share of, px: the window's short side. */
    private val scale: Float,
) {
    /** The light's colour: the album accent, a third of the way to white. */
    val color: Color = lerp(accent, Color.White, 0.35f)

    /** This frame's light in root px. Null until the lyrics have been laid out. */
    private fun frameInRoot(): RayFrame? {
        val box = lettersBox()?.takeIf { it.width > 0f && it.height > 0f } ?: return null
        if (scale <= 0f) return null
        val t = if (moving) time.value else 0f
        // "All lyrics" is the Shadertoy's whole image shining: no band at all.
        val line = if (fx.godRaysAllLyrics) null else band()?.takeIf { it.height > 0f && it.width > 0f }
        val center = GodRayGeometry.lightCenter(line, box)
        val (az, el) = GodRayGeometry.animatedAngles(fx, t)
        val tiltNow = tilt.value
        val point = GodRayGeometry.lightPoint(center, az, el, focal = GodRayGeometry.FOCAL_SHARE * scale) +
            Offset(-tiltNow.x, tiltNow.y) * (fx.godRayTilt * GodRayGeometry.TILT_SHARE * scale) +
            GodRayGeometry.swayOffset(fx.godRaySway, t, scale)
        // The kick brightens the shafts and pushes them a little further.
        val beat = (pulse?.value ?: 0f) * fx.bassReact * fx.godRayBeat
        return RayFrame(
            light = point,
            center = center,
            line = line,
            exposure = fx.godRayExposure * (1f + 1.2f * beat),
            density = (fx.godRayDensity * (1f + 0.15f * beat)).coerceAtMost(1f),
            elevationDeg = el,
            scale = scale,
            time = t,
        )
    }

    /**
     * This frame's light for a layer whose top-left sits at [originInRoot],
     * in that layer's own pixels. Null until the lyrics have been laid out.
     */
    fun frameFor(originInRoot: Offset): RayFrame? = frameInRoot()?.shiftedBy(-originInRoot)

    /** This frame's light in words, for [RaysProbe]'s report: what is missing when there is none. */
    fun describe(): String {
        val box = lettersBox()
        val line = band()
        val f = frameInRoot()
        return "scale=${scale.toInt()} " +
            "box=${box?.let { "${it.left.toInt()},${it.top.toInt()} ${it.width.toInt()}x${it.height.toInt()}" } ?: "none"} " +
            "band=${line?.let { "${it.top.toInt()}..${it.bottom.toInt()}" } ?: "none"} " +
            if (f == null) {
                "light=NONE"
            } else {
                "light=${f.light.x.toInt()},${f.light.y.toInt()} " +
                    "exp=${"%.2f".format(f.exposure)} density=${"%.2f".format(f.density)}"
            }
    }
}

/** One frame of the god rays' light, in some layer's own pixels. */
internal data class RayFrame(
    val light: Offset,
    val center: Offset,
    val line: Rect?,
    val exposure: Float,
    val density: Float,
    val elevationDeg: Float,
    /** The length the light's distances are shares of, px ([LyricRayLight]'s scale). */
    val scale: Float,
    val time: Float,
) {
    fun shiftedBy(d: Offset): RayFrame = copy(light = light + d, center = center + d, line = line?.translate(d))
}

/**
 * The light for a lyric surface, or null when there are no rays to light it:
 * rays off, the low-performance glass switch on, or below API 33.
 */
@Composable
internal fun rememberLyricRayLight(
    accent: Color,
    pulse: State<Float>? = null,
    /** What is being sung, in root px. */
    band: () -> Rect? = { null },
    /** The lyric surface, in root px; read in the draw phase. */
    lettersBox: () -> Rect?,
    fx: LyricsFxSettings = LocalLyricsFx.current,
    /** Who this light is for, in the Debug Log's rays report. */
    debugName: String = "lyrics",
): LyricRayLight? {
    if (!fx.godRays) return null
    if (LocalLowPerformance.current.disableLiquidGlass) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    // A clock only when something on the rays moves: the dust, the orbit or
    // the sway. Still rays redraw only when the lyrics under them do.
    val moving = GodRayGeometry.isMoving(fx)
    val time = rememberFrameSeconds(animated = moving)
    val tilt = if (fx.godRayTilt > 0f) rememberGravityTilt() else NoTilt
    val window = LocalWindowInfo.current.containerSize
    val scale = min(window.width, window.height).toFloat()
    val light = remember(fx, accent, moving, time, tilt, pulse, band, lettersBox, scale) {
        RaysProbe.lightsBuilt++
        LyricRayLight(fx, accent, moving, time, tilt, pulse, band, lettersBox, scale)
    }
    // Keyed on nothing, so a light rebuilt every recomposition still gets
    // reported, as a high "lights built", instead of restarting the timer.
    val current = rememberUpdatedState(light)
    LaunchedEffect(Unit) {
        var lastKey = ""
        var lastAt = 0L
        while (true) {
            delay(2_000)
            val state = current.value.describe()
            val key = state + RaysProbe.kinds()
            val now = System.currentTimeMillis()
            // A line when anything changes, and otherwise every 10 s.
            if (key != lastKey || now - lastAt >= 10_000) {
                LyricsDebug.log("rays[$debugName]: $state | ${RaysProbe.kinds()} | ${RaysProbe.drain()}")
                lastKey = key
                lastAt = now
            } else {
                RaysProbe.drain()
            }
        }
    }
    return light
}

/**
 * Where a layer drawing the light sits in the root, recorded as it lays out:
 * the light is handed out in root px and each layer moves it into its own.
 */
internal fun Modifier.rayLayerOrigin(origin: MutableState<Offset?>): Modifier =
    onGloballyPositioned { origin.value = it.positionInRoot() }

/** The god-rays shader, or null on a device that will not compile it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun rememberGodRaysShader(): RuntimeShader? = remember {
    runCatching { RuntimeShader(GOD_RAYS_SRC) }
        .onSuccess { LyricsDebug.log("god-rays shader compiled") }
        .onFailure { LyricsDebug.log("god-rays shader FAILED to compile: ${it.message}") }
        .getOrNull()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun godRaysModifier(light: LyricRayLight, output: RayOutput): Modifier {
    val shader = rememberGodRaysShader() ?: return Modifier
    val origin = remember { mutableStateOf<Offset?>(null) }
    return Modifier.rayLayerOrigin(origin).graphicsLayer {
        val at = origin.value
        val f = at?.let(light::frameFor)
        renderEffect = if (f == null || size.minDimension <= 0f) {
            RaysProbe.hit(if (at == null) RaysProbe.Surface.NO_ORIGIN else RaysProbe.Surface.NO_LIGHT_FRAME)
            null
        } else {
            RaysProbe.hit(RaysProbe.Surface.LIT)
            setGodRayUniforms(shader, light, f, output)
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

/** What a rays layer hands back: [GOD_RAYS_SRC]'s `uRaysOnly` and `uOnLetters`. */
internal enum class RayOutput {
    /**
     * The letters with their light, under them or over them as
     * [LyricsFxSettings.godRaysOnTop] says: a lyric view with no backdrop.
     */
    LETTERS_AND_LIGHT,

    /**
     * The shafts alone: the backdrop, whose letters are a copy there only to
     * give the light. The real ones draw later, on top.
     */
    SHAFTS,

    /**
     * The real letters with the light that falls on them added, and nothing
     * around them: "On top", over the backdrop's shafts.
     */
    ON_LETTERS,
}

/**
 * Every uniform of [GOD_RAYS_SRC] for one frame [f] of [light], in the pixels
 * of the layer [f] was moved into. [output] says what the layer hands back.
 * [emission] scales how much light the letters give: the backdrop's copy is
 * taken before the glass, at full strength, where the shafts were tuned on the
 * glass's see-through letters.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Density.setGodRayUniforms(
    shader: RuntimeShader,
    light: LyricRayLight,
    f: RayFrame,
    output: RayOutput,
    emission: Float = 1f,
) {
    val fx = light.fx
    val samples = GodRayGeometry.samplesFor(fx.godRayQuality)
    val decay = GodRayGeometry.perSampleDecay(fx.godRayDecay, samples)

    shader.setFloatUniform("uLight", f.light.x, f.light.y)
    shader.setFloatUniform("uSource", fx.godRaySource.toFloat())
    shader.setFloatUniform("uOnTop", if (fx.godRaysOnTop) 1f else 0f)
    shader.setFloatUniform("uRaysOnly", if (output == RayOutput.SHAFTS) 1f else 0f)
    shader.setFloatUniform("uOnLetters", if (output == RayOutput.ON_LETTERS) 1f else 0f)
    shader.setFloatUniform("uDensity", f.density)
    shader.setFloatUniform("uReachCap", GodRayGeometry.REACH_CAP_SHARE * f.scale)
    shader.setFloatUniform("uSamples", samples.toFloat())
    shader.setFloatUniform("uDecay", decay)
    shader.setFloatUniform(
        "uSampleWeight",
        GodRayGeometry.sampleWeight(GodRayGeometry.LETTERS_GAIN * f.exposure * emission, decay, samples),
    )
    shader.setFloatUniform("uCenterTap", GodRayGeometry.CENTER_TAP)
    shader.setFloatUniform("uBacklightGain", GodRayGeometry.BACKLIGHT_GAIN * f.exposure)
    shader.setFloatUniform("uDecayRef", fx.godRayDecay)
    shader.setFloatUniform("uFalloffLen", f.density * f.scale)
    // Inflated a little: the pump and the per-letter wave carry glyphs a
    // few dp past the item's own box.
    val pad = 2.dp.toPx()
    val line = f.line
    if (line != null) {
        shader.setFloatUniform("uBand", line.left - pad, line.top - pad, line.right + pad, line.bottom + pad)
    } else {
        shader.setFloatUniform("uBand", 0f, 1f, 0f, 0f)
    }
    shader.setFloatUniform("uFeather", 5.dp.toPx(), 3.dp.toPx())
    shader.setFloatUniform("uGuard", GodRayGeometry.LEGIBILITY_GUARD)
    shader.setFloatUniform("uSunR", fx.godRaySunSize * f.scale)
    shader.setFloatUniform("uSunColor", light.color.red, light.color.green, light.color.blue)
    shader.setFloatUniform("uShimmer", fx.godRayShimmer)
    shader.setFloatUniform(
        "uStripeCells",
        GodRayGeometry.stripeCells(
            radius = max((f.light - f.center).getDistance(), 0.35f * f.scale),
            stripePx = 7.dp.toPx(),
        ).toFloat(),
    )
    shader.setFloatUniform("uTime", f.time)
    shader.setFloatUniform("uFrame", if (light.moving) GodRayGeometry.jitterFrame(f.time).toFloat() else 0f)
}

/**
 * Where the word being sung sits, for rays that follow it word by word.
 *
 * The karaoke line reports the word that is lighting up (or, between words,
 * the one that just did) in root coordinates; the rays layer subtracts its own
 * root position. Snapshot state, so the layer reruns its uniforms when the
 * light hops to the next word even with nothing else on it animating.
 *
 * Each report carries its line, and [rectFor] answers only for the line asked
 * about, so a new line never shines from the old one's last word while it
 * scrolls in. Clearing the report when the line changed was the first try, and
 * it raced: the clearing effect runs after layout, by which time the new
 * line's first word may already have reported, and it was wiped.
 */
internal class SungWordAnchor {
    private var sung by mutableStateOf<Pair<Int, Rect>?>(null)

    fun report(line: Int, rectInRoot: Rect) {
        sung = line to rectInRoot
    }

    fun rectFor(line: Int): Rect? = sung?.takeIf { it.first == line }?.second
}

/**
 * The arithmetic behind the rays, kept off the GPU so it can be tested: where
 * a 3D light lands on the screen, and how the article's decay and sample
 * weight survive a change in sample count.
 */
internal object GodRayGeometry {
    /** Perspective: px of offset per unit of cot(elevation), as a share of the surface's long side. */
    const val FOCAL_SHARE = 0.5f

    /**
     * The flattest the light may lie. cot(el) is unbounded as the elevation
     * reaches 0; past 3 (about 18°) the shafts are already near-parallel, and a
     * light any further out would stretch every march past the samples there
     * are to fill it.
     */
    const val COT_MAX = 3f

    /** Letters mode never marches further than this share of the long side. */
    const val REACH_CAP_SHARE = 0.8f

    /** How far a full tilt of the phone swings the light, as a share of the long side. */
    const val TILT_SHARE = 0.3f

    /**
     * Exposure 1 → this gain on Letters mode's weighted average. The
     * Shadertoy's taps add up to 3.293 (0.4 × (1 + 0.58767 × Σ 0.92^i over
     * 50)), so exposure 0.57 is the original's brightness.
     */
    const val LETTERS_GAIN = 5.8f

    /**
     * Exposure 1 → this gain in Backlight mode. Lower than Letters', because
     * here the light fills whole samples where a letter covers a sliver of one.
     */
    const val BACKLIGHT_GAIN = 3f

    /** How much of the light is taken back off under the sung letters, in their shape, so they stay readable. */
    const val LEGIBILITY_GUARD = 0.65f

    /** The sample count the article's decay is quoted against (the Shadertoy uses 50). */
    const val DECAY_REFERENCE_SAMPLES = 50

    /** The article's per-sample weight against its 0.4 first tap. */
    const val ARTICLE_WEIGHT = 0.58767f

    /**
     * The first tap, on the pixel itself, relative to a march sample: the
     * Shadertoy weighs it 0.4 and each sample 0.4 × 0.58767.
     */
    const val CENTER_TAP = 1f / ARTICLE_WEIGHT

    /**
     * A band edge that is not there: a whole line runs the full width. Large
     * and finite, because an infinity reaching the shader's smoothstep would
     * come back NaN.
     */
    const val UNBOUNDED = 100_000f

    /**
     * Where the light sits when it faces you head-on (90°): the middle of what
     * is being sung — the sung word, or the line's centre across the lyric
     * [surface] when the band runs the full width. The surface's centre with
     * no band. The surface, not the layer the rays are drawn in: the
     * backdrop's layer is the whole screen, and its centre is somewhere
     * around the song title.
     */
    fun lightCenter(band: Rect?, surface: Rect): Offset {
        if (band == null) return surface.center
        val x = if (band.left <= -UNBOUNDED / 2f || band.right >= UNBOUNDED / 2f) surface.center.x else band.center.x
        return Offset(x, band.center.y)
    }

    /** 4 is the Shadertoy's own 50, and the shader's loop length. */
    fun samplesFor(quality: Int): Int = when (quality.coerceIn(1, 4)) {
        1 -> 16
        2 -> 24
        3 -> 32
        else -> 50
    }

    /**
     * Where a light at ([azimuthDeg], [elevationDeg]) lands on the screen.
     *
     * Parallel shafts in 3D converge, in perspective, on the point where their
     * direction meets the screen: [center] when the light is straight behind
     * it (90°), sliding out along the azimuth as the light lowers, as
     * `focal * cot(elevation)`. Azimuth runs counter-clockwise from the right
     * with 90° straight up, the same convention as the glass's light angle;
     * screen y points down, hence the minus.
     */
    fun lightPoint(center: Offset, azimuthDeg: Float, elevationDeg: Float, focal: Float): Offset {
        val el = elevationDeg.coerceIn(0f, 90f) * (PI.toFloat() / 180f)
        val s = sin(el)
        val cot = if (s <= 1e-4f) COT_MAX else min(cos(el) / s, COT_MAX)
        val az = azimuthDeg * (PI.toFloat() / 180f)
        return Offset(center.x + focal * cot * cos(az), center.y - focal * cot * sin(az))
    }

    /** The direction the light is pointing at time [t]: the set angles, plus the orbit round the line. */
    fun animatedAngles(fx: LyricsFxSettings, t: Float): Pair<Float, Float> {
        val az = fx.godRayAzimuthDeg + fx.godRaySpinDps * t
        return (((az % 360f) + 360f) % 360f) to fx.godRayElevationDeg.coerceIn(0f, 90f)
    }

    /**
     * The sway: the Shadertoy's own wandering light, `pos = (sin(t), sin(t *
     * 0.913)) * 0.5` in units of the screen's height, scaled by [sway]. Its uv
     * y points up and the screen's down, hence the minus.
     */
    fun swayOffset(sway: Float, t: Float, height: Float): Offset {
        if (sway <= 0f) return Offset.Zero
        val r = 0.5f * height * sway
        return Offset(sin(t) * r, -sin(t * 0.913f) * r)
    }

    fun isMoving(fx: LyricsFxSettings): Boolean =
        fx.godRayShimmer > 0f || fx.godRaySpinDps != 0f || fx.godRaySway > 0f

    /** The exposure the presets were tuned around, where the glass catches exactly its catch setting. */
    const val GLASS_CATCH_REFERENCE_EXPOSURE = 0.6f

    /**
     * How much of the rays' light the glass letters catch: the catch setting,
     * scaled by how bright the shafts are this frame, so a beat flare flashes
     * on the letters too. Capped, so a hot exposure cannot blow the glass out.
     */
    fun glassRayAmount(catch: Float, exposure: Float): Float =
        catch.coerceIn(0f, 1f) * (exposure / GLASS_CATCH_REFERENCE_EXPOSURE).coerceIn(0f, 2.5f)

    /**
     * How far the light stands off the letters, as the z of the direction the
     * glass shades with, against the screen-space reach of 1. A light behind
     * the line (90°) stands high, so the letters are lit nearly head-on; a
     * raking light (0°) lies low, so the bevels facing it take nearly all of it.
     */
    fun glassLightLift(elevationDeg: Float): Float =
        0.15f + 0.5f * sin(elevationDeg.coerceIn(0f, 90f) * (PI.toFloat() / 180f))

    /**
     * The article quotes decay per sample at 50 samples. Holding the decay
     * over the whole march fixed instead means changing the quality setting
     * changes how clean the shafts are and not how long they are.
     */
    fun perSampleDecay(decay: Float, samples: Int): Float =
        decay.coerceIn(0f, 1f).pow(DECAY_REFERENCE_SAMPLES.toFloat() / samples.coerceAtLeast(1))

    /**
     * Turns the shader's decayed sum — the [CENTER_TAP] plus every march
     * sample — into a weighted average times [gain], so the brightness does
     * not depend on the sample count either.
     */
    fun sampleWeight(gain: Float, perSampleDecay: Float, samples: Int): Float {
        var sum = CENTER_TAP
        var w = 1f
        repeat(samples.coerceAtLeast(1)) {
            sum += w
            w *= perSampleDecay
        }
        return gain / sum
    }

    /**
     * How many dust stripes go round the light: a whole number, so the noise
     * meets itself where the angle wraps instead of leaving a seam, and each
     * stripe about [stripePx] wide at [radius] from it.
     */
    fun stripeCells(radius: Float, stripePx: Float): Int =
        max((2f * PI.toFloat() * radius / stripePx.coerceAtLeast(1f)).roundToInt(), 8)

    /**
     * The jitter pattern's frame: the interleaved-gradient-noise offset moves
     * each frame, so the sampling grain averages out over time instead of
     * sitting still as a texture. 64 frames, then it repeats.
     */
    fun jitterFrame(t: Float): Int = (floor(t * 60f).toInt() % 64 + 64) % 64
}

// Volumetric light scattering (GPU Gems 3, ch. 13) over the lyric layer.
// Output stays premultiplied: the rays are clamped to rgb <= alpha before they
// are composited with the letters, which are either drawn over the light
// (under) or have it added onto them (on top).
private const val GOD_RAYS_SRC = """
uniform shader content;
uniform float2 uLight;          // the light's point on this surface, px (may lie off it)
uniform float uSource;          // 0 = the sung line shines, 1 = a light behind the lyrics
uniform float uOnTop;           // 1 = add the light over the letters, 0 = draw it under them
uniform float uRaysOnly;        // 1 = the backdrop layer: return the shafts alone, not the letters
uniform float uOnLetters;       // 1 = return only the light on the letters, in their shape ("On top" over a backdrop)
uniform float uDensity;         // share of the way to the light each pixel gathers
uniform float uReachCap;        // letters: the longest march, px
uniform float uSamples;         // 16 / 24 / 32 / 50
uniform float uDecay;           // letters: per-sample decay, already converted for uSamples
uniform float uSampleWeight;    // letters: turns the decayed sum into an average times exposure
uniform float uCenterTap;       // letters: the article's first tap, on the pixel itself (1 / weight)
uniform float uBacklightGain;   // backlight: exposure on the plain average
uniform float uDecayRef;        // backlight: decay per 1/50 of uFalloffLen
uniform float uFalloffLen;      // backlight: how far the shafts reach, px
uniform float4 uBand;           // what is sung, px (left, top, right, bottom); bottom < top = nothing
uniform float2 uFeather;        // band edge: outside, inside, px
uniform float uGuard;           // how much light is taken off under the sung letters
uniform float uSunR;            // backlight disc radius, px
uniform float3 uSunColor;
uniform float uShimmer;         // dust in the shafts
uniform float uStripeCells;     // whole number of dust stripes round the light
uniform float uTime;
uniform float uFrame;           // jitter frame, 0..63

bool hasBand() { return uBand.w >= uBand.y; }

// 1 inside what is being sung, easing to 0 across its feathered edges.
float bandMask(float2 s) {
    if (!hasBand()) { return 1.0; }
    float y = smoothstep(uBand.y - uFeather.x, uBand.y + uFeather.y, s.y)
            * (1.0 - smoothstep(uBand.w - uFeather.y, uBand.w + uFeather.x, s.y));
    float x = smoothstep(uBand.x - uFeather.x, uBand.x + uFeather.y, s.x)
            * (1.0 - smoothstep(uBand.z - uFeather.y, uBand.z + uFeather.x, s.x));
    return x * y;
}

// How much of a sample is lit letter rather than the soft shadow under it
// (lyricShadow lays it inside this layer): its brightness for its coverage.
// A glyph — accent, white, glass — passes whole; the shadow is black, so it
// neither shines nor blocks the light. Without this the shadow streaked the
// shafts dark and stood in front of them.
float lit(float4 c) {
    if (c.a < 0.004) { return 0.0; }
    return clamp(max(c.r, max(c.g, c.b)) * 4.0 / c.a, 0.0, 1.0);
}

float hash11(float n) { return fract(sin(n * 127.1 + 311.7) * 43758.5453); }

// Dust: value noise round the light, in a whole number of cells so it closes
// on itself where the angle wraps, breathing slowly in time rather than
// travelling. 0..1.
float dust(float2 p) {
    float2 d = p - uLight;
    float u = (atan(d.y, d.x) / 6.2831853 + 0.5) * uStripeCells;
    float i0 = floor(u);
    float f = u - i0;
    f = f * f * (3.0 - 2.0 * f);
    float tt = uTime * 0.35;
    float j0 = mod(floor(tt), 256.0);
    float g = fract(tt);
    g = g * g * (3.0 - 2.0 * g);
    float i1 = mod(i0 + 1.0, uStripeCells);
    i0 = mod(i0, uStripeCells);
    float a0 = mix(hash11(i0 + j0 * 57.0), hash11(i1 + j0 * 57.0), f);
    float a1 = mix(hash11(i0 + (j0 + 1.0) * 57.0), hash11(i1 + (j0 + 1.0) * 57.0), f);
    return mix(a0, a1, g);
}

half4 main(float2 p) {
    float4 src = float4(content.eval(p));
    bool back = uSource > 0.5;
    // Only the letters' own light is wanted here, and where there is no
    // letter there is none: most of the layer skips the march.
    if (uOnLetters > 0.5 && src.a < 0.004) {
        return half4(src);
    }
    // What a pixel with no light hands back: the backdrop's letters are a
    // copy, only the light's source, never drawn.
    float4 unlit = uRaysOnly > 0.5 ? float4(0.0) : src;

    float2 toL = uLight - p;
    float dist = length(toL);
    float2 dir = toL / max(dist, 0.001);
    float reach = uDensity * (back ? dist : min(dist, uReachCap));

    // Letters: only what is being sung shines, so a pixel whose march never
    // comes near it gathers nothing. Skipping those is most of the screen when
    // the light is above or below the line, and nearly all of it for one word.
    if (!back && hasBand()) {
        float2 end = p + dir * reach;
        float2 lo = min(p, end);
        float2 hi = max(p, end);
        if (hi.y < uBand.y - uFeather.x || lo.y > uBand.w + uFeather.x ||
            hi.x < uBand.x - uFeather.x || lo.x > uBand.z + uFeather.x) {
            // Not the copy: handed back from the backdrop, it drew the plain
            // letters of every line whose march missed the sung one under
            // the glass ones.
            return half4(unlit);
        }
    }

    float stepLen = reach / uSamples;
    // Interleaved gradient noise (Jimenez 2014) as each pixel's start offset:
    // the fix hornet gives under the Shadertoy, which turns the march's
    // banding into fine grain and lets it get by on far fewer samples.
    float2 jp = p + float2(5.588238 * uFrame, 0.0);
    float jitter = fract(52.9829189 * fract(dot(jp, float2(0.06711056, 0.00583715))));

    // The Shadertoy's first tap, `color = texture(tc) * 0.4`, on the pixel
    // itself at 1 / weight of a march sample. Without it the port was 5% off
    // the original; with it, and the taps at 1..N steps below, it matches
    // to 0.1% at 50 samples.
    float4 acc = back ? float4(0.0) : src * (lit(src) * bandMask(p) * uCenterTap);
    float w = 1.0;
    for (int i = 0; i < 50; i++) {
        if (float(i) < uSamples) {
            // 1 - jitter .. N - jitter steps toward the light: the Shadertoy
            // pushes tc back by the jitter, then steps before every sample.
            float2 s = p + dir * ((float(i) + 1.0 - jitter) * stepLen);
            float4 c = float4(content.eval(s));
            if (back) {
                float r = distance(s, uLight);
                float sun = 0.75 * smoothstep(uSunR, uSunR * 0.2, r)
                          + 0.12 * smoothstep(uSunR * 2.2, 0.0, r);
                float k = sun * (1.0 - c.a * lit(c));
                acc += float4(uSunColor * k, k);
            } else {
                acc += c * (lit(c) * bandMask(s) * w);
                w *= uDecay;
            }
        }
    }
    if (back) {
        // The light fills the far end of every march, so it is averaged
        // plainly and the article's decay is counted out from the light
        // instead: the shafts fade with distance from it.
        acc *= (uBacklightGain / uSamples) * pow(uDecayRef, 50.0 * dist / max(uFalloffLen, 1.0));
    } else {
        acc *= uSampleWeight;
    }

    acc *= 1.0 + uShimmer * (dust(p) * 1.3 - 0.65);
    // Legibility: the light is taken down under the sung letters themselves,
    // in their own shape, so they stay readable while the shafts run on
    // through the gaps between them. It used to be dimmed across the whole
    // band, and on device that showed as a darker rectangle cut out of the
    // shafts wherever the sung line was.
    if (hasBand()) {
        acc *= 1.0 - uGuard * src.a * lit(src) * bandMask(p);
    }

    float a = clamp(acc.a, 0.0, 1.0);
    float3 rays = min(clamp(acc.rgb, 0.0, 1.0), float3(a));
    float4 light = float4(rays, a);
    // The backdrop layer's letters are a copy, there only to give the light;
    // the real ones draw later, on top, so only the shafts are handed back.
    if (uRaysOnly > 0.5) {
        return half4(light);
    }
    // "On top" over a backdrop: the shafts are already under the letters, so
    // what is left of the article's composite (src + rays, below) is the
    // light that falls on the letters themselves, added to their own pixels
    // — capped where that composite caps it, at what the letters cover, or a
    // soft glyph edge would glow brighter than it is solid. Over the shafts,
    // that is the composite. The cap has to be measured on these, the real
    // letters: measured on the backdrop's copy, which is drawn before the
    // glass at full strength, it found no room at all and the sung line
    // stood grey under its own light. Their alpha is left as it is, so the
    // edge fade's offscreen buffer takes them like any letters.
    if (uOnLetters > 0.5) {
        float topA = src.a + a * (1.0 - src.a);
        float3 room = max(float3(topA) - src.rgb - rays * (1.0 - src.a), float3(0.0));
        float3 add = min(rays * src.a, room);
        return half4(half3(min(src.rgb + add, float3(src.a))), half(src.a));
    }
    if (uOnTop > 0.5) {
        // The article's composite: the light is added onto the scene.
        float outA = src.a + a * (1.0 - src.a);
        return half4(half3(min(src.rgb + rays, float3(outA))), half(outA));
    }
    // Under: back to front, the shadow on the background, the shafts, then
    // the letters, crisp in front of their own light.
    float4 letters = src * lit(src);
    float4 shade = src - letters;
    return half4(letters + (light + shade * (1.0 - a)) * (1.0 - letters.a));
}
"""
