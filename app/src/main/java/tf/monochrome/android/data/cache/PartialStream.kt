package tf.monochrome.android.data.cache

import java.io.File
import java.io.IOException

/**
 * A cache file that is being written and read at the same time.
 *
 * The download runs ahead on its own coroutine, publishing how far it has got
 * through [publish]; a reader calls [awaitAvailable] and blocks only when it
 * catches up with the writer. That's what lets playback start on the first
 * bytes instead of waiting for the whole file.
 *
 * Both sides are plain blocking monitor calls rather than coroutines on
 * purpose: the reader is ExoPlayer's loader thread, which is designed to block
 * inside a DataSource exactly like a network read would.
 */
class PartialStream(
    /** The file being appended to. Readers open their own handle on it. */
    val file: File,
) {
    private val lock = Object()

    /** Content-Length when the server sent one, else [UNKNOWN_LENGTH]. */
    @Volatile
    var totalBytes: Long = UNKNOWN_LENGTH
        private set

    /** Bytes durably on disk and safe to read. */
    @Volatile
    var availableBytes: Long = 0L
        private set

    /** Set once the download has written every byte. */
    @Volatile
    var isComplete: Boolean = false
        private set

    /**
     * Where the finished file was installed, once complete. Readers keep using
     * [file]: an open descriptor survives the rename, so a reader that started
     * mid-download is unaffected by the install.
     */
    @Volatile
    var completedFile: File? = null
        private set

    /** Set when the download died; readers rethrow it rather than seeing a short file. */
    @Volatile
    var failure: IOException? = null
        private set

    /** Set when the whole stream is abandoned — wakes every waiter. */
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun setTotalBytes(total: Long) {
        synchronized(lock) {
            totalBytes = total
            lock.notifyAll()
        }
    }

    /** Publish progress. Call only after the bytes have been flushed to disk. */
    fun publish(available: Long) {
        synchronized(lock) {
            if (available > availableBytes) availableBytes = available
            lock.notifyAll()
        }
    }

    fun complete(finalBytes: Long, installedAs: File) {
        synchronized(lock) {
            availableBytes = maxOf(availableBytes, finalBytes)
            if (totalBytes == UNKNOWN_LENGTH) totalBytes = availableBytes
            completedFile = installedAs
            isComplete = true
            lock.notifyAll()
        }
    }

    /**
     * Block until the whole file is on disk and return it. Used by callers that
     * genuinely need every byte — sharing the file with another app, and the
     * download-on-demand path — rather than playback, which streams as it goes.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun awaitCompletion(timeoutMs: Long = COMPLETION_TIMEOUT_MS): File {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!isComplete && failure == null && !isCancelled) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw IOException("Timed out downloading ${file.name}")
                lock.wait(remaining)
            }
            failure?.let { throw it }
            return completedFile ?: throw IOException("Download of ${file.name} did not complete")
        }
    }

    fun fail(cause: IOException) {
        synchronized(lock) {
            failure = cause
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            isCancelled = true
            lock.notifyAll()
        }
    }

    /**
     * Block until more than [offset] bytes are readable, and return how many
     * are. Returns [offset] unchanged once the writer has finished (or given
     * up) without ever getting past it — the caller then sees end-of-input.
     *
     * @throws IOException if the download failed, so a truncated file is never
     *   mistaken for a complete one.
     * @throws InterruptedException if the reading thread is interrupted, which
     *   is how ExoPlayer unwinds a loader that's being cancelled.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun awaitAvailable(offset: Long, timeoutMs: Long = STALL_TIMEOUT_MS): Long {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (availableBytes <= offset && !isComplete && failure == null && !isCancelled) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw IOException("Timed out waiting for bytes past $offset in ${file.name}")
                }
                lock.wait(remaining)
            }
            failure?.let { throw it }
            if (isCancelled && availableBytes <= offset) {
                throw IOException("Stream cancelled while waiting for bytes past $offset")
            }
            return availableBytes
        }
    }

    companion object {
        const val UNKNOWN_LENGTH = -1L

        /**
         * How long a reader waits with no progress at all before giving up.
         * Generous: a stalled read should surface as a playback error rather
         * than parking an ExoPlayer loader thread forever, but it must outlast
         * an ordinary slow patch on mobile data.
         */
        const val STALL_TIMEOUT_MS = 60_000L

        /** Ceiling for a whole-file wait; only the share / on-demand paths use it. */
        const val COMPLETION_TIMEOUT_MS = 10 * 60_000L
    }
}
