package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSegmentedTest {

    // Three segments in a 306px capsule, 3px apart: 100px each, a step of 103.
    private val segment = segmentWidth(306f, 3f, 3)
    private val step = segment + 3f

    @Test
    fun `segments share the capsule less the gaps`() {
        assertEquals(100f, segment, 0.001f)
        assertEquals(0f, segmentWidth(306f, 3f, 0), 0f)
        assertEquals(306f, segmentWidth(306f, 3f, 1), 0.001f)
    }

    @Test
    fun `the thumb centres under the finger`() {
        // A finger at a segment's middle puts the thumb exactly over it.
        assertEquals(0f, thumbAt(50f, segment, step, 3), 0.001f)
        assertEquals(1f, thumbAt(153f, segment, step, 3), 0.001f)
        assertEquals(2f, thumbAt(256f, segment, step, 3), 0.001f)
        // Between two, it is between them: the drag is free, not stepped.
        assertEquals(0.5f, thumbAt(101.5f, segment, step, 3), 0.001f)
    }

    @Test
    fun `the thumb stops at either end`() {
        assertEquals(0f, thumbAt(-80f, segment, step, 3), 0f)
        assertEquals(2f, thumbAt(900f, segment, step, 3), 0f)
        assertEquals(0f, thumbAt(500f, segment, step, 1), 0f)
    }

    @Test
    fun `a released thumb settles on the nearest segment`() {
        assertEquals(0, nearestSegment(0.49f, 3))
        assertEquals(1, nearestSegment(0.51f, 3))
        assertEquals(1, nearestSegment(1.4f, 3))
        assertEquals(2, nearestSegment(1.6f, 3))
        assertEquals(2, nearestSegment(7f, 3))
        assertEquals(0, nearestSegment(-1f, 3))
    }
}
