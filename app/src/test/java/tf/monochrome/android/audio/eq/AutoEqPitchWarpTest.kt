package tf.monochrome.android.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The AutoEQ pre-warp: bands are tuned to `freq / pitchRatio` so that Media3's
 * Sonic stage — which sits *after* this processor in DefaultAudioSink's chain
 * and scales every frequency by the playback pitch ratio — lands the correction
 * back on the headphone resonance it was measured against.
 *
 * Sonic is not in the loop here, so these tests measure the processor's own
 * output: a correctly pre-warped chain looks *shifted* at this point, by
 * exactly the inverse of what Sonic will later apply.
 */
class AutoEqPitchWarpTest {

    private val sampleRate = 48000

    /** Push a stereo float sine through the processor; returns steady-state L amplitude. */
    private fun measure(proc: AutoEqProcessor, freq: Double, frames: Int = 32768): Double {
        val out = FloatArray(frames)
        var written = 0
        var phaseIdx = 0
        val chunk = 1024
        val buf = ByteBuffer.allocateDirect(chunk * 8).order(ByteOrder.nativeOrder())
        while (written < frames) {
            buf.clear()
            for (i in 0 until chunk) {
                val s = sin(2.0 * PI * freq * (phaseIdx + i) / sampleRate).toFloat()
                buf.putFloat(s)
                buf.putFloat(s)
            }
            phaseIdx += chunk
            buf.flip()
            proc.queueInput(buf)
            val o = proc.output
            while (o.remaining() >= 8 && written < frames) {
                out[written++] = o.getFloat()
                o.getFloat()  // skip R
            }
        }
        // Goertzel over the settled second half.
        val start = frames / 2
        val w = 2.0 * PI * freq / sampleRate
        val c = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in start until frames) {
            val s0 = out[i] + c * s1 - s2
            s2 = s1; s1 = s0
        }
        val n = frames - start
        return sqrt((s1 * s1 + s2 * s2 - c * s1 * s2).coerceAtLeast(0.0)) / (n / 2.0)
    }

    private fun db(x: Double) = 20.0 * log10(x)

    private fun processor(bands: List<EqBand>): AutoEqProcessor {
        val proc = AutoEqProcessor()
        proc.configure(AudioFormat(sampleRate, 2, C.ENCODING_PCM_FLOAT))
        proc.flush()
        proc.applyBands(bands, preamp = 0f, enabled = true)
        return proc
    }

    private fun semitones(n: Double) = 2.0.pow(n / 12.0).toFloat()

    private val band = listOf(EqBand(0, FilterType.PEAKING, 3000f, 10f, 4f, true))

    @Test
    fun `unity pitch ratio leaves the band where it was`() {
        val proc = processor(band)
        proc.setPitchRatio(1.0f, immediate = true)
        assertEquals(10.0, db(measure(proc, 3000.0)), 0.3)
    }

    @Test
    fun `pre-warp moves the band down by the pitch ratio`() {
        // +2 semitones: Sonic will multiply every frequency by 1.1225, so the
        // band must sit at 3000 / 1.1225 = 2672.5 Hz beforehand.
        val ratio = semitones(2.0)
        val proc = processor(band)
        proc.setPitchRatio(ratio, immediate = true)

        val warpedCenter = 3000.0 / ratio
        assertEquals(
            "peak should have moved to freq / ratio",
            10.0, db(measure(proc, warpedCenter)), 0.3,
        )
        // And it should no longer be sitting on the original centre — that is
        // precisely the misalignment this exists to prevent.
        val atOriginal = db(measure(proc, 3000.0))
        assertTrue(
            "band should have left 3 kHz (was ${"%.2f".format(atOriginal)} dB)",
            atOriginal < 7.0,
        )
    }

    @Test
    fun `pre-warp is a rigid translation on the log-frequency axis`() {
        // The whole justification for leaving Q alone: a pitch shift scales
        // frequency, which on a log axis is a shift, and a shift preserves
        // bandwidth in octaves. So the warped response at p / ratio must equal
        // the unwarped response at p, for every probe p.
        val ratio = semitones(3.0)
        val flat = processor(band).also { it.setPitchRatio(1.0f, immediate = true) }
        val warped = processor(band).also { it.setPitchRatio(ratio, immediate = true) }

        for (probe in listOf(1500.0, 2200.0, 3000.0, 4100.0, 5600.0)) {
            val reference = db(measure(flat, probe))
            val shifted = db(measure(warped, probe / ratio))
            assertEquals(
                "shape must be preserved at $probe Hz",
                reference, shifted, 0.5,
            )
        }
    }

    @Test
    fun `down-shift warps the band upward`() {
        // Ratio below 1: Sonic will move everything down, so the pre-warp has
        // to push bands up to compensate.
        val ratio = semitones(-5.0)
        val proc = processor(band)
        proc.setPitchRatio(ratio, immediate = true)
        assertEquals(10.0, db(measure(proc, 3000.0 / ratio)), 0.3)
    }

    @Test
    fun `glide converges on the same alignment as an immediate set`() {
        val ratio = semitones(2.0)
        val target = 3000.0 / ratio

        val glided = processor(band)
        // No `immediate`: this is the real path, where the warp slides in over
        // roughly 5 x the 140 ms time constant rather than jumping.
        glided.setPitchRatio(ratio)

        // The glide runs on a control-rate coroutine, so poll for settling
        // rather than assuming a fixed number of ticks.
        val deadline = System.nanoTime() + 5_000_000_000L
        var reached = Double.NaN
        while (System.nanoTime() < deadline) {
            reached = db(measure(glided, target, frames = 8192))
            if (abs(reached - 10.0) < 0.3) break
            Thread.sleep(20)
        }
        assertEquals(
            "glide should land on the same place immediate does",
            10.0, reached, 0.3,
        )
    }

    @Test
    fun `glide starts from the unwarped alignment rather than jumping`() {
        // The point of the glide: the first moments after the ratio changes
        // must still be near the old alignment, not already at the new one.
        val proc = processor(band)
        proc.setPitchRatio(semitones(7.0))
        // One short block, immediately after the request.
        val atOldCentre = db(measure(proc, 3000.0, frames = 4096))
        assertTrue(
            "should still be near the old centre right after the change " +
                "(was ${"%.2f".format(atOldCentre)} dB)",
            atOldCentre > 6.0,
        )
    }

    @Test
    fun `degenerate ratios are ignored`() {
        val proc = processor(band)
        proc.setPitchRatio(1.0f, immediate = true)
        proc.setPitchRatio(0f, immediate = true)
        proc.setPitchRatio(Float.NaN, immediate = true)
        proc.setPitchRatio(-2f, immediate = true)
        assertEquals(
            "rejected ratios must leave the previous alignment intact",
            10.0, db(measure(proc, 3000.0)), 0.3,
        )
    }
}
