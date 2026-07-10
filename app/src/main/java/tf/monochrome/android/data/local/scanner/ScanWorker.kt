package tf.monochrome.android.data.local.scanner

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot full library scan, enqueued when the user finishes (or skips)
 * onboarding. A worker rather than a plain coroutine so the initial scan
 * survives process death right after setup. Everything it needs — folder
 * roots, thresholds — is read from preferences inside MediaScanner, so
 * there's no input data.
 */
@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanCoordinator: ScanCoordinator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        scanCoordinator.runFullScan()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "initial_library_scan"

        /**
         * KEEP: re-finishing onboarding (e.g. restarted from Settings) while
         * a scan is already queued/running must not restart it.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScanWorker>().build()
            )
        }
    }
}
