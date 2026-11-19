package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.ui.components.DEFAULT_TRACK_ORDERS
import tf.monochrome.android.ui.components.TrackOrder
import tf.monochrome.android.ui.components.TrackSort

class DownloadsListOrderTest {

    private fun download(id: Long, title: String, artist: String, album: String?, seconds: Int, size: Long) =
        DownloadedTrackEntity(
            id = id,
            title = title,
            duration = seconds,
            artistName = artist,
            albumTitle = album,
            filePath = "/music/$id.flac",
            quality = "LOSSLESS",
            sizeBytes = size,
        )

    // Newest download first, the order DownloadsViewModel hands over.
    private val list = listOf(
        download(1, "Waves", "DJ Satomi", "Greatest Hits", 300, 27_000_000),
        download(2, "jolene", "Dolly Parton", null, 160, 56_000_000),
        download(3, "Nuclear Sun", "DJ Satomi", "Hardstyle", 240, 53_000_000),
    )

    @Test
    fun `a TIDAL download's cover id becomes an address the image can open`() {
        // The record keeps TIDAL's cover id; as it was, nothing loaded.
        assertEquals(
            "https://resources.tidal.com/images/0a1b2c3d/4e5f/6071/8293/a4b5c6d7e8f9/640x640.jpg",
            downloadCover("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", libraryCover = null),
        )
        // An address or a picture on the phone is left as it is.
        assertEquals("https://static.qobuz.com/c.jpg", downloadCover("https://static.qobuz.com/c.jpg", null))
        assertEquals("/data/artwork/ab.jpg", downloadCover("/data/artwork/ab.jpg", null))
        assertEquals(null, downloadCover(null, null))
        assertEquals(null, downloadCover(" ", ""))
    }

    @Test
    fun `the cover the library read from the file comes first`() {
        assertEquals("/data/artwork/ab.jpg", downloadCover("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", "/data/artwork/ab.jpg"))
        assertEquals("/data/artwork/ab.jpg", downloadCover(null, "/data/artwork/ab.jpg"))
    }

    @Test
    fun `a downloaded file is listed once, with a cover that opens`() {
        val tidalId = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"
        val records = listOf(
            // In a picked folder the record holds the file's content address.
            download(1, "Waves", "DJ Satomi", "Greatest Hits", 300, 1).copy(
                filePath = "content://tree/Download/Waves.flac", albumCover = tidalId, downloadedAt = 30,
            ),
            // In app storage, which the library does not scan.
            download(2, "With You", "DJ Satomi", "Greatest Hits", 200, 1).copy(albumCover = tidalId, downloadedAt = 20),
        )
        val scanned = listOf(
            download(-7, "Waves", "DJ Satomi", "Greatest Hits", 300, 1).copy(
                filePath = "/sd/Download/Waves.flac", albumCover = "/data/artwork/waves.jpg", downloadedAt = 31,
            ),
            download(-8, "Sideloaded", "Someone", null, 100, 1).copy(
                filePath = "/sd/Download/Sideloaded.mp3", albumCover = "/data/artwork/side.jpg", downloadedAt = 10,
            ),
        )
        val merged = mergeDownloads(records, scanned) { it.replace("content://tree", "/sd") }
        // The record's row for the file in both, never the library's as well.
        assertEquals(listOf(1L, 2L, -8L), merged.map { it.id })
        assertEquals("/data/artwork/waves.jpg", merged[0].albumCover)
        assertTrue(merged[1].albumCover!!.startsWith("https://resources.tidal.com/images/"))
        assertEquals("/data/artwork/side.jpg", merged[2].albumCover)
        // Everything else about a record is as it was saved.
        assertEquals(records[0].copy(albumCover = merged[0].albumCover), merged[0])
    }

    @Test
    fun `original order is newest first, reversed is oldest first`() {
        assertSame(list, list.applyDownloadSort(TrackSort(TrackOrder.ORIGINAL)))
        assertEquals(listOf(3L, 2L, 1L), list.applyDownloadSort(TrackSort(TrackOrder.ORIGINAL, ascending = false)).map { it.id })
    }

    @Test
    fun `title sorts case-insensitively`() {
        assertEquals(listOf(2L, 3L, 1L), list.applyDownloadSort(TrackSort(TrackOrder.TITLE)).map { it.id })
    }

    @Test
    fun `file size sorts smallest first and flips`() {
        assertEquals(listOf(1L, 3L, 2L), list.applyDownloadSort(TrackSort(TrackOrder.FILE_SIZE)).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), list.applyDownloadSort(TrackSort(TrackOrder.FILE_SIZE, ascending = false)).map { it.id })
    }

    @Test
    fun `artist ties are broken by title`() {
        // DJ Satomi's two (Nuclear Sun, then Waves) before Dolly Parton.
        assertEquals(listOf(3L, 1L, 2L), list.applyDownloadSort(TrackSort(TrackOrder.ARTIST)).map { it.id })
    }

    @Test
    fun `query matches title, artist and album, ignoring case`() {
        assertEquals(listOf(1L, 3L), list.applyDownloadQuery("satomi").map { it.id })
        assertEquals(listOf(3L), list.applyDownloadQuery("HARDSTYLE").map { it.id })
        assertEquals(listOf(2L), list.applyDownloadQuery("  Jolene ").map { it.id })
        assertTrue(list.applyDownloadQuery("nothing like this").isEmpty())
    }

    @Test
    fun `blank query returns the list itself`() {
        assertSame(list, list.applyDownloadQuery("   "))
    }

    @Test
    fun `search then sort`() {
        assertEquals(listOf(3L, 1L), list.applyDownloadSearchAndSort("satomi", TrackSort(TrackOrder.TITLE)).map { it.id })
    }

    @Test
    fun `file size is offered on downloads and nowhere else`() {
        assertTrue(TrackOrder.FILE_SIZE in DOWNLOAD_TRACK_ORDERS)
        // A streamed track has no file; the key would reorder nothing there.
        assertFalse(TrackOrder.FILE_SIZE in DEFAULT_TRACK_ORDERS)
    }
}
