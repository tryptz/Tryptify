package tf.monochrome.android.data.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire formats, against payloads recorded from the real services.
 *
 * This is the layer most likely to rot without anyone noticing. Every parser
 * here fails soft, so a renamed field does not throw — it returns an empty
 * chart, which on screen is indistinguishable from a genre nobody listens to.
 * These fixtures are the only thing that tells the two apart.
 */
class ChartsParsingTest {

    // Trimmed from api.listenbrainz.org/1/stats/sitewide/recordings?range=week
    private val listenBrainzBody = """
        {"payload":{
          "count":2,"offset":0,"range":"week",
          "from_ts":1784505600,"to_ts":1785110400,"last_updated":1786094046,
          "recordings":[
            {"artist_name":"BTS","artist_mbids":["0d79fe8e-ba27-4859-bb8c-2f255f346853"],
             "caa_id":44649429843,"caa_release_mbid":"2f9b427d-cc6e-4465-9f70-9d2109381db0",
             "listen_count":216022,"recording_mbid":"6f33dc05-cdc0-4a2f-8039-e8fed082eec6",
             "release_name":"ARIRANG","track_name":"SWIM"},
            {"artist_name":"AIROD","listen_count":91,"track_name":"Acid Storm"}
          ]}}
    """.trimIndent()

    // Trimmed from ws.audioscrobbler.com/2.0/?method=tag.gettoptracks
    private val lastFmBody = """
        {"tracks":{"track":[
          {"name":"Windowlicker","duration":"363","mbid":"abc-123",
           "artist":{"name":"Aphex Twin","mbid":"f22942a1"},
           "image":[{"#text":"https://example.test/small.png","size":"small"},
                    {"#text":"https://example.test/large.png","size":"large"}],
           "@attr":{"rank":"1"}},
          {"name":"Xtal","artist":{"name":"Aphex Twin"},"@attr":{"rank":"2"}}
        ],"@attr":{"tag":"idm","page":"1"}}}
    """.trimIndent()

    private val musicBrainzBody = """
        {"count":167,"offset":0,"artists":[
          {"id":"912f2a1a","name":"AIROD","score":100},
          {"id":"1f9df192","name":"Chris Liebing","score":98}
        ]}
    """.trimIndent()

    @Test
    fun `listenbrainz payload yields entries, counts and real window bounds`() {
        val chart = parseSitewide(listenBrainzBody)

        assertEquals(2, chart.entries.size)
        assertEquals(1784505600L, chart.fromTs)
        assertEquals(1785110400L, chart.toTs)

        val first = chart.entries[0]
        assertEquals(1, first.rank)
        assertEquals("SWIM", first.title)
        assertEquals("BTS", first.artistName)
        assertEquals(216022L, first.listenCount)
        assertEquals("6f33dc05-cdc0-4a2f-8039-e8fed082eec6", first.recordingMbid)
        assertNotNull("cover art is addressable from caa_id + release mbid", first.artworkUrl)
        assertTrue(first.artworkUrl!!.contains("2f9b427d-cc6e-4465-9f70-9d2109381db0"))
    }

    @Test
    fun `a row without cover art ids simply has no artwork`() {
        // Not an error: most of the long tail has no art, and inventing a URL
        // that 404s is worse than a blank square.
        val second = parseSitewide(listenBrainzBody).entries[1]
        assertNull(second.artworkUrl)
        assertEquals("AIROD", second.artistName)
        assertEquals(91L, second.listenCount)
    }

    @Test
    fun `lastfm payload yields ranked entries and the largest image`() {
        val entries = parseTagTopTracks(lastFmBody)

        assertEquals(2, entries.size)
        assertEquals(1, entries[0].rank)
        assertEquals("Windowlicker", entries[0].title)
        assertEquals("Aphex Twin", entries[0].artistName)
        // The last non-blank image is the biggest one Last.fm offers.
        assertEquals("https://example.test/large.png", entries[0].artworkUrl)
        assertEquals(2, entries[1].rank)
        assertNull("no image array at all is fine", entries[1].artworkUrl)
    }

    @Test
    fun `tag charts carry no listen counts`() {
        // tag_getTopTracks reports rank but not playcount, so the UI must not
        // render a count column for this source. Zero is the honest value.
        assertTrue(parseTagTopTracks(lastFmBody).all { it.listenCount == 0L })
    }

    @Test
    fun `musicbrainz payload yields artist names`() {
        assertEquals(listOf("AIROD", "Chris Liebing"), parseArtistsForTag(musicBrainzBody))
    }

    @Test
    fun `rows missing a title or artist are dropped rather than rendered blank`() {
        val body = """
            {"tracks":{"track":[
              {"name":"","artist":{"name":"Someone"}},
              {"name":"Real Track","artist":{"name":""}},
              {"name":"Kept","artist":{"name":"Kept Artist"}}
            ]}}
        """.trimIndent()
        val entries = parseTagTopTracks(body)
        assertEquals(1, entries.size)
        assertEquals("Kept", entries[0].title)
    }

    @Test
    fun `an empty or shapeless payload yields an empty chart, not a throw`() {
        assertEquals(0, parseSitewide("""{"payload":{}}""").entries.size)
        assertEquals(0, parseTagTopTracks("""{}""").size)
        assertEquals(0, parseArtistsForTag("""{"count":0}""").size)
    }
}
