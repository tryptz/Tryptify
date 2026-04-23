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
            .build()

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
                                libraryRepository.addToHistory(track)
                                scrobblingService.updateNowPlaying(track)
                            }
                        }
                    }
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(createSessionActivity())
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
        return object : DefaultRenderersFactory(this) {
            init {
                // FfmpegAudioRenderer picks up any audio format MediaCodec
                // can't decode (DSD, APE, TAK, WavPack, MPC, TrueHD, DTS, …).
                // EXTENSION_RENDERER_MODE_ON = added AFTER the platform
                // MediaCodecAudioRenderer, so hardware stays preferred for
                // formats it supports and FFmpeg is used as a fallback.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            }

            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return try {
                    DefaultAudioSink.Builder(context)
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
                } catch (error: Exception) {
                    projectMEngineRepository.reportAudioTapFailure(
                        "projectM audio tap unavailable: ${error.message ?: "unknown error"}"
                    )
                    checkNotNull(
                        super.buildAudioSink(
                            context,
                            enableFloatOutput,
                            enableAudioTrackPlaybackParams
                        )
                    )
                }
            }

            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                // Let the default MediaCodecAudioRenderer run first so hardware
                // decoders get priority for the codecs they support.
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
                // Append the FFmpeg extension renderer as a universal
                // fallback. Media3 walks the list in order and picks the
                // first renderer that can handle the track's format.
                runCatching {
                    out.add(
                        androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer(
                            eventHandler,
                            eventListener,
                            audioSink,
                        )
                    )
                }.onFailure { error ->
                    android.util.Log.w(
                        "PlaybackService",
                        "FfmpegAudioRenderer unavailable; falling back to MediaCodec only",
                        error,
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
                val (mediaItem, trackStream) = streamResolver.resolveMediaItem(currentTrack)
                currentReplayGain = trackStream?.replayGain

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
}
