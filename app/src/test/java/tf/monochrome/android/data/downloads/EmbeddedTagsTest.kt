package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * What a download writes into its own tags is all a strict offline player has
 * to go on — a wrong DATE or a missing TRACKNUMBER shows up as a mis-sorted
 * album, so the decisions behind them are pinned here.
 */
class EmbeddedTagsTest {

    @Test
    fun `release date keeps the leading calendar date`() {
        assertEquals("2019-05-17", EmbeddedTags.releaseDate("2019-05-17"))
        assertEquals("2019-05-17", EmbeddedTags.releaseDate("2019-05-17T00:00:00.000+0000"))
        assertEquals("2019-05", EmbeddedTags.releaseDate("2019-05"))
        assertEquals("1969", EmbeddedTags.releaseDate(" 1969 "))
    }

    @Test
    fun `release date without a usable year is dropped`() {
        assertNull(EmbeddedTags.releaseDate(null))
        assertNull(EmbeddedTags.releaseDate(""))
        assertNull(EmbeddedTags.releaseDate("unknown"))
        assertNull(EmbeddedTags.releaseDate("0000-00-00"))
    }

    @Test
    fun `base title strips only the display version suffix`() {
        assertEquals("Song", EmbeddedTags.baseTitle("Song — Live", "Live"))
        assertEquals("Song — Live", EmbeddedTags.baseTitle("Song — Live", null))
        assertEquals("Song", EmbeddedTags.baseTitle("Song", "Remastered"))
    }

    @Test
    fun `image mime is sniffed from magic bytes`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()
        assertEquals("image/jpeg", EmbeddedTags.imageMime(jpeg))
        assertEquals("image/png", EmbeddedTags.imageMime(png))
        assertNull(EmbeddedTags.imageMime(webp))
        assertNull(EmbeddedTags.imageMime(ByteArray(0)))
    }

    @Test
    fun `download item carries album ordering and grouping fields`() {
        val track = Track(
            id = 7,
            title = "Song",
            artist = Artist(id = 1, name = "Guest feat. Host"),
            trackNumber = 4,
            volumeNumber = 2,
            album = Album(
                id = 9,
                title = "Record",
                artist = Artist(id = 2, name = "Host"),
                releaseDate = "2021-03-05",
                genre = "Jazz",
            ),
        )
        val item = DownloadItem.from(track)
        assertEquals(4, item.trackNumber)
        assertEquals(2, item.discNumber)
        assertEquals("Host", item.albumArtist)
        assertEquals("2021-03-05", item.releaseDate)
        assertEquals("Jazz", item.genre)
    }

    @Test
    fun `queue persisted before the tag fields existed still restores`() {
        val q = DownloadQueue()
        q.restore("""[{"trackId":1,"title":"Old","artistName":"Artist"}]""")
        val item = q.entries.value.single().item
        assertEquals(1L, item.trackId)
        assertNull(item.trackNumber)
        assertNull(item.albumArtist)
    }
}
