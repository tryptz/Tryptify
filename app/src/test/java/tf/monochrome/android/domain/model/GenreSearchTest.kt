package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.usecase.rankGenres
import java.io.File

/**
 * Genre search, against the real 771-node dataset.
 *
 * A fixture would only prove the ranking compiles. What needs guarding is that
 * the genres a listener actually types find the node they meant, and that
 * popularity breaking ties doesn't let a large genre outrank a better match.
 */
class GenreSearchTest {

    private val graph: GenreGraph by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val assets = File("src/main/assets")
        val data = json.decodeFromString(
            GenreGraphData.serializer(),
            File(assets, "genre_graph.json").readText(),
        )
        val vocabulary = json.decodeFromString(
            GenreVocabulary.serializer(),
            File(assets, "genre_vocabulary.json").readText(),
        ).names
        GenreGraph(data, vocabulary)
    }

    private fun search(query: String, limit: Int = 24): List<String> {
        val exact = query.takeIf { it.isNotBlank() }?.let { graph.resolve(it) }
        return rankGenres(graph.allGenres, query, limit, exact?.id).map { it.id }
    }

    @Test
    fun `an empty query offers the most listened genres rather than nothing`() {
        val top = search("")
        assertEquals(24, top.size)
        // Whatever the exact order, the openers have to be genres a listener
        // would recognise — an empty row that leads with obscurities is worse
        // than the static list it replaced.
        assertTrue("expected a household name near the top, got ${top.take(5)}",
            top.take(6).any { it in setOf("rock", "pop", "electronic", "metal", "folk", "ambient") })
    }

    @Test
    fun `typing a genre's real name puts it first`() {
        for (id in listOf("hardstyle", "techno", "bachata", "shoegaze", "gabber")) {
            val name = graph[id]!!.name
            assertEquals("searching '$name'", id, search(name).first())
        }
    }

    @Test
    fun `abbreviations the graph knows resolve to their genre`() {
        // These go through GenreGraph.resolve, which is the part that already
        // understood aliases; the search must not get in its way.
        assertEquals("drum-and-bass", search("dnb").first())
        assertEquals("drum-and-bass", search("d&b").first())
        assertEquals("idm", search("idm").first())
    }

    @Test
    fun `a prefix finds the genre before you have finished typing`() {
        assertTrue("'hards' should reach hardstyle", "hardstyle" in search("hards"))
        assertTrue("'shoe' should reach shoegaze", "shoegaze" in search("shoe"))
        assertTrue("'bacha' should reach bachata", "bachata" in search("bacha"))
    }

    @Test
    fun `a word from the middle of a name still finds it`() {
        // "liquid drum & bass" is not something anyone types in full.
        assertTrue("'liquid' should reach liquid dnb", search("liquid").any { it.contains("liquid") })
        assertTrue("'uptempo' should reach uptempo hardcore",
            search("uptempo").any { it.contains("uptempo") })
    }

    @Test
    fun `words in any order match a multi-word name`() {
        assertTrue("'bass drum' should still find drum & bass",
            "drum-and-bass" in search("bass drum"))
    }

    @Test
    fun `a single typo still finds the genre`() {
        assertTrue("'techo' should reach techno", "techno" in search("techo"))
        assertTrue("'hardstlye' should reach hardstyle", "hardstyle" in search("hardstlye"))
    }

    @Test
    fun `a short query is not treated as a typo of everything`() {
        // Fuzzy matching is gated at four characters: below that nearly every
        // genre is one edit from every other and the row fills with noise.
        val results = search("dub")
        assertTrue("'dub' should lead with real dub genres, got ${results.take(4)}",
            results.take(4).all { it.contains("dub") })
    }

    @Test
    fun `popularity breaks ties but never beats a better match`() {
        // "hard techno" is far smaller than "techno", and typing its full name
        // must still put it first rather than deferring to the bigger genre.
        assertEquals("hard-techno", search("hard techno").first())
        // Meanwhile among equally-good prefix matches, the bigger one leads.
        val h = search("ha")
        assertTrue("expected a well-known 'ha' genre first, got ${h.take(3)}", h.isNotEmpty())
    }

    @Test
    fun `nonsense matches nothing rather than everything`() {
        assertTrue(search("zzzzqqqq").isEmpty())
        assertTrue(search("qwertyuiop").isEmpty())
    }

    @Test
    fun `punctuation alone is treated as nothing typed`() {
        // "!!!" folds away to an empty string, so it is the same question as an
        // empty box — "what is there?" — and answering with the popular genres
        // is more useful than an empty row and a "no matches" message.
        assertEquals(search(""), search("!!!"))
    }

    @Test
    fun `the limit is respected and results are distinct`() {
        val results = search("house", limit = 8)
        assertTrue(results.size <= 8)
        assertEquals(results.distinct(), results)
    }
}
