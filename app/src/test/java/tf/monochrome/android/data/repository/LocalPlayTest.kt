package tf.monochrome.android.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource

/**
 * Whether a play is recorded as local. The track's id can't tell: a local
 * file's id is the hash of "local_<n>", positive about half the time, and the
 * sign was all that was asked, so over half of the local plays went into the
 * stats, and up to the cloud, as TIDAL streams.
 */
class LocalPlayTest {

    private val file = PlaybackSource.LocalFile("/music/waves.flac", AudioCodec.FLAC, 44_100, 16)
    private val positiveLocalId = "local_42".hashCode().toLong()

    @Test
    fun `a local file is local whatever the sign of its id`() {
        assertTrue(positiveLocalId > 0)
        assertTrue(isLocalPlay(positiveLocalId, file))
        assertTrue(isLocalPlay(-7L, file))
    }

    @Test
    fun `a stream is not local`() {
        assertFalse(isLocalPlay(58_990_486L, PlaybackSource.HiFiApi(58_990_486L)))
    }

    @Test
    fun `a track queued without its source keeps the old rule`() {
        // Local files are always queued with one, so this is a catalogue track.
        assertFalse(isLocalPlay(58_990_486L, null))
        assertTrue(isLocalPlay(-7L, null))
    }
}
