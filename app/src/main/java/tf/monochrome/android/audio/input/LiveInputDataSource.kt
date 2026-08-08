package tf.monochrome.android.audio.input

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import kotlin.math.min

/**
 * The carrier stream for Live line-in mode.
 *
 * The DSP chain lives *inside* `DefaultAudioSink`, which only runs while
 * ExoPlayer is rendering something. In Mix mode there is a real track keeping
 * it turning; in Live mode there isn't, so this stands in: an endless silent
 * WAV that ExoPlayer plays like any other progressive source. The actual audio
 * arrives further down, where `MixBusProcessor` hands the captured line-in to
 * the native engine and the buses routed to `LineIn` read that instead of this
 * silence.
 *
 * Two consequences worth being explicit about, because they look like bugs
 * otherwise:
 *
 * - **No real-time pacing.** It emits silence as fast as ExoPlayer asks. That
 *   is correct rather than sloppy: the injection point is downstream of
 *   ExoPlayer's buffer, so however far ahead the silence is buffered adds
 *   nothing to monitoring latency, and refusing to fill the buffer would only
 *   delay the start (`bufferForPlaybackMs` is 2.5 s).
 * - **A finite, very long duration.** WAV has a 32-bit data-chunk size, so the
 *   header declares ~3.1 h of silence rather than true infinity. Nobody
 *   monitors a line input for three hours straight, and the alternative
 *   (unbounded length) makes the extractor's seek map and the progress bar
 *   behave far worse.
 */
@UnstableApi
class LiveInputDataSource(
    private val sampleRate: Int,
) : BaseDataSource(/* isNetwork= */ false) {

    private var dataSpec: DataSpec? = null
    private var opened = false

    /** Bytes served so far, header included — the position in the synthetic file. */
    private var position: Long = 0L

    private val header: ByteArray by lazy { buildWavHeader(sampleRate) }
    private val totalBytes: Long get() = HEADER_BYTES + DATA_BYTES

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        position = dataSpec.position.coerceIn(0L, totalBytes)
        opened = true
        transferStarted(dataSpec)
        val remaining = totalBytes - position
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining
        else min(dataSpec.length, remaining)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = totalBytes - position
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), remaining).toInt()
        var written = 0

        // Header first, then silence. Only the very first read straddles the
        // boundary, but a caller is free to ask for two bytes at a time.
        if (position < HEADER_BYTES) {
            val fromHeader = min(toRead.toLong(), HEADER_BYTES - position).toInt()
            System.arraycopy(header, position.toInt(), buffer, offset, fromHeader)
            written += fromHeader
        }
        if (written < toRead) {
            java.util.Arrays.fill(buffer, offset + written, offset + toRead, 0.toByte())
            written = toRead
        }

        position += written
        bytesTransferred(written)
        return written
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }

    /** Serves [LIVE_INPUT_URI] from this source and delegates nothing else. */
    class Factory(private val sampleRate: Int) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveInputDataSource(sampleRate)
    }

    companion object {
        /** Custom scheme so the carrier can never collide with a real media URI. */
        val LIVE_INPUT_URI: Uri = Uri.parse("tryptify-input://live")

        private const val HEADER_BYTES = 44L

        /**
         * ~3.1 h at 48 kHz / 16-bit stereo. Sized so the RIFF chunk size
         * (`36 + DATA_BYTES`) still fits in a *signed* 32-bit int — plenty of
         * WAV parsers read those fields as signed, and a value that wraps
         * negative reads as a corrupt file. Also a whole number of 4-byte
         * frames so the extractor never sees a split sample at the end.
         */
        private const val DATA_BYTES = 0x7FFFFFD8L

        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16

        /** Only the WAV path is ever needed here; skip sniffing the other 20-odd extractors. */
        fun extractorsFactory(): ExtractorsFactory = ExtractorsFactory { arrayOf(WavExtractor()) }

        /** The queue entry shown on the lock screen and in the notification. */
        fun mediaItem(deviceLabel: String): MediaItem = MediaItem.Builder()
            .setUri(LIVE_INPUT_URI)
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Line In")
                    .setArtist(deviceLabel)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        const val MEDIA_ID = "tryptify:line-in"

        private fun buildWavHeader(sampleRate: Int): ByteArray {
            val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
            val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
            val out = ByteArray(HEADER_BYTES.toInt())
            var i = 0
            fun ascii(s: String) { for (c in s) out[i++] = c.code.toByte() }
            fun le32(v: Long) {
                out[i++] = (v and 0xFF).toByte()
                out[i++] = ((v shr 8) and 0xFF).toByte()
                out[i++] = ((v shr 16) and 0xFF).toByte()
                out[i++] = ((v shr 24) and 0xFF).toByte()
            }
            fun le16(v: Int) {
                out[i++] = (v and 0xFF).toByte()
                out[i++] = ((v shr 8) and 0xFF).toByte()
            }

            ascii("RIFF")
            le32(36L + DATA_BYTES)   // RIFF chunk size — everything after this field
            ascii("WAVE")
            ascii("fmt ")
            le32(16L)                // PCM fmt chunk size
            le16(1)                  // format = PCM
            le16(CHANNELS)
            le32(sampleRate.toLong())
            le32(byteRate.toLong())
            le16(blockAlign)
            le16(BITS_PER_SAMPLE)
            ascii("data")
            le32(DATA_BYTES)
            return out
        }
    }
}
