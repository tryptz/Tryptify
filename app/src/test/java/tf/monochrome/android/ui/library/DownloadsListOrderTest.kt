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
