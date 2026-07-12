package tf.monochrome.android.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioPlannerWeightsTest {

    @Test
    fun `defaults match the plan's recommended values`() {
        val w = RadioPlannerWeights.DEFAULT
        assertEquals(1.20f, w.localLibrary, 0f)
        assertEquals(1.00f, w.qobuz, 0f)
        assertEquals(0.90f, w.listenbrainzGraph, 0f)
        assertEquals(1.20f, w.canonicalVersionBias, 0f)
        assertEquals(1.30f, w.avoidRecentlyPlayed, 0f)
        assertEquals(0.70f, w.eraConsistency, 0f)
    }

    @Test
    fun `clamped coerces out-of-range values into 0 to 3`() {
        val w = RadioPlannerWeights(novelty = -5f, familiarity = 99f).clamped()
        assertEquals(0f, w.novelty, 0f)
        assertEquals(3f, w.familiarity, 0f)
    }

    @Test
    fun `clamped replaces non-finite values with the field default`() {
        val w = RadioPlannerWeights(
            localLibrary = Float.NaN,
            qobuz = Float.POSITIVE_INFINITY,
            moodContinuity = Float.NEGATIVE_INFINITY,
        ).clamped()
        assertEquals(RadioPlannerWeights.DEFAULT.localLibrary, w.localLibrary, 0f)
        assertEquals(RadioPlannerWeights.DEFAULT.qobuz, w.qobuz, 0f)
        assertEquals(RadioPlannerWeights.DEFAULT.moodContinuity, w.moodContinuity, 0f)
    }

    @Test
    fun `clamped leaves in-range values untouched`() {
        val w = RadioPlannerWeights(novelty = 2.5f, eraConsistency = 0.1f).clamped()
        assertEquals(2.5f, w.novelty, 0f)
        assertEquals(0.1f, w.eraConsistency, 0f)
    }
}
