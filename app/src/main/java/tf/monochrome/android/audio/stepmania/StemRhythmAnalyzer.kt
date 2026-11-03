// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.ln
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

/**
 * Lightweight rhythm analysis tuned for an isolated drum stem.
 *
 * Separation has already removed most harmonic energy, so a transient envelope
 * plus normalized autocorrelation is enough for the first on-device version.
 * The full mix remains a fallback when no drum stem was requested or produced.
 *
 * The other stems never move the tempo; given them, it adds what they do on
 * the drums' grid (see [StemFeatureExtractor]).
 */
class StemRhythmAnalyzer(
    private val minimumBpm: Float = 65f,
    private val maximumBpm: Float = 220f,
) : StepManiaRhythmAnalyzer {

    override fun analyze(
        mix: SampleEdits.Buffer,
        drums: SampleEdits.Buffer?,
    ): RhythmAnalysis {
        val source = drums?.takeIf {
            it.sampleRate == mix.sampleRate && it.frames == mix.frames
        } ?: mix
        require(source.sampleRate > 0) { "sample rate must be positive" }
        val envelope = TransientEnvelopeBuilder(source.sampleRate)
        envelope.add(source.left, source.right, 0, source.frames)
        return analyze(envelope)
    }

    /**
     * Drums for the tempo and grid, and every other stem the separator gave
     * back for what the tune does on that grid (see [StemFeatureExtractor]).
     */
    override fun analyze(
        mix: SampleEdits.Buffer,
        stems: Map<Stem, SampleEdits.Buffer>,
    ): RhythmAnalysis {
        val drums = stems[Stem.DRUMS]?.takeIf {
            it.sampleRate == mix.sampleRate && it.frames == mix.frames
        } ?: mix
        require(drums.sampleRate > 0) { "sample rate must be positive" }
        val envelope = TransientEnvelopeBuilder(drums.sampleRate)
        envelope.add(drums.left, drums.right, 0, drums.frames)
        val layers = StemLayerEnvelopes(drums.sampleRate)
        layers.add(stems, mix.frames, 0, mix.frames)
        return analyze(envelope, layers)
    }

    /**
     * [analyze] of the drums, with what [layers] heard on the same grid, at
     * the level of [octaveHint] (see [analyze]).
     */
    fun analyze(
        envelope: TransientEnvelopeBuilder,
        layers: StemLayerEnvelopes,
        octaveHint: Float? = null,
    ): RhythmAnalysis {
        val rhythm = analyze(envelope, octaveHint)
        return rhythm.copy(stems = StemFeatureExtractor.extract(rhythm, layers.builders))
    }

    /**
     * The same analysis from an envelope built as the audio streamed past,
     * for a song that is never in memory whole (see
     * [StepManiaConversionService]).
     *
     * [octaveHint] is the whole mix's tempo ([tf.monochrome.android.audio.tempo.SongTempo]),
     * which settles half or double time. The drums' envelope cannot: it
     * hears the hats as sharply as the kick, so hats on every eighth make
     * twice the tempo look as likely as the beat. The mix's estimator splits
     * the kick and snare from the hats and decides on them. The drums still
     * place the beat; the hint only picks the level, and one that is no
     * power of two from the drums' tempo is ignored.
     */
    fun analyze(envelope: TransientEnvelopeBuilder, octaveHint: Float? = null): RhythmAnalysis {
        val sampleRate = envelope.sampleRate
        require(envelope.frames >= sampleRate.toLong() * MINIMUM_SECONDS) {
            "at least $MINIMUM_SECONDS seconds of audio are required"
        }

        // Hops a second: 100 only where the rate divides by it (22050 Hz
        // hops 220 samples, 100.2 a second, and read as 100 the tempo came
        // out 0.2% slow and the arrows drifted off).
        val hz = envelope.hopsPerSecond.toFloat()
        val raw = envelope.raw()
        val shaped = shape(raw)
        val lag = strongestLag(shaped, hz)
        // Before the song is silence, so a beat on its first hop rises from
        // nothing; taken as no rise, the grid started a beat late.
        val onset = FloatArray(raw.size) { index ->
            max(0f, raw[index] - if (index == 0) 0f else raw[index - 1])
        }
        val tempo = refineTempo(onset, lag, hz).let { drums ->
            octaveHint?.let { atLevelOf(drums, it, onset, hz) } ?: drums
        }
        val bpm = tempo.bpm
        val offsetSeconds = tempo.phaseFrames / hz
        val durationSeconds = envelope.frames.toFloat() / sampleRate
        val grid = sampleSixteenthGrid(
            envelope = shaped,
            offsetSeconds = offsetSeconds,
            bpm = bpm,
            durationSeconds = durationSeconds,
            hz = hz,
        )

        return RhythmAnalysis(
            bpm = bpm,
            offsetSeconds = offsetSeconds,
            confidence = lagConfidence(shaped, lag),
            durationSeconds = durationSeconds,
            gridStrengths = grid,
        )
    }

    private fun shape(raw: FloatArray): FloatArray {
        // A three-frame maximum keeps narrow drum attacks visible after the
        // file is reduced to a 100 Hz envelope.
        val widened = FloatArray(raw.size) { index ->
            var value = raw[index]
            if (index > 0) value = max(value, raw[index - 1] * 0.8f)
            if (index + 1 < raw.size) value = max(value, raw[index + 1] * 0.8f)
            value
        }
        return robustNormalize(widened)
    }

    private fun strongestLag(envelope: FloatArray, hz: Float): Int {
        val minimumLag = (60f * hz / maximumBpm).roundToInt().coerceAtLeast(1)
        val maximumLag = (60f * hz / minimumBpm).roundToInt()
            .coerceAtMost(envelope.lastIndex)
        var bestLag = (60f * hz / DEFAULT_BPM).roundToInt()
        var bestScore = Float.NEGATIVE_INFINITY

        for (lag in minimumLag..maximumLag) {
            var score = correlation(envelope, lag)
            if (lag * 2 <= maximumLag) score += correlation(envelope, lag * 2) * 0.2f
            val bpm = 60f * hz / lag
            if (bpm in 85f..190f) score *= 1.025f
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return if (bestScore <= 1e-4f) {
            (60f * hz / DEFAULT_BPM).roundToInt()
        } else {
            bestLag
        }
    }

    private class Tempo(val bpm: Float, val phaseFrames: Float, val score: Double)

    /**
     * The tempo between whole hops. [strongestLag] can only say 6000 / lag
     * BPM (150 or 153.8, never 150.5), and a song a fraction off that grid
     * slides off its arrows: at 150.5 charted as 150, half a beat by 0:45.
     * So every tempo within half a hop of the lag's is tried, coarsely and
     * then finely around the best, each at its best phase, scored by how
     * hard the envelope rises on its beats. Across hundreds of beats that
     * places the beat far finer than one hop.
     */
    private fun refineTempo(onset: FloatArray, lag: Int, hz: Float): Tempo {
        val slowest = max(minimumBpm, 60f * hz / (lag + 0.5f))
        val fastest = min(maximumBpm, 60f * hz / (lag - 0.5f))
        var best = Tempo(60f * hz / lag, 0f, Double.NEGATIVE_INFINITY)
        val coarseSteps = ((fastest - slowest) / COARSE_BPM_STEP).toInt()
        for (step in 0..coarseSteps) {
            val tempo = onBeats(onset, slowest + step * COARSE_BPM_STEP, hz)
            if (tempo.score > best.score) best = tempo
        }
        val centre = best.bpm
        val fineSteps = (COARSE_BPM_STEP / FINE_BPM_STEP).roundToInt()
        for (step in -fineSteps..fineSteps) {
            val bpm = centre + step * FINE_BPM_STEP
            if (bpm < slowest || bpm > fastest) continue
            val tempo = onBeats(onset, bpm, hz)
            if (tempo.score > best.score) best = tempo
        }
        return best
    }

    /**
     * [drums] doubled or halved (up to twice either way) to the level of
     * [hint], with the phase found again at it: which beat of two is the
     * downbeat is the drums' to say. Null when no level is within
     * [SAME_LEVEL] of [hint], or none is in range: then the drums' own.
     */
    private fun atLevelOf(drums: Tempo, hint: Float, onset: FloatArray, hz: Float): Tempo? {
        if (!(hint > 0f) || !hint.isFinite()) return null
        val level = LEVELS
            .map { drums.bpm * it }
            .filter { it in minimumBpm..maximumBpm }
            .minByOrNull { abs(ln(it / hint)) }
            ?: return null
        if (abs(level / hint - 1f) > SAME_LEVEL) return null
        if (level == drums.bpm) return drums
        return onBeats(onset, level, hz)
    }

    /** [bpm] at the phase whose beats land on the most rise, half a hop at a time. */
    private fun onBeats(onset: FloatArray, bpm: Float, hz: Float): Tempo {
        val period = 60.0 * hz / bpm
        var bestPhase = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var phase = 0.0
        while (phase < period) {
            var score = 0.0
            var count = 0
            var beat = phase
            while (beat < onset.lastIndex) {
                val index = beat.toInt()
                val fraction = beat - index
                score += onset[index] * (1.0 - fraction) + onset[index + 1] * fraction
                count += 1
                beat += period
            }
            if (count > 0) score /= count
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
            phase += 0.5
        }
        return Tempo(bpm, bestPhase.toFloat(), bestScore)
    }

    private fun sampleSixteenthGrid(
        envelope: FloatArray,
        offsetSeconds: Float,
        bpm: Float,
        durationSeconds: Float,
        hz: Float,
    ): FloatArray {
        val subdivision = 60f / bpm / 4f
        val count = ceil((durationSeconds - offsetSeconds).coerceAtLeast(0f) / subdivision)
            .toInt()
            .coerceAtLeast(1)
        return FloatArray(count) { tick ->
            val seconds = offsetSeconds + tick * subdivision
            val frame = (seconds * hz).roundToInt()
            localPeak(envelope, frame, 2).coerceIn(0f, 1f)
        }
    }

    private fun lagConfidence(envelope: FloatArray, lag: Int): Float =
        correlation(envelope, lag).coerceIn(0f, 1f)

    private fun correlation(values: FloatArray, lag: Int): Float {
        if (lag <= 0 || lag >= values.size) return 0f
        var product = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        for (index in lag until values.size) {
            val first = values[index].toDouble()
            val second = values[index - lag].toDouble()
            product += first * second
            firstEnergy += first * first
            secondEnergy += second * second
        }
        val denominator = sqrt(firstEnergy * secondEnergy)
        return if (denominator <= 1e-12) 0f else (product / denominator).toFloat()
    }

    private fun robustNormalize(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        val sorted = values.sortedArray()
        val floor = sorted[(sorted.lastIndex * 0.5f).roundToInt()]
        val ceiling = sorted[(sorted.lastIndex * 0.95f).roundToInt()]
        val range = ceiling - floor
        if (range <= 1e-6f) return FloatArray(values.size)
        return FloatArray(values.size) { index ->
            ((values[index] - floor) / range).coerceIn(0f, 1f)
        }
    }

    private fun localPeak(values: FloatArray, center: Int, radius: Int): Float {
        var peak = 0f
        val start = (center - radius).coerceAtLeast(0)
        val end = (center + radius).coerceAtMost(values.lastIndex)
        for (index in start..end) peak = max(peak, values[index])
        return peak
    }

    internal companion object {
        const val ENVELOPE_HZ = 100
        const val MINIMUM_SECONDS = 4
        const val DEFAULT_BPM = 120f
        const val COARSE_BPM_STEP = 0.05f
        const val FINE_BPM_STEP = 0.005f

        /** The levels of one beat [atLevelOf] tries: a quarter of the drums' tempo to four times it. */
        val LEVELS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)

        /** How near the mix's tempo a level must be to be its level. */
        const val SAME_LEVEL = 0.06f
    }
}

/**
 * The analyzer's transient envelope, built a block at a time.
 *
 * One value per hop (a hundredth of a second): how sharply the mono signal
 * moves within it, a cheap high-pass, plus a little of its peak, on a log
 * scale. A block boundary changes nothing — the difference across it is taken
 * from the last sample of the block before — so streaming a song through this
 * gives exactly the values the whole song in one buffer does.
 *
 * Alongside it, how often the signal crosses zero in each hop ([crossingRates]).
 * On a melodic stem that rises and falls with the pitch, which is all the
 * chart needs from it: which way the tune went between two notes.
 */
class TransientEnvelopeBuilder(val sampleRate: Int) {

    init {
        require(sampleRate > 0) { "sample rate must be positive" }
    }

    private val hop = (sampleRate / StemRhythmAnalyzer.ENVELOPE_HZ).coerceAtLeast(1)

    /** Values a second: [StemRhythmAnalyzer.ENVELOPE_HZ] when the rate divides by it, near it otherwise. */
    val hopsPerSecond: Double get() = sampleRate.toDouble() / hop
    private var values = FloatArray(1024)
    private var count = 0
    private var previous = 0f
    private var started = false
    private var differenceEnergy = 0.0
    private var peak = 0f
    private var inHop = 0
    private var rates = FloatArray(1024)
    private var crossings = 0

    /** Frames taken so far. */
    var frames = 0L
        private set

    /** Frames [from] until [until] of [left] and [right] (null for mono). */
    fun add(left: FloatArray, right: FloatArray?, from: Int, until: Int) {
        for (index in from until until) {
            val sample = if (right != null) (left[index] + right[index]) * 0.5f else left[index]
            if (!started) {
                previous = sample
                started = true
            }
            val difference = sample - previous
            if ((sample >= 0f) != (previous >= 0f)) crossings += 1
            differenceEnergy += difference * difference
            peak = max(peak, kotlin.math.abs(sample))
            previous = sample
            inHop += 1
            if (inHop == hop) close()
        }
        frames += (until - from).coerceAtLeast(0)
    }

    /** One value per hop, the last one partial; the builder can keep going after. */
    fun raw(): FloatArray {
        val out = values.copyOf(count + if (inHop > 0) 1 else 0)
        if (inHop > 0) out[count] = valueOf(differenceEnergy, peak, inHop)
        return out
    }

    /**
     * Zero crossings per second in each hop, the same length as [raw]. Half of
     * it is a pure tone's frequency.
     */
    fun crossingRates(): FloatArray {
        val out = rates.copyOf(count + if (inHop > 0) 1 else 0)
        if (inHop > 0) out[count] = rateOf(crossings, inHop)
        return out
    }

    private fun close() {
        if (count == values.size) {
            values = values.copyOf(values.size * 2)
            rates = rates.copyOf(values.size)
        }
        values[count] = valueOf(differenceEnergy, peak, inHop)
        rates[count] = rateOf(crossings, inHop)
        count += 1
        differenceEnergy = 0.0
        peak = 0f
        inHop = 0
        crossings = 0
    }

    companion object {
        /**
         * A builder holding envelopes made elsewhere — by a TryptMania Parser
         * server, from the same arithmetic — as if it had taken [frames]
         * frames itself: [raw] and [crossingRates] give these back.
         */
        fun restored(sampleRate: Int, raw: FloatArray, rates: FloatArray, frames: Long): TransientEnvelopeBuilder {
            require(raw.size == rates.size) { "raw and rates differ in length" }
            return TransientEnvelopeBuilder(sampleRate).apply {
                values = raw.copyOf(maxOf(raw.size, 1))
                this.rates = rates.copyOf(maxOf(rates.size, 1))
                count = raw.size
                this.frames = frames
            }
        }
    }

    private fun rateOf(crossings: Int, samples: Int): Float =
        crossings.toFloat() * sampleRate / samples.coerceAtLeast(1)

    private fun valueOf(energy: Double, peak: Float, samples: Int): Float {
        val highPassedRms = sqrt(energy / samples.coerceAtLeast(1)).toFloat()
        return ln(1.0 + 40.0 * (highPassedRms + peak * 0.08f)).toFloat()
    }
}
