package tf.monochrome.android.audio.dsp

// Matches SnapinType enum in snapin_processor.h — ordinal is the native int value
enum class SnapinType(val displayName: String, val category: SnapinCategory) {
    GAIN("Gain", SnapinCategory.UTILITY),
    STEREO("Stereo", SnapinCategory.UTILITY),
    FILTER("Filter", SnapinCategory.EQ_FILTER),
    EQ_3BAND("3-Band EQ", SnapinCategory.EQ_FILTER),
    COMPRESSOR("Compressor", SnapinCategory.DYNAMICS),
    LIMITER("Limiter", SnapinCategory.DYNAMICS),
    GATE("Gate", SnapinCategory.DYNAMICS),
    DYNAMICS("Dynamics", SnapinCategory.DYNAMICS),
    COMPACTOR("Compactor", SnapinCategory.DYNAMICS),
    TRANSIENT_SHAPER("Transient Shaper", SnapinCategory.DYNAMICS),
    DISTORTION("Distortion", SnapinCategory.DISTORTION),
    SHAPER("Shaper", SnapinCategory.DISTORTION),
    CHORUS("Chorus", SnapinCategory.MODULATION),
    ENSEMBLE("Ensemble", SnapinCategory.MODULATION),
    FLANGER("Flanger", SnapinCategory.MODULATION),
    PHASER("Phaser", SnapinCategory.MODULATION),
    DELAY("Delay", SnapinCategory.SPACE),
    REVERB("Reverb", SnapinCategory.SPACE),
    BITCRUSH("Bitcrush", SnapinCategory.DISTORTION),
    COMB_FILTER("Comb Filter", SnapinCategory.EQ_FILTER),
    CHANNEL_MIXER("Channel Mixer", SnapinCategory.UTILITY),
    FORMANT_FILTER("Formant Filter", SnapinCategory.EQ_FILTER),
    FREQUENCY_SHIFTER("Frequency Shifter", SnapinCategory.MODULATION),
    HAAS("Haas", SnapinCategory.UTILITY),
    LADDER_FILTER("Ladder Filter", SnapinCategory.EQ_FILTER),
    NONLINEAR_FILTER("Nonlinear Filter", SnapinCategory.EQ_FILTER),
    PHASE_DISTORTION("Phase Distortion", SnapinCategory.DISTORTION),
    PITCH_SHIFTER("Pitch Shifter", SnapinCategory.MODULATION),
    RESONATOR("Resonator", SnapinCategory.EQ_FILTER),
    REVERSER("Reverser", SnapinCategory.SPACE),
    RING_MOD("Ring Mod", SnapinCategory.MODULATION),
    TAPE_STOP("Tape Stop", SnapinCategory.MODULATION),
    TRANCE_GATE("Trance Gate", SnapinCategory.DYNAMICS),
    EQ_10BAND("10-Band EQ", SnapinCategory.EQ_FILTER);

    val isAvailable: Boolean
        get() = true  // All 33 snapins implemented

    companion object {
        fun fromOrdinal(ordinal: Int): SnapinType? =
            entries.getOrNull(ordinal)

        fun availableTypes(): List<SnapinType> =
            entries.filter { it.isAvailable }
    }
}

enum class SnapinCategory(val displayName: String) {
    UTILITY("Utility"),
    EQ_FILTER("EQ & Filter"),
    DYNAMICS("Dynamics"),
    DISTORTION("Distortion"),
    MODULATION("Modulation"),
    SPACE("Space")
}
