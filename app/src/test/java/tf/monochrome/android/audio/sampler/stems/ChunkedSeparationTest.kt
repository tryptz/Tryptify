package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import kotlin.math.abs
import kotlin.random.Random

/**
 * The seam tests.
 *
 * Chunked inference is where long-track separation goes wrong, and it goes
 * wrong quietly: the stems are all there, the SDR looks fine, and every few
 * seconds there is a click or a level bump that no metric was watching for.
 *
 * So the central test here does not measure a seam, it proves there cannot be
 * one. Run an identity separator — one that hands back exactly what it was
 * given — through the whole windowing path, and the output has to be the input
 * sample for sample. Anything wrong with the weighting, the hop, the
 * normalisation or the edge handling shows up as a difference, and the size of
 * the difference points straight at which.
 */
class ChunkedSeparationTest {

    private fun noise(frames: Int, seed: Int = 1): SampleEdits.Buffer {
        val rng = Random(seed)
        return SampleEdits.Buffer(
            FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * 0.4f },
            FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * 0.4f },
            44100,
        )
    }

    /** Hands back what it was given, as a single stem. */
    private val identity: suspend (SampleEdits.Buffer) -> Map<Stem, SampleEdits.Buffer> =
        { chunk -> mapOf(Stem.VOCALS to chunk) }

    // ── the property everything else rests on ───────────────────────────

    @Test
    fun `an identity separator reproduces the input exactly`() = runBlocking {
        val input = noise(44100)
        val out = ChunkedSeparation.run(
            input = input,
            segmentFrames = 8192,
            stems = setOf(Stem.VOCALS),
            process = identity,
        )
        val got = out.getValue(Stem.VOCALS)
        assertEquals(input.frames, got.frames)

        var worst = 0f
        for (i in 0 until input.frames) {
            worst = maxOf(worst, abs(got.left[i] - input.left[i]))
            worst = maxOf(worst, abs(got.right!![i] - input.right!![i]))
        }
        // Float32 epsilon, not a tolerance for a sloppy overlap-add.
        assertTrue("worst sample error was $worst", worst < 1e-5f)
    }

    /**
     * The same, across the shapes that actually break windowing code: tracks
     * shorter than one window, tracks a hair longer than a whole number of
     * windows, and overlaps from none to heavy.
     */
    @Test
    fun `identity holds for every length and overlap`() = runBlocking {
        val lengths = listOf(1, 2, 999, 8192, 8193, 44100, 44101)
        val segments = listOf(256, 1024, 8192)
        val overlaps = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f)

        for (frames in lengths) {
            for (segment in segments) {
                for (overlap in overlaps) {
                    val input = noise(frames, seed = frames + segment)
                    val got = ChunkedSeparation.run(
                        input = input,
                        segmentFrames = segment,
                        stems = setOf(Stem.VOCALS),
                        overlap = overlap,
                        process = identity,
                    ).getValue(Stem.VOCALS)

                    var worst = 0f
                    for (i in 0 until frames) {
                        worst = maxOf(worst, abs(got.left[i] - input.left[i]))
                    }
                    assertTrue(
                        "frames=$frames segment=$segment overlap=$overlap error=$worst",
                        worst < 1e-5f,
                    )
                }
            }
        }
    }

    /**
     * A constant signal is the clearest possible seam detector: any gain ripple
     * at a window boundary shows up as a step in a line that should be flat.
     */
    @Test
    fun `a constant signal comes back flat`() = runBlocking {
        val frames = 50000
        val input = SampleEdits.Buffer(
            FloatArray(frames) { 0.5f },
            FloatArray(frames) { 0.5f },
            44100,
        )
        val got = ChunkedSeparation.run(
            input = input,
            segmentFrames = 1024,
            stems = setOf(Stem.VOCALS),
            process = identity,
        ).getValue(Stem.VOCALS)

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in got.left) {
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        assertTrue("ripple ${hi - lo} between $lo and $hi", hi - lo < 1e-5f)
        assertEquals(0.5f, lo, 1e-5f)
    }

    /** A sharper crossfade must not change the gain, only where it happens. */
    @Test
    fun `transition power does not affect unity gain`() = runBlocking {
        val input = noise(30000)
        for (power in listOf(0.5f, 1f, 2f, 4f)) {
            val got = ChunkedSeparation.run(
                input = input,
                segmentFrames = 2048,
                stems = setOf(Stem.VOCALS),
                transitionPower = power,
                process = identity,
            ).getValue(Stem.VOCALS)
            var worst = 0f
            for (i in 0 until input.frames) {
                worst = maxOf(worst, abs(got.left[i] - input.left[i]))
            }
            assertTrue("power=$power error=$worst", worst < 1e-5f)
        }
    }

    // ── stereo ──────────────────────────────────────────────────────────

    /**
     * Windowing must not touch the image. If the two channels were ever
     * weighted or normalised differently the picture would move at every seam,
     * which is exactly the failure the brief calls out.
     */
    @Test
    fun `the stereo image survives windowing`() = runBlocking {
        val frames = 44100
        val rng = Random(11)
        val left = FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * 0.4f }
        // A fixed inter-channel delay is a fixed position in the field.
        val right = FloatArray(frames)
        for (i in 24 until frames) right[i] = left[i - 24] * 0.8f
        val input = SampleEdits.Buffer(left, right, 44100)

        val got = ChunkedSeparation.run(
            input = input,
            segmentFrames = 4096,
            stems = setOf(Stem.VOCALS),
            process = identity,
        ).getValue(Stem.VOCALS)

        val drift = SpatialMetrics.drift(input, got)
        assertEquals(0, drift.itdDeltaSamples)
        assertTrue("width moved by ${drift.widthDelta}", abs(drift.widthDelta) < 1e-4f)
        assertTrue("balance moved by ${drift.balanceDeltaDb}", abs(drift.balanceDeltaDb) < 0.01f)
    }

    /** Mono in, mono out — not a silent right channel and not a fake width. */
    @Test
    fun `a mono source stays centred`() = runBlocking {
        val frames = 20000
        val rng = Random(3)
        val mono = FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * 0.3f }
        val input = SampleEdits.Buffer(mono, null, 44100)

        val got = ChunkedSeparation.run(
            input = input,
            segmentFrames = 2048,
            stems = setOf(Stem.VOCALS),
            process = identity,
        ).getValue(Stem.VOCALS)

        for (i in 0 until frames) {
            assertEquals("frame $i", got.left[i], got.right!![i], 1e-6f)
        }
    }

    // ── the windowing arithmetic ────────────────────────────────────────

    @Test
    fun `every sample is covered by at least one window`() {
        for (frames in listOf(1, 5, 1000, 8192, 8193, 100000)) {
            for (segment in listOf(64, 1024, 8192)) {
                for (overlap in listOf(0f, 0.25f, 0.75f)) {
                    val plan = ChunkedSeparation.plan(frames, segment, overlap)
                    val covered = BooleanArray(frames)
                    for (w in plan.windows) {
                        for (i in 0 until w.validFrames) covered[w.start + i] = true
                    }
                    val gaps = covered.count { !it }
                    assertEquals("frames=$frames segment=$segment overlap=$overlap", 0, gaps)
                }
            }
        }
    }

    /** No window may claim frames the track does not have. */
    @Test
    fun `windows stay inside the track`() {
        val plan = ChunkedSeparation.plan(1000, 256, 0.25f)
        for (w in plan.windows) {
            assertTrue(w.start >= 0)
            assertTrue("window ${w.index} ran past the end", w.start + w.validFrames <= 1000)
        }
    }

    @Test
    fun `an empty track produces no windows and no stems`() = runBlocking {
        assertTrue(ChunkedSeparation.plan(0, 1024, 0.25f).windows.isEmpty())
        val out = ChunkedSeparation.run(
            input = SampleEdits.Buffer(FloatArray(0), null, 44100),
            segmentFrames = 1024,
            stems = setOf(Stem.VOCALS),
            process = identity,
        )
        assertTrue(out.isEmpty())
    }

    /**
     * A pathological overlap must not stall. Anything at or above 1.0 would
     * make the hop zero and the planner loop forever, which is a hang rather
     * than a wrong answer and therefore worse.
     */
    @Test
    fun `an extreme overlap still terminates`() {
        val plan = ChunkedSeparation.plan(10000, 1024, 5f)
        assertTrue(plan.hopFrames >= 1)
        assertTrue(plan.windows.isNotEmpty())
        assertTrue(plan.windows.size < 10000)
    }

    // ── weights ─────────────────────────────────────────────────────────

    /**
     * The ramp must never touch zero. A window whose edge weight is exactly
     * zero contributes nothing there, and on a track short enough to be one
     * window that means dividing by zero instead of returning the audio.
     */
    @Test
    fun `the weight ramp is positive everywhere and peaks in the middle`() {
        val w = ChunkedSeparation.triangularWeights(1024)
        assertEquals(1024, w.size)
        for (i in w.indices) assertTrue("weight $i was ${w[i]}", w[i] > 0f)
        assertEquals(1f, w.max(), 1e-6f)
        // Rises then falls.
        assertTrue(w[512] > w[0])
        assertTrue(w[512] > w[1023])
        for (i in 1 until 512) assertTrue("not rising at $i", w[i] >= w[i - 1])
        for (i in 513 until 1024) assertTrue("not falling at $i", w[i] <= w[i - 1])
    }

    /** A single-window track is the case where edge weighting has no partner. */
    @Test
    fun `a track shorter than one window comes back untouched`() = runBlocking {
        val input = noise(500)
        val got = ChunkedSeparation.run(
            input = input,
            segmentFrames = 8192,
            stems = setOf(Stem.VOCALS),
            process = identity,
        ).getValue(Stem.VOCALS)

        assertEquals(500, got.frames)
        for (i in 0 until 500) assertEquals("frame $i", input.left[i], got.left[i], 1e-5f)
    }

    // ── contract ────────────────────────────────────────────────────────

    /** The model always sees a full window, because QNN has no dynamic shapes. */
    @Test
    fun `every chunk handed to the backend is exactly one segment long`() = runBlocking {
        val seen = mutableListOf<Int>()
        ChunkedSeparation.run(
            input = noise(10000),
            segmentFrames = 4096,
            stems = setOf(Stem.VOCALS),
        ) { chunk ->
            seen += chunk.frames
            mapOf(Stem.VOCALS to chunk)
        }
        assertTrue(seen.isNotEmpty())
        assertTrue("chunk sizes were $seen", seen.all { it == 4096 })
    }

    /** The padding past the end of the track must be silence, not stale audio. */
    @Test
    fun `the tail window is zero padded`() = runBlocking {
        val frames = 5000
        val input = SampleEdits.Buffer(FloatArray(frames) { 1f }, null, 44100)
        var lastChunk: FloatArray? = null
        ChunkedSeparation.run(
            input = input,
            segmentFrames = 4096,
            stems = setOf(Stem.VOCALS),
            overlap = 0f,
        ) { chunk ->
            lastChunk = chunk.left
            mapOf(Stem.VOCALS to chunk)
        }
        val tail = requireNotNull(lastChunk)
        // 5000 frames, 4096 segment, no overlap: the second window starts at
        // 4096 and has 904 real frames followed by padding.
        assertEquals(1f, tail[0], 0f)
        assertEquals(1f, tail[903], 0f)
        assertEquals(0f, tail[904], 0f)
        assertEquals(0f, tail[4095], 0f)
    }

    /** A stem the backend could not find is silence, not a crash. */
    @Test
    fun `a stem the backend omits comes back silent`() = runBlocking {
        val out = ChunkedSeparation.run(
            input = noise(9000),
            segmentFrames = 2048,
            stems = setOf(Stem.VOCALS, Stem.DRUMS),
        ) { chunk -> mapOf(Stem.VOCALS to chunk) }

        assertTrue(out.containsKey(Stem.DRUMS))
        val drums = out.getValue(Stem.DRUMS)
        assertEquals(9000, drums.frames)
        assertTrue(drums.left.all { it == 0f })
    }

    @Test
    fun `progress runs from above zero to one`() = runBlocking {
        val seen = mutableListOf<Float>()
        ChunkedSeparation.run(
            input = noise(40000),
            segmentFrames = 4096,
            stems = setOf(Stem.VOCALS),
            onProgress = { seen += it },
            process = identity,
        )
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.first() > 0f)
        assertEquals(1f, seen.last(), 1e-6f)
        // Never goes backwards.
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
    }
}
