// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.ui.player.LocalLyricsFx
import tf.monochrome.android.ui.player.LyricRayLight
import tf.monochrome.android.ui.player.RayOutput
import tf.monochrome.android.ui.player.rememberGodRaysShader
import tf.monochrome.android.ui.player.setGodRayUniforms

/**
 * A brief flash of god rays from each note that is hit, and red ones from each
 * note that is missed.
 *
 * The note flashes white-hot at its receptor for a moment and the lyrics' own
 * god-ray shader turns that flash into shafts of light — the same volumetric
 * scattering the sung word streams in the player, so a hit is lit the way a
 * lyric is. The light sits on the struck notes, head-on, so the shafts burst
 * straight out of the arrow's own outline, dark gaps and all.
 *
 * Kept cheap because it runs during play: the shader is only attached while a
 * flash is alive, which is a fifth of a second after a hit; the layer covers
 * the receptor strip rather than the screen; and the march runs at the lowest
 * of the lyrics' qualities unless the graphics settings ask for more. Nothing
 * at all is drawn when those settings turn the rays off, under reduced motion,
 * with the low-performance glass switch on, or before Android 13, which is
 * where the lyrics' rays turn off too.
 */
@Composable
internal fun GlyphHitRays(
    assets: GlyphAssetRepository,
    laneWidthPx: Int,
    noteSizePx: Int,
    /** Where the receptors sit, in this layer's pixels. */
    receptorYPx: Float,
    explosionProvider: () -> Map<GlyphLane, LaneFlash>,
    frameTick: State<Long>,
    enabled: Boolean,
    /** The live combo, which picks the rays' colour (see [GlyphComboRays]). */
    comboProvider: () -> Int,
    modifier: Modifier = Modifier,
    /** Samples per shaft, 1 to 4: the graphics settings' ray quality. */
    quality: Int = 1,
) {
    if (!enabled) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (LocalLowPerformance.current.disableLiquidGlass) return
    HitRaysLayer(assets, laneWidthPx, noteSizePx, receptorYPx, explosionProvider, frameTick, comboProvider, quality, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun HitRaysLayer(
    assets: GlyphAssetRepository,
    laneWidthPx: Int,
    noteSizePx: Int,
    receptorYPx: Float,
    explosionProvider: () -> Map<GlyphLane, LaneFlash>,
    frameTick: State<Long>,
    comboProvider: () -> Int,
    quality: Int,
    modifier: Modifier,
) {
    val shader = rememberGodRaysShader() ?: return
    val base = LocalLyricsFx.current
    // The lyrics' rays, pointed at the hit: letters as the light, all of the
    // layer shining, the light head-on so the shafts burst outward, and none
    // of the slow orbit, sway or tilt a lyric line drifts with.
    val fx = remember(base, quality) {
        base.copy(
            godRays = true,
            godRaySource = LyricsFxSettings.GOD_RAYS_LETTERS,
            godRaysAllLyrics = true,
            godRaysFollowWord = false,
            godRayElevationDeg = 90f,
            godRaySpinDps = 0f,
            godRaySway = 0f,
            godRayTilt = 0f,
            godRayQuality = quality.coerceIn(1, 4),
            // Short shafts: a flash, not a beam, and they have to fade out
            // inside the strip this layer covers rather than end at its edge.
            godRayDensity = min(base.godRayDensity, 0.5f),
        )
    }
    val window = LocalWindowInfo.current.containerSize
    val scale = min(window.width, window.height).toFloat()
    // Where the flashes are this frame, written by the layer and read by the
    // light; both run in the same draw pass, so a plain holder is enough.
    val litBox = remember { arrayOfNulls<Rect>(1) }
    val light = remember(fx, scale) {
        LyricRayLight(
            fx = fx,
            accent = BURST_ACCENT,
            moving = false,
            time = mutableStateOf(0f),
            tilt = mutableStateOf(Offset.Zero),
            pulse = null,
            band = { null },
            lettersBox = { litBox[0] },
            scale = scale,
        )
    }
    // The flash's colour this frame, written by the layer and read by the
    // sprites below it in the same pass.
    val tint = remember { arrayOf(Color.White) }

    // Only the strip around the receptors, not the playfield: the shader runs
    // over every pixel of its layer, and the shafts never reach further.
    val top = (receptorYPx - RAY_REACH_NOTES * noteSizePx).coerceAtLeast(0f).roundToInt()
    val localReceptorY = receptorYPx - top
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val height = (constraints.maxHeight - top).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, top) }
            }
            .graphicsLayer {
                // Read every frame, so the effect follows the flashes.
                @Suppress("UNUSED_EXPRESSION") frameTick.value
                val now = System.nanoTime()
                var box: Rect? = null
                var strongest = 0f
                for ((lane, flash) in explosionProvider()) {
                    val life = flashLife(now, flash.atNanos)
                    if (life <= 0f) continue
                    strongest = maxOf(strongest, life)
                    val cx = lane.ordinal * laneWidthPx + laneWidthPx / 2f
                    val note = Rect(
                        cx - noteSizePx / 2f, localReceptorY - noteSizePx / 2f,
                        cx + noteSizePx / 2f, localReceptorY + noteSizePx / 2f,
                    )
                    box = box?.let { Rect(min(it.left, note.left), min(it.top, note.top), maxOf(it.right, note.right), maxOf(it.bottom, note.bottom)) } ?: note
                }
                litBox[0] = box
                val frame = if (box != null) light.frameFor(Offset.Zero) else null
                renderEffect = if (frame == null) {
                    null
                } else {
                    // Brightest at the hit, gone a fifth of a second later.
                    setGodRayUniforms(shader, light, frame.copy(exposure = frame.exposure * strongest), RayOutput.LETTERS_AND_LIGHT)
                    // In the combo's colour rather than the light's own: the
                    // shafts climb white, yellow, orange, red, rainbow as the
                    // combo passes each fifty.
                    val colour = GlyphComboRays.colour(comboProvider(), now)
                    tint[0] = colour
                    shader.setFloatUniform("uSunColor", colour.red, colour.green, colour.blue)
                    android.graphics.RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                        .asComposeRenderEffect()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frameTick.value
            val now = System.nanoTime()
            for ((lane, flash) in explosionProvider()) {
                val life = flashLife(now, flash.atNanos)
                if (life <= 0f) continue
                if (!lane.isDance) {
                    // A pop-kun has no sprite: its disc, white-hot or red.
                    drawCircle(
                        color = (if (flash.isHit) tint[0] else GlyphComboRays.MISS).copy(alpha = life),
                        radius = noteSizePx / 2f,
                        center = Offset(lane.ordinal * laneWidthPx + laneWidthPx / 2f, localReceptorY),
                    )
                    continue
                }
                val image = assets.image(
                    GlyphAssetCatalog.tap(lane, GlyphBeatDivision.QUARTER), noteSizePx, noteSizePx,
                ) ?: continue
                // The struck arrow, white-hot or in the combo's colour, held
                // still at its own size: a flash, not a swell. A missed one
                // flashes red. The shafts take each arrow's own colour (the
                // shader marches the layer's pixels), so a miss beside a hit
                // streams red while the hit keeps the combo's.
                val size = noteSizePx
                val x = lane.ordinal * laneWidthPx + (laneWidthPx - size) / 2f
                drawImage(
                    image = image,
                    dstOffset = IntOffset(x.roundToInt(), (localReceptorY - size / 2f).roundToInt()),
                    dstSize = IntSize(size, size),
                    alpha = life,
                    colorFilter = if (flash.isHit) {
                        ColorFilter.tint(tint[0], BlendMode.SrcIn)
                    } else {
                        MISS_FILTER
                    },
                )
            }
        }
    }
}

/** A missed arrow's flash: the colour its rays stream in. */
private val MISS_FILTER = ColorFilter.tint(GlyphComboRays.MISS, BlendMode.SrcIn)

/** 1 at the hit, falling to 0 over [FLASH_SECONDS]; 0 outside it. */
private fun flashLife(nowNanos: Long, atNanos: Long): Float {
    val age = (nowNanos - atNanos) / 1_000_000_000f
    if (age < 0f || age >= FLASH_SECONDS) return 0f
    val t = 1f - age / FLASH_SECONDS
    return t * t
}

/** A brief flash: long enough to see, short enough that a stream of hits stays a stream of flashes. */
private const val FLASH_SECONDS = 0.22f

/** How far above the receptors, in notes, the shafts are given room to fade. */
private const val RAY_REACH_NOTES = 5f

/**
 * The light's base colour. The shader is handed the combo's colour each frame
 * instead (see [GlyphComboRays]); this only seeds [LyricRayLight].
 */
private val BURST_ACCENT = Color(0xFF3FC8FF)

/**
 * Whether hit flashes have rays to give, here: the same conditions the rays
 * layer checks, so the playfield's glyphs are only lit by light that is there.
 */
@Composable
internal fun rememberHitRaysAvailable(reducedMotion: Boolean): Boolean {
    if (reducedMotion) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (LocalLowPerformance.current.disableLiquidGlass) return false
    return rememberGodRaysShader() != null
}

/**
 * The light the hit flashes throw on everything else on the playfield.
 *
 * The rays come out of the struck note, so the notes, heads, bodies and
 * receptors around it are lit by them too — the way the lyrics' glass letters
 * catch the light their rays come from. Updated once a frame from the live
 * flashes (four at most, one a lane); [at] is how much light reaches a point,
 * strongest beside the hit and gone [RAY_REACH_NOTES] notes away, fading with
 * the flash. A glyph with light on it is drawn once more over itself, in the
 * flash's colour and added, which is all the lighting costs — and nothing when
 * no flash is alive.
 */
internal class GlyphHitLight {
    private val x = FloatArray(GlyphLane.entries.size)
    private val y = FloatArray(GlyphLane.entries.size)
    private val life = FloatArray(GlyphLane.entries.size)
    private var count = 0
    private var reach = 1f

    val isLit: Boolean get() = count > 0

    private var filterColour = Color.Unspecified
    private var cachedFilter: ColorFilter? = null

    /** The flash's colour, multiplied into a copy of the glyph that is then added. */
    val filter: ColorFilter
        get() = cachedFilter ?: ColorFilter.tint(Color.White, BlendMode.Modulate).also { cachedFilter = it }

    /** Light in [colour] from now on; the filter is remade only when it changes. */
    fun tint(colour: Color) {
        if (colour == filterColour) return
        filterColour = colour
        cachedFilter = ColorFilter.tint(colour, BlendMode.Modulate)
    }

    fun update(
        nowNanos: Long,
        flashes: Map<GlyphLane, LaneFlash>,
        laneWidthPx: Int,
        noteSizePx: Int,
        receptorYPx: Float,
    ) {
        count = 0
        reach = (RAY_REACH_NOTES * noteSizePx).coerceAtLeast(1f)
        for ((lane, flash) in flashes) {
            // Hits only: the light is the combo's colour, and a miss's red
            // rays lighting the notes around it the combo's would contradict
            // them.
            if (!flash.isHit) continue
            val l = flashLife(nowNanos, flash.atNanos)
            if (l <= 0f) continue
            x[count] = lane.ordinal * laneWidthPx + laneWidthPx / 2f
            y[count] = receptorYPx
            life[count] = l
            count += 1
        }
    }

    fun clear() {
        count = 0
    }

    /** How much flash light reaches ([px], [py]), 0 to 1. */
    fun at(px: Float, py: Float): Float {
        var total = 0f
        for (i in 0 until count) {
            val dx = px - x[i]
            val dy = py - y[i]
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / reach
            if (d >= 1f) continue
            val falloff = (1f - d) * (1f - d)
            total += life[i] * falloff
        }
        return total.coerceAtMost(1f) * LIGHT_STRENGTH
    }
}

/** How bright a glyph gets right beside a hit, as a share of a full-strength copy. */
private const val LIGHT_STRENGTH = 0.85f

/**
 * The hit rays' colour by combo: white to start, then yellow, orange and red
 * as the combo passes each fifty, and from two hundred a rainbow that keeps
 * turning. A broken combo drops straight back to white.
 */
internal object GlyphComboRays {
    const val STEP = 50

    /** 0 white, 1 yellow, 2 orange, 3 red, 4 rainbow. */
    fun tier(combo: Int): Int = (combo.coerceAtLeast(0) / STEP).coerceAtMost(RAINBOW_TIER)

    fun colour(combo: Int, nowNanos: Long): Color = when (tier(combo)) {
        0 -> Color.White
        1 -> YELLOW
        2 -> ORANGE
        3 -> RED
        else -> {
            val seconds = (nowNanos % RAINBOW_PERIOD_NANOS) / 1_000_000_000f
            Color.hsv(seconds / RAINBOW_PERIOD_SECONDS * 360f, 0.8f, 1f)
        }
    }

    /** A miss's rays, whatever the combo was. */
    val MISS = Color(0xFFFF2020)

    const val RAINBOW_TIER = 4
    private const val RAINBOW_PERIOD_SECONDS = 2.4f
    private const val RAINBOW_PERIOD_NANOS = 2_400_000_000L
    private val YELLOW = Color(0xFFFFE24A)
    private val ORANGE = Color(0xFFFF9A2E)
    private val RED = Color(0xFFFF3B3B)
}
