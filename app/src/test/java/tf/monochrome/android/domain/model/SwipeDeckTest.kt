package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.discover.SwipeDeckStore

/**
 * The swipe deck's dealing rules — the things that make a deck feel broken
 * when they fail: a liked or recently skipped song coming back, a song dealt
 * twice, one artist on two cards in a row, one source filling the whole stack.
 */
class SwipeDeckTest {

    private fun track(id: String, artist: String, title: String = "Song $id") = UnifiedTrack(
        id = id,
        title = title,
        durationSeconds = 200,
        artistName = artist,
        source = PlaybackSource.HiFiApi(tidalId = id.filter(Char::isDigit).toLongOrNull() ?: 1L),
        sourceType = SourceType.API,
    )

    private fun card(id: String, artist: String, title: String = "Song $id", reason: DeckReason = DeckReason.Genre("Trance")) =
        DeckCard(track(id, artist, title), reason)

    @Test
    fun `a stack is never larger than asked`() {
        val band = (1..40).map { card("t$it", "Artist $it") }
        assertEquals(SwipeDeck.SIZE, SwipeDeck.deal(listOf(band), emptySet()).size)
    }

    @Test
    fun `the bands are taken in turn, so one source cannot fill the stack`() {
        val today = (1..20).map { card("a$it", "A$it", reason = DeckReason.Today("Hard Trance")) }
        val genre = (1..20).map { card("b$it", "B$it", reason = DeckReason.Genre("Eurodance")) }
        val artist = (1..20).map { card("c$it", "C$it", reason = DeckReason.Artist("PPK")) }
        val dealt = SwipeDeck.deal(listOf(today, genre, artist), emptySet(), size = 9)
        assertEquals(3, dealt.count { it.reason is DeckReason.Today })
        assertEquals(3, dealt.count { it.reason is DeckReason.Genre })
        assertEquals(3, dealt.count { it.reason is DeckReason.Artist })
    }

    @Test
    fun `a thin band leaves room for the others`() {
        val thin = listOf(card("a1", "A"))
        val rich = (1..30).map { card("b$it", "B$it") }
        assertEquals(SwipeDeck.SIZE, SwipeDeck.deal(listOf(thin, rich, emptyList()), emptySet()).size)
    }

    @Test
    fun `liked and skipped songs are left out, whatever their id`() {
        val liked = card("t1", "Ferry Corsten", "Out of the Blue")
        val sameSongOtherService = card("q99", "Ferry Corsten", "Out Of The Blue (Remastered)")
        val fresh = card("t2", "Push", "Universal Nation")
        val dealt = SwipeDeck.deal(listOf(listOf(liked, sameSongOtherService, fresh)), setOf(liked.key))
        assertEquals(listOf(fresh.track.id), dealt.map { it.track.id })
    }

    @Test
    fun `the same song from two sources is dealt once`() {
        val a = card("t1", "ATB", "9 PM (Till I Come)", DeckReason.Today("Trance"))
        val b = card("q1", "ATB", "9 PM", DeckReason.Artist("Cosmic Gate"))
        assertEquals(1, SwipeDeck.deal(listOf(listOf(a), listOf(b)), emptySet()).size)
    }

    @Test
    fun `one artist never takes two cards in a row while anyone else is left`() {
        val band = listOf(
            card("1", "Marco V"), card("2", "Marco V"), card("3", "Marco V"),
            card("4", "Push"), card("5", "Push"), card("6", "ATB"),
        )
        val dealt = SwipeDeck.deal(listOf(band), emptySet())
        dealt.zipWithNext().forEach { (a, b) ->
            assertFalse("${a.track.artistName} twice in a row in ${dealt.map { it.track.artistName }}",
                a.track.artistName == b.track.artistName)
        }
    }

    @Test
    fun `skips last thirty days, and only the newest are kept when there are too many`() {
        val today = 20_000L
        val skips = mapOf("old" to today - 30, "recent" to today - 29, "today" to today)
        assertEquals(setOf("recent", "today"), SwipeDeck.liveSkips(skips, today).keys)

        val many = (0 until SwipeDeck.MAX_SKIPS + 50).associate { "k$it" to today - (it % 20) }
        assertEquals(SwipeDeck.MAX_SKIPS, SwipeDeck.liveSkips(many, today).size)
    }

    @Test
    fun `a stored deck reads back as it was written`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val state = SwipeDeckStore.State(
            day = 20_000,
            service = "QOBUZ",
            cards = listOf(
                card("t1", "PPK", reason = DeckReason.Artist("Push")),
                card("t2", "Push", reason = DeckReason.Today("Trance")),
            ),
            position = 1,
            kept = listOf("t1"),
            dealtToday = listOf("ppk|song t1"),
            round = 0,
            skipped = mapOf("a|b" to 19_990L),
        )
        val back = json.decodeFromString(SwipeDeckStore.State.serializer(), json.encodeToString(SwipeDeckStore.State.serializer(), state))
        assertEquals(state, back)
        assertTrue(back.cards[0].reason is DeckReason.Artist)
    }
}
