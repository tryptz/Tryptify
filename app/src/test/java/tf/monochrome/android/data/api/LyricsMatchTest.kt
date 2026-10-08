package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchTest {

    private data class Candidate(val title: String, val artist: String, val durationMs: Long?)

    private fun pick(query: LyricsQuery, vararg candidates: Candidate): Candidate? =
        LyricsMatch.pick(query, candidates.toList(), { it.title }, { it.artist }, { it.durationMs })

    @Test
    fun `title variants peel version decorations most specific first`() {
        assertEquals(
            listOf("Song — Radio Edit", "Song"),
            LyricsMatch.titleVariants("Song — Radio Edit"),
        )
        assertEquals(
            listOf("Bohemian Rhapsody - Remastered 2011", "Bohemian Rhapsody"),
            LyricsMatch.titleVariants("Bohemian Rhapsody - Remastered 2011"),
        )
        assertEquals("Blinding Lights", LyricsMatch.titleVariants("Blinding Lights (feat. Someone)").last())
        // A dash that is part of the title is not a version tag.
        assertEquals(listOf("Part I - The Beginning"), LyricsMatch.titleVariants("Part I - The Beginning"))
    }

    @Test
    fun `a credit list splits into its artists`() {
        assertEquals(listOf("queen", "david bowie"), LyricsMatch.artists("Queen, David Bowie"))
        assertEquals(listOf("luis fonsi", "daddy yankee"), LyricsMatch.artists("Luis Fonsi feat. Daddy Yankee"))
        assertEquals("Queen", LyricsMatch.primaryArtist("Queen & David Bowie"))
    }

    @Test
    fun `accents and case do not stop an artist match`() {
        assertTrue(LyricsMatch.artistMatches("ROSALÍA", "The Weeknd, Rosalia"))
        assertFalse(LyricsMatch.artistMatches("Queen", "Boyce Avenue"))
    }

    @Test
    fun `a runtime outside the tolerance is another recording`() {
        val query = LyricsQuery("アイドル", "YOASOBI", durationMs = 213_000)
        // Kugou's real candidates for this song: none is the 3:33 studio cut.
        assertNull(
            pick(
                query,
                Candidate("アイドル", "YOASOBI", 241_841),
                Candidate("アイドル", "YOASOBI", 287_817),
                Candidate("アイドル", "YOASOBI", 89_965),
            ),
        )
        assertEquals(
            213_500L,
            pick(query, Candidate("アイドル", "YOASOBI", 241_841), Candidate("アイドル", "YOASOBI", 213_500))?.durationMs,
        )
    }

    @Test
    fun `a remix or live take is rejected unless the track is one`() {
        val query = LyricsQuery("Blinding Lights", "The Weeknd", durationMs = 200_000)
        assertNull(pick(query, Candidate("Blinding Lights (Major Lazer Remix)", "The Weeknd", 199_000)))
        assertNull(pick(query, Candidate("Blinding Lights - Live", "The Weeknd", null)))
        val live = LyricsQuery("Blinding Lights (Live)", "The Weeknd", durationMs = 253_000)
        assertEquals("Blinding Lights (Live)", pick(live, Candidate("Blinding Lights (Live)", "The Weeknd", 253_365))?.title)
    }

    @Test
    fun `a cover by another artist needs the runtime to vouch for it`() {
        val query = LyricsQuery("Blinding Lights", "The Weeknd")
        // No runtime on either side and a different artist: nothing confirms it.
        assertNull(pick(query, Candidate("Blinding Lights", "Boyce Avenue", null)))
    }

    @Test
    fun `an agreeing runtime confirms a credit spelt in another script`() {
        val query = LyricsQuery("晴天", "周杰倫", durationMs = 269_000)
        assertEquals("周杰伦", pick(query, Candidate("晴天", "周杰伦", 269_500))?.artist)
    }

    @Test
    fun `an exact title outranks a closer runtime`() {
        val query = LyricsQuery("Love", "Artist", durationMs = 200_000)
        val chosen = pick(
            query,
            Candidate("Love Me Do", "Artist", 200_000),
            Candidate("Love", "Artist", 201_500),
        )
        assertEquals("Love", chosen?.title)
    }

    @Test
    fun `a short title is not matched by containment`() {
        assertFalse(LyricsMatch.titleMatches("Love", "Lovely"))
        assertTrue(LyricsMatch.titleMatches("Motion Sickness", "Motion Sickness (Remastered)"))
    }
}
