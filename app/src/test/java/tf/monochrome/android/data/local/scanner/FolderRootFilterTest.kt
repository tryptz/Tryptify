package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRootFilterTest {

    @Test
    fun `empty roots means unrestricted`() {
        assertTrue(MediaStoreSource.isUnderRoots("/storage/emulated/0/Podcasts/ep.mp3", emptySet()))
    }

    @Test
    fun `path under root matches`() {
        assertTrue(
            MediaStoreSource.isUnderRoots(
                "/storage/emulated/0/Music/album/track.flac",
                setOf("/storage/emulated/0/Music")
            )
        )
    }

    @Test
    fun `path equal to root matches`() {
        assertTrue(
            MediaStoreSource.isUnderRoots(
                "/storage/emulated/0/Music",
                setOf("/storage/emulated/0/Music")
            )
        )
    }

    @Test
    fun `sibling folder sharing prefix does not match`() {
        assertFalse(
            MediaStoreSource.isUnderRoots(
                "/storage/emulated/0/MusicVideos/clip.mp3",
                setOf("/storage/emulated/0/Music")
            )
        )
    }

    @Test
    fun `any of several roots matches`() {
        val roots = setOf("/storage/emulated/0/Music", "/storage/ABCD-1234/FLAC")
        assertTrue(MediaStoreSource.isUnderRoots("/storage/ABCD-1234/FLAC/x.flac", roots))
        assertFalse(MediaStoreSource.isUnderRoots("/storage/emulated/0/Download/x.flac", roots))
    }

    @Test
    fun `trailing slash on root is tolerated`() {
        assertTrue(
            MediaStoreSource.isUnderRoots(
                "/storage/emulated/0/Music/track.flac",
                setOf("/storage/emulated/0/Music/")
            )
        )
    }

    @Test
    fun `like pattern escapes underscore`() {
        assertEquals("My\\_Music", MediaStoreSource.escapeLikePattern("My_Music"))
    }

    @Test
    fun `like pattern escapes percent`() {
        assertEquals("100\\% mix", MediaStoreSource.escapeLikePattern("100% mix"))
    }

    @Test
    fun `like pattern escapes backslash first`() {
        // A literal backslash in the path must not merge with a following
        // wildcard escape: "a\_b" → "a\\\_b".
        assertEquals("a\\\\\\_b", MediaStoreSource.escapeLikePattern("a\\_b"))
    }

    @Test
    fun `plain path is unchanged`() {
        val path = "/storage/emulated/0/Music"
        assertEquals(path, MediaStoreSource.escapeLikePattern(path))
    }

    // ── Exclusions ──────────────────────────────────────────────────────
    //
    // This was a bare startsWith, which meant excluding /Music also excluded
    // /Music2 and took every track in it out of the library on the next scan.
    // The delete that runs at exclusion time always had the boundary; the scan
    // filter did not, so the two disagreed about what "this folder" meant.

    @Test
    fun `nothing is excluded when the set is empty`() {
        assertFalse(MediaStoreSource.isExcluded("/storage/emulated/0/Music/a.mp3", emptySet()))
    }

    @Test
    fun `a file under an excluded folder is excluded`() {
        assertTrue(
            MediaStoreSource.isExcluded(
                "/storage/emulated/0/Music/a.mp3",
                setOf("/storage/emulated/0/Music"),
            )
        )
    }

    @Test
    fun `a sibling sharing the prefix is NOT excluded`() {
        // The P1. /Music2 is a different folder and keeps its music.
        assertFalse(
            MediaStoreSource.isExcluded(
                "/storage/emulated/0/Music2/a.mp3",
                setOf("/storage/emulated/0/Music"),
            )
        )
        assertFalse(
            MediaStoreSource.isExcluded(
                "/storage/emulated/0/MusicVideos/clip.mp3",
                setOf("/storage/emulated/0/Music"),
            )
        )
    }

    @Test
    fun `the excluded folder itself matches`() {
        val p = "/storage/emulated/0/Music"
        assertTrue(MediaStoreSource.isExcluded(p, setOf(p)))
    }

    @Test
    fun `a trailing slash on the exclusion is tolerated`() {
        assertTrue(
            MediaStoreSource.isExcluded(
                "/storage/emulated/0/Music/a.mp3",
                setOf("/storage/emulated/0/Music/"),
            )
        )
    }

    @Test
    fun `a blank exclusion does not swallow the whole device`() {
        // "" trimmed is "", and "anything".startsWith("/") style checks would
        // make every path match. A junk entry must exclude nothing.
        assertFalse(MediaStoreSource.isExcluded("/storage/emulated/0/a.mp3", setOf("", "/")))
    }

    @Test
    fun `deeper nesting under an excluded folder is excluded`() {
        assertTrue(
            MediaStoreSource.isExcluded(
                "/storage/emulated/0/Music/Rock/1990/a.mp3",
                setOf("/storage/emulated/0/Music"),
            )
        )
    }
}
