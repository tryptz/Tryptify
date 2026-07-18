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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The shared FFT tap, provided by the player screen so lyric composables deep
 * in the tree can drive audio-reactive effects. Null (the default) disables
 * the bass path entirely — previews and tests never touch the audio engine.
 */
val LocalLyricsSpectrum = compositionLocalOf<SpectrumAnalyzerTap?> { null }

/**
 * Screen-position registry for the active lyric line. [bassBeat] reports the
 * line's root-coordinate bounds here; the full-screen, UNCLIPPED [LyricsFxLayer]
 * reads them to bloom a soft album-accent glow behind the line. Because the FX
 * draws on a layer with no clipping ancestor, the glow can never be cut by any
 * canvas/container — it only ever runs off the physical screen edge.
 */
class LyricGlyphAnchors {
    var lineCenter by mutableStateOf<Offset?>(null)
    var lineHalf by mutableStateOf(Size.Zero)
    fun reset() {
        lineCenter = null
        lineHalf = Size.Zero
    }
}

val LocalLyricGlyphAnchors = compositionLocalOf<LyricGlyphAnchors?> { null }

/** Shared bass pulse so the line pump and the full-screen glow use one analyzer stake and breathe together. */
val LocalBeatPulse = compositionLocalOf<State<Float>?> { null }

// 40–110 Hz — the kick/bass fundamentals — mapped into the tap's 256
// log-spaced bins (20 Hz..20 kHz): log10(f/20)/3 * 255.
private const val BASS_BIN_START = 26
private const val BASS_BIN_END = 62

private const val SPRING_STIFFNESS = 300f

// Adaptive kick detection. The tap emits per-bin levels in dB (pink-compensated
// and re-centered so the midband sits at 0 dB), and in real music the bass band
// sits WELL above that reference — often +12 dB or more on anything bass-heavy.
// An absolute threshold therefore pins at max on every frame, so the pump swells
// once and just stays swollen. Instead we track a slow "bass floor" and drive the
// pulse from how far the instantaneous level pokes ABOVE that floor — i.e. the
// kick transient — which rests near 0 between kicks whatever the mix level is.
//
// FLOOR_FALL_SEC: time constant when the floor follows the level down (toward the
// quiet gaps between kicks). FLOOR_RISE_SEC: much slower, so the floor creeps up
// through sustained-loud sections without ever chasing a kick's own transient.
// KICK_RANGE_DB: dB above the floor that counts as a full-strength kick.
private const val FLOOR_FALL_SEC = 0.4f
private const val FLOOR_RISE_SEC = 2.5f
private const val KICK_RANGE_DB = 8f

/**
 * Per-frame bass pulse in 0..~1.6 (>1 on overshoot). FFT bass bins → dB →
 * kick transient above an adaptive bass floor → attack/release envelope →
 * underdamped spring (attack, release, bounce are Studio settings). Read from
 * draw/layer lambdas only, so the pulse never causes recomposition. Ref-counts
 * a stake on the analyzer for exactly as long as it is composed.
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
        var floor = 0f
        var floorInit = false
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

                // Adaptive bass floor: falls quickly toward the quiet gaps between
                // kicks, rises slowly so it never chases a kick's own transient.
                if (!floorInit) { floor = db; floorInit = true }
                val floorTau = if (db < floor) FLOOR_FALL_SEC else FLOOR_RISE_SEC
                floor += (db - floor) * (1f - exp(-dt / floorTau))

                // Kick = how far the level pokes above the floor, in kick-range dB.
                // Rests near 0 between kicks regardless of the mix's bass level.
                val raw = ((db - floor) / KICK_RANGE_DB).coerceIn(0f, 1f)
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
 * Nothing is drawn inside this element, so nothing here can be clipped.
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
 * Full-screen glow layer: one soft album-accent bloom behind the whole active
 * line, breathing with the bass. Lives on a layer with no clipping ancestor, so
 * the light can never be cut by a canvas. Draw-phase only.
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
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawBehind {
                val p = (pulse.value * fx.bassReact).coerceIn(0f, 1.6f)
                if (p <= 0.03f) return@drawBehind

                // One soft album-accent bloom behind the whole active line.
                anchors.lineCenter?.let { lc ->
                    val c = lc - origin
                    val glowR = anchors.lineHalf.height + fx.glowRadiusDp.dp.toPx() * (1f + p)
                    drawGlow(c, glowR, p, accent, fx)
                }
            },
    )
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
