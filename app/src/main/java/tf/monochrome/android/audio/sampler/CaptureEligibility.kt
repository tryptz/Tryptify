// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether what is playing may be captured and kept.
 *
 * The rule is provenance, and it is deliberately narrow: a capture may be
 * saved when the audio is already the user's own copy on their own device.
 * That is a file they added to the library, or a track they downloaded
 * through Tryptify. Everything else — streamed catalogue, live radio, an
 * item whose origin cannot be established — is not saveable.
 *
 * The important property is which way the check fails. An unknown source
 * resolves to *not allowed*, so a code path that forgets to register a track
 * makes the feature unavailable rather than making it permissive. There is no
 * override, and the same decision is re-checked inside
 * [SampleCaptureEngine.finish] before anything is written to disk.
 *
 * Nothing in the app captures audio it is not itself decoding: there is no
 * system playback capture, no microphone path, and no way to point the sampler
 * at another application.
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

        val source = registry[track.id]?.source
        val isLocalFile = source is PlaybackSource.LocalFile
        val isDownloaded = runCatching { library.isDownloaded(track.id) }.getOrDefault(false)

        when {
            isLocalFile -> engine.setEligibility(true, "On-device file")
            isDownloaded -> engine.setEligibility(true, "Downloaded to this device")
            else -> engine.setEligibility(
                allowed = false,
                reason = "Only on-device files and downloads can be saved as samples",
            )
        }
    }
}
