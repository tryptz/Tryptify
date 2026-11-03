package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/** The lyrics lookup never sends a hashed id to TIDAL, and searches a local song under its own artist. */
class LyricsLookupTest {

    @Test
    fun `ids that are not TIDAL's skip TIDAL by id`() {
        for (s in listOf(
            SourceType.LOCAL, SourceType.COLLECTION, SourceType.QOBUZ, SourceType.DEEZER,
            SourceType.YOUTUBE, SourceType.SOUNDCLOUD,
        )) {
            assertTrue("$s", LyricsLookup.skipsTidalById(s))
        }
        assertFalse(LyricsLookup.skipsTidalById(SourceType.API))
        assertFalse(LyricsLookup.skipsTidalById(null))
    }

    private val compilationTrack = Track(id = 1, title = "Jolene", duration = 200, artist = Artist(id = 9, name = "Various Artists"))

    private fun local(artistNames: List<String>) = UnifiedTrack(
        id = "local_7",
        title = "Jolene",
        durationSeconds = 200,
        artistName = "Various Artists",
        artistNames = artistNames,
        source = PlaybackSource.LocalFile("/m/x.flac", AudioCodec.FLAC, 44100),
        sourceType = SourceType.LOCAL,
    )

    @Test
    fun `a compilation track is searched under its own artist`() {
        val t = LyricsLookup.trackFor(compilationTrack, local(listOf("Dolly Parton", "Various Artists")))
        assertEquals("Dolly Parton", t.artist?.name)
        assertEquals("Jolene", t.title)
    }

    @Test
    fun `anything else is left as it is`() {
        assertSame(compilationTrack, LyricsLookup.trackFor(compilationTrack, null))
        assertSame(compilationTrack, LyricsLookup.trackFor(compilationTrack, local(emptyList())))
    }
}
