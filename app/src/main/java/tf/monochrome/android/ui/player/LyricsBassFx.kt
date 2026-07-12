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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The shared FFT tap, provided by the player screen so lyric composables deep
 * in the tree can drive audio-reactive effects. Null (the default) disables
 * the bass path entirely — previews and tests never touch the audio engine.
 */
val LocalLyricsSpectrum = compositionLocalOf<SpectrumAnalyzerTap?> { null }

/**
 * Screen-position anchor for the active lyric line, in root coordinates.
 * The active line reports its bounds here; [BeatRaysBackdrop] — a full-screen
 * layer behind the whole player — draws the god rays at that position, so the
 * beams can run to screen length without ever being clipped by the lyric
 * surface's own compositing bounds.
 */
class BeatRayAnchor {
    val center = mutableStateOf<Offset?>(null)
    val halfSize = mutableStateOf(Size.Zero)
}

val LocalBeatRayAnchor = compositionLocalOf<BeatRayAnchor?> { null }

/** Shared bass pulse, provided by the player so the line pump and the full-screen rays breathe together. */
val LocalBeatPulse = compositionLocalOf<State<Float>?> { null }

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
internal fun rememberBassPulse(): State<Float> = rememberBassPulse(LocalLyricsSpectrum.current)

@Composable
internal fun rememberBassPulse(tap: SpectrumAnalyzerTap?): State<Float> {
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
    // False when a full-screen BeatRaysBackdrop draws the light instead —
    // the line keeps only its pump/pop transform.
    drawHalo: Boolean = true,
): Modifier {
    if (intensity <= 0.01f) return this
    val pumped = this.graphicsLayer {
        val p = pulse.value * intensity
        val s = popScale() * (1f + 0.08f * p)
        scaleX = s
        scaleY = s
    }
    if (!drawHalo) return pumped
    return pumped
        .drawBehind {
            val p = (pulse.value * intensity).coerceIn(0f, 1.6f)
            if (p <= 0.03f) return@drawBehind
            val c = center

            // The light must NEVER meet the lyric surface's compositing
            // bounds — a clipped gradient silhouettes the container as a
            // rectangle with corners. Two rules enforce that regardless of
            // line shape (single row or a tall wrapped block):
            //  1. Ray roots sit on an ellipse hugging the glyph block, so
            //     beams visibly leave the letter edges, not a box centre.
            //  2. Ray length and glow radius are fixed dp (never scaled by
            //     the box), and every gradient reaches full transparency at
            //     its own endpoint — it dies in open space, so a boundary
            //     has nothing to cut.
            val rx = size.width * 0.30f // ≈ half-width of centred text
            val ry = size.height * 0.5f
            val rayLen = (40.dp.toPx() + 44.dp.toPx() * p).coerceAtMost(90.dp.toPx())
            val sweep = time.value * (10f * PI.toFloat() / 180f) // slow orbit, rad/s

            repeat(RAY_COUNT) { i ->
                val a = sweep + i * (2f * PI.toFloat() / RAY_COUNT)
                val dirX = cos(a)
                val dirY = sin(a)
                // Root on the glyph-block ellipse; beam extends outward.
                val start = Offset(c.x + dirX * rx * 0.92f, c.y + dirY * ry * 0.85f)
                val end = Offset(start.x + dirX * rayLen, start.y + dirY * rayLen)
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(accent.copy(alpha = 0.30f * p), Color.Transparent),
                        start = start,
                        end = end,
                    ),
                    start = start,
                    end = end,
                    strokeWidth = (2f + 6f * p) * density,
                    cap = StrokeCap.Round,
                )
            }

            // Tight glow around the text block itself — fixed dp ceiling so a
            // tall wrapped line can't inflate it into a box-filling wash.
            val glowR = (ry + 26.dp.toPx() * (1f + p)).coerceAtMost(120.dp.toPx())
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.26f * p),
                        accent.copy(alpha = 0.08f * p),
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

private const val RAY_COUNT = 12

/**
 * Full-screen god-ray layer, composed BEHIND the whole player content (above
 * only the background/stain). Draws at the active line's screen position from
 * [BeatRayAnchor], so the beams span the screen with nothing to clip them —
 * the lyric surface's compositing bounds are no longer in the light's path.
 * Draw-phase only: anchor, pulse, and clock are all read inside drawBehind.
 */
@Composable
internal fun BeatRaysBackdrop(
    anchor: BeatRayAnchor,
    pulse: State<Float>,
    accent: Color,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    if (intensity <= 0.01f) return
    val time = rememberFrameSeconds()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                val rootCenter = anchor.center.value ?: return@drawBehind
                val p = (pulse.value * intensity).coerceIn(0f, 1.6f)
                if (p <= 0.03f) return@drawBehind
                val c = rootCenter - origin
                val half = anchor.halfSize.value
                val rx = half.width * 0.92f
                val ry = half.height * 0.85f

                // Screen-length beams: roots on the glyph-block ellipse, ends
                // fading to full transparency at their own endpoints.
                val reach = size.maxDimension * (0.40f + 0.22f * p)
                val sweep = time.value * (10f * PI.toFloat() / 180f)
                repeat(RAY_COUNT) { i ->
                    val a = sweep + i * (2f * PI.toFloat() / RAY_COUNT)
                    val dirX = cos(a)
                    val dirY = sin(a)
                    val start = Offset(c.x + dirX * rx, c.y + dirY * ry)
                    val end = Offset(start.x + dirX * reach, start.y + dirY * reach)
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(accent.copy(alpha = 0.26f * p), Color.Transparent),
                            start = start,
                            end = end,
                        ),
                        start = start,
                        end = end,
                        strokeWidth = (2f + 7f * p) * density,
                        cap = StrokeCap.Round,
                    )
                }

                val glowR = ry + 44.dp.toPx() * (1f + p)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.22f * p),
                            accent.copy(alpha = 0.07f * p),
                            Color.Transparent,
                        ),
                        center = c,
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = c,
                )
            },
    )
}
