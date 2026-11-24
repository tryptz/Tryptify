package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls

/**
 * What the system-wide equalizer is told to play: the saved correction and
 * the tone shelves as a gain at each of the global effect's fixed bands, for
 * each ear, and whether its limiter is wanted. Kept apart from
 * [SystemAudioEqController], which only hands this to Android, so it can be
 * held to numbers ([SystemEqCurveTest]).
 */
internal object SystemEqCurve {

    /**
     * 31-band ⅓-octave grid (ISO R40 centres) across the audible range. Finer
     * than a 10 or 15 band graphic EQ, so the sampled curve tracks the
     * parametric correction's sharper peaks and shelves more closely. A
     * graphic EQ still cannot reproduce a parametric curve exactly (fixed
     * centres, and the effect's own crossovers), so the in-app path stays the
     * reference; this narrows the gap.
     */
    val BAND_FREQS = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
        20000f,
    )

    const val MIN_GAIN_DB = -24f
    const val MAX_GAIN_DB = 24f

    /**
     * How far above full scale the curve may reach before the limiter is put
     * in: a tenth of a dB is rounding, not a boost.
     */
    const val LIMITER_ABOVE_DB = 0.1f

    /** A preamp smaller than this, with no band shaping the sound, is none. */
    const val NO_PREAMP_DB = 0.005f

    /**
     * One ear's gains at [BAND_FREQS], dB, the preamp included.
     *
     * [left] and [right] are the same array when both ears play the same
     * curve ([perEar] false): the common case, and what a device whose effect
     * has one channel can play.
     */
    class Plan(val left: FloatArray, val right: FloatArray, val limiter: Boolean) {
        val perEar: Boolean get() = left !== right
    }

    /**
     * The plan for these settings, or null when nothing would change the
     * sound: no band that does anything and no preamp. The effect is then
     * taken off, and the audio passes untouched.
     *
     * [correctionR] is the right ear's own correction, used only in
     * 2-channel mode ([stereo]) and when one is saved; otherwise the left
     * one plays in both ears, as in the app's own player. The tone shelves
     * go to both. The preamp is one for both ears, sized by the louder
     * ([AutoPreamp.applied]), so the two stay level with each other.
     *
     * The limiter is wanted when the curve as it will play rises above full
     * scale anywhere: a manual preamp left at 0 over a boost, say. With the
     * automatic preamp it never does, and the limiter stays out of the path.
     */
    fun plan(
        correctionL: List<EqBand>,
        correctionR: List<EqBand>,
        stereo: Boolean,
        tone: ToneControls,
        manualPreampDb: Float,
        autoPreamp: Boolean,
    ): Plan? {
        val rightOwn = stereo && correctionR.isNotEmpty() && correctionR != correctionL
        val right = if (rightOwn) correctionR else correctionL
        val toneBands = tone.toBands()
        val bandsL = correctionL + toneBands
        val bandsR = right + toneBands
        val preamp = AutoPreamp.applied(
            correctionOn = true,
            automatic = autoPreamp,
            manualDb = manualPreampDb,
            correctionL = correctionL,
            correctionR = right,
            tone = tone,
        )
        val shapes = (bandsL + bandsR).any { it.enabled && it.gain != 0f }
        // A band that is on at 0 dB answers with a float's rounding, not
        // with nothing, and the automatic preamp made of it is as small:
        // that is no preamp, or the effect would stay in the path for it.
        if (!shapes && kotlin.math.abs(preamp) < NO_PREAMP_DB) return null
        val peak = preamp + maxOf(AutoPreamp.peakBoostDb(bandsL), AutoPreamp.peakBoostDb(bandsR))
        val left = gains(bandsL, preamp)
        return Plan(
            left = left,
            right = if (rightOwn) gains(bandsR, preamp) else left,
            limiter = peak > LIMITER_ABOVE_DB,
        )
    }

    /**
     * The same settings as real filters, for the Shizuku mode
     * ([tf.monochrome.android.audio.eq.shizuku.SystemPeqDsp]): each ear's
     * bands packed as it takes them, and the preamp. No sampling at fixed
     * bands, so it is the curve the app's own player runs.
     */
    class Parametric(val left: FloatArray, val right: FloatArray, val preampDb: Float) {
        override fun equals(other: Any?): Boolean =
            other is Parametric && preampDb == other.preampDb &&
                left.contentEquals(other.left) && right.contentEquals(other.right)

        override fun hashCode(): Int = 31 * (31 * left.contentHashCode() + right.contentHashCode()) + preampDb.hashCode()
    }

    /** [plan]'s rules for the Shizuku mode; null when nothing would change the sound. */
    fun parametric(
        correctionL: List<EqBand>,
        correctionR: List<EqBand>,
        stereo: Boolean,
        tone: ToneControls,
        manualPreampDb: Float,
        autoPreamp: Boolean,
    ): Parametric? {
        val rightOwn = stereo && correctionR.isNotEmpty() && correctionR != correctionL
        val right = if (rightOwn) correctionR else correctionL
        val toneBands = tone.toBands()
        val preamp = AutoPreamp.applied(
            correctionOn = true,
            automatic = autoPreamp,
            manualDb = manualPreampDb,
            correctionL = correctionL,
            correctionR = right,
            tone = tone,
        )
        val packedL = tf.monochrome.android.audio.eq.shizuku.SystemPeqDsp.packBands(correctionL + toneBands)
        val packedR = tf.monochrome.android.audio.eq.shizuku.SystemPeqDsp.packBands(right + toneBands)
        if (packedL.isEmpty() && packedR.isEmpty() && kotlin.math.abs(preamp) < NO_PREAMP_DB) return null
        return Parametric(packedL, packedR, if (kotlin.math.abs(preamp) < NO_PREAMP_DB) 0f else preamp)
    }

    /** The gain at each of [BAND_FREQS] for [bands] behind [preampDb]. */
    fun gains(bands: List<EqBand>, preampDb: Float): FloatArray =
        FloatArray(BAND_FREQS.size) { gainAt(BAND_FREQS[it], bands, preampDb) }

    /** The preamp and every band's response at [freqHz], dB, held to what the effect takes. */
    fun gainAt(freqHz: Float, bands: List<EqBand>, preampDb: Float): Float {
        var g = preampDb
        for (band in bands) g += AutoEqEngine.calculateBiquadResponse(freqHz, band)
        return g.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    /**
     * Whether the system-wide effect corrects this app's own music, so that
     * the app's own equalizer must stand down or the song is corrected twice.
     * It does when it is on, unless the music leaves through the exclusive
     * USB DAC: that path goes round Android's mixer, where the effect sits,
     * and with the app's equalizer standing down too the song had no
     * correction at all. Nor in the Shizuku mode, which leaves the app's own
     * playback out of what it takes: the app's equalizer is the exact one,
     * at the song's own rate and depth.
     */
    fun coversOwnPlayback(
        systemWide: Boolean,
        exclusiveDacStreaming: Boolean,
        shizukuActive: Boolean = false,
    ): Boolean = systemWide && !exclusiveDacStreaming && !shizukuActive
}
