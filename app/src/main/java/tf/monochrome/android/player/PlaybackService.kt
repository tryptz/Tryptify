package tf.monochrome.android.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.scrobbling.ScrobblingService
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ReplayGainValues
import tf.monochrome.android.ui.main.MainActivity
import tf.monochrome.android.visualizer.ProjectMAudioTapProcessor
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import tf.monochrome.android.widget.NowPlayingWidget
import androidx.glance.appwidget.updateAll
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var streamResolver: StreamResolver
    @Inject lateinit var replayGainProcessor: ReplayGainProcessor
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var scrobblingService: ScrobblingService
    @Inject lateinit var projectMEngineRepository: ProjectMEngineRepository
    @Inject lateinit var channelDetectorProcessor: tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
    @Inject lateinit var downmixProcessor: tf.monochrome.android.audio.dsp.DownmixProcessor
    @Inject lateinit var mixBusProcessor: MixBusProcessor
    @Inject lateinit var dspManager: DspEngineManager
    @Inject lateinit var autoEqProcessor: AutoEqProcessor
    @Inject lateinit var parametricEqProcessor: ParametricEqProcessor
    @Inject lateinit var spectrumAnalyzerTap: SpectrumAnalyzerTap
    @Inject lateinit var unifiedTrackRegistry: UnifiedTrackRegistry
    @Inject lateinit var usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter
    @Inject lateinit var stereoInputController: tf.monochrome.android.audio.input.StereoInputController
    @Inject lateinit var libusbDriver: tf.monochrome.android.audio.usb.LibusbUacDriver
    @Inject lateinit var bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController
    // Atmos: the sample tap preserves raw E-AC-3 frames (which carry the JOC/OAMD
    // the FFmpeg decoder discards) into atmosFrameBuffer; atmosAudioProcessor
    // pairs each with the decoded bed PCM and renders objects to binaural stereo.
    @Inject lateinit var atmosAudioProcessor: tf.monochrome.android.audio.atmos.AtmosAudioProcessor
    @Inject lateinit var atmosFrameBuffer: tf.monochrome.android.audio.atmos.AtmosFrameBuffer

    /** Shared Atmos tap — used both as the player's factory and to wrap the
     *  directly-built DASH/progressive sources. Built via an annotated helper:
     *  an @OptIn on the property itself does not reach the lazy {} lambda as
     *  far as lint's UnsafeOptInUsageError detector is concerned. */
    private val atmosTapFactory by lazy { buildAtmosTapFactory() }

    @OptIn(UnstableApi::class)
    private fun buildAtmosTapFactory() =
        tf.monochrome.android.audio.atmos.AtmosTapMediaSourceFactory(
            DefaultMediaSourceFactory(this), atmosFrameBuffer)

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentReplayGain: ReplayGainValues? = null

    // Last track the ProjectM preset was advanced for — so a real track change
    // (skip, previous, pick a new song) rolls the visualizer to a fresh preset
    // immediately instead of waiting on the rotation timer.
    private var lastPresetTrackId: String? = null

    private fun createSessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Tuned for hi-fi streaming: buffer 30 s minimum and 120 s cap so a
        // brief cell-signal dip mid-track doesn't rebuffer, and the 48 kHz
        // Opus / FLAC / ALAC tail has room without starving the audio
        // thread. Playback starts after 2.5 s of buffered audio (down from
        // the 5 s default) so tapping play feels immediate on a warm cache;
        // rebuffer after an underrun waits for 5 s so we don't churn. See
        // androidx.media3.exoplayer.DefaultLoadControl defaults — this
        // widens the ceiling by 2-3× to absorb hi-bitrate streams.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30_000,
                /* maxBufferMs */ 120_000,
                /* bufferForPlaybackMs */ 2_500,
                /* bufferForPlaybackAfterRebufferMs */ 5_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        player = ExoPlayer.Builder(this, buildRenderersFactory())
            // Tap raw E-AC-3 access units on their way to the FFmpeg decoder so
            // the Atmos JOC/OAMD side-data survives for atmosAudioProcessor.
            // Covers the setMediaItem paths (local files included); the direct
            // setMediaSource paths below build their own sources and are not
            // tapped yet.
            .setMediaSourceFactory(atmosTapFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Spins up the next media item's decoder + fills 10 s of its
                // buffer before the current track ends. Paired with the DSP
                // engine's live reconfigure path (no destroy/create), this
                // is what makes cross-sample-rate transitions silent.
                setPreloadConfiguration(
                    ExoPlayer.PreloadConfiguration(
                        /* targetPreloadDurationUs */ 10_000_000L,
                    )
                )
            }

        player.addListener(object : Player.Listener {
            // Keep the home-screen now-playing widget live: the widget uses
            // updatePeriodMillis=0 (no polling), so it only refreshes when the
            // service pushes an update on a real playback change.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshNowPlayingWidget()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                refreshNowPlayingWidget()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        val currentTrack = queueManager.currentTrack.value
                        if (currentTrack != null) {
                            serviceScope.launch {
                                scrobblingService.scrobbleTrack(currentTrack)
                            }
                        }
                        onTrackEnded()
                    }
                    Player.STATE_READY -> {
                        applyReplayGain()
                        applyPlaybackSpeed()
                        applyEq()
                        applyParametricEq()
                    }
                    Player.STATE_BUFFERING, Player.STATE_IDLE -> {
                        // No action needed
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    val trackId = mediaItem.mediaId.toLongOrNull()
                    if (trackId != null) {
                        val track = queueManager.currentQueue.find { it.id == trackId }
                        if (track != null) {
                            serviceScope.launch {
                                val unified = unifiedTrackRegistry[trackId]
                                libraryRepository.addToHistory(track, unified)
                                scrobblingService.updateNowPlaying(track)
                            }
                        }
                    }
                }

                // Whenever the playing track actually changes and auto-shuffle is
                // on, jump the ProjectM visualizer to a new preset right away — so
                // skipping/selecting a song rolls the preset instead of leaving the
                // old one up until the rotation timer fires. (No-op when the engine
                // isn't running, e.g. the visualizer view is closed.) Fires for any
                // transition reason so replacing the queue with a new song counts.
                val newTrackId = mediaItem?.mediaId
                if (newTrackId != null && newTrackId != lastPresetTrackId) {
                    lastPresetTrackId = newTrackId
                    if (projectMEngineRepository.autoShuffle.value) {
                        projectMEngineRepository.nextPreset()
                    }
                }
            }
        })

        // Bit-perfect USB DAC routing — when the user has the toggle on
        // and a USB Audio Class device is attached, pin ExoPlayer's
        // output to it via setPreferredAudioDevice. Reverts to system
        // default whenever the toggle goes off or the DAC is unplugged.
        serviceScope.launch {
            preferences.usbBitPerfectEnabled
                .combine(usbAudioRouter.usbOutputDevice) { enabled, device ->
                    if (enabled) device else null
                }
                .collect { preferred ->
                    runCatching { player.setPreferredAudioDevice(preferred) }
                }
        }

        // Live-mode line-in. The DSP chain lives inside DefaultAudioSink and
        // only runs while the renderer is pumping, so Live mode needs
        // *something* playing — StereoInputController asks for a silent carrier
        // and the captured audio joins further down, inside MixBusProcessor.
        serviceScope.launch {
            stereoInputController.carrierRequest.collect { deviceLabel ->
                if (deviceLabel != null) startLineInCarrier(deviceLabel) else stopLineInCarrier()
            }
        }

        // Wrap the ExoPlayer so Media3's notification + lock-screen surface
        // working next / previous controls. The wrapper routes those commands
        // through our QueueManager-backed skipToNext / skipToPrevious because
        // we resolve stream URLs one track at a time and ExoPlayer's own
        // playlist is never the source of truth for queue position.
        val forwardingPlayer = QueueForwardingPlayer(
            delegate = player,
            queueManager = queueManager,
            onNext = ::skipToNext,
            onPrev = ::skipToPrevious,
        )
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(createSessionActivity())
            .setCallback(PlaybackResumptionCallback())
            .build()

        // Seamlessly apply playback speed when settings change
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.playbackSpeed,
                preferences.preservePitch
            ) { speed, preservePitch ->
                Pair(speed, preservePitch)
            }.collect { (speed, preservePitch) ->
                player.playbackParameters = PlaybackParameters(speed, if (preservePitch) 1.0f else speed)
            }
        }

        // Multichannel handling: fold 5.1/7.1 down to stereo (default) or,
        // when the user turns the toggle off, pass multichannel PCM through
        // to AudioTrack untouched (the stereo-only processors deactivate
        // themselves for >2 ch). Takes effect on the next pipeline
        // reconfigure (track change / seek), like the other DSP toggles.
        serviceScope.launch {
            preferences.multichannelDownmixEnabled.collect { enabled ->
                downmixProcessor.setEnabled(enabled)
            }
        }
        // Preamp + LFE path follow the Atmos page's Downmix settings, so
        // plain multichannel PCM folds the same way the Atmos fallback does.
        serviceScope.launch {
            preferences.rendererProfile.collect { profile ->
                downmixProcessor.setPreampDb(profile.downmixPreampDb)
                downmixProcessor.setLfeLowpass(profile.lfeLowpass)
            }
        }

        // Listen to EQ + tone changes and apply them. Tone shelves are folded into
        // the in-app AutoEQ processor whenever the system-wide effect isn't the one
        // handling this app's audio, so tone works independent of the system-wide
        // toggle (and without double-processing when it IS on).
        serviceScope.launch {
            // Seven sources exceeds combine's typed overloads (max 5), so this
            // uses the vararg form and casts each slot back out by position.
            kotlinx.coroutines.flow.combine(
                preferences.eqEnabled,
                preferences.eqBandsJson,
                preferences.eqBandsRJson,
                preferences.eqStereoMode,
                preferences.eqPreamp,
                preferences.systemToneControls,
                preferences.systemWideAutoEqEnabled,
            ) { v ->
                EqApply(
                    enabled = v[0] as Boolean,
                    bandsJson = v[1] as String?,
                    bandsRJson = v[2] as String?,
                    stereo = v[3] as Boolean,
                    preamp = v[4] as Double,
                    tone = v[5] as tf.monochrome.android.domain.model.ToneControls,
                    systemWide = v[6] as Boolean,
                )
            }.collect { applyEqSettings(it) }
        }

        // Listen to Parametric EQ changes and apply them
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.paramEqEnabled,
                preferences.paramEqBandsJson,
                preferences.paramEqPreamp
            ) { enabled, bandsJson, preamp ->
                Triple(enabled, bandsJson, preamp)
            }.collect { (enabled, bandsJson, preamp) ->
                applyParametricEqSettings(enabled, bandsJson, preamp)
            }
        }

        // Restore DSP mixer state when the native engine becomes ready
        serviceScope.launch {
            var hasRestored = false
            mixBusProcessor.engineReady.collect { ready ->
                if (ready && !hasRestored) {
                    dspManager.restoreState()
                    hasRestored = true
                } else if (ready && hasRestored) {
                    // Re-apply saved state on engine recreation (format change)
                    val stateJson = preferences.dspStateJson.first()
                    if (!stateJson.isNullOrEmpty()) dspManager.loadStateJson(stateJson)
                    dspManager.setEnabled(dspManager.enabled.value)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val audioBus = projectMEngineRepository.audioBus
        // NextRenderersFactory extends DefaultRenderersFactory and appends
        // FFmpeg-based renderers (prebuilt libavcodec + libavformat) after
        // the platform ones. We subclass it so our AudioSink override (with
        // the custom AudioProcessor chain — DSP, EQ, spectrum tap, ProjectM
        // tee) still applies while the FFmpeg renderer handles any format
        // MediaCodec can't (DSD, APE, TAK, WavPack, MPC, TrueHD, DTS, …).
        return object : io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(this@PlaybackService) {
            init {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)

                // Hand ALAC to FFmpeg instead of the platform decoder.
                //
                // EXTENSION_RENDERER_MODE_ON puts the FFmpeg renderers *after*
                // the MediaCodec ones, so any format the platform advertises
                // wins. This device advertises c2.qti.alac.{sw,hw}.decoder, so
                // Apple downloads went there and came out silent, while Atmos
                // (E-AC-3) played fine precisely because it has no platform
                // decoder here and fell through to FFmpeg.
                //
                // Returning no decoders for audio/alac makes
                // MediaCodecAudioRenderer report the format unsupported, so
                // ExoPlayer moves on to FfmpegAudioRenderer — the same decoder
                // already carrying the Atmos path. The bundled libavcodec does
                // include ALAC, so nothing is lost by skipping the vendor one.
                setMediaCodecSelector { mimeType, secure, tunneling ->
                    if (MimeTypes.AUDIO_ALAC.equals(mimeType, ignoreCase = true)) {
                        emptyList()
                    } else {
                        androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mimeType, secure, tunneling)
                    }
                }
            }

            // Wrap the platform-default MediaCodecAdapter.Factory in
            // ImportanceMediaCodecAdapterFactory so every codec we configure
            // (AAC, Opus, ALAC, Vorbis, FLAC, …) gets KEY_IMPORTANCE = 0 set
            // in its MediaFormat. That marks our codecs as the last to be
            // reclaimed by Android's IResourceManagerService — without it,
            // mid-track and during cross-format transitions logcat shows
            // `E MediaCodec: Released by resource manager` followed by
            // audio dropouts.
            //
            // Cached so successive calls return the same wrapper instance
            // (DefaultRenderersFactory calls getCodecAdapterFactory() per
            // renderer construction).
            private var cachedImportanceFactory:
                androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory? = null

            override fun getCodecAdapterFactory():
                androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                cachedImportanceFactory?.let { return it }
                val wrapped = ImportanceMediaCodecAdapterFactory(super.getCodecAdapterFactory())
                cachedImportanceFactory = wrapped
                return wrapped
            }

            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return try {
                    val defaultSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(
                            arrayOf(
                                channelDetectorProcessor, // Passive tap: reports source channel count/layout + per-channel activity
                                atmosAudioProcessor,    // Atmos: multichannel bed → object render → binaural stereo; inactive for ≤2ch
                                downmixProcessor,       // Multichannel→stereo fold-down; inactive (NOT_SET) for mono/stereo
                                mixBusProcessor,        // DSP engine (mixer/effects)
                                autoEqProcessor,        // AutoEQ (independent, always-on when enabled)
                                parametricEqProcessor,  // Parametric EQ (after AutoEQ, stacks on top)
                                spectrumAnalyzerTap,    // Passive FFT tap for the Parametric EQ editor visualizer
                                TeeAudioProcessor(
                                    ProjectMAudioTapProcessor(audioBus)
                                )
                            )
                        )
                        .build()
                    // Wrap with LibusbAudioSink: a no-op when the user
                    // hasn't enabled exclusive mode (forwards everything
                    // to defaultSink). When the toggle is on AND
                    // UsbExclusiveController has a libusb device handle
                    // open, configure() spins up the iso pump and
                    // handleBuffer() routes PCM to the DAC directly,
                    // bypassing AudioTrack + the HAL.
                    //
                    // Processors are passed in so the libusb path runs
                    // the SAME DSP / EQ / spectrum / ProjectM-tap chain
                    // DefaultAudioSink would. The processors are
                    // singletons but only one of the two paths
                    // configures + drains them at a time (bypassActive
                    // gates inside LibusbAudioSink), so there's no
                    // contention.
                    tf.monochrome.android.audio.usb.LibusbAudioSink(
                        delegate = defaultSink,
                        driver = libusbDriver,
                        volumeController = bypassVolumeController,
                        processors = listOf(
                            channelDetectorProcessor,
                            atmosAudioProcessor,
                            downmixProcessor,
                            mixBusProcessor,
                            autoEqProcessor,
                            parametricEqProcessor,
                            spectrumAnalyzerTap,
                            // ProjectM tap intentionally omitted from
                            // the bypass chain — the inline pump runs
                            // on the renderer thread and the visualizer
                            // bus sometimes blocks on its consumer.
                            // Spectrum tap is light-weight and fine.
                        ),
                    )
                } catch (error: Exception) {
                    projectMEngineRepository.reportAudioTapFailure(
                        "projectM audio tap unavailable: ${error.message ?: "unknown error"}"
                    )
                    val fallback = checkNotNull(
                        super.buildAudioSink(
                            context,
                            enableFloatOutput,
                            enableAudioTrackPlaybackParams
                        )
                    )
                    tf.monochrome.android.audio.usb.LibusbAudioSink(
                        delegate = fallback,
                        driver = libusbDriver,
                        volumeController = bypassVolumeController,
                    )
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    fun playTrack(track: tf.monochrome.android.domain.model.Track) {
        serviceScope.launch {
            try {
                val (mediaItem, trackStream) = streamResolver.resolveMediaItem(track)
                currentReplayGain = trackStream?.replayGain

                if (mediaItem == null) {
                    // Stream URL couldn't be resolved (offline / API error /
                    // blank URL). Skipping is preferable to feeding ExoPlayer
                    // a MediaItem with no localConfiguration — that path NPEs
                    // inside DefaultMediaSourceFactory.
                    onTrackEnded()
                    return@launch
                }

                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()

                libraryRepository.addToHistory(track)
            } catch (e: Exception) {
                // Skip to next on error
                onTrackEnded()
            }
        }
    }

    /**
     * Resolve the queue's current track and play it.
     *
     * [startPositionMs] exists for the line-in carrier: leaving Live mode has
     * to put the interrupted track back where it was, and the position has to
     * be applied at setMediaItem time — resolution is asynchronous, so a
     * seekTo() from the caller would land before the item does.
     */
    @OptIn(UnstableApi::class)
    fun playQueue(startPositionMs: Long = 0L) {
        val currentTrack = queueManager.currentTrack.value ?: return
        serviceScope.launch {
            try {
                // Unified tracks (local files, encrypted collections) go through a
                // different resolver — they aren't HiFi API streams and the legacy
                // resolver can't handle them. Consult the shared registry first so
                // notification / lock-screen next / previous taps route correctly
                // for mixed queues.
                val unifiedTrack = unifiedTrackRegistry[currentTrack.id]
                if (unifiedTrack != null) {
                    val resolved = streamResolver.resolveUnifiedTrack(unifiedTrack)
                    currentReplayGain = resolved.trackStream?.replayGain
                    if (!resolved.isPlayable) {
                        onTrackEnded()
                        return@launch
                    }
                    player.setMediaItem(resolved.mediaItem, startPositionMs)
                    player.prepare()
                    player.play()
                    libraryRepository.addToHistory(currentTrack, unifiedTrack)
                    return@launch
                }

                val (mediaItem, trackStream) = streamResolver.resolveMediaItem(currentTrack)
                currentReplayGain = trackStream?.replayGain

                if (mediaItem == null) {
                    onTrackEnded()
                    return@launch
                }

                val streamUrl = trackStream?.streamUrl
                if (streamUrl != null && streamUrl.isNotBlank()) {
                    val dataSourceFactory = DefaultDataSource.Factory(this@PlaybackService)

                    val source = if (trackStream.isDash) {
                        // Create DASH source from MPD XML string
                        val mpdUri = ("data:application/dash+xml;base64," +
                            android.util.Base64.encodeToString(streamUrl.toByteArray(), android.util.Base64.NO_WRAP)).toUri()
                        DashMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(mpdUri))
                    } else {
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(mediaItem)
                    }

                    // These sources are built directly and so bypass the player's
                    // MediaSource.Factory — wrap them with the Atmos tap too, or
                    // streamed E-AC-3 would lose its JOC/OAMD side-data.
                    player.setMediaSource(atmosTapFactory.wrap(source), startPositionMs)
                } else {
                    player.setMediaItem(mediaItem, startPositionMs)
                }

                player.prepare()
                player.play()

                libraryRepository.addToHistory(currentTrack)

                // Preload next tracks
                preloadNextTracks()
            } catch (e: Exception) {
                onTrackEnded()
            }
        }
    }

    // ── Line-in carrier (Live mode) ─────────────────────────────────────

    /** Used only before the pipeline has ever configured — no track played yet. */
    private val CARRIER_FALLBACK_RATE = 48000

    /** True while the player is rendering the silent line-in carrier. */
    private var lineInCarrierActive = false
    private var carrierResumePositionMs = 0L
    private var carrierResumeWasPlaying = false

    /**
     * Take the player over with an endless silent stream so the DSP chain keeps
     * running with no track loaded. Whatever was playing is remembered and put
     * back by [stopLineInCarrier].
     */
    @OptIn(UnstableApi::class)
    private fun startLineInCarrier(deviceLabel: String) {
        if (lineInCarrierActive) return
        carrierResumeWasPlaying = player.isPlaying
        carrierResumePositionMs = player.currentPosition.coerceAtLeast(0L)
        lineInCarrierActive = true

        // Rate matters: the carrier's format is what MixBusProcessor configures
        // to, and capture is then opened at that same rate.
        val rate = mixBusProcessor.currentSampleRate().takeIf { it > 0 } ?: CARRIER_FALLBACK_RATE
        val source = ProgressiveMediaSource.Factory(
            tf.monochrome.android.audio.input.LiveInputDataSource.Factory(rate),
            tf.monochrome.android.audio.input.LiveInputDataSource.extractorsFactory(),
        ).createMediaSource(
            tf.monochrome.android.audio.input.LiveInputDataSource.mediaItem(deviceLabel)
        )
        // Wrapped like the other hand-built sources so the Atmos tap stays in
        // the graph; it is inert on 16-bit PCM but the pipeline expects it.
        player.setMediaSource(atmosTapFactory.wrap(source))
        player.prepare()
        player.play()
    }

    /**
     * Hand the player back. The queue was never touched, so the interrupted
     * track is re-resolved through the normal path and restored to its old
     * position — playing again only if it was playing when line-in took over.
     */
    private fun stopLineInCarrier() {
        if (!lineInCarrierActive) return
        lineInCarrierActive = false
        player.stop()
        player.clearMediaItems()
        if (carrierResumeWasPlaying && queueManager.currentTrack.value != null) {
            playQueue(carrierResumePositionMs)
        }
        carrierResumePositionMs = 0L
        carrierResumeWasPlaying = false
    }

    fun skipToNext() {
        // Next / previous have no meaning against a live input, and acting on
        // them would silently swap the carrier out for a track. Notification
        // and lock-screen taps both land here.
        if (lineInCarrierActive) return
        val nextTrack = queueManager.next()
        if (nextTrack != null) {
            playQueue()
        } else {
            player.stop()
        }
    }

    fun skipToPrevious() {
        if (lineInCarrierActive) return
        // If more than 3 seconds in, restart current track
        if (player.currentPosition > 3000) {
            player.seekTo(0)
            return
        }
        val prevTrack = queueManager.previous()
        if (prevTrack != null) {
            playQueue()
        }
    }

    fun seekTo(positionMs: Long) {
        // The carrier is silence with a nominal 3-hour length; seeking inside
        // it does nothing useful and only desyncs the progress bar.
        if (lineInCarrierActive) return
        player.seekTo(positionMs)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    private fun onTrackEnded() {
        val nextTrack = queueManager.next()
        if (nextTrack != null) {
            playQueue()
        }
    }

    fun setPlaybackSpeed(speed: Float, preservePitch: Boolean) {
        player.playbackParameters = PlaybackParameters(speed, if (preservePitch) 1.0f else speed)
    }

    private fun applyPlaybackSpeed() {
        serviceScope.launch {
            val speed = preferences.playbackSpeed.first()
            val preservePitch = preferences.preservePitch.first()
            player.playbackParameters = PlaybackParameters(speed, if (preservePitch) 1.0f else speed)
        }
    }

    private fun applyReplayGain() {
        serviceScope.launch {
            val volume = preferences.volume.first().toFloat()
            val adjustedVolume = replayGainProcessor.calculateVolume(volume, currentReplayGain)
            player.volume = adjustedVolume
            // Mirror to the libusb bypass path. Player.volume runs
            // inside DefaultAudioSink which we skip when bypass is
            // hot, so without this line the slider + ReplayGain
            // attenuation only applies on the AudioFlinger fallback.
            bypassVolumeController.setVolume(adjustedVolume)
        }
    }

    private suspend fun preloadNextTracks() {
        // Preload metadata for next 2 tracks to reduce latency
        val queue = queueManager.currentQueue
        val currentIdx = queueManager.currentQueueIndex
        for (i in 1..2) {
            val nextIdx = currentIdx + i
            if (nextIdx < queue.size) {
                try {
                    streamResolver.resolveMediaItem(queue[nextIdx])
                } catch (_: Exception) {
                    // Preload failure is non-critical
                }
            }
        }
    }

    private data class EqApply(
        val enabled: Boolean,
        val bandsJson: String?,
        val bandsRJson: String?,
        val stereo: Boolean,
        val preamp: Double,
        val tone: tf.monochrome.android.domain.model.ToneControls,
        val systemWide: Boolean,
    )

    /**
     * Push a fresh render to every now-playing widget instance. [serviceScope] runs
     * on the main dispatcher, so the suspend updateAll is safe to launch here; the
     * whole thing is wrapped so a widget/Glance hiccup can never crash playback.
     */
    private fun refreshNowPlayingWidget() {
        serviceScope.launch {
            runCatching { NowPlayingWidget().updateAll(this@PlaybackService) }
        }
    }

    /**
     * Apply current EQ settings from preferences
     */
    private fun applyEq() {
        serviceScope.launch {
            try {
                applyEqSettings(
                    EqApply(
                        enabled = preferences.eqEnabled.first(),
                        bandsJson = preferences.eqBandsJson.first(),
                        bandsRJson = preferences.eqBandsRJson.first(),
                        stereo = preferences.eqStereoMode.first(),
                        preamp = preferences.eqPreamp.first(),
                        tone = preferences.systemToneControls.first(),
                        systemWide = preferences.systemWideAutoEqEnabled.first(),
                    ),
                )
            } catch (e: Exception) {
                // EQ application non-critical
            }
        }
    }

    /**
     * Apply EQ + tone settings to the standalone AutoEQ processor (independent of
     * mixer DSP). When system-wide is ON, the global output-mix effect already
     * corrects THIS app's audio too, so the in-app AutoEQ and tone are fully
     * bypassed here to avoid a double correction. When it's OFF, the AutoEQ (when
     * enabled) and the bass/treble tone shelves are applied in-app — so tone works
     * whether or not system-wide is on, and neither is ever applied twice.
     */
    private fun applyEqSettings(cfg: EqApply) {
        try {
            if (cfg.systemWide) {
                autoEqProcessor.applyBands(emptyList(), 0f, false)
                return
            }
            val json = Json { ignoreUnknownKeys = true }
            fun decode(bandsJson: String?): List<EqBand> =
                if (cfg.enabled && !bandsJson.isNullOrEmpty()) {
                    json.decodeFromString(bandsJson)
                } else {
                    emptyList()
                }
            val autoL = decode(cfg.bandsJson)
            // 2-channel mode gives the right ear its own curve; with the switch
            // off (or no R curve saved yet) the left list drives both ears.
            val autoR = if (cfg.stereo) decode(cfg.bandsRJson).ifEmpty { autoL } else autoL
            // Tone shelves are ear-agnostic: appended to both channels so
            // bass/treble stay centred regardless of the calibration split.
            val toneBands = cfg.tone.toBands()
            val bandsL = autoL + toneBands
            val bandsR = autoR + toneBands
            val preamp = if (cfg.enabled) cfg.preamp else 0.0
            val active = bandsL.any { it.enabled } || bandsR.any { it.enabled }
            autoEqProcessor.applyBands(bandsL, bandsR, preamp.toFloat(), active)
        } catch (e: Exception) {
            // Gracefully handle EQ application errors
        }
    }

    /**
     * Apply current Parametric EQ settings from preferences
     */
    private fun applyParametricEq() {
        serviceScope.launch {
            try {
                val enabled = preferences.paramEqEnabled.first()
                val bandsJson = preferences.paramEqBandsJson.first()
                val preamp = preferences.paramEqPreamp.first()
                applyParametricEqSettings(enabled, bandsJson, preamp)
            } catch (_: Exception) { }
        }
    }

    /**
     * Apply Parametric EQ settings to the standalone ParametricEqProcessor
     */
    private fun applyParametricEqSettings(enabled: Boolean, bandsJson: String?, preamp: Double) {
        try {
            val bands = if (!bandsJson.isNullOrEmpty()) {
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<List<EqBand>>(bandsJson)
            } else {
                emptyList()
            }
            parametricEqProcessor.applyBands(bands, preamp.toFloat(), enabled)
        } catch (_: Exception) { }
    }

    /**
     * Restores the last-known queue when the user taps play on a detached
     * notification / lock-screen button / BT remote after the MediaSession
     * has been idle. Without this, Media3 falls back to the default
     * `MediaSession.Callback.onPlaybackResumption` which throws
     * `UnsupportedOperationException` (visible in logcat as a big stack
     * trace) and the play tap silently does nothing.
     *
     * Returns the current QueueManager contents rebuilt into MediaItems.
     * The returned items carry only the track id as mediaId — they aren't
     * directly playable; when Media3 calls `player.prepare()` our
     * onMediaItemTransition listener and the existing `playQueue()` path
     * take over to resolve the actual stream URL.
     */
    @OptIn(UnstableApi::class)
    private inner class PlaybackResumptionCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val snapshot = queueManager.currentQueue
            val index = queueManager.currentQueueIndex.coerceAtLeast(0)

            // Empty queue on first launch → hand back an empty resumption;
            // Media3 treats that as "nothing to resume" and the user lands
            // on Home instead of the play tap being silently eaten.
            if (snapshot.isEmpty()) {
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                )
            }

            // Media3 1.5.1 hands the returned items straight to
            // PlayerWrapper.setMediaItems → DefaultMediaSourceFactory, which
            // NPEs on any item without a localConfiguration. Returning bare
            // mediaId stubs (as we used to) crashed the session on every
            // BT-remote / lock-screen play tap. Resolve the queue's URIs on
            // serviceScope and complete the future when ready.
            val future = com.google.common.util.concurrent.SettableFuture
                .create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val resolvedItems = snapshot.mapNotNull { track ->
                    val unified = unifiedTrackRegistry[track.id]
                    if (unified != null) {
                        val r = runCatching { streamResolver.resolveUnifiedTrack(unified) }.getOrNull()
                        if (r?.isPlayable == true) r.mediaItem else null
                    } else {
                        val (mediaItem, _) = runCatching { streamResolver.resolveMediaItem(track) }
                            .getOrDefault(Pair(null, null))
                        mediaItem
                    }
                }
                if (resolvedItems.isEmpty()) {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    return@launch
                }
                val safeIndex = index.coerceAtMost(resolvedItems.lastIndex)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        resolvedItems,
                        safeIndex,
                        /* startPositionMs = */ 0L,
                    )
                )
            }
            return future
        }

        /**
         * Echo controller-provided MediaItems back unchanged. PlayerViewModel
         * already resolves URIs (and other LocalConfiguration) before calling
         * `MediaController.setMediaItem(...)`, so the items the session
         * receives are play-ready. Without this override, the default impl
         * throws `UnsupportedOperationException` on every play tap (visible
         * as a long MediaSessionStub stack trace in logcat) and the
         * downstream PlayerWrapper.setMediaItems then NPEs in
         * DefaultMediaSourceFactory because it gets fed empty items.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
            // External controllers (Android Auto, Bluetooth headsets, the
            // Glance widget) routinely send bare MediaItems carrying only a
            // mediaId — no URI, no localConfiguration. Forwarding those to
            // PlayerWrapper.setMediaItems makes DefaultMediaSourceFactory NPE
            // at line 457 (visible in logcat as MediaSessionStub: Session
            // operation failed). Drop them here.
            val playable = mediaItems.filterTo(mutableListOf()) { item ->
                item.localConfiguration?.uri?.toString()?.isNotBlank() == true
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(playable)
        }
    }
}
