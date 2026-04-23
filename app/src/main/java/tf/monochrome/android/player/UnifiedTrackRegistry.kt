package tf.monochrome.android.player

import tf.monochrome.android.domain.model.UnifiedTrack
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared map from legacy [tf.monochrome.android.domain.model.Track.id] to the
 * originating [UnifiedTrack] (local file, encrypted collection entry, etc.).
 *
 * The main-process [tf.monochrome.android.ui.player.PlayerViewModel] populates
 * this when the user plays a unified queue, and both the ViewModel's
 * `resolveAndPlay()` path and the [PlaybackService]'s media-button / notification
 * skip path consult it to decide which resolver branch to use (unified local
 * vs. legacy HiFi API).
 *
 * Without a shared registry, notification / lock-screen next / previous taps
 * reach `PlaybackService.skipToNext()` → `playQueue()`, which would only ever
 * call the HiFi API resolver — so skipping in the notification while playing
 * a local file would silently fail.
 */
@Singleton
class UnifiedTrackRegistry @Inject constructor() {
    private val map = ConcurrentHashMap<Long, UnifiedTrack>()

    fun put(trackId: Long, unifiedTrack: UnifiedTrack) {
        map[trackId] = unifiedTrack
    }

    fun putAll(entries: List<UnifiedTrack>, legacyIdFor: (UnifiedTrack) -> Long) {
        entries.forEach { map[legacyIdFor(it)] = it }
    }

    operator fun get(trackId: Long): UnifiedTrack? = map[trackId]

    fun clear() { map.clear() }
}
