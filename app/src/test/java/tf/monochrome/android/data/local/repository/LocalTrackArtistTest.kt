package tf.monochrome.android.data.local.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.repository.LocalMediaRepository.Companion.toUnifiedTrack

/**
 * The artist a local track goes by: what the rows show, and what Last.fm and
 * ListenBrainz are sent. It is the track's own, not its album's.
 */
class LocalTrackArtistTest {

    private fun track(artist: String?, albumArtist: String?) = LocalTrackEntity(
        filePath = "/music/a.flac",
        fileSizeBytes = 1,
        lastModified = 0,
        title = "Song",
        artist = artist,
        albumArtist = albumArtist,
        codec = "FLAC",
        sampleRate = 44_100,
        bitRate = 900,
        channels = 2,
        durationSeconds = 200,
        // A cached cover, so the mapping never builds a file Uri: on Android
        // that is a framework stub, which throws in a plain JVM test.
        artworkCacheKey = "cover",
    )

    @Test
    fun `a compilation track goes by its own artist`() {
        val t = track(artist = "Queen", albumArtist = "Various Artists").toUnifiedTrack()
        assertEquals("Queen", t.artistName)
        assertEquals("Queen", t.toLegacyTrack().artist?.name)
        assertEquals("Various Artists", t.albumArtistName)
    }

    @Test
    fun `a guest credit is not replaced by the album artist`() {
        val t = track(artist = "Daft Punk feat. Pharrell Williams", albumArtist = "Daft Punk").toUnifiedTrack()
        assertEquals("Daft Punk feat. Pharrell Williams", t.toLegacyTrack().artist?.name)
    }

    @Test
    fun `a track with no artist of its own takes the album artist, then Unknown Artist`() {
        assertEquals("Daft Punk", track(artist = null, albumArtist = "Daft Punk").toUnifiedTrack().artistName)
        assertEquals("Unknown Artist", track(artist = null, albumArtist = null).toUnifiedTrack().artistName)
    }
}
