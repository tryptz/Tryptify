package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * How the spectrum over the artwork is drawn.
 *
 * The first four are the waterfall. [singleLine] styles draw only the live
 * spectrum — no history, so depth, fade and angle do not apply to them.
 */
@Serializable
enum class WaterfallStyle(val label: String, val description: String, val singleLine: Boolean = false) {
    LINES("Lines", "See-through lines in the album colour, lightening at the peaks"),
    RIDGELINE("Ridgeline", "Each line hides what is behind it, like a mountain range"),
    HEAT("Heat", "Coloured by height: green at the floor, yellow-white at the peaks"),
    NEON("Neon", "Lines add up where they cross, so dense areas glow"),
    GLASS(
        "Glass",
        "One line: the live spectrum as a pool of liquid glass that morphs with the music and refracts the cover",
        singleLine = true,
    ),
    LEGACY(
        "Legacy",
        "One line: the original spectrum, a flowing envelope filled down to the baseline",
        singleLine = true,
    ),
}

/**
 * The spectrum waterfall over the artwork: how far back the lines run before
 * they are gone, where along that they start to fade, and how steeply they rise
 * toward the back. Stored as one JSON blob and synced with the rest of the
 * settings, clamped on the way in and out like [WaveCandySettings].
 *
 * The ranges are cpp/dsp/scope/spectrum_waterfall.h's, which clamps them again
 * on its side; `SpectrumWaterfallSettingsTest` holds the two together.
 */
@Serializable
data class SpectrumWaterfallSettings(
    /** Seconds a line takes to travel from the front to fully faded. */
    val depthSeconds: Float = 2.5f,
    /** Fraction of that trip a line keeps its full strength. */
    val fadeStart: Float = 0.3f,
    /** How steeply the lines rise toward the back, in degrees. */
    val angleDeg: Float = 45f,
    /** How the lines are drawn. */
    val style: WaterfallStyle = WaterfallStyle.LINES,
    /** Stroke of the front line in dp; lines further back are thinner in proportion. */
    val lineWidthDp: Float = 1.6f,
    /**
     * Frames a second the waterfall is drawn at; [FPS_DISPLAY] is every refresh
     * the display gives it. A cap skips the draw, which is the expensive part.
     */
    val targetFps: Int = FPS_DISPLAY,
    /**
     * Whether a cap snaps to an even step of the display's refresh (60 on a
     * 120 Hz panel is every second refresh, evenly spaced) or is held by the
     * clock (exactly the number asked for, at the cost of uneven spacing).
     *
     * Not a swap-interval switch like projectM's: the waterfall is drawn by
     * the UI renderer, which never presents faster than the display refreshes.
     */
    val vsync: Boolean = true,
) {
    fun clamped() = copy(
        depthSeconds = depthSeconds.coerceIn(MIN_DEPTH_SECONDS, MAX_DEPTH_SECONDS),
        fadeStart = fadeStart.coerceIn(0f, MAX_FADE_START),
        angleDeg = angleDeg.coerceIn(MIN_ANGLE_DEG, MAX_ANGLE_DEG),
        lineWidthDp = lineWidthDp.coerceIn(MIN_LINE_WIDTH_DP, MAX_LINE_WIDTH_DP),
        targetFps = if (targetFps <= FPS_DISPLAY) FPS_DISPLAY else targetFps.coerceIn(MIN_FPS, MAX_FPS),
    )

    /** Seconds a line keeps full strength before it starts to fade. */
    val solidSeconds: Float get() = depthSeconds * fadeStart

    companion object {
        const val MIN_DEPTH_SECONDS = 0.5f
        const val MAX_DEPTH_SECONDS = 8f
        const val MAX_FADE_START = 0.95f
        const val MIN_ANGLE_DEG = 5f
        const val MAX_ANGLE_DEG = 75f
        const val MIN_LINE_WIDTH_DP = 0.5f
        const val MAX_LINE_WIDTH_DP = 3f

        /** No cap: every refresh the display gives. */
        const val FPS_DISPLAY = 0
        const val MIN_FPS = 10
        const val MAX_FPS = 240

        /** The caps the Studio offers, after Max. */
        val FPS_CHOICES = listOf(15, 24, 30, 45, 60, 90, 120)

        /** History lines; cpp's SpectrumWaterfall::kRows. */
        const val LINES = 48

        val DEFAULT = SpectrumWaterfallSettings()
    }
}
