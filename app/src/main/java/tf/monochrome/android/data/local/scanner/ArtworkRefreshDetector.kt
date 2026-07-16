package tf.monochrome.android.data.local.scanner

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import tf.monochrome.android.data.local.db.LocalMediaDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects evicted album-art cache files at app start and self-heals.
 *
 * Embedded covers are extracted to `cacheDir/artwork` at scan time, and
 * Android reclaims the cache directory under storage pressure (or a
 * "cleaner" app / manual "Clear cache" wipes it). Room rows then point at
 * vanished JPGs, so every local track and album renders a placeholder until
 * the user manually hits refresh in the Library tab. Running
 * [refreshIfArtworkMissing] once per process start makes that repair
 * automatic — needsReRead() already re-extracts art for rows whose cache
 * file is gone; nothing was triggering it without user action.
 *
 * Only keys inside our own artwork cache dir are probed. Sidecar covers
 * (cover.jpg next to the audio) live on external storage, where a missing
 * file can simply mean an unmounted SD card — auto-starting a scan in that
 * state would prune the whole volume's tracks from the library.
 */
@Singleton
class ArtworkRefreshDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaDao: LocalMediaDao,
    private val scanCoordinator: ScanCoordinator,
) {

    suspend fun refreshIfArtworkMissing() {
        // Empty library: nothing to repair, and onboarding's ScanWorker owns
        // the first scan.
        if (localMediaDao.getTrackCount() == 0) return

        val evicted = countEvictedArtwork(
            cachedKeys = localMediaDao.getDistinctArtworkCacheKeys(),
            artworkCacheDirPath = File(context.cacheDir, "artwork").absolutePath,
        )
        if (evicted == 0) return

        Log.i(TAG, "$evicted cached artwork file(s) evicted — rescanning to restore album art")
        // fullScan, not incremental: the cache files vanished without the
        // audio files' mtime changing, and only fullScan's needsReRead()
        // path re-checks artwork existence. It re-reads tags solely for the
        // broken rows, so an otherwise-intact library passes through fast.
        scanCoordinator.runFullScan()
    }

    companion object {
        private const val TAG = "ArtworkRefresh"

        /**
         * Count cache keys under [artworkCacheDirPath] whose file no longer
         * exists. Pure function so it's unit testable; [artworkFileExists]
         * is injectable for the same reason.
         */
        fun countEvictedArtwork(
            cachedKeys: List<String>,
            artworkCacheDirPath: String,
            artworkFileExists: (String) -> Boolean = { File(it).exists() },
        ): Int {
            val root = artworkCacheDirPath.trimEnd('/') + "/"
            return cachedKeys.count { it.startsWith(root) && !artworkFileExists(it) }
        }
    }
}
