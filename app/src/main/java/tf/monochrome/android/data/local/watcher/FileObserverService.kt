package tf.monochrome.android.data.local.watcher

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.data.local.scanner.MediaScanner
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches known music directories for file changes in real time.
 * Debounces events and triggers incremental scans when files change.
 */
@Singleton
class FileObserverService @Inject constructor(
    private val mediaScanner: MediaScanner
) {
    private val observers = mutableListOf<FileObserver>()
    // SupervisorJob so one failed incremental scan doesn't cancel the whole
    // scope and silently kill file watching for the rest of the session.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var isRunning = false

    @Suppress("NewApi") // FileObserver(File, Int) requires API 29; gracefully skipped on older
    fun startWatching(directories: List<String>) {
        if (isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        isRunning = true

        for (dirPath in directories) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) continue

            val observer = object : FileObserver(dir, CREATE or DELETE or MODIFY or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    // Only react to audio files
                    val ext = path.substringAfterLast('.').lowercase()
                    if (ext !in audioExtensions) return

                    // Debounce: wait 500ms after last event before scanning.
                    // Catch exceptions explicitly — an unhandled throw from
                    // incrementalScan() would propagate to the IO thread's
                    // uncaught handler (process crash) even with the
                    // SupervisorJob above.
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        try {
                            delay(500)
                            mediaScanner.incrementalScan().collect { /* consume flow */ }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (t: Throwable) {
                            android.util.Log.w("FileObserverService", "incremental scan failed, will retry on next event", t)
                        }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    fun stopWatching() {
        isRunning = false
        debounceJob?.cancel()
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    companion object {
        // Broad coverage so a file drop into a watched folder in any of the
        // common audio containers triggers a rescan. The scanner downstream
        // (MediaStoreSource + TagReader) decides whether we can actually
        // identify / play the file; dropping it here based on extension alone
        // hides hi-res / lossless formats even before we've seen them.
        private val audioExtensions = setOf(
            // Lossy
            "mp3", "aac", "m4a", "m4b", "m4p", "3gp",
            "ogg", "oga", "opus",
            "wma", "mpc", "mp2", "mp1",
            // Lossless
            "flac", "alac", "wav", "wave", "w64",
            "aif", "aiff", "aifc",
            "ape", "tak", "wv", "tta", "shn",
            // High-res / DSD / niche
            "dsf", "dff", "dsd",
            // Container-only (codec inside)
            "mka", "caf", "mpd",
            // Misc
            "au", "amr", "ra", "rm",
        )
    }
}
