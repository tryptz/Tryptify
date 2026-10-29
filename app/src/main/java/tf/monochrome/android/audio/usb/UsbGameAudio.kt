// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.usb

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.audio.resample.FloatSonicAudioProcessor
import tf.monochrome.android.audio.resample.VariRateAudioProcessor

/**
 * Trypt Mania's way onto the exclusive USB DAC.
 *
 * The mode's players (`GlyphAudioTransport`, `GlyphPreviewPlayer`) stay
 * ExoPlayers of their own, outside the shared player on purpose; what
 * changes is where their audio goes. Each gets a [LibusbAudioSink] from
 * [renderersFactory], so with a DAC claimed the song plays through it, at
 * the DAC level the volume keys set, and without one the sink passes
 * through to Android's output as before.
 *
 * The DAC has one stream, so the sink takes it from the main player as
 * [StreamClaimant.GAME] ([StreamLease]): the main player pauses while the
 * game holds it, and the next play on the main player takes it back.
 *
 * No DSP: the chain is only what the sink needs to feed the DAC (float
 * samples, the guard for decoders that lie about them) and the practice
 * speed (the resampler for pitch that follows speed, time-stretching for
 * pitch that does not). The processors are this player's own, not the main
 * player's shared stages, which only one chain may configure at a time.
 */
@Singleton
@UnstableApi
class UsbGameAudio @Inject constructor(
    private val driver: LibusbUacDriver,
    private val volume: BypassVolumeController,
) {
    /** Who holds the DAC's stream (see [StreamLease.holder]); the game is on the DAC while it is [StreamClaimant.GAME]. */
    val holder: StateFlow<StreamClaimant?> get() = driver.streamHolder

    /** True while Trypt Mania's audio is what the DAC plays. */
    fun gameOnDac(): Boolean = driver.holdsStream(StreamClaimant.GAME)

    /** Renderers whose audio sink goes to the DAC when one is claimed; one per player. */
    fun renderersFactory(context: Context): RenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val resampler = VariRateAudioProcessor()
            val timeStretch = FloatSonicAudioProcessor()
            return LibusbAudioSink(
                delegate = DefaultAudioSink.Builder(context)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build(),
                driver = driver,
                volumeController = volume,
                processors = listOf(FloatPcmGuard(), ToFloatPcmAudioProcessor(), resampler, timeStretch),
                resampler = resampler,
                timeStretch = timeStretch,
                claimant = StreamClaimant.GAME,
                // The player's own volume (a preview's fade) under the DAC level,
                // not the main player's gain, which is another song's.
                ownsVolume = true,
            )
        }
    }
}
