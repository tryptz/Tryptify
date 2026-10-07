package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathsTest {

    @Test
    fun `a picked download folder maps to its path, trailing space and all`() {
        assertEquals(
            "/storage/emulated/0/Monochrome ",
            SafPaths.absolutePath("content://com.android.externalstorage.documents/tree/primary%3AMonochrome%20"),
        )
    }

    @Test
    fun `a downloaded file under the folder maps to the path the local library has`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMonochrome%20" +
            "/document/primary%3AMonochrome%20%2FThe%20Beatles%2F1%2F02.%20From%20Me%20To%20You.flac"
        assertEquals("/storage/emulated/0/Monochrome /The Beatles/1/02. From Me To You.flac", SafPaths.absolutePath(uri))
    }

    @Test
    fun `an SD card volume and a plus sign survive`() {
        val uri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic" +
            "/document/1A2B-3C4D%3AMusic%2FA%2BB.flac"
        assertEquals("/storage/1A2B-3C4D/Music/A+B.flac", SafPaths.absolutePath(uri))
    }

    @Test
    fun `other providers and app files have no external path`() {
        assertNull(SafPaths.absolutePath("content://com.android.providers.downloads.documents/tree/downloads"))
        assertNull(SafPaths.absolutePath("/data/user/0/tf.monotrypt.android/files/downloads/1.flac"))
    }

    @Test
    fun `a path maps back to its document id`() {
        assertEquals("primary:Monochrome /The Beatles/1/a.flac", SafPaths.documentIdOfPath("/storage/emulated/0/Monochrome /The Beatles/1/a.flac"))
        assertEquals("1A2B-3C4D:Music/a.flac", SafPaths.documentIdOfPath("/storage/1A2B-3C4D/Music/a.flac"))
        assertNull(SafPaths.documentIdOfPath("/data/user/0/tf.monotrypt.android/files/a.flac"))
    }
}
