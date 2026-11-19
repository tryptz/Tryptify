package tf.monochrome.android.share

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.api.QobuzTrackMatch
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * A file of the listener's own, shared as the catalogue's song it is a copy
 * of ([ShareLinks.localLink]): by its ISRC, else by its name and length.
 */
class LocalShareLinkTest {
    private fun track(id: Long, title: String, artist: String, seconds: Int, album: Long = 0) = Track(
        id = id,
        title = title,
        duration = seconds,
        artist = Artist(id = 1, name = artist),
        album = if (album > 0) Album(id = album, title = "Good Girl Gone Bad") else null,
    )

    private val file = track(-77, "Umbrella", "Rihanna", 276)
    private val never: suspend (String) -> List<Track> = { error("the catalogue was searched by name") }

    @Test
    fun `a file with an ISRC is the Qobuz song with that code`() = runBlocking {
        var asked: String? = null
        val link = ShareLinks.localLink(
            file, "us-um7-07-05412",
            byIsrc = { asked = it; QobuzTrackMatch(trackId = 3141, albumSlug = "abc123", artistId = 9) },
            byName = never,
        )!!
        assertEquals("USUM70705412", asked)  // as the tag wrote it, made plain
        assertEquals(SharedLink.Source.QOBUZ, link.source)
        assertEquals("abc123", link.album)
        assertEquals(3141L, link.track)
        assertEquals("USUM70705412", link.isrc)
        assertEquals(276, link.durationSec)
        // And it is a link the app itself reads back.
        val url = java.net.URI(SharedLink.url(link))
        val query = url.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val read = SharedLink.parse(url.scheme, url.host, url.path) { query[it] }!!
        assertEquals(link.source, read.source)
        assertEquals(link.album, read.album)
        assertEquals(link.track, read.track)
        assertEquals(link.isrc, read.isrc)
    }

    @Test
    fun `without a code, or where Qobuz has none for it, the song is found by name and length`() = runBlocking {
        val catalogue = listOf(
            track(11, "Umbrella (Live)", "Rihanna", 300, album = 5),
            track(12, "Umbrella", "Rihanna feat. JAY-Z", 275, album = 6),
            track(13, "Umbrella", "Some Cover Band", 276, album = 7),
        )
        var query: String? = null
        val byName: suspend (String) -> List<Track> = { query = it; catalogue }
        val untagged = ShareLinks.localLink(file, null, byIsrc = { error("no code to ask with") }, byName = byName)!!
        assertEquals("Rihanna Umbrella", query)
        assertEquals(SharedLink.Source.TIDAL, untagged.source)
        assertEquals(12L, untagged.track)
        assertEquals("6", untagged.album)
        assertNull(untagged.isrc)
        // A code Qobuz does not know still goes in the link: the other end may find it.
        val unknown = ShareLinks.localLink(file, "USUM70705412", byIsrc = { null }, byName = byName)!!
        assertEquals(12L, unknown.track)
        assertEquals("USUM70705412", unknown.isrc)
        // A match with no album cannot be linked: by name then.
        val noAlbum = ShareLinks.localLink(file, "USUM70705412", byIsrc = { QobuzTrackMatch(1, null, null) }, byName = byName)!!
        assertEquals(SharedLink.Source.TIDAL, noAlbum.source)
    }

    @Test
    fun `another version is another length and is not the song`() {
        val live = track(11, "Umbrella", "Rihanna", 300, album = 5)
        val remaster = track(12, "Umbrella (Remastered 2017) [Explicit]", "RIHANNA", 277, album = 6)
        val exact = track(13, "Umbrella", "Rihanna", 276, album = 7)
        assertNull(ShareLinks.sameSong(listOf(live), file))
        // Notes in brackets and case are set aside; the nearest length wins.
        assertEquals(12L, ShareLinks.sameSong(listOf(live, remaster), file)!!.id)
        assertEquals(13L, ShareLinks.sameSong(listOf(remaster, exact, live), file)!!.id)
        // Another song, another artist, or a file that does not know its length: nothing.
        assertNull(ShareLinks.sameSong(listOf(track(14, "Umbrellas", "Rihanna", 276, 8)), file))
        assertNull(ShareLinks.sameSong(listOf(track(15, "Umbrella", "The Baseballs", 276, 8)), file))
        assertNull(ShareLinks.sameSong(listOf(exact), file.copy(duration = 0)))
        assertTrue(ShareLinks.SAME_LENGTH_SEC <= 3)
    }

    @Test
    fun `a long version is found by its own length, not the single's`() = runBlocking {
        // An eight-minute mix on the phone: the catalogue's four-minute single is not it.
        val mix = track(-5, "Turn the Tide", "Sylver", 480)
        val catalogue = listOf(
            track(21, "Turn the Tide", "Sylver", 244, album = 3),
            track(22, "Turn the Tide (Extended Mix)", "Sylver", 481, album = 4),
        )
        val link = ShareLinks.localLink(mix, null, byIsrc = { null }, byName = { catalogue })!!
        assertEquals(22L, link.track)
        assertEquals(480, link.durationSec)
        assertNull(ShareLinks.localLink(mix, null, byIsrc = { null }, byName = { catalogue.take(1) }))
    }

    @Test
    fun `a recording of one's own has no link`() = runBlocking {
        assertNull(ShareLinks.localLink(file, null, byIsrc = { null }, byName = { emptyList() }))
        assertNull(ShareLinks.localLink(file.copy(title = ""), "nonsense", byIsrc = { error("not a code") }, byName = never))
        // What is not an ISRC is not asked about.
        assertNull(SharedLink.isrcOf("12345"))
        assertNull(SharedLink.isrcOf(null))
        assertEquals("GBAYE0601498", SharedLink.isrcOf(" gb-aye-06-01498 "))
    }

    @Test
    fun `a download is linked by the number it was downloaded under, when that is its song`() {
        val file = track(0, "Flying High", "DJ Splash", 215)
        val theirs = track(77, "Flying High (Original Mix)", "DJ Splash", 216, album = 900)
        val link = ShareLinks.exactLink(file, theirs)
        assertEquals(SharedLink.Source.TIDAL, link?.source)
        assertEquals("900", link?.album)
        assertEquals(77L, link?.track)
        // A Qobuz or Deezer download's number names some other song on TIDAL: no link from it.
        assertNull(ShareLinks.exactLink(file, track(77, "Something Else", "Someone", 215, album = 900)))
        assertNull(ShareLinks.exactLink(file, track(77, "Flying High", "DJ Splash", 400, album = 900)))
        // And none without the album the link names.
        assertNull(ShareLinks.exactLink(file, track(77, "Flying High", "DJ Splash", 215)))
    }
}
