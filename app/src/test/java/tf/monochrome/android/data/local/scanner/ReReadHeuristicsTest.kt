package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.local.db.TrackScanInfo

class ReReadHeuristicsTest {

    private fun scanInfo(
        lastModified: Long = 1_000L,
        artworkCacheKey: String? = "/cache/artwork/abc.jpg",
        hasEmbeddedArt: Boolean = true,
        artist: String? = "Some Artist",
        title: String? = "Some Title"
    ) = TrackScanInfo(
        filePath = "/storage/emulated/0/Music/song.flac",
        lastModified = lastModified,
        artworkCacheKey = artworkCacheKey,
        hasEmbeddedArt = hasEmbeddedArt,
        artist = artist,
        title = title
    )

    private val artExists: (String) -> Boolean = { true }
    private val artMissing: (String) -> Boolean = { false }

    @Test
    fun `unknown file needs read`() {
        assertTrue(MediaScanner.needsReRead(null, 1_000L, artExists))
    }

    @Test
    fun `stale mtime needs read`() {
        assertTrue(MediaScanner.needsReRead(scanInfo(lastModified = 500L), 1_000L, artExists))
    }

    @Test
    fun `fresh row with intact artwork is skipped`() {
        assertFalse(MediaScanner.needsReRead(scanInfo(lastModified = 1_000L), 1_000L, artExists))
    }

    @Test
    fun `reaped artwork cache file forces re-read`() {
        assertTrue(MediaScanner.needsReRead(scanInfo(), 1_000L, artMissing))
    }

    @Test
    fun `artwork-less row is re-read so newer sidecar detection gets a chance`() {
        val info = scanInfo(hasEmbeddedArt = false, artworkCacheKey = null)
        assertTrue(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `art carried over from the old cache store forces re-read`() {
        // Legacy art is one unscaled copy per track file. Re-reading is what
        // replaces it with a downscaled copy shared across the album, so the
        // store compacts over the rescans the user already runs.
        val info = scanInfo(artworkCacheKey = "/data/.../files/artwork/legacy/abc.jpg")
        assertTrue(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `art written by the new store is left alone`() {
        val info = scanInfo(artworkCacheKey = "/data/.../files/artwork/abc.jpg")
        assertFalse(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `missing artist with derivable title forces re-read`() {
        val info = scanInfo(artist = null, title = "Artist - Title")
        assertTrue(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `missing artist with plain title is skipped`() {
        val info = scanInfo(artist = null, title = "Just A Title")
        assertFalse(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `file-name titles do not re-read an Artist - Title file name every scan`() {
        val info = scanInfo(artist = null, title = "Artist - Title")
        assertFalse(MediaScanner.needsReRead(info, 1_000L, artExists, titleFromFileName = true))
    }

    @Test
    fun `a number taken for an artist is healed by one scan, not by every scan`() {
        // "01 - Song" split by an older scan: healed while the heal is pending.
        val numbered = scanInfo(artist = "01")
        assertTrue(MediaScanner.needsReRead(numbered, 1_000L, artExists, healNumbering = true))
        // A tagged artist really named with a number reads back the same, so as
        // a rule of every scan it was re-read forever, under a new id each time.
        val band = scanInfo(artist = "311")
        assertTrue(MediaScanner.needsReRead(band, 1_000L, artExists, healNumbering = true))
        assertFalse(MediaScanner.needsReRead(band, 1_000L, artExists, healNumbering = false))
        assertFalse(MediaScanner.needsReRead(numbered, 1_000L, artExists, healNumbering = false))
        // A real artist is never re-read for this.
        assertFalse(MediaScanner.needsReRead(scanInfo(artist = "Sylver"), 1_000L, artExists, healNumbering = true))
    }

    @Test
    fun `the heal is pending from the first version on`() {
        // A library from before the heal existed stores 0.
        assertTrue(0 < MediaScanner.NUMBERING_HEAL_VERSION)
    }

    @Test
    fun `title from path drops the folder and the extension only`() {
        assertEquals(
            "Vogel im Kafig OpenJoc SharurDM gain",
            MediaScanner.titleFromPath("/storage/emulated/0/Music/Vogel im Kafig OpenJoc SharurDM gain.flac"),
        )
        assertEquals("v1.2 mix", MediaScanner.titleFromPath("/m/v1.2 mix.flac"))
    }

    @Test
    fun `a measured dynamic range survives a re-read of the same file, not a changed one`() {
        val measured = TrackScanInfo(
            filePath = "/music/a.flac", lastModified = 1_000L, artworkCacheKey = null,
            hasEmbeddedArt = false, artist = "A", title = "Song", dynamicRange = 9.4f,
        )
        // Re-read for its art, or a title setting: same file, same value.
        assertEquals(9.4f, MediaScanner.keptDynamicRange(measured, 1_000L))
        // Rewritten since: new audio, measured again.
        assertEquals(null, MediaScanner.keptDynamicRange(measured, 2_000L))
        // A file new to the library has none.
        assertEquals(null, MediaScanner.keptDynamicRange(null, 1_000L))
    }
}
