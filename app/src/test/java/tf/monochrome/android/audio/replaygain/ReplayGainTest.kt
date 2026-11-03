// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack
import kotlin.math.log10

/** From a song's tags and the listener's choice to the gain applied. */
class ReplayGainTest {

    private fun db(linear: Float) = 20f * log10(linear)

    private val tagged = SongGain(trackDb = -8f, albumDb = -6f)

    // Aimed at the tags' own reference, so a gain applied is the tag's gain.
    private val track = ReplayGainSettings(ReplayGainMode.TRACK, targetLufs = ReplayGain.REFERENCE_LUFS)
    private val album = ReplayGainSettings(ReplayGainMode.ALBUM, targetLufs = ReplayGain.REFERENCE_LUFS)

    @Test
    fun `off changes nothing`() {
        assertEquals(1f, ReplayGain.linear(tagged, ReplayGainSettings(ReplayGainMode.OFF, targetLufs = -8f), inAlbum = true))
    }

    @Test
    fun `an untagged song is left as it is, whatever the target`() {
        ReplayGain.TARGET_STEPS.forEach { target ->
            assertEquals(1f, ReplayGain.linear(SongGain(), track.copy(targetLufs = target), inAlbum = false))
        }
    }

    @Test
    fun `track and album modes pick their gain, falling back to the other`() {
        assertEquals(-8f, db(ReplayGain.linear(tagged, track, inAlbum = false)), 0.01f)
        assertEquals(-6f, db(ReplayGain.linear(tagged, album, inAlbum = false)), 0.01f)
        assertEquals(-6f, db(ReplayGain.linear(SongGain(albumDb = -6f), track, inAlbum = false)), 0.01f)
        assertEquals(-8f, db(ReplayGain.linear(SongGain(trackDb = -8f), album, inAlbum = false)), 0.01f)
    }

    @Test
    fun `smart takes the album's gain only inside an album`() {
        val smart = ReplayGainSettings(ReplayGainMode.SMART, targetLufs = ReplayGain.REFERENCE_LUFS)
        assertEquals(-6f, db(ReplayGain.linear(tagged, smart, inAlbum = true)), 0.01f)
        assertEquals(-8f, db(ReplayGain.linear(tagged, smart, inAlbum = false)), 0.01f)
    }

    @Test
    fun `the target moves every gain by its distance from the reference`() {
        // A song tagged -8 dB is 8 dB above -18 LUFS, so -10 LUFS: -4 dB brings it to -14.
        assertEquals(-4f, db(ReplayGain.linear(tagged, track.copy(targetLufs = -14f), inAlbum = false)), 0.01f)
        assertEquals(-13f, db(ReplayGain.linear(tagged, track.copy(targetLufs = -23f), inAlbum = false)), 0.01f)
        assertEquals(-6f, db(ReplayGain.linear(tagged, track.copy(targetLufs = -16f), inAlbum = false)), 0.01f)
    }

    @Test
    fun `songs land on -14 LUFS unless the listener picks otherwise`() {
        assertEquals(-14f, ReplayGainSettings().targetLufs)
        assertEquals(-4f, db(ReplayGain.linear(tagged, ReplayGainSettings(ReplayGainMode.TRACK), inAlbum = false)), 0.01f)
    }

    @Test
    fun `at -8 LUFS a song quieter than that is not raised without a peak`() {
        // -10 LUFS would need +2 dB, and nothing says there is room for it.
        assertEquals(1f, ReplayGain.linear(tagged, track.copy(targetLufs = -8f), inAlbum = false))
        // A master louder than -8 still comes down.
        assertEquals(-2f, db(ReplayGain.linear(SongGain(trackDb = -12f), track.copy(targetLufs = -8f), inAlbum = false)), 0.01f)
    }

    @Test
    fun `a pre-amp chosen before the target becomes the target nearest it`() {
        assertEquals(-18f, ReplayGain.targetFromPreamp(0f))
        assertEquals(-23f, ReplayGain.targetFromPreamp(-3f))
        assertEquals(-23f, ReplayGain.targetFromPreamp(-6f))
        // -15 sits between -16 and -14: the quieter.
        assertEquals(-16f, ReplayGain.targetFromPreamp(3f))
        assertEquals(-14f, ReplayGain.targetFromPreamp(6f))
    }

    @Test
    fun `a boost with no peak known is not made`() {
        assertEquals(1f, ReplayGain.linear(SongGain(trackDb = 4f), track, inAlbum = false))
    }

    @Test
    fun `a boost goes only as far as the peak allows`() {
        // Peak at -6 dBFS (0.5): room for 6 dB, of the 10 asked for.
        val g = ReplayGain.linear(SongGain(trackDb = 10f, trackPeak = 0.5f), track, inAlbum = false)
        assertEquals(2f, g, 0.0001f)
        // Room for more than is asked: the full boost.
        assertEquals(3f, db(ReplayGain.linear(SongGain(trackDb = 3f, trackPeak = 0.5f), track, inAlbum = false)), 0.01f)
    }

    @Test
    fun `a broken tag is held to a sane range`() {
        assertEquals(-24f, db(ReplayGain.linear(SongGain(trackDb = -300f), track, inAlbum = false)), 0.01f)
    }

    @Test
    fun `a file's peak tags are read, and let a quiet song be raised`() {
        val file = libraryFile(rg = 3f).copy(replayGainTrackPeak = 0.5f, replayGainAlbumPeak = 0.6f)
        val gain = ReplayGain.of(file)
        assertEquals(0.5f, gain.trackPeak)
        assertEquals(0.6f, gain.albumPeak)
        // +3 dB at -18 LUFS, +7 at -14: the peak at -6 dBFS has room for 6 of them.
        assertEquals(3f, db(ReplayGain.linear(gain, track, inAlbum = false)), 0.01f)
        assertEquals(6.02f, db(ReplayGain.linear(gain, ReplayGainSettings(ReplayGainMode.TRACK), inAlbum = false)), 0.01f)
        // Without the peak it stays where it is.
        assertEquals(1f, ReplayGain.linear(ReplayGain.of(libraryFile(rg = 3f)), track, inAlbum = false))
    }

    @Test
    fun `r128 gains are read against their own reference`() {
        // -2304 / 256 = -9 dB against -23 LUFS: -4 dB against ReplayGain's -18.
        val gain = ReplayGain.of(libraryFile(r128 = -2304))
        assertEquals(-4f, gain.trackDb!!, 0.001f)
        val rg = ReplayGain.of(libraryFile(rg = -7.5f, r128 = -2304))
        assertEquals(-7.5f, rg.trackDb!!, 0.001f)
    }

    private fun libraryFile(rg: Float? = null, r128: Int? = null) = UnifiedTrack(
        id = "local_1",
        title = "t",
        durationSeconds = 0,
        artistName = "a",
        replayGainTrack = rg,
        r128TrackGain = r128,
        source = PlaybackSource.LocalFile(filePath = "/music/t.flac", codec = AudioCodec.FLAC, sampleRate = 44_100),
        sourceType = SourceType.LOCAL,
    )

    @Test
    fun `an album is a song between songs of its own album`() {
        assertTrue(ReplayGain.playingAnAlbum(previousAlbumId = 7, albumId = 7, nextAlbumId = 9))
        assertTrue(ReplayGain.playingAnAlbum(previousAlbumId = null, albumId = 7, nextAlbumId = 7))
        assertFalse(ReplayGain.playingAnAlbum(previousAlbumId = 3, albumId = 7, nextAlbumId = 9))
        assertFalse(ReplayGain.playingAnAlbum(previousAlbumId = null, albumId = null, nextAlbumId = null))
    }
}
