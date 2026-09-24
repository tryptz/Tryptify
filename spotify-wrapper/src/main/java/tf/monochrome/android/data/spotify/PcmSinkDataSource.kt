package tf.monochrome.android.data.spotify

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import tf.monochrome.android.data.cache.SilentWav

/**
 * Serves `spotify-pcm://track/<base62>?durationMs=N` (see [SpotifyPcmUri]) as a
 * WAV file whose payload is the PCM librespot is decoding right now. ExoPlayer
 * plays it like any local WAV — which is the point: the samples come out of
 * its decoder stage into the renderer's AudioProcessor chain, so the DSP,
 * AutoEQ, the mixer, the visualizer taps and the USB DAC route all apply.
 *
 * **Why a WAV and not raw PCM.** ExoPlayer has no extractor for headerless
 * PCM. A WAV header built from the track's known length (the same 44.1 kHz /
 * 16-bit / stereo layout as [SilentWav], which is also librespot's output
 * format) gives it a duration and a seek map for free.
 *
 * **Seeking** is how WAV seeking always works: WavExtractor maps a time to a
 * byte offset and reopens this source there. [open] turns that offset back
 * into milliseconds and has librespot seek, so a scrub lands where asked.
 *
 * **Length mismatch.** Spotify's advertised duration and the decoded length
 * differ by a few milliseconds. If librespot finishes early the rest is
 * padded with silence; if it runs long the tail is cut at the declared length.
 */
@UnstableApi
class PcmSinkDataSource(
    private val librespot: LibrespotPlayerWrapper,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var spotifyUri: String? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var bytesRemaining = 0L
    private var generation = NO_STREAM
    private var drained = false   // librespot finished; the rest is silence
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val request = SpotifyPcmUri.parse(dataSpec.uri.toString())
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        if (!librespot.isConnected || !PcmSinkRegistry.formatSupported) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        transferInitializing(dataSpec)

        val dataSize = SilentWav.dataSize(request.durationMs)
        val total = SilentWav.HEADER_SIZE + dataSize
        if (dataSpec.position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        uri = dataSpec.uri
        spotifyUri = request.spotifyUri
        header = SilentWav.header(dataSize)
        position = dataSpec.position
        val available = total - position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }
        // The stream itself starts on the first PCM read, not here: opening
        // just to read the header (preparation, preloading) must not take
        // librespot away from the track that is playing.
        generation = NO_STREAM
        drained = false
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), bytesRemaining).toInt()

        val count = if (position < SilentWav.HEADER_SIZE) {
            val n = minOf(wanted.toLong(), SilentWav.HEADER_SIZE - position).toInt()
            System.arraycopy(header, position.toInt(), buffer, offset, n)
            n
        } else {
            readPcm(buffer, offset, wanted) ?: return C.RESULT_END_OF_INPUT
        }
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    /** Null when the stream was superseded by another open. */
    private fun readPcm(buffer: ByteArray, offset: Int, wanted: Int): Int? {
        if (drained) {
            java.util.Arrays.fill(buffer, offset, offset + wanted, 0.toByte())
            return wanted
        }
        if (generation == NO_STREAM) {
            val startMs = pcmOffsetToMs(position - SilentWav.HEADER_SIZE)
            generation = try {
                librespot.openStream(checkNotNull(spotifyUri), startMs)
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
        }
        val n = PcmSinkRegistry.pipe.read(generation, buffer, offset, wanted, READ_TIMEOUT_MS)
        return when (n) {
            PcmPipe.ENDED -> {
                // Decoded length came up short of the advertised duration.
                drained = true
                java.util.Arrays.fill(buffer, offset, offset + wanted, 0.toByte())
                wanted
            }
            PcmPipe.SUPERSEDED -> null
            PcmPipe.TIMED_OUT -> {
                // Forget the stream: ExoPlayer retries the load by reopening at
                // the current byte offset, which restarts librespot there.
                generation = NO_STREAM
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            }
            else -> n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        spotifyUri = null
        generation = NO_STREAM
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val librespot: LibrespotPlayerWrapper) : DataSource.Factory {
        override fun createDataSource(): DataSource = PcmSinkDataSource(librespot)
    }

    companion object {
        private const val NO_STREAM = -1L
        private const val READ_TIMEOUT_MS = 10_000L
        private const val BYTES_PER_SECOND =
            PcmSinkRegistry.SAMPLE_RATE.toLong() * PcmSinkRegistry.BYTES_PER_FRAME

        /**
         * The play position, in whole milliseconds, of byte [pcmOffset] of the
         * PCM payload. Rounds down; the sub-millisecond remainder is below the
         * precision of a Vorbis seek anyway.
         */
        fun pcmOffsetToMs(pcmOffset: Long): Int =
            (pcmOffset.coerceAtLeast(0L) * 1000 / BYTES_PER_SECOND)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
