package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a FLAC stream is saved as flac`() {
        val header = "fLaC".toByteArray() + bytes(0x00, 0x00, 0x00, 0x22)
        assertEquals(DownloadFormat.FLAC, DownloadFormat.sniff(header))
        assertEquals("flac", DownloadFormat.FLAC.extension)
    }

    @Test
    fun `TIDAL's AAC in MP4 is saved as m4a, not mp3`() {
        // A box size, then "ftyp" and a brand: how every MP4 (and a DASH init segment) starts.
        val header = bytes(0x00, 0x00, 0x00, 0x18) + "ftypdash".toByteArray()
        assertEquals(DownloadFormat.M4A, DownloadFormat.sniff(header))
        assertEquals("m4a", DownloadFormat.M4A.extension)
        assertEquals("audio/mp4", DownloadFormat.M4A.mimeType)
    }

    @Test
    fun `MP3 and anything unrecognised is saved as mp3`() {
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff("ID3".toByteArray() + bytes(0x04, 0x00, 0x00)))
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff(ByteArray(0)))
        assertEquals(DownloadFormat.MP3, DownloadFormat.sniff(bytes(0x00, 0x00, 0x00, 0x18, 0x66)))
    }

    @Test
    fun `YouTube's Opus in WebM is saved as webm, untagged`() {
        // The EBML header's ID, then its size and the "webm" doc type further on.
        val header = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81, 0x01)
        assertEquals(DownloadFormat.WEBM, DownloadFormat.sniff(header))
        assertEquals("webm", DownloadFormat.WEBM.extension)
        assertEquals("audio/webm", DownloadFormat.WEBM.mimeType)
        assertEquals(false, DownloadFormat.WEBM.taggable)
        assertEquals(true, DownloadFormat.M4A.taggable)
        assertEquals(true, DownloadFormat.FLAC.taggable)
        assertEquals(false, DownloadFormat.MP3.taggable)
    }
}
