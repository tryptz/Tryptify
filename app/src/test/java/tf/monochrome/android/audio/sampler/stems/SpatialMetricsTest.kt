package tf.monochrome.android.audio.sampler.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import kotlin.math.abs
import kotlin.random.Random

/**
 * Known-answer tests for the spatial measurements.
 *
 * A metric you have not checked against a signal whose answer you already know
 * is not a measurement, it is a number. So every case here is built to have one
 * correct result: a mono signal is correlation 1, a hard-panned one is balanced
 * to one side, a delayed channel has exactly the delay it was given.
 *
 * These matter more than usual because the brief ranks spatial fidelity second
 * only to audio quality, and because the published evidence says every
 * separator degrades it. A test suite that cannot detect a collapsed image
 * would let that happen silently.
 */
class SpatialMetricsTest {

    private fun noise(frames: Int, seed: Int = 1, amplitude: Float = 0.4f) =
        Random(seed).let { rng -> FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * amplitude } }

    private fun stereo(l: FloatArray, r: FloatArray?) = SampleEdits.Buffer(l, r, 44100)

    // ── the anchors ─────────────────────────────────────────────────────

    @Test
    fun `identical channels read as mono`() {
        val n = noise(20000)
        val image = SpatialMetrics.measure(stereo(n, n.copyOf()))
        assertEquals(1f, image.correlation, 1e-4f)
        assertEquals(0f, image.width, 1e-4f)
        assertEquals(0f, image.balanceDb, 0.01f)
        assertTrue(image.isMono)
    }

    @Test
    fun `a mono buffer reads as mono`() {
        val image = SpatialMetrics.measure(stereo(noise(10000), null))
        assertEquals(1f, image.correlation, 1e-4f)
        assertTrue(image.isMono)
    }

    @Test
    fun `independent channels read as wide`() {
        val image = SpatialMetrics.measure(stereo(noise(40000, 1), noise(40000, 2)))
        assertTrue("correlation was ${image.correlation}", abs(image.correlation) < 0.05f)
        assertTrue("width was ${image.width}", image.width > 0.95f)
        assertFalse(image.isMono)
    }

    /**
     * Out-of-phase channels are the case a naive `width = 1 - correlation`
     * would score as *more* than fully wide. They are not wider than
     * uncorrelated, they are a mono signal that cancels — and cancellation is
     * a different problem from width.
     */
    @Test
    fun `an out of phase pair is not scored as extra wide`() {
        val n = noise(20000)
        val image = SpatialMetrics.measure(stereo(n, FloatArray(n.size) { -n[it] }))
        assertEquals(-1f, image.correlation, 1e-4f)
        assertEquals(0f, image.width, 1e-4f)
        assertTrue(image.width <= 1f)
    }

    /**
     * Silence is not wide. Without this, a backend that produced nothing at all
     * would score as having perfectly preserved a wide image.
     */
    @Test
    fun `silence is not wide`() {
        val image = SpatialMetrics.measure(stereo(FloatArray(5000), FloatArray(5000)))
        assertEquals(1f, image.correlation, 0f)
        assertEquals(0f, image.width, 0f)
        assertTrue(image.isMono)
    }

    /**
     * A hard-panned source is the opposite case, and has to score the opposite
     * way: fully decorrelated, with the balance saying which side.
     */
    @Test
    fun `a hard panned source is decorrelated and off balance`() {
        val image = SpatialMetrics.measure(stereo(noise(10000), FloatArray(10000)))
        assertEquals(0f, image.correlation, 1e-4f)
        assertEquals(1f, image.width, 1e-4f)
        assertTrue("balance was ${image.balanceDb}", image.balanceDb < -60f)
    }

    // ── level and time cues ─────────────────────────────────────────────

    @Test
    fun `balance reports the louder side in dB`() {
        val n = noise(20000)
        // Right at half amplitude is -6 dB, so balance is -6.
        val quieter = FloatArray(n.size) { n[it] * 0.5f }
        val image = SpatialMetrics.measure(stereo(n, quieter))
        assertEquals(-6.02f, image.balanceDb, 0.1f)

        val louder = FloatArray(n.size) { n[it] * 2f }
        assertEquals(6.02f, SpatialMetrics.measure(stereo(n, louder)).balanceDb, 0.1f)
    }

    /**
     * The inter-channel delay is the cue that puts a source left or right, and
     * the measurement has to recover it exactly — a couple of samples out is an
     * audible shift in apparent position.
     */
    @Test
    fun `the inter channel delay is recovered exactly`() {
        val frames = 40000
        val left = noise(frames, seed = 5)
        for (delay in listOf(0, 1, 7, 24, 50)) {
            val right = FloatArray(frames)
            for (i in delay until frames) right[i] = left[i - delay] * 0.9f
            val image = SpatialMetrics.measure(stereo(left, right))
            assertEquals("delay $delay", delay, image.itdSamples)
        }
    }

    /** A source on the right shows up as a negative lag. */
    @Test
    fun `the delay is signed by which side leads`() {
        val frames = 40000
        val source = noise(frames, seed = 9)
        val left = FloatArray(frames)
        for (i in 30 until frames) left[i] = source[i - 30]
        val image = SpatialMetrics.measure(stereo(left, source))
        assertEquals(-30, image.itdSamples)
    }

    // ── drift ───────────────────────────────────────────────────────────

    @Test
    fun `an untouched signal has no drift`() {
        val buffer = stereo(noise(20000, 1), noise(20000, 2))
        val drift = SpatialMetrics.drift(buffer, buffer)
        assertEquals(0f, drift.widthDelta, 0f)
        assertEquals(0f, drift.correlationDelta, 0f)
        assertEquals(0, drift.itdDeltaSamples)
        assertEquals(0f, drift.score, 1e-6f)
    }

    /**
     * The test that gives the suite its point: collapsing a wide mix to the
     * middle has to score badly, and score worse than a small level change.
     * If those two came out the same, the score would be useless for deciding
     * whether a backend is acceptable.
     */
    @Test
    fun `collapsing to mono scores far worse than a level trim`() {
        val left = noise(30000, 1)
        val right = noise(30000, 2)
        val original = stereo(left, right)

        val mid = FloatArray(left.size) { (left[it] + right[it]) * 0.5f }
        val collapsed = SpatialMetrics.drift(original, stereo(mid, mid.copyOf()))

        val trimmed = stereo(
            FloatArray(left.size) { left[it] * 0.9f },
            FloatArray(right.size) { right[it] * 0.9f },
        )
        val quieter = SpatialMetrics.drift(original, trimmed)

        assertTrue("collapse scored ${collapsed.score}", collapsed.score > 1.5f)
        assertTrue("trim scored ${quieter.score}", quieter.score < 0.2f)
        assertTrue(collapsed.score > quieter.score * 5)
    }

    /** Processing one channel independently moves the image, and must show it. */
    @Test
    fun `treating the channels independently is detected`() {
        val frames = 40000
        val left = noise(frames, seed = 4)
        val right = FloatArray(frames)
        for (i in 20 until frames) right[i] = left[i - 20] * 0.85f
        val original = stereo(left, right)

        // The failure mode BSRNN warns about: each channel spliced on its own,
        // so the delay that placed the source no longer holds.
        val shifted = FloatArray(frames)
        for (i in 45 until frames) shifted[i] = left[i - 45] * 0.85f
        val drift = SpatialMetrics.drift(original, stereo(left, shifted))

        assertEquals(25, drift.itdDeltaSamples)
        assertTrue("score was ${drift.score}", drift.score > 1f)
    }

    // ── reconstruction ──────────────────────────────────────────────────

    /**
     * Stems that were split by subtraction have to add back up exactly. This
     * is the property behind the residual approach, and the reason it protects
     * the stereo image: whatever is not extracted keeps the record's own phase.
     */
    @Test
    fun `a residual split reconstructs the mix exactly`() {
        val frames = 30000
        val mixL = noise(frames, 1)
        val mixR = noise(frames, 2)
        val mix = stereo(mixL, mixR)

        val vocalL = noise(frames, 3, amplitude = 0.15f)
        val vocalR = noise(frames, 4, amplitude = 0.15f)
        val vocals = stereo(vocalL, vocalR)
        val backing = stereo(
            FloatArray(frames) { mixL[it] - vocalL[it] },
            FloatArray(frames) { mixR[it] - vocalR[it] },
        )

        val result = SpatialMetrics.reconstruction(mix, listOf(vocals, backing))
        assertTrue("relative error ${result.relativeError}", result.relativeError < 1e-5f)
        assertTrue("peak error ${result.peakError}", result.peakError < 1e-5f)
        assertEquals(1f, result.energyRatio, 1e-4f)
    }

    /** A separator that drops a stem on the floor has to be caught. */
    @Test
    fun `losing a stem shows up as reconstruction error`() {
        val frames = 20000
        val a = noise(frames, 1)
        val b = noise(frames, 2)
        val mix = stereo(FloatArray(frames) { a[it] + b[it] }, FloatArray(frames) { a[it] + b[it] })

        val onlyOne = listOf(stereo(a, a.copyOf()))
        val result = SpatialMetrics.reconstruction(mix, onlyOne)
        assertTrue("relative error ${result.relativeError}", result.relativeError > 0.5f)
        assertTrue("energy ratio ${result.energyRatio}", result.energyRatio < 0.9f)
    }

    /** Bleed — the same content in two stems — inflates the energy. */
    @Test
    fun `duplicated content shows up as excess energy`() {
        val frames = 20000
        val a = noise(frames, 1)
        val mix = stereo(a, a.copyOf())
        val doubled = listOf(stereo(a, a.copyOf()), stereo(a.copyOf(), a.copyOf()))
        val result = SpatialMetrics.reconstruction(mix, doubled)
        assertTrue("energy ratio ${result.energyRatio}", result.energyRatio > 1.8f)
    }

    @Test
    fun `no stems at all is reported rather than crashing`() {
        val result = SpatialMetrics.reconstruction(stereo(noise(100), null), emptyList())
        assertEquals(0f, result.rmsError, 0f)
    }

    // ── hygiene ─────────────────────────────────────────────────────────

    @Test
    fun `dc offset and clipping are reported`() {
        val frames = 10000
        val biased = FloatArray(frames) { 0.3f }
        val image = SpatialMetrics.measure(stereo(biased, biased.copyOf()))
        assertEquals(0.3f, image.dcL, 1e-5f)
        assertEquals(0.3f, image.dcR, 1e-5f)
        assertFalse(image.clipped)

        val hot = FloatArray(frames) { if (it == 500) 1.5f else 0.2f }
        assertTrue(SpatialMetrics.measure(stereo(hot, hot.copyOf())).clipped)
    }

    @Test
    fun `an empty buffer does not divide by zero`() {
        val image = SpatialMetrics.measure(stereo(FloatArray(0), null))
        assertEquals(0f, image.rmsL, 0f)
        assertEquals(0, image.itdSamples)
    }
}
