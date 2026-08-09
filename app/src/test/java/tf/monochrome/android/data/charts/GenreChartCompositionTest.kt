package tf.monochrome.android.data.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.usecase.agrees
import tf.monochrome.android.domain.usecase.narrowToGenre

/**
 * The windows, the name matching and the genre narrowing — the pure half of
 * chart building, exercised without a network.
 */
class GenreChartCompositionTest {

    @Test
    fun `every window maps onto a real listenbrainz range`() {
        // These strings are wire values, not labels. A typo here does not fail
        // loudly; it returns an error body that parses to an empty chart.
        assertEquals("week", ChartWindow.SEVEN_DAYS.listenBrainzRange)
        assertEquals("month", ChartWindow.THIRTY_DAYS.listenBrainzRange)
        assertEquals("half_yearly", ChartWindow.SIX_MONTHS.listenBrainzRange)
        assertEquals("year", ChartWindow.ONE_YEAR.listenBrainzRange)
        assertEquals("all_time", ChartWindow.ALL_TIME.listenBrainzRange)
    }

    @Test
    fun `window ids round trip and an unknown id falls back to the default`() {
        for (window in ChartWindow.entries) {
            assertEquals(window, ChartWindow.fromId(window.id))
        }
        // Ids key the cache and the screen's saved state, so an id that no
        // longer exists has to resolve to something rather than crash.
        assertEquals(ChartWindow.DEFAULT, ChartWindow.fromId("90d"))
        assertEquals(ChartWindow.DEFAULT, ChartWindow.fromId(null))
    }

    @Test
    fun `match folding collapses the ways two services spell the same track`() {
        val expected = normalizeForMatch("Sicko Mode")
        assertEquals(expected, normalizeForMatch("SICKO MODE"))
        assertEquals(expected, normalizeForMatch("Sicko Mode (feat. Drake)"))
        assertEquals(expected, normalizeForMatch("Sicko Mode - Remastered"))
        assertEquals(expected, normalizeForMatch("  Sicko   Mode!  "))
        assertEquals(expected, normalizeForMatch("Sicko Mode [Explicit]"))
    }

    @Test
    fun `match folding still separates genuinely different names`() {
        assertFalse(normalizeForMatch("Aphex Twin") == normalizeForMatch("Aphex Twins"))
        assertFalse(normalizeForMatch("Untitled 1") == normalizeForMatch("Untitled 2"))
    }

    private fun entry(rank: Int, artist: String, title: String, listens: Long) =
        ChartEntry(rank = rank, title = title, artistName = artist, listenCount = listens)

    @Test
    fun `narrowing keeps only the genre's artists and renumbers from one`() {
        val global = listOf(
            entry(1, "BTS", "SWIM", 216_022),
            entry(2, "Taylor Swift", "Fortnight", 180_000),
            entry(51, "AIROD", "Acid Storm", 900),
            entry(900, "Chris Liebing", "Novo", 400),
        )
        val artists = setOf(normalizeForMatch("AIROD"), normalizeForMatch("Chris Liebing"))

        val chart = narrowToGenre(global, artists, limit = 100)

        assertEquals(listOf("AIROD", "Chris Liebing"), chart.map { it.artistName })
        // Global positions 51 and 900 would imply 898 tracks were withheld.
        assertEquals(listOf(1, 2), chart.map { it.rank })
    }

    @Test
    fun `narrowing ranks by listens, not by the global order it arrived in`() {
        val global = listOf(
            entry(1, "AIROD", "Quiet One", 10),
            entry(2, "Chris Liebing", "Loud One", 5_000),
        )
        val artists = setOf(normalizeForMatch("AIROD"), normalizeForMatch("Chris Liebing"))

        assertEquals(listOf("Loud One", "Quiet One"), narrowToGenre(global, artists, 100).map { it.title })
    }

    @Test
    fun `narrowing respects the limit and survives an empty artist set`() {
        val global = (1..200).map { entry(it, "Artist $it", "Track $it", it.toLong()) }
        val artists = global.map { normalizeForMatch(it.artistName) }.toSet()

        assertEquals(100, narrowToGenre(global, artists, 100).size)
        // A genre MusicBrainz has never heard of narrows to nothing rather than
        // silently returning the global chart under a genre's name.
        assertTrue(narrowToGenre(global, emptySet(), 100).isEmpty())
    }

    // --- catalogue matching -------------------------------------------------
    //
    // The reason this matcher exists: searching a catalogue for a genre's name
    // ranks by how well a *title or album* matches those words, which is the
    // one query machine-generated filler is built to win. Nothing reaches the
    // player without agreeing with the chart on both fields.

    private val airod = entry(1, "AIROD", "Acid Storm", 900)

    @Test
    fun `a hit agreeing on artist and title is accepted`() {
        assertTrue(agrees(airod, "AIROD", "Acid Storm"))
    }

    @Test
    fun `harmless spelling differences still agree`() {
        assertTrue("case and punctuation", agrees(airod, "airod", "ACID STORM!"))
        assertTrue("feature credit on one side", agrees(airod, "AIROD", "Acid Storm (feat. Somebody)"))
        assertTrue("remaster suffix", agrees(airod, "AIROD", "Acid Storm - Remastered"))
        assertTrue("collaboration credit", agrees(airod, "AIROD & Friend", "Acid Storm"))
    }

    @Test
    fun `a track that merely has the genre in its name is rejected`() {
        // The exact failure being guarded: a filler upload titled after the
        // genre, by an artist who has nothing to do with it.
        val slop = entry(1, "Hard Techno Mix", "Hard Techno", 0)
        assertFalse(agrees(slop, "AI Beats Factory", "Hard Techno 2024 Best Drops"))
    }

    @Test
    fun `agreeing on only one field is not enough`() {
        assertFalse("right title, wrong artist", agrees(airod, "Someone Else", "Acid Storm"))
        assertFalse("right artist, wrong track", agrees(airod, "AIROD", "Different Track"))
    }

    @Test
    fun `a hit missing artist or title is rejected rather than assumed`() {
        // Blank folds to empty, and empty is contained in every string — without
        // this guard a result with no artist would match anything at all.
        assertFalse(agrees(airod, "", "Acid Storm"))
        assertFalse(agrees(airod, "AIROD", ""))
        assertFalse(agrees(entry(1, "", "Acid Storm", 0), "AIROD", "Acid Storm"))
    }

    @Test
    fun `a chart reports whether it is showing the window that was asked for`() {
        val honest = GenreChart(
            genreId = "hard-techno", genreName = "Hard techno",
            requested = ChartWindow.SEVEN_DAYS, shown = ChartWindow.SEVEN_DAYS,
            source = ChartSource.WINDOWED_LISTENS,
        )
        val fallen = honest.copy(shown = ChartWindow.ALL_TIME, source = ChartSource.TAG_CHART)

        assertFalse(honest.fellBack)
        assertTrue("a fallback must be visible to the screen", fallen.fellBack)
    }
}
