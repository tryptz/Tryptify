package tf.monochrome.android.player

import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.meter.DynamicRangeNative
import tf.monochrome.android.audio.meter.DynamicRangeRules
import tf.monochrome.android.data.local.db.LocalMediaDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures each local track's dynamic range as it plays, and keeps it.
 *
 * A library track that has no value yet turns the meter on from its first
 * buffer ([trackChanged]); one that has one leaves it off, so a track is
 * measured once, not on every play. When it plays through to its end — the
 * player's end, or a gapless move to the next — the reading is checked
 * ([DynamicRangeRules]) and written to that file's row alone. A seek, a skip
 * or a crossfade leaves it unmeasured until a later clean play.
 *
 * Called on the player's thread (the main looper), as its listener is.
 */
@Singleton
class DynamicRangeRecorder @Inject constructor(
    private val dao: LocalMediaDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Target(val filePath: String, val durationSeconds: Int) {
        var clean = true
    }

    private var target: Target? = null

    /** Bumped on every change, so a lookup that lands after the next one is dropped. */
    private var generation = 0

    /**
     * A new track is playing. [previousPlayedThrough]: the one before it ran to
     * its end into this one (a gapless move, or a repeat), so its reading is
     * whole and is read now, before the meter starts again.
     */
    fun trackChanged(mediaItem: MediaItem?, previousPlayedThrough: Boolean) {
        if (previousPlayedThrough) finish()
        target = null
        DynamicRangeNative.stop()
        // First, whatever the new item is: a lookup still out for the last
        // local track must not arm the meter on a stream that followed it.
        val gen = ++generation
        val rowId = localRowIdOf(mediaItem) ?: return
        scope.launch {
            val row = runCatching { dao.dynamicRangeTarget(rowId) }.getOrNull() ?: return@launch
            if (row.dynamicRange != null || row.durationSeconds <= 0) return@launch
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                target = Target(row.filePath, row.durationSeconds)
                DynamicRangeNative.start()
            }
        }
    }

    /** The play head moved. Back to the start is a fresh measurement; anywhere else spoils this one. */
    fun seeked(positionMs: Long) {
        val t = target ?: return
        if (positionMs <= RESTART_MS) {
            DynamicRangeNative.restart()
            t.clean = true
        } else {
            t.clean = false
        }
    }

    /** The player reached the end of the track, with nothing after it to move on to. */
    fun trackEnded() {
        finish()
        ++generation
        target = null
        DynamicRangeNative.stop()
    }

    private fun finish() {
        val t = target ?: return
        target = null
        val (reading, beforeFormatChange) = DynamicRangeNative.readBoth()
        val dr = DynamicRangeRules.acceptEither(reading, beforeFormatChange, t.durationSeconds, t.clean)
        if (dr == null) {
            Log.d(TAG, "not kept: $reading / $beforeFormatChange over ${t.durationSeconds} s, clean=${t.clean}")
            return
        }
        scope.launch {
            runCatching { dao.updateDynamicRange(t.filePath, dr) }
                .onFailure { Log.w(TAG, "could not save the dynamic range", it) }
        }
    }

    private companion object {
        const val TAG = "DynamicRange"

        /** A seek this close to the start is a restart, not a jump. */
        const val RESTART_MS = 500L
    }
}
