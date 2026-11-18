package tf.monochrome.android.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tf.monochrome.android.data.subsonic.Subsonic
import java.io.IOException

/**
 * The media session's picture loader, for the one address Media3's own cannot
 * open: a `subsonic-cover://<id>` reference ([Subsonic.coverArtRef]).
 *
 * A song from the listener's Subsonic server (Tryptify Cloud included)
 * carries that reference as its artwork, never the real address, which holds
 * the sign-in. The session hands a song's artwork address to this loader for
 * the notification, the lock screen and Bluetooth, and Media3's loader took
 * the reference for a web address: "unknown protocol: subsonic-cover", and no
 * cover. That was every song played from the copy on the phone
 * (`StreamResolver.resolveLocalFile`, which is how a song that is both on the
 * server and in the local library plays), and any whose picture did not
 * arrive in the moment `resolveSubsonic` waits for it.
 *
 * So the reference is answered here, with the picture itself from
 * [coverBytes] (the server, through this app's own sign-in). Only the bitmap
 * reaches the session; the address with the sign-in still never does. Every
 * other address goes to [others] untouched.
 */
@UnstableApi
class SubsonicCoverBitmapLoader(
    private val others: BitmapLoader,
    private val scope: CoroutineScope,
    private val coverBytes: suspend (ref: String) -> ByteArray?,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = others.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = others.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != Subsonic.COVER_SCHEME) return others.loadBitmap(uri)
        val picture = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val bytes = coverBytes(uri.toString())
                if (bytes == null) {
                    // No server connected now, or it has no picture for this song: said once, as any missing cover is.
                    picture.setException(IOException("no cover for this song on the music server"))
                } else {
                    picture.setFuture(others.decodeBitmap(bytes))
                }
            } catch (e: CancellationException) {
                picture.cancel(false)
                throw e
            } catch (e: Exception) {
                picture.setException(e)
            }
        }
        return picture
    }
}
