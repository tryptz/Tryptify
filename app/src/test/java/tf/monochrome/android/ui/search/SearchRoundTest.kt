package tf.monochrome.android.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.data.scxyt.ScxytSource
import tf.monochrome.android.data.scxyt.ScxytSource.SOUNDCLOUD
import tf.monochrome.android.data.scxyt.ScxytSource.YOUTUBE
import tf.monochrome.android.ui.search.SearchRound.State
import tf.monochrome.android.ui.search.SearchViewModel.SearchSourceFilter

/**
 * Search shows the catalogues and the library as soon as they are in, and
 * merges YouTube and SoundCloud in as each answers ([SearchRound]). They used
 * to be awaited with the rest, so every search waited for YouTube's, up to its
 * 7 s, whichever source the listener had chosen.
 */
class SearchRoundTest {

    /** A round whose late sources answer after the given virtual times, recording what is handed over. */
    private class Harness(scope: CoroutineScope, answerAfter: Map<ScxytSource, Long>) {
        val searched = mutableListOf<ScxytSource>()
        val merged = mutableListOf<ScxytSource>()
        val round = SearchRound(
            query = "q",
            parent = scope,
            search = { source: ScxytSource ->
                searched += source
                delay(answerAfter.getValue(source))
                "songs from $source"
            },
            onAnswer = { source, songs ->
                assertEquals("songs from $source", songs)
                merged += source
            },
        )
    }

    private fun TestScope.at(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `the first part is shown while YouTube is still out`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 7_000L))
        h.round.ask(listOf(YOUTUBE))
        at(300)
        // TIDAL and the library answer at 300 ms and are shown then, with the
        // small spinner under them, not the full-page one.
        h.round.firstShown(failed = false, firstPage = 12)
        runCurrent()
        assertEquals(State.MORE_COMING, h.round.state(found = true))
        assertEquals(emptyList<ScxytSource>(), h.merged)
        at(6_999)
        assertEquals(State.MORE_COMING, h.round.state(found = true))
        at(7_000)
        assertEquals(listOf(YOUTUBE), h.merged)
        assertEquals(State.DONE, h.round.state(found = true))
    }

    @Test
    fun `each late source is merged as it answers`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 5_000L, SOUNDCLOUD to 1_000L))
        h.round.ask(listOf(YOUTUBE, SOUNDCLOUD))
        at(300)
        h.round.firstShown(failed = false, firstPage = 3)
        at(1_000)
        assertEquals(listOf(SOUNDCLOUD), h.merged)
        assertEquals(setOf(YOUTUBE), h.round.pending)
        assertEquals(State.MORE_COMING, h.round.state(found = true))
        at(5_000)
        assertEquals(listOf(SOUNDCLOUD, YOUTUBE), h.merged)
        assertEquals(emptySet<ScxytSource>(), h.round.pending)
    }

    @Test
    fun `an answer that beats the first part waits for it`() = runTest {
        // Merged before the first part is shown, it would go into the
        // previous query's results, which the first part then replaces.
        val h = Harness(backgroundScope, mapOf(SOUNDCLOUD to 100L))
        h.round.ask(listOf(SOUNDCLOUD))
        at(200)
        assertEquals(emptyList<ScxytSource>(), h.merged)
        assertEquals(State.SEARCHING, h.round.state(found = true))
        h.round.firstShown(failed = false, firstPage = 0)
        runCurrent()
        assertEquals(listOf(SOUNDCLOUD), h.merged)
        assertEquals(State.DONE, h.round.state(found = true))
    }

    @Test
    fun `a newer query drops the older one's late answers`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 7_000L))
        h.round.ask(listOf(YOUTUBE))
        at(300)
        h.round.firstShown(failed = false, firstPage = 5)
        at(2_000)
        h.round.cancel()
        at(10_000)
        assertEquals(emptyList<ScxytSource>(), h.merged)
    }

    @Test
    fun `a source is asked once per query`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 1_000L, SOUNDCLOUD to 1_000L))
        h.round.ask(listOf(YOUTUBE))
        h.round.ask(listOf(YOUTUBE))
        h.round.ask(listOf(YOUTUBE, SOUNDCLOUD))
        h.round.firstShown(failed = false, firstPage = 0)
        at(2_000)
        assertEquals(listOf(YOUTUBE, SOUNDCLOUD), h.searched)
        assertEquals(listOf(YOUTUBE, SOUNDCLOUD), h.merged)
    }

    @Test
    fun `a chip chosen after the first part asks its source then`() = runTest {
        // Searched under TIDAL, YouTube was left out; choosing All asks it.
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 2_000L, SOUNDCLOUD to 500L))
        h.round.ask(SearchViewModel.lateSourcesFor(SearchSourceFilter.TIDAL))
        at(300)
        h.round.firstShown(failed = false, firstPage = 8)
        assertEquals(emptyList<ScxytSource>(), h.searched)
        assertEquals(State.DONE, h.round.state(found = true))
        at(1_000)
        h.round.ask(SearchViewModel.lateSourcesFor(SearchSourceFilter.ALL))
        assertEquals(State.MORE_COMING, h.round.state(found = true))
        at(1_500)
        assertEquals(listOf(SOUNDCLOUD), h.merged)
        at(3_000)
        assertEquals(listOf(YOUTUBE, SOUNDCLOUD), h.searched)
        assertEquals(listOf(SOUNDCLOUD, YOUTUBE), h.merged)
        assertEquals(State.DONE, h.round.state(found = true))
    }

    @Test
    fun `couldn't reach search waits for YouTube and SoundCloud`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 4_000L))
        h.round.ask(listOf(YOUTUBE))
        at(300)
        h.round.firstShown(failed = true, firstPage = 0)
        runCurrent()
        // Every catalogue failed, but YouTube may still find something: the
        // spinner stays, not the error.
        assertEquals(State.SEARCHING, h.round.state(found = false))
        at(4_000)
        assertEquals(State.UNREACHABLE, h.round.state(found = false))
        assertEquals(State.DONE, h.round.state(found = true))
    }

    @Test
    fun `no results waits for YouTube and SoundCloud`() = runTest {
        val h = Harness(backgroundScope, mapOf(YOUTUBE to 4_000L))
        h.round.ask(listOf(YOUTUBE))
        at(300)
        h.round.firstShown(failed = false, firstPage = 0)
        runCurrent()
        assertEquals(State.SEARCHING, h.round.state(found = false))
        at(4_000)
        assertEquals(State.DONE, h.round.state(found = false))
    }

    /** A result row: its id and its search score (the title's match plus the source's boost). */
    private data class Song(val id: String, val score: Int)

    // scoreTracks' shape: first of two equal ids, then by score, stably.
    private val rank: (List<Song>) -> List<Song> = { songs ->
        songs.distinctBy { it.id }.sortedByDescending { it.score }
    }

    @Test
    fun `late songs rank into the first page as if they had come in with it`() = runTest {
        val h = Harness(backgroundScope, emptyMap())
        // The same title from TIDAL (boost 50) and YouTube (42) and SoundCloud (41).
        val firstPart = listOf(Song("api_other", 1_850), Song("api_song", 4_050))
        val shown = rank(firstPart)
        h.round.firstShown(failed = false, firstPage = shown.size)
        // A second TIDAL page, loaded on scroll before YouTube answered.
        val pageTwo = listOf(Song("api_page2", 4_050))
        val youtube = listOf(Song("yt_other", 3_542), Song("yt_song", 4_042))

        val merged = h.round.mergeIntoFirstPage(shown + pageTwo, youtube, rank)
        assertEquals(rank(firstPart + youtube) + pageTwo, merged)
        assertEquals(listOf("api_song", "yt_song", "yt_other", "api_other", "api_page2"), merged.map { it.id })

        // SoundCloud answering next ranks among YouTube's too, still above page two.
        val soundcloud = listOf(Song("sc_song", 4_041))
        val again = h.round.mergeIntoFirstPage(merged, soundcloud, rank)
        assertEquals(rank(firstPart + youtube + soundcloud) + pageTwo, again)
    }

    @Test
    fun `only a chip that can list their songs asks YouTube and SoundCloud`() {
        val asks = SearchSourceFilter.entries.associateWith { SearchViewModel.lateSourcesFor(it) }
        assertEquals(listOf(YOUTUBE, SOUNDCLOUD), asks[SearchSourceFilter.ALL])
        // A downloaded YouTube or SoundCloud song is listed under Local.
        assertEquals(listOf(YOUTUBE, SOUNDCLOUD), asks[SearchSourceFilter.LOCAL])
        assertEquals(listOf(YOUTUBE), asks[SearchSourceFilter.YOUTUBE])
        assertEquals(listOf(SOUNDCLOUD), asks[SearchSourceFilter.SOUNDCLOUD])
        for (chip in listOf(
            SearchSourceFilter.TIDAL, SearchSourceFilter.QOBUZ, SearchSourceFilter.DEEZER,
            SearchSourceFilter.COLLECTION, SearchSourceFilter.SUBSONIC,
        )) {
            assertEquals("$chip", emptyList<ScxytSource>(), asks[chip])
        }
    }
}
