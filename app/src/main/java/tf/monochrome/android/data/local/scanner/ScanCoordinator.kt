package tf.monochrome.android.data.local.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide scan state. Scans can be started from several places —
 * the Library tab, the FileObserver watcher, and the onboarding-enqueued
 * ScanWorker — but progress used to live in per-ViewModel StateFlows, so a
 * scan started anywhere else was invisible to the Library UI and nothing
 * stopped two entry points from scanning concurrently. All entry points go
 * through here instead: one shared progress stream, one global in-flight
 * guard.
 */
@Singleton
class ScanCoordinator @Inject constructor(
    private val mediaScanner: MediaScanner
) {
    private val scanMutex = Mutex()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Clears the last terminal progress so the UI can dismiss the bar. */
    fun clearProgress() { _scanProgress.value = null }

    /** Runs a full scan, or returns immediately if any scan is in flight. */
    suspend fun runFullScan() = runGuarded { mediaScanner.fullScan() }

    /** Runs an incremental scan, or returns immediately if any scan is in flight. */
    suspend fun runIncrementalScan() = runGuarded { mediaScanner.incrementalScan() }

    private suspend inline fun runGuarded(
        scan: () -> kotlinx.coroutines.flow.Flow<ScanProgress>
    ) {
        if (!scanMutex.tryLock()) return
        try {
            _isScanning.value = true
            scan().collect { progress ->
                _scanProgress.value = progress
                if (progress is ScanProgress.Complete || progress is ScanProgress.Error) {
                    _isScanning.value = false
                }
            }
        } finally {
            _isScanning.value = false
            scanMutex.unlock()
        }
    }
}
