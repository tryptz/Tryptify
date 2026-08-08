package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * Rules for "play the on-device copy instead of streaming". The two failure
 * modes pull in opposite directions — miss a file the user has (and stream over
 * mobile data for nothing) versus play the wrong recording — so both are
 * covered here.
 */
class LocalTrackMatchingTest {

    private fun localFile(
        title: String,
        artist: String,
        duration: Int = 200,
        album: String? = "An Album",
        albumArtist: String? = null,
        codec: AudioCodec = AudioCodec.FLAC,
        path: String = "/music/${title.hashCode()}.flac",
    ) = UnifiedTrack(
        id = "local_${path.hashCode()}",
        title = title,
        durationSeconds = duration,
        artistName = artist,
        artistNames = listOfNotNull(artist, albumArtist).distinct(),
        albumArtistName = albumArtist,
        albumTitle = album,
        codec = codec,
        sampleRate = 44_100,
        bitDepth = if (codec == AudioCodec.FLAC) 16 else null,
        source = PlaybackSource.LocalFile(
            filePath = path,
            codec = codec,
            sampleRate = 44_100,
            bitDepth = if (codec == AudioCodec.FLAC) 16 else null,
        ),
        sourceType = SourceType.LOCAL,
    )

    private fun pick(
        candidates: List<UnifiedTrack>,
        title: String,
        artist: String,
        album: String? = "An Album",
        duration: Int = 200,
    ) = LocalTrackMatching.pickBest(candidates, title, artist, album, duration)

    // --- accepts the same recording ---------------------------------------

    @Test
    fun `matches an identically tagged file`() {
        val file = localFile("Windowlicker", "Aphex Twin")
        assertEquals(file, pick(listOf(file), "Windowlicker", "Aphex Twin"))
    }

    @Test
    fun `catalogue version suffixes do not block a match`() {
        val file = localFile("Karma Police", "Radiohead")
        assertEquals(
            file,
            pick(listOf(file), "Karma Police (Remastered 2016)", "Radiohead"),
        )
    }

    @Test
    fun `em dash version suffix does not block a match`() {
        val file = localFile("Teardrop", "Massive Attack")
        assertEquals(file, pick(listOf(file), "Teardrop — 2012 Mix", "Massive Attack"))
    }

    @Test
    fun `a locally tagged version suffix does not block a match either`() {
        val file = localFile("Karma Police (Remastered)", "Radiohead")
        assertEquals(file, pick(listOf(file), "Karma Police", "Radiohead"))
    }

    @Test
    fun `diacritics and punctuation are ignored`() {
        val file = localFile("Dont Stop", "Bjork")
        assertEquals(file, pick(listOf(file), "Don't Stop", "Björk"))
    }

    @Test
    fun `a shared artist credit is enough`() {
        val file = localFile("Under Pressure", "Queen")
        assertEquals(file, pick(listOf(file), "Under Pressure", "Queen & David Bowie"))
    }

    @Test
    fun `the album artist tag can carry the match`() {
        val file = localFile("Sing", "Various Artists", albumArtist = "Blur")
        assertEquals(file, pick(listOf(file), "Sing", "Blur"))
    }

    @Test
    fun `a small runtime difference is tolerated`() {
        val file = localFile("Roads", "Portishead", duration = 302)
        assertEquals(file, pick(listOf(file), "Roads", "Portishead", duration = 300))
    }

    @Test
    fun `an unknown runtime on either side cannot veto a match`() {
        val file = localFile("Roads", "Portishead", duration = 0)
        assertEquals(file, pick(listOf(file), "Roads", "Portishead", duration = 300))
    }

    // --- rejects everything else ------------------------------------------

    @Test
    fun `a different artist is not a match`() {
        val file = localFile("Hurt", "Nine Inch Nails")
        assertNull(pick(listOf(file), "Hurt", "Johnny Cash"))
    }

    @Test
    fun `a different song with a shared title prefix is not a match`() {
        val file = localFile("Love Story", "Taylor Swift")
        assertNull(pick(listOf(file), "Love", "Taylor Swift"))
    }

    @Test
    fun `a much longer take is not a match`() {
        // The archetypal wrong-recording case: a live version under the same
        // title and artist, minutes longer than the studio cut.
        val live = localFile("Whole Lotta Love", "Led Zeppelin", duration = 800)
        assertNull(pick(listOf(live), "Whole Lotta Love", "Led Zeppelin", duration = 333))
    }

    @Test
    fun `nothing matches when the artist is unknown`() {
        val file = localFile("Untitled", "Unknown Artist")
        assertNull(pick(listOf(file), "Untitled", ""))
    }

    @Test
    fun `an empty library matches nothing`() {
        assertNull(pick(emptyList(), "Windowlicker", "Aphex Twin"))
    }

    // --- ranking ----------------------------------------------------------

    @Test
    fun `the lossless copy wins over the lossy one`() {
        val mp3 = localFile("Alive", "Pearl Jam", codec = AudioCodec.MP3, path = "/music/a.mp3")
        val flac = localFile("Alive", "Pearl Jam", codec = AudioCodec.FLAC, path = "/music/a.flac")
        assertEquals(flac, pick(listOf(mp3, flac), "Alive", "Pearl Jam"))
    }

    @Test
    fun `the copy from the right album wins`() {
        val compilation = localFile(
            "Alive", "Pearl Jam", album = "Greatest Hits", path = "/music/hits.flac",
        )
        val original = localFile("Alive", "Pearl Jam", album = "Ten", path = "/music/ten.flac")
        assertEquals(
            original,
            pick(listOf(compilation, original), "Alive", "Pearl Jam", album = "Ten"),
        )
    }

    @Test
    fun `the closest runtime wins over a merely acceptable one`() {
        val loose = localFile("Alive", "Pearl Jam", duration = 344, path = "/music/loose.flac")
        val exact = localFile("Alive", "Pearl Jam", duration = 340, path = "/music/exact.flac")
        assertEquals(
            exact,
            pick(listOf(loose, exact), "Alive", "Pearl Jam", duration = 340),
        )
    }

    // --- helpers ----------------------------------------------------------

    @Test
    fun `baseTitle strips trailing qualifiers but never empties the title`() {
        assertEquals("Song", LocalTrackMatching.baseTitle("Song (Remastered) [Explicit]"))
        assertEquals("(Intro)", LocalTrackMatching.baseTitle("(Intro)"))
    }

    @Test
    fun `baseTitle leaves hyphenated titles alone`() {
        // "Death - Pierce Me" is the whole title; trimming at the hyphen would
        // collapse it onto any track called "Death".
        assertEquals("Death - Pierce Me", LocalTrackMatching.baseTitle("Death - Pierce Me"))
    }

    @Test
    fun `artistParts splits credits without breaking single names`() {
        assertEquals(setOf("queen", "david bowie"), LocalTrackMatching.artistParts("Queen & David Bowie"))
        assertEquals(setOf("ac dc"), LocalTrackMatching.artistParts("AC/DC"))
        assertEquals(setOf("malcolm x"), LocalTrackMatching.artistParts("Malcolm X"))
        assertEquals(setOf("drake", "future"), LocalTrackMatching.artistParts("Drake feat. Future"))
    }

    @Test
    fun `titlePattern escapes LIKE wildcards`() {
        assertEquals("100\\% Endurance%", LocalTrackMatching.titlePattern("100% Endurance"))
        assertEquals("a\\_b%", LocalTrackMatching.titlePattern("a_b"))
        assertNull(LocalTrackMatching.titlePattern("   "))
    }

    @Test
    fun `durationsAgree only tolerates small differences`() {
        assertTrue(LocalTrackMatching.durationsAgree(200, 203))
        assertTrue(LocalTrackMatching.durationsAgree(0, 203))
        assertFalse(LocalTrackMatching.durationsAgree(200, 260))
    }
}
