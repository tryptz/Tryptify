package tf.monochrome.android.audio.dsp.crossfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-JVM tests for the headphone crossfeed stage. The effect is plain
 * Kotlin (no JNI), so these lock its bypass transparency, the angle → mix
 * mapping, and the contralateral feed behaviour.
 */
class CrossfeedEffectTest {

    private fun impulseLeft(n: Int) = FloatArray(n) { if (it == 0) 1f else 0f }

    private fun freshEffect(): CrossfeedEffect =
        CrossfeedEffect().also { it.prepare(48000.0) }

    @Test
    fun `disabled is bit-transparent`() {
        val fx = freshEffect()
        val l = FloatArray(512) { kotlin.math.sin(it * 0.1f) }
        val r = FloatArray(512) { kotlin.math.cos(it * 0.07f) }
        val refL = l.copyOf()
        val refR = r.copyOf()
        fx.processArrays(l, r, 512)
        assertTrue(l.contentEquals(refL))
        assertTrue(r.contentEquals(refR))
    }

    @Test
    fun `180 degrees is (near) transparent even when enabled`() {
        val fx = freshEffect()
        fx.setEnabled(true)
        fx.setSpeakerAngleDeg(180f)
        val l = impulseLeft(2048)
        val r = FloatArray(2048)
        fx.processArrays(l, r, 2048)
        // cos(90°) → crossfeed gain ~0: right stays silent, left stays ~unity.
        assertEquals(1f, l[0], 1e-3f)
        assertTrue(r.all { abs(it) < 1e-3f })
    }

    @Test
    fun `narrow angle feeds left signal into right ear, delayed`() {
        val fx = freshEffect()
        fx.setEnabled(true)
        fx.setSpeakerAngleDeg(30f)
        // Long enough for the 5 ms parameter glide to land at full strength.
        val frames = 4096
        val l = FloatArray(frames) { kotlin.math.sin(it * 0.05f) }
        val r = FloatArray(frames)
        fx.processArrays(l, r, frames)
        val tailEnergy = (frames / 2 until frames).sumOf { abs(r[it]).toDouble() }
        assertTrue("contralateral feed should be audible", tailEnergy > 1.0)
        // Direct channel is renormalised, never boosted above unity.
        assertTrue(l.all { abs(it) <= 1f + 1e-4f })
    }

    @Test
    fun `speaker model is the default algorithm`() {
        assertEquals(CrossfeedAlgorithm.SPEAKER, freshEffect().state.value.algorithm)
    }

    @Test
    fun `fixed algorithms feed the opposite ear at their published levels`() {
        // Same signal through each network; a hotter feed level must leave more
        // contralateral energy: BS2B (−4.5 dB) > Chu Moy (−6) > Meier (−9.5).
        fun tailEnergyFor(algo: CrossfeedAlgorithm): Double {
            val fx = freshEffect()
            fx.setEnabled(true)
            fx.setAlgorithm(algo)
            val frames = 4096
            val l = FloatArray(frames) { kotlin.math.sin(it * 0.05f) }
            val r = FloatArray(frames)
            fx.processArrays(l, r, frames)
            return (frames / 2 until frames).sumOf { abs(r[it]).toDouble() }
        }
        val bs2b = tailEnergyFor(CrossfeedAlgorithm.BS2B)
        val cmoy = tailEnergyFor(CrossfeedAlgorithm.CMOY)
        val meier = tailEnergyFor(CrossfeedAlgorithm.JMEIER)
        assertTrue("all algorithms should crossfeed", meier > 1.0)
        assertTrue("BS2B should feed harder than Chu Moy", bs2b > cmoy)
        assertTrue("Chu Moy should feed harder than Meier", cmoy > meier)
    }

    @Test
    fun `switching algorithm mid-stream stays bounded`() {
        val fx = freshEffect()
        fx.setEnabled(true)
        val frames = 1024
        for (algo in CrossfeedAlgorithm.entries) {
            fx.setAlgorithm(algo)
            val l = FloatArray(frames) { kotlin.math.sin(it * 0.05f) }
            val r = FloatArray(frames) { kotlin.math.cos(it * 0.03f) }
            fx.processArrays(l, r, frames)
            assertTrue(l.all { it.isFinite() && abs(it) <= 1.5f })
            assertTrue(r.all { it.isFinite() && abs(it) <= 1.5f })
        }
    }

    @Test
    fun `angle is clamped to the supported range`() {
        val fx = freshEffect()
        fx.setSpeakerAngleDeg(500f)
        assertEquals(CrossfeedState.MAX_ANGLE_DEG, fx.state.value.speakerAngleDeg, 0f)
        fx.setSpeakerAngleDeg(1f)
        assertEquals(CrossfeedState.MIN_ANGLE_DEG, fx.state.value.speakerAngleDeg, 0f)
    }

    @Test
    fun `re-enabling after fade-out does not replay a stale tail`() {
        val fx = freshEffect()
        fx.setEnabled(true)
        fx.setSpeakerAngleDeg(30f)
        val frames = 4096
        fx.processArrays(FloatArray(frames) { 0.5f }, FloatArray(frames) { 0.5f }, frames)
        fx.setEnabled(false)
        // Silence long enough for the gain glide to hit the fast-path reset.
        repeat(8) { fx.processArrays(FloatArray(frames), FloatArray(frames), frames) }
        fx.setEnabled(true)
        val l = FloatArray(64)
        val r = FloatArray(64)
        fx.processArrays(l, r, 64)
        assertTrue(l.all { abs(it) < 1e-4f })
        assertTrue(r.all { abs(it) < 1e-4f })
    }
}
