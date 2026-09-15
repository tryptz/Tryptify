package tf.monochrome.android.data.local.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val mediaScanner: MediaScanner,
    private val preferences: tf.monochrome.android.data.preferences.PreferencesManager,
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

    /**
     * Drops a folder from the library. Files on disk are untouched.
     *
     * Takes the same lock as a scan: it deletes rows and rebuilds the album,
     * artist and folder tables, which is exactly what a scan is doing in its
     * grouping phase, and the two interleaving would leave either one's output
     * half-overwritten.
     */
    suspend fun excludeFolder(path: String) {
        if (!scanMutex.tryLock()) return
        try {
            mediaScanner.excludeFolder(path)
        } finally {
            scanMutex.unlock()
        }
    }

    /**
     * Rebuilds `local_folders` once, if this build has not already done it.
     *
     * The tree used to be written without its intermediate folders, and it is
     * only rebuilt during a scan — so the fix would not reach anyone's existing
     * library until they thought to rescan. This is not a scan: one query for
     * the track paths and one table rewrite, no MediaStore and no tag reading.
     */
    suspend fun rebuildFolderTreeIfStale() {
        if (preferences.folderTreeRebuildVersion.first() >= FOLDER_TREE_REBUILD_VERSION) return
        if (!scanMutex.tryLock()) return
        try {
            mediaScanner.rebuildFolders()
            preferences.setFolderTreeRebuildVersion(FOLDER_TREE_REBUILD_VERSION)
        } finally {
            scanMutex.unlock()
        }
    }

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

    private companion object {
        /** Bump to make every install rebuild its folder tree once. */
        const val FOLDER_TREE_REBUILD_VERSION = 1
    }
}
