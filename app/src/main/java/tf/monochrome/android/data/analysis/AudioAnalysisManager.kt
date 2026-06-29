package tf.monochrome.android.data.analysis

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues the background audio-feature analysis pass. */
@Singleton
class AudioAnalysisManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Kicks off analysis if not already scheduled (app start / toggle on). */
    fun ensureScheduled() = enqueue(ExistingWorkPolicy.KEEP)

    /** Forces a fresh pass now (e.g. a manual "Analyze now"). */
    fun triggerNow() = enqueue(ExistingWorkPolicy.REPLACE)

    private fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AudioAnalysisWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(AudioAnalysisWorker.UNIQUE_WORK, policy, request)
    }
}
