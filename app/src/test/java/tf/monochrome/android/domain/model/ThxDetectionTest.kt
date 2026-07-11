package tf.monochrome.android.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the THX Spatial Audio detection rule: the phrase must appear in
 * full (in title, version, or the album fallbacks), case-insensitively and
 * tolerant of internal whitespace — but a bare "THX" must not trip it.
 */
class ThxDetectionTest {

    @Test
    fun `version string with canonical phrase is detected`() {
        assertTrue(isThxSpatialAudio(version = "THX Spatial Audio version"))
        assertTrue(isThxSpatialAudio(version = "THX Spatial Audio"))
    }

    @Test
    fun `detection is case-insensitive and whitespace-tolerant`() {
        assertTrue(isThxSpatialAudio(version = "thx spatial audio"))
        assertTrue(isThxSpatialAudio(title = "Track (THX  Spatial   Audio)"))
    }

    @Test
    fun `album fallback marks the track`() {
        assertTrue(
            isThxSpatialAudio(
                title = "Some Song",
                version = null,
                albumVersion = "THX Spatial Audio version",
            )
        )
        assertTrue(isThxSpatialAudio(albumTitle = "Oddfellows (THX Spatial Audio)"))
    }

    @Test
    fun `bare THX does not match`() {
        assertFalse(isThxSpatialAudio(title = "THX"))
        assertFalse(isThxSpatialAudio(title = "THX 1138"))
        assertFalse(isThxSpatialAudio(version = "Spatial Audio")) // missing THX
    }

    @Test
    fun `nothing set is not THX`() {
        assertFalse(isThxSpatialAudio())
        assertFalse(isThxSpatialAudio(title = "Regular Album", version = "Remastered"))
    }
}
