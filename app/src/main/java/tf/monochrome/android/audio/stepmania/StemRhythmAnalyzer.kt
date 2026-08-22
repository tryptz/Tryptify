// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * Lightweight rhythm analysis tuned for an isolated drum stem.
 *
 * Separation has already removed most harmonic energy, so a transient envelope
 * plus normalized autocorrelation is enough for the first on-device version.
 * The full mix remains a fallback when no drum stem was requested or produced.
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
        require(source.frames >= source.sampleRate * MINIMUM_SECONDS) {
            "at least $MINIMUM_SECONDS seconds of audio are required"
        }

        val hop = (source.sampleRate / ENVELOPE_HZ).coerceAtLeast(1)
        val envelope = transientEnvelope(source, hop)
        val lag = strongestLag(envelope)
        val phase = strongestPhase(envelope, lag)
        val bpm = 60f * ENVELOPE_HZ / lag
        val offsetSeconds = phase.toFloat() / ENVELOPE_HZ
        val durationSeconds = source.frames.toFloat() / source.sampleRate
        val grid = sampleSixteenthGrid(
            envelope = envelope,
            offsetSeconds = offsetSeconds,
            bpm = bpm,
            durationSeconds = durationSeconds,
        )

        return RhythmAnalysis(
            bpm = bpm,
            offsetSeconds = offsetSeconds,
            confidence = lagConfidence(envelope, lag),
            durationSeconds = durationSeconds,
            gridStrengths = grid,
        )
    }

    private fun transientEnvelope(source: SampleEdits.Buffer, hop: Int): FloatArray {
        val frames = ceil(source.frames.toDouble() / hop).toInt()
        val raw = FloatArray(frames)
        var previous = monoAt(source, 0)

        for (frame in 0 until frames) {
            val start = frame * hop
            val end = (start + hop).coerceAtMost(source.frames)
            var differenceEnergy = 0.0
            var peak = 0f
            for (index in start until end) {
                val sample = monoAt(source, index)
                val difference = sample - previous
                differenceEnergy += difference * difference
                peak = max(peak, kotlin.math.abs(sample))
                previous = sample
            }
            val count = (end - start).coerceAtLeast(1)
            val highPassedRms = sqrt(differenceEnergy / count).toFloat()
            raw[frame] = ln(1.0 + 40.0 * (highPassedRms + peak * 0.08f)).toFloat()
        }

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

    private fun strongestLag(envelope: FloatArray): Int {
        val minimumLag = (60f * ENVELOPE_HZ / maximumBpm).roundToInt().coerceAtLeast(1)
        val maximumLag = (60f * ENVELOPE_HZ / minimumBpm).roundToInt()
            .coerceAtMost(envelope.lastIndex)
        var bestLag = (60f * ENVELOPE_HZ / DEFAULT_BPM).roundToInt()
        var bestScore = Float.NEGATIVE_INFINITY

        for (lag in minimumLag..maximumLag) {
            var score = correlation(envelope, lag)
            if (lag * 2 <= maximumLag) score += correlation(envelope, lag * 2) * 0.2f
            val bpm = 60f * ENVELOPE_HZ / lag
            if (bpm in 85f..190f) score *= 1.025f
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return if (bestScore <= 1e-4f) {
            (60f * ENVELOPE_HZ / DEFAULT_BPM).roundToInt()
        } else {
            bestLag
        }
    }

    private fun strongestPhase(envelope: FloatArray, lag: Int): Int {
        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (phase in 0 until lag) {
            var score = 0f
            var count = 0
            var index = phase
            while (index < envelope.size) {
                score += localPeak(envelope, index, 2)
                count += 1
                index += lag
            }
            if (count > 0) score /= count
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
        }
        return bestPhase
    }

    private fun sampleSixteenthGrid(
        envelope: FloatArray,
        offsetSeconds: Float,
        bpm: Float,
        durationSeconds: Float,
    ): FloatArray {
        val subdivision = 60f / bpm / 4f
        val count = ceil((durationSeconds - offsetSeconds).coerceAtLeast(0f) / subdivision)
            .toInt()
            .coerceAtLeast(1)
        return FloatArray(count) { tick ->
            val seconds = offsetSeconds + tick * subdivision
            val frame = (seconds * ENVELOPE_HZ).roundToInt()
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

    private fun monoAt(source: SampleEdits.Buffer, index: Int): Float {
        val left = source.left[index]
        return source.right?.let { (left + it[index]) * 0.5f } ?: left
    }

    private companion object {
        const val ENVELOPE_HZ = 100
        const val MINIMUM_SECONDS = 4
        const val DEFAULT_BPM = 120f
    }
}
