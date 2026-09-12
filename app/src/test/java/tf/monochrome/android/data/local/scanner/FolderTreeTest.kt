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

    // ── Where the Folders tab opens ─────────────────────────────────────
    //
    // The tab used to read getRootFolders(), which selects `parentPath IS NULL`
    // and matches nothing — parentPath is substringBeforeLast('/'), which for a
    // top-level /storage is "" and not null. So the tab listed only hand-added
    // roots, and a folder of hundreds of scanned songs never showed up in it
    // while the same songs filled the Songs list.

    private val sd = "/storage/emulated/0"

    private fun rootPaths(vararg paths: String) =
        folderBrowseRoots(buildFolderTree(paths.toList())).map { it.path }

    @Test
    fun `the roots are the folders with music, not the corridors above them`() {
        // /storage and /storage/emulated hold exactly one child each and no
        // music of their own — three taps that ask no question.
        assertEquals(
            listOf("$sd/Download", "$sd/Monochrome+", "$sd/Music"),
            rootPaths("$sd/Music/a.mp3", "$sd/Monochrome+/b.mp3", "$sd/Download/Rock/c.mp3"),
        )
    }

    @Test
    fun `a folder holding its own music stops the descent`() {
        // The reported case: hundreds of songs sitting directly in one folder.
        // It is a destination, not a corridor, so the walk stops and lists it.
        assertEquals(listOf("$sd/Monochrome+"), rootPaths("$sd/Monochrome+/a.mp3"))
    }

    @Test
    fun `descent continues while there is nothing to choose`() {
        // Every level down to x/y has one child and no music of its own.
        assertEquals(listOf("$sd/Monochrome+/x/y"), rootPaths("$sd/Monochrome+/x/y/b.mp3"))
    }

    @Test
    fun `a branch point that also holds music is itself a root`() {
        // A stray file loose in internal storage: the walk cannot go past it
        // without hiding it.
        assertEquals(listOf(sd), rootPaths("$sd/loose.mp3", "$sd/Music/a.mp3"))
    }

    @Test
    fun `roots come back sorted by name, case-insensitively`() {
        assertEquals(
            listOf("$sd/alpha", "$sd/Beta", "$sd/gamma"),
            rootPaths("$sd/gamma/c.mp3", "$sd/alpha/a.mp3", "$sd/Beta/b.mp3"),
        )
    }

    @Test
    fun `an empty library has no roots rather than throwing`() {
        assertEquals(emptyList<String>(), folderBrowseRoots(emptyList()).map { it.path })
    }
}
