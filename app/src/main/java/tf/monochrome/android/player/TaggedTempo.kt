package tf.monochrome.android.player

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.tempo.BpmTapProcessor
import tf.monochrome.android.audio.tempo.SongTempo
import tf.monochrome.android.data.local.db.LocalMediaDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A track's tempo from the whole of it, in place of ten seconds' estimate:
 * a library track's own BPM tag where it has one, or else the whole file
 * measured ([SongTempo]).
 *
 * DJ software writes the tag from the whole track, or a person typed it; the
 * live estimate has ten seconds to go on and can read half or double. So a
 * tagged track's tempo is its tag from the start, as a typed tempo is. A file
 * on the phone without one is measured from end to end in the background,
 * and the live estimate waits for it. A stream keeps the live estimate, and a
 * tap on the number still measures any track from the audio playing.
 *
 * Called on the player's thread, as its listener is.
 */
@Singleton
class TaggedTempo @Inject constructor(
    private val dao: LocalMediaDao,
    private val bpmTap: BpmTapProcessor,
    private val songTempo: SongTempo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every change, so a lookup that lands after the next track's is dropped. */
    private var generation = 0

    /** The last track's lookup and measurement, stopped when the track changes. */
    private var lookup: Job? = null

    /** A new track: after [BpmTapProcessor.newTrack], so its tempo wins over the measurement it started. */
    fun trackChanged(mediaItem: MediaItem?) {
        val gen = ++generation
        lookup?.cancel()
        val rowId = localRowIdOf(mediaItem)
        val uri = mediaItem?.localConfiguration?.uri?.takeIf { songTempo.canMeasure(it) }
        if (rowId == null && uri == null) return
        if (uri != null) bpmTap.awaitSong()
        lookup = scope.launch {
            val tagged = rowId?.let { runCatching { dao.bpmOf(it) }.getOrNull() }
            val bpm = tagged ?: uri?.let { songTempo.of(it) }
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                when {
                    bpm != null -> bpmTap.setTempo(bpm)
                    // The song could not be read, or has no steady pulse
                    // across it: the live estimate after all.
                    uri != null -> bpmTap.measure()
                }
            }
        }
    }
}
