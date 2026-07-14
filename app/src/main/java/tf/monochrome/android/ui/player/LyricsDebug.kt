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
            append(" taps=").append(1 + 4 * fx.glassSampleRings).append(']')
        }

        append(" | fxaa=").append(if (fx.fxaa) "ON[${(fx.fxaaStrength * 100).toInt()}%]" else "off")

        append(" | wave=").append(
            if (fx.rotationDegrees > 0.05f) "ON[tilt=${fx.rotationDegrees} speed=${fx.waveSpeed}]" else "off",
        )

        append(" | beat=").append(
            if (fx.bassReact > 0.01f) "ON[react=${(fx.bassReact * 100).toInt()}% pump=${fx.pumpAmount}]" else "off",
        )

        val raysOn = fx.rayCount > 0 && fx.bassReact > 0.01f
        append(" | rays=").append(
            if (raysOn) {
                val dir = if (fx.rayFixedDirection) "fixed@${fx.rayAngleDeg.toInt()}°"
                else "burst/spread=${fx.raySpreadDeg.toInt()}°"
                "ON[n=${fx.rayCount} $dir bright=${fx.rayBrightness}]"
            } else "off",
        )
        append(" glow=").append(if (fx.bassReact > 0.01f && fx.glowBrightness > 0.001f) "ON" else "off")
    }
}
