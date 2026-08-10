package tf.monochrome.android.data.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.usecase.agrees
import tf.monochrome.android.domain.usecase.capPerArtist
import tf.monochrome.android.domain.usecase.narrowToGenre
import tf.monochrome.android.domain.usecase.promoteConfirmed

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

    // --- cross-checking -----------------------------------------------------

    /** The head of Last.fm's real `hard techno` chart, pop tags and all. */
    private val hardTechno = listOf(
        entry(1, "FKA twigs", "Perfectly", 0),
        entry(2, "Charli xcx", "365", 0),
        entry(3, "Klangkuenstler", "Toter Schmetterling", 0),
        entry(4, "Sara Landry", "Heaven", 0),
        entry(5, "Baby Jane", "Eternal Embrace", 0),
    )

    @Test
    fun `confirmed artists rise above the pop acts a crowd mis-tagged`() {
        val confirmed = setOf(
            normalizeForMatch("Klangkuenstler"),
            normalizeForMatch("Sara Landry"),
        )

        val chart = promoteConfirmed(hardTechno, confirmed)

        assertEquals(
            listOf("Klangkuenstler", "Sara Landry", "FKA twigs", "Charli xcx", "Baby Jane"),
            chart.map { it.artistName },
        )
        assertEquals("ranks are rebuilt", listOf(1, 2, 3, 4, 5), chart.map { it.rank })
    }

    @Test
    fun `promotion preserves the source ranking inside each group`() {
        // Only the boundary between confirmed and unconfirmed is new; Last.fm's
        // ordering has to survive on either side of it.
        val chart = promoteConfirmed(hardTechno, setOf(normalizeForMatch("Baby Jane")))
        assertEquals(
            listOf("Baby Jane", "FKA twigs", "Charli xcx", "Klangkuenstler", "Sara Landry"),
            chart.map { it.artistName },
        )
    }

    @Test
    fun `nothing is reordered on no evidence`() {
        // A genre no source recognises, and a check that confirmed nobody. Both
        // must leave the chart alone rather than shuffle it on a hunch.
        assertEquals(hardTechno, promoteConfirmed(hardTechno, emptySet()))
        assertEquals(hardTechno, promoteConfirmed(hardTechno, setOf("someone else entirely")))
    }

    @Test
    fun `one artist cannot own the chart`() {
        // The regression this guards: promoting confirmed artists floated four
        // consecutive Sara Landry tracks to the top of hard techno.
        val clumped = listOf(
            entry(1, "Sara Landry", "Heaven", 0),
            entry(2, "Sara Landry", "Chaos Magicka", 0),
            entry(3, "Sara Landry", "Legacy", 0),
            entry(4, "Sara Landry", "Pressure", 0),
            entry(5, "Klangkuenstler", "Untergang", 0),
        )

        val capped = capPerArtist(clumped, max = 3)

        assertEquals(4, capped.size)
        assertEquals(3, capped.count { it.artistName == "Sara Landry" })
        assertEquals("the fourth is dropped, not reordered", "Legacy", capped[2].title)
        assertEquals("Klangkuenstler", capped[3].artistName)
        assertEquals(listOf(1, 2, 3, 4), capped.map { it.rank })
    }

    @Test
    fun `capping is case and punctuation insensitive`() {
        val sameArtist = listOf(
            entry(1, "Sara Landry", "A", 0),
            entry(2, "SARA LANDRY", "B", 0),
            entry(3, "sara  landry!", "C", 0),
        )
        assertEquals(2, capPerArtist(sameArtist, max = 2).size)
    }

    @Test
    fun `a non-positive cap leaves the chart alone`() {
        assertEquals(hardTechno, capPerArtist(hardTechno, max = 0))
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
