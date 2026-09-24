package tf.monochrome.android.ui.mixer.fxchain

import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.ui.mixer.fxchain.FxVisualMath as M
import kotlin.math.ln
import kotlin.math.pow

/**
 * The draggable points on an effect's graph: grab the knee of a compressor,
 * the band of an EQ, the tail of a reverb, and move it.
 *
 * Positions are fractions of the panel (x 0 = left, y 0 = top) and are
 * computed with the same maths [FxVisual] draws with, so every handle sits on
 * the feature it controls. A drag is relative: the finger's movement, as a
 * fraction of the panel, is added to the parameter's own position on its
 * [FxAxis] and mapped back. Where an axis is the one the picture is drawn on
 * (frequency, dB, threshold) the handle stays under the finger exactly; where
 * it is not (Q, ratio) the handle moves with the finger at a sensible rate.
 *
 * Pure Kotlin, so the round trips are unit tested rather than trusted.
 */
internal class FxAxis(
    val param: Int,
    /** The parameter value as a panel fraction, given all the parameters. */
    val toFrac: (value: Float, p: (Int) -> Float) -> Float,
    /** Back from a panel fraction to a value (clamped to the range by the caller). */
    val fromFrac: (frac: Float, p: (Int) -> Float) -> Float,
)

internal class FxHandle(
    /** Shown beside the handle while it is held. */
    val label: String,
    val x: FxAxis?,
    val y: FxAxis?,
    /** Where the handle is drawn, as panel fractions. */
    val position: (p: (Int) -> Float) -> Pair<Float, Float>,
) {
    /** Every parameter this handle moves, for resetting it with a double tap. */
    val params: List<Int> get() = listOfNotNull(x?.param, y?.param).distinct()
}

internal object FxHandles {

    // ── Axis builders ──────────────────────────────────────────────────────

    /** A frequency on the log axis every response graph is drawn on. */
    private fun freqX(param: Int) = FxAxis(
        param,
        toFrac = { v, _ -> M.freqToT(v) },
        fromFrac = { f, _ -> M.freqAt(f.coerceIn(0f, 1f)) },
    )

    /** A gain on a response graph's dB axis (see FxVisual.drawResponse). */
    private fun responseDbY(param: Int, dbRange: Float = 30f) = FxAxis(
        param,
        toFrac = { v, _ -> 0.5f - (v / dbRange) * 0.46f },
        fromFrac = { f, _ -> (0.5f - f) / 0.46f * dbRange },
    )

    /** Linear [lo]..[hi] over the panel's height, [lo] at the bottom. */
    private fun linearUp(param: Int, lo: Float, hi: Float) = FxAxis(
        param,
        toFrac = { v, _ -> 1f - (v - lo) / (hi - lo) },
        fromFrac = { f, _ -> lo + (1f - f) * (hi - lo) },
    )

    /** Logarithmic [lo]..[hi] over the panel's height, [lo] at the bottom. */
    private fun logUp(param: Int, lo: Float, hi: Float) = FxAxis(
        param,
        toFrac = { v, _ -> 1f - ln(v.coerceAtLeast(lo) / lo) / ln(hi / lo) },
        fromFrac = { f, _ -> lo * (hi / lo).pow(1f - f) },
    )

    /** A threshold on a transfer graph's input axis, −60..0 dB. */
    private fun transferX(param: Int) = FxAxis(
        param,
        toFrac = { v, _ -> (v + 60f) / 60f },
        fromFrac = { f, _ -> f * 60f - 60f },
    )

    private fun transferY(outDb: Float) = 1f - (outDb.coerceIn(-60f, 0f) + 60f) / 60f

    private fun responseY(db: Float, dbRange: Float = 30f) =
        0.5f - (db.coerceIn(-dbRange, dbRange) / dbRange) * 0.46f

    // ── Per effect ─────────────────────────────────────────────────────────

    fun forType(type: SnapinType): List<FxHandle> = when (type) {
        SnapinType.FILTER -> listOf(
            FxHandle("Cutoff · Q", freqX(1), logUp(2, 0.1f, 20f)) { p ->
                M.freqToT(p(1)) to responseY(M.filterDb(p(0).toInt(), p(1), p(1), p(2), p(3), p(4).toInt()))
            },
        )
        SnapinType.EQ_3BAND -> listOf(
            FxHandle("Low", freqX(0), responseDbY(1)) { p -> M.freqToT(p(0)) to responseY(p(1)) },
            FxHandle("Mid", freqX(3), responseDbY(4)) { p -> M.freqToT(p(3)) to responseY(p(4)) },
            FxHandle("High", freqX(6), responseDbY(7)) { p -> M.freqToT(p(6)) to responseY(p(7)) },
        )
        SnapinType.EQ_10BAND -> (0 until 10).map { band ->
            val base = 1 + band * 5
            FxHandle("${band + 1}", freqX(base), responseDbY(base + 1)) { p ->
                M.freqToT(p(base)) to responseY(p(base + 1))
            }
        }
        SnapinType.LADDER_FILTER -> listOf(
            FxHandle("Cutoff · Reso", freqX(0), linearUp(1, 0f, 100f)) { p ->
                M.freqToT(p(0)) to responseY(M.ladderDb(p(0), p(0), p(1)))
            },
        )
        SnapinType.NONLINEAR_FILTER -> listOf(
            FxHandle("Cutoff · Q", freqX(1), logUp(2, 0.1f, 20f)) { p ->
                val shape = M.NONLINEAR_TYPE_MAP[p(0).toInt().coerceIn(0, 3)]
                M.freqToT(p(1)) to responseY(M.biquadDb(shape, p(1), p(1), p(2)))
            },
        )
        SnapinType.COMPRESSOR -> listOf(
            FxHandle("Thresh · Ratio", transferX(3), logUp(2, 1f, 100f)) { p ->
                (p(3) + 60f) / 60f to transferY(M.compressorOutDb(p(3), p(3), p(2), p(4), p(5)))
            },
        )
        SnapinType.LIMITER -> listOf(
            FxHandle("Thresh", transferX(1), null) { p ->
                (p(1) + 60f) / 60f to transferY(M.limiterOutDb(p(1), p(0), p(1), p(4)))
            },
        )
        SnapinType.GATE -> listOf(
            FxHandle("Thresh · Range", transferX(3), linearUp(5, 0f, 80f)) { p ->
                ((p(3) + 60f) / 60f).coerceIn(0f, 1f) to transferY(p(3))
            },
        )
        SnapinType.COMPACTOR -> listOf(
            FxHandle("Thresh", transferX(3), null) { p ->
                (p(3) + 60f) / 60f to transferY(M.compactorOutDb(p(3), p(3), p(5)))
            },
        )
        SnapinType.REVERB -> listOf(
            // The gap before the tail: FxVisual.drawReverb puts the tail start
            // at 0.08 + pre-delay/500 × 0.18.
            FxHandle(
                "Pre-delay",
                FxAxis(0, toFrac = { v, _ -> 0.08f + v / 500f * 0.18f },
                    fromFrac = { f, _ -> (f - 0.08f) / 0.18f * 500f }),
                null,
            ) { p -> 0.08f + p(0) / 500f * 0.18f to 0.12f },
            // One time constant into the tail: sideways is decay, up/down damping.
            FxHandle(
                "Decay · Damp",
                FxAxis(1,
                    toFrac = { v, p -> reverbTailStart(p) + reverbTau(v, p(2)) },
                    fromFrac = { f, p ->
                        val span = 0.5f + (p(2) / 100f).coerceIn(0f, 1f) * 0.8f
                        (f - reverbTailStart(p) - 0.06f) / span * 30f
                    }),
                FxAxis(3, toFrac = { v, _ -> 0.25f + v / 100f * 0.5f },
                    fromFrac = { f, _ -> (f - 0.25f) / 0.5f * 100f }),
            ) { p ->
                val tau = reverbTau(p(1), p(2))
                val damp = (p(3) / 100f).coerceIn(0f, 1f)
                val env = kotlin.math.exp(-1f) * kotlin.math.exp(-tau * damp * 2.2f) * 0.8f
                (reverbTailStart(p) + tau).coerceAtMost(0.97f) to 0.92f - env * 0.80f
            },
        )
        SnapinType.DELAY -> listOf(
            // The first echo: sideways is time, up is feedback.
            FxHandle(
                "Time · Feedback",
                FxAxis(0, toFrac = { v, _ -> 0.05f + (v / 2000f).coerceIn(0.02f, 1f) * 0.42f },
                    fromFrac = { f, _ -> (f - 0.05f) / 0.42f * 2000f }),
                FxAxis(1,
                    toFrac = { v, p -> delayEchoY(v, p) },
                    fromFrac = { f, p ->
                        if (p(2) > 0.5f) (0.5f - f) / 0.425f * 100f else (1f - f) / 0.85f * 100f
                    }),
            ) { p ->
                0.05f + (p(0) / 2000f).coerceIn(0.02f, 1f) * 0.42f to delayEchoY(p(1), p)
            },
        )
        SnapinType.STEREO -> listOf(
            // The ellipse's right edge is the width, its top the mid level.
            FxHandle(
                "Width",
                FxAxis(1,
                    // Uncapped, unlike the picture (which stops the ellipse at
                    // +6 dB): a capped axis would drag a higher value down to 6.
                    toFrac = { v, p -> 0.5f + p(2) * 0.22f + M.dbToLin(v) * 0.22f },
                    fromFrac = { f, p ->
                        M.linToDb(((f - 0.5f - p(2) * 0.22f) / 0.22f).coerceAtLeast(1e-4f))
                    }),
                null,
            ) { p -> 0.5f + p(2) * 0.22f + M.dbToLin(p(1)).coerceAtMost(2f) * 0.22f to 0.5f },
            FxHandle(
                "Mid",
                null,
                FxAxis(0,
                    toFrac = { v, _ -> 0.5f - M.dbToLin(v) * 0.38f },
                    fromFrac = { f, _ -> M.linToDb(((0.5f - f) / 0.38f).coerceAtLeast(1e-4f)) }),
            ) { p -> 0.5f + p(2) * 0.22f to 0.5f - M.dbToLin(p(0)).coerceAtMost(2f) * 0.38f },
            FxHandle(
                "Pan",
                FxAxis(2, toFrac = { v, _ -> 0.5f + v * 0.22f }, fromFrac = { f, _ -> (f - 0.5f) / 0.22f }),
                null,
            ) { p -> 0.5f + p(2) * 0.22f to 0.5f },
        )
        SnapinType.TRANSIENT_SHAPER -> listOf(
            // Drawn on the shaped envelope (the picture clips it, so the
            // handle can stop rising); dragged at a rate of its own.
            FxHandle("Attack", null, linearUp(0, -100f, 100f)) { p ->
                0.06f to 0.92f - transientShaped(0.06f, p) * 0.72f
            },
            FxHandle("Sustain", null, linearUp(2, -100f, 100f)) { p ->
                0.62f to 0.92f - transientShaped(0.62f, p) * 0.72f
            },
        )
        else -> emptyList()
    }

    private fun reverbTailStart(p: (Int) -> Float) = 0.08f + (p(0) / 500f) * 0.18f

    private fun reverbTau(decay: Float, size: Float): Float {
        val decayNorm = (decay / 30f).coerceIn(0.005f, 1f)
        return 0.06f + decayNorm * (0.5f + (size / 100f).coerceIn(0f, 1f) * 0.8f)
    }

    /** FxVisual.drawTransientShaper's shaped envelope at [t]. */
    private fun transientShaped(t: Float, p: (Int) -> Float): Float {
        val attack = p(0) / 100f
        val pump = p(1) / 100f
        val sustain = p(2) / 100f
        val base = if (t < 0.06f) t / 0.06f
            else 0.34f + 0.66f * kotlin.math.exp(-((t - 0.06f) / 0.94f) * 5f)
        val attackRegion = kotlin.math.exp(-((t - 0.06f).coerceAtLeast(0f)) * 18f)
        val pumpSwell = if (t > 0.12f) pump * 0.35f * (1f - kotlin.math.exp(-(t - 0.12f) * 6f)) else 0f
        return (base * (1f + attack * 0.9f * attackRegion) * (1f + sustain * 0.8f * (1f - attackRegion)) + pumpSwell)
            .coerceIn(0f, 1.15f)
    }

    private fun delayEchoY(feedback: Float, p: (Int) -> Float): Float {
        val fb = (feedback / 100f).coerceIn(0.03f, 0.97f)
        return if (p(2) > 0.5f) 0.5f - fb * 0.425f else 1f - fb * 0.85f
    }
}
