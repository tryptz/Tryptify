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
    private val track = ReplayGainSettings(ReplayGainMode.TRACK)
    private val album = ReplayGainSettings(ReplayGainMode.ALBUM)

    @Test
    fun `off changes nothing`() {
        assertEquals(1f, ReplayGain.linear(tagged, ReplayGainSettings(ReplayGainMode.OFF, preampDb = 6f), inAlbum = true))
    }

    @Test
    fun `an untagged song is left as it is, pre-amp and all`() {
        assertEquals(1f, ReplayGain.linear(SongGain(), track.copy(preampDb = 6f), inAlbum = false))
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
        val smart = ReplayGainSettings(ReplayGainMode.SMART)
        assertEquals(-6f, db(ReplayGain.linear(tagged, smart, inAlbum = true)), 0.01f)
        assertEquals(-8f, db(ReplayGain.linear(tagged, smart, inAlbum = false)), 0.01f)
    }

    @Test
    fun `the pre-amp adds to the gain`() {
        assertEquals(-5f, db(ReplayGain.linear(tagged, track.copy(preampDb = 3f), inAlbum = false)), 0.01f)
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
