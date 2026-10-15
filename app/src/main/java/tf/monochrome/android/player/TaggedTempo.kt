package tf.monochrome.android.player

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.tempo.BpmTapProcessor
import tf.monochrome.android.data.local.db.LocalMediaDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A library track's tempo from its own BPM tag, where it has one, in place
 * of the estimate from its audio.
 *
 * DJ software writes the tag from the whole track, or a person typed it; the
 * estimate has ten seconds to go on and can read half or double. So a tagged
 * track's tempo is its tag from the start, as a typed tempo is, and a tap on
 * the number still measures it from the audio.
 *
 * Called on the player's thread, as its listener is.
 */
@Singleton
class TaggedTempo @Inject constructor(
    private val dao: LocalMediaDao,
    private val bpmTap: BpmTapProcessor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every change, so a lookup that lands after the next track's is dropped. */
    private var generation = 0

    /** A new track: after [BpmTapProcessor.newTrack], so its tag wins over the measurement it started. */
    fun trackChanged(mediaItem: MediaItem?) {
        val gen = ++generation
        val rowId = localRowIdOf(mediaItem) ?: return
        scope.launch {
            val bpm = runCatching { dao.bpmOf(rowId) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (gen == generation) bpmTap.setTempo(bpm)
            }
        }
    }
}
