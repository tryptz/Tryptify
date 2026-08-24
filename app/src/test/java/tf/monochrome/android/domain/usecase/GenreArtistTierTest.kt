package tf.monochrome.android.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.charts.ChartEntry

/**
 * The tier that fills a genre row from the genre's own artists.
 *
 * This exists because the row it replaces was a catalogue search for the
 * genre's *name*, which ranks records that say "neoclassical" above records
 * that are neoclassical. Every case below is a shape that search produced, or a
 * shape the fix would produce if one of these helpers were subtly wrong.
 */
class GenreArtistTierTest {

    private fun entry(rank: Int, artist: String, title: String = "Track $rank") =
        ChartEntry(rank = rank, title = title, artistName = artist)

    @Test
    fun `chart artists keep the chart's order and appear once each`() {
        val artists = chartArtists(
            listOf(
                entry(1, "Ólafur Arnalds"),
                entry(2, "Nils Frahm"),
                entry(3, "OLAFUR ARNALDS"),
                entry(4, "Max Richter"),
            )
        )
        assertEquals(listOf("Ólafur Arnalds", "Nils Frahm", "Max Richter"), artists)
    }

    @Test
    fun `an artist with no usable name is not an artist`() {
        assertEquals(
            listOf("Nils Frahm"),
            chartArtists(listOf(entry(1, "  "), entry(2, "!!!!"), entry(3, "Nils Frahm"))),
        )
    }

    @Test
    fun `the pop acts a crowd sprinkled onto a genre are dropped`() {
        // The failure this tier could amplify. A tag chart's head collects
        // whoever is on everyone's radar: hard techno comes back led by artists
        // who make nothing of the kind. Carrying that at one row is survivable;
        // filling a whole row from those artists' catalogues is not.
        val named = listOf("FKA twigs", "Charli xcx", "Klangkuenstler", "Sara Landry", "I Hate Models")
        val ranked = rankGenreArtists(
            names = named,
            confirmed = setOf("klangkuenstler", "sara landry", "i hate models"),
            want = 6,
            minConfirmed = 3,
        )
        assertEquals(listOf("Klangkuenstler", "Sara Landry", "I Hate Models"), ranked)
    }

    @Test
    fun `a check with too little to say leaves the chart's order alone`() {
        // A cold MusicBrainz cache and an artist nobody has tagged produce this,
        // and it must read as "no opinion" rather than "no good artists" — the
        // other reading empties out every genre that is merely obscure.
        val named = listOf("Somebody", "Somebody Else", "A Third Party")
        assertEquals(named, rankGenreArtists(named, emptySet(), want = 6, minConfirmed = 3))
        assertEquals(
            named,
            rankGenreArtists(named, setOf("somebody"), want = 6, minConfirmed = 3),
        )
    }

    @Test
    fun `page two draws on different artists than page one`() {
        val ranked = (1..12).map { "Artist $it" }
        val first = artistWindowFor(ranked, page = 0, artistsPerPage = 6, perArtist = 3)
        val second = artistWindowFor(ranked, page = 1, artistsPerPage = 6, perArtist = 3)

        assertEquals(0, first.trackSkip)
        assertEquals(0, second.trackSkip)
        assertTrue(first.artists.intersect(second.artists.toSet()).isEmpty())
        assertEquals(6, second.artists.size)
    }

    @Test
    fun `once the artists run out the pages go deeper into each one`() {
        val ranked = (1..12).map { "Artist $it" }
        val third = artistWindowFor(ranked, page = 2, artistsPerPage = 6, perArtist = 3)
        assertEquals(listOf("Artist 1", "Artist 2", "Artist 3", "Artist 4", "Artist 5", "Artist 6"), third.artists)
        assertEquals(3, third.trackSkip)
    }

    @Test
    fun `a single artist laps on every page rather than dividing by zero`() {
        val window = artistWindowFor(listOf("Only One"), page = 3, artistsPerPage = 6, perArtist = 3)
        assertEquals(listOf("Only One"), window.artists)
        assertEquals(9, window.trackSkip)
    }

    @Test
    fun `no artists is an empty window and not a crash`() {
        val window = artistWindowFor(emptyList(), page = 0, artistsPerPage = 6, perArtist = 3)
        assertTrue(window.artists.isEmpty())
    }

    @Test
    fun `round robin deals across bands so no one artist owns the head`() {
        val dealt = roundRobin(
            listOf(
                listOf("a1", "a2", "a3"),
                listOf("b1", "b2"),
                listOf("c1", "c2", "c3"),
            ),
            limit = 9,
        )
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "c2", "a3", "c3"), dealt)
    }

    @Test
    fun `round robin respects its limit and tolerates empty bands`() {
        assertEquals(
            listOf("a1", "c1", "a2"),
            roundRobin(listOf(listOf("a1", "a2"), emptyList(), listOf("c1")), limit = 3),
        )
        assertTrue(roundRobin(listOf(listOf("a1")), limit = 0).isEmpty())
        assertTrue(roundRobin(emptyList<List<String>>(), limit = 5).isEmpty())
    }

    @Test
    fun `a credit that carries a collaborator is still the artist we asked for`() {
        assertTrue(matchesArtistName("Ólafur Arnalds & Nils Frahm", "Nils Frahm"))
        assertTrue(matchesArtistName("Nils Frahm", "nils frahm"))
    }

    @Test
    fun `a short name has to match outright`() {
        // Bare containment makes a two-letter act match half the catalogue, and
        // on this tier the credited-artist test is the only filter there is.
        assertFalse(matchesArtistName("MK Ultra Sound System", "MK"))
        assertTrue(matchesArtistName("MK", "M.K."))
    }

    @Test
    fun `an unnameable credit matches nothing`() {
        assertFalse(matchesArtistName("", "Nils Frahm"))
        assertFalse(matchesArtistName("Nils Frahm", "   "))
    }
}
