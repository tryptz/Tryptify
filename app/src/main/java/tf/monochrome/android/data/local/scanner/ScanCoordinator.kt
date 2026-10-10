package tf.monochrome.android.data.local.scanner

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide scan state. Scans can be started from several places —
 * the Library tab, LibraryWatcher, and the onboarding-enqueued
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

    // Scans a screen asks for run here, not in the screen's view model: there,
    // leaving the screen cancelled the scan halfway, with the batches already
    // written cut off from their albums and artists until the next full scan.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Clears the last terminal progress so the UI can dismiss the bar. */
    fun clearProgress() { _scanProgress.value = null }

    /**
     * Runs a full scan, after any scan already in flight. For onboarding's
     * ScanWorker: dropped, as it was when busy, the first library was not
     * built if a LibraryWatcher refresh happened to be running.
     */
    suspend fun runFullScan() = runGuarded { mediaScanner.fullScan() }

    private val fullScanQueued = AtomicBoolean(false)

    /**
     * A full scan that waits for one already running instead of being dropped,
     * started here and left running whatever the caller does next. For a
     * change only a full scan can apply, such as the title source, and for
     * every scan a person asks for: dropped while another ran, a tap on
     * Rescan during LibraryWatcher's quiet refresh would do nothing at all.
     * One waiting is enough, since it reads the folders and settings when it
     * starts, so a second request meanwhile is folded into it.
     */
    fun requestFullScanAfterCurrent() {
        if (!fullScanQueued.compareAndSet(false, true)) return
        scope.launch {
            try {
                runGuarded {
                    fullScanQueued.set(false)
                    mediaScanner.fullScan()
                }
            } finally {
                fullScanQueued.set(false)
            }
        }
    }

    /**
     * Adds [path] as a library folder, then scans it in: in that order, in one
     * job. The Library tab used to save the folder and start the scan as two
     * jobs, and the scan could read the folder list before the save landed and
     * leave the new folder out (#136).
     */
    fun addFolderAndScan(path: String) {
        scope.launch {
            preferences.addUserFolderRoot(path)
            requestFullScanAfterCurrent()
        }
    }

    /**
     * The incremental scan LibraryWatcher runs when Android's audio index
     * changes. Quiet: it publishes no progress, because nobody asked for it
     * and a bar at every launch would say nothing, and the library's own
     * tables are what show the result. It waits for a scan already running
     * rather than being dropped, so a change that lands mid-scan is still
     * picked up after it.
     */
    suspend fun runBackgroundRefresh() {
        scanMutex.withLock {
            mediaScanner.incrementalScan().collect { progress ->
                when {
                    progress is ScanProgress.Error ->
                        Log.w(TAG, "library refresh failed: ${progress.message}")
                    progress is ScanProgress.Complete && (progress.added > 0 || progress.removed > 0) ->
                        Log.i(TAG, "library refresh: ${progress.added} read, ${progress.removed} removed")
                }
            }
        }
    }

    /**
     * Drops a folder from the library. Files on disk are untouched.
     *
     * Takes the same lock as a scan: it deletes rows and rebuilds the album,
     * artist and folder tables, which is exactly what a scan is doing in its
     * grouping phase, and the two interleaving would leave either one's output
     * half-overwritten. It waits for that lock, here rather than in the
     * screen's scope: it used to give up when a scan held it, and with
     * LibraryWatcher refreshing on its own, removing a folder could then
     * silently do nothing.
     */
    fun excludeFolder(path: String) {
        scope.launch {
            scanMutex.withLock { mediaScanner.excludeFolder(path) }
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

    /** Runs [scan] with its progress published, after any scan already holding the lock. */
    private suspend inline fun runGuarded(
        scan: () -> kotlinx.coroutines.flow.Flow<ScanProgress>
    ) {
        scanMutex.lock()
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
        const val TAG = "ScanCoordinator"

        /** Bump to make every install rebuild its folder tree once. */
        const val FOLDER_TREE_REBUILD_VERSION = 1
    }
}
