package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.ToneControls
import kotlin.math.abs

/**
 * What the system-wide equalizer is told to play: each ear's curve, when the
 * limiter is wanted, when there is nothing to play at all, and when the app's
 * own equalizer has to stand down for it.
 */
class SystemEqCurveTest {

    private fun peak(freq: Float, gain: Float, q: Float = 1.4f) =
        EqBand(id = freq.toInt(), type = FilterType.PEAKING, freq = freq, gain = gain, q = q, enabled = true)

    private val flatTone = ToneControls()
    private val cut = listOf(peak(3150f, -6f))
    private val boost = listOf(peak(100f, 6f))

    /** The index of [hz] in the effect's bands. */
    private fun band(hz: Float) = SystemEqCurve.BAND_FREQS.indexOfFirst { it == hz }.also { assertTrue(it >= 0) }

    @Test
    fun `with nothing to play there is no plan, and the effect comes off`() {
        assertNull(SystemEqCurve.plan(emptyList(), emptyList(), false, flatTone, 0f, autoPreamp = true))
        assertNull(SystemEqCurve.plan(emptyList(), emptyList(), true, flatTone, 0f, autoPreamp = false))
        // A band that does nothing is nothing.
        val idle = listOf(peak(1000f, 0f), peak(200f, 4f).copy(enabled = false))
        assertNull(SystemEqCurve.plan(idle, emptyList(), false, flatTone, 0f, autoPreamp = true))
        // A manual preamp alone is something: it turns everything down.
        assertNotNull(SystemEqCurve.plan(emptyList(), emptyList(), false, flatTone, -3f, autoPreamp = false))
    }

    @Test
    fun `one correction plays in both ears, as one curve`() {
        val plan = SystemEqCurve.plan(cut, emptyList(), stereo = false, flatTone, 0f, autoPreamp = true)!!
        assertFalse(plan.perEar)
        assertSame(plan.left, plan.right)
        assertEquals(SystemEqCurve.BAND_FREQS.size, plan.left.size)
        // The cut is where it was put, and far from it nothing.
        assertEquals(-6f, plan.left[band(3150f)], 0.05f)
        assertEquals(0f, plan.left[band(50f)], 0.1f)
    }

    @Test
    fun `in 2-channel mode the right ear plays its own correction`() {
        val right = listOf(peak(3150f, -2f), peak(8000f, -4f))
        val plan = SystemEqCurve.plan(cut, right, stereo = true, flatTone, 0f, autoPreamp = true)!!
        assertTrue(plan.perEar)
        assertEquals(-6f, plan.left[band(3150f)], 0.05f)
        // Only the skirt of its 3.15 kHz cut is left up at 8 kHz.
        assertTrue(plan.left[band(8000f)] > -1f)
        assertTrue("the right ear's own cut at 8 kHz", plan.right[band(8000f)] < -3.5f)
        assertTrue("and its smaller one at 3.15 kHz", plan.right[band(3150f)] > -3.5f)
    }

    @Test
    fun `the right ear falls back to the left's without the mode or without a curve of its own`() {
        val right = listOf(peak(8000f, -4f))
        // 2-channel mode off: the right curve saved is not played.
        assertFalse(SystemEqCurve.plan(cut, right, stereo = false, flatTone, 0f, autoPreamp = true)!!.perEar)
        // On, with no right curve saved yet.
        assertFalse(SystemEqCurve.plan(cut, emptyList(), stereo = true, flatTone, 0f, autoPreamp = true)!!.perEar)
        // On, with the same curve in both: one curve, not two alike.
        assertFalse(SystemEqCurve.plan(cut, cut.toList(), stereo = true, flatTone, 0f, autoPreamp = true)!!.perEar)
    }

    @Test
    fun `the tone shelves go to both ears, and one preamp keeps them level`() {
        val tone = ToneControls(bassGainDb = 6f)
        assertTrue("a bass shelf", tone.toBands().any { it.enabled })
        val right = listOf(peak(8000f, 5f))
        val plan = SystemEqCurve.plan(cut, right, stereo = true, tone, 0f, autoPreamp = true)!!
        // The same preamp under both: where neither ear has a band of its
        // own (1 kHz) they differ only by their own corrections' skirts.
        val l = plan.left[band(1000f)]
        val r = plan.right[band(1000f)]
        assertTrue("left $l right $r", abs(l - r) < 1.5f)
        // Automatic preamp: sized by the louder ear, so neither rises above full scale.
        assertTrue(plan.left.max() <= 0.1f)
        assertTrue(plan.right.max() <= 0.1f)
        // And the bass is up against the mid in both.
        assertTrue(plan.left[band(50f)] > plan.left[band(1000f)] + 3f)
        assertTrue(plan.right[band(50f)] > plan.right[band(1000f)] + 3f)
    }

    @Test
    fun `the limiter is in only while the curve rises above full scale`() {
        // Cuts only: never.
        assertFalse(SystemEqCurve.plan(cut, emptyList(), false, flatTone, 0f, autoPreamp = false)!!.limiter)
        // A boost with the automatic preamp is pulled down by as much.
        assertFalse(SystemEqCurve.plan(boost, emptyList(), false, flatTone, 0f, autoPreamp = true)!!.limiter)
        // The same boost with the manual preamp left at 0 would clip.
        assertTrue(SystemEqCurve.plan(boost, emptyList(), false, flatTone, 0f, autoPreamp = false)!!.limiter)
        // Turned down by hand as far as the boost, it would not.
        assertFalse(SystemEqCurve.plan(boost, emptyList(), false, flatTone, -6f, autoPreamp = false)!!.limiter)
        // Not far enough, and it would.
        assertTrue(SystemEqCurve.plan(boost, emptyList(), false, flatTone, -3f, autoPreamp = false)!!.limiter)
        // A boost in the right ear alone counts.
        assertTrue(SystemEqCurve.plan(cut, boost, stereo = true, flatTone, 0f, autoPreamp = false)!!.limiter)
        // A manual preamp turned up over nothing at all.
        assertTrue(SystemEqCurve.plan(emptyList(), emptyList(), false, flatTone, 3f, autoPreamp = false)!!.limiter)
        // A narrow boost between two of the effect's bands is still seen:
        // the peak is read off the real curve, not off the 31 samples.
        val between = listOf(peak(1120f, 9f, q = 12f))
        val plan = SystemEqCurve.plan(between, emptyList(), false, flatTone, 0f, autoPreamp = false)!!
        assertTrue(plan.limiter)
        assertTrue("its samples alone are under it", plan.left.max() < 9f)
    }

    @Test
    fun `a gain past what the effect takes is held to it`() {
        val wild = listOf(peak(1000f, 20f), peak(1000f, 20f))
        val plan = SystemEqCurve.plan(wild, emptyList(), false, flatTone, 0f, autoPreamp = false)!!
        assertEquals(SystemEqCurve.MAX_GAIN_DB, plan.left[band(1000f)], 0f)
        assertEquals(SystemEqCurve.MIN_GAIN_DB, SystemEqCurve.gainAt(1000f, listOf(peak(1000f, -20f), peak(1000f, -20f)), 0f), 0f)
    }

    @Test
    fun `the app's own equalizer stands down for system-wide, except through the exclusive DAC`() {
        // Off: the app's own plays.
        assertFalse(SystemEqCurve.coversOwnPlayback(systemWide = false, exclusiveDacStreaming = false))
        assertFalse(SystemEqCurve.coversOwnPlayback(systemWide = false, exclusiveDacStreaming = true))
        // On, through Android: the effect has the song, the app's own would be a second correction.
        assertTrue(SystemEqCurve.coversOwnPlayback(systemWide = true, exclusiveDacStreaming = false))
        // On, through the exclusive DAC: the effect never sees the song, so the app's own must play.
        assertFalse(SystemEqCurve.coversOwnPlayback(systemWide = true, exclusiveDacStreaming = true))
    }
}
