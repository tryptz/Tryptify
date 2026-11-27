package tf.monochrome.android.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The cover's third view: the music as a sheet of water (a spectrogram drawn
 * as a lit, rippled surface). Time runs sideways, the newest at the right edge; pitch
 * runs into the distance, bass at the front. Stored as one JSON blob and
 * synced like [SpectrumWaterfallSettings], clamped on the way in and out.
 *
 * [quality] and [preview] are the listener's own: a theme neither sets nor
 * shares them ([withPersonalFrom]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Spectrogram3DSettings(
    /** Speed: how many seconds of music the front edge spans, right to left. */
    val secondsAcross: Float = 4f,
    /** How high the camera looks down: low is a near-flat horizon, high looks down on it. */
    val angleDeg: Float = 18f,
    /** How tall the peaks stand, 1 as shipped. */
    val peakHeight: Float = 1f,
    /** How shiny the surface is: 0 matte, 1 wet. */
    val gloss: Float = 0.6f,
    /** How much light glows through the ridges, in the album's second colour. */
    val subsurface: Float = 0.7f,
    /** 0 sharp, detailed ridges; 1 soft and rounded (a blur across pitch). */
    val smoothness: Float = 0.3f,
    /** How long the surface takes to rise to a new sound, in ms. 0 is at once. */
    val attackMs: Float = 12f,
    /** How long a sound stands as it dies away, in ms. 0 drops at once. */
    val releaseMs: Float = 160f,
    /**
     * The FFT's size, in samples at 44.1 and 48 kHz ([FFT_SIZES]): short
     * answers at once with fat notes, long is fine in pitch and late.
     */
    val fftSize: Int = 4096,
    /** How much each FFT window overlaps the one before, in percent: more is more spectra a second. */
    val overlapPct: Float = 96f,
    /** dB from the floor to a full peak: narrow stands everything up, wide keeps the quiet low. */
    val rangeDb: Float = 23f,
    /** The water's own small waves and the glints on them, 0 a still pool. */
    val ripples: Float = 0.6f,
    /** How dark the shadows the peaks cast are, 0 none. */
    val shadows: Float = 0.85f,
    /** How much the words being sung light the surface, in the Lyrics view. 0 leaves it to the sun. */
    val lyricLight: Float = 0.8f,
    /** How quickly the back fades into the cover. */
    val haze: Float = 0.55f,
    /**
     * Motion blur: how long the picture is exposed as it scrolls, 0 none and
     * 1 a twenty-fourth of a second, so the faster the speed, the longer the
     * streaks.
     */
    val motionBlur: Float = 0.5f,
    /**
     * Played behind the whole player, along the bottom of the screen from
     * edge to edge, under the title and the controls, as it lies under
     * expanded lyrics. Off, it is on the cover.
     */
    val background: Boolean = false,
    /** The lowest pitch shown, at the front, in Hz. */
    val minHz: Float = 30f,
    /** The highest pitch shown, at the back, in Hz. */
    val maxHz: Float = 5_000f,
    /** Mesh detail, [MIN_QUALITY]..[MAX_QUALITY]. Personal: older phones want fewer points. */
    val quality: Int = 2,
    /** The Settings preview is shown. Personal. */
    val preview: Boolean = true,
    /**
     * Which renderer the look was set for ([LOOK]). Always written, so a
     * saved look without it is known to be from before ([savedForAnOlderLook]).
     */
    @EncodeDefault val look: Int = LOOK,
) {
    /** Every value in its range; a value that is not a number takes the default. */
    fun clamped(): Spectrogram3DSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, def: Float) = if (isFinite()) coerceIn(min, max) else def
        val low = minHz.c(MIN_HZ, MAX_LOW_HZ, d.minHz)
        // At least two octaves between the ends, so there is a landscape to draw.
        val high = maxHz.c(MIN_HIGH_HZ, MAX_HZ, d.maxHz).coerceAtLeast(low * MIN_RANGE_RATIO).coerceAtMost(MAX_HZ)
        return copy(
            secondsAcross = secondsAcross.c(MIN_SECONDS, MAX_SECONDS, d.secondsAcross),
            angleDeg = angleDeg.c(MIN_ANGLE_DEG, MAX_ANGLE_DEG, d.angleDeg),
            peakHeight = peakHeight.c(MIN_PEAK, MAX_PEAK, d.peakHeight),
            gloss = gloss.c(0f, 1f, d.gloss),
            subsurface = subsurface.c(0f, 1f, d.subsurface),
            smoothness = smoothness.c(0f, 1f, d.smoothness),
            attackMs = attackMs.c(0f, MAX_ATTACK_MS, d.attackMs),
            releaseMs = releaseMs.c(0f, MAX_RELEASE_MS, d.releaseMs),
            fftSize = fftSizeNear(fftSize),
            overlapPct = overlapPct.c(MIN_OVERLAP_PCT, MAX_OVERLAP_PCT, d.overlapPct),
            rangeDb = rangeDb.c(MIN_RANGE_DB, MAX_RANGE_DB, d.rangeDb),
            ripples = ripples.c(0f, 1f, d.ripples),
            shadows = shadows.c(0f, 1f, d.shadows),
            lyricLight = lyricLight.c(0f, 1f, d.lyricLight),
            haze = haze.c(0f, 1f, d.haze),
            motionBlur = motionBlur.c(0f, 1f, d.motionBlur),
            minHz = low,
            maxHz = high,
            quality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
            look = LOOK,
        )
    }

    /** This look with [other]'s personal settings: what a theme applied over [other] gives. */
    fun withPersonalFrom(other: Spectrogram3DSettings): Spectrogram3DSettings =
        copy(quality = other.quality, preview = other.preview)

    companion object {
        val DEFAULT = Spectrogram3DSettings()

        /**
         * The renderer the sliders are tuned for: 2 is the water lit per
         * pixel, with shadows. Values saved for the first one (each mesh
         * point lit) do not carry over: there Smoothness near 100% and Gloss
         * near 0 hid its blocks, and here they flatten the water and put its
         * light out. That is what the owner's phone showed against the demo
         * on 2026-11-26 ("it looks nothing like the demo").
         */
        const val LOOK = 2

        /** Whether [savedJson] was written before the look's version was: by the first renderer's builds. */
        fun savedForAnOlderLook(savedJson: String): Boolean = !savedJson.contains("\"look\"")

        /**
         * What a saved value means now: one from an older look gives the
         * look as shipped, with the listener's own quality and preview
         * switch kept.
         */
        fun fromSaved(savedJson: String, decoded: Spectrogram3DSettings): Spectrogram3DSettings =
            if (savedForAnOlderLook(savedJson)) DEFAULT.withPersonalFrom(decoded) else decoded

        const val MIN_SECONDS = 0.5f
        const val MAX_SECONDS = 20f
        const val MIN_ANGLE_DEG = 5f
        const val MAX_ANGLE_DEG = 60f
        const val MIN_PEAK = 0.25f
        const val MAX_PEAK = 2f
        const val MIN_HZ = 20f
        const val MAX_LOW_HZ = 2_000f
        const val MIN_HIGH_HZ = 500f
        const val MAX_HZ = 20_000f
        const val MIN_RANGE_RATIO = 4f
        /** The FFT sizes on offer (the analyzer's own list, repeated here for the model: [Spectrogram3DSceneTest] holds them equal). */
        val FFT_SIZES = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384)
        const val MIN_OVERLAP_PCT = 50f
        const val MAX_OVERLAP_PCT = 99f
        const val MIN_RANGE_DB = 12f
        const val MAX_RANGE_DB = 60f

        /** [size] as one of [FFT_SIZES]: the nearest, by the octave. */
        fun fftSizeNear(size: Int): Int {
            var best = FFT_SIZES[0]
            for (candidate in FFT_SIZES) {
                if (kotlin.math.abs(kotlin.math.ln(size.coerceAtLeast(1).toFloat() / candidate)) <
                    kotlin.math.abs(kotlin.math.ln(size.coerceAtLeast(1).toFloat() / best))
                ) {
                    best = candidate
                }
            }
            return best
        }

        const val MAX_ATTACK_MS = 200f
        const val MAX_RELEASE_MS = 1500f
        const val MIN_QUALITY = 1
        const val MAX_QUALITY = 4
    }
}
