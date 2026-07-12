package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared FFT tap, provided by the player screen so lyric composables deep
 * in the tree can drive audio-reactive effects. Null (the default) disables
 * the bass path entirely — previews and tests never touch the audio engine.
 */
val LocalLyricsSpectrum = compositionLocalOf<SpectrumAnalyzerTap?> { null }

// 40–110 Hz — the kick/bass fundamentals — mapped into the tap's 256
// log-spaced bins (20 Hz..20 kHz): log10(f/20)/3 * 255.
private const val BASS_BIN_START = 26
private const val BASS_BIN_END = 62

private const val SPRING_STIFFNESS = 300f

/**
 * Per-frame bass pulse in 0..~1.3 (>1 on overshoot), built as:
 * FFT bass bins → dB → normalized level → attack/release envelope →
 * underdamped spring (attack, release, and bounce are Studio settings).
 * Read it from draw/layer lambdas only, so the pulse never causes
 * recomposition. Holds a ref-counted stake on the analyzer for exactly as
 * long as it is composed.
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
        onDispose { tap.release() }
    }
    // Keyed on the envelope/spring tuning so Studio slider changes retune the
    // engine live instead of waiting for the next composition of the player.
    LaunchedEffect(tap, fx.attackMs, fx.releaseMs, fx.bounce) {
        val attackSec = (fx.attackMs / 1000f).coerceAtLeast(0.001f)
        val releaseSec = (fx.releaseMs / 1000f).coerceAtLeast(0.01f)
        // ζ = c / (2√k) → c = 2ζ√k. Lower ζ = more overshoot ring = bouncier.
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

                // Average bass-band level. Bins are pink-compensated dB
                // centred so the midband sits at 0 — a kick swings the bass
                // band up to roughly +12 dB, which maps to level 1.0.
                val bins = tap.spectrumBins.value
                var sum = 0f
                for (b in BASS_BIN_START..BASS_BIN_END) sum += bins[b]
                val db = sum / (BASS_BIN_END - BASS_BIN_START + 1)
                val raw = (db / 12f).coerceIn(0f, 1f)

                // Fast attack snaps onto the kick; the release holds through
                // it instead of flickering per frame.
                val coef = if (raw > env) 1f - exp(-dt / attackSec) else 1f - exp(-dt / releaseSec)
                env += (raw - env) * coef

                // Spring integration (semi-implicit Euler — stable at any fps).
                vel += ((env - pos) * SPRING_STIFFNESS - vel * springDamping) * dt
                pos += vel * dt
                pulse.floatValue = pos.coerceIn(0f, 1.6f)
            }
        }
    }
    return pulse
}

/**
 * Audio-reactive beat FX applied directly to a text (or any) element: the
 * element pumps with the bass (scale), pops in on activation, and radiates a
 * glow plus god rays that emanate from the element's own borders. Ray and
 * glow gradients reach full transparency at their own endpoints, so the light
 * dissolves in open space — no container edge is ever silhouetted, regardless
 * of the surface the element sits in.
 *
 * This is the "god ray" effect as an element modifier, matching the Studio's
 * mental model: the font is an element, the god ray is an FX on that element.
 * Everything reads [pulse]/[time]/[popScale] inside draw or layer lambdas —
 * zero recomposition per frame.
 */
internal fun Modifier.bassBeat(
    pulse: State<Float>,
    time: State<Float>,
    popScale: () -> Float,
    accent: Color,
    fx: LyricsFxSettings,
): Modifier {
    val intensity = fx.bassReact
    if (intensity <= 0.01f) return this
    val pumped = this.graphicsLayer {
        val p = pulse.value * intensity
        val s = popScale() * (1f + fx.pumpAmount * p)
        scaleX = s
        scaleY = s
    }
    // Nothing to draw when both rays and glow are off — keep the pump only.
    if (fx.rayCount <= 0 && fx.glowBrightness <= 0.001f) return pumped
    return pumped.drawBehind {
        val p = (pulse.value * intensity).coerceIn(0f, 1.6f)
        if (p <= 0.03f) return@drawBehind
        val c = center

        // Roots on an ellipse hugging the glyph block, so beams visibly leave
        // the letter borders rather than radiating from a hidden box centre.
        val rx = size.width * 0.46f
        val ry = size.height * 0.5f
        // Reach scales with the element's own size and the pulse, and the
        // gradient dies before the endpoint — no fixed screen dependence,
        // no visible cut.
        val reach = size.height * (0.8f + 2.4f * fx.rayLength) * (0.55f + 0.45f * p)
        val sweep = time.value * (fx.raySpinDegPerSec * PI.toFloat() / 180f)
        drawBeams(c, rx * 0.94f, ry * 0.9f, reach, p, accent, fx, sweep)

        val glowR = ry + fx.glowRadiusDp.dp.toPx() * (1f + p)
        drawGlow(c, glowR, p, accent, fx)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeams(
    center: Offset,
    rx: Float,
    ry: Float,
    reach: Float,
    p: Float,
    accent: Color,
    fx: LyricsFxSettings,
    sweepRad: Float,
) {
    val count = fx.rayCount
    if (count <= 0) return
    repeat(count) { i ->
        val a = sweepRad + i * (2f * PI.toFloat() / count)
        val dirX = cos(a)
        val dirY = sin(a)
        val start = Offset(center.x + dirX * rx, center.y + dirY * ry)
        val end = Offset(start.x + dirX * reach, start.y + dirY * reach)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(accent.copy(alpha = fx.rayBrightness * p), Color.Transparent),
                start = start,
                end = end,
            ),
            start = start,
            end = end,
            strokeWidth = (2f + (fx.rayWidthDp - 2f) * p).coerceAtLeast(1f) * density,
            cap = StrokeCap.Round,
        )
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
