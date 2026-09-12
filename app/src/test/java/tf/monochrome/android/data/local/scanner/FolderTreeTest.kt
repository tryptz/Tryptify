package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder tree the browser walks.
 *
 * These exist because the tree used to have holes. `rebuildFolders` keyed only
 * on the folder holding each *file*, so a folder containing nothing but other
 * folders was never written down — and `getSubfolders` finds children by
 * `parentPath`, so one missing link cut off the entire subtree beneath it. A
 * user's `/storage/emulated/0/Monochrome+` opened to a blank screen for exactly
 * this reason: its music was two levels down.
 *
 * The first three tests are that bug, at one, two and three levels of nesting.
 */
class FolderTreeTest {

    private val root = "/storage/emulated/0/Monochrome+"

    private fun tree(vararg paths: String) = buildFolderTree(paths.toList())

    private fun childrenOf(folders: List<tf.monochrome.android.data.local.db.LocalFolderEntity>, parent: String) =
        folders.filter { it.parentPath == parent }.map { it.path }.sorted()

    @Test
    fun `a track directly in the folder gives the folder a row`() {
        val folders = tree("$root/a.mp3")
        assertTrue(folders.any { it.path == root })
    }

    @Test
    fun `a folder one level down is reachable`() {
        val folders = tree("$root/Downloads/a.mp3")
        assertEquals(listOf("$root/Downloads"), childrenOf(folders, root))
    }

    @Test
    fun `a folder two levels down is reachable — the blank-screen case`() {
        // This is the regression. The old builder wrote only ".../Some/Deeper",
        // leaving ".../Some" with no row, so listing `root` returned nothing at
        // all and every track below it was unreachable.
        val folders = tree("$root/Some/Deeper/a.mp3")
        assertEquals(listOf("$root/Some"), childrenOf(folders, root))
        assertEquals(listOf("$root/Some/Deeper"), childrenOf(folders, "$root/Some"))
    }

    @Test
    fun `every ancestor up to the top gets a row`() {
        val folders = tree("$root/a/b/c/x.mp3").map { it.path }
        for (expected in listOf(
            "/storage", "/storage/emulated", "/storage/emulated/0",
            root, "$root/a", "$root/a/b", "$root/a/b/c",
        )) {
            assertTrue("no row for $expected", expected in folders)
        }
    }

    @Test
    fun `the top of the path keeps an empty parent, not null`() {
        // getRootFolders() selects on `parentPath IS NULL`. A null here would
        // make /storage a root folder and put a row nobody asked for at the top
        // of the Folders tab.
        val storage = tree("$root/a.mp3").single { it.path == "/storage" }
        assertEquals("", storage.parentPath)
    }

    @Test
    fun `a track count is the whole subtree, not just the direct files`() {
        val folders = tree(
            "$root/one.mp3",
            "$root/Some/two.mp3",
            "$root/Some/Deeper/three.mp3",
            "$root/Some/Deeper/four.mp3",
        )
        fun count(path: String) = folders.single { it.path == path }.trackCount
        assertEquals(4, count(root))
        assertEquals(3, count("$root/Some"))
        assertEquals(2, count("$root/Some/Deeper"))
    }

    @Test
    fun `a leaf folder's count is unchanged by the subtree rule`() {
        // With no subfolders the recursive and direct counts are the same
        // number, so nothing already on screen moves.
        val folders = tree("$root/a.mp3", "$root/b.mp3", "$root/c.mp3")
        assertEquals(3, folders.single { it.path == root }.trackCount)
    }

    @Test
    fun `each folder appears exactly once however many tracks it holds`() {
        val folders = tree("$root/a.mp3", "$root/b.mp3", "$root/Sub/c.mp3")
        assertEquals(folders.map { it.path }, folders.map { it.path }.distinct())
    }

    @Test
    fun `no tracks means no folders`() {
        assertEquals(emptyList<String>(), buildFolderTree(emptyList()).map { it.path })
    }

    @Test
    fun `a bare filename with no folder is skipped rather than crashing`() {
        assertEquals(emptyList<String>(), buildFolderTree(listOf("orphan.mp3")).map { it.path })
    }

    @Test
    fun `displayName is the last segment`() {
        val folders = tree("$root/Some/a.mp3")
        assertEquals("Some", folders.single { it.path == "$root/Some" }.displayName)
        assertEquals("Monochrome+", folders.single { it.path == root }.displayName)
    }
}
