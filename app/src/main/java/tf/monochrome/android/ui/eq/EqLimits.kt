package tf.monochrome.android.ui.eq

/**
 * Shared range constants for the two EQ surfaces.
 *
 * Both surfaces now share the same ±24 dB per-band range: the AutoEQ
 * *generator* stays conservative on its own (per-band safeBoost of 12/6/3 dB
 * by frequency), but user edits and imported profiles are no longer clamped
 * tighter than the Parametric surface — real-world APO exports carry >12 dB
 * bands. Callers are responsible for communicating headroom risk to the user.
 */
object EqLimits {
    const val AUTOEQ_MAX_BAND_DB = 24f
    const val PARAMETRIC_MAX_BAND_DB = 24f
    const val MIN_FREQ_HZ = 20f
    const val MAX_FREQ_HZ = 20000f

    // Total signal-path headroom for AutoEQ (band gain + preamp). Beyond this,
    // the biquad cascade drives the ReplayGain limiter into hard clipping at
    // the band's resonant peak. Preamp is clamped against (TOTAL - peakBand),
    // so this sits one preamp-slider's travel above the band cap.
    const val AUTOEQ_MAX_TOTAL_DB = 36f
}
