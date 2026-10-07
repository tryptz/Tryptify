package tf.monochrome.android.data.local.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Downloads list asks for a folder's tracks as `filePath >= prefix AND
 * filePath < folderRangeEnd(prefix)`, so the unique filePath index can serve
 * it. These pin that the range is the folder, no more and no less, compared
 * the way SQLite compares text by default (byte order, as Kotlin's for these).
 */
class FolderRangeEndTest {

    private val prefix = "/storage/emulated/0/Music/"

    private fun inRange(path: String) = path >= prefix && path < folderRangeEnd(prefix)

    @Test
    fun `everything under the folder is in range`() {
        assertTrue(inRange("/storage/emulated/0/Music/a.flac"))
        assertTrue(inRange("/storage/emulated/0/Music/Queen/Greatest Hits/01. Bohemian Rhapsody.flac"))
        assertTrue(inRange("/storage/emulated/0/Music/~zz.flac"))
    }

    @Test
    fun `neighbours of the folder are not`() {
        assertFalse(inRange("/storage/emulated/0/Music"))
        assertFalse(inRange("/storage/emulated/0/Music2/a.flac"))
        assertFalse(inRange("/storage/emulated/0/Music-old/a.flac"))
        assertFalse(inRange("/storage/emulated/0/Musia/a.flac"))
        assertFalse(inRange("/storage/emulated/0/Download/a.flac"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a prefix that is not a folder is refused`() {
        folderRangeEnd("/storage/emulated/0/Music")
    }
}
