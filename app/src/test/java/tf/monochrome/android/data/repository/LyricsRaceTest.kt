package tf.monochrome.android.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

class LyricsRaceTest {

    private val word = Lyrics(isSynced = true, lines = listOf(LyricLine(0, "a", listOf(LyricWord(0, 100, "a")))))
    private val line = Lyrics(isSynced = true, lines = listOf(LyricLine(0, "a")))
    private val plain = Lyrics(isSynced = false, lines = listOf(LyricLine(0, "a")))

    private fun entry(name: String, afterMs: Long, result: Lyrics?) =
        LyricsRace.Entry(name) { delay(afterMs); result }

    @Test
    fun `word timing from the first source ends the race at once`() = runBlocking {
        val winner = LyricsRace.run(
            listOf(entry("first", 10, word), entry("slow", 5_000, word)),
            graceMs = 1_000,
            deadlineMs = 10_000,
        )!!
        assertEquals("first", winner.name)
        assertTrue("waited for a source it did not need: ${winner.elapsedMs} ms", winner.elapsedMs < 1_000)
    }

    @Test
    fun `word timing beats line timing from a higher source`() = runBlocking {
        val winner = LyricsRace.run(listOf(entry("tidal", 10, line), entry("apple", 50, word)))!!
        assertEquals("apple", winner.name)
    }

    @Test
    fun `a higher source gets the grace window to beat a lower word-timed answer`() = runBlocking {
        val winner = LyricsRace.run(
            listOf(entry("high", 200, word), entry("low", 10, word)),
            graceMs = 1_000,
        )!!
        assertEquals("high", winner.name)
    }

    @Test
    fun `after the grace window the lower answer stands`() = runBlocking {
        val winner = LyricsRace.run(
            listOf(entry("high", 5_000, word), entry("low", 10, word)),
            graceMs = 100,
            deadlineMs = 10_000,
        )!!
        assertEquals("low", winner.name)
        assertTrue(winner.elapsedMs < 1_000)
    }

    @Test
    fun `line timing waits for the rest in case one is word-timed`() = runBlocking {
        val winner = LyricsRace.run(listOf(entry("line", 10, line), entry("word", 150, word)))!!
        assertEquals("word", winner.name)
    }

    @Test
    fun `with only line timing in hand the rest get the settle window, not the deadline`() = runBlocking {
        // A song no source has karaoke for: one slow source must not hold the
        // line-timed lyrics back until the deadline.
        val winner = LyricsRace.run(
            listOf(entry("slow", 60_000, null), entry("line", 10, line)),
            settleMs = 200,
            deadlineMs = 15_000,
        )!!
        assertEquals("line", winner.name)
        assertTrue("held the lyrics back for ${winner.elapsedMs} ms", winner.elapsedMs < 1_000)
    }

    @Test
    fun `word timing inside the settle window still wins`() = runBlocking {
        val winner = LyricsRace.run(
            listOf(entry("line", 10, line), entry("word", 150, word)),
            settleMs = 1_000,
        )!!
        assertEquals("word", winner.name)
    }

    @Test
    fun `equal timing goes to the source listed first`() = runBlocking {
        val winner = LyricsRace.run(listOf(entry("first", 100, line), entry("second", 10, line)))!!
        assertEquals("first", winner.name)
    }

    @Test
    fun `plain text is the last resort`() = runBlocking {
        val winner = LyricsRace.run(listOf(entry("plain", 10, plain), entry("none", 20, null)))!!
        assertEquals("plain", winner.name)
    }

    @Test
    fun `the deadline caps a source that never answers`() = runBlocking {
        val winner = LyricsRace.run(
            listOf(entry("hangs", 60_000, word), entry("line", 10, line)),
            deadlineMs = 300,
        )!!
        assertEquals("line", winner.name)
        assertTrue(winner.elapsedMs < 2_000)
    }

    @Test
    fun `a failing source is a miss, not a crash`() = runBlocking {
        val boom = LyricsRace.Entry("boom") { error("network down") }
        val winner = LyricsRace.run(listOf(boom, entry("ok", 10, line)))!!
        assertEquals("ok", winner.name)
    }

    @Test
    fun `nothing anywhere is null`() = runBlocking {
        assertNull(LyricsRace.run(listOf(entry("a", 10, null), entry("b", 10, null))))
        assertNull(LyricsRace.run(emptyList()))
    }
}
