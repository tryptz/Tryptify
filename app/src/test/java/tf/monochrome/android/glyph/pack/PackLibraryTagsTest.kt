package tf.monochrome.android.glyph.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.local.db.TrackScanInfo
import tf.monochrome.android.data.local.scanner.MediaScanner

class PackLibraryTagsTest {
    @Test
    fun `a pack by one artist is that artist's album, any other is various artists`() {
        assertEquals("Camellia", GlyphPackLibrary.packAlbumArtist(listOf("Camellia", "camellia ", "Camellia")))
        assertEquals(GlyphPackLibrary.VARIOUS_ARTISTS, GlyphPackLibrary.packAlbumArtist(listOf("Camellia", "xi")))
        assertEquals(GlyphPackLibrary.VARIOUS_ARTISTS, GlyphPackLibrary.packAlbumArtist(emptyList()))
    }

    private fun info(title: String?, artist: String?) = TrackScanInfo(
        filePath = "/storage/emulated/0/StepMania/Songs/P/S/song.ogg",
        lastModified = 1L,
        artworkCacheKey = null,
        hasEmbeddedArt = false,
        artist = artist,
        title = title,
    )

    private val tags = GlyphPackLibrary.LibraryTags(
        title = "Ghost", artist = "Camellia", album = "P", albumArtist = "Camellia", artPath = null,
    )

    @Test
    fun `a pack song read before it was linked is read again for its chart's tags`() {
        assertTrue(MediaScanner.needsPackRetag(info("song", null), tags))
        assertTrue(MediaScanner.needsPackRetag(info("Ghost", "Unknown Artist"), tags))
    }

    @Test
    fun `a pack song already showing its chart's tags, or a song of no pack, is left alone`() {
        assertFalse(MediaScanner.needsPackRetag(info("Ghost", "Camellia"), tags))
        assertFalse(MediaScanner.needsPackRetag(info("song", null), null))
        // A file never read is read anyway; this rule is not what reads it.
        assertFalse(MediaScanner.needsPackRetag(null, tags))
    }
}
