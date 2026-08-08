package tf.monochrome.android.data.local

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monotonic counter bumped whenever audio files are added to the device — a
 * finished library scan, a finished download.
 *
 * Consumers that cache "this song isn't on disk" answers key them off [value]
 * so the very next play after a scan or a download sees the new file instead
 * of a stale negative. Removals need no bump: a cached *positive* answer is
 * re-checked against the filesystem before it's used.
 */
@Singleton
class LocalLibraryRevision @Inject constructor() {

    private val counter = AtomicLong(0)

    val value: Long get() = counter.get()

    fun bump() {
        counter.incrementAndGet()
    }
}
