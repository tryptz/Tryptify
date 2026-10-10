package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

class LyricClockTest {

    private val karaoke = Lyrics(
        isSynced = true,
        lines = listOf(
            LyricLine(1_000, "one two", listOf(LyricWord(1_000, 1_200, "one"), LyricWord(1_300, 1_600, "two"))),
            LyricLine(5_000, "three", listOf(LyricWord(5_000, 5_400, "three"))),
        ),
    )

    @Test
    fun `bluetooth delay is converted into song time`() {
        assertEquals(200L, LyricClock.delayInSongMs(200f, 1f))
        assertEquals(300L, LyricClock.delayInSongMs(200f, 1.5f))
        assertEquals(100L, LyricClock.delayInSongMs(200f, 0.5f))
        assertEquals(10_000L - 400L, LyricClock.heardPositionMs(10_000, 200f, 2f))
    }

    @Test
    fun `a nonsense speed counts as normal speed`() {
        assertEquals(200L, LyricClock.delayInSongMs(200f, 0f))
        assertEquals(200L, LyricClock.delayInSongMs(200f, Float.NaN))
    }

    @Test
    fun `the next boundary is the soonest word edge or line start`() {
        assertEquals(1_200L, LyricClock.nextBoundaryMs(karaoke.lines, 1_050))
        assertEquals(1_300L, LyricClock.nextBoundaryMs(karaoke.lines, 1_200))
        assertEquals(5_000L, LyricClock.nextBoundaryMs(karaoke.lines, 1_600))
        assertEquals(1_000L, LyricClock.nextBoundaryMs(karaoke.lines, 0))
        assertNull(LyricClock.nextBoundaryMs(karaoke.lines, 6_000))
    }

    @Test
    fun `the poll wakes at the next word in wall time`() {
        // 150 ms of song to the end of "one": 150 ms at 1x, 75 ms at 2x.
        assertEquals(152L, LyricClock.nextPollDelayMs(1_050, 1f, 0f, karaoke))
        assertEquals(77L, LyricClock.nextPollDelayMs(1_050, 2f, 0f, karaoke))
        // 0.5x: 300 ms of wall time, longer than the idle interval.
        assertEquals(LyricClock.IDLE_POLL_MS, LyricClock.nextPollDelayMs(1_050, 0.5f, 0f, karaoke))
    }

    @Test
    fun `the poll targets the boundary as heard through the delay`() {
        // Reported 1_250, heard 1_050 with 200 ms of latency at 1x.
        assertEquals(152L, LyricClock.nextPollDelayMs(1_250, 1f, 200f, karaoke))
    }

    @Test
    fun `far from any boundary the poll keeps its idle pace`() {
        assertEquals(LyricClock.IDLE_POLL_MS, LyricClock.nextPollDelayMs(2_000, 1f, 0f, karaoke))
        assertEquals(LyricClock.IDLE_POLL_MS, LyricClock.nextPollDelayMs(2_000, 1f, 0f, null))
        val unsynced = Lyrics(isSynced = false, lines = listOf(LyricLine(0, "words")))
        assertEquals(LyricClock.IDLE_POLL_MS, LyricClock.nextPollDelayMs(0, 1f, 0f, unsynced))
    }

    @Test
    fun `never faster than a frame`() {
        assertEquals(LyricClock.MIN_POLL_MS, LyricClock.nextPollDelayMs(1_199, 1f, 0f, karaoke))
    }
}
