package tf.monochrome.android.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.subsonic.Subsonic
import java.io.IOException
import java.util.concurrent.ExecutionException

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubsonicCoverBitmapLoaderTest {

    /** Media3's loader as far as this one uses it: what it was asked, and a bitmap for whatever it is given. */
    private class Others : BitmapLoader {
        val opened = ArrayList<Uri>()
        val decoded = ArrayList<ByteArray>()
        val picture: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        override fun supportsMimeType(mimeType: String) = mimeType == "image/jpeg"
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            decoded += data
            return Futures.immediateFuture(picture)
        }
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            opened += uri
            return Futures.immediateFuture(picture)
        }
    }

    // Unconfined: the lookup below never suspends, so the answer is there when loadBitmap returns.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `a subsonic cover reference is answered with the server's picture, and Media3's loader never sees the address`() {
        val others = Others()
        val asked = ArrayList<String>()
        val bytes = byteArrayOf(1, 2, 3)
        val loader = SubsonicCoverBitmapLoader(others, scope) { ref -> asked += ref; bytes }

        val ref = Subsonic.coverArtRef("al-42 a/b")!!
        val picture = loader.loadBitmap(Uri.parse(ref))

        assertTrue(picture.isDone)
        assertSame(others.picture, picture.get())
        // The reference as the song carries it, so the repository finds the same cover id in it.
        assertEquals(listOf(ref), asked)
        assertEquals("al-42 a/b", Subsonic.coverArtId(asked.single()))
        assertSame(bytes, others.decoded.single())
        assertTrue("the reference went to the web loader", others.opened.isEmpty())
    }

    @Test
    fun `every other address goes to Media3's loader as before`() {
        val others = Others()
        var asked = 0
        val loader = SubsonicCoverBitmapLoader(others, scope) { asked++; null }

        for (address in listOf("https://example.com/cover.jpg", "content://media/external/audio/albumart/7", "file:///sdcard/cover.png")) {
            assertSame(others.picture, loader.loadBitmap(Uri.parse(address)).get())
        }
        assertEquals(3, others.opened.size)
        assertEquals(0, asked)
        assertTrue(loader.supportsMimeType("image/jpeg"))
        assertSame(others.picture, loader.decodeBitmap(byteArrayOf(9)).get())
    }

    @Test
    fun `no cover is a failure the session can report, never a wait without end`() {
        val missing = SubsonicCoverBitmapLoader(Others(), scope) { null }
            .loadBitmap(Uri.parse("${Subsonic.COVER_SCHEME}://x"))
        assertTrue(missing.isDone)
        assertTrue(runCatching { missing.get() }.exceptionOrNull() is ExecutionException)

        val failed = SubsonicCoverBitmapLoader(Others(), scope) { throw IOException("server away") }
            .loadBitmap(Uri.parse("${Subsonic.COVER_SCHEME}://x"))
        assertTrue(failed.isDone)
        assertEquals("server away", (runCatching { failed.get() }.exceptionOrNull() as ExecutionException).cause?.message)
    }
}
