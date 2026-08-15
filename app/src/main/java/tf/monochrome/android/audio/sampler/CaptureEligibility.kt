// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out where what is playing came from, and labels the capture with it.
 *
 * Capture is allowed for anything Tryptify is decoding. This class does not
 * gatekeep — it *labels*, so a capture can be attributed: every saved sample
 * records the track, artist and position it came from, and
 * [SampleCaptureEngine.Eligibility.reason] puts that on screen. The user
 * always knows what they are sampling.
 *
 * The only thing that reports "not allowed" is having nothing to capture —
 * no track loaded. That is a statement of fact rather than a policy.
 *
 * Scope is bounded by construction rather than by rules: the taps read
 * Tryptify's own decoder and DSP output, so the sampler records what this app
 * is playing and nothing else. There is no system playback capture and no
 * microphone path.
 */
@Singleton
class CaptureEligibilityResolver @Inject constructor(
    private val queueManager: QueueManager,
    private val registry: UnifiedTrackRegistry,
    private val library: LibraryRepository,
) {

    /**
     * Recomputes eligibility for whatever is playing and pushes it into
     * [engine]. Suspends because the download check is a database read.
     */
    suspend fun refresh(engine: SampleCaptureEngine) {
        val track = queueManager.currentTrack.value
        if (track == null) {
            engine.setEligibility(allowed = false, reason = "Nothing is playing")
            return
        }

        val isLocalFile = registry[track.id]?.source is PlaybackSource.LocalFile
        val isDownloaded = runCatching { library.isDownloaded(track.id) }.getOrDefault(false)

        val label = when {
            isLocalFile -> "On-device file"
            isDownloaded -> "Downloaded to this device"
            else -> "Streaming — ${track.displayArtist}"
        }
        engine.setEligibility(allowed = true, reason = label)
    }
}
