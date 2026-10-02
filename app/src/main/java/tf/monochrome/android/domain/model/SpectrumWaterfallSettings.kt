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
 * How each bin of the spectrum is held over time — the analysis "type" of a
 * spectrum analyzer such as Voxengo SPAN, whose names these follow.
 */
@Serializable
enum class SpectrumAnalysisType {
    /**
     * Real-time average: each bin follows the signal, rising quickly and
     * falling back over the averaging time. The overlay's long-standing look.
     */
    RT_AVG,

    /** Real-time max: each bin jumps to a peak, holds it for the averaging time, then falls. */
    RT_MAX,

    /** The average since the track started: the song's long-term tonal balance. */
    AVG,

    /** The loudest each bin has been since the track started. */
    MAX,
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
    /** How each bin is held over time. */
    val analysis: SpectrumAnalysisType = SpectrumAnalysisType.RT_AVG,
    /**
     * The averaging time, in ms: how long a real-time average takes to fall
     * back, or how long a real-time max holds its peak. The default is the
     * overlay's release from before it was adjustable, so nothing moves for
     * anyone who never touches it.
     */
    val avgTimeMs: Float = DEFAULT_AVG_TIME_MS,
    /**
     * How much each FFT window overlaps the one before it, in percent. Higher
     * analyses more often for smoother motion; lower analyses less often, and
     * steps more. The analyzer never runs faster than the display, so above
     * the point where it already does, more overlap changes nothing.
     */
    val overlapPct: Float = DEFAULT_OVERLAP_PCT,
    /** The lines' colour as ARGB, or null to take the album's. */
    val colorArgb: Int? = null,
) {
    fun clamped() = copy(
        depthSeconds = depthSeconds.coerceIn(MIN_DEPTH_SECONDS, MAX_DEPTH_SECONDS),
        fadeStart = fadeStart.coerceIn(0f, MAX_FADE_START),
        angleDeg = angleDeg.coerceIn(MIN_ANGLE_DEG, MAX_ANGLE_DEG),
        lineWidthDp = lineWidthDp.coerceIn(MIN_LINE_WIDTH_DP, MAX_LINE_WIDTH_DP),
        targetFps = if (targetFps <= FPS_DISPLAY) FPS_DISPLAY else targetFps.coerceIn(MIN_FPS, MAX_FPS),
        avgTimeMs = avgTimeMs.coerceIn(MIN_AVG_TIME_MS, MAX_AVG_TIME_MS),
        overlapPct = overlapPct.coerceIn(MIN_OVERLAP_PCT, MAX_OVERLAP_PCT),
        // Lines are drawn over the cover: a see-through colour would just be a
        // weaker version of the same colour, so the alpha is always full.
        colorArgb = colorArgb?.let { it or 0xFF000000.toInt() },
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

        const val MIN_AVG_TIME_MS = 0f
        const val MAX_AVG_TIME_MS = 5000f
        /**
         * The overlay's old fixed release, 0.12 per 60 Hz frame, as a time
         * constant (about 139 ms): the default changes nothing for anyone.
         */
        const val DEFAULT_AVG_TIME_MS = 1000f / (0.12f * 60f)
        const val MIN_OVERLAP_PCT = 50f
        const val MAX_OVERLAP_PCT = 99f
        const val DEFAULT_OVERLAP_PCT = 96f

        /** The caps the Studio offers, after Max. */
        val FPS_CHOICES = listOf(15, 24, 30, 45, 60, 90, 120)

        /** History lines; cpp's SpectrumWaterfall::kRows. */
        const val LINES = 48

        val DEFAULT = SpectrumWaterfallSettings()
    }
}
