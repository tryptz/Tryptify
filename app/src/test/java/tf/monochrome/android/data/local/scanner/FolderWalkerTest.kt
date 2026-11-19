package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library's own reading of its folders, and how it is put together with
 * Android's index. FolderIndexerTest holds the rest of the walk's rules.
 */
class FolderWalkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, bytes: Int = 1): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun names(files: List<WalkedFile>) = files.map { it.path.removePrefix(tmp.root.absolutePath + "/") }.toSet()

    @Test
    fun `each file comes with its size and its date to the second`() {
        val song = file("Music/Song.flac", bytes = 1234)
        song.setLastModified(1_700_000_123_456)
        val walked = FolderWalker.walk(tmp.root, emptySet()).single()
        assertEquals(song.absolutePath, walked.path)
        assertEquals(1234L, walked.sizeBytes)
        // Android's index keeps seconds; a date kept finer would never equal its.
        assertEquals(1_700_000_123_000, walked.modifiedMs)
    }

    @Test
    fun `the folder the listener picked is read even when it says nomedia`() {
        file("Picked/.nomedia")
        file("Picked/Song.mp3")
        file("Picked/Voice notes/.nomedia")
        file("Picked/Voice notes/note.opus")
        val hidden = mutableListOf<String>()
        assertEquals(setOf("Song.mp3"), FolderWalker.walk(File(tmp.root, "Picked"), emptySet(), hidden)
            .map { it.path.substringAfterLast('/') }.toSet())
        assertEquals(listOf(File(tmp.root, "Picked/Voice notes").absolutePath), hidden)
    }

    @Test
    fun `hidden files are not songs`() {
        file("Music/Song.mp3")
        file("Music/._Song.mp3")
        file("Music/.trashed-Song.mp3")
        assertEquals(setOf("Music/Song.mp3"), names(FolderWalker.walk(tmp.root, emptySet())))
    }

    @Test
    fun `a folder named like a song is a folder`() {
        file("Music/Album.flac/01.flac")
        assertEquals(setOf("Music/Album.flac/01.flac"), names(FolderWalker.walk(tmp.root, emptySet())))
    }

    @Test
    fun `a file that is not a folder is no root`() {
        val song = file("Song.mp3")
        assertTrue(FolderWalker.walk(song, emptySet()).isEmpty())
    }

    private fun indexed(path: String, size: Long = 9, modified: Long = 9_000) = AudioFileInfo(
        absolutePath = path, displayName = path.substringAfterLast('/'), mimeType = "audio/flac",
        sizeBytes = size, dateModified = modified, duration = 1000, uri = null,
    )

    @Test
    fun `a walked file is taken as walked, and one only Android knows is kept while it exists`() {
        val walked = listOf(WalkedFile("/m/a.flac", 100, 5_000), WalkedFile("/m/new.flac", 200, 6_000))
        val index = listOf(indexed("/m/a.flac"), indexed("/m/hidden.flac"), indexed("/m/deleted.flac"))
        val merged = LibraryFiles.merge(walked, index) { it != "/m/deleted.flac" }
        assertEquals(listOf("/m/a.flac", "/m/new.flac", "/m/hidden.flac"), merged.map { it.absolutePath })
        val a = merged.first()
        assertEquals("the file's own size and date, read just now", 100L to 5_000L, a.sizeBytes to a.dateModified)
        assertEquals("a.flac", a.displayName)
    }

    @Test
    fun `with nothing walked, Android's rows stand as they are`() {
        val merged = LibraryFiles.merge(emptyList(), listOf(indexed("/m/a.flac", size = 7))) { true }
        assertEquals(7L, merged.single().sizeBytes)
    }
}
