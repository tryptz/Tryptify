package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.DeviceTarget
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Makes an EQ of the app's fit a USB DAC: its number of filters, the kinds
 * it has, and the frequency, gain and Q it takes.
 *
 * An EQ that already fits is left as it is, to the last digit. One that does
 * not is fitted again with the app's own AutoEQ ([AutoEqEngine]): from the
 * headphone's measurement when the preset kept it (the same fit the AutoEQ
 * screen makes, for the DAC's number of filters), else from the curve the old
 * bands drew. Nothing is cut off silently: [Result] says what changed and how
 * far the new curve is from the old one.
 */
object DacEqFit {

    /**
     * @property refit the bands were fitted anew, not carried over.
     * @property fromMeasurement … from the headphone's measurement.
     * @property limited bands whose frequency, gain or Q was outside the
     *   DAC's range and was moved to its edge.
     * @property sourceBands how many bands the EQ had that did anything.
     * @property maxErrorDb the largest distance between the two curves,
     *   20 Hz to 20 kHz. After a new fit the two are compared at the same
     *   level (a fit keeps a curve's shape, not its loudness).
     */
    class Result(
        val bands: List<EqBand>,
        val refit: Boolean,
        val fromMeasurement: Boolean,
        val limited: Int,
        val sourceBands: Int,
        val maxErrorDb: Float,
    ) {
        val asItIs: Boolean get() = !refit && limited == 0
    }

    /**
     * [source] fitted to [target]. [measurement] and [targetCurve] are the
     * headphone's curve and the curve it was corrected toward, when the
     * preset kept them.
     */
    fun fit(
        source: List<EqBand>,
        target: DeviceTarget,
        measurement: List<FrequencyPoint> = emptyList(),
        targetCurve: List<FrequencyPoint> = emptyList(),
    ): Result {
        val caps = target.caps
        val active = source.filter { it.enabled && it.gain != 0f }
        val fits = active.size <= caps.bands && active.all { DacEqMapping.toDac(it.type) in caps.types }
        val fromMeasurement = !fits && measurement.size >= 2 && targetCurve.size >= 2
        val chosen = when {
            fits -> active
            fromMeasurement -> AutoEqEngine.runAutoEqAlgorithm(
                measurement = measurement,
                target = targetCurve,
                bandCount = caps.bands,
                sampleRate = DacEqMapping.DISPLAY_SAMPLE_RATE,
            )
            else -> fitCurve(active, caps)
        }
        var limited = 0
        val inRange = chosen.sortedBy { it.freq }.mapIndexed { i, band ->
            val held = limit(band, caps)
            if (!same(held, band)) limited++
            held.copy(id = i, enabled = true)
        }
        val bands = withRealShelves(inRange, target)
        limited += bands.indices.count { !same(bands[it], inRange[it]) }
        return Result(
            bands = bands,
            refit = !fits,
            fromMeasurement = fromMeasurement,
            limited = limited,
            sourceBands = active.size,
            maxErrorDb = distance(active, bands, sameLevel = !fits),
        )
    }

    /**
     * The curve [active] draws, fitted again with as many filters as the DAC
     * has. Shelves the DAC has are kept as they are (a shelf is the one thing
     * peaks fit badly); everything else becomes the curve the new peaks follow.
     */
    private fun fitCurve(active: List<EqBand>, caps: DeviceCapabilities): List<EqBand> {
        val kept = active
            .filter { it.type != FilterType.PEAKING && DacEqMapping.toDac(it.type) in caps.types }
            .take(max(0, caps.bands - 1))
        val rest = active - kept.toSet()
        val room = caps.bands - kept.size
        if (rest.isEmpty() || room <= 0) return kept
        val curve = GRID.sum(rest)
        // The AutoEQ fit corrects a measurement toward a target; a curve to
        // be reproduced is the correction of its own mirror image toward flat.
        val mirrored = List(FREQS.size) { FrequencyPoint(FREQS[it], -curve[it]) }
        val flat = List(FREQS.size) { FrequencyPoint(FREQS[it], 0f) }
        return kept + AutoEqEngine.runAutoEqAlgorithm(
            measurement = mirrored,
            target = flat,
            bandCount = room,
            maxFrequency = 20_000f,
            sampleRate = DacEqMapping.DISPLAY_SAMPLE_RATE,
        )
    }

    /** [band] with its frequency, gain and Q inside what the DAC takes. */
    fun limit(band: EqBand, caps: DeviceCapabilities): EqBand {
        val type = DacEqMapping.toDac(band.type)
        val form = caps.shelfForm
        val gains = caps.gainRange(type)
        val moved = band.copy(
            freq = inside(band.freq, caps.freqMinHz, caps.freqMaxHz),
            gain = inside(band.gain, gains.start, gains.endInclusive),
        )
        // The DAC's range is in its own number (a slope, for some shelves),
        // so the Q is checked there and brought back.
        val qs = caps.qRange(type)
        val own = DacEqMapping.dacQ(moved, form)
        if (qs.start > qs.endInclusive || own in qs) return moved
        if (form == io.github.chronosauros.contour.core.ShelfForm.Q || band.type == FilterType.PEAKING) {
            return moved.copy(q = inside(band.q, qs.start, qs.endInclusive))
        }
        // A slope: a hair inside the edge, so the Q it comes back as is still
        // in range once it is a float.
        val edge = own.coerceIn(qs.start, qs.endInclusive)
        val held = edge + (if (edge == qs.start) 1 else -1) * 1e-4 * (qs.endInclusive - qs.start)
        val q = DacEqMapping.appQ(band.type, held, moved.gain.toDouble(), form) ?: DacEqMapping.DEFAULT_SHELF_Q
        return moved.copy(q = q.toFloat())
    }

    /**
     * [x] inside [lo]..[hi] as a float that is still inside it as a double:
     * 0.7 as a float is a hair under 0.7, and a DAC whose Q starts at 0.7
     * refused it.
     */
    private fun inside(x: Float, lo: Double, hi: Double): Float {
        if (lo > hi) return x
        var f = x.toDouble().coerceIn(lo, hi).toFloat()
        if (f < lo) f = Math.nextUp(f)
        if (f > hi) f = Math.nextDown(f)
        return f
    }

    /**
     * A strong shelf with a Q well over 0.7 is inside every DAC's ranges and
     * still not a filter on some: the DACs that are sent coefficients get
     * them from the slope formula, whose square root goes negative there, and
     * Contour refuses the band ("non-finite biquad coefficients"). Such an EQ
     * gets its steep shelves back at 0.707, where every gain has a shelf.
     */
    private fun withRealShelves(bands: List<EqBand>, target: DeviceTarget): List<EqBand> {
        if (bands.none { it.type != FilterType.PEAKING && it.q > STEEP_SHELF_Q }) return bands
        val refused = runCatching { target.issues(DacEqMapping.profile("fit", bands, null, target, 0L)) }
            .getOrElse { listOf(it.message.orEmpty()) }
        if (refused.none { "non-finite" in it || "real response" in it || "finite response" in it }) return bands
        return bands.map {
            if (it.type != FilterType.PEAKING && it.q > STEEP_SHELF_Q) it.copy(q = DacEqMapping.DEFAULT_SHELF_Q.toFloat()) else it
        }
    }

    private const val STEEP_SHELF_Q = 0.7072f

    private fun same(a: EqBand, b: EqBand) =
        abs(a.freq - b.freq) < 1e-3f && abs(a.gain - b.gain) < 1e-4f && abs(a.q - b.q) < 1e-4f

    /** The largest distance between the curves of [a] and [b], in dB. */
    private fun distance(a: List<EqBand>, b: List<EqBand>, sameLevel: Boolean): Float {
        val ca = GRID.sum(a)
        val cb = GRID.sum(b)
        var shift = 0f
        if (sameLevel) {
            var sum = 0f
            var n = 0
            for (i in FREQS.indices) if (FREQS[i] in 250f..2500f) { sum += cb[i] - ca[i]; n++ }
            if (n > 0) shift = sum / n
        }
        var worst = 0f
        for (i in FREQS.indices) worst = max(worst, abs(cb[i] - ca[i] - shift))
        return worst
    }

    /** 20 Hz to 20 kHz, 24 points an octave. */
    private val FREQS = FloatArray(240) { (20.0 * 1000.0.pow(it / 239.0)).toFloat() }
    private val GRID = AutoEqEngine.ResponseGrid(FREQS, DacEqMapping.DISPLAY_SAMPLE_RATE)
}
