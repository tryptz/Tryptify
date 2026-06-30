package tf.monochrome.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
class DynamicLoadControl(
    private val standard: LoadControl = standardLoadControl(),
    private val highRes: LoadControl = highResLoadControl(),
) : LoadControl {
    private val hiResMode = AtomicBoolean(false)

    fun setHiResMode(enabled: Boolean) {
        hiResMode.set(enabled)
    }

    fun isHiResMode(): Boolean = hiResMode.get()

    private fun active(): LoadControl = if (hiResMode.get()) highRes else standard

    override fun onPrepared(playerId: PlayerId) {
        standard.onPrepared(playerId)
        highRes.onPrepared(playerId)
    }

    override fun onPrepared() {
        standard.onPrepared()
        highRes.onPrepared()
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        standard.onTracksSelected(parameters, trackGroups, trackSelections)
        highRes.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        standard.onStopped(playerId)
        highRes.onStopped(playerId)
    }

    override fun onStopped() {
        standard.onStopped()
        highRes.onStopped()
    }

    override fun onReleased(playerId: PlayerId) {
        standard.onReleased(playerId)
        highRes.onReleased(playerId)
    }

    override fun onReleased() {
        standard.onReleased()
        highRes.onReleased()
    }

    override fun getAllocator(): Allocator {
        return active().allocator
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        return active().shouldContinueLoading(parameters)
    }

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean {
        return active().shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        return active().shouldStartPlayback(parameters)
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long {
        return active().getBackBufferDurationUs(playerId)
    }

    override fun getBackBufferDurationUs(): Long {
        return active().backBufferDurationUs
    }

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean {
        return active().retainBackBufferFromKeyframe(playerId)
    }

    override fun retainBackBufferFromKeyframe(): Boolean {
        return active().retainBackBufferFromKeyframe()
    }

    companion object {
        fun standardLoadControl(): LoadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

        fun highResLoadControl(): LoadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 120_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .build()
    }
}
