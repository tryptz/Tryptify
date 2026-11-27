package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.SpectrogramFeed
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.Spectrogram3DSettings

/**
 * The 3D spectrogram's feed and its playhead: the analyzer hands spectra
 * over in batches, a pass's worth at a time, and the picture must still move
 * on by each frame's time with every spectrum in its place, once, in order.
 * One spectrum a frame, stretched over the frame, drew slabs at a fast speed
 * ("mega blocky").
 */
class SpectrogramPlayheadTest {
    private val rate = 44_100

    /** A feed and a writer that publishes, a pass every [passSeconds], every spectrum whose window has ended. */
    private class Stream(val rate: Int, val fft: Int, val hop: Int) {
        val feed = SpectrogramFeed(4).also { it.restart(rate) }
        var made = 0
        fun passUpTo(seconds: Double) {
            val now = (seconds * rate).toLong()
            while (fft + made.toLong() * hop <= now) {
                feed.slot()[0] = made.toFloat()
                feed.publish(fft + made.toLong() * hop)
                made++
            }
        }
    }

    private fun run(frameHz: Int, fft: Int, hop: Int, seconds: Double = 3.0): Triple<List<Int>, List<Float>, Stream> {
        val stream = Stream(rate, fft, hop)
        val playhead = SpectrogramPlayhead(stream.feed)
        val order = ArrayList<Int>()
        val steps = ArrayList<Float>()
        var t = 0.0
        var nextPass = 0.0
        val dt = 1.0 / frameHz
        while (t < seconds) {
            // The analyzer's pass, 60 times a second, whatever the display's rate.
            while (nextPass <= t) { stream.passUpTo(nextPass); nextPass += 1.0 / 60 }
            playhead.advance(dt.toFloat()) { bins, s ->
                order += bins[0].toInt()
                steps += s
            }
            t += dt
        }
        return Triple(order, steps, stream)
    }

    @Test
    fun `every spectrum is handed over once, in order, and the picture moves on by the time that passed`() {
        for (frameHz in listOf(60, 90, 120, 144)) {
            for ((fft, hop) in listOf(4096 to 163, 2048 to 82, 16384 to 655, 1024 to 512)) {
                val (order, steps, stream) = run(frameHz, fft, hop)
                // In order, none twice. A spectrum may be named twice in a row:
                // once as what the picture is moving toward, once as reached.
                for (i in 1 until order.size) assertTrue("$frameHz/$fft: ${order[i - 1]} then ${order[i]}", order[i] >= order[i - 1])
                val reached = order.distinct()
                assertEquals("$frameHz/$fft: none skipped", reached.last() - reached.first() + 1, reached.size)
                // It keeps up: the last one handed over is within a tenth of a second of the newest made.
                assertTrue("$frameHz/$fft", (stream.made - 1 - reached.last()) * hop / rate.toDouble() < 0.1)
                // No time is handed out backward, none in a lump, and it adds up to the time played.
                assertTrue(steps.all { it >= 0f })
                assertTrue("$frameHz/$fft: ${steps.max()}", steps.max() <= 2.5f / frameHz + hop / rate.toFloat())
                val played = steps.sum()
                assertTrue("$frameHz/$fft: $played", played in 2.6f..3.05f)
            }
        }
    }

    @Test
    fun `at a fast display a frame is never left standing while spectra wait`() {
        // 120 frames a second against 60 passes: every other frame gets no
        // new batch, and must still move, toward a spectrum that is there.
        val stream = Stream(rate, 4096, 163)
        val playhead = SpectrogramPlayhead(stream.feed)
        var t = 0.0
        var nextPass = 0.0
        var still = 0
        var frames = 0
        while (t < 2.0) {
            while (nextPass <= t) { stream.passUpTo(nextPass); nextPass += 1.0 / 60 }
            var moved = 0f
            playhead.advance(1f / 120) { _, s -> moved += s }
            if (t > 0.5) {
                frames++
                if (moved < 0.5f / 120) still++
            }
            t += 1.0 / 120
        }
        assertEquals("frames that did not move", 0, still)
        assertTrue(frames > 100)
    }

    @Test
    fun `a new stream or a long stall starts at the newest sound instead of playing the gap out`() {
        val stream = Stream(rate, 4096, 163)
        val playhead = SpectrogramPlayhead(stream.feed)
        var handed = 0
        var t = 0.0
        while (t < 1.0) {
            stream.passUpTo(t)
            playhead.advance(1f / 60) { _, _ -> handed++ }
            t += 1.0 / 60
        }
        assertTrue(handed > 100)
        // The picture is off screen for two seconds while the sound goes on.
        stream.passUpTo(3.0)
        handed = 0
        var time = 0f
        playhead.advance(1f / 60) { _, s -> handed++; time += s }
        assertTrue("$handed handed over after the stall", handed <= 1)
        assertTrue(time <= 1f / 60 + 1e-4f)
        // A new stream (another rate, another FFT size): numbers start again, and nothing breaks.
        stream.feed.restart(48_000)
        playhead.advance(1f / 60) { _, _ -> }
        stream.feed.slot()[0] = 7f
        stream.feed.publish(4096)
        var seen = -1
        repeat(8) { playhead.advance(1f / 60) { bins, _ -> seen = bins[0].toInt() } }
        assertEquals(7, seen)
    }

    @Test
    fun `the feed keeps the newest spectra readable and counts who is watching`() {
        val feed = SpectrogramFeed(2)
        assertEquals(0L, feed.count)
        assertTrue(!feed.isWanted)
        feed.acquire(); feed.acquire(); feed.release()
        assertTrue(feed.isWanted)
        feed.release(); feed.release()
        assertTrue(!feed.isWanted)
        feed.restart(44_100)
        val epoch = feed.epoch
        for (i in 0 until 500) {
            feed.slot()[0] = i.toFloat()
            feed.publish(i * 100L)
        }
        assertEquals(500L, feed.count)
        assertEquals(500L - (SpectrogramFeed.CAPACITY - SpectrogramFeed.GUARD), feed.oldest())
        for (n in feed.oldest() until feed.count) {
            assertEquals(n.toFloat(), feed.spectrum(n)[0], 0f)
            assertEquals(n * 100L, feed.endSample(n))
        }
        feed.restart(96_000)
        assertEquals(0L, feed.count)
        assertEquals(epoch + 1, feed.epoch)
        assertEquals(96_000, feed.sampleRate)
    }

    @Test
    fun `the FFT's size and overlap are the listener's, and a long FFT costs a pass no more than a short one`() {
        // The model's list is the analyzer's.
        assertTrue(Spectrogram3DSettings.FFT_SIZES.contentEquals(SpectrumAnalyzerTap.SPECTROGRAM_FFT_SIZES))
        assertEquals(SpectrumAnalyzerTap.DEFAULT_SPECTROGRAM_FFT, Spectrogram3DSettings.DEFAULT.fftSize)
        assertEquals(Spectrogram3DScene.RANGE_DB, Spectrogram3DSettings.DEFAULT.rangeDb, 0f)
        // As long in time at any rate, and never longer than the ring holds twice over.
        assertEquals(4096, SpectrumAnalyzerTap.spectrogramFftSize(44_100, 4096))
        assertEquals(4096, SpectrumAnalyzerTap.spectrogramFftSize(48_000, 4096))
        assertEquals(8192, SpectrumAnalyzerTap.spectrogramFftSize(96_000, 4096))
        assertEquals(16384, SpectrumAnalyzerTap.spectrogramFftSize(192_000, 4096))
        assertEquals(1024, SpectrumAnalyzerTap.spectrogramFftSize(44_100, 1024))
        assertEquals(SpectrumAnalyzerTap.FFT_SIZE_HIGH, SpectrumAnalyzerTap.spectrogramFftSize(192_000, 16384))
        // A size that is not on the list is the nearest that is.
        assertEquals(2048, SpectrumAnalyzerTap.spectrogramFftBase(1800))
        assertEquals(1024, SpectrumAnalyzerTap.spectrogramFftBase(-5))
        assertEquals(16384, SpectrumAnalyzerTap.spectrogramFftBase(1_000_000))
        assertEquals(8192, Spectrogram3DSettings.fftSizeNear(9000))
        // The hop is what the overlap leaves of the window.
        assertEquals(163, SpectrumAnalyzerTap.spectrogramHop(4096, 0.96f))
        assertEquals(2048, SpectrumAnalyzerTap.spectrogramHop(4096, 0.5f))
        assertTrue(SpectrumAnalyzerTap.spectrogramHop(1024, 0.999f) >= 10)
        // About the same FFT work a pass at every size.
        for (size in SpectrumAnalyzerTap.SPECTROGRAM_FFT_SIZES) {
            val work = SpectrumAnalyzerTap.spectraPerPass(size) * size
            assertTrue("$size: $work", work in 12_000..25_000)
        }
        // Settings out of range come back in it.
        val wild = Spectrogram3DSettings(fftSize = 2500, overlapPct = 500f, rangeDb = Float.NaN).clamped()
        assertEquals(2048, wild.fftSize)
        assertEquals(Spectrogram3DSettings.MAX_OVERLAP_PCT, wild.overlapPct, 0f)
        assertEquals(Spectrogram3DSettings.DEFAULT.rangeDb, wild.rangeDb, 0f)
    }
}
