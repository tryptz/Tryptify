package tf.monochrome.android.data.local.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.data.local.db.LocalTrackEntity

/** An album's tempo, key, loudness and range, from its tracks' tags and measurements. */
class AlbumAggregatesTest {

    private var n = 0

    private fun track(
        seconds: Int = 200,
        bpm: Float? = null,
        key: Int? = null,
        rgTrack: Float? = null,
        rgAlbum: Float? = null,
        r128Track: Int? = null,
        r128Album: Int? = null,
        dr: Float? = null,
    ) = LocalTrackEntity(
        filePath = "/music/${n++}.flac",
        fileSizeBytes = 1,
        lastModified = 0,
        codec = "FLAC",
        sampleRate = 44100,
        bitRate = 900,
        channels = 2,
        durationSeconds = seconds,
        rgTrackGain = rgTrack,
        rgAlbumGain = rgAlbum,
        r128TrackGain = r128Track,
        r128AlbumGain = r128Album,
        bpm = bpm,
        camelotKey = key,
        dynamicRange = dr,
    )

    @Test
    fun `the median of what there is, null for nothing`() {
        assertEquals(124f, AlbumAggregates.median(listOf(130f, 120f, 124f))!!, 0f)
        assertEquals(125f, AlbumAggregates.median(listOf(120f, 130f))!!, 0f)
        assertNull(AlbumAggregates.median(emptyList()))
        assertEquals(5f, AlbumAggregates.median(listOf(5f, Float.NaN))!!, 0f)
    }

    @Test
    fun `tempo is the median of the tagged tracks`() {
        val sums = AlbumAggregates.of(listOf(track(bpm = 170f), track(bpm = 174f), track(bpm = 87f), track()))
        assertEquals(170f, sums.bpm!!, 0f)
    }

    @Test
    fun `the key is the commonest, a tie going to the longer, then the lower code`() {
        assertEquals(8, AlbumAggregates.of(listOf(track(key = 8), track(key = 8), track(key = 20))).camelotKey)
        assertEquals(20, AlbumAggregates.of(listOf(track(seconds = 100, key = 8), track(seconds = 300, key = 20))).camelotKey)
        assertEquals(3, AlbumAggregates.of(listOf(track(key = 9), track(key = 3))).camelotKey)
        assertNull(AlbumAggregates.of(listOf(track(), track())).camelotKey)
    }

    @Test
    fun `loudness comes from the album gain first, ReplayGain against -18, R128 against -23`() {
        val rg = AlbumAggregates.of(listOf(track(rgAlbum = -8f, rgTrack = -3f, r128Album = 0), track(rgAlbum = -8f)))
        assertEquals(-10f, rg.lufs!!, 1e-4f)
        val r128 = AlbumAggregates.of(listOf(track(r128Album = -2048), track(r128Album = -2048)))
        assertEquals(-15f, r128.lufs!!, 1e-4f)
    }

    @Test
    fun `without an album gain, the tracks' loudness is averaged as energy, by length`() {
        // Two equal-length tracks at -8 and -14 LUFS: the energy mean, -10.04, not -11.
        val two = AlbumAggregates.of(listOf(track(rgTrack = -10f), track(rgTrack = -4f)))
        assertEquals(-10.04f, two.lufs!!, 0.01f)
        // One track's loudness is itself.
        assertEquals(-12f, AlbumAggregates.of(listOf(track(r128Track = -11 * 256))).lufs!!, 1e-4f)
        // A track three times as long weighs three times as much.
        val long = AlbumAggregates.of(listOf(track(seconds = 300, rgTrack = -10f), track(seconds = 100, rgTrack = -4f)))
        assertEquals(-8.90f, long.lufs!!, 0.01f)
        assertNull(AlbumAggregates.of(listOf(track(), track())).lufs)
    }

    @Test
    fun `dynamic range is the median of the tracks measured`() {
        assertEquals(9f, AlbumAggregates.of(listOf(track(dr = 8f), track(dr = 10f), track())).dynamicRange!!, 0f)
        assertNull(AlbumAggregates.of(listOf(track())).dynamicRange)
    }
}
