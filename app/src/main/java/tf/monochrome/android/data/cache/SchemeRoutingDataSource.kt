package tf.monochrome.android.data.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Picks a [DataSource] by URI scheme at open time: the app's own `qobuz://`
 * URIs go to [qobuz], everything else (file, content, asset, http) to
 * [default].
 *
 * DefaultDataSource has a closed set of schemes and no extension point, so this
 * sits in front of it rather than trying to replace it — the standard schemes
 * keep their standard handling.
 */
@UnstableApi
class SchemeRoutingDataSource(
    private val default: DataSource,
    private val qobuz: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // Registered on both: which one handles a given open() isn't known yet,
        // and the bandwidth meter needs the events either way.
        default.addTransferListener(transferListener)
        qobuz.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (QobuzPartialDataSource.isQobuzUri(dataSpec.uri.toString())) qobuz else default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active) { "read() before open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
