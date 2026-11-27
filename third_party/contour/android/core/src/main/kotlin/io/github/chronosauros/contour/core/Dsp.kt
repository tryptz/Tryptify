package io.github.chronosauros.contour.core

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a DAC builds a LOW or HIGH SHELF from the band's Q (record `peq.shelfForm`). Both are the RBJ Audio EQ Cookbook shelf and coincide only at S = 1 <-> Q = 0.7071; at Q 2-3 the Q form peaks 3-4 dB higher.
 *  - [SLOPE]: the shelf slope S = Q, `alpha = sin(w0)/2 * sqrt((A + 1/A)*(1/S - 1) + 2)`. The DACs that play the biquad coefficients Contour computes (`WalkPlay.scaledCoefficients`, the Moondrop gen 1 bytes) play this form by construction.
 *  - [Q]: `alpha = sin(w0) / (2*Q)`, Q = register / `shelfQScale`. The DACs that build the shelf themselves from the registers play this form (measured on the KA15, the JA11, the K13 R2R and others).
 * Only what is drawn and what AUTO computes follows the form; band registers and coefficient bytes never do.
 */
enum class ShelfForm { SLOPE, Q }

/**
 * Display DSP: RBJ Audio EQ Cookbook biquads (Q for peaks and the four pass filters, the shelf in the [ShelfForm] the DAC plays: slope S = band Q, or the Q form)
 * evaluated at [FS] with the user's own values, i.e. the intended curve.
 *
 * Cheap enough for every drag frame: a [FreqGrid] precomputes cos(w) and cos(2w) once, so one band
 * costs a handful of multiplications per point and no trigonometry.
 */
object Dsp {
    const val FS: Double = 96_000.0
    const val FMIN: Double = 20.0
    const val FMAX: Double = 20_000.0
    const val DISPLAY_POINTS: Int = 512

    /** Log-spaced frequencies with their cos(w), cos(2w) at [FS]. */
    class FreqGrid(val freqs: DoubleArray) {
        val size: Int get() = freqs.size
        internal val cosW = DoubleArray(freqs.size) { cos(2.0 * Math.PI * freqs[it] / FS) }
        internal val cos2W = DoubleArray(freqs.size) { cos(4.0 * Math.PI * freqs[it] / FS) }
    }

    fun logFreqs(n: Int, fMin: Double = FMIN, fMax: Double = FMAX): DoubleArray {
        require(n >= 2)
        val span = ln(fMax / fMin)
        return DoubleArray(n) { fMin * exp(span * it / (n - 1)) }
    }

    /** The 20 Hz - 20 kHz grid used for drawing. */
    val DISPLAY_GRID: FreqGrid by lazy { FreqGrid(logFreqs(DISPLAY_POINTS)) }

    /** Normalised biquad: b0, b1, b2, a1, a2 with a0 = 1. */
    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** [form] is how the DAC builds a shelf (see [ShelfForm]); peaks and pass filters are the same in both forms. Required, so every call site says which DAC it speaks for. */
    fun biquad(band: Band, form: ShelfForm, fs: Double = FS): Biquad {
        val w0 = 2.0 * Math.PI * band.freqHz / fs
        val c = cos(w0)
        val s = sin(w0)
        val alpha = s / (2.0 * band.q) // peaks, pass filters and, in the Q form, shelves
        val a = 10.0.pow(band.gainDb / 40.0)
        val shelfAlpha = if (!band.type.isShelf) 0.0 else when (form) {
            ShelfForm.SLOPE -> {
                val radicand = (a + 1.0 / a) * (1.0 / band.q - 1.0) + 2.0
                require(radicand.isFinite() && radicand >= 0.0) { "Shelf gain/Q has no real response" }
                (s / 2.0) * sqrt(radicand)
            }
            // The Q form has a real response for every positive finite Q; a Q that is not (zero, negative, NaN) ends in the finite check below.
            ShelfForm.Q -> alpha
        }
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (band.type) {
            FilterType.PEAK -> {
                b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
            }
            FilterType.LOW_SHELF -> {
                val k = 2 * sqrt(a) * shelfAlpha
                b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k
            }
            FilterType.HIGH_SHELF -> {
                val k = 2 * sqrt(a) * shelfAlpha
                b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k
            }
            FilterType.LOW_PASS -> {
                b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
            FilterType.HIGH_PASS -> {
                b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
            // RBJ BPF, constant 0 dB peak gain: the response is exactly 0 dB at f0, whatever Q.
            FilterType.BAND_PASS -> {
                b0 = alpha; b1 = 0.0; b2 = -alpha
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
            // RBJ APF: magnitude 1 at every frequency, only the phase turns (around f0, sharper with Q).
            FilterType.ALL_PASS -> {
                b0 = 1 - alpha; b1 = -2 * c; b2 = 1 + alpha
                a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha
            }
        }
        val result = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        require(result.b0.isFinite() && result.b1.isFinite() && result.b2.isFinite() && result.a1.isFinite() && result.a2.isFinite()) {
            "Band has no finite response"
        }
        return result
    }

    /** Adds one band's response in dB to [out] (same size as [grid]). */
    fun addBandDb(band: Band, form: ShelfForm, grid: FreqGrid, out: DoubleArray) {
        val q = biquad(band, form)
        val nk = q.b0 * q.b0 + q.b1 * q.b1 + q.b2 * q.b2
        val n1 = 2 * (q.b0 * q.b1 + q.b1 * q.b2)
        val n2 = 2 * q.b0 * q.b2
        val dk = 1 + q.a1 * q.a1 + q.a2 * q.a2
        val d1 = 2 * (q.a1 + q.a1 * q.a2)
        val d2 = 2 * q.a2
        val cw = grid.cosW
        val c2w = grid.cos2W
        for (i in out.indices) {
            val num = nk + n1 * cw[i] + n2 * c2w[i]
            val den = dk + d1 * cw[i] + d2 * c2w[i]
            out[i] += 10.0 * log10(num / den)
        }
    }

    /** Total response in dB of the enabled bands over [grid], written into [out] (allocation-free). */
    fun responseDb(bands: List<Band>, form: ShelfForm, grid: FreqGrid, out: DoubleArray) {
        require(out.size == grid.size)
        out.fill(0.0)
        for (b in bands) if (b.enabled) addBandDb(b, form, grid, out)
        require(out.all { it.isFinite() }) { "Profile has no finite response" }
    }

    fun responseDb(bands: List<Band>, form: ShelfForm, grid: FreqGrid = DISPLAY_GRID): DoubleArray =
        DoubleArray(grid.size).also { responseDb(bands, form, grid, it) }

    /** One band's own curve in dB (disabled bands included - the editor may still show them). */
    fun bandDb(band: Band, form: ShelfForm, grid: FreqGrid = DISPLAY_GRID): DoubleArray =
        DoubleArray(grid.size).also { addBandDb(band, form, grid, it) }
}

/** Auto preamp: whole dB, never positive. */
object Preamp {
    /**
     * The lowest preamp Contour offers on any DAC, whatever its register takes (owner 11.11.2026: "nikt realnie nie potrzebuje -128 dB of preamp").
     * The Moondrop Dawn Pro 2's PRE-GAIN register runs -128..0 dB; the WalkPlay DACs already stop at -30. It limits what the editor, the
     * slider, the DAC page and a send take; a read keeps what the DAC holds, so a lower value set by another tool loads and blocks the send.
     */
    const val FLOOR_DB = -30.0

    /**
     * Rounds [x] dB DOWN (toward quieter, never louder) to a multiple of [step]; the 1e-6 guard keeps a value that is
     * already on the grid (-3.2 stays -3.2). Every rounding of a stored or sent preamp goes through here.
     */
    fun floorTo(x: Double, step: Double = 0.1): Double {
        // Far outside any device range there is no grid to land on, and scaling a huge finite value could overflow to
        // Infinity: return it unchanged (the planner refuses it as before). Non-finite input is returned as is.
        if (!x.isFinite() || kotlin.math.abs(x) >= 1e9) return x
        return if (step == 0.1) kotlin.math.floor(x * 10 + 1e-6) / 10.0 else kotlin.math.floor(x / step + 1e-6) * step
    }

    private const val SCAN_POINTS = 1024
    private val SCAN_GRID: Dsp.FreqGrid by lazy { Dsp.FreqGrid(Dsp.logFreqs(SCAN_POINTS)) }

    /**
     * Max of the total response over 1 024 log points plus every enabled band's centre frequency;
     * `-ceil(max - 1e-6)` whole dB when max > 0, else 0. The Protocol Micro stores whole dB only, and
     * rounding up leaves a little extra headroom (owner's preference). The shelves are those of [form], the one the DAC plays ([ShelfForm]); the answer cache is keyed by the bands and the form.
     */
    fun auto(bands: List<Band>, form: ShelfForm): Int {
        val active = bands.filter { it.enabled }
        if (active.isEmpty()) return 0
        answers.firstOrNull { it.form == form && it.bands == active }?.let { return it.db }
        val centres = Dsp.FreqGrid(DoubleArray(active.size) { active[it].freqHz })
        val max = maxOf(Dsp.responseDb(active, form, SCAN_GRID).max(), Dsp.responseDb(active, form, centres).max())
        require(max.isFinite()) { "AUTO preamp needs a finite response" }
        val db = if (max > 0) -kotlin.math.ceil(max - 1e-6).toInt() else 0
        answers = (listOf(Answer(active, form, db)) + answers).take(KEPT)
        return db
    }

    /**
     * The last few questions and their answers. One step of an edit asks for the same bands many times - the preamp row, the send checks, the match against what
     * the DAC holds, once more per USER slot - and each answer is a scan of 1 024 points per band. Only answers are kept: an EQ with no finite response throws
     * every time. The list is replaced whole, so the UI thread and the DAC thread can both ask (a lost update only costs one scan).
     */
    private class Answer(val bands: List<Band>, val form: ShelfForm, val db: Int)
    private const val KEPT = 4
    @Volatile private var answers: List<Answer> = emptyList()
}
