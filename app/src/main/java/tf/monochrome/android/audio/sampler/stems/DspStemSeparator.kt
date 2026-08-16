// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tf.monochrome.android.audio.sampler.SampleEdits
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stem separation without a model.
 *
 * ## What this is, and what it is not
 *
 * This is classical DSP, not a neural separator. It will not match Demucs, and
 * it is not trying to: it ships nothing, downloads nothing, runs offline in a
 * couple of seconds on a phone, and needs no NPU. For a sampler — where the
 * question is usually "give me the drums out of this two-bar loop" — that
 * trade is a good one. For pulling a clean vocal out of a dense mix it is not,
 * and the UI says so rather than pretending.
 *
 * A model-backed [StemSeparator] can be dropped in beside this one without the
 * UI changing.
 *
 * ## How it works
 *
 * Two independent, well-understood decompositions, composed:
 *
 * **Harmonic/percussive**, after Fitzgerald (2010). Percussive events are
 * broadband and brief, so on a spectrogram they are vertical lines; harmonic
 * content is narrow and sustained, so it forms horizontal lines. Median
 * filtering across time suppresses the verticals and leaves the harmonic
 * estimate; median filtering across frequency does the reverse. Soft Wiener
 * masks built from the two estimates then split the original spectrum.
 *
 * **Centre extraction**, for the harmonic half. In almost every mix the lead
 * vocal is panned centre, so per bin the correlation between left and right
 * says how central that bin is. Centre-dominant bins above the bass band go to
 * vocals, the rest to other.
 *
 * The masks are built to sum to one at every bin, so
 * `drums + bass + vocals + other` reconstructs the input to within the STFT's
 * own round-trip error. That is a property worth having and worth testing: it
 * means nothing is silently lost, and a user who saves all four stems has not
 * thrown away part of their sample.
 */
@Singleton
class DspStemSeparator @Inject constructor() : StemSeparator {

    override val description: String =
        "On-device DSP separation — harmonic/percussive split with centre extraction. " +
            "Fast and offline, but not as clean as a trained model on dense mixes."

    override fun availableStems(input: SampleEdits.Buffer): Set<Stem> =
        if (input.right != null) {
            // Four, never six. Median filtering separates percussive from
            // harmonic and panning separates centred from wide; neither can
            // tell a guitar from a piano, because nothing about that
            // distinction is spectral position or stereo placement. Claiming
            // those two and returning something plausible-looking would be
            // worse than not offering them.
            Stem.FOUR
        } else {
            // Centre extraction needs two channels to compare. On mono there is
            // no panning information at all, so a vocal stem would just be
            // "everything tonal" wearing a vocal label.
            setOf(Stem.DRUMS, Stem.BASS, Stem.OTHER)
        }

    override suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        quality: StemQuality,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer> {
        if (input.frames < MIN_FRAMES) return emptyMap()

        val fftSize = when (quality) {
            StemQuality.FAST -> 1024
            StemQuality.BALANCED -> 2048
            StemQuality.HIGH -> 4096
        }
        val kernel = when (quality) {
            StemQuality.FAST -> 9
            StemQuality.BALANCED -> 17
            StemQuality.HIGH -> 31
        }
        val stft = Stft(fftSize, fftSize / 4)
        val wanted = requested intersect availableStems(input)
        if (wanted.isEmpty()) return emptyMap()

        onProgress(StemProgress(0.02f, "Analysing audio"))
        val leftSpec = stft.analyze(input.left)
        currentCoroutineContext().ensureActive()
        val rightSpec = input.right?.let {
            onProgress(StemProgress(0.12f, "Analysing audio"))
            stft.analyze(it)
        }
        currentCoroutineContext().ensureActive()

        // The split is decided from the mono sum. Deciding per channel would
        // let a bin be called percussive on the left and harmonic on the right,
        // which pulls a centred sound apart into two different stems and
        // sounds like phasing.
        onProgress(StemProgress(0.22f, "Finding percussive content"))
        val magnitude = monoMagnitude(leftSpec, rightSpec)
        val harmonic = medianOverTime(magnitude, kernel)
        currentCoroutineContext().ensureActive()
        onProgress(StemProgress(0.45f, "Finding harmonic content"))
        val percussive = medianOverFrequency(magnitude, kernel)
        currentCoroutineContext().ensureActive()

        onProgress(StemProgress(0.62f, "Building masks"))
        val frames = leftSpec.frames
        val bins = leftSpec.bins
        val binHz = input.sampleRate.toFloat() / fftSize

        // Wiener-style soft masks. Squaring before the ratio sharpens the
        // decision without the hard on/off of a binary mask, which is what
        // produces the "underwater" warble people associate with cheap
        // separation.
        val percussiveMask = Array(frames) { f ->
            FloatArray(bins) { b ->
                val h = harmonic[f][b] * harmonic[f][b]
                val p = percussive[f][b] * percussive[f][b]
                val total = h + p
                if (total < 1e-12f) 0f else p / total
            }
        }

        val centre = if (rightSpec != null) centreness(leftSpec, rightSpec) else null

        val result = LinkedHashMap<Stem, SampleEdits.Buffer>()
        var done = 0
        val steps = wanted.size.coerceAtLeast(1)

        for (stem in Stem.FOUR) {
            if (stem !in wanted) continue
            currentCoroutineContext().ensureActive()
            onProgress(
                StemProgress(
                    0.65f + 0.33f * (done.toFloat() / steps),
                    "Separating ${stem.label.lowercase()}",
                ),
            )

            val mask = Array(frames) { f ->
                FloatArray(bins) { b ->
                    val percussivePart = percussiveMask[f][b]
                    val harmonicPart = 1f - percussivePart
                    val bassWeight = bassWeight(b * binHz)
                    val central = centre?.get(f)?.get(b) ?: 1f
                    when (stem) {
                        Stem.DRUMS -> percussivePart
                        Stem.BASS -> harmonicPart * bassWeight
                        Stem.VOCALS -> harmonicPart * (1f - bassWeight) * central
                        // Unreachable: availableStems never offers these, so
                        // `wanted` cannot contain them. Spelled out rather than
                        // left to an else so that adding a seventh stem fails
                        // here at compile time instead of silently producing
                        // silence.
                        Stem.GUITAR, Stem.PIANO -> 0f
                        Stem.OTHER ->
                            if (centre == null) {
                                // No centre information: everything tonal above
                                // the bass band lands here rather than in a
                                // vocal stem that would be a lie.
                                harmonicPart * (1f - bassWeight)
                            } else {
                                harmonicPart * (1f - bassWeight) * (1f - central)
                            }
                    }
                }
            }

            result[stem] = SampleEdits.Buffer(
                left = applyMask(stft, leftSpec, mask, input.frames),
                right = rightSpec?.let { applyMask(stft, it, mask, input.frames) },
                sampleRate = input.sampleRate,
            )
            done += 1
        }

        onProgress(StemProgress(1f, "Done"))
        return result
    }

    // ── analysis ────────────────────────────────────────────────────────

    /** Magnitude of the mono sum, which is what the split is decided from. */
    private fun monoMagnitude(
        left: Stft.Spectrogram,
        right: Stft.Spectrogram?,
    ): Array<FloatArray> = Array(left.frames) { f ->
        FloatArray(left.bins) { b ->
            if (right == null) {
                sqrt(left.re[f][b] * left.re[f][b] + left.im[f][b] * left.im[f][b])
            } else {
                val re = (left.re[f][b] + right.re[f][b]) * 0.5f
                val im = (left.im[f][b] + right.im[f][b]) * 0.5f
                sqrt(re * re + im * im)
            }
        }
    }

    /**
     * How centred each bin is, from 0 (hard-panned) to 1 (dead centre).
     *
     * Compares the mid and side magnitudes: a sound present equally in both
     * channels cancels in the side signal, so a small side relative to mid
     * means central. Squared at the end to bias the mask toward strongly
     * centred content, which keeps the vocal stem from collecting every
     * slightly-centred pad in the mix.
     */
    private fun centreness(
        left: Stft.Spectrogram,
        right: Stft.Spectrogram,
    ): Array<FloatArray> = Array(left.frames) { f ->
        FloatArray(left.bins) { b ->
            val midRe = (left.re[f][b] + right.re[f][b]) * 0.5f
            val midIm = (left.im[f][b] + right.im[f][b]) * 0.5f
            val sideRe = (left.re[f][b] - right.re[f][b]) * 0.5f
            val sideIm = (left.im[f][b] - right.im[f][b]) * 0.5f
            val mid = sqrt(midRe * midRe + midIm * midIm)
            val side = sqrt(sideRe * sideRe + sideIm * sideIm)
            val total = mid + side
            if (total < 1e-9f) 0f else {
                val ratio = mid / total
                ratio * ratio
            }
        }
    }

    /** 1 below [BASS_LOW_HZ], 0 above [BASS_HIGH_HZ], smooth in between. */
    private fun bassWeight(hz: Float): Float = when {
        hz <= BASS_LOW_HZ -> 1f
        hz >= BASS_HIGH_HZ -> 0f
        else -> {
            // Smoothstep rather than a straight line: a linear crossover leaves
            // an audible corner where the bass stem hands over.
            val t = (BASS_HIGH_HZ - hz) / (BASS_HIGH_HZ - BASS_LOW_HZ)
            t * t * (3f - 2f * t)
        }
    }

    /** Median along the time axis — suppresses transients, leaves tones. */
    private fun medianOverTime(source: Array<FloatArray>, kernel: Int): Array<FloatArray> {
        val frames = source.size
        if (frames == 0) return source
        val bins = source[0].size
        val half = kernel / 2
        val scratch = FloatArray(kernel)
        return Array(frames) { f ->
            FloatArray(bins) { b ->
                var count = 0
                for (k in -half..half) {
                    val index = f + k
                    if (index in 0 until frames) scratch[count++] = source[index][b]
                }
                median(scratch, count)
            }
        }
    }

    /** Median along the frequency axis — suppresses tones, leaves transients. */
    private fun medianOverFrequency(source: Array<FloatArray>, kernel: Int): Array<FloatArray> {
        val frames = source.size
        if (frames == 0) return source
        val bins = source[0].size
        val half = kernel / 2
        val scratch = FloatArray(kernel)
        return Array(frames) { f ->
            val row = source[f]
            FloatArray(bins) { b ->
                var count = 0
                for (k in -half..half) {
                    val index = b + k
                    if (index in 0 until bins) scratch[count++] = row[index]
                }
                median(scratch, count)
            }
        }
    }

    /**
     * Median of the first [count] entries of [scratch].
     *
     * Insertion sort on a shared, reused array: the kernels here are 9 to 31
     * elements, where insertion sort beats anything cleverer, and reusing the
     * array keeps a two-million-iteration loop from allocating.
     */
    private fun median(scratch: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        for (i in 1 until count) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j -= 1
            }
            scratch[j + 1] = value
        }
        return if (count % 2 == 1) {
            scratch[count / 2]
        } else {
            (scratch[count / 2 - 1] + scratch[count / 2]) * 0.5f
        }
    }

    /** Multiplies a spectrum by [mask] and inverts it back to samples. */
    private fun applyMask(
        stft: Stft,
        spec: Stft.Spectrogram,
        mask: Array<FloatArray>,
        outputLength: Int,
    ): FloatArray {
        val re = Array(spec.frames) { f ->
            FloatArray(spec.bins) { b -> spec.re[f][b] * mask[f][b] }
        }
        val im = Array(spec.frames) { f ->
            FloatArray(spec.bins) { b -> spec.im[f][b] * mask[f][b] }
        }
        return stft.synthesize(
            Stft.Spectrogram(re, im, spec.frames, spec.bins, spec.pad, spec.paddedLength),
            outputLength,
        )
    }

    companion object {
        /** Below roughly a tenth of a second there is nothing to analyse. */
        const val MIN_FRAMES = 4096

        /** Everything below this is bass, whatever else it is. */
        const val BASS_LOW_HZ = 120f

        /** Nothing above this counts as bass. */
        const val BASS_HIGH_HZ = 320f

        /** Peak of a stem, exposed for the mixer's meters. */
        fun peak(buffer: SampleEdits.Buffer): Float {
            var peak = 0f
            for (v in buffer.left) peak = maxOf(peak, abs(v))
            buffer.right?.let { for (v in it) peak = maxOf(peak, abs(v)) }
            return peak
        }
    }
}
