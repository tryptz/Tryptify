package tf.monochrome.android.audio.eq.shizuku

import tf.monochrome.android.audio.eq.EqBiquad
import tf.monochrome.android.audio.eq.EqBiquadType
import tf.monochrome.android.audio.eq.designBiquadCoefficients
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The equalizer the Shizuku system-wide mode plays other apps' audio
 * through: the listener's correction as the real parametric filters, a
 * chain an ear, where the standard system-wide effect can only sample it at
 * 31 fixed bands.
 *
 * The same sections the app's own player runs ([EqBiquad], the matched
 * coefficients), so what another app plays is corrected exactly as a song in
 * Tryptify is. It runs in Shizuku's process on 16-bit stereo, which is what
 * Android hands a loopback capture, in place:
 *
 *  - the preamp, then each ear's bands;
 *  - a soft knee over [KNEE] of full scale, so a boost the preamp did not
 *    make room for rounds off where it would have clipped. Below the knee
 *    nothing is touched;
 *  - back to 16 bits with one bit of triangular dither, since the filters'
 *    output is no longer on the 16-bit grid and plain rounding would add
 *    distortion that follows the music.
 *
 * With no curve ([setCurve] with nothing that shapes the sound and no
 * preamp) the samples pass exactly as they came.
 *
 * [setCurve] may be called from any thread and designs the filters there;
 * [process] is the audio thread's and only picks the finished design up.
 * Pure Kotlin with no Android types: it is unit tested on the JVM, and it
 * must stay loadable in a process that has no application.
 */
internal class SystemPeqDsp(private val sampleRate: Int) {

    private class Design(val left: Array<FloatArray>, val right: Array<FloatArray>, val gain: Float)

    @Volatile
    private var pending: Design? = EMPTY

    // The audio thread's own.
    private var current: Design = EMPTY
    private var chainL: Array<EqBiquad> = emptyArray()
    private var chainR: Array<EqBiquad> = emptyArray()
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)
    private var seed = 0x2545F491

    /**
     * The curve to play from the next block: each ear's bands as
     * [packBands] packs them, and the preamp in dB.
     */
    fun setCurve(left: FloatArray, right: FloatArray, preampDb: Float) {
        val gain = if (preampDb.isFinite()) 10f.pow(preampDb.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB) / 20f) else 1f
        pending = Design(design(left), design(right), gain)
    }

    private fun design(packed: FloatArray): Array<FloatArray> {
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i + FIELDS <= packed.size && out.size < MAX_BANDS) {
            val type = when (packed[i].toInt()) {
                TYPE_LOW_SHELF -> EqBiquadType.LOW_SHELF
                TYPE_HIGH_SHELF -> EqBiquadType.HIGH_SHELF
                else -> EqBiquadType.PEAKING
            }
            val freq = packed[i + 1]
            val gainDb = packed[i + 2]
            val q = packed[i + 3]
            i += FIELDS
            // What crossed a process boundary is not trusted to be a filter.
            if (!freq.isFinite() || !gainDb.isFinite() || !q.isFinite()) continue
            if (freq <= 0f || freq >= sampleRate * 0.49f || q <= 0f || gainDb == 0f) continue
            out += designBiquadCoefficients(
                type, sampleRate.toDouble(), freq.toDouble(), q.toDouble(),
                gainDb.coerceIn(-MAX_BAND_DB, MAX_BAND_DB).toDouble(), matched = true,
            )
        }
        return out.toTypedArray()
    }

    /** Whether the curve in force changes the sound at all. */
    val shaping: Boolean get() = (pending ?: current).let { it.left.isNotEmpty() || it.right.isNotEmpty() || it.gain != 1f }

    /** [frames] stereo frames of [pcm], interleaved, in place. */
    fun process(pcm: ShortArray, frames: Int) {
        pending?.let { next ->
            pending = null
            // The same number of sections keeps their memory, so a turn of a
            // knob moves the response and does not restart it.
            chainL = adopt(chainL, next.left)
            chainR = adopt(chainR, next.right)
            current = next
        }
        val d = current
        if (d.left.isEmpty() && d.right.isEmpty() && d.gain == 1f) return
        if (bufL.size < frames) {
            bufL = FloatArray(frames)
            bufR = FloatArray(frames)
        }
        val g = d.gain * FROM_SHORT
        for (i in 0 until frames) {
            bufL[i] = pcm[2 * i] * g
            bufR[i] = pcm[2 * i + 1] * g
        }
        for (b in chainL) b.processBlock(bufL, frames)
        for (b in chainR) b.processBlock(bufR, frames)
        for (i in 0 until frames) {
            pcm[2 * i] = toShort(bufL[i])
            pcm[2 * i + 1] = toShort(bufR[i])
        }
    }

    private fun adopt(chain: Array<EqBiquad>, coefs: Array<FloatArray>): Array<EqBiquad> {
        val out = if (chain.size == coefs.size) chain else Array(coefs.size) { EqBiquad() }
        for (i in coefs.indices) out[i].retune(coefs[i])
        return out
    }

    private fun toShort(x: Float): Short {
        var y = if (x.isFinite()) x else 0f
        val a = abs(y)
        if (a > KNEE) {
            val over = (a - KNEE) / (1f - KNEE)
            val shaped = KNEE + (1f - KNEE) * tanh(over)
            y = if (y < 0f) -shaped else shaped
        }
        // Triangular dither, one bit peak to peak: two uniform numbers.
        seed = seed * 1664525 + 1013904223
        val r1 = (seed ushr 8) * UNIT
        seed = seed * 1664525 + 1013904223
        val r2 = (seed ushr 8) * UNIT
        val v = y * TO_SHORT + (r1 - r2)
        return Math.round(v).coerceIn(-32768, 32767).toShort()
    }

    companion object {
        /** Floats a band: its kind, its frequency in Hz, its gain in dB, its Q. */
        const val FIELDS = 4
        const val TYPE_PEAKING = 0
        const val TYPE_LOW_SHELF = 1
        const val TYPE_HIGH_SHELF = 2

        /** More than any correction has; the rest are dropped, not played wrong. */
        const val MAX_BANDS = 64

        /** Where the soft knee begins, of full scale. */
        const val KNEE = 0.96f

        private const val MAX_BAND_DB = 30f
        private const val MIN_PREAMP_DB = -60f
        private const val MAX_PREAMP_DB = 24f
        private const val FROM_SHORT = 1f / 32768f
        private const val TO_SHORT = 32768f
        private const val UNIT = 1f / 16777216f
        private val EMPTY = Design(emptyArray(), emptyArray(), 1f)

        /**
         * [bands] as [setCurve] takes them: the ones that are on and do
         * something, [FIELDS] floats each, in order.
         */
        fun packBands(bands: List<EqBand>): FloatArray {
            val live = bands.filter { it.enabled && it.gain != 0f }.take(MAX_BANDS)
            val out = FloatArray(live.size * FIELDS)
            live.forEachIndexed { i, b ->
                out[i * FIELDS] = when (b.type) {
                    FilterType.LOWSHELF -> TYPE_LOW_SHELF
                    FilterType.HIGHSHELF -> TYPE_HIGH_SHELF
                    FilterType.PEAKING -> TYPE_PEAKING
                }.toFloat()
                out[i * FIELDS + 1] = b.freq
                out[i * FIELDS + 2] = b.gain
                out[i * FIELDS + 3] = b.q
            }
            return out
        }
    }
}
