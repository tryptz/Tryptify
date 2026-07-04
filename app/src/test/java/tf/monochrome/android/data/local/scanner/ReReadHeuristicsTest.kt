package tf.monochrome.android.data.local.scanner

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
    fun `missing artist with derivable title forces re-read`() {
        val info = scanInfo(artist = null, title = "Artist - Title")
        assertTrue(MediaScanner.needsReRead(info, 1_000L, artExists))
    }

    @Test
    fun `missing artist with plain title is skipped`() {
        val info = scanInfo(artist = null, title = "Just A Title")
        assertFalse(MediaScanner.needsReRead(info, 1_000L, artExists))
    }
}
