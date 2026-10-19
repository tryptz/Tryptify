package tf.monochrome.android.ui.eq

import androidx.compose.ui.graphics.Color
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The EQ Studio's axes: the same span, dB window and midband normalization as
 * [FrequencyResponseGraph], so a curve lands in the same place on both screens.
 * Pure arithmetic, kept out of the composables so it can be tested.
 */
internal object StudioAxis {
    const val MIN_FREQ = EqLimits.MIN_FREQ_HZ
    const val MAX_FREQ = EqLimits.MAX_FREQ_HZ
    const val DB_RANGE = 40f

    private val LOG_MIN = log10(MIN_FREQ)
    private val LOG_SPAN = log10(MAX_FREQ) - LOG_MIN

    /** Where [freq] sits across the axis, 0 at 20 Hz and 1 at 20 kHz. */
    fun fractionOf(freq: Float): Float =
        (log10(freq.coerceIn(MIN_FREQ, MAX_FREQ)) - LOG_MIN) / LOG_SPAN

    /** The inverse of [fractionOf], clamped to the axis. */
    fun freqAt(fraction: Float): Float =
        10f.pow(LOG_MIN + fraction.coerceIn(0f, 1f) * LOG_SPAN)

    /** [count] frequencies spaced evenly on the log axis, both ends included. */
    fun logGrid(count: Int): FloatArray =
        FloatArray(count) { freqAt(it.toFloat() / (count - 1)) }

    /**
     * The mean level from 250 Hz to 2.5 kHz, which the graph centres on. Falls
     * back to the level at 1 kHz for a curve with no points in that band.
     */
    fun normalizationOffset(data: List<FrequencyPoint>): Float {
        var sum = 0f
        var count = 0
        for (p in data) {
            if (p.freq in 250f..2500f) {
                sum += p.gain
                count++
            }
        }
        return if (count > 0) sum / count else interpolate(data, 1000f)
    }

    /**
     * [curve]'s level at [freq], interpolated on the log axis. Held flat past
     * either end, and 0 for an empty curve.
     */
    fun interpolate(curve: List<FrequencyPoint>, freq: Float): Float {
        if (curve.isEmpty()) return 0f
        if (freq <= curve.first().freq) return curve.first().gain
        if (freq >= curve.last().freq) return curve.last().gain
        var lo = 0
        var hi = curve.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (curve[mid].freq <= freq) lo = mid else hi = mid
        }
        val a = curve[lo]
        val b = curve[hi]
        val span = ln(b.freq / a.freq)
        if (span <= 0f) return a.gain
        val t = ln(freq / a.freq) / span
        return a.gain + t * (b.gain - a.gain)
    }

    /** [curve] resampled onto [freqs], or an empty array for an empty curve. */
    fun resample(curve: List<FrequencyPoint>, freqs: FloatArray): FloatArray =
        if (curve.isEmpty()) FloatArray(0) else FloatArray(freqs.size) { interpolate(curve, freqs[it]) }
}

/**
 * Whole-list band edits the Studio makes that the classic screen never needed:
 * adding a filter, removing one, sorting, and the drag gestures that move one.
 */
internal object StudioBands {
    /** More than this and a filter list stops being something a person tunes. */
    const val MAX_BANDS = 20
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f
    const val DEFAULT_Q = 1.41f

    fun nextId(bands: List<EqBand>): Int = (bands.maxOfOrNull { it.id } ?: -1) + 1

    /**
     * [bands] plus one flat peaking filter, placed in the middle of the widest
     * gap the current filters leave on the log axis — so a new filter appears
     * where there is room to see and grab it, rather than on top of another.
     * Unchanged once [MAX_BANDS] is reached.
     */
    fun withNewBand(bands: List<EqBand>): List<EqBand> {
        if (bands.size >= MAX_BANDS) return bands
        val stops = buildList {
            add(0f)
            bands.mapTo(this) { StudioAxis.fractionOf(it.freq) }
            add(1f)
        }.sorted()
        var bestMid = 0.5f
        var bestGap = -1f
        for (i in 0 until stops.size - 1) {
            val gap = stops[i + 1] - stops[i]
            if (gap > bestGap) {
                bestGap = gap
                bestMid = (stops[i] + stops[i + 1]) / 2f
            }
        }
        val freq = roundFreq(StudioAxis.freqAt(bestMid))
        return bands + EqBand(id = nextId(bands), type = FilterType.PEAKING, freq = freq, gain = 0f, q = DEFAULT_Q)
    }

    fun without(bands: List<EqBand>, id: Int): List<EqBand> = bands.filterNot { it.id == id }

    /**
     * Ordered low to high, ids kept: the selection and each filter's colour
     * follow the filter, not the slot it used to be in.
     */
    fun sortedByFrequency(bands: List<EqBand>): List<EqBand> = bands.sortedBy { it.freq }

    /** A frequency rounded the way a person would type it: 3 significant figures. */
    fun roundFreq(freq: Float): Float {
        if (freq <= 0f) return freq
        val magnitude = 10f.pow(kotlin.math.floor(log10(freq)) - 2f)
        return (freq / magnitude).roundToInt() * magnitude
    }

    /** A drag of [octaves] along the axis from [start]. */
    fun scrubFreq(start: Float, octaves: Float): Float =
        (start * 2f.pow(octaves)).coerceIn(StudioAxis.MIN_FREQ, StudioAxis.MAX_FREQ)

    fun clampGain(gain: Float): Float =
        gain.coerceIn(-EqLimits.AUTOEQ_MAX_BAND_DB, EqLimits.AUTOEQ_MAX_BAND_DB)

    /** Q moves on a log scale, so a drag feels the same at 0.5 as at 5. */
    fun scrubQ(start: Float, octaves: Float): Float =
        (start * 2f.pow(octaves)).coerceIn(MIN_Q, MAX_Q)

    /**
     * Q under a two-finger pinch. Spreading the fingers widens the filter, and
     * a wider filter is a *lower* Q, so Q follows the inverse of the spread.
     */
    fun pinchQ(startQ: Float, startSpan: Float, span: Float): Float {
        if (startSpan <= 0f || span <= 0f) return startQ
        return (startQ * startSpan / span).coerceIn(MIN_Q, MAX_Q)
    }

    /**
     * The filter whose handle is nearest [x], [y] within [radius] pixels, or
     * null when none is. Handles are given as (id, x, y).
     */
    fun nearestHandle(handles: List<Triple<Int, Float, Float>>, x: Float, y: Float, radius: Float): Int? {
        var best: Int? = null
        var bestDist = radius
        for ((id, hx, hy) in handles) {
            val d = sqrt((hx - x) * (hx - x) + (hy - y) * (hy - y))
            if (d <= bestDist) {
                bestDist = d
                best = id
            }
        }
        return best
    }
}

/**
 * Each filter's colour. Keyed on the filter's id, not its position, so sorting
 * or deleting another filter never repaints the one being tuned. The hue steps
 * by the golden angle, which keeps neighbours far apart however many there are.
 */
internal fun studioFilterColor(id: Int): Color {
    val hue = ((id * 137.508f) % 360f + 360f) % 360f
    return Color.hsv(hue, 0.62f, 0.93f)
}

/** "105 Hz", "1.2 kHz", "16 kHz". */
internal fun formatStudioFreq(freq: Float): String = when {
    freq >= 10_000f -> "${(freq / 1000f).roundToInt()} kHz"
    freq >= 1000f -> String.format(java.util.Locale.US, "%.1f kHz", freq / 1000f)
    else -> "${freq.roundToInt()} Hz"
}

/** "+3.5 dB", "−2.0 dB", "0.0 dB". */
internal fun formatStudioGain(gain: Float): String {
    val rounded = (gain * 10f).roundToInt() / 10f
    return when {
        rounded > 0f -> String.format(java.util.Locale.US, "+%.1f dB", rounded)
        rounded < 0f -> String.format(java.util.Locale.US, "−%.1f dB", abs(rounded))
        else -> "0.0 dB"
    }
}

internal fun formatStudioQ(q: Float): String = String.format(java.util.Locale.US, "%.2f", q)

/** The short code peqdb, Equalizer APO and AutoEq all use for a filter type. */
internal fun FilterType.studioCode(): String = when (this) {
    FilterType.PEAKING -> "PK"
    FilterType.LOWSHELF -> "LSC"
    FilterType.HIGHSHELF -> "HSC"
}
