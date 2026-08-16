package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import kotlin.math.abs
import kotlin.math.sin

/**
 * The DSP behind stem separation.
 *
 * These are the assertions that make the feature trustworthy rather than
 * plausible. The transform has to round-trip exactly, the overlap-add has to
 * reconstruct exactly, and the four stems have to add back up to the input —
 * because if they do not, separation is quietly destroying part of the user's
 * sample and no amount of listening to one stem would reveal it.
 */
class FftTest {

    @Test
    fun `forward then inverse is the identity`() {
        val n = 256
        val random = java.util.Random(7)
        val original = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val re = original.copyOf()
        val im = FloatArray(n)

        Fft.transform(re, im, inverse = false)
        Fft.transform(re, im, inverse = true)

        for (i in 0 until n) assertEquals("sample $i", original[i], re[i], 1e-4f)
        for (i in 0 until n) assertEquals("imag $i", 0f, im[i], 1e-4f)
    }

    @Test
    fun `a pure tone lands in one bin`() {
        val n = 128
        val bin = 8
        val re = FloatArray(n) { sin(2.0 * Math.PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        Fft.transform(re, im, inverse = false)

        val magnitudes = FloatArray(n / 2 + 1) { b ->
            kotlin.math.sqrt(re[b] * re[b] + im[b] * im[b])
        }
        val loudest = magnitudes.indices.maxByOrNull { magnitudes[it] }
        assertEquals(bin, loudest)
    }

    @Test
    fun `a non power of two is left alone rather than corrupted`() {
        // Silently producing garbage for a bad size is far worse than doing
        // nothing: every caller here sizes by construction, so reaching this
        // means a bug elsewhere and the data should survive to be debugged.
        val re = floatArrayOf(1f, 2f, 3f)
        val im = FloatArray(3)
        Fft.transform(re, im, inverse = false)
        assertEquals(1f, re[0], 1e-6f)
        assertEquals(3f, re[2], 1e-6f)
    }

    @Test
    fun `power of two rounding`() {
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(1024, Fft.nextPowerOfTwo(1024))
        assertEquals(2048, Fft.nextPowerOfTwo(1025))
    }
}

class StftTest {

    private fun signal(length: Int) = FloatArray(length) {
        (0.6 * sin(2.0 * Math.PI * 5 * it / 128) + 0.3 * sin(2.0 * Math.PI * 23 * it / 128)).toFloat()
    }

    @Test
    fun `analyse then synthesise reconstructs the signal`() {
        // The property the whole separator rests on: if this is not exact,
        // every stem carries a reconstruction artefact before any mask is even
        // applied.
        val stft = Stft(fftSize = 256, hop = 64)
        val source = signal(2000)
        val spec = stft.analyze(source)
        val out = stft.synthesize(spec, source.size)

        assertEquals(source.size, out.size)
        for (i in source.indices) assertEquals("sample $i", source[i], out[i], 1e-3f)
    }

    @Test
    fun `reconstruction is exact at the very edges`() {
        // The first and last frames overlap incompletely, which is exactly
        // where a fixed normalisation constant would leave the signal quiet.
        val stft = Stft(fftSize = 128, hop = 32)
        val source = FloatArray(500) { 0.8f }
        val out = stft.synthesize(stft.analyze(source), source.size)
        assertEquals(0.8f, out[0], 1e-3f)
        assertEquals(0.8f, out[source.size - 1], 1e-3f)
    }

    @Test
    fun `reconstruction holds across sizes and hops`() {
        for (fftSize in listOf(128, 256, 512)) {
            for (divisor in listOf(2, 4, 8)) {
                val stft = Stft(fftSize, fftSize / divisor)
                val source = signal(1500)
                val out = stft.synthesize(stft.analyze(source), source.size)
                var worst = 0f
                for (i in source.indices) worst = maxOf(worst, abs(out[i] - source[i]))
                assertTrue("fft=$fftSize hop=${fftSize / divisor} err=$worst", worst < 1e-2f)
            }
        }
    }

    @Test
    fun `a shorter output length truncates rather than overruns`() {
        val stft = Stft(fftSize = 128, hop = 32)
        val out = stft.synthesize(stft.analyze(signal(1000)), 400)
        assertEquals(400, out.size)
    }

    @Test
    fun `bins are the non-redundant half plus one`() {
        val spec = Stft(fftSize = 256, hop = 64).analyze(signal(1000))
        assertEquals(129, spec.bins)
        assertTrue(spec.frames > 0)
    }
}

class DspStemSeparatorTest {

    private val separator = DspStemSeparator()

    /** A steady tone with periodic clicks: harmonic and percussive by design. */
    private fun mixture(frames: Int = 24_000, stereo: Boolean = true): SampleEdits.Buffer {
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            // 220 Hz tone, panned centre.
            val tone = 0.4f * sin(2.0 * Math.PI * 220.0 * i / 48_000.0).toFloat()
            left[i] = tone
            right[i] = tone
        }
        // Clicks every 6000 frames — broadband, so unmistakably percussive.
        var click = 0
        while (click < frames) {
            for (k in 0 until 40) {
                if (click + k < frames) {
                    val decay = 1f - k / 40f
                    val noise = if (k % 2 == 0) decay else -decay
                    left[click + k] += noise * 0.6f
                    right[click + k] += noise * 0.6f
                }
            }
            click += 6000
        }
        return SampleEdits.Buffer(left, if (stereo) right else null, 48_000)
    }

    @Test
    fun `stereo offers the classic four and mono does not`() {
        // Four, not every stem there is. Guitar and piano exist for six-stem
        // models; median filtering and panning cannot tell one from the other,
        // so this separator does not claim them.
        assertEquals(Stem.FOUR, separator.availableStems(mixture()))
        assertTrue(separator.availableStems(mixture()).none { it in Stem.EXTENDED })

        val mono = separator.availableStems(mixture(stereo = false))
        assertEquals(setOf(Stem.DRUMS, Stem.BASS, Stem.OTHER), mono)
        // A vocal stem from a mono source would be "everything tonal" wearing
        // a vocal label, which is worse than not offering it.
        assertFalse(Stem.VOCALS in mono)
    }

    @Test
    fun `the stems add back up to the input`() = runBlocking {
        // The masks are built to sum to one at every bin, so this is the
        // guarantee that separation loses nothing. A user who saves all four
        // has exactly what they started with.
        val source = mixture()
        val stems = separator.separate(source, quality = StemQuality.FAST)
        assertEquals(4, stems.size)

        var worst = 0f
        for (i in 0 until source.frames) {
            var sum = 0f
            for (stem in stems.values) sum += stem.left[i]
            worst = maxOf(worst, abs(sum - source.left[i]))
        }
        assertTrue("reconstruction error $worst", worst < 2e-2f)
    }

    @Test
    fun `mono stems also add back up`() = runBlocking {
        val source = mixture(stereo = false)
        val stems = separator.separate(source, quality = StemQuality.FAST)
        assertEquals(3, stems.size)

        var worst = 0f
        for (i in 0 until source.frames) {
            var sum = 0f
            for (stem in stems.values) sum += stem.left[i]
            worst = maxOf(worst, abs(sum - source.left[i]))
        }
        assertTrue("reconstruction error $worst", worst < 2e-2f)
    }

    @Test
    fun `clicks land in drums and the tone does not`() = runBlocking {
        val source = mixture()
        val stems = separator.separate(source, quality = StemQuality.BALANCED)
        val drums = stems.getValue(Stem.DRUMS)

        // Energy right at a click, versus energy in the middle of a gap where
        // only the sustained tone is playing.
        val atClick = energy(drums.left, 6000, 6100)
        val betweenClicks = energy(drums.left, 8500, 8600)
        assertTrue(
            "drums should be louder at a transient ($atClick vs $betweenClicks)",
            atClick > betweenClicks * 3f,
        )
    }

    @Test
    fun `every stem keeps the input's shape`() = runBlocking {
        val source = mixture()
        val stems = separator.separate(source, quality = StemQuality.FAST)
        for ((stem, buffer) in stems) {
            assertEquals("$stem length", source.frames, buffer.frames)
            assertEquals("$stem rate", source.sampleRate, buffer.sampleRate)
            assertEquals("$stem channels", 2, buffer.channelCountOrOne())
            for (v in buffer.left) {
                assertFalse("$stem produced NaN", v.isNaN())
                assertFalse("$stem produced infinity", v.isInfinite())
            }
        }
    }

    @Test
    fun `only the requested stems are produced`() = runBlocking {
        val stems = separator.separate(
            mixture(),
            requested = setOf(Stem.DRUMS),
            quality = StemQuality.FAST,
        )
        assertEquals(setOf(Stem.DRUMS), stems.keys)
    }

    @Test
    fun `progress runs from start to finish`() = runBlocking {
        val seen = mutableListOf<Float>()
        separator.separate(mixture(), quality = StemQuality.FAST) { seen.add(it.fraction) }
        assertTrue(seen.isNotEmpty())
        assertTrue("progress never decreases", seen.zipWithNext().all { it.second >= it.first })
        assertEquals(1f, seen.last(), 1e-6f)
    }

    @Test
    fun `something too short to analyse yields nothing rather than noise`() = runBlocking {
        val tiny = SampleEdits.Buffer(FloatArray(512), FloatArray(512), 48_000)
        assertTrue(separator.separate(tiny).isEmpty())
    }

    @Test
    fun `silence separates into silence`() = runBlocking {
        // The mask denominators are all zero here; without their guards this
        // is where NaN would appear and propagate into every saved stem.
        val silent = SampleEdits.Buffer(FloatArray(12_000), FloatArray(12_000), 48_000)
        val stems = separator.separate(silent, quality = StemQuality.FAST)
        for ((stem, buffer) in stems) {
            for (v in buffer.left) {
                assertFalse("$stem produced NaN from silence", v.isNaN())
                assertTrue("$stem should stay silent", abs(v) < 1e-4f)
            }
        }
    }

    private fun energy(samples: FloatArray, from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until minOf(to, samples.size)) sum += samples[i] * samples[i]
        return sum
    }

    private fun SampleEdits.Buffer.channelCountOrOne() = if (right == null) 1 else 2
}
