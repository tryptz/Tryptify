package tf.monochrome.android.audio.eq

import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * The spectrum waterfall's native half (cpp/dsp/scope/spectrum_waterfall.h):
 * keeps the history of lines and projects them in perspective. One instance per
 * overlay, owned by the composable that draws it; UI thread only.
 */
object WaterfallNative {
    init { DspNativeLoader.ensureLoaded() }

    /** History lines plus the live one: SpectrumWaterfall::kRows + 1. */
    const val MAX_LINES = 49

    /** Floats per line: (SpectrumWaterfall::kPoints − 1) × 4. */
    const val FLOATS_PER_LINE = (128 - 1) * 4

    /** Per-line metadata: alpha, stroke scale, baseline y. */
    const val META_PER_LINE = 3

    @JvmStatic external fun nativeCreate(): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    /**
     * Writes the visible lines back to front into [segs] (FLOATS_PER_LINE each)
     * and [meta] (META_PER_LINE each); returns how many.
     */
    @JvmStatic external fun nativeRender(
        handle: Long, bins: FloatArray, nowSec: Double,
        width: Float, height: Float, depthSeconds: Float, fadeStart: Float, angleDeg: Float,
        floorDb: Float, headroomDb: Float, segs: FloatArray, meta: FloatArray,
    ): Int

    /** Baselines of the front line, the fade's start and the last line, into [out][0..2]. */
    @JvmStatic external fun nativeGuides(
        width: Float, height: Float, fadeStart: Float, angleDeg: Float, out: FloatArray,
    )
}
