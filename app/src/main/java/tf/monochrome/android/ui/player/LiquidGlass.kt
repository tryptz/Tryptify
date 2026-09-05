package tf.monochrome.android.ui.player

import kotlin.math.max
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import tf.monochrome.android.performance.LocalLowPerformance

/**
 * "Liquid glass" treatment for the lyric surfaces: an AGSL RenderEffect that
 * turns the already-drawn glyphs into real, refractive glass. The bevel normal
 * is derived from the text's own alpha field (so it stays draw-only — layout,
 * font size and line spacing are untouched); the glyph body is rendered
 * see-through, the smooth album-tinted backdrop is reconstructed and *lensed*
 * through that bevel (with chromatic aberration), and a bright specular rim
 * rides the beveled edge. The light direction follows device tilt (gravity
 * sensor) plus a slow autonomous drift. Nothing travels across the surface:
 * the living motion undulates in place, so the glass never draws the eye.
 *
 * [tint] is the album colour the reconstructed backdrop and the glass frost are
 * tinted with — pass the active accent so the refraction matches what's behind
 * the lyrics.
 *
 * Requires API 33 (RuntimeShader); below that, or if the shader fails to
 * compile on some GPU driver, the modifier is a no-op and lyrics render as the
 * solid text they were handed.
 */
@Composable
internal fun Modifier.liquidGlass(
    enabled: Boolean = true,
    tint: Color = Color(0xFF8FB4FF),
): Modifier {
    val fx = LocalLyricsFx.current
    val backdrop = LocalPlayerBackdrop.current
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (!enabled || !fx.liquidGlass || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassModifier(tint, fx, backdrop))
}

/**
 * What actually sits behind the lyric glass, so the shader can lens the real
 * album tones (Apple-OS style) instead of only a flat wash. Provided at the
 * player route from the album palette + the "Blurred Album Background" setting.
 * The default (disabled) leaves the glass numerically identical to the flat
 * single-tint reconstruction it used before.
 */
internal data class PlayerBackdrop(
    val blurredArt: Boolean = false,
    val dominant: Color = Color(0xFF101018),
    val secondary: Color = Color(0xFF101018),
)

internal val LocalPlayerBackdrop = androidx.compose.runtime.compositionLocalOf { PlayerBackdrop() }

// One process-wide epoch so every rememberFrameSeconds() instance reports the
// same timeline: a surface composed later (e.g. the lyrics expand morph)
// continues the animation phase instead of restarting it from zero.
@Volatile
private var frameClockEpochNanos = -1L

/**
 * The single state object every glass surface animates from.
 *
 * It used to be one `mutableFloatStateOf` *per call site*, and there are six of
 * them — the lyric glass, the panel, the player chrome, two per-letter lyric
 * paths and the Studio preview. With the player open, six independent state
 * objects were each written every frame, so one frame dirtied six separate
 * invalidation scopes even though all six held the identical number.
 *
 * Sharing one holder makes that one write and one scope. The per-surface
 * `withFrameNanos` loops stay (they are a single Choreographer callback list,
 * and they all compute the same value, so the redundant writes are equal-valued
 * and Compose's structural-equality policy drops them for free).
 *
 * Deliberately NOT gated on `surfaceMotion`: the default is 0.53 and every
 * shipped theme animates, so a "no motion" gate would essentially never fire.
 * The glass is a continuously-animated effect by design.
 */
private val frameClockSeconds = mutableFloatStateOf(0f)

/**
 * Per-frame clock in seconds on a shared app-wide timeline. Read it from draw
 * or layout lambdas (graphicsLayer, drawBehind) so animation never recomposes.
 *
 * Pass `animated = false` when the calling surface's shader has no live
 * time-varying term — every `uTime` use in [LIQUID_GLASS_SRC] is now scaled by
 * `uLiquid`, so a surface at `surfaceMotion = 0` renders identically frame to
 * frame and has no reason to drive one.
 *
 * The gate matters most for the mini player, which is mounted app-wide by the
 * nav host: with its motion at zero, the library, search and settings screens
 * stop being pinned at display refresh rate by a strip of glass that isn't
 * moving. The `if` is deliberate — flipping [animated] recomposes, which
 * enters or leaves the effect, so the loop starts and stops with it.
 */
@Composable
internal fun rememberFrameSeconds(animated: Boolean = true): State<Float> {
    // "Disable animations" stops this loop for the whole app. It is the single
    // most expensive thing the setting touches: while the player or the mini
    // player is on screen this pins the process at display refresh rate, and
    // the mini player is mounted by the nav host on every tab.
    if (animated && !LocalLowPerformance.current.disableAnimations) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { now ->
                    if (frameClockEpochNanos < 0L) frameClockEpochNanos = now
                    frameClockSeconds.floatValue = (now - frameClockEpochNanos) / 1_000_000_000f
                }
            }
        }
    }
    return frameClockSeconds
}

/**
 * Adds ~±1 LSB of static triangular noise to break 8-bit banding in smooth
 * gradients. Apply to dedicated background nodes so only the gradient pays
 * the offscreen pass. No-op below API 33 or if the shader fails to compile
 * (banding stays, exactly as before).
 */
@Composable
internal fun Modifier.dithered(): Modifier {
    // Dropped in low-performance mode: it's a full-screen offscreen shader pass
    // every frame, which is exactly the cost that mode exists to shed. Gradient
    // banding comes back, and that is the accepted trade.
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { runCatching { RuntimeShader(DITHER_SRC) }.getOrNull() }
        ?: return this
    return this.graphicsLayer {
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}

/**
 * Post-process FXAA (single-pass luma edge anti-aliasing) for the lyric
 * surface: smooths the jagged edges the 3D letter tilts, the glass relight and
 * a reduced panel resolution leave behind. Chain it OUTSIDE (before) the
 * [liquidGlass] modifier so it runs on the glass output.
 *
 * Requires API 33 (RuntimeShader) and the fx toggle; below that, or if the
 * shader fails to compile, the modifier is a no-op and lyrics render unchanged.
 */
@Composable
internal fun Modifier.fxaa(): Modifier {
    val fx = LocalLyricsFx.current
    // Nothing left to anti-alias once the glass relight is gone.
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (!fx.fxaa || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(fxaaModifier(fx.fxaaStrength))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun fxaaModifier(strength: Float): Modifier {
    val shader = remember {
        runCatching { RuntimeShader(FXAA_SRC) }
            .onSuccess { LyricsDebug.log("FXAA shader compiled") }
            .onFailure { LyricsDebug.log("FXAA shader FAILED to compile: ${it.message}") }
            .getOrNull()
    } ?: return Modifier
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uStrength", strength)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun liquidGlassModifier(
    tint: Color,
    fx: tf.monochrome.android.domain.model.LyricsFxSettings,
    backdrop: PlayerBackdrop,
): Modifier {
    val shader = remember {
        runCatching { RuntimeShader(LIQUID_GLASS_SRC) }
            .onSuccess { LyricsDebug.log("liquid-glass shader compiled") }
            .onFailure { LyricsDebug.log("liquid-glass shader FAILED to compile: ${it.message}") }
            .getOrNull()
    } ?: return Modifier

    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()

    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            // Second album tone + how strongly the lensed backdrop bleeds into the
            // glass body. Only non-zero when the blurred album background is on, so
            // the glass reads as sitting over the real artwork (Apple-OS style);
            // otherwise uBackdropMix = 0 keeps the flat single-tint look unchanged.
            val secondary = backdrop.secondary
            shader.setFloatUniform("uTint2", secondary.red, secondary.green, secondary.blue)
            shader.setFloatUniform("uBackdropMix", if (backdrop.blurredArt) 0.6f else 0f)
            shader.setFloatUniform("uBodyOpacity", fx.glassBodyOpacity)
            shader.setFloatUniform("uRefraction", fx.glassRefraction)
            shader.setFloatUniform("uRimGain", fx.glassRimBrightness)
            shader.setFloatUniform("uDispersion", fx.glassDispersion)
            shader.setFloatUniform("uSampleRings", fx.glassSampleRings.toFloat())
            shader.setFloatUniform("uRoundness", 1f)
            shader.setFloatUniform("uDepth", 1f)
            shader.setFloatUniform("uLiquid", 1f)
            // Lyrics keep the neutral (non-player-tunable) relight parameters.
            shader.setFloatUniform("uReflection", 1f)
            shader.setFloatUniform("uGloss", 90f)
            shader.setFloatUniform("uTiltAmount", 0.7f)
            shader.setFloatUniform("uLightAngle", 2.3561945f)   // 135°
            shader.setFloatUniform("uFresnelPower", 5f)
            shader.setFloatUniform("uFrost", 0f)
            shader.setFloatUniform("uBulge", 0.5f, 0.5f)
            shader.setFloatUniform("uBulgeAmt", 0f)
            shader.setFloatUniform("uBulgeR", 0f)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/**
 * The SAME refractive lyric glass ([LIQUID_GLASS_SRC]), applied to a solid PANEL
 * surface (the player's dock / sheet) instead of glyphs — so the player chrome
 * reads as the exact liquid glass the active lyric line does, not a flat frost.
 *
 * Apply it to a Box that already has a rounded, translucent fill: the shader
 * bevels that fill's edges into a lit, tilt-reactive refractive rim (with
 * chromatic dispersion) over a see-through, album-tinted body. Place it BEHIND
 * the panel's content so the buttons and labels sitting on the glass stay crisp
 * and untouched.
 *
 * Unlike [liquidGlass] it is NOT gated on the lyric-FX toggle (player chrome is
 * always glass) and is tuned for a panel: a more present body and a stronger
 * rim. Requires API 33; a no-op (plain fill) below that or on shader failure.
 */
@Composable
internal fun Modifier.liquidGlassPanel(tint: Color): Modifier {
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassPanelModifier(tint))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun liquidGlassPanelModifier(tint: Color): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() } ?: return Modifier
    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uTint2", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uBackdropMix", 0f)
            // Panel tuning: a body that stays fairly present (a panel, not a
            // glyph), with a strong lit rim and gentle edge refraction.
            shader.setFloatUniform("uBodyOpacity", 0.82f)
            shader.setFloatUniform("uRefraction", 0.10f)
            shader.setFloatUniform("uRimGain", 1.30f)
            shader.setFloatUniform("uDispersion", 1.0f)
            shader.setFloatUniform("uSampleRings", 2f)
            shader.setFloatUniform("uRoundness", 1f)
            shader.setFloatUniform("uDepth", 1f)
            shader.setFloatUniform("uLiquid", 1f)
            // Panel keeps the neutral relight parameters.
            shader.setFloatUniform("uReflection", 1f)
            shader.setFloatUniform("uGloss", 90f)
            shader.setFloatUniform("uTiltAmount", 0.7f)
            shader.setFloatUniform("uLightAngle", 2.3561945f)   // 135°
            shader.setFloatUniform("uFresnelPower", 5f)
            shader.setFloatUniform("uFrost", 0f)
            shader.setFloatUniform("uBulge", 0.5f, 0.5f)
            shader.setFloatUniform("uBulgeAmt", 0f)
            shader.setFloatUniform("uBulgeR", 0f)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/**
 * Player-chrome glass settings (the transport buttons), provided at the player
 * route from the persisted [tf.monochrome.android.domain.model.PlayerGlassSettings].
 */
val LocalPlayerGlass = compositionLocalOf { tf.monochrome.android.domain.model.PlayerGlassSettings() }

/**
 * Whether [playerGlass] will actually do anything here.
 *
 * The modifier is a silent no-op in four cases — glass switched off, the
 * low-performance override, below API 33, or a device whose driver will not
 * compile the shader — and a caller cannot otherwise tell. That matters because
 * the slab a caller draws underneath it is meant to be turned *into* glass: at
 * full opacity it is a solid rounded rectangle until the shader bevels it, so
 * the honest choice of fill depends on whether the shader is coming.
 *
 * This existed as a guess before, and the guess was to draw the slab at a tenth
 * of its opacity so the failure mode would be a faint tint. That made every
 * panel that *did* have the shader look nothing like the mini player, which
 * draws its slab solid: the shader builds its bevel and rim from the alpha
 * heightfield of what is under it, and a near-transparent fill gives it almost
 * no heightfield to read — a soft smudge with no edge instead of a pane.
 *
 * The compile attempt is remembered unconditionally, before any of the state
 * that can change, so this never alters the shape of a caller's composition.
 */
@Composable
fun rememberLiquidGlassAvailable(): Boolean {
    val compiles = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() != null
    }
    val flat = LocalLowPerformance.current.disableLiquidGlass
    val enabled = LocalPlayerGlass.current.enabled
    return compiles && !flat && enabled
}

/**
 * The player background as a haze source, for the chrome that sits over it.
 *
 * Null off the player route and on devices that can't blur. When set, the
 * transport, dock, status tiles and hero can each frost the real, blurred album
 * art behind them — the same live haze the mini player gets from the nav host —
 * rather than only relighting their own fill with the shader. Carried as a local
 * so a tile buried three composables deep gets it without every layer between
 * passing it down.
 *
 * It must be provided *outside* the node marked as the source: a haze effect
 * cannot sample a layer it is drawn inside, which paints the source's flat base
 * colour instead of a blur — the slab-not-glass bug this whole path exists to
 * avoid.
 */
val LocalPlayerHaze = compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/** How long the frost takes to settle onto a surface, or to leave it. */
private const val HAZE_FADE_MILLIS = 400

/**
 * The blurred-backdrop layer that goes *under* a piece of player glass.
 *
 * This is the haze — an actual gaussian blur of whatever [LocalPlayerHaze]
 * captured — as distinct from the frost, which is the tint carried on top of it.
 * Both are here because a sheet of real glass is both: you see the blurred room
 * through it *and* it has a colour. Draw this first, then the shader slab, then
 * the content; on the punched tiles (dock, transport) the icon holes then reveal
 * this blur rather than the raw art.
 *
 * It fades rather than appears. Glass frosting over is a thing that happens to
 * a surface, and switching it on used to swap the artwork behind a panel for a
 * blur of it between one frame and the next — a cut, which reads as the picture
 * changing rather than the surface. The same fade runs backwards when the blur
 * is turned off, so the layer leaves the way it arrived instead of blinking
 * out; it follows the app's "Disable animations" setting like everything else.
 *
 * Draws nothing whenever there is no source, the device can't blur, glass is
 * off, or the blur radius is zero, so callers can place it unconditionally.
 */
@Composable
fun PlayerGlassHaze(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
) {
    val haze = LocalPlayerHaze.current ?: return
    val g = LocalPlayerGlass.current
    val profile = tf.monochrome.android.performance.LocalPerformanceProfile.current
    val lit = profile.allowHazeBlur && g.enabled && g.hazeBlurDp > 0f

    val millis = tf.monochrome.android.ui.theme.motionMillis(HAZE_FADE_MILLIS)
    val fade = remember { Animatable(0f) }
    LaunchedEffect(lit, millis) {
        fade.animateTo(if (lit) 1f else 0f, tween(durationMillis = millis))
    }
    // Composed while it is still on its way out, which is what lets it fade out
    // at all: a layer removed from the tree cannot animate its own departure.
    //
    // Gated on the value rather than on `isRunning`, which was the wrong
    // question by exactly one frame. On the composition where `lit` goes false
    // the previous fade has already finished, so nothing is running yet -- the
    // LaunchedEffect body only starts afterwards -- and this returned, taking
    // the layer out of the tree. Starting the animation then set `isRunning`,
    // which scheduled another composition that put the layer back at full
    // strength to fade down. The frost snapped off, blinked back on and only
    // then faded: the cut this exists to remove, with a flash added.
    //
    // `derivedStateOf` so the read costs two recompositions across the whole
    // animation rather than one per frame -- the value itself is read in the
    // layer block below.
    val leaving by remember { derivedStateOf { fade.value > 0.001f } }
    if (!lit && !leaving) return

    val frostBg = androidx.compose.material3.MaterialTheme.colorScheme.background
    val isDark = frostBg.luminance() <= 0.5f
    // The blur is the haze; this is the frost, and it is the thin part — most
    // of what reads through should be the blurred art.
    val frostTint = playerFrostTint(g, isDark)

    androidx.compose.foundation.layout.Box(
        modifier
            // Read in the layer block, not the composition: the fade would
            // otherwise recompose this on every frame it runs.
            .graphicsLayer { alpha = fade.value }
            .clip(shape)
            .hazeEffect(
                state = haze,
                style = dev.chrisbanes.haze.HazeStyle(
                    backgroundColor = frostBg,
                    blurRadius = g.hazeBlurDp.dp,
                    tints = listOf(dev.chrisbanes.haze.HazeTint(frostTint)),
                    noiseFactor = 0f,
                ),
            ),
    )
}

/**
 * The wash that goes over the blur — the *frost*, as opposed to the haze.
 *
 * One function because this recipe was written out by hand in three places
 * (here, [tf.monochrome.android.ui.components.GlassPanel], and the player's
 * audio-tools sheet) and they were free to drift. Every sheet of glass in the
 * app now frosts by the same rule, scaled by the listener's own "Backdrop
 * tint" from the Player Visuals Studio.
 *
 * The light-theme wash used to be nearly twice the dark one (0.45 white
 * against 0.32 black). On a light or warm theme that is most of a solid
 * colour laid over the blur, which is why those panels read as milky slabs
 * rather than glass — the blurred artwork was there, just buried. The two
 * sides are even now, and anyone who liked the heavier look can put it back
 * by raising Backdrop tint, which reaches 2.0.
 */
fun playerFrostTint(glass: tf.monochrome.android.domain.model.PlayerGlassSettings, isDark: Boolean): Color {
    val base = if (isDark) Color.Black.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.26f)
    return base.copy(alpha = (base.alpha * glass.hazeTint).coerceIn(0f, 1f))
}

/**
 * The SAME refractive lyric glass ([LIQUID_GLASS_SRC]) applied to a player
 * button's icon, so the play/skip shapes read as 3D chrome liquid glass just
 * like the active lyric line. Reads [LocalPlayerGlass] for its parameters
 * (tunable in the Studio's "Player Glass" tab). Apply it to the Icon so the
 * shader bevels the (solid) glyph shape. No-op when disabled, below API 33, or
 * on shader-compile failure.
 */
@Composable
internal fun Modifier.playerGlass(
    tint: Color,
    bulgeCenter: Offset = Offset(0.5f, 0.5f),
    bulgeAmount: () -> Float = { 0f },
    /**
     * How wide the press dome is, as a fraction of the pane's longest side.
     *
     * Zero keeps the shader's own default of a sixth of the width, which is what
     * the transport and the mini player's carved-out controls were tuned around:
     * there the dome is meant to pick out one button on a bar of several. A pane
     * that is *itself* the button wants the swell across the whole of it, and a
     * sixth of the width on a full-width sheet is a dimple nobody can see.
     */
    bulgeRadiusFraction: Float = 0f,
): Modifier {
    val g = LocalPlayerGlass.current
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (!g.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(playerGlassModifier(tint, g, bulgeCenter, bulgeAmount, bulgeRadiusFraction))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun playerGlassModifier(
    tint: Color,
    g: tf.monochrome.android.domain.model.PlayerGlassSettings,
    bulgeCenter: Offset,
    bulgeAmount: () -> Float,
    bulgeRadiusFraction: Float,
): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() } ?: return Modifier
    // Unlike the lyric glass and the panel, which pin uLiquid to 1, this
    // surface's motion is user-tunable — and every uTime term in the shader is
    // scaled by uLiquid, so at zero it renders identically frame to frame and
    // has no reason to drive a clock. This is the mini player, mounted app-wide
    // by the nav host, so the gate reaches every screen.
    val timeSec = rememberFrameSeconds(animated = g.surfaceMotion > 0f)
    val tilt = rememberGravityTilt()
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uTint2", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uBackdropMix", 0f)
            shader.setFloatUniform("uBodyOpacity", g.bodyOpacity)
            shader.setFloatUniform("uRefraction", g.refraction)
            // Rim fades out with the last stretch of body opacity: at 0 the
            // body is invisible and a full-strength rim would leave floating
            // box outlines around every plate (visible on the transport skips).
            // Above 0.15 body the rim is untouched.
            shader.setFloatUniform(
                "uRimGain",
                g.rimBrightness * (g.bodyOpacity / 0.15f).coerceIn(0f, 1f),
            )
            shader.setFloatUniform("uDispersion", g.dispersion)
            shader.setFloatUniform("uSampleRings", g.sampleRings.toFloat())
            shader.setFloatUniform("uRoundness", g.roundness)
            shader.setFloatUniform("uDepth", g.depth)
            // Player-tunable relight (Studio "Player Glass" tab). Surface motion
            // replaces the old fixed 0.25 calm; gloss maps to a specular exponent
            // (20 = soft/frosted-wide .. 260 = tight mirror), edge width to the
            // Fresnel falloff (8 = thin crisp .. 2 = broad shoulder).
            shader.setFloatUniform("uLiquid", g.surfaceMotion)
            shader.setFloatUniform("uReflection", g.reflection)
            shader.setFloatUniform("uGloss", 20f + 240f * g.gloss)
            shader.setFloatUniform("uTiltAmount", g.tiltReactivity)
            shader.setFloatUniform("uLightAngle", g.lightAngleDeg * 0.017453292f)
            shader.setFloatUniform("uFresnelPower", 8f - 6f * g.edgeWidth)
            shader.setFloatUniform("uFrost", g.frost)
            shader.setFloatUniform("uBulge", bulgeCenter.x, bulgeCenter.y)
            shader.setFloatUniform("uBulgeAmt", bulgeAmount())
            shader.setFloatUniform(
                "uBulgeR",
                if (bulgeRadiusFraction > 0f) {
                    max(size.width, size.height) * bulgeRadiusFraction
                } else {
                    0f
                },
            )
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/**
 * Low-pass-filtered gravity in [-1, 1] per axis; Offset.Zero if no sensor.
 *
 * Every glass surface (each lyric line, the panel, every player button) reads
 * the same tilt, so instead of each modifier registering its OWN gravity
 * listener — the player alone had ~7 running at once — they all share ONE
 * process-wide [GravityTiltSource], ref-counted so the single hardware
 * registration lives exactly as long as at least one glass surface is composed.
 */
@Composable
private fun rememberGravityTilt(): State<Offset> {
    val context = LocalContext.current
    DisposableEffect(context) {
        GravityTiltSource.acquire(context)
        onDispose { GravityTiltSource.release() }
    }
    return GravityTiltSource.tilt
}

/**
 * Shared, ref-counted gravity tilt for all liquid-glass surfaces. Registers a
 * single [SensorEventListener] on the first [acquire] and unregisters it on the
 * last [release]. All access is on the main thread (Compose effects + the
 * main-Looper sensor callback), so the counter and filter need no locking.
 */
private object GravityTiltSource : SensorEventListener {
    val tilt = mutableStateOf(Offset.Zero)
    private var manager: SensorManager? = null
    private var refCount = 0
    private var fx = 0f
    private var fy = 0f

    fun acquire(context: Context) {
        if (refCount++ > 0) return
        val mgr = context.applicationContext
            .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = mgr?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: mgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (mgr != null && sensor != null) {
            manager = mgr
            mgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun release() {
        if (refCount <= 0) return
        if (--refCount == 0) {
            manager?.unregisterListener(this)
            manager = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val gy = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        fx += (gx - fx) * 0.12f
        fy += (gy - fy) * 0.12f
        tilt.value = Offset(fx, fy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

// Static (not time-animated) noise: fixes banding without shimmer. Noise is
// scaled by alpha to stay premultiplied-valid.
private const val DITHER_SRC = """
uniform shader content;

half4 main(float2 p) {
    half4 c = content.eval(p);
    float n1 = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(p + 17.13, float2(26.651, 41.778))) * 24634.6345);
    float d = (n1 + n2 - 1.0) * (1.5 / 255.0);
    float a = float(c.a);
    float3 rgb = clamp(float3(c.rgb) + d * a, 0.0, a);
    return half4(half3(rgb), c.a);
}
"""

// Single-pass luma FXAA (the classic NVIDIA/Geeks3D formulation). Operates on
// the premultiplied-alpha lyric surface: luma is read from premultiplied rgb so
// glyph alpha-edges register, and the directional 4-tap blend runs on the full
// premultiplied colour (averaging premultiplied samples stays valid). uStrength
// cross-fades the anti-aliased result back over the original.
private const val FXAA_SRC = """
uniform shader content;
uniform float uStrength;

half4 main(float2 frag) {
    float3 luma = float3(0.299, 0.587, 0.114);
    float SPAN_MAX = 8.0;
    float REDUCE_MUL = 1.0 / 8.0;
    float REDUCE_MIN = 1.0 / 128.0;

    half4 c = content.eval(frag);
    float lumaM  = dot(float3(c.rgb), luma);
    float lumaNW = dot(float3(content.eval(frag + float2(-1.0, -1.0)).rgb), luma);
    float lumaNE = dot(float3(content.eval(frag + float2( 1.0, -1.0)).rgb), luma);
    float lumaSW = dot(float3(content.eval(frag + float2(-1.0,  1.0)).rgb), luma);
    float lumaSE = dot(float3(content.eval(frag + float2( 1.0,  1.0)).rgb), luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    // Flat region: nothing to smooth, return the source untouched.
    if (lumaMax - lumaMin < 0.02) {
        return c;
    }

    float2 dir = float2(
        -((lumaNW + lumaNE) - (lumaSW + lumaSE)),
         ((lumaNW + lumaSW) - (lumaNE + lumaSE)));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * REDUCE_MUL), REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    // frag is in pixels (see the +/-1px luma taps above), so the blend offsets
    // must stay in pixels too — no texel-size (1/uSize) scaling here.
    dir = clamp(dir * rcpDirMin, -SPAN_MAX, SPAN_MAX);

    // Do the blend in float precision (the codebase's convention) to avoid
    // half/float mismatch on scalar-vector ops, then convert back at the end.
    float4 rgbA = 0.5 * (
        float4(content.eval(frag + dir * (1.0 / 3.0 - 0.5))) +
        float4(content.eval(frag + dir * (2.0 / 3.0 - 0.5))));
    float4 rgbB = rgbA * 0.5 + 0.25 * (
        float4(content.eval(frag + dir * -0.5)) +
        float4(content.eval(frag + dir *  0.5)));

    float lumaB = dot(rgbB.rgb, luma);
    float4 aa = (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
    return half4(mix(float4(c), aa, uStrength));
}
"""

// True refractive glass. Output stays in premultiplied alpha (RenderEffect
// contract): the final rgb is clamped to <= the emitted alpha, so anti-aliased
// glyph edges remain valid and halo-free. The glyph body is emitted at reduced
// alpha (the backdrop shows through), while the beveled rim rises back to full
// alpha so its bright specular can read as a crisp glass edge.
//
// Smooth-backdrop note: the field the glass refracts is reconstructed in-shader
// (a soft vertical wash + top glow + two off-axis pools, album-tinted) to
// mirror the smooth gradient that actually sits behind the lyrics. Because that
// real backdrop is low-frequency, a reconstructed field lenses
// indistinguishably from sampling the real pixels — and it needs no fragile
// per-frame backdrop capture.
//
// Optics model (three layers, all riding the existing uniforms):
//  - bevel refraction: Snell bend where the alpha-field normal turns (edges);
//  - interior slab parallax: tilt/drift offset of the backdrop across FLAT
//    faces, scaling with uRefraction — the "thickness" a bevel-only model
//    lacks;
//  - liquid: fast edge shimmer (edge-gated, three interfering octaves with a
//    slow breath) + a slow ~600px face swell (ungated, far too broad to
//    lattice a button disc);
//  - shine: the glint twinkles in hash-staggered 4px cells with a slowly
//    cycling chromatic spread — scaled by uLiquid so surfaceMotion = 0 presets
//    stay perfectly still. No pass travels across the pane; see the shader's
//    own note where the light sheet used to be.
private const val LIQUID_GLASS_SRC = """
uniform shader content;
uniform float2 uSize;
uniform float uTime;
uniform float2 uTilt;
uniform float3 uTint;
uniform float uBodyOpacity;   // glass body opacity (lower = more see-through)
uniform float uRefraction;    // how hard the bevel lenses the backdrop
uniform float uRimGain;       // brightness of the specular glass edge
uniform float uDispersion;    // chromatic aberration at the refracting edges
uniform float uSampleRings;   // bevel sample rings 1/2/3 → 5/9/13 taps per pixel
uniform float3 uTint2;        // second album tone (blurred-art backdrop)
uniform float uBackdropMix;   // 0 = flat single-tint wash; >0 = lens the album art
uniform float uRoundness;     // bevel shoulder width: 1 = neutral, higher = rounder/softer edge
uniform float uDepth;         // profondeur: 1 = neutral, higher = steeper relief / more 3D
uniform float uLiquid;        // surface unrest: 1 = full moving sheen, lower = calmer, cleaner glass
uniform float uReflection;    // environment ("room") reflection strength
uniform float uGloss;         // specular exponent: higher = tighter, mirror-polished glint
uniform float uTiltAmount;    // how strongly device tilt moves the light/reflection
uniform float uLightAngle;    // key-light direction, radians
uniform float uFresnelPower;  // Fresnel falloff: lower = broader reflective rim
uniform float uFrost;         // frosted roughness: 0 = clear, higher = misted
uniform float2 uBulge;        // press-bulge centre, normalized (0..1) in the surface
uniform float uBulgeAmt;      // press-bulge swell, 0 = none .. 1 = full dome
uniform float uBulgeR;        // press-bulge dome radius in px; <=0 falls back to uSize.x/6

// Smooth album-tinted backdrop field, reconstructed so the glass can lens it.
// Returns a 0..1 luminance weight for the tint at uv (matches the vertical
// wash + soft top glow drawn behind the lyrics). Two soft off-axis pools give
// the field low-frequency STRUCTURE: a featureless gradient displaces into
// itself and refraction reads as nothing — these pools are what make the
// lensing visible while staying smooth enough to pass for the real backdrop.
float backdropField(float2 uv) {
    float wash = mix(0.45, 0.0, clamp(uv.y, 0.0, 1.0));
    float glow = smoothstep(1.0, 0.0, distance(uv, float2(0.5, 0.22)) * 1.5);
    float pool1 = smoothstep(0.85, 0.0, distance(uv, float2(0.22, 0.65))) * 0.16;
    float pool2 = smoothstep(0.95, 0.0, distance(uv, float2(0.80, 0.38))) * 0.12;
    return clamp(wash + glow * 0.5 + pool1 + pool2, 0.0, 1.0);
}

// Album tone the glass lenses at uv. With the blurred cover behind the lyrics
// (uBackdropMix > 0) this is a two-tone album blend, so the refraction carries
// real colour variation like a photo behind glass. The blend runs mostly down
// the surface with a slight diagonal lean, so HORIZONTAL displacement also
// crosses a colour boundary (a pure-vertical blend made sideways lensing
// invisible). With no blurred art (uBackdropMix = 0) it collapses to uTint —
// identical to the old single tone.
float3 backdropTintAt(float2 uv) {
    float t = clamp(uv.y * 0.82 + uv.x * 0.18, 0.0, 1.0);
    float3 two = mix(uTint, uTint2, smoothstep(0.0, 1.0, t));
    return mix(uTint, two, uBackdropMix);
}

// Procedural studio environment, sampled by the reflection vector. A soft
// vertical light gradient plus a moving key light and a cooler fill, so the
// glass catches a believable "room" that streaks across the bevel as the
// surface normal turns — instead of one flat, generic highlight. Device tilt
// and a slow drift move the lights so the reflections stay alive.
float3 environment(float3 r, float2 tilt, float2 keyDir, float t, float liquid) {
    float2 d = r.xy;
    // Screen y is down, so the bright sky is where the reflection points up
    // (r.y < 0): a soft top->bottom studio gradient.
    float up = clamp(0.5 - 0.5 * r.y, 0.0, 1.0);
    float3 sky = mix(float3(0.05, 0.06, 0.08), float3(0.82, 0.88, 1.0),
                     smoothstep(0.12, 0.96, up));
    // Bright soft key light placed along the chosen light angle (keyDir),
    // nudged by device tilt + a slow drift.
    float2 key = keyDir + tilt * 0.5
               + 0.06 * float2(sin(t * 0.40), cos(t * 0.33)) * liquid;
    float keyI = smoothstep(0.55, 0.0, distance(d, key));
    // Cooler fill light from the opposite corner.
    float2 fill = -keyDir * 0.85 - tilt * 0.4;
    float fillI = smoothstep(0.72, 0.0, distance(d, fill)) * 0.5;
    return sky + float3(1.0, 0.97, 0.92) * keyI * 1.7
              + float3(0.65, 0.82, 1.0) * fillI;
}

half4 main(float2 p) {
    half4 src = content.eval(p);
    float a = float(src.a);
    // Outside the glyphs the surface is empty, so the glass exists only where a
    // letter is: hand those pixels straight back (transparent) and the real
    // backdrop behind the lyric layer shows through untouched.
    if (a < 0.004) {
        return src;
    }

    // Bevel normals from the glyph alpha field. The sample count is a user
    // setting (uSampleRings): the fine cross is always taken; the broad ring and
    // the diagonal ring are gated so lower quality skips those texture fetches —
    // fewer taps per pixel is a real GPU saving.
    // Roundness widens the taps: a broader sampling radius reads the alpha edge
    // over a wider band, so the bevel shoulder rolls off round and pillowy
    // instead of a tight, sharp edge (rr = 1 reproduces the original look).
    float rr = uRoundness;
    float aL1 = float(content.eval(p + float2(-1.25 * rr, 0.0)).a);
    float aR1 = float(content.eval(p + float2( 1.25 * rr, 0.0)).a);
    float aU1 = float(content.eval(p + float2(0.0, -1.25 * rr)).a);
    float aD1 = float(content.eval(p + float2(0.0,  1.25 * rr)).a);
    float2 grad = float2(aL1 - aR1, aU1 - aD1);

    if (uSampleRings > 1.5) {
        // Broad ring: rounder, softer bevel.
        float aL2 = float(content.eval(p + float2(-2.5 * rr, 0.0)).a);
        float aR2 = float(content.eval(p + float2( 2.5 * rr, 0.0)).a);
        float aU2 = float(content.eval(p + float2(0.0, -2.5 * rr)).a);
        float aD2 = float(content.eval(p + float2(0.0,  2.5 * rr)).a);
        grad += 0.4 * float2(aL2 - aR2, aU2 - aD2);
    }
    if (uSampleRings > 2.5) {
        // Diagonal ring: smoother normals on curved strokes.
        float aNW = float(content.eval(p + float2(-1.8 * rr, -1.8 * rr)).a);
        float aNE = float(content.eval(p + float2( 1.8 * rr, -1.8 * rr)).a);
        float aSW = float(content.eval(p + float2(-1.8 * rr,  1.8 * rr)).a);
        float aSE = float(content.eval(p + float2( 1.8 * rr,  1.8 * rr)).a);
        grad += 0.3 * float2((aNW + aSW) - (aNE + aSE),
                             (aNW + aNE) - (aSW + aSE));
    }

    // Liquid, two scales:
    // 1) Edge shimmer — the original fine ripple, gated by the real bevel
    //    strength (the geometric gradient BEFORE the ripple) so the fast
    //    undulation only lives where there already IS an edge. Ungated, a big
    //    flat face — a button disc, the dock slab — became a lattice of ripple
    //    highlights. Thin glyphs are all edge, so lyrics keep their liquid life.
    float edge = clamp(length(grad) * 1.5, 0.0, 1.0);
    float w1 = sin(p.x * 0.055 + uTime * 1.7) * cos(p.y * 0.081 - uTime * 1.3);
    float w2 = sin((p.x + p.y) * 0.035 - uTime * 0.9);
    // A third, counter-running octave breaks the two-wave moiré into organic
    // caustic-like interference, and a slow breath swells the whole shimmer in
    // and out so the motion never settles into a loop the eye can lock onto.
    float w3 = sin(p.y * 0.047 - p.x * 0.021 + uTime * 2.3);
    float breathe = 0.8 + 0.2 * sin(uTime * 0.7);
    grad += (0.04 * float2(w1, w2) + 0.02 * float2(w3, -w3))
            * uLiquid * a * edge * breathe;
    // 2) Face swell — a much longer wavelength (~600px vs ~100px) at a quarter
    //    of the amplitude, NOT edge-gated. This is what makes a flat pane read
    //    as liquid: one slow dome drifting across the face, far too broad to
    //    lattice, gently steering the interior lensing below.
    float s1 = sin(p.x * 0.010 + uTime * 0.55) * sin(p.y * 0.012 - uTime * 0.42);
    float s2 = sin((p.x - p.y) * 0.007 + uTime * 0.31);
    grad += 0.011 * uLiquid * float2(s1, s2) * a;

    // Press bulge: a soft dome that swells the glass under a pressed button. The
    // radial slope peaks mid-radius (derivative of a dome) so the surface reads as
    // a smooth bump that lenses the backdrop; uBulgeAmt animates it in/out.
    if (uBulgeAmt > 0.001) {
        float2 bc = uBulge * uSize;
        float R = (uBulgeR > 0.0) ? uBulgeR : (uSize.x / 6.0);
        float rr2 = distance(p, bc);
        float dome = smoothstep(R, 0.0, rr2);
        float2 bdir = (rr2 > 0.5) ? (p - bc) / rr2 : float2(0.0, 0.0);
        grad += bdir * (dome * (1.0 - dome)) * uBulgeAmt * 10.0;
    }

    // Surface normal from the alpha heightfield. Depth (profondeur) scales how
    // hard the bevel tips the normal off the surface — the dominant "3D" knob,
    // now a strong multiplier on the slope instead of a small z-base nudge.
    float slopeGain = 3.5 * uDepth;
    float3 N = normalize(float3(grad * slopeGain, 1.0));

    // Frost: per-pixel micro-roughness scatters the reflection, refraction and
    // glint into a misted, frosted surface. Gated so uFrost = 0 is unchanged.
    if (uFrost > 0.001) {
        float h1 = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        float h2 = fract(sin(dot(p, float2(39.346, 11.135))) * 24634.6345);
        N = normalize(N + float3((float2(h1, h2) - 0.5) * uFrost * 0.6, 0.0));
    }

    float2 uv = p / uSize;
    float3 I = float3(0.0, 0.0, -1.0);   // view ray, into the screen

    // Fresnel (Schlick, F0 = 0.04 for glass): ~4% reflection head-on, climbing
    // to ~100% at grazing edges. This is what makes the rim catch the light and
    // the face stay see-through — the core of the glass look. uFresnelPower sets
    // how broad the reflective rim band is (lower = wider shoulder).
    float cosV = clamp(N.z, 0.0, 1.0);
    float fres = 0.04 + 0.96 * pow(1.0 - cosV, uFresnelPower);

    // Refraction (Snell, via refract) with a per-channel index of refraction so
    // R/G/B bend by different amounts — true chromatic dispersion, strongest at
    // the edges where the bevel turns. eta = n_air / n_glass ~ 0.66.
    float dispSpread = 0.06 * uDispersion;
    float3 Tr = refract(I, N, 0.66 - dispSpread);
    float3 Tg = refract(I, N, 0.66);
    float3 Tb = refract(I, N, 0.66 + dispSpread);
    float power = uRefraction * 1.6;

    // Interior slab parallax: a real glass pane offsets what's behind it even
    // where the surface is dead flat (thickness x viewing angle), which the
    // bevel-only refract() above can't produce — flat face ⇒ N = (0,0,1) ⇒ no
    // bend. Device tilt supplies the viewing angle and a slow drift keeps the
    // face alive when the phone is still; both scale with uRefraction so the
    // existing presets keep their meaning (refraction 0 stays perfectly flat).
    // The per-channel spread fringes the interior under high dispersion, so a
    // "prism" preset fringes the whole pane, not just its rim.
    float2 faceOfs = (uTilt * uTiltAmount * 0.35
                    + 0.05 * float2(sin(uTime * 0.23), cos(uTime * 0.19)) * uLiquid)
                    * uRefraction;
    float chroma = 0.30 * uDispersion;
    float2 uvR = uv + Tr.xy * power + faceOfs * (1.0 + chroma);
    float2 uvG = uv + Tg.xy * power + faceOfs;
    float2 uvB = uv + Tb.xy * power + faceOfs * (1.0 - chroma);
    float3 refr = float3(
        backdropTintAt(uvR).r * backdropField(uvR),
        backdropTintAt(uvG).g * backdropField(uvG),
        backdropTintAt(uvB).b * backdropField(uvB));

    // Vibrancy: glass slightly saturates what shows through it (thin-slab
    // absorption). A restrained boost — enough to make the transmitted colour
    // read richer than the raw backdrop without posterizing dark tones.
    float lum = dot(refr, float3(0.299, 0.587, 0.114));
    refr = clamp(mix(float3(lum), refr, 1.22), 0.0, 1.0);

    // Reflected environment: the room the glass catches, turning with N so the
    // reflection streaks across the bevel as the surface curves. The key light
    // sits along uLightAngle; uTiltAmount scales how much device tilt sways it.
    float2 keyDir = float2(cos(uLightAngle), -sin(uLightAngle)) * 0.69;
    float3 refl = environment(reflect(I, N), uTilt * uTiltAmount, keyDir, uTime, uLiquid);

    // Crisp specular glint from the same key light (uLightAngle + tilt), with a
    // uGloss-controlled exponent (higher = tighter mirror), dispersed for sparkle.
    float2 lightXY = float2(cos(uLightAngle), -sin(uLightAngle));
    float3 L = normalize(float3(
        // Scaled by uLiquid like every other time-varying term: the file's own
        // contract is that surfaceMotion = 0 "stays perfectly still", and these
        // three were the exceptions that kept the glint and the dispersion
        // drifting anyway — which meant a still preset still cost a frame clock.
        lightXY.x * 0.5 - uTilt.x * 0.8 * uTiltAmount + 0.25 * sin(uTime * 0.37) * uLiquid,
        lightXY.y * 0.5 + uTilt.y * 0.8 * uTiltAmount + 0.20 * cos(uTime * 0.29) * uLiquid,
        0.85));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float ndh   = max(dot(N, H), 0.0);
    float spec  = pow(ndh, uGloss);
    // The rainbow spread of the glint slowly widens and narrows, so the
    // chromatic fringe cycles instead of sitting frozen on the bevel.
    float dsp   = 0.015 * uDispersion * (1.0 + 0.35 * sin(uTime * 0.9) * uLiquid);
    float specR = pow(max(dot(normalize(N + float3(dsp, 0.0, 0.0)), H), 0.0), uGloss);
    float specB = pow(max(dot(normalize(N - float3(dsp, 0.0, 0.0)), H), 0.0), uGloss);

    // Edge twinkle: 4px cells pulse the glint with hash-staggered phases and
    // rates, so bevel highlights sparkle as points firing off one another
    // instead of glowing statically. Zero-mean-ish (baseline -0.15 against a
    // ~0.27 mean pulse) and scaled by uLiquid, so calm presets keep a steady
    // glint and the average brightness barely moves.
    float twHash = fract(sin(dot(floor(p * 0.25), float2(127.1, 311.7))) * 43758.5453);
    float twinkle = pow(0.5 + 0.5 * sin(uTime * (1.5 + 3.0 * twHash) + twHash * 6.2831), 4.0);
    float glintGain = 1.0 + (0.6 * twinkle - 0.15) * uLiquid;

    // Body: the glyph's own colour (kept legible) with a hint of the lensed
    // backdrop; leans more see-through over real blurred art.
    float3 glyphTint = float3(src.rgb) / a;
    float bodyMix = mix(0.72, 0.42, uBackdropMix);
    float3 bodyCol = mix(refr, glyphTint, bodyMix);

    // Fresnel-blend the reflection over the body (edges reflect, the face
    // transmits), then add the dispersed glint. uRimGain scales both the
    // reflection and the glint, so "Edge highlight" is a real brightness knob.
    // The glint is Fresnel-weighted too, so it rides the bevel edge rather than
    // washing the whole flat face white (a front-facing flat surface would
    // otherwise fire the specular uniformly).
    float3 col3 = mix(bodyCol, refl * uReflection, clamp(fres * 1.1, 0.0, 1.0));
    col3 += float3(specR, spec, specB) * uRimGain * fres * glintGain;

    // There is deliberately no traveling light sheet here. A soft diagonal band
    // used to glide across every pane every ~7s — the classic "shine" pass — and
    // it was the one motion here you could not look away from: it crosses the
    // whole pane, it is the brightest thing on it while it does, and unlike the
    // shimmer and the swell it has somewhere to be, so the eye tracks it off the
    // edge and then waits for the next one. Removed rather than damped: at any
    // brightness a band crossing the pane still reads as an event, and glass is
    // meant to be a surface, not a signal. The other liquid terms stay — they
    // undulate in place, so they read as material rather than as something
    // happening. Do not reintroduce a sweep here without asking.

    // Highlight shoulder: every edge term above stacks additively in the same
    // 1-2px band (Fresnel rim + glint, each gained by its own knob), and
    // the premultiply clamp below used to flatten any overflow into a solid
    // max-brightness line — the over-sharpened halo look when several edge
    // settings run hot. Roll intensities above the knee off asymptotically
    // toward white instead; pixels below the knee are bit-identical, so calm
    // presets keep their exact look and only blown rim pixels are compressed.
    float3 overHi = max(col3 - 0.82, float3(0.0));
    col3 = min(col3, float3(0.82)) + 0.18 * (1.0 - exp(-overHi / 0.18));

    // Transparent face, opaque bright rim: alpha is low across the body (backdrop
    // reads through) and climbs to full where Fresnel and the glint peak, so the
    // rim highlight reads as a crisp glass edge rather than being clamped away.
    // Same shoulder as the colour: the outline saturates to opaque gradually
    // instead of snapping, so the rim doesn't etch a hard 1px contour.
    float rimSum = fres * 1.2 + spec;
    float rim = min(rimSum, 0.82) + 0.18 * (1.0 - exp(-max(rimSum - 0.82, 0.0) / 0.18));
    float outA = clamp(a * (uBodyOpacity + (1.0 - uBodyOpacity) * rim), 0.0, a);

    float3 col = col3 * outA;              // premultiplied
    col = min(col, float3(outA));          // keep rgb <= alpha (premult-valid)
    return half4(half3(col), half(outA));
}
"""
