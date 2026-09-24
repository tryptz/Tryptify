package tf.monochrome.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import tf.monochrome.android.audio.UsbAudioRouter
import tf.monochrome.android.audio.atmos.AtmosAudioProcessor
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.pipeline.AudioPipelineInputs
import tf.monochrome.android.audio.pipeline.AtmosStage
import tf.monochrome.android.audio.pipeline.AudioPipelineMonitor
import tf.monochrome.android.audio.pipeline.DecodedStream
import tf.monochrome.android.audio.pipeline.ChainInput
import tf.monochrome.android.audio.pipeline.OutputDeviceProbe
import tf.monochrome.android.audio.pipeline.OutputPath
import tf.monochrome.android.audio.pipeline.UsbStream
import tf.monochrome.android.audio.resample.VariRateAudioProcessor
import tf.monochrome.android.audio.usb.UsbExclusiveController
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.EqRepository
import javax.inject.Inject

/**
 * Gathers what the Audio Pipeline panel shows, from the seven places it lives.
 *
 * The panel itself renders an [AudioPipelineInputs]; this is the plumbing that
 * fills one in. Nothing here decides what to *say* — that is
 * `buildAudioPipelineSnapshot`, which is pure and tested. This only collects.
 *
 * Everything is `WhileSubscribed`, so none of it runs while the panel is shut.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPipelineViewModel @Inject constructor(
    monitor: AudioPipelineMonitor,
    channelDetector: ChannelDetectorProcessor,
    private val variRate: VariRateAudioProcessor,
    private val spectrumTap: SpectrumAnalyzerTap,
    private val outputProbe: OutputDeviceProbe,
    private val atmosProcessor: AtmosAudioProcessor,
    usbRouter: UsbAudioRouter,
    usbExclusive: UsbExclusiveController,
    dspEngine: DspEngineManager,
    preferences: PreferencesManager,
    eqRepository: EqRepository,
) : ViewModel() {

    /**
     * The values that are plain fields rather than flows.
     *
     * The speed resampler's ratio, the analyser's FFT size and the HAL's
     * preferred rate are all read, not observed — none of them has a flow to
     * subscribe to. A slow tick is the honest way to show them: fast enough
     * that changing playback speed updates the panel while you watch, slow
     * enough to cost nothing. Only while the panel is open.
     */
    private val polled: Flow<PolledValues> = flow {
        while (true) {
            emit(
                PolledValues(
                    speedRatio = variRate.getRatio(),
                    fftSize = spectrumTap.fftSize,
                    halSampleRateHz = outputProbe.halSampleRateHz(),
                    outputChannels = spectrumTap.outputChannelCount.takeIf { it > 0 },
                )
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    private data class Live(
        val stream: DecodedStream?,
        val decoderName: String?,
        val chain: ChainInput?,
        val atmos: AtmosAudioProcessor.Outcome?,
    )

    private data class PolledValues(
        val speedRatio: Float,
        val fftSize: Int,
        val halSampleRateHz: Int?,
        val outputChannels: Int?,
    )

    /**
     * The Atmos row, for a multichannel source only. The processor reports
     * what it did once frames flow; before that, and when it is out of the
     * chain, the setting and the native library say why.
     */
    private fun atmosStage(sourceChannels: Int?, outcome: AtmosAudioProcessor.Outcome?): AtmosStage? {
        if (sourceChannels == null || sourceChannels <= 2) return null
        return when {
            outcome == AtmosAudioProcessor.Outcome.OBJECTS_BINAURAL -> AtmosStage.OBJECTS_BINAURAL
            outcome == AtmosAudioProcessor.Outcome.BED_FOLDED -> AtmosStage.BED_FOLDED
            atmosProcessor.isPassthrough -> AtmosStage.PASSTHROUGH
            !atmosProcessor.isRendererAvailable -> AtmosStage.UNAVAILABLE
            else -> null
        }
    }

    private val eqPresetName: Flow<String?> = preferences.eqActivePresetId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(null)
            else eqRepository.getPresetByIdFlow(id).let { presets ->
                flow { presets.collect { emit(it?.name) } }
            }
        }

    /**
     * The width of an inserted Stereo snapin, in dB, or null when none is in
     * the mixer.
     *
     * Null and 0 dB mean different things and the panel says so: null is "the
     * chain has no stereo stage at all", 0 dB is "it has one, doing nothing".
     * A bypassed instance counts as absent, because that is what it is.
     */
    private val stereoWidthDb: Flow<Float?> = combine(
        dspEngine.enabled,
        dspEngine.buses,
    ) { enabled, buses ->
        if (!enabled) return@combine null
        buses.asSequence()
            .flatMap { it.plugins.asSequence() }
            .firstOrNull { !it.bypassed && it.type == SnapinType.STEREO }
            ?.parameters?.get(STEREO_WIDTH_PARAM)
    }

    private val usbState: Flow<Pair<OutputPath, UsbStream?>> = combine(
        usbExclusive.status,
        usbExclusive.diagnostics,
        usbRouter.usbOutputDevice,
        preferences.usbBitPerfectEnabled,
    ) { status, diagnostics, usbDevice, framework ->
        val streaming = status == UsbExclusiveController.Status.Streaming
        val path = when {
            streaming -> OutputPath.USB_EXCLUSIVE
            framework && usbDevice != null -> OutputPath.USB_FRAMEWORK
            else -> OutputPath.AUDIO_TRACK
        }
        val stream = diagnostics?.takeIf { streaming }?.let {
            UsbStream(
                sampleRateHz = it.sampleRateHz,
                bitsPerSample = it.bitsPerSample,
                channels = it.channels,
                detail = buildString {
                    append(it.uacLabel())
                    append(" · ")
                    append(it.speedLabel())
                    if (it.hasFeedbackEndpoint) append(" · async feedback")
                },
            )
        }
        path to stream
    }

    private val chain: Flow<ChainInput?> = channelDetector.state.let { state ->
        flow {
            state.collect { s ->
                emit(
                    s?.let {
                        ChainInput(
                            sampleRate = it.sampleRate,
                            channelCount = it.channelCount,
                            layoutName = it.layoutName,
                            isFloat = it.isFloat,
                        )
                    }
                )
            }
        }
    }

    val inputs: StateFlow<AudioPipelineInputs> = combine(
        combine(monitor.stream, monitor.decoderName, chain, atmosProcessor.outcome) { stream, decoder, chainInput, atmos ->
            Live(stream, decoder, chainInput, atmos)
        },
        polled,
        combine(preferences.dspBlockSize, eqPresetName, stereoWidthDb) { block, eq, width ->
            Triple(block, eq, width)
        },
        usbState,
        outputProbe.routed,
    ) { live, poll, dsp, usb, routed ->
        val (path, usbStream) = usb
        AudioPipelineInputs(
            stream = live.stream,
            decoderName = live.decoderName,
            chain = live.chain,
            speedRatio = poll.speedRatio,
            dspBlockFrames = dsp.first,
            eqPresetName = dsp.second,
            stereoWidthDb = dsp.third,
            visualizerFftSize = poll.fftSize,
            outputPath = path,
            deviceName = routed?.name,
            outputKind = routed?.kind,
            halSampleRateHz = poll.halSampleRateHz,
            usb = usbStream,
            // Asked on every tick rather than observed: the spatializer's own
            // listener says nothing about which format it would take, and the
            // chain's format is part of the question — its *output* format,
            // from the tap after the Atmos and downmix stages.
            spatialAudio = outputProbe.spatialAudio(live.chain?.sampleRate, poll.outputChannels),
            atmos = atmosStage(live.chain?.channelCount, live.atmos),
            outputChannels = poll.outputChannels,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(POLL_INTERVAL_MS),
        AudioPipelineInputs(),
    )

    private companion object {
        /** The Stereo snapin's second parameter — see `getParamDefs`. */
        const val STEREO_WIDTH_PARAM = 1
        const val POLL_INTERVAL_MS = 1000L
    }
}
