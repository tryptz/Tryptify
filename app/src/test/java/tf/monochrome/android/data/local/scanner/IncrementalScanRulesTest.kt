package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.local.db.TrackScanInfo

/**
 * The two decisions the incremental scan makes on its own, now that it runs
 * on every change to Android's audio index and at every launch (#136).
 */
class IncrementalScanRulesTest {

    private fun known(lastModified: Long) = TrackScanInfo(
        filePath = "/Music/a.flac",
        lastModified = lastModified,
        artworkCacheKey = null,
        hasEmbeddedArt = true,
        artist = "A",
        title = "a",
    )

    @Test
    fun `a path the library does not have is read, however old the file`() {
        // A copied or moved file keeps its date. Judged by date alone it was
        // skipped, and a moved song vanished from the library.
        assertTrue(MediaScanner.needsIncrementalRead(null, mediaStoreDateModified = 1_000L))
    }

    @Test
    fun `a file changed since it was read is read again`() {
        assertTrue(MediaScanner.needsIncrementalRead(known(1_000L), mediaStoreDateModified = 2_000L))
    }

    @Test
    fun `an unchanged file is left alone`() {
        assertFalse(MediaScanner.needsIncrementalRead(known(2_000L), mediaStoreDateModified = 2_000L))
    }

    @Test
    fun `files really going is pruned`() {
        assertTrue(MediaScanner.pruneLooksReal(libraryTracks = 500, toDelete = 0))
        assertTrue(MediaScanner.pruneLooksReal(libraryTracks = 500, toDelete = 12))
        assertTrue(MediaScanner.pruneLooksReal(libraryTracks = 500, toDelete = 250))
        // A small library can lose most of itself for real.
        assertTrue(MediaScanner.pruneLooksReal(libraryTracks = 10, toDelete = 9))
    }

    @Test
    fun `MediaStore answering empty never empties the library`() {
        // At boot, before storage is mounted, MediaStore lists nothing.
        assertFalse(MediaScanner.pruneLooksReal(libraryTracks = 500, toDelete = 500))
        assertFalse(MediaScanner.pruneLooksReal(libraryTracks = 3, toDelete = 3))
    }

    @Test
    fun `more than half of a real library is left for a full scan`() {
        assertFalse(MediaScanner.pruneLooksReal(libraryTracks = 500, toDelete = 251))
        assertFalse(MediaScanner.pruneLooksReal(libraryTracks = 20, toDelete = 11))
    }
}
