package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The folder walk that finds the files to ask Android to index (#136). What it
 * misses is invisible to every scan, and what it wrongly includes is asked for
 * again on every scan, so both directions are pinned here.
 */
class FolderIndexerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeText("x") }

    private fun walk(root: File = tmp.root, excluded: Set<String> = emptySet(), nomedia: MutableList<String>? = null) =
        FolderIndexer.walkAudioFiles(root, excluded, nomedia).map { it.removePrefix(tmp.root.absolutePath + "/") }.toSet()

    @Test
    fun `audio is found at any depth, by extension in any case`() {
        file("Artist/Album/01 One.flac")
        file("Artist/Album/Disc 2/01 Two.MP3")
        file("Loose.m4a")
        assertEquals(setOf("Artist/Album/01 One.flac", "Artist/Album/Disc 2/01 Two.MP3", "Loose.m4a"), walk())
    }

    @Test
    fun `covers, lyrics and videos are not audio`() {
        file("Album/cover.jpg")
        file("Album/01.lrc")
        file("Album/clip.mp4")
        file("Album/manifest.mpd")
        file("Album/01.opus")
        assertEquals(setOf("Album/01.opus"), walk())
    }

    @Test
    fun `a nomedia folder is skipped with everything under it, and reported`() {
        file("Music/Kept.flac")
        file("Music/WhatsApp Audio/.nomedia")
        file("Music/WhatsApp Audio/note.opus")
        file("Music/WhatsApp Audio/Sent/sent.opus")
        val nomedia = mutableListOf<String>()
        assertEquals(setOf("Music/Kept.flac"), walk(nomedia = nomedia))
        assertEquals(listOf(File(tmp.root, "Music/WhatsApp Audio").absolutePath), nomedia)
    }

    @Test
    fun `hidden folders are skipped`() {
        file("Music/.trash/old.flac")
        file("Music/new.flac")
        assertEquals(setOf("Music/new.flac"), walk())
    }

    @Test
    fun `excluded folders are skipped, by folder boundary`() {
        file("Music/Live/a.flac")
        file("Music/Live2/b.flac")
        val excluded = setOf(File(tmp.root, "Music/Live").absolutePath)
        // /Music/Live excluded must not take /Music/Live2 with it.
        assertEquals(setOf("Music/Live2/b.flac"), walk(excluded = excluded))
    }

    @Test
    fun `a root that does not exist or cannot be listed is empty, not an error`() {
        assertTrue(FolderIndexer.walkAudioFiles(File(tmp.root, "gone"), emptySet()).isEmpty())
    }

    @Test
    fun `a link back up the tree ends`() {
        file("Music/a.flac")
        Files.createSymbolicLink(File(tmp.root, "Music/loop").toPath(), File(tmp.root, "Music").toPath())
        // Found once; the link is the same folder and is not walked again.
        assertEquals(setOf("Music/a.flac"), walk())
    }

    @Test
    fun `the wait for Android grows with the files, within bounds`() {
        assertEquals(15_000L, FolderIndexer.timeoutFor(0))
        assertEquals(40_000L, FolderIndexer.timeoutFor(100))
        assertEquals(180_000L, FolderIndexer.timeoutFor(100_000))
    }
}
