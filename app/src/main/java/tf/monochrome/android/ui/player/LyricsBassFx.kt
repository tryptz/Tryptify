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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
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
        onDispose { tap.release() }
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

                // One soft bloom behind the whole line.
                anchors.lineCenter?.let { lc ->
                    val c = lc - origin
                    val glowR = anchors.lineHalf.height + fx.glowRadiusDp.dp.toPx() * (1f + p)
                    drawGlow(c, glowR, p, accent, fx)
                }

                // Per-glyph rays: each burst rooted on its own letter, sized to
                // the glyph, sweeping with the glyph's wave phase.
                if (fx.rayCount > 0) {
                    val spin = time.value * (fx.raySpinDegPerSec * PI.toFloat() / 180f)
                    anchors.glyphs.values.forEach { g ->
                        val c = g.center - origin
                        val glyph = max(g.halfW, g.halfH)
                        val reach = glyph * 2f * (0.6f + 2.4f * fx.rayLength) * (0.5f + 0.5f * p)
                        drawBeams(c, g.halfW * 0.55f, g.halfH * 0.55f, reach, p, accent, fx, spin + g.phase)
                    }
                }
            },
    )
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
