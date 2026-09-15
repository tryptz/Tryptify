package tf.monochrome.android.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which artwork URIs are really audio files.
 *
 * Pinned because both ways of getting it wrong are silent. Too loose and every
 * cover disappears from the notification with no error anywhere; too strict and
 * Media3 goes back to reading whole audio files into the Java heap to look for
 * a picture, which is what froze the audio thread on a 32-bit WAV.
 */
class ArtworkUriPathTest {

    @Test
    fun `audio file paths are recognised`() {
        assertTrue(pathLooksLikeAudioFile("/storage/emulated/0/Music/take.wav"))
        assertTrue(pathLooksLikeAudioFile("/Music/track.flac"))
        assertTrue(pathLooksLikeAudioFile("/Music/track.mp3"))
        assertTrue(pathLooksLikeAudioFile("/Music/track.dsf"))
    }

    @Test
    fun `extension match is case insensitive`() {
        // Windows-authored libraries and BWF exports routinely ship .WAV.
        assertTrue(pathLooksLikeAudioFile("/Music/TAKE.WAV"))
        assertTrue(pathLooksLikeAudioFile("/Music/Track.FlAc"))
    }

    @Test
    fun `real cover art is left alone`() {
        assertFalse(pathLooksLikeAudioFile("/images/1234/640x640.jpg"))
        assertFalse(pathLooksLikeAudioFile("/covers/album.png"))
        assertFalse(pathLooksLikeAudioFile("/covers/album.webp"))
    }

    @Test
    fun `paths with no extension are left alone`() {
        // A cover endpoint that carries its id in the query, not the path.
        assertFalse(pathLooksLikeAudioFile("/cover"))
        assertFalse(pathLooksLikeAudioFile(""))
    }

    @Test
    fun `a dot in a directory name does not count as an extension`() {
        // substringAfterLast('.') yields "b/cover" here, which matches nothing.
        assertFalse(pathLooksLikeAudioFile("/a.b/cover"))
    }

    @Test
    fun `a null path is not an audio file`() {
        assertFalse(pathLooksLikeAudioFile(null))
    }
}
