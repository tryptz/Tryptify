// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.tempo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder

/**
 * A whole song's tempo, from its file: [TempoEstimator] over every second of
 * it rather than the ten the player hears live, with
 * [TempoEstimator.wholeSongEstimate] placing the beat across all of them.
 *
 * The file is decoded on the phone a block at a time ([PcmDecoder]); nothing
 * is separated and nothing leaves the phone. Only what [PcmDecoder] can open
 * is measured: a file or a content URI. A stream would be downloaded a second
 * time for one number, so a streamed track keeps the live measurement.
 *
 * Read by the player for a library track without a BPM tag, and by chart
 * generation to settle half or double time, which the drums' envelope alone
 * cannot (its hats fill every eighth).
 */
@Singleton
class SongTempo @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Measured songs, by URI: a song played again is not decoded again. */
    private val measured = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>) = size > CACHED
    }

    /** Whether [uri] is one [of] can read. */
    fun canMeasure(uri: Uri?): Boolean = uri?.scheme in READABLE

    /** [uri]'s tempo in BPM, or null when it cannot be read or has no steady pulse. Off the main thread. */
    suspend fun of(uri: Uri): Float? {
        if (!canMeasure(uri)) return null
        val key = uri.toString()
        synchronized(measured) { measured[key] }?.let { return it }
        val bpm = withContext(Dispatchers.Default) {
            val job = coroutineContext
            var estimator: TempoEstimator? = null
            var mono = FloatArray(0)
            runCatching {
                PcmDecoder.stream(
                    context = context,
                    uri = uri,
                    isActive = { job.isActive },
                    onFormat = { format ->
                        // A step every 5 ms for the whole song (as long as
                        // the longest the window holds when it does not say).
                        val seconds = if (format.durationUs > 0) format.durationUs / 1_000_000L + 2 else MAX_SECONDS
                        val steps = (minOf(seconds, MAX_SECONDS) * TempoEstimator.ENVELOPE_HZ).toInt()
                        estimator = TempoEstimator(format.sampleRate, maxOf(steps, TempoEstimator.WINDOW_FRAMES))
                    },
                ) { left, right, frames ->
                    val est = estimator ?: return@stream
                    if (mono.size < frames) mono = FloatArray(frames)
                    if (right == null) {
                        System.arraycopy(left, 0, mono, 0, frames)
                    } else {
                        for (i in 0 until frames) mono[i] = (left[i] + right[i]) * 0.5f
                    }
                    est.feed(mono, frames)
                }
            }.getOrNull() ?: return@withContext null
            job.ensureActive()
            estimator?.wholeSongEstimate()
        } ?: return null
        synchronized(measured) { measured[key] = bpm }
        return bpm
    }

    private companion object {
        val READABLE = setOf("file", "content")
        const val CACHED = 256

        /** Longer than this (a DJ mix), the tempo is the last twenty minutes'. */
        const val MAX_SECONDS = 20L * 60L
    }
}
