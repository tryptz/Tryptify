package tf.monochrome.android.data.local.watcher

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val scope = CoroutineScope(Dispatchers.IO)
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

                    // Debounce: wait 500ms after last event before scanning
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(500)
                        mediaScanner.incrementalScan().collect { /* consume flow */ }
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
        private val audioExtensions = setOf(
            "flac", "mp3", "m4a", "aac", "ogg", "oga", "opus",
            "wav", "aif", "aiff", "ape", "wma"
        )
    }
}
