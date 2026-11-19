package tf.monochrome.android.domain.model

import androidx.annotation.StringRes
import tf.monochrome.android.R
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.visualizer.AmbientVisualizerSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything a music link can move, as the listener has it set: what a target
 * reads its setting's own value from, and what decides whether it shows.
 */
data class StudioLook(
    val lyrics: LyricsFxSettings = LyricsFxSettings.DEFAULT,
    /** The player's own controls: the play disc, the chevrons, the dock. */
    val player: PlayerGlassSettings = PlayerGlassSettings.DEFAULT,
    /** The universal glass: the mini player, the tab bar, every panel and pill. */
    val panels: PlayerGlassSettings = PlayerGlassSettings.DEFAULT,
    val tv: GlyphTvTuning = GlyphTvTuning(),
    /** The CRT effect's look; [GlyphTvShader.OFF] when it is off. */
    val tvLook: GlyphTvShader = GlyphTvShader.OFF,
    /** MilkDrop in the player's background (the Visualizer tab's ambient mode). */
    val ambient: AmbientVisualizerSettings = AmbientVisualizerSettings(),
)

/** Which Studio tab a linked setting is on, and so which renderer draws it. */
enum class LinkArea(@StringRes val title: Int, val prefix: String) {
    LYRICS(R.string.mode_lyrics, "lyrics"),
    PLAYER(R.string.fx_player, "player"),
    PANELS(R.string.fx_ui_panels, "panels"),
    TV(R.string.settings_crt_effect, "tv"),
    VISUALIZER(R.string.visualizer, "visualizer"),
}

/**
 * The visual settings a music link may move, and how.
 *
 * Only settings that are drawn — read in a draw or layer block, every frame —
 * are here. Some are deliberately left out, each for a reason a listener can
 * be told:
 *
 *  - Switches and choices (god rays on/off, the light source, the layer): a
 *    switch would flash on and off with the beat.
 *  - Anything multiplied by elapsed time — the god rays' orbit, the wave
 *    tightness: moving the rate moves the whole phase, so the light and the
 *    letters would jump instead of speeding up. The wave speed was one until
 *    the wave got a clock of its own that the speed adds to each frame
 *    (rememberLetterWaveSeconds); a rate can be a target only that way.
 *  - Anything that gates a clock or a sensor (surface motion, tilt), or lays
 *    the lyrics out (size, spacing, margins, lines): those are read in
 *    composition, and moving them every frame would rebuild the lyric list.
 *  - Lengths of time an animation runs for (the colour transition, the god
 *    rays' light transition): they are read when a fade starts, not drawn.
 *  - Stepped quality (sample rings, ray quality, the CRT's pixel size and mask
 *    size): each step rebuilds what the GPU draws with.
 *  - The CRT's zoom: not linked yet. Taps would follow it as they follow the
 *    linked curve and tilt (each gesture holds the tube it began on), but a
 *    picture that grows and shrinks with the beat has not been asked for.
 *  - The visualizer engine's own settings (mesh, frame rate, delay, preset
 *    timer, sensitivity, brightness): they configure projectM, which already
 *    follows the music, and sensitivity carries a flashing-light warning.
 *
 * [min] and [max] are each field's own `clamped()` bounds, so a link can never
 * ask a renderer for a value the setting itself refuses.
 */
class VisualLinkTarget(
    val key: String,
    @StringRes val label: Int,
    val group: Group,
    val min: Float,
    val max: Float,
    /** What has to be on for this setting to show at all. */
    val needs: Need,
    /** The setting's own value in [StudioLook]: what the slider shows. */
    val read: (StudioLook) -> Float,
    /** [StudioLook] with this setting at the value given, already inside [min]..[max]. */
    internal val write: (StudioLook, Float) -> StudioLook,
    val format: (Float) -> String,
) {
    enum class Group { GOD_RAYS, GLOW, GLASS, WAVE, BUTTON_GLASS, BACKDROP, SHADOW, TV, AMBIENT }

    enum class Need {
        NOTHING, GOD_RAYS, BACKLIGHT, BASS_REACTION, LIQUID_GLASS, WAVE, SHADOW,

        /** This tab's Liquid glass switch. */
        GLASS_ON,

        /** Some backdrop blur on this tab's glass: with none, there is no frost to move. */
        BACKDROP,

        /** Some shadow depth on this tab's glass: with none, no shadow is drawn. */
        GLASS_SHADOW,

        /** The CRT effect on. */
        TV,

        /** The CRT effect's CRT look, the one with these controls. */
        TV_CRT,

        /** The CRT effect's CRT-Royale look. */
        TV_ROYALE,

        /** The Visualizer tab's ambient mode: MilkDrop in the player's background. */
        AMBIENT,

        /** Glow behind album art (Settings › Appearance), which the bass reaction draws. */
        ART_GLOW,
    }

    /** The Studio tab this setting is on. */
    val area: LinkArea = LinkArea.entries.first { key.startsWith(it.prefix + ".") }

    /** The requirement [look] does not meet, or null when the setting shows. */
    fun unmet(look: StudioLook): Need? {
        val fx = look.lyrics
        val glass = if (area == LinkArea.PLAYER) look.player else look.panels
        return when (needs) {
            Need.NOTHING -> null
            Need.GOD_RAYS -> if (fx.godRays) null else Need.GOD_RAYS
            Need.BACKLIGHT -> when {
                !fx.godRays -> Need.GOD_RAYS
                fx.godRaySource != LyricsFxSettings.GOD_RAYS_BACKLIGHT -> Need.BACKLIGHT
                else -> null
            }
            Need.BASS_REACTION -> if (fx.bassReact > 0.01f) null else Need.BASS_REACTION
            Need.LIQUID_GLASS -> if (fx.liquidGlass) null else Need.LIQUID_GLASS
            Need.WAVE -> if (fx.rotationDegrees > 0.05f) null else Need.WAVE
            Need.SHADOW -> if (fx.shadowDepth > 0.01f) null else Need.SHADOW
            Need.GLASS_ON -> if (glass.enabled) null else Need.GLASS_ON
            Need.BACKDROP -> when {
                !glass.enabled -> Need.GLASS_ON
                glass.hazeBlurDp <= 0f -> Need.BACKDROP
                else -> null
            }
            Need.GLASS_SHADOW -> when {
                !glass.enabled -> Need.GLASS_ON
                glass.shadowDepth <= 0.01f -> Need.GLASS_SHADOW
                else -> null
            }
            Need.TV -> if (look.tvLook != GlyphTvShader.OFF) null else Need.TV
            Need.TV_CRT -> when (look.tvLook) {
                GlyphTvShader.OFF -> Need.TV
                GlyphTvShader.CRT -> null
                else -> Need.TV_CRT
            }
            Need.TV_ROYALE -> when (look.tvLook) {
                GlyphTvShader.OFF -> Need.TV
                GlyphTvShader.ROYALE -> null
                else -> Need.TV_ROYALE
            }
            Need.AMBIENT -> if (look.ambient.enabled) null else Need.AMBIENT
            Need.ART_GLOW -> when {
                !fx.glowBehindArt -> Need.ART_GLOW
                fx.bassReact <= 0.01f -> Need.BASS_REACTION
                else -> null
            }
        }
    }
}

object VisualLinkTargets {
    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun deg(v: Float) = "${v.roundToInt()}°"
    private fun two(v: Float) = String.format(Locale.US, "%.2f", v)
    private fun dp(v: Float) = "${v.roundToInt()} dp"

    private fun lyric(
        field: String, @StringRes label: Int, group: VisualLinkTarget.Group, min: Float, max: Float,
        needs: VisualLinkTarget.Need, get: (LyricsFxSettings) -> Float,
        set: (LyricsFxSettings, Float) -> LyricsFxSettings, format: (Float) -> String,
    ) = VisualLinkTarget(
        "lyrics.$field", label, group, min, max, needs,
        { get(it.lyrics) }, { look, v -> look.copy(lyrics = set(look.lyrics, v)) }, format,
    )

    /** One glass slider, made twice: for the player's controls and for the UI panels. */
    private class GlassField(
        val field: String, @StringRes val label: Int, val group: VisualLinkTarget.Group,
        val min: Float, val max: Float, val needs: VisualLinkTarget.Need,
        val get: (PlayerGlassSettings) -> Float, val set: (PlayerGlassSettings, Float) -> PlayerGlassSettings,
        val format: (Float) -> String,
    )

    private val GLASS_FIELDS = listOf(
        GlassField("bodyOpacity", R.string.fx_glass_opacity, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 1f,
            VisualLinkTarget.Need.GLASS_ON, { it.bodyOpacity }, { g, v -> g.copy(bodyOpacity = v) }, ::pct),
        GlassField("frost", R.string.fx_frosted_blur, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 1f,
            VisualLinkTarget.Need.GLASS_ON, { it.frost }, { g, v -> g.copy(frost = v) }, ::pct),
        GlassField("hazeBlurDp", R.string.fx_backdrop_blur, VisualLinkTarget.Group.BACKDROP, 0f, 80f,
            VisualLinkTarget.Need.BACKDROP, { it.hazeBlurDp }, { g, v -> g.copy(hazeBlurDp = v) }, ::dp),
        GlassField("hazeTint", R.string.fx_backdrop_tint, VisualLinkTarget.Group.BACKDROP, 0f, 2f,
            VisualLinkTarget.Need.BACKDROP, { it.hazeTint }, { g, v -> g.copy(hazeTint = v) }, ::pct),
        GlassField("roundness", R.string.fx_roundness, VisualLinkTarget.Group.BUTTON_GLASS, 0.5f, 2f,
            VisualLinkTarget.Need.GLASS_ON, { it.roundness }, { g, v -> g.copy(roundness = v) }, ::two),
        GlassField("depth", R.string.fx_depth_profondeur, VisualLinkTarget.Group.BUTTON_GLASS, 0.5f, 2f,
            VisualLinkTarget.Need.GLASS_ON, { it.depth }, { g, v -> g.copy(depth = v) }, ::two),
        GlassField("refraction", R.string.fx_refraction, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 0.4f,
            VisualLinkTarget.Need.GLASS_ON, { it.refraction }, { g, v -> g.copy(refraction = v) }, ::two),
        GlassField("dispersion", R.string.fx_chromatic_aberration, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 2f,
            VisualLinkTarget.Need.GLASS_ON, { it.dispersion }, { g, v -> g.copy(dispersion = v) }, ::pct),
        GlassField("lightAngleDeg", R.string.fx_light_angle, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 360f,
            VisualLinkTarget.Need.GLASS_ON, { it.lightAngleDeg }, { g, v -> g.copy(lightAngleDeg = v) }, ::deg),
        GlassField("rimBrightness", R.string.fx_edge_highlight, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 2f,
            VisualLinkTarget.Need.GLASS_ON, { it.rimBrightness }, { g, v -> g.copy(rimBrightness = v) }, ::pct),
        GlassField("edgeWidth", R.string.fx_edge_width, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 1f,
            VisualLinkTarget.Need.GLASS_ON, { it.edgeWidth }, { g, v -> g.copy(edgeWidth = v) }, ::pct),
        GlassField("reflection", R.string.fx_reflection, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 2f,
            VisualLinkTarget.Need.GLASS_ON, { it.reflection }, { g, v -> g.copy(reflection = v) }, ::pct),
        GlassField("gloss", R.string.fx_gloss, VisualLinkTarget.Group.BUTTON_GLASS, 0f, 1f,
            VisualLinkTarget.Need.GLASS_ON, { it.gloss }, { g, v -> g.copy(gloss = v) }, ::pct),
        GlassField("shadowDepth", R.string.fx_shadow_depth, VisualLinkTarget.Group.SHADOW, 0f, 1f,
            VisualLinkTarget.Need.GLASS_SHADOW, { it.shadowDepth }, { g, v -> g.copy(shadowDepth = v) }, ::pct),
        GlassField("shadowSoftness", R.string.fx_shadow_softness, VisualLinkTarget.Group.SHADOW, 0f, 1f,
            VisualLinkTarget.Need.GLASS_SHADOW, { it.shadowSoftness }, { g, v -> g.copy(shadowSoftness = v) }, ::pct),
        GlassField("shadowTint", R.string.fx_shadow_tint, VisualLinkTarget.Group.SHADOW, 0f, 1f,
            VisualLinkTarget.Need.GLASS_SHADOW, { it.shadowTint }, { g, v -> g.copy(shadowTint = v) }, ::pct),
    )

    private fun glass(area: LinkArea, f: GlassField) = VisualLinkTarget(
        "${area.prefix}.${f.field}", f.label, f.group, f.min, f.max, f.needs,
        { look -> f.get(if (area == LinkArea.PLAYER) look.player else look.panels) },
        { look, v ->
            if (area == LinkArea.PLAYER) look.copy(player = f.set(look.player, v))
            else look.copy(panels = f.set(look.panels, v))
        },
        f.format,
    )

    private fun tv(
        field: String, @StringRes label: Int, min: Float, max: Float, needs: VisualLinkTarget.Need,
        get: (GlyphTvTuning) -> Float, set: (GlyphTvTuning, Float) -> GlyphTvTuning,
        format: (Float) -> String = ::pct,
    ) = VisualLinkTarget(
        "tv.$field", label, VisualLinkTarget.Group.TV, min, max, needs,
        { get(it.tv) }, { look, v -> look.copy(tv = set(look.tv, v)) }, format,
    )

    private val LYRICS: List<VisualLinkTarget> = listOf(
        // God rays.
        lyric("godRayExposure", R.string.fx_god_ray_exposure, VisualLinkTarget.Group.GOD_RAYS, 0f, 1.5f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayExposure }, { f, v -> f.copy(godRayExposure = v) }, ::pct),
        lyric("godRayDensity", R.string.fx_god_ray_length, VisualLinkTarget.Group.GOD_RAYS, 0.2f, 1f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayDensity }, { f, v -> f.copy(godRayDensity = v) }, ::pct),
        lyric("godRayDecay", R.string.fx_god_ray_decay, VisualLinkTarget.Group.GOD_RAYS, 0.85f, 1f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayDecay }, { f, v -> f.copy(godRayDecay = v) },
            { String.format(Locale.US, "%.3f", it) }),
        lyric("godRaySunSize", R.string.fx_god_ray_sun_size, VisualLinkTarget.Group.GOD_RAYS, 0.03f, 0.3f,
            VisualLinkTarget.Need.BACKLIGHT, { it.godRaySunSize }, { f, v -> f.copy(godRaySunSize = v) }, ::pct),
        lyric("godRayShimmer", R.string.fx_god_ray_dust, VisualLinkTarget.Group.GOD_RAYS, 0f, 1f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayShimmer }, { f, v -> f.copy(godRayShimmer = v) }, ::pct),
        lyric("godRayBeat", R.string.fx_god_ray_beat, VisualLinkTarget.Group.GOD_RAYS, 0f, 1f,
            VisualLinkTarget.Need.BASS_REACTION, { it.godRayBeat }, { f, v -> f.copy(godRayBeat = v) }, ::pct),
        lyric("godRaySway", R.string.fx_god_ray_sway, VisualLinkTarget.Group.GOD_RAYS, 0f, 1f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRaySway }, { f, v -> f.copy(godRaySway = v) }, ::pct),
        lyric("godRayAzimuthDeg", R.string.fx_god_ray_azimuth, VisualLinkTarget.Group.GOD_RAYS, 0f, 360f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayAzimuthDeg }, { f, v -> f.copy(godRayAzimuthDeg = v) }, ::deg),
        lyric("godRayElevationDeg", R.string.fx_god_ray_elevation, VisualLinkTarget.Group.GOD_RAYS, 0f, 90f,
            VisualLinkTarget.Need.GOD_RAYS, { it.godRayElevationDeg }, { f, v -> f.copy(godRayElevationDeg = v) }, ::deg),
        lyric("glassRayCatch", R.string.fx_god_ray_glass_catch, VisualLinkTarget.Group.GOD_RAYS, 0f, 1f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRayCatch }, { f, v -> f.copy(glassRayCatch = v) }, ::pct),
        // Glow and the beat's swell. Both are drawn by the bass engine.
        lyric("glowRadiusDp", R.string.settings_glow_radius, VisualLinkTarget.Group.GLOW, 0f, 160f,
            VisualLinkTarget.Need.BASS_REACTION, { it.glowRadiusDp }, { f, v -> f.copy(glowRadiusDp = v) },
            { "+${it.roundToInt()} dp" }),
        lyric("glowBrightness", R.string.settings_glow_brightness, VisualLinkTarget.Group.GLOW, 0f, 0.6f,
            VisualLinkTarget.Need.BASS_REACTION, { it.glowBrightness }, { f, v -> f.copy(glowBrightness = v) }, ::pct),
        // The same glow behind the album cover (Settings › Appearance). Read
        // through its fallback, so a new link rests on the glow on screen;
        // a link pins it, as its slider does.
        lyric("artGlowRadiusDp", R.string.music_link_art_glow_radius, VisualLinkTarget.Group.GLOW, 0f, 160f,
            VisualLinkTarget.Need.ART_GLOW, { it.effectiveArtGlowRadiusDp }, { f, v -> f.copy(artGlowRadiusDp = v) },
            { "+${it.roundToInt()} dp" }),
        lyric("artGlowBrightness", R.string.music_link_art_glow_brightness, VisualLinkTarget.Group.GLOW, 0f, 0.6f,
            VisualLinkTarget.Need.ART_GLOW, { it.effectiveArtGlowBrightness }, { f, v -> f.copy(artGlowBrightness = v) }, ::pct),
        lyric("pumpAmount", R.string.fx_pump_amount, VisualLinkTarget.Group.GLOW, 0f, 0.25f,
            VisualLinkTarget.Need.BASS_REACTION, { it.pumpAmount }, { f, v -> f.copy(pumpAmount = v) },
            { "+${(it * 100).roundToInt()}%" }),
        // The glass letters.
        lyric("glassBodyOpacity", R.string.fx_glass_opacity, VisualLinkTarget.Group.GLASS, 0.2f, 1f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassBodyOpacity }, { f, v -> f.copy(glassBodyOpacity = v) }, ::pct),
        lyric("glassRefraction", R.string.fx_refraction, VisualLinkTarget.Group.GLASS, 0f, 0.4f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRefraction }, { f, v -> f.copy(glassRefraction = v) }, ::two),
        lyric("glassRimBrightness", R.string.fx_edge_highlight, VisualLinkTarget.Group.GLASS, 0f, 2f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRimBrightness }, { f, v -> f.copy(glassRimBrightness = v) }, ::pct),
        lyric("glassDispersion", R.string.fx_chromatic_aberration, VisualLinkTarget.Group.GLASS, 0f, 2f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassDispersion }, { f, v -> f.copy(glassDispersion = v) }, ::pct),
        lyric("glassRoundness", R.string.fx_roundness, VisualLinkTarget.Group.GLASS, 0.5f, 2f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRoundness }, { f, v -> f.copy(glassRoundness = v) }, ::two),
        lyric("glassDepth", R.string.fx_depth_profondeur, VisualLinkTarget.Group.GLASS, 0.5f, 2f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassDepth }, { f, v -> f.copy(glassDepth = v) }, ::two),
        lyric("glassFrost", R.string.fx_frosted_blur, VisualLinkTarget.Group.GLASS, 0f, 1f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassFrost }, { f, v -> f.copy(glassFrost = v) }, ::pct),
        lyric("glassLightAngleDeg", R.string.fx_light_angle, VisualLinkTarget.Group.GLASS, 0f, 360f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassLightAngleDeg }, { f, v -> f.copy(glassLightAngleDeg = v) }, ::deg),
        lyric("glassEdgeWidth", R.string.fx_edge_width, VisualLinkTarget.Group.GLASS, 0f, 1f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassEdgeWidth }, { f, v -> f.copy(glassEdgeWidth = v) }, ::pct),
        lyric("glassReflection", R.string.fx_reflection, VisualLinkTarget.Group.GLASS, 0f, 2f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassReflection }, { f, v -> f.copy(glassReflection = v) }, ::pct),
        lyric("glassGloss", R.string.fx_gloss, VisualLinkTarget.Group.GLASS, 0f, 1f,
            VisualLinkTarget.Need.LIQUID_GLASS, { it.glassGloss }, { f, v -> f.copy(glassGloss = v) }, ::pct),
        // Anti-aliasing is a uniform of the same glass pass.
        lyric("fxaaStrength", R.string.fx_aa_strength, VisualLinkTarget.Group.GLASS, 0f, 1f,
            VisualLinkTarget.Need.NOTHING, { it.fxaaStrength }, { f, v -> f.copy(fxaaStrength = v) }, ::pct),
        // The 3D wave, the shadow under it, and the bend of the whole block.
        lyric("rotationDegrees", R.string.fx_tilt, VisualLinkTarget.Group.WAVE, 0f, 25f,
            VisualLinkTarget.Need.WAVE, { it.rotationDegrees }, { f, v -> f.copy(rotationDegrees = v) }, ::deg),
        // The speed adds to the wave's own clock each frame, never multiplies
        // the app's, so a linked speed speeds the wave up instead of throwing it.
        lyric("waveSpeed", R.string.fx_wave_speed, VisualLinkTarget.Group.WAVE, 0.25f, 3f,
            VisualLinkTarget.Need.WAVE, { it.waveSpeed }, { f, v -> f.copy(waveSpeed = v) },
            { String.format(Locale.US, "%.2fx", it) }),
        lyric("waveTravelDp", R.string.fx_wave_travel, VisualLinkTarget.Group.WAVE, 0f, 8f,
            VisualLinkTarget.Need.WAVE, { it.waveTravelDp }, { f, v -> f.copy(waveTravelDp = v) },
            { String.format(Locale.US, "%.1f dp", it) }),
        lyric("shadowDepth", R.string.fx_shadow_depth, VisualLinkTarget.Group.WAVE, 0f, 1f,
            VisualLinkTarget.Need.SHADOW, { it.shadowDepth }, { f, v -> f.copy(shadowDepth = v) }, ::pct),
        lyric("curvatureDeg", R.string.fx_curvature, VisualLinkTarget.Group.WAVE, 0f, 75f,
            VisualLinkTarget.Need.NOTHING, { it.curvatureDeg }, { f, v -> f.copy(curvatureDeg = v) }, ::deg),
        // A link sets the sides' own bend, never "same as top and bottom".
        lyric("curvatureSideDeg", R.string.fx_curvature_sides, VisualLinkTarget.Group.WAVE, 0f, 75f,
            VisualLinkTarget.Need.NOTHING, { it.effectiveCurvatureSideDeg }, { f, v -> f.copy(curvatureSideDeg = v) }, ::deg),
    )

    private val TV: List<VisualLinkTarget> = listOf(
        tv("strength", R.string.music_link_tv_strength, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.strength }, { t, v -> t.copy(strength = v) }),
        tv("brightness", R.string.music_link_tv_brightness, GlyphTvTuning.MIN_BRIGHTNESS, GlyphTvTuning.MAX_BRIGHTNESS,
            VisualLinkTarget.Need.TV, { it.brightness }, { t, v -> t.copy(brightness = v) }),
        tv("scanlines", R.string.music_link_tv_scanlines, 0f, 1f, VisualLinkTarget.Need.TV_CRT,
            { it.scanlines }, { t, v -> t.copy(scanlines = v) }),
        tv("mask", R.string.music_link_tv_mask, 0f, 1f, VisualLinkTarget.Need.TV_CRT,
            { it.mask }, { t, v -> t.copy(mask = v) }),
        tv("glow", R.string.music_link_tv_glow, 0f, 1f, VisualLinkTarget.Need.TV_CRT,
            { it.glow }, { t, v -> t.copy(glow = v) }),
        tv("sharpness", R.string.music_link_tv_sharpness, 0f, 1f, VisualLinkTarget.Need.TV_CRT,
            { it.sharpness }, { t, v -> t.copy(sharpness = v) }),
        tv("chromatic", R.string.music_link_tv_chromatic, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.chromatic }, { t, v -> t.copy(chromatic = v) }),
        // The tube's shape. Touches are bent through it too: each gesture
        // keeps the shape it started on (MainActivity.dispatchTouchEvent), so
        // a tap lands where it was seen and a held finger does not drift.
        tv("curveTopDeg", R.string.music_link_tv_curve_top, 0f, GlyphTvTuning.MAX_CURVE_DEG, VisualLinkTarget.Need.TV,
            { it.curveTopDeg }, { t, v -> t.copy(curveTopDeg = v) }, ::deg),
        tv("curveSideDeg", R.string.music_link_tv_curve_side, 0f, GlyphTvTuning.MAX_CURVE_DEG, VisualLinkTarget.Need.TV,
            { it.curveSideDeg }, { t, v -> t.copy(curveSideDeg = v) }, ::deg),
        tv("tiltSideDeg", R.string.music_link_tv_tilt_side, -GlyphTvTuning.MAX_TILT_DEG, GlyphTvTuning.MAX_TILT_DEG,
            VisualLinkTarget.Need.TV, { it.tiltSideDeg }, { t, v -> t.copy(tiltSideDeg = v) }, ::deg),
        tv("tiltTopDeg", R.string.music_link_tv_tilt_top, -GlyphTvTuning.MAX_TILT_DEG, GlyphTvTuning.MAX_TILT_DEG,
            VisualLinkTarget.Need.TV, { it.tiltTopDeg }, { t, v -> t.copy(tiltTopDeg = v) }, ::deg),
        tv("bezel", R.string.music_link_tv_bezel, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.bezel }, { t, v -> t.copy(bezel = v) }),
        tv("corners", R.string.music_link_tv_corners, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.corners }, { t, v -> t.copy(corners = v) }),
        tv("vignette", R.string.music_link_tv_vignette, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.vignette }, { t, v -> t.copy(vignette = v) }),
        tv("glare", R.string.music_link_tv_glare, 0f, 1f, VisualLinkTarget.Need.TV,
            { it.glare }, { t, v -> t.copy(glare = v) }),
        tv("royaleMaskStrength", R.string.music_link_tv_mask_strength, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleMaskStrength }, { t, v -> t.copy(royaleMaskStrength = v) }),
        tv("royaleBeamWidth", R.string.music_link_tv_beam_width, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleBeamWidth }, { t, v -> t.copy(royaleBeamWidth = v) }),
        tv("royaleBeamShape", R.string.music_link_tv_beam_shape, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleBeamShape }, { t, v -> t.copy(royaleBeamShape = v) }),
        tv("royaleHalation", R.string.music_link_tv_halation, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleHalation }, { t, v -> t.copy(royaleHalation = v) }),
        tv("royaleDiffusion", R.string.music_link_tv_diffusion, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleDiffusion }, { t, v -> t.copy(royaleDiffusion = v) }),
        tv("royaleBloom", R.string.music_link_tv_bloom, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleBloom }, { t, v -> t.copy(royaleBloom = v) }),
        tv("royaleBorder", R.string.music_link_tv_edge, 0f, 1f, VisualLinkTarget.Need.TV_ROYALE,
            { it.royaleBorder }, { t, v -> t.copy(royaleBorder = v) }),
    )

    // Stored as whole percents, so a link moves them a percent at a time.
    private val VISUALIZER: List<VisualLinkTarget> = listOf(
        VisualLinkTarget(
            "visualizer.opacity", R.string.fx_visualizer_opacity, VisualLinkTarget.Group.AMBIENT,
            0f, 100f, VisualLinkTarget.Need.AMBIENT,
            { it.ambient.opacityPercent.toFloat() },
            { look, v -> look.copy(ambient = look.ambient.copy(opacityPercent = v.roundToInt())) },
            { "${it.roundToInt()}%" },
        ),
        VisualLinkTarget(
            "visualizer.blackPoint", R.string.fx_black_transparency, VisualLinkTarget.Group.AMBIENT,
            0f, AmbientVisualizerSettings.MAX_BLACK_POINT.toFloat(), VisualLinkTarget.Need.AMBIENT,
            { it.ambient.blackPointPercent.toFloat() },
            { look, v -> look.copy(ambient = look.ambient.copy(blackPointPercent = v.roundToInt())) },
            { "${it.roundToInt()}%" },
        ),
    )

    val ALL: List<VisualLinkTarget> = LYRICS +
        GLASS_FIELDS.map { glass(LinkArea.PLAYER, it) } +
        GLASS_FIELDS.map { glass(LinkArea.PANELS, it) } +
        TV +
        VISUALIZER

    private val BY_KEY: Map<String, VisualLinkTarget> = ALL.associateBy { it.key }

    fun byKey(key: String): VisualLinkTarget? = BY_KEY[key]

    /** The key of [field] on [area]'s glass: "player.frost", "panels.frost". */
    fun glassKey(area: LinkArea, field: String): String = "${area.prefix}.$field"

    /**
     * [look] with every linked setting in [values] at its live value, each
     * kept inside its own range. Returns [look] itself when nothing applies.
     */
    fun apply(look: StudioLook, values: Map<String, Float>, only: LinkArea? = null): StudioLook {
        if (values.isEmpty()) return look
        var out = look
        for ((key, live) in values) {
            val t = BY_KEY[key] ?: continue
            if (only != null && t.area != only) continue
            if (!live.isFinite()) continue
            out = t.write(out, live.coerceIn(t.min, t.max))
        }
        return out
    }

    /**
     * [base] with every linked lyric setting replaced by its live value from
     * [values] (keyed like [VisualLinkTarget.key]). Returns [base] itself when
     * nothing applies, so the common no-link frame allocates nothing.
     */
    fun applyLyrics(base: LyricsFxSettings, values: Map<String, Float>): LyricsFxSettings {
        if (values.keys.none { it.startsWith("lyrics.") }) return base
        return apply(StudioLook(lyrics = base), values, LinkArea.LYRICS).lyrics
    }

    /** [base] as [area]'s glass with its linked settings live; [base] itself when none are. */
    fun applyGlass(base: PlayerGlassSettings, area: LinkArea, values: Map<String, Float>): PlayerGlassSettings {
        val prefix = area.prefix + "."
        if (values.keys.none { it.startsWith(prefix) }) return base
        val look = if (area == LinkArea.PLAYER) StudioLook(player = base) else StudioLook(panels = base)
        val out = apply(look, values, area)
        return if (area == LinkArea.PLAYER) out.player else out.panels
    }

    /** [base] with the CRT effect's linked settings live; [base] itself when none are. */
    fun applyTv(base: GlyphTvTuning, values: Map<String, Float>): GlyphTvTuning {
        if (values.keys.none { it.startsWith("tv.") }) return base
        return apply(StudioLook(tv = base), values, LinkArea.TV).tv
    }

    /** [base] with the ambient visualizer's linked settings live; [base] itself when none are. */
    fun applyAmbient(base: AmbientVisualizerSettings, values: Map<String, Float>): AmbientVisualizerSettings {
        if (values.keys.none { it.startsWith("visualizer.") }) return base
        return apply(StudioLook(ambient = base), values, LinkArea.VISUALIZER).ambient
    }

    /**
     * The lyric settings drawn behind the album cover rather than the lyrics:
     * the player runs these in cover-art view, where the rest of `lyrics.` rest.
     */
    val COVER_KEYS: Set<String> = setOf("lyrics.artGlowRadiusDp", "lyrics.artGlowBrightness")

    /** Whether any of [keys] keeps the god rays' clock running (their dust or sway). */
    fun movesRayClock(keys: Set<String>): Boolean =
        "lyrics.godRayShimmer" in keys || "lyrics.godRaySway" in keys
}
