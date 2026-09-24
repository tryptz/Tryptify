package tf.monochrome.android.data.spotify

import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkOutput
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The pipe between librespot's decoder and ExoPlayer: librespot's player is
 * configured with `setOutputClass(PcmSink)`, so it reflects an instance of
 * this class and writes every decoded PCM chunk into it. The chunk queue is
 * what [PcmSinkDataSource] — an ExoPlayer DataSource — pulls from, turning
 * Spotify's audio into just another MediaItem inside Tryptify's queue.
 *
 * Ring-buffer semantics: if the reader falls behind, the oldest chunk is
 * dropped rather than blocking Spotify's decoder thread.
 */
class PcmSink : SinkOutput {

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    override fun start(format: OutputAudioFormat): Boolean {
        PcmSinkRegistry.sink = this
        return true
    }

    override fun write(buffer: ByteArray, offset: Int, len: Int) {
        val chunk = if (offset == 0 && len == buffer.size) buffer else buffer.copyOfRange(offset, offset + len)
        // Drop the oldest chunk when the reader stalls — audio keeps flowing.
        while (!queue.offer(chunk)) {
            queue.poll()
        }
    }

    override fun flush() {
        queue.clear()
    }

    override fun stop() {
        flush()
    }

    override fun release() {
        flush()
    }

    override fun close() = flush()

    /** Called by [PcmSinkDataSource]; blocks up to [timeoutMs] for the next chunk. */
    fun read(timeoutMs: Long): ByteArray? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private companion object {
        const val QUEUE_CAPACITY = 256
    }
}
