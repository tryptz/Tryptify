package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTimelineTest {

    @Test
    fun `constant speed scales from the anchor`() {
        val t = SpeedTimeline()
        t.reset(outUs = 1_000_000, factor = 2.0)
        assertEquals(1_000_000, t.mediaAt(1_000_000))
        assertEquals(3_000_000, t.mediaAt(2_000_000))
    }

    @Test
    fun `a change applies only from where it was set`() {
        val t = SpeedTimeline()
        t.reset(outUs = 0, factor = 1.0)
        t.setFactor(outUs = 10_000_000, factor = 1.5)
        assertEquals(5_000_000, t.mediaAt(5_000_000))
        assertEquals(10_000_000, t.mediaAt(10_000_000))
        assertEquals(13_000_000, t.mediaAt(12_000_000))
    }

    @Test
    fun `the audible position still resolves against the older checkpoint`() {
        // The change is set at the write end, which is ahead of playout by
        // the output's buffer; until playout reaches it the old speed holds.
        val t = SpeedTimeline()
        t.reset(outUs = 0, factor = 1.0)
        t.setFactor(outUs = 2_000_000, factor = 2.0)
        assertEquals(1_800_000, t.mediaAt(1_800_000))
        assertEquals(2_400_000, t.mediaAt(2_200_000))
    }

    @Test
    fun `setting a factor at the write end does not drop what playout still needs`() {
        val t = SpeedTimeline()
        t.reset(outUs = 0, factor = 1.0)
        t.setFactor(outUs = 1_000_000, factor = 2.0)
        t.setFactor(outUs = 3_000_000, factor = 0.5)
        // Playout is still before the first change.
        assertEquals(500_000, t.mediaAt(500_000))
        assertEquals(3_000_000, t.mediaAt(2_000_000))
        assertEquals(5_500_000, t.mediaAt(4_000_000))
    }

    @Test
    fun `rebase moves media time and keeps the speed`() {
        val t = SpeedTimeline()
        t.reset(outUs = 0, factor = 2.0)
        t.rebase(outUs = 1_000_000, mediaUs = 10_000_000)
        assertEquals(12_000_000, t.mediaAt(2_000_000))
    }

    @Test
    fun `empty timeline is the identity`() {
        assertEquals(1_234, SpeedTimeline().mediaAt(1_234))
    }
}
