package tf.monochrome.android.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    @Inject lateinit var mixBusProcessor: MixBusProcessor
    @Inject lateinit var dspManager: DspEngineManager
    @Inject lateinit var autoEqProcessor: AutoEqProcessor
    @Inject lateinit var parametricEqProcessor: ParametricEqProcessor
    @Inject lateinit var spectrumAnalyzerTap: SpectrumAnalyzerTap
    @Inject lateinit var unifiedTrackRegistry: UnifiedTrackRegistry
    @Inject lateinit var usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter
    @Inject lateinit var libusbDriver: tf.monochrome.android.audio.usb.LibusbUacDriver
    @Inject lateinit var bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentReplayGain: ReplayGainValues? = null

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

        // Listen to EQ changes and apply them
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                preferences.eqEnabled,
                preferences.eqBandsJson,
                preferences.eqPreamp
            ) { enabled, bandsJson, preamp ->
                Triple(enabled, bandsJson, preamp)
            }.collect { (enabled, bandsJson, preamp) ->
                applyEqSettings(enabled, bandsJson, preamp)
            }
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

    @OptIn(UnstableApi::class)
    fun playQueue() {
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
                    player.setMediaItem(resolved.mediaItem)
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

                    player.setMediaSource(source)
                } else {
                    player.setMediaItem(mediaItem)
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

    fun skipToNext() {
        val nextTrack = queueManager.next()
        if (nextTrack != null) {
            playQueue()
        } else {
            player.stop()
        }
    }

    fun skipToPrevious() {
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

    /**
     * Apply current EQ settings from preferences
     */
    private fun applyEq() {
        serviceScope.launch {
            try {
                val enabled = preferences.eqEnabled.first()
                val bandsJson = preferences.eqBandsJson.first()
                val preamp = preferences.eqPreamp.first()
                applyEqSettings(enabled, bandsJson, preamp)
            } catch (e: Exception) {
                // EQ application non-critical
            }
        }
    }

    /**
     * Apply EQ settings to the standalone AutoEQ processor (independent of mixer DSP)
     */
    private fun applyEqSettings(enabled: Boolean, bandsJson: String?, preamp: Double) {
        try {
            val bands = if (!bandsJson.isNullOrEmpty()) {
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<List<EqBand>>(bandsJson)
            } else {
                emptyList()
            }
            autoEqProcessor.applyBands(bands, preamp.toFloat(), enabled)
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
