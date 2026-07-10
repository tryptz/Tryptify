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
}
