package tf.monochrome.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering of release tags. Both failure directions are bad and neither is
 * loud: too eager and every user is told about an update that doesn't exist,
 * too shy and a real release is never mentioned.
 */
class AppVersionTest {

    @Test
    fun `a higher version is newer`() {
        assertTrue(AppVersion.isNewer("1.8.5", "1.8.4"))
        assertTrue(AppVersion.isNewer("1.9.0", "1.8.9"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppVersion.isNewer("1.8.4", "1.8.4"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(AppVersion.isNewer("1.8.3", "1.8.4"))
        assertFalse(AppVersion.isNewer("1.7.9", "1.8.0"))
    }

    @Test
    fun `a v prefix on the tag is ignored`() {
        assertTrue(AppVersion.isNewer("v1.8.5", "1.8.4"))
        assertFalse(AppVersion.isNewer("v1.8.4", "1.8.4"))
        assertTrue(AppVersion.isNewer("V1.9.0", "v1.8.4"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertEquals(0, AppVersion.compare("1.9", "1.9.0"))
        assertEquals(0, AppVersion.compare("2", "2.0.0"))
        assertTrue(AppVersion.isNewer("1.9", "1.8.9"))
    }

    @Test
    fun `numbers compare numerically, not as text`() {
        // The classic bug: "1.8.10" sorts before "1.8.9" as a string.
        assertTrue(AppVersion.isNewer("1.8.10", "1.8.9"))
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `a prerelease is older than the release it precedes`() {
        assertTrue(AppVersion.isNewer("1.9.0", "1.9.0-beta1"))
        assertFalse(AppVersion.isNewer("1.9.0-beta1", "1.9.0"))
    }

    @Test
    fun `a prerelease of a higher version still beats the current release`() {
        assertTrue(AppVersion.isNewer("1.9.0-rc1", "1.8.4"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, AppVersion.compare("1.8.4+build7", "1.8.4"))
    }

    @Test
    fun `a tag with no digits is never treated as a version`() {
        // Otherwise "latest" and "nightly" both parse as 0.0.0 and compare
        // equal, and any junk tag reads as a release.
        assertFalse(AppVersion.isNewer("latest", "1.8.4"))
        assertFalse(AppVersion.isNewer("nightly", "0.0.0"))
    }

    @Test
    fun `blank input is never newer`() {
        assertFalse(AppVersion.isNewer("", "1.8.4"))
        assertFalse(AppVersion.isNewer("1.8.5", ""))
    }

    @Test
    fun `tags with trailing text on a part still parse`() {
        assertTrue(AppVersion.isNewer("1.8.5a", "1.8.4"))
    }
}
