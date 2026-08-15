package tf.monochrome.android.audio.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The post-capture edits. */
class SampleEditsTest {

    private fun buffer(frames: Int, value: Float = 0.5f, stereo: Boolean = false) =
        SampleEdits.Buffer(
            FloatArray(frames) { value },
            if (stereo) FloatArray(frames) { -value } else null,
            48_000,
        )

    // ── trim ────────────────────────────────────────────────────────────

    @Test
    fun `trim keeps the selection`() {
        val source = SampleEdits.Buffer(FloatArray(100) { it.toFloat() }, null, 48_000)
        val trimmed = SampleEdits.trim(source, 20, 60)
        assertEquals(40, trimmed.frames)
        assertEquals(20f, trimmed.left[0], 1e-6f)
        assertEquals(59f, trimmed.left[39], 1e-6f)
    }

    @Test
    fun `trim clamps out of range bounds`() {
        val source = buffer(100)
        assertEquals(100, SampleEdits.trim(source, -50, 500).frames)
    }

    @Test
    fun `an inverted or empty selection leaves the buffer alone`() {
        // A trim handle dragged past its partner should do nothing, not
        // produce a zero-length sample the engine then has to defend against.
        val source = buffer(100)
        assertSame(source, SampleEdits.trim(source, 60, 20))
        assertSame(source, SampleEdits.trim(source, 50, 50))
        assertSame(source, SampleEdits.trim(source, 50, 53))
    }

    @Test
    fun `trim keeps both channels in step`() {
        val source = buffer(100, stereo = true)
        val trimmed = SampleEdits.trim(source, 10, 40)
        assertEquals(30, trimmed.frames)
        assertEquals(30, trimmed.right!!.size)
    }

    // ── level ───────────────────────────────────────────────────────────

    @Test
    fun `gain in dB scales as expected`() {
        val source = buffer(8, 0.5f)
        assertEquals(0.25f, SampleEdits.gainDb(source, -6.0206f).left[0], 1e-4f)
        assertEquals(1.0f, SampleEdits.gainDb(source, 6.0206f).left[0], 1e-4f)
        assertSame(source, SampleEdits.gainDb(source, 0f))
    }

    @Test
    fun `peak reads across both channels`() {
        val buffer = SampleEdits.Buffer(
            floatArrayOf(0.1f, -0.2f),
            floatArrayOf(0.9f, 0.3f),
            48_000,
        )
        assertEquals(0.9f, SampleEdits.peak(buffer), 1e-6f)
    }

    @Test
    fun `normalize lifts the peak to the ceiling`() {
        val normalized = SampleEdits.normalize(buffer(64, 0.2f))
        assertEquals(SampleEdits.NORMALIZE_CEILING, SampleEdits.peak(normalized), 1e-4f)
    }

    @Test
    fun `normalize brings a hot buffer down as well as up`() {
        val hot = SampleEdits.Buffer(floatArrayOf(0.1f, 4f, -0.2f), null, 48_000)
        assertEquals(SampleEdits.NORMALIZE_CEILING, SampleEdits.peak(SampleEdits.normalize(hot)), 1e-4f)
    }

    @Test
    fun `normalize leaves silence alone`() {
        // Otherwise it multiplies the noise floor by an enormous factor and
        // hands the user a hiss where they expected a sound.
        val silent = buffer(64, 0f)
        assertSame(silent, SampleEdits.normalize(silent))

        val nearlySilent = buffer(64, 1e-7f)
        assertSame(nearlySilent, SampleEdits.normalize(nearlySilent))
    }

    // ── fades ───────────────────────────────────────────────────────────

    @Test
    fun `fade in ramps from zero`() {
        val faded = SampleEdits.fadeIn(buffer(4800, 1f), 10f)  // 480 frames
        assertEquals(0f, faded.left[0], 1e-6f)
        assertTrue(faded.left[240] > 0.4f && faded.left[240] < 0.6f)
        assertEquals(1f, faded.left[480], 1e-6f)
        assertEquals(1f, faded.left[4799], 1e-6f)
    }

    @Test
    fun `fade out ramps to zero at the end`() {
        val faded = SampleEdits.fadeOut(buffer(4800, 1f), 10f)
        assertEquals(1f, faded.left[0], 1e-6f)
        assertTrue(faded.left[4799] < 0.01f)
    }

    @Test
    fun `a zero length fade is a no-op`() {
        val source = buffer(100)
        assertSame(source, SampleEdits.fadeIn(source, 0f))
        assertSame(source, SampleEdits.fadeOut(source, -5f))
    }

    @Test
    fun `a fade longer than the sample does not run off the end`() {
        val faded = SampleEdits.fadeIn(buffer(100, 1f), 10_000f)
        assertEquals(100, faded.frames)
        assertEquals(0f, faded.left[0], 1e-6f)
    }

    @Test
    fun `fades touch both channels`() {
        val faded = SampleEdits.fadeIn(buffer(4800, 1f, stereo = true), 10f)
        assertEquals(0f, faded.right!![0], 1e-6f)
    }

    // ── other operations ────────────────────────────────────────────────

    @Test
    fun `reverse flips both channels`() {
        val source = SampleEdits.Buffer(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(4f, 5f, 6f),
            48_000,
        )
        val reversed = SampleEdits.reverse(source)
        assertEquals(3f, reversed.left[0], 1e-6f)
        assertEquals(1f, reversed.left[2], 1e-6f)
        assertEquals(6f, reversed.right!![0], 1e-6f)
    }

    @Test
    fun `mono sums the pair and halves the file`() {
        val stereo = SampleEdits.Buffer(
            floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f),
            48_000,
        )
        val mono = SampleEdits.toMono(stereo)
        assertNull(mono.right)
        assertEquals(0.5f, mono.left[0], 1e-6f)
        assertEquals(0.5f, mono.left[1], 1e-6f)

        val alreadyMono = buffer(10)
        assertSame(alreadyMono, SampleEdits.toMono(alreadyMono))
    }

    @Test
    fun `onset detection finds the transient past leading silence`() {
        val samples = FloatArray(1000)
        for (i in 400 until 1000) samples[i] = 0.6f
        val onset = SampleEdits.firstOnset(SampleEdits.Buffer(samples, null, 48_000))
        assertEquals(400, onset)
    }

    @Test
    fun `onset detection returns zero when nothing crosses the threshold`() {
        // Leaving the sample as recorded is the right answer for audio that
        // is quiet throughout — snapping to an arbitrary point would be worse.
        assertEquals(0, SampleEdits.firstOnset(buffer(500, 0.001f)))
    }

    // ── peaks ───────────────────────────────────────────────────────────

    @Test
    fun `peaks produce min max pairs`() {
        val samples = FloatArray(1024) { if (it % 2 == 0) 0.8f else -0.6f }
        val peaks = SampleEdits.peaks(SampleEdits.Buffer(samples, null, 48_000), buckets = 64)
        assertEquals(128, peaks.size)
        for (b in 0 until 64) {
            assertEquals("min $b", -0.6f, peaks[b * 2], 1e-5f)
            assertEquals("max $b", 0.8f, peaks[b * 2 + 1], 1e-5f)
        }
    }

    @Test
    fun `peaks keep a lone transient rather than averaging it away`() {
        // The whole reason this is min/max and not RMS: a single spike is the
        // thing the user is looking at the waveform to find.
        val samples = FloatArray(4096)
        samples[2000] = 1f
        val peaks = SampleEdits.peaks(SampleEdits.Buffer(samples, null, 48_000), buckets = 64)
        assertTrue("the spike survives", peaks.any { abs(it) > 0.9f })
    }

    @Test
    fun `peaks handle an empty buffer and clamp the bucket count`() {
        val empty = SampleEdits.Buffer(FloatArray(0), null, 48_000)
        assertEquals(2 * 8, SampleEdits.peaks(empty, buckets = 1).size)
        assertNotNull(SampleEdits.peaks(buffer(10), buckets = 100_000))
    }

    @Test
    fun `duration is derived from frames and rate`() {
        assertEquals(1000L, SampleEdits.Buffer(FloatArray(48_000), null, 48_000).durationMs)
        assertEquals(0L, SampleEdits.Buffer(FloatArray(100), null, 0).durationMs)
    }
}
