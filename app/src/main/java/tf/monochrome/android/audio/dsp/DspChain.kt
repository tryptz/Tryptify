package tf.monochrome.android.audio.dsp

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor

/**
 * A complete, independent copy of the audio processing chain — its own native
 * DSP engine, its own AutoEQ and Parametric EQ, its own Oxford post-chain.
 *
 * The app's normal chain is a set of singletons wired into the one ExoPlayer.
 * That is right for a single stream and wrong for a crossfade, where two tracks
 * are sounding at once: a processor holds per-stream filter state, and the
 * native engine holds one sample rate, so feeding two streams through a single
 * chain would corrupt both. Playing the outgoing tail *without* processing
 * isn't acceptable either — it would audibly drop the user's EQ and DSP for the
 * length of every blend.
 *
 * So the blend gets its own chain. The native engine is handle-based
 * (`enginePtr` on every call), so a second instance is a first-class thing
 * rather than a hack, and [seedFrom] copies the live settings across so the
 * copy sounds identical to what was already playing.
 *
 * A copy is short-lived: created when a blend starts, [release]d when it ends,
 * which destroys its native engine. Nothing else in the app should hold one.
 */
@UnstableApi
class DspChain private constructor(
    val channelDetector: ChannelDetectorProcessor,
    val downmix: DownmixProcessor,
    val mixBus: MixBusProcessor,
    val autoEq: AutoEqProcessor,
    val parametricEq: ParametricEqProcessor,
    // Held here because MixBusProcessor keeps them private, and this chain is
    // the thing that made them — no need to widen that API to seed a copy.
    val inflator: InflatorEffect,
    val compressor: CompressorEffect,
    val crossfeed: CrossfeedEffect,
) {
    /**
     * In the same order as the main player's chain, minus the taps. The Atmos
     * tap, the spectrum analyser and the ProjectM tap are deliberately absent:
     * they feed the visualiser and the EQ editor, which follow the *current*
     * track, and a blend's outgoing tail must not push frames into them.
     */
    val processors: Array<AudioProcessor>
        get() = arrayOf(channelDetector, downmix, mixBus, autoEq, parametricEq)

    /**
     * Copies the live chain's settings onto this one.
     *
     * The DSP engine's state is JSON the native side round-trips for exactly
     * this purpose. It can only be loaded once this copy's engine exists, and
     * that doesn't happen until ExoPlayer configures the processor with a
     * format — so the JSON is held here and applied by [applyPendingDspState]
     * when [MixBusProcessor.engineReady] fires.
     */
    fun seedFrom(
        dspStateJson: String?,
        autoEqBandsL: List<tf.monochrome.android.domain.model.EqBand>,
        autoEqBandsR: List<tf.monochrome.android.domain.model.EqBand>,
        autoEqPreamp: Float,
        autoEqEnabled: Boolean,
        parametricBands: List<tf.monochrome.android.domain.model.EqBand>,
        parametricPreamp: Float,
        parametricEnabled: Boolean,
        inflatorState: tf.monochrome.android.audio.dsp.oxford.InflatorState,
        compressorState: tf.monochrome.android.audio.dsp.oxford.CompressorState,
        crossfeedState: tf.monochrome.android.audio.dsp.crossfeed.CrossfeedState,
    ) {
        pendingDspState = dspStateJson?.takeIf { it.isNotBlank() && it != "{}" }
        autoEq.applyBands(autoEqBandsL, autoEqBandsR, autoEqPreamp, autoEqEnabled)
        parametricEq.applyBands(parametricBands, parametricPreamp, parametricEnabled)

        // The Oxford chain hangs off MixBusProcessor rather than the native
        // engine, so it isn't covered by the state JSON above. Without this the
        // outgoing tail of every blend played with the Inflator, Compressor and
        // Crossfeed at their defaults — the user's whole post-chain dropping
        // out for the length of the transition, oversampling included. Each
        // update{} pushes the full state natively in one call.
        inflator.update { inflatorState }
        compressor.update { compressorState }
        crossfeed.update { crossfeedState }
    }

    private var pendingDspState: String? = null
    private var engineReadyJob: kotlinx.coroutines.Job? = null

    /**
     * Watches for this copy's native engine coming up so the seeded DSP state
     * can be pushed into it. The engine isn't created until ExoPlayer configures
     * the processor with a format, which is after the blend has already begun.
     */
    fun observeEngine(scope: kotlinx.coroutines.CoroutineScope) {
        engineReadyJob?.cancel()
        engineReadyJob = scope.launch {
            mixBus.engineReady.collect { ready -> if (ready) applyPendingDspState() }
        }
    }

    /**
     * Pushes the seeded DSP state into the native engine. Call once the engine
     * exists; a no-op otherwise, and a no-op after it has been applied.
     */
    fun applyPendingDspState() {
        val json = pendingDspState ?: return
        val ptr = mixBus.getEnginePtr()
        if (ptr == 0L) return
        runCatching { mixBus.nativeLoadStateJson(ptr, json) }
        pendingDspState = null
    }

    /**
     * Tears the copy down, destroying its native engine. Idempotent, and safe
     * to call even if the chain was never configured.
     */
    fun release() {
        engineReadyJob?.cancel()
        engineReadyJob = null
        processors.forEach { runCatching { it.reset() } }
        // These hold native handles of their own, outside the mix engine.
        runCatching { inflator.release() }
        runCatching { compressor.release() }
        pendingDspState = null
    }

    companion object {
        /**
         * Builds a fresh chain. Every part is constructed directly rather than
         * injected: Hilt hands out the singletons the main player uses, and the
         * entire point here is to *not* get those.
         */
        fun createCopy(): DspChain {
            val inflator = InflatorEffect()
            val compressor = CompressorEffect()
            val crossfeed = CrossfeedEffect()
            return DspChain(
                channelDetector = ChannelDetectorProcessor(),
                downmix = DownmixProcessor(),
                mixBus = MixBusProcessor(inflator, compressor, crossfeed),
                autoEq = AutoEqProcessor(),
                parametricEq = ParametricEqProcessor(),
                inflator = inflator,
                compressor = compressor,
                crossfeed = crossfeed,
            )
        }
    }
}
