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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import kotlin.math.exp

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

// Underdamped spring chasing the bass envelope: ζ = c/(2√k) ≈ 0.38, so a kick
// overshoots ~25% and rings once before settling — the "bounce". Critically
// damped would track without bounce; lower damping jiggles for too long.
private const val SPRING_STIFFNESS = 300f
private const val SPRING_DAMPING = 13f

/**
 * Per-frame bass pulse in 0..~1.3 (>1 on overshoot), built as:
 * FFT bass bins → dB → normalized level → fast-attack/slow-release envelope
 * → underdamped spring. Read it from draw/layer lambdas only, so the pulse
 * never causes recomposition. Holds a ref-counted stake on the analyzer for
 * exactly as long as it is composed.
 */
@Composable
internal fun rememberBassPulse(): State<Float> {
    val tap = LocalLyricsSpectrum.current
    val pulse = remember { mutableFloatStateOf(0f) }
    if (tap == null) return pulse

    DisposableEffect(tap) {
        tap.acquire()
        onDispose { tap.release() }
    }
    LaunchedEffect(tap) {
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

                // Fast attack (~12 ms) snaps onto the kick; slow release
                // (~150 ms) holds through it instead of flickering per frame.
                val coef = if (raw > env) 1f - exp(-dt / 0.012f) else 1f - exp(-dt / 0.150f)
                env += (raw - env) * coef

                // Spring integration (semi-implicit Euler — stable at any fps).
                vel += ((env - pos) * SPRING_STIFFNESS - vel * SPRING_DAMPING) * dt
                pos += vel * dt
                pulse.floatValue = pos.coerceIn(0f, 1.6f)
            }
        }
    }
    return pulse
}

/**
 * Nightcore-style beat treatment for the active lyric line: the line pumps
 * with the bass (scale), pops in on activation, and radiates a soft glow
 * plus slowly rotating god rays whose length, brightness, and thickness ride
 * the bass pulse. Everything reads [pulse]/[time]/[popScale] inside draw or
 * layer lambdas — zero recomposition per frame.
 */
internal fun Modifier.bassBeat(
    pulse: State<Float>,
    time: State<Float>,
    popScale: () -> Float,
    accent: Color,
    intensity: Float,
): Modifier {
    if (intensity <= 0.01f) return this
    return this
        .graphicsLayer {
            val p = pulse.value * intensity
            val s = popScale() * (1f + 0.08f * p)
            scaleX = s
            scaleY = s
        }
        .drawBehind {
            val p = (pulse.value * intensity).coerceIn(0f, 1.6f)
            if (p <= 0.03f) return@drawBehind
            val c = center
            // Halo sized from the LINE HEIGHT, not the line width: rays as
            // long as the line get clipped by the lyric surface's offscreen
            // compositing bounds, and the straight cut edges silhouette the
            // container as a visible rectangle. A height-scaled halo whose
            // gradient reaches full transparency at its own endpoint fades
            // out in open space — nothing left for the bounds to clip.
            val reach = size.minDimension * (2.4f + 1.2f * p)

            // God rays first, glow on top: the glow softens the ray roots so
            // they appear to emerge from light rather than from a point.
            val rayBrush = Brush.linearGradient(
                colors = listOf(accent.copy(alpha = 0.28f * p), Color.Transparent),
                start = c,
                end = Offset(c.x + reach, c.y),
            )
            val sweep = time.value * 10f // slow rotation, degrees/second
            repeat(RAY_COUNT) { i ->
                rotate(degrees = sweep + i * (360f / RAY_COUNT), pivot = c) {
                    drawLine(
                        brush = rayBrush,
                        start = c,
                        end = Offset(c.x + reach, c.y),
                        strokeWidth = (2f + 6f * p) * density,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // Glow radius stays inside the ray reach so its gradient also
            // completes before any container edge can slice it.
            val glowR = reach * 0.8f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.30f * p),
                        accent.copy(alpha = 0.10f * p),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = glowR,
                ),
                radius = glowR,
                center = c,
            )
        }
}

private const val RAY_COUNT = 8
