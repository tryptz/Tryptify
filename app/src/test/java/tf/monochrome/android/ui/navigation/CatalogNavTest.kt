package tf.monochrome.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * Where "Go to album" leads. A song's number says nothing about which
 * service it belongs to, and TIDAL is what is left when nobody claims one:
 * every case here is a number that was once handed to TIDAL and was not
 * TIDAL's (the album page then said "HTTP 404").
 */
class CatalogNavTest {

    private fun unified(id: String, albumId: String?, type: SourceType, source: PlaybackSource) = UnifiedTrack(
        id = id, title = "Song", durationSeconds = 200, artistName = "Artist",
        albumId = albumId, albumTitle = "Album", source = source, sourceType = type,
    )

    private val localFile = PlaybackSource.LocalFile("/music/a.flac", AudioCodec.FLAC, 44_100)

    @Test
    fun `a library song opens its library album`() {
        val song = unified("local_17", "local_album_2077", SourceType.LOCAL, localFile)
        assertEquals(
            AlbumDestination.Known("local_album_2077"),
            albumDestinationOf(song.toLegacyTrack(), song, savedLocal = false),
        )
    }

    @Test
    fun `a catalogue song opens the album its record names, not a hash of it`() {
        val song = unified("123", "77646169", SourceType.API, PlaybackSource.HiFiApi(123))
        val legacy = song.toLegacyTrack()
        // The song model keeps the number now…
        assertEquals(77646169L, legacy.album?.id)
        assertEquals(AlbumDestination.Catalogue(77646169), albumDestinationOf(legacy, song, savedLocal = false))
        // …and where a legacy track still carries the old hash, the record wins.
        val hashed = legacy.copy(album = Album(id = "77646169".hashCode().toLong(), title = "Album"))
        assertEquals(AlbumDestination.Catalogue(77646169), albumDestinationOf(hashed, song, savedLocal = false))
    }

    @Test
    fun `a saved file of the listener's own has no catalogue album`() {
        // Liked Songs after a restart: a bare track, its ids made up from "local_17" and "local_album_2077".
        val saved = Track(
            id = "local_17".hashCode().toLong(), title = "Song",
            album = Album(id = "local_album_2077".hashCode().toLong(), title = "Album"),
        )
        // The made-up album number is positive: it was sent to TIDAL as an album's.
        assertEquals(true, saved.album!!.id > 0)
        assertNull(albumDestinationOf(saved, unified = null, savedLocal = true))
    }

    @Test
    fun `a bare catalogue song opens its album`() {
        val song = Track(id = 123, title = "Song", album = Album(id = 456, title = "Album"))
        assertEquals(AlbumDestination.Catalogue(456), albumDestinationOf(song, unified = null, savedLocal = false))
        assertNull(albumDestinationOf(song.copy(album = Album(id = 0, title = "")), unified = null, savedLocal = false))
        assertNull(albumDestinationOf(song.copy(album = null), unified = null, savedLocal = false))
    }

    @Test
    fun `a song with no album page has none`() {
        val radio = unified("radio_1", null, SourceType.LIVE_RADIO, PlaybackSource.HiFiApi(1))
        assertNull(albumDestinationOf(Track(id = 1, title = "Song", album = Album(id = 9, title = "A")), radio, savedLocal = false))
    }
}
