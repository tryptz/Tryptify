package tf.monochrome.android.data.analysis

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.player.StreamResolver

/**
 * Background pass that measures audio features for every not-yet-analysed
 * track in the user's library. Resolves each track to a decodable URI through
 * the existing [StreamResolver] (so local files, Qobuz-cached files and
 * progressive HiFi streams all work), then runs [AudioFeatureAnalyzer].
 *
 * DASH (inline base64 MPD) and encrypted-collection sources can't be read by
 * MediaExtractor, so they're skipped. The worklist is deduplicated against
 * already-analysed rows, so if the OS stops the worker it simply resumes on the
 * next run.
 */
@HiltWorker
class AudioAnalysisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AudioFeatureRepository,
    private val analyzer: AudioFeatureAnalyzer,
    private val streamResolver: StreamResolver,
    private val preferences: PreferencesManager,
) : CoroutineWorker(context, params) {

    @UnstableApi
    override suspend fun doWork(): Result {
        if (!preferences.analyzeAudioFeatures.first()) return Result.success()

        val pending = runCatching { repository.pendingTracks() }.getOrDefault(emptyList())
        // Record the run target so the Settings screen can show "done / total".
        preferences.setAudioAnalysisTarget(repository.analyzedCount() + pending.size)
        if (pending.isEmpty()) return Result.success()

        for (track in pending) {
            if (isStopped) break
            try {
                val resolved = streamResolver.resolveUnifiedTrack(track)
                if (!resolved.isPlayable || resolved.isDash || resolved.isEncrypted) continue
                val uri = resolved.mediaItem.localConfiguration?.uri ?: continue
                val features = analyzer.analyze(uri) ?: continue
                repository.save(track, features, System.currentTimeMillis())
            } catch (_: Exception) {
                // Skip this track; the next run will retry it (still pending).
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK = "audio_feature_analysis"
    }
}
