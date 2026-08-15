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

/**
 * The selection-based operations behind the wave editor.
 *
 * The recurring theme is that a bad selection must never destroy audio. An
 * inverted one is an ordering mistake, not a request to delete everything; an
 * empty one means "no selection", which every operation treats as a no-op or
 * as the whole buffer, but never as "the first zero samples".
 */
class SampleRangeEditsTest {

    private fun ramp(frames: Int, stereo: Boolean = false) = SampleEdits.Buffer(
        FloatArray(frames) { it.toFloat() },
        if (stereo) FloatArray(frames) { -it.toFloat() } else null,
        48_000,
    )

    // ── range clamping ──────────────────────────────────────────────────

    @Test
    fun `an inverted selection is ordered rather than emptied`() {
        // Handles dragged past each other still describe a region.
        val range = SampleEdits.clampRange(ramp(100), 80, 20)
        assertEquals(20, range.first)
        assertEquals(79, range.last)
    }

    @Test
    fun `a selection outside the buffer is clipped to it`() {
        val range = SampleEdits.clampRange(ramp(100), -40, 400)
        assertEquals(0, range.first)
        assertEquals(99, range.last)
    }

    @Test
    fun `an empty selection stays empty`() {
        assertTrue(SampleEdits.clampRange(ramp(100), 50, 50).isEmpty())
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    fun `delete removes the selection and closes the gap`() {
        val cut = SampleEdits.deleteRange(ramp(100), 10, 20)
        assertEquals(90, cut.frames)
        assertEquals(9f, cut.left[9], 1e-6f)
        // 10..19 are gone, so what followed them now sits at index 10.
        assertEquals(20f, cut.left[10], 1e-6f)
    }

    @Test
    fun `delete keeps both channels aligned`() {
        val cut = SampleEdits.deleteRange(ramp(100, stereo = true), 10, 20)
        assertEquals(90, cut.frames)
        assertEquals(90, cut.right!!.size)
        assertEquals(-20f, cut.right!![10], 1e-6f)
    }

    @Test
    fun `delete refuses to leave less than a playable sample`() {
        // "Delete everything" is what clear would mean; this operation is not
        // it, and returning an unplayable stub would be worse than refusing.
        val source = ramp(20)
        assertSame(source, SampleEdits.deleteRange(source, 0, 20))
        assertSame(source, SampleEdits.deleteRange(source, 0, 19))
    }

    @Test
    fun `delete with no selection changes nothing`() {
        val source = ramp(100)
        assertSame(source, SampleEdits.deleteRange(source, 30, 30))
    }

    // ── in-place range operations ───────────────────────────────────────

    @Test
    fun `silence zeroes only the selection`() {
        val out = SampleEdits.silenceRange(ramp(100), 40, 60)
        assertEquals(100, out.frames)
        assertEquals(39f, out.left[39], 1e-6f)
        assertEquals(0f, out.left[40], 1e-6f)
        assertEquals(0f, out.left[59], 1e-6f)
        assertEquals(60f, out.left[60], 1e-6f)
    }

    @Test
    fun `gain applies to the selection only`() {
        val flat = SampleEdits.Buffer(FloatArray(100) { 0.5f }, null, 48_000)
        val out = SampleEdits.gainRange(flat, 10, 20, -6.0206f)
        assertEquals(0.5f, out.left[9], 1e-4f)
        assertEquals(0.25f, out.left[10], 1e-4f)
        assertEquals(0.25f, out.left[19], 1e-4f)
        assertEquals(0.5f, out.left[20], 1e-4f)
    }

    @Test
    fun `normalize range uses the selection's own peak`() {
        // The loud part outside the selection must not influence the scaling,
        // or "normalize this quiet word" would do nothing.
        val samples = FloatArray(100) { 0.1f }
        samples[90] = 1f
        val out = SampleEdits.normalizeRange(SampleEdits.Buffer(samples, null, 48_000), 0, 50)
        assertEquals(SampleEdits.NORMALIZE_CEILING, out.left[0], 1e-4f)
        assertEquals(1f, out.left[90], 1e-6f)
    }

    @Test
    fun `fades ramp across the selection and leave the rest alone`() {
        val flat = SampleEdits.Buffer(FloatArray(100) { 1f }, null, 48_000)

        val fadedIn = SampleEdits.fadeInRange(flat, 20, 40)
        assertEquals(1f, fadedIn.left[19], 1e-6f)
        assertEquals(0f, fadedIn.left[20], 1e-6f)
        assertEquals(1f, fadedIn.left[39], 1e-6f)
        assertEquals(1f, fadedIn.left[40], 1e-6f)

        val fadedOut = SampleEdits.fadeOutRange(flat, 20, 40)
        assertEquals(1f, fadedOut.left[20], 1e-6f)
        assertEquals(0f, fadedOut.left[39], 1e-6f)
        assertEquals(1f, fadedOut.left[40], 1e-6f)
    }

    @Test
    fun `reverse range flips only the selection`() {
        val out = SampleEdits.reverseRange(ramp(100), 10, 20)
        assertEquals(9f, out.left[9], 1e-6f)
        assertEquals(19f, out.left[10], 1e-6f)
        assertEquals(10f, out.left[19], 1e-6f)
        assertEquals(20f, out.left[20], 1e-6f)
    }

    @Test
    fun `range operations with no selection are no-ops`() {
        val source = ramp(100)
        assertSame(source, SampleEdits.silenceRange(source, 10, 10))
        assertSame(source, SampleEdits.normalizeRange(source, 10, 10))
        assertSame(source, SampleEdits.reverseRange(source, 10, 10))
        assertSame(source, SampleEdits.fadeInRange(source, 10, 10))
        assertSame(source, SampleEdits.gainRange(source, 10, 10, 6f))
    }

    @Test
    fun `peak of range ignores everything outside it`() {
        val samples = FloatArray(100) { 0.2f }
        samples[80] = 0.9f
        val buffer = SampleEdits.Buffer(samples, null, 48_000)
        assertEquals(0.2f, SampleEdits.peakOfRange(buffer, 0, 50), 1e-6f)
        assertEquals(0.9f, SampleEdits.peakOfRange(buffer, 50, 100), 1e-6f)
    }

    // ── zero crossings ──────────────────────────────────────────────────

    @Test
    fun `snap finds the nearest upward zero crossing`() {
        // A slow square: negative for 50, positive for 50. The upward crossing
        // is at the last negative sample.
        val samples = FloatArray(200) { if ((it / 50) % 2 == 0) -0.5f else 0.5f }
        val buffer = SampleEdits.Buffer(samples, null, 48_000)
        assertEquals(49, SampleEdits.snapToZeroCrossing(buffer, 52))
        assertEquals(49, SampleEdits.snapToZeroCrossing(buffer, 45))
        assertEquals(149, SampleEdits.snapToZeroCrossing(buffer, 152))
    }

    @Test
    fun `snap gives up rather than moving a handle a long way`() {
        // A wrong snap is worse than none: silence has no crossings to find,
        // and dragging the handle somewhere arbitrary would be baffling.
        val buffer = SampleEdits.Buffer(FloatArray(1000), null, 48_000)
        assertEquals(500, SampleEdits.snapToZeroCrossing(buffer, 500, search = 32))
    }

    @Test
    fun `snap stays inside the buffer`() {
        val buffer = SampleEdits.Buffer(FloatArray(64) { 0.3f }, null, 48_000)
        val snapped = SampleEdits.snapToZeroCrossing(buffer, 63)
        assertTrue(snapped in 0..63)
    }

    // ── silence trimming ────────────────────────────────────────────────

    @Test
    fun `trim silence crops both ends and keeps a little pad`() {
        val samples = FloatArray(48_000)
        for (i in 20_000 until 30_000) samples[i] = 0.8f
        val out = SampleEdits.trimSilence(SampleEdits.Buffer(samples, null, 48_000))
        // 2 ms of pad each side at 48 kHz is 96 frames.
        assertEquals(10_000 + 192, out.frames)
        // The pad means the first sample is still silence, not the transient —
        // cutting exactly on the attack is what shaves a hit's character off.
        assertEquals(0f, out.left[0], 1e-6f)
        assertEquals(0.8f, out.left[100], 1e-6f)
    }

    @Test
    fun `trim silence leaves a silent buffer alone`() {
        val source = SampleEdits.Buffer(FloatArray(1000), null, 48_000)
        assertSame(source, SampleEdits.trimSilence(source))
    }

    // ── zoomed peaks ────────────────────────────────────────────────────

    @Test
    fun `peaks of a window describe only that window`() {
        val samples = FloatArray(1000)
        for (i in 500 until 600) samples[i] = 1f
        val buffer = SampleEdits.Buffer(samples, null, 48_000)

        val quiet = SampleEdits.peaksOfRange(buffer, 0, 400, buckets = 32)
        assertTrue("the loud part is outside this window", quiet.all { abs(it) < 1e-6f })

        val loud = SampleEdits.peaksOfRange(buffer, 500, 600, buckets = 32)
        assertTrue(loud.any { it > 0.9f })
    }

    @Test
    fun `peaks of a degenerate window are empty rather than a crash`() {
        val buffer = ramp(100)
        assertEquals(16, SampleEdits.peaksOfRange(buffer, 50, 50, buckets = 8).size)
        assertEquals(16, SampleEdits.peaksOfRange(buffer, 80, 20, buckets = 8).size)
    }

    @Test
    fun `a window shorter than the bucket count still fills every bucket`() {
        // Zoomed all the way in, buckets degenerate to single samples; the
        // array still has to be fully populated or the draw walks garbage.
        val peaks = SampleEdits.peaksOfRange(ramp(100), 0, 4, buckets = 64)
        assertEquals(128, peaks.size)
    }
}
