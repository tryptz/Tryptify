package tf.monochrome.android.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.cache.QobuzStreamUri
import tf.monochrome.android.domain.model.AudioQuality

/**
 * Which URIs may sit in ExoPlayer's playlist ahead of time. Getting this wrong
 * in the permissive direction reintroduces exactly the staleness the
 * one-track-at-a-time design exists to prevent, so the http/data cases matter
 * as much as the file/qobuz ones.
 */
class GaplessEligibilityTest {

    @Test
    fun `on-device files may be pre-queued`() {
        assertTrue(GaplessEligibility.isStableUri("file:///storage/emulated/0/Music/a.flac"))
        assertTrue(GaplessEligibility.isStableUri("content://com.android.externalstorage/document/a.flac"))
    }

    @Test
    fun `a bare filesystem path counts as a file`() {
        // The download store records internal-storage paths without a scheme.
        assertTrue(GaplessEligibility.isStableUri("/data/user/0/tf.monochrome/files/x.flac"))
    }

    @Test
    fun `qobuz uris may be pre-queued because they resolve at open time`() {
        assertTrue(GaplessEligibility.isStableUri(QobuzStreamUri.build(11, AudioQuality.LOSSLESS)))
    }

    @Test
    fun `time-limited stream urls may not be pre-queued`() {
        assertFalse(GaplessEligibility.isStableUri("https://stream.example.test/t.flac?etsp=1699"))
        assertFalse(GaplessEligibility.isStableUri("http://stream.example.test/t.flac"))
    }

    @Test
    fun `inline dash manifests may not be pre-queued`() {
        assertFalse(GaplessEligibility.isStableUri("data:application/dash+xml;base64,PE1QRD4="))
    }

    @Test
    fun `nothing is not pre-queueable`() {
        assertFalse(GaplessEligibility.isStableUri(null))
        assertFalse(GaplessEligibility.isStableUri(""))
        assertFalse(GaplessEligibility.isStableUri("   "))
        assertFalse(GaplessEligibility.isStableUri("not-a-uri"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertTrue(GaplessEligibility.isStableUri("FILE:///music/a.flac"))
        assertFalse(GaplessEligibility.isStableUri("HTTPS://example.test/a.flac"))
    }
}
