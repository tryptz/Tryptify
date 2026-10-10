package tf.monochrome.android.ui.player

import android.util.Log
import tf.monochrome.android.domain.model.LyricsFxSettings

/**
 * Structured debug logging for the lyrics subsystem. Every line lands in the
 * in-app Debug Log (Settings › Debug Log) because [tf.monochrome.android.debug.DebugLogCollector]
 * pipes our own logcat back into the buffer — so a plain [Log] call under the
 * [TAG] shows up there with no extra wiring.
 *
 * Call sites are lifecycle/state transitions only (settings change, shader
 * compile, font load, lyrics load, active-line change, beat engine acquire/
 * release). Nothing here is called from a draw/graphicsLayer lambda — the
 * lyric renderer runs its shaders and clocks every frame, and logging at that
 * rate would flood the ring buffer.
 */
internal object LyricsDebug {
    const val TAG = "LyricsFx"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    /** One line naming every effect currently running on the lyrics and its key params. */
    fun summary(fx: LyricsFxSettings): String = buildString {
        append("config: ")
        append("font=").append(
            if (fx.customFont && fx.customFontPath.isNotBlank()) fx.customFontPath.substringAfterLast('/')
            else "theme",
        )
        append(" btDelay=").append(fx.bluetoothDelayMs.toInt()).append("ms")

        append(" | glass=").append(if (fx.liquidGlass) "ON" else "off")
        if (fx.liquidGlass) {
            append("[opacity=").append((fx.glassBodyOpacity * 100).toInt()).append('%')
            append(" refr=").append(fx.glassRefraction)
            append(" rim=").append(fx.glassRimBrightness)
            append(" disp=").append(fx.glassDispersion)
            append(" round=").append(fx.glassRoundness)
            append(" depth=").append(fx.glassDepth)
            append(" motion=").append(fx.glassSurfaceMotion)
            append(" refl=").append(fx.glassReflection)
            append(" gloss=").append(fx.glassGloss)
            append(" tilt=").append(fx.glassTiltReactivity)
            append(" light=").append(fx.glassLightAngleDeg.toInt()).append('°')
            append(" edge=").append(fx.glassEdgeWidth)
            append(" frost=").append(fx.glassFrost)
            append(" taps=").append(1 + 4 * fx.glassSampleRings).append(']')
        }

        append(" | fxaa=").append(if (fx.fxaa) "ON[${(fx.fxaaStrength * 100).toInt()}%]" else "off")

        append(" | wave=").append(
            if (fx.rotationDegrees > 0.05f) "ON[tilt=${fx.rotationDegrees} speed=${fx.waveSpeed}]" else "off",
        )

        append(" | beat=").append(
            if (fx.bassReact > 0.01f) "ON[react=${(fx.bassReact * 100).toInt()}% pump=${fx.pumpAmount}]" else "off",
        )

        append(" | glow=").append(
            if (fx.bassReact > 0.01f && fx.glowBrightness > 0.001f) "ON[radius=${fx.glowRadiusDp.toInt()}dp]" else "off",
        )

        append(" | rays=").append(
            if (fx.godRays) {
                val source = if (fx.godRaySource == LyricsFxSettings.GOD_RAYS_BACKLIGHT) "backlight" else "letters"
                "ON[$source ${if (fx.godRaysOnTop) "top" else "under"} " +
                    "az=${fx.godRayAzimuthDeg.toInt()}° el=${fx.godRayElevationDeg.toInt()}° " +
                    "exp=${fx.godRayExposure} density=${fx.godRayDensity} decay=${fx.godRayDecay} " +
                    "spin=${fx.godRaySpinDps} sway=${fx.godRaySway} tilt=${fx.godRayTilt} q=${fx.godRayQuality}]"
            } else {
                "off"
            },
        )
    }
}

/**
 * What the god rays did since the last report, for the Debug Log.
 *
 * The draw blocks only count here, a field write per frame, and
 * [rememberLyricRayLight] reports on a timer, so nothing logs from a draw
 * lambda. Added when the player showed no rays at all while the Studio's
 * preview, on the same settings, did, and nothing in the log said why.
 */
internal object RaysProbe {
    /** How the backdrop's draw went. */
    enum class Backdrop { NO_ORIGIN, LETTERS_RELEASED, NO_LIGHT_FRAME, NO_EFFECT, SHADOW_ONLY, RAYS }

    /** How a lyric surface's own rays layer went (the light on the letters, or the legacy rays). */
    enum class Surface { NO_ORIGIN, NO_LIGHT_FRAME, LIT }

    private val backdrop = IntArray(Backdrop.entries.size)
    private val surface = IntArray(Surface.entries.size)

    /** Why the backdrop composed nothing, or "composed". Set in composition. */
    var backdropState = "never composed"

    /** How many lights were built since the last report: a high number means one every recomposition. */
    var lightsBuilt = 0

    /** How many times the lyric view recorded its letters for the backdrop since the last report. */
    var copiesRecorded = 0

    fun hit(outcome: Backdrop) { backdrop[outcome.ordinal]++ }
    fun hit(outcome: Surface) { surface[outcome.ordinal]++ }

    /** Which outcomes happened, without their counts: what decides whether a report is new. */
    fun kinds(): String = "backdrop[$backdropState: ${names(backdrop, Backdrop.entries)}] " +
        "surface[${names(surface, Surface.entries)}]"

    /** The counts since the last call, and zeroes them. */
    fun drain(): String {
        val line = "backdrop ${counts(backdrop, Backdrop.entries)} | surface ${counts(surface, Surface.entries)} | " +
            "copies recorded $copiesRecorded | lights built $lightsBuilt"
        backdrop.fill(0)
        surface.fill(0)
        lightsBuilt = 0
        copiesRecorded = 0
        return line
    }

    private fun names(n: IntArray, e: List<Enum<*>>) =
        e.filter { n[it.ordinal] > 0 }.joinToString(",") { it.name }.ifEmpty { "none" }

    private fun counts(n: IntArray, e: List<Enum<*>>) =
        e.filter { n[it.ordinal] > 0 }.joinToString(" ") { "${it.name}x${n[it.ordinal]}" }.ifEmpty { "no draws" }
}
