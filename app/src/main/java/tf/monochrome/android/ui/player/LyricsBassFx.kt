package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared FFT tap, provided by the player screen so lyric composables deep
 * in the tree can drive audio-reactive effects. Null (the default) disables
 * the bass path entirely — previews and tests never touch the audio engine.
 */
val LocalLyricsSpectrum = compositionLocalOf<SpectrumAnalyzerTap?> { null }

/** One glyph's screen position (root coordinates) + its wave phase. */
data class GlyphAnchor(
    val center: Offset,
    val halfW: Float,
    val halfH: Float,
    val phase: Float,
)

/**
 * Screen-position registry for the active line's glyphs. Each active
 * [Letter3DText] reports its root-coordinate bounds here; [LyricsFxLayer] — a
 * full-screen, UNCLIPPED layer — draws each glyph's god rays at that
 * position. Because the FX draws on a layer with no clipping ancestor, the
 * light is impossible to cut by any canvas/container: rays only ever run off
 * the physical screen edge, never against a surface rectangle.
 */
class LyricGlyphAnchors {
    val glyphs = mutableStateMapOf<Int, GlyphAnchor>()
    var lineCenter by mutableStateOf<Offset?>(null)
    var lineHalf by mutableStateOf(Size.Zero)
    fun reset() {
        glyphs.clear()
        lineCenter = null
        lineHalf = Size.Zero
    }
}

val LocalLyricGlyphAnchors = compositionLocalOf<LyricGlyphAnchors?> { null }

/** Shared bass pulse so the line pump and the full-screen rays use one analyzer stake and breathe together. */
val LocalBeatPulse = compositionLocalOf<State<Float>?> { null }

// 40–110 Hz — the kick/bass fundamentals — mapped into the tap's 256
// log-spaced bins (20 Hz..20 kHz): log10(f/20)/3 * 255.
private const val BASS_BIN_START = 26
private const val BASS_BIN_END = 62

private const val SPRING_STIFFNESS = 300f

/**
 * Per-frame bass pulse in 0..~1.3 (>1 on overshoot). FFT bass bins → dB →
 * normalized level → attack/release envelope → underdamped spring (attack,
 * release, bounce are Studio settings). Read from draw/layer lambdas only, so
 * the pulse never causes recomposition. Ref-counts a stake on the analyzer
 * for exactly as long as it is composed.
 */
@Composable
internal fun rememberBassPulse(): State<Float> =
    rememberBassPulse(LocalLyricsSpectrum.current, LocalLyricsFx.current)

@Composable
internal fun rememberBassPulse(tap: SpectrumAnalyzerTap?, fx: LyricsFxSettings): State<Float> {
    val pulse = remember { mutableFloatStateOf(0f) }
    if (tap == null) return pulse

    DisposableEffect(tap) {
        tap.acquire()
        LyricsDebug.log("FFT analyzer staked (bass pulse active)")
        onDispose {
            tap.release()
            LyricsDebug.log("FFT analyzer released (bass pulse stopped)")
        }
    }
    LaunchedEffect(tap, fx.attackMs, fx.releaseMs, fx.bounce) {
        val attackSec = (fx.attackMs / 1000f).coerceAtLeast(0.001f)
        val releaseSec = (fx.releaseMs / 1000f).coerceAtLeast(0.01f)
        val springDamping = 2f * fx.springDampingRatio * sqrt(SPRING_STIFFNESS)
        var env = 0f
        var pos = 0f
        var vel = 0f
        var lastNanos = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos < 0) 0.016f
                else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = now
                val bins = tap.spectrumBins.value
                var sum = 0f
                for (b in BASS_BIN_START..BASS_BIN_END) sum += bins[b]
                val db = sum / (BASS_BIN_END - BASS_BIN_START + 1)
                val raw = (db / 12f).coerceIn(0f, 1f)
                val coef = if (raw > env) 1f - exp(-dt / attackSec) else 1f - exp(-dt / releaseSec)
                env += (raw - env) * coef
                vel += ((env - pos) * SPRING_STIFFNESS - vel * springDamping) * dt
                pos += vel * dt
                pulse.floatValue = pos.coerceIn(0f, 1.6f)
            }
        }
    }
    return pulse
}

/**
 * Line-level part of the beat FX: the element pumps with the bass (scale) and
 * pops in on activation. It also reports the line's root-coordinate bounds
 * into [anchors] so [LyricsFxLayer] can bloom a glow behind the whole line.
 * The RAYS are per-glyph (see [reportGlyphAnchor] + [LyricsFxLayer]); nothing
 * is drawn inside this element, so nothing here can be clipped.
 */
internal fun Modifier.bassBeat(
    pulse: State<Float>,
    popScale: () -> Float,
    fx: LyricsFxSettings,
    anchors: LyricGlyphAnchors?,
): Modifier {
    if (fx.bassReact <= 0.01f) return this
    var mod = this.graphicsLayer {
        val p = pulse.value * fx.bassReact
        val s = popScale() * (1f + fx.pumpAmount * p)
        scaleX = s
        scaleY = s
    }
    if (anchors != null) {
        mod = mod.onGloballyPositioned { coords ->
            anchors.lineCenter = coords.boundsInRoot().center
            anchors.lineHalf = Size(coords.size.width / 2f, coords.size.height / 2f)
        }
    }
    return mod
}

/**
 * Publishes a single glyph's screen position into [anchors] under [key] while
 * composed, removing it on dispose. Applied to each active-line glyph so the
 * full-screen [LyricsFxLayer] can draw that glyph's rays at its real screen
 * location — per-letter, and impossible to clip.
 */
@Composable
internal fun Modifier.reportGlyphAnchor(
    anchors: LyricGlyphAnchors?,
    key: Int,
    phase: Float,
): Modifier {
    if (anchors == null) return this
    DisposableEffect(anchors, key) {
        onDispose { anchors.glyphs.remove(key) }
    }
    return this.onGloballyPositioned { coords ->
        anchors.glyphs[key] = GlyphAnchor(
            center = coords.boundsInRoot().center,
            halfW = coords.size.width / 2f,
            halfH = coords.size.height / 2f,
            phase = phase,
        )
    }
}

/**
 * Full-screen god-ray + glow layer. Draws each active glyph's rays at its
 * reported screen position, plus one soft glow behind the whole line. Lives
 * on a layer with no clipping ancestor, so the light can never be cut by a
 * canvas — beams simply run off the screen edge at worst. Draw-phase only.
 */
@Composable
internal fun LyricsFxLayer(
    anchors: LyricGlyphAnchors,
    pulse: State<Float>,
    accent: Color,
    fx: LyricsFxSettings,
    modifier: Modifier = Modifier,
) {
    if (fx.bassReact <= 0.01f) return
    val time = rememberFrameSeconds()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                val p = (pulse.value * fx.bassReact).coerceIn(0f, 1.6f)
                if (p <= 0.03f) return@drawBehind

                // Rays and glow share one album-accent colour, hue-rotated by the
                // user's setting.
                val rayColor = accent.shiftHue(fx.rayHueShift)

                // One soft bloom behind the whole line.
                anchors.lineCenter?.let { lc ->
                    val c = lc - origin
                    val glowR = anchors.lineHalf.height + fx.glowRadiusDp.dp.toPx() * (1f + p)
                    drawGlow(c, glowR, p, rayColor, fx)
                }

                // Crepuscular god rays: soft, wide, volumetric light shafts fanning
                // out from ONE source above the lyric line (like sun/underwater god
                // rays), additively blended so overlaps bloom — instead of a busy
                // starburst rooted on every letter. Reach breathes with the pulse.
                if (fx.rayCount > 0) {
                    anchors.lineCenter?.let { lc ->
                        val c = lc - origin
                        val pulseReach = (1f - fx.rayPulseAmount) + fx.rayPulseAmount * (0.5f + 0.5f * p)
                        val reach = size.height * (0.5f + 1.0f * fx.rayLength) * pulseReach
                        val driftRad = time.value * (fx.raySpinDegPerSec * PI.toFloat() / 180f)
                        drawGodRayShafts(c, reach, p, rayColor, fx, driftRad, time.value)
                    }
                }
            },
    )
}

/** Rotate a colour's hue by [degrees] (0 = unchanged), keeping saturation/value. */
private fun Color.shiftHue(degrees: Float): Color {
    if (degrees == 0f) return this
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor((alpha * 255f).toInt().coerceIn(0, 255), hsv))
}

/**
 * Draws the lyric line's crepuscular god rays: soft, wide light shafts fanning
 * out from a single source above the line and blended additively so overlaps
 * bloom into volumetric light (sun / underwater god-ray look), rather than a
 * per-letter starburst.
 *
 * Honours the full ray parameter set: [LyricsFxSettings.rayAngleDeg] tilts the
 * whole fan, [raySpreadDeg] is the fan's cone angle (360 → a full radial sun),
 * [rayCount] the number of shafts, [rayLength]→reach, [rayWidthDp] shaft width,
 * [rayBrightness] alpha, [rayDecay] the fade shape, [rayTaper] how much shafts
 * widen toward the tip, [rayLengthJitter]/[rayFlicker] the per-shaft variation,
 * [raySpinDegPerSec] a slow drift, and [rayPulseAmount] the bass reactivity.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGodRayShafts(
    lineCenter: Offset,
    reach: Float,
    p: Float,
    rayColor: Color,
    fx: LyricsFxSettings,
    driftRad: Float,
    timeSec: Float,
) {
    val count = fx.rayCount
    if (count <= 0 || reach <= 1f) return

    // Base direction points DOWN (0 = up in this convention, so PI = down),
    // tilted by rayAngleDeg; shafts spread across raySpreadDeg. The source sits
    // above the line so the fan pours down through and past the lyric.
    val baseRad = PI.toFloat() + fx.rayAngleDeg * PI.toFloat() / 180f
    val spreadRad = fx.raySpreadDeg.coerceIn(0f, 360f) * PI.toFloat() / 180f
    val source = Offset(lineCenter.x, lineCenter.y - reach * 0.42f)

    val widthPulse = (1f - fx.rayPulseAmount) + fx.rayPulseAmount * p
    val baseHalf = (fx.rayWidthDp * 1.3f * density) * (0.6f + 0.5f * widthPulse)
    // Kept above the 0.10 peak stop below so the gradient stops stay strictly
    // increasing (Brush.linearGradient requires monotonic offsets).
    val decay = fx.rayDecay.coerceIn(0.2f, 0.95f)
    // Volumetric shafts overlap additively, so each is faint; the pile-up near
    // the source is what reads as bright light. Mostly steady ambient light with
    // a bass lift on top, so the fan reads as continuous god rays (not a strobe).
    val baseAlpha = (fx.rayBrightness * 0.7f * (0.78f + 0.22f * p)).coerceIn(0f, 0.6f)
    val beamPath = Path()

    repeat(count) { i ->
        val frac = if (count > 1) (i / (count - 1f) - 0.5f) else 0f
        // Slow independent wobble so the fan gently breathes/shimmers.
        val wobble = 0.05f * sin(timeSec * 0.5f + i * 1.3f)
        val ang = baseRad + driftRad * 0.15f + frac * spreadRad + wobble
        val dirX = sin(ang)
        val dirY = -cos(ang)

        val rnd = abs(sin(i * 12.9898f + 3.7f) * 43758.545f).let { it - floor(it) }
        val beamReach = reach * (1f - fx.rayLengthJitter * rnd)
        val flick = if (fx.rayFlicker > 0f) {
            (1f - fx.rayFlicker) + fx.rayFlicker * (0.5f + 0.5f * sin(timeSec * 6f + i * 1.7f))
        } else 1f
        val alpha = (baseAlpha * flick).coerceIn(0f, 0.6f)
        if (alpha <= 0.002f) return@repeat

        val end = Offset(source.x + dirX * beamReach, source.y + dirY * beamReach)
        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                // Fade in from the source (which sits off above the text), peak,
                // then decay to nothing so the shaft dissolves into the backdrop.
                0f to Color.Transparent,
                0.10f to rayColor.copy(alpha = alpha),
                decay to rayColor.copy(alpha = alpha * 0.45f),
                1f to Color.Transparent,
            ),
            start = source,
            end = end,
        )
        // Soft shaft: narrow near the source, widening toward the tip as light
        // spreads (rayTaper controls how much). Perpendicular to the beam.
        val nx = -dirY
        val ny = dirX
        val nearHalf = baseHalf * 0.35f
        val farHalf = baseHalf * (0.9f + 1.1f * fx.rayTaper)
        beamPath.reset()
        beamPath.moveTo(source.x + nx * nearHalf, source.y + ny * nearHalf)
        beamPath.lineTo(source.x - nx * nearHalf, source.y - ny * nearHalf)
        beamPath.lineTo(end.x - nx * farHalf, end.y - ny * farHalf)
        beamPath.lineTo(end.x + nx * farHalf, end.y + ny * farHalf)
        beamPath.close()
        drawPath(path = beamPath, brush = brush, blendMode = BlendMode.Plus)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(
    center: Offset,
    radius: Float,
    p: Float,
    accent: Color,
    fx: LyricsFxSettings,
) {
    if (fx.glowBrightness <= 0.001f || radius <= 1f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = fx.glowBrightness * p),
                accent.copy(alpha = fx.glowBrightness * 0.32f * p),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
