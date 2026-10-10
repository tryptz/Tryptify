package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files in the download folder that the app did not record get an id made
 * from their path, which keys their row in the Downloads list. Two rows with
 * one key crash that list, and the id has to stay the same across rescans,
 * because a queued track refers to it.
 */
class DownloadsPathIdTest {

    @Test
    fun `the same path always gets the same negative id`() {
        val path = "/storage/emulated/0/Music/Queen/Greatest Hits/01. Bohemian Rhapsody.flac"
        assertEquals(DownloadsViewModel.pathId(path), DownloadsViewModel.pathId(path))
        assertTrue(DownloadsViewModel.pathId(path) < 0)
    }

    @Test
    fun `a large download folder gets one id per file`() {
        // The size where the old 31-bit hash collided about one time in six.
        val paths = (1..20_000).map { "/storage/emulated/0/Music/Artist ${it / 12}/Album/${"%02d".format(it % 12)}. Track $it.flac" }
        val ids = paths.map(DownloadsViewModel::pathId)
        assertEquals(paths.size, ids.toSet().size)
        assertTrue(ids.all { it < 0 })
    }
}
