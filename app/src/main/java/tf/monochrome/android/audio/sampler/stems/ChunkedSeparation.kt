// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.ensureActive
import tf.monochrome.android.audio.sampler.SampleEdits
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Runs a fixed-size separator over audio of any length.
 *
 * Every model worth using here takes a window of a few seconds, not a song. A
 * four-minute track has to be cut up, processed piece by piece and put back
 * together — and putting it back together is the part that goes wrong. Butt
 * the pieces end to end and every join is a discontinuity: a click if the
 * waveform jumps, a phase seam if it does not quite, and a level bump wherever
 * two windows disagree.
 *
 * The fix is the one Demucs uses in `apply_model`, reproduced here: overlap the
 * windows, weight each one with a triangular ramp that falls to nothing at its
 * edges, and divide by the summed weights at the end. Because the division is
 * by the weights that were actually applied, the result is unity gain
 * everywhere by construction — including at the very start and end of the
 * track, where a window has no partner to overlap with. `ChunkedSeparationTest`
 * pins that down the only way worth doing: it runs an identity separator
 * through the whole path and asserts the output is the input, sample for
 * sample.
 *
 * ## Fixed-size windows are not an implementation detail
 *
 * [process] is always handed exactly [segmentFrames] frames, zero-padded when
 * the source runs out. That is not tidiness — QNN has no dynamic shapes at all,
 * so a variable-length final window would either fail to run on the NPU or
 * force a separate graph for it. Padding costs one mostly-silent window per
 * track and keeps one graph.
 */
object ChunkedSeparation {

    /** Demucs' default. Enough to hide a seam without paying for it twice. */
    const val DEFAULT_OVERLAP = 0.25f

    /** A fast mode. Seams are usually still inaudible; sometimes they are not. */
    const val FAST_OVERLAP = 0.1f

    /**
     * Shapes the crossfade. 1.0 is the linear ramp Demucs uses; higher values
     * make each window dominate its own centre more and hand over faster.
     */
    const val DEFAULT_TRANSITION_POWER = 1.0f

    data class Window(val index: Int, val start: Int, val validFrames: Int)

    data class Plan(
        val windows: List<Window>,
        val segmentFrames: Int,
        val hopFrames: Int,
        val totalFrames: Int,
    )

    /**
     * Works out where the windows go.
     *
     * Split from [run] so the arithmetic can be tested without a separator, and
     * so a caller can estimate memory and time before committing to a job.
     */
    fun plan(totalFrames: Int, segmentFrames: Int, overlap: Float = DEFAULT_OVERLAP): Plan {
        val segment = segmentFrames.coerceAtLeast(2)
        val safeOverlap = overlap.coerceIn(0f, 0.9f)
        // At least one frame of travel, or a rounding error at a tiny segment
        // size would make the loop never advance.
        val hop = max(1, (segment * (1f - safeOverlap)).toInt())

        val frames = totalFrames.coerceAtLeast(0)
        val windows = ArrayList<Window>()
        if (frames > 0) {
            var start = 0
            var index = 0
            while (start < frames) {
                windows += Window(index++, start, min(segment, frames - start))
                if (start + segment >= frames) break
                start += hop
            }
        }
        return Plan(windows, segment, hop, frames)
    }

    /**
     * The triangular weight for one window.
     *
     * Rises from 1 to the midpoint and falls back, normalised so its peak is 1
     * and raised to [transitionPower]. It never reaches zero at the edges,
     * which matters: a weight of exactly zero at both ends of a one-window
     * track would divide by zero rather than reproduce the audio.
     */
    fun triangularWeights(
        segmentFrames: Int,
        transitionPower: Float = DEFAULT_TRANSITION_POWER,
    ): FloatArray {
        val n = segmentFrames.coerceAtLeast(2)
        val half = n / 2
        val weights = FloatArray(n)
        for (i in 0 until half) weights[i] = (i + 1).toFloat()
        for (i in half until n) weights[i] = (n - i).toFloat()
        var peak = 0f
        for (w in weights) peak = max(peak, w)
        if (peak <= 0f) return FloatArray(n) { 1f }
        for (i in weights.indices) {
            weights[i] = (weights[i] / peak).pow(transitionPower)
        }
        return weights
    }

    /**
     * Separates [input] by running [process] over overlapping windows.
     *
     * [process] receives a stereo window of exactly `segmentFrames` frames and
     * returns one buffer per stem of the same length. Stems it omits are
     * treated as silence for that window rather than as an error, so a backend
     * that can only find vocals in a passage does not fail the whole track.
     *
     * Cancellation is checked between windows, which is the only place it can
     * be honoured without abandoning a partly-written accumulator.
     */
    suspend fun run(
        input: SampleEdits.Buffer,
        segmentFrames: Int,
        stems: Set<Stem>,
        overlap: Float = DEFAULT_OVERLAP,
        transitionPower: Float = DEFAULT_TRANSITION_POWER,
        onProgress: (Float) -> Unit = {},
        process: suspend (SampleEdits.Buffer) -> Map<Stem, SampleEdits.Buffer>,
    ): Map<Stem, SampleEdits.Buffer> {
        val frames = input.frames
        if (frames == 0 || stems.isEmpty()) return emptyMap()

        val plan = plan(frames, segmentFrames, overlap)
        val weights = triangularWeights(plan.segmentFrames, transitionPower)

        // One accumulator pair per stem, plus a single shared weight sum —
        // every stem is weighted identically, so keeping one copy rather than
        // four saves a float array the length of the track per extra stem.
        val accL = stems.associateWith { FloatArray(frames) }
        val accR = stems.associateWith { FloatArray(frames) }
        val weightSum = FloatArray(frames)

        val srcL = input.left
        val srcR = input.right ?: input.left
        val chunkL = FloatArray(plan.segmentFrames)
        val chunkR = FloatArray(plan.segmentFrames)

        for (window in plan.windows) {
            coroutineContext.ensureActive()

            // Zero-fill first: the tail window is shorter than the segment and
            // the model must not see the previous window's audio in the gap.
            java.util.Arrays.fill(chunkL, 0f)
            java.util.Arrays.fill(chunkR, 0f)
            for (i in 0 until window.validFrames) {
                chunkL[i] = srcL[window.start + i]
                chunkR[i] = srcR[window.start + i]
            }

            val produced = process(
                SampleEdits.Buffer(chunkL.copyOf(), chunkR.copyOf(), input.sampleRate),
            )

            for (i in 0 until window.validFrames) {
                weightSum[window.start + i] += weights[i]
            }
            for (stem in stems) {
                val out = produced[stem] ?: continue
                val ol = out.left
                val or = out.right ?: out.left
                val dl = accL.getValue(stem)
                val dr = accR.getValue(stem)
                val usable = min(window.validFrames, min(ol.size, or.size))
                for (i in 0 until usable) {
                    val w = weights[i]
                    dl[window.start + i] += ol[i] * w
                    dr[window.start + i] += or[i] * w
                }
            }

            onProgress((window.index + 1).toFloat() / plan.windows.size)
        }

        // Normalise by the weight actually applied at each sample. This is what
        // makes the overlap-add unity gain rather than approximately so, and it
        // is why the first and last windows need no special case.
        for (stem in stems) {
            val dl = accL.getValue(stem)
            val dr = accR.getValue(stem)
            for (i in 0 until frames) {
                val w = weightSum[i]
                if (w > 1e-9f) {
                    dl[i] /= w
                    dr[i] /= w
                }
            }
        }

        return stems.associateWith { stem ->
            SampleEdits.Buffer(accL.getValue(stem), accR.getValue(stem), input.sampleRate)
        }
    }

    /**
     * Peak heap cost of a run, in bytes, so the caller can shrink the segment
     * or refuse the job before the allocator does it for them.
     *
     * Counts the accumulators, the weight sum and the working chunks. It does
     * not count the model's own activations, which only the backend knows.
     */
    fun estimateBytes(totalFrames: Int, segmentFrames: Int, stemCount: Int): Long {
        val perStem = 2L * totalFrames * Float.SIZE_BYTES
        val chunks = 4L * segmentFrames * Float.SIZE_BYTES
        return perStem * stemCount + totalFrames.toLong() * Float.SIZE_BYTES + chunks
    }
}
