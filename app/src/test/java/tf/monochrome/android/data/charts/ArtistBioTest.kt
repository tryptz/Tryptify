package tf.monochrome.android.data.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opening of a Last.fm bio, as a planet's caption. */
class ArtistBioTest {

    // Trimmed from ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=Burial&format=json
    private val body = """
        {"artist":{"name":"Burial","url":"https://www.last.fm/music/Burial",
          "mbid":"9ddce51c-2b75-4b3e-ac8c-1db09119f8c5",
          "stats":{"listeners":"1146342","playcount":"62734211"},
          "bio":{"published":"10 Feb 2006, 09:23",
            "summary":"Burial is the moniker of William Bevan, a London-based electronic musician. His debut album &quot;Burial&quot; was released on Hyperdub in 2006. <a href=\"https://www.last.fm/music/Burial\">Read more on Last.fm</a>",
            "content":"…"}}}
    """.trimIndent()

    @Test
    fun `the summary comes back as plain sentences`() {
        val info = parseArtistInfo(body)!!
        val bio = info.bio!!
        assertTrue(bio.startsWith("Burial is the moniker of William Bevan"))
        assertTrue(bio.contains("\"Burial\""))
        assertFalse(bio.contains("Read more"))
        assertFalse(bio.contains("<"))
        assertTrue(bio.length <= BIO_CHARS)
    }

    @Test
    fun `whole sentences, as many as fit`() {
        val summary = "One short sentence. " + "A second sentence that is long enough to push past the caption on its own, ".repeat(3) + "end."
        assertEquals("One short sentence.", bioBlurb(summary))
    }

    @Test
    fun `one sentence longer than the caption is cut with an ellipsis`() {
        val blurb = bioBlurb("word ".repeat(80))!!
        assertEquals(BIO_CHARS, blurb.length)
        assertTrue(blurb.endsWith("…"))
    }

    @Test
    fun `no bio, an empty one, or a disambiguation note is nothing`() {
        assertNull(parseArtistInfo("""{"error":6,"message":"The artist you supplied could not be found"}"""))
        assertNull(bioBlurb(""" <a href="https://www.last.fm/music/X">Read more on Last.fm</a>"""))
        assertNull(bioBlurb("There are multiple artists with this name: 1) a Swedish band 2) a rapper."))
    }

    @Test
    fun `entities and other tags are undone, and the text in a link is kept`() {
        assertEquals(
            "Rock & roll from Detroit, with \"The Stooges\".",
            bioBlurb("Rock &amp; roll from <b>Detroit</b>, with &quot;<a href=\"x\">The Stooges</a>&quot;."),
        )
    }

    @Test
    fun `the artist's MusicBrainz id comes with the bio, and counts their catalogue`() {
        assertEquals("9ddce51c-2b75-4b3e-ac8c-1db09119f8c5", parseArtistInfo(body)!!.mbid)
        // Trimmed from musicbrainz.org/ws/2/release-group?artist=<mbid>&limit=1&fmt=json
        val groups = """{"release-group-count":47,"release-group-offset":0,"release-groups":[{"id":"x","title":"Untrue"}]}"""
        assertEquals(47, parseReleaseGroupCount(groups))
        assertNull(parseReleaseGroupCount("""{"error":"Not Found"}"""))
    }
}
