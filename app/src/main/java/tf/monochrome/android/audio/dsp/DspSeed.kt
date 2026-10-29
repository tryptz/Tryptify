// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.dsp

import androidx.media3.common.util.UnstableApi
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedState
import tf.monochrome.android.audio.dsp.oxford.CompressorState
import tf.monochrome.android.audio.dsp.oxford.InflatorState
import tf.monochrome.android.domain.model.EqBand

/**
 * Everything the main player's chain is set to, as one value: what a copy
 * of the chain ([DspChain.seedFrom]) needs to sound the same. PlaybackService
 * makes one whenever a setting lands on its singletons and hands it to
 * whoever runs a copy for longer than a blend (Trypt Mania's players,
 * `UsbGameAudio`), so those follow the settings live instead of being
 * seeded once.
 */
data class DspSeed(
    val dspStateJson: String?,
    val autoEqBandsL: List<EqBand>,
    val autoEqBandsR: List<EqBand>,
    val autoEqPreamp: Float,
    val autoEqEnabled: Boolean,
    val parametricBands: List<EqBand>,
    val parametricPreamp: Float,
    val parametricEnabled: Boolean,
    val inflator: InflatorState,
    val compressor: CompressorState,
    val crossfeed: CrossfeedState,
    val blockSize: Int,
    val dspEnabled: Boolean,
)

/** [DspChain.seedFrom] from a [DspSeed]; the pitch ratio is this stream's own. */
@UnstableApi
fun DspChain.seed(seed: DspSeed, autoEqPitchRatio: Float) = seedFrom(
    dspStateJson = seed.dspStateJson,
    autoEqBandsL = seed.autoEqBandsL,
    autoEqBandsR = seed.autoEqBandsR,
    autoEqPreamp = seed.autoEqPreamp,
    autoEqEnabled = seed.autoEqEnabled,
    parametricBands = seed.parametricBands,
    parametricPreamp = seed.parametricPreamp,
    parametricEnabled = seed.parametricEnabled,
    inflatorState = seed.inflator,
    compressorState = seed.compressor,
    crossfeedState = seed.crossfeed,
    blockSize = seed.blockSize,
    dspEnabled = seed.dspEnabled,
    autoEqPitchRatio = autoEqPitchRatio,
)
