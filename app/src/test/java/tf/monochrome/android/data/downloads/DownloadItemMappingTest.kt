package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * What reaches the file on disk is decided here, one hop before the tag writer.
 *
 * A downloaded FLAC used to land anonymous: no track number, so a player sorted
 * the album alphabetically and put track 10 before track 2; no album artist, so
 * a record with a featured credit on half its tracks fragmented into an album
 * per artist. The tag write itself is device code — JAudioTagger against a real
 * file — but whether there is anything to write is pure mapping, and that is
 * what this pins.
 */
class DownloadItemMappingTest {

    private fun track(album: Album?) = Track(
        id = 7L,
        title = "Sixteen Going On Seventeen",
        duration = 210,
        artist = Artist(id = 1L, name = "Bill Evans"),
        album = album,
        trackNumber = 4,
        volumeNumber = 2,
    )

    @Test
    fun `track and disc number reach the download item`() {
        val item = DownloadItem.from(track(album = null))

        assertEquals(4, item.trackNumber)
        // `volumeNumber` is TIDAL's name for the disc, and the Qobuz mapper puts
        // `media_number` in it — either way it is the disc the tag wants.
        assertEquals(2, item.discNumber)
    }

    @Test
    fun `album artist and release date come off the album`() {
        val item = DownloadItem.from(
            track(
                Album(
                    id = 2L,
                    title = "Portrait in Jazz",
                    artist = Artist(id = 9L, name = "Bill Evans Trio"),
                    releaseDate = "1960-01-01",
                )
            )
        )

        // The album's artist, not the track's — that distinction is the whole
        // reason ALBUMARTIST exists.
        assertEquals("Bill Evans Trio", item.albumArtist)
        assertEquals("1960-01-01", item.releaseDate)
    }

    @Test
    fun `a multi-artist album falls back to the joined credits`() {
        val item = DownloadItem.from(
            track(
                Album(
                    id = 3L,
                    title = "Split",
                    artists = listOf(
                        Artist(id = 4L, name = "Ella Fitzgerald"),
                        Artist(id = 5L, name = "Louis Armstrong"),
                    ),
                )
            )
        )

        assertEquals("Ella Fitzgerald, Louis Armstrong", item.albumArtist)
    }

    @Test
    fun `an album with no artist at all leaves the tag unset rather than blank`() {
        val item = DownloadItem.from(Album(id = 6L, title = "Untitled").let { track(it) })

        // A blank ALBUMARTIST is worse than none: a player shows an album filed
        // under the empty artist instead of falling back to the track artist.
        assertNull(item.albumArtist)
        assertNull(item.releaseDate)
    }

    @Test
    fun `missing numbers are absent, not invented`() {
        val bare = Track(id = 8L, title = "Untitled", artist = Artist(id = 1L, name = "Someone"))
        val item = DownloadItem.from(bare)

        // 0 is the tag writer's "don't write this field". Track and disc numbers
        // are 1-based, so there is no real zeroth track to confuse it with.
        assertEquals(0, item.trackNumber)
        assertEquals(0, item.discNumber)
    }

    @Test
    fun `the unified context menu download keeps the album artist and year`() {
        // UnifiedTrack.toLegacyTrack is the route the unified context menu takes
        // to the download queue, and it used to build an Album out of the title
        // and cover alone — so a download started from that menu arrived with
        // both of these already thrown away.
        val unified = UnifiedTrack(
            id = "q:1234",
            title = "So What",
            durationSeconds = 545,
            trackNumber = 1,
            discNumber = 1,
            artistName = "Miles Davis",
            albumArtistName = "Miles Davis Sextet",
            albumTitle = "Kind of Blue",
            albumId = "a:99",
            releaseYear = 1959,
            source = PlaybackSource.HiFiApi(tidalId = 1234L),
            sourceType = SourceType.QOBUZ,
        )

        val item = DownloadItem.from(unified.toLegacyTrack())

        assertEquals("Miles Davis Sextet", item.albumArtist)
        assertEquals("1959", item.releaseDate)
        assertEquals(1, item.trackNumber)
        assertEquals(1, item.discNumber)
        assertEquals("Kind of Blue", item.albumTitle)
    }
}
