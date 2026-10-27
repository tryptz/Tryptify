package tf.monochrome.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathsTest {
    @Test
    fun `a document on the phone's own storage is under emulated 0`() {
        assertEquals(
            "/storage/emulated/0/StepMania/Songs/Pack/Song/song.ogg",
            documentIdToPath("primary:StepMania/Songs/Pack/Song/song.ogg"),
        )
        assertEquals("/storage/emulated/0", documentIdToPath("primary:"))
    }

    @Test
    fun `a document on an SD card is under its volume`() {
        assertEquals("/storage/1A2B-3C4D/Songs/a.mp3", documentIdToPath("1A2B-3C4D:Songs/a.mp3"))
    }

    @Test
    fun `a raw Downloads id carries its own path`() {
        assertEquals("/storage/emulated/0/Download/Pack", documentIdToPath("raw:/storage/emulated/0/Download/Pack"))
    }

    @Test
    fun `an id of any other shape has no path`() {
        // Numbered Downloads and media ids name no folder.
        assertNull(documentIdToPath("msf:1234"))
        assertNull(documentIdToPath("audio:56"))
        assertNull(documentIdToPath("no-colon"))
        assertNull(documentIdToPath(":Songs"))
    }
}
