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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var scrobblingService: ScrobblingService
    @Inject lateinit var discordPresence: tf.monochrome.android.data.presence.DiscordPresenceManager
    @Inject lateinit var projectMEngineRepository: ProjectMEngineRepository
    @Inject lateinit var channelDetectorProcessor: tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
    @Inject lateinit var downmixProcessor: tf.monochrome.android.audio.dsp.DownmixProcessor
    @Inject lateinit var mixBusProcessor: MixBusProcessor
    // The Oxford post-chain, injected so a blend's DSP copy can be seeded with
    // whatever these are set to right now.
    @Inject lateinit var inflatorEffect: tf.monochrome.android.audio.dsp.oxford.InflatorEffect
    @Inject lateinit var compressorEffect: tf.monochrome.android.audio.dsp.oxford.CompressorEffect
    @Inject lateinit var crossfeedEffect: tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
    @Inject lateinit var dspManager: DspEngineManager
    @Inject lateinit var autoEqProcessor: AutoEqProcessor
    @Inject lateinit var parametricEqProcessor: ParametricEqProcessor
    @Inject lateinit var spectrumAnalyzerTap: SpectrumAnalyzerTap
    @Inject lateinit var unifiedTrackRegistry: UnifiedTrackRegistry
    @Inject lateinit var qobuzCache: tf.monochrome.android.data.cache.QobuzStreamCacheManager
    @Inject lateinit var usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter
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
            DefaultMediaSourceFactory(buildDataSourceFactory()), atmosFrameBuffer)

    /**
     * Everything DefaultDataSource handles (file / content / asset / http),
     * plus the `qobuz://` scheme, which plays a Qobuz track out of the cache
     * file while it is still downloading instead of waiting for the last byte.
     *
     * The HTTP half is built explicitly rather than left to the default, for
     * live radio. Two of its defaults are wrong for a public directory of
     * community-run streams: cross-protocol redirects are refused, and a large
     * share of stations bounce between http and https on the way to the audio,
     * so they fail before a byte arrives; and the default User-Agent gets a 403
     * from a fair number of Icecast servers. Neither matters for TIDAL, Qobuz or
     * Apple — this only permits requests that currently hard-fail — but the
     * factory is shared with them and with the crossfade player, so the change
     * is deliberately stated here rather than buried in the radio code.
     */
    @OptIn(UnstableApi::class)
    private fun buildDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(RADIO_USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val default = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
        val qobuz = tf.monochrome.android.data.cache.QobuzPartialDataSource.Factory(qobuzCache)
        return androidx.media3.datasource.DataSource.Factory {
            tf.monochrome.android.data.cache.SchemeRoutingDataSource(
                default.createDataSource(),
                qobuz.createDataSource(),
            )
        }
    }

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        // thread. See androidx.media3.exoplayer.DefaultLoadControl defaults —
        // this widens the ceiling by 2-3× to absorb hi-bitrate streams.
        //
        // bufferForPlaybackMs is what the listener actually feels: nothing is
        // heard until that much media is buffered, on every start and every
        // skip. It used to sit at 2_500, described as "down from the 5 s
        // default" — but DEFAULT_BUFFER_FOR_PLAYBACK_MS *is* 2500, so the
        // start-up threshold had never been lowered at all. 750 ms is enough
        // for the decoder to keep ahead on a local file or a warm stream, and
        // it comes straight off time-to-first-sound. The post-underrun
        // threshold stays at 5 s, so a genuinely struggling connection still
        // refills properly instead of churning.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30_000,
                /* maxBufferMs */ 120_000,
                /* bufferForPlaybackMs */ 750,
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
                // Also what wakes the blend watcher — see startCrossfadeWatcher.
                playingSignal.value = isPlaying
                refreshNowPlayingWidget()
                // Discord draws the progress bar from timestamps and animates
                // it on its own, so a pause has to be pushed or the bar runs
                // on through music that stopped.
                queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                refreshNowPlayingWidget()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek moves the play head without changing the track, and
                // Discord is animating a bar from timestamps that just went
                // stale — it would keep counting from where the track used to
                // be. Only seeks: track changes come through the queue watcher.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        val currentTrack = queueManager.currentTrack.value
                        // For a live station this is not "the song finished" —
                        // it is the server hanging up, which Icecast does
                        // routinely. Scrobbling it would credit a station to a
                        // city on someone's Last.fm, and advancing would end
                        // playback for good on a one-item queue. Reconnect
                        // instead, bounded so a station that has gone dark for
                        // good doesn't spin forever.
                        if (currentTrack != null && isLiveStream(currentTrack)) {
                            if (liveReconnects++ < MAX_LIVE_RECONNECTS) {
                                player.prepare()
                                player.play()
                            } else {
                                liveReconnects = 0
                                onTrackEnded()
                            }
                            return
                        }
                        if (currentTrack != null) {
                            serviceScope.launch {
                                scrobblingService.scrobbleTrack(currentTrack)
                            }
                        }
                        onTrackEnded()
                    }
                    Player.STATE_READY -> {
                        consecutivePlayerErrors = 0
                        liveReconnects = 0
                        // The queue watcher pushes the presence as soon as the
                        // track changes, which is before the player has the
                        // item and therefore before its position means
                        // anything. This is the correction, once it does.
                        queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
                        applyVolume()
                        applyPlaybackSpeed()
                        applyEq()
                        applyParametricEq()
                    }
                    Player.STATE_BUFFERING, Player.STATE_IDLE -> {
                        // No action needed
                    }
                }
            }

            /**
             * Pre-queuing a track means the player can now fail on an item the
             * service never explicitly started — a Qobuz fetch that 404s, a
             * file deleted since it was queued. Without a handler that stops
             * playback dead with no recovery, so drop the window and retry the
             * position QueueManager points at (the gapless hand-off has already
             * advanced it). Once the retries are spent the track is given up on
             * and the queue moves along, so a bad item can't trap it.
             *
             * The retries are spaced out, and this is the whole point of them.
             * Retrying in the same breath meant a track that was merely slow —
             * a cold stream, a signal dip on the hand-off — hit the identical
             * not-ready condition on the retry, and the second failure moved on
             * within milliseconds of the first. With nothing to slow it down
             * that walked the queue at a few hundred ms a track. A real fetch
             * needs a moment before it counts as failed.
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                dropGaplessNext()
                val attempt = consecutivePlayerErrors++
                // A live station gets a far longer rope than a track does. Two
                // failures is ~3.6 s, which any signal dip on mobile data will
                // spend without trying — and on a one-item station queue giving
                // up means playback simply stops, with nothing to move on to and
                // no sign of why. A track that can't be fetched is dead; a
                // station that can't be reached this second usually isn't.
                val live = queueManager.currentTrack.value?.let { isLiveStream(it) } == true
                val budget = if (live) MAX_LIVE_PLAYER_ERRORS else MAX_CONSECUTIVE_PLAYER_ERRORS
                if (attempt >= budget) {
                    consecutivePlayerErrors = 0
                    onTrackEnded()
                    return
                }
                // Backs off further each time, so a stream that needs a second
                // gets one and a genuinely dead item still fails promptly.
                val backoffMs = PLAYER_ERROR_RETRY_DELAY_MS * (attempt + 1)
                val target = queueManager.currentTrack.value?.id
                playerErrorRecovery?.cancel()
                playerErrorRecovery = serviceScope.launch {
                    kotlinx.coroutines.delay(backoffMs)
                    // A skip, a new queue or a sleep timer during the wait owns
                    // the player now — retrying would yank it back.
                    if (queueManager.currentTrack.value?.id != target) return@launch
                    playQueue()
                }
            }

            /**
             * Recovers a start that audio focus refused.
             *
             * Only the refusal that lands within [FOCUS_RETRY_WINDOW_MS] of the
             * service's own play() request is retried — that's the request
             * failing, not the user starting a podcast halfway through a song,
             * which arrives later and with a different reason. Bounded retries,
             * and any successful start clears the counter, so this can never
             * turn into the app fighting another for the audio device.
             */
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    autoPlayRetries = 0
                    return
                }
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) return
                if (System.currentTimeMillis() - playRequestedAt > FOCUS_RETRY_WINDOW_MS) return
                if (autoPlayRetries >= MAX_AUTO_PLAY_RETRIES) return

                autoPlayRetries++
                serviceScope.launch {
                    kotlinx.coroutines.delay(FOCUS_RETRY_DELAY_MS)
                    if (!player.playWhenReady) startPlayback()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A gapless hand-off: the player moved to the item we
                // pre-queued, so it advanced the queue for us. Bring
                // QueueManager into line *without* re-resolving — calling
                // playQueue() here would tear down the very hand-off that just
                // happened and reintroduce the gap.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    player.currentMediaItemIndex > 0
                ) {
                    queueManager.next()
                    trimPlayedItems()
                    // There is no STATE_READY between gapless items, so the
                    // volume is re-asserted here — it's the only hook the
                    // hand-off gives us.
                    applyVolume()
                    serviceScope.launch { preloadNextTracks() }
                }
                // The window advanced, so the memo below is about the track
                // that just played. A repeat-one queue would otherwise keep the
                // same key and never re-evaluate.
                lastGaplessAttempt = null
                syncGaplessNext()

                if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    val trackId = mediaItem.mediaId.toLongOrNull()
                    if (trackId != null) {
                        val track = queueManager.currentQueue.find { it.id == trackId }
                        if (track != null) {
                            serviceScope.launch {
                                val unified = unifiedTrackRegistry[trackId]
                                // A station is not something you listened to,
                                // it is somewhere you tuned. Writing it to
                                // history fills Recently Played — and the stats
                                // Discover builds on — with entries keyed by a
                                // synthetic hash, and telling Last.fm about it
                                // scrobbles a "track" named after a station and
                                // credited to a city.
                                if (!isLiveStream(track)) {
                                    libraryRepository.addToHistory(track, unified)
                                    scrobblingService.updateNowPlaying(track)
                                }
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
        // Gapless playback. The setting was wired to the Settings screen but
        // never read here, so the toggle did nothing at all; this is what makes
        // it live. Flipping it off drops any pre-queued item immediately rather
        // than waiting for the next transition.
        serviceScope.launch {
            preferences.gaplessPlayback.collect { enabled ->
                gaplessEnabled = enabled
                syncGaplessNext()
            }
        }
        serviceScope.launch {
            preferences.gaplessNoResample.collect { noResample ->
                gaplessNoResample = noResample
                syncGaplessNext()
            }
        }

        // Mirrored here purely to seed a blend's own DSP chain. DspEngineManager
        // pushes both of these into the injected MixBusProcessor singleton, but
        // a chain copy is built outside its reach and would otherwise start at
        // the 1024 default with the mixer on regardless of what the user set.
        serviceScope.launch { preferences.dspBlockSize.collect { dspBlockSize = it } }
        serviceScope.launch { preferences.dspEnabled.collect { dspEnabled = it } }

        // Blend length. Any non-zero value takes over from the gapless window,
        // so re-derive that whenever it changes.
        serviceScope.launch {
            preferences.crossfadeDuration.collect { seconds ->
                crossfadeMs = seconds.coerceAtLeast(0) * 1_000L
                if (crossfadeMs == 0L) crossfade.cancel()
                syncGaplessNext()
            }
        }
        startCrossfadeWatcher()

        // The Discord card mirrors the queue's current track, and nothing else.
        //
        // It used to be pushed from onMediaItemTransition, which was wrong in a
        // way that only showed up on a device: that handler ignores
        // MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, and the ordinary way
        // this service starts a track is player.setMediaItem — a playlist
        // change. So the card was set by the first track and then never again,
        // and Discord happily animated its progress bar to the end of a song
        // that had long since finished. Only the gapless hand-off, which
        // pre-queues and transitions with REASON_AUTO, ever got through.
        //
        // currentTrack has no such gaps: every route to a new track moves it —
        // playlist replacement, the gapless hand-off, a crossfade, a skip by
        // hand — and it going null is every route to nothing playing at all.
        // That last part matters as much as the first: Discord holds a presence
        // until the connection that set it closes, so without it the final
        // track of the night sits on the profile for as long as the service
        // lives, which for a foreground media service is hours.
        serviceScope.launch {
            queueManager.currentTrack
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { track ->
                    if (track == null) discordPresence.clear()
                    else pushDiscordPresence(track)
                }
        }

        // Anything that reorders, extends or truncates the queue — drag to
        // reorder, play-next, clear-upcoming, a shuffle or repeat-mode change —
        // can invalidate what we pre-queued. Re-deriving the window on every
        // queue emission is cheap (it no-ops when already correct) and means no
        // individual mutation has to remember to tell us.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                queueManager.queue,
                queueManager.repeatMode,
            ) { _, _ -> Unit }.collect { syncGaplessNext() }
        }

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

    /**
     * Tell Discord what's playing, if the listener asked for that.
     *
     * The play head is only believed when the player is actually holding this
     * track. The queue moves to the next song before the player is loaded with
     * it, so asking then would hand Discord the *previous* track's position —
     * and since the card's progress bar is drawn from a start timestamp, that
     * is not a small error but a bar that opens part-filled and finishes early.
     * Mismatched means the track is starting, which means zero; STATE_READY
     * pushes again once the player agrees.
     *
     * The manager is a no-op when the feature is off, which is the normal case,
     * so this stays a cheap call on a hot path.
     */
    private fun pushDiscordPresence(track: tf.monochrome.android.domain.model.Track) {
        val player = mediaSession?.player ?: return
        val loaded = player.currentMediaItem?.mediaId == track.id.toString()
        discordPresence.update(
            tf.monochrome.android.data.presence.DiscordPresence.NowPlaying(
                title = track.title,
                artist = track.artist?.name,
                album = track.album?.title,
                artworkAsset = track.album?.cover?.let {
                    tf.monochrome.android.domain.model.buildCoverUrl(it, 640)
                },
                // The badge's rhythm and colour are resolved in the manager,
                // which already runs off the main thread and holds the graph
                // and the palette cache — this only hands it the two inputs.
                genreId = unifiedTrackRegistry[track.id]?.genreId,
                // The SAME size the rest of the app asks for, deliberately.
                // DynamicColorExtractor caches by URL and Coil caches by URL, so
                // a different size here is a different key in both: it made
                // every track fetch a second copy of its own cover and run a
                // second software decode and Palette pass, duplicating work the
                // player had already done a moment earlier.
                artworkUrl = track.album?.cover?.let {
                    tf.monochrome.android.domain.model.buildCoverUrl(it, 640)
                },
                positionMs = if (loaded) player.currentPosition.coerceAtLeast(0L) else 0L,
                // The player's duration is unset until the track is prepared;
                // the catalogue's is in seconds and always there.
                durationMs = (player.duration.takeIf { loaded && it > 0 })
                    ?: (track.duration * 1000L),
                // A track the player hasn't reached yet is starting, not paused
                // — reading isPlaying here would badge every new song "Paused".
                paused = loaded && !player.isPlaying,
            ),
        )
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        // Discord holds a presence until the connection that set it closes, so
        // leaving without this parks the last track on the profile for good.
        // shutdown(), not clear(): the service is going away now, so there is
        // nothing for the between-tracks grace period to wait for.
        discordPresence.shutdown()
        crossfade.release()
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
                startPlayback()

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
                    if (!resolved.isPlayable) {
                        onTrackEnded()
                        return@launch
                    }
                    player.setMediaItem(resolved.mediaItem)
                    player.prepare()
                    startPlayback()
                    if (!isLiveStream(currentTrack)) {
                        libraryRepository.addToHistory(currentTrack, unifiedTrack)
                    }
                    return@launch
                }

                val (mediaItem, trackStream) = streamResolver.resolveMediaItem(currentTrack)

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
                    player.setMediaSource(atmosTapFactory.wrap(source))
                } else {
                    player.setMediaItem(mediaItem)
                }

                player.prepare()
                startPlayback()

                libraryRepository.addToHistory(currentTrack)

                // Preload next tracks
                preloadNextTracks()
                // setMediaItem/setMediaSource above replaced the whole playlist,
                // so any previously pre-queued item is already gone; rebuild the
                // window for the track we just started.
                syncGaplessNext()
            } catch (e: Exception) {
                onTrackEnded()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun skipToNext() {
        crossfade.cancel()
        // An explicit skip takes the normal resolve path rather than the
        // pre-queued item: playQueue() replaces the playlist outright, which
        // also discards the window. Dropping it up front keeps the player from
        // briefly auto-advancing into it if the track ends mid-skip.
        dropGaplessNext()
        val nextTrack = queueManager.next()
        if (nextTrack != null) {
            playQueue()
        } else {
            player.stop()
        }
    }

    @OptIn(UnstableApi::class)
    fun skipToPrevious() {
        // If more than 3 seconds in, restart current track
        if (player.currentPosition > 3000) {
            player.seekTo(0)
            return
        }
        crossfade.cancel()
        dropGaplessNext()
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
            startPlayback()
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

    // Volume has two independent inputs — the user slider and the crossfade
    // ramp — and both would otherwise want to own player.volume outright.
    // They're kept apart here: [baseVolume] is the level the track should play
    // at, [crossfadeGain] is where the blend has got to, and only [pushVolume]
    // multiplies them onto the player. Without this, a STATE_READY landing in
    // the middle of a blend reset the ramp to full and the incoming track
    // slammed in at once.
    @Volatile private var baseVolume = 1f
    @Volatile private var crossfadeGain = 1f

    private fun pushVolume() {
        val effective = baseVolume * crossfadeGain
        player.volume = effective
        // Mirror to the libusb bypass path. Player.volume runs
        // inside DefaultAudioSink which we skip when bypass is
        // hot, so without this line the volume slider and the blend
        // ramp only apply on the AudioFlinger fallback.
        bypassVolumeController.setVolume(effective)
    }

    private fun applyVolume() {
        serviceScope.launch {
            baseVolume = preferences.volume.first().toFloat()
            pushVolume()
        }
    }

    // --- Blending between tracks ------------------------------------------
    //
    // One slider governs the whole transition: at 0s tracks butt up against
    // each other with no gap (the pre-queued window below), and any value above
    // that overlaps them instead. The two are mutually exclusive by
    // construction — an overlap needs the next track started *early*, which is
    // the opposite of handing it to the player to follow on seamlessly — so a
    // non-zero blend switches the gapless window off.

    @Volatile private var crossfadeMs = 0L

    /**
     * Mirrors [player]'s playing state as something suspendable. Player.Listener
     * is a callback, and the blend watcher needs to *wait* for playback rather
     * than poll for it.
     */
    private val playingSignal = kotlinx.coroutines.flow.MutableStateFlow(false)

    // Live copies of the two DSP settings a blend's chain has to be told about;
    // defaults match PreferencesManager's until the collectors above land.
    @Volatile private var dspBlockSize = 1024
    @Volatile private var dspEnabled = false

    // Type left inferred, like atmosTapFactory above: spelling CrossfadeController
    // out here is itself an opt-in usage that an @OptIn on the property doesn't
    // cover, so lint flags the declaration even when every call site is clean.
    private val crossfade by lazy { buildCrossfadeController() }

    /**
     * The settings last pushed to the live chain, kept so a blend's copy can be
     * seeded with exactly what is playing rather than re-deriving it from
     * preferences and risking the two drifting apart.
     */
    private data class AppliedEq(
        val bandsL: List<EqBand>,
        val bandsR: List<EqBand>,
        val preamp: Float,
        val enabled: Boolean,
    ) {
        companion object {
            val NONE = AppliedEq(emptyList(), emptyList(), 0f, false)
        }
    }

    @Volatile private var lastAutoEq = AppliedEq.NONE
    @Volatile private var lastParametricEq = AppliedEq.NONE

    /**
     * A second processing chain for a blend's outgoing tail, carrying the same
     * DSP graph and EQ curves as the track that is already playing. Released
     * with the blend, which destroys its native engine — see [DspChain].
     */
    @OptIn(UnstableApi::class)
    private fun buildSeededDspChain(): tf.monochrome.android.audio.dsp.DspChain {
        val chain = tf.monochrome.android.audio.dsp.DspChain.createCopy()
        val autoEq = lastAutoEq
        val paramEq = lastParametricEq
        chain.seedFrom(
            dspStateJson = runCatching { dspManager.getStateJson() }.getOrNull(),
            autoEqBandsL = autoEq.bandsL,
            autoEqBandsR = autoEq.bandsR,
            autoEqPreamp = autoEq.preamp,
            autoEqEnabled = autoEq.enabled,
            parametricBands = paramEq.bandsL,
            parametricPreamp = paramEq.preamp,
            parametricEnabled = paramEq.enabled,
            // Straight off the live singletons — whatever is playing right now.
            inflatorState = inflatorEffect.state.value,
            compressorState = compressorEffect.state.value,
            crossfeedState = crossfeedEffect.state.value,
            blockSize = dspBlockSize,
            dspEnabled = dspEnabled,
        )
        // The copy's native engine only exists once ExoPlayer configures it
        // with a format, which is after the blend has started.
        chain.observeEngine(serviceScope)
        return chain
    }

    @OptIn(UnstableApi::class)
    private fun buildCrossfadeController(): CrossfadeController =
        CrossfadeController(
            context = this,
            scope = serviceScope,
            dataSourceFactory = buildDataSourceFactory(),
            dspChainFactory = ::buildSeededDspChain,
        ) { gain ->
            crossfadeGain = gain
            pushVolume()
        }

    /**
     * Whether a blend can run right now.
     *
     * The exclusive libusb path is the hard stop: it claims the USB device for
     * one stream, and the tail player uses the ordinary Android sink, so during
     * a blend its audio would come out of a different device entirely. Skipping
     * the blend is the honest outcome — bit-perfect output is the reason
     * someone plugs in that DAC, and a gap is a smaller price than the tail
     * playing out of the phone speaker.
     */
    private fun canCrossfade(): Boolean =
        crossfadeMs > 0L && !libusbDriver.isStreaming.value

    /**
     * Hands the tail of the current track to the secondary player and starts
     * the next one on the main player, overlapping the two.
     */
    @OptIn(UnstableApi::class)
    private fun beginCrossfade() {
        val item = player.currentMediaItem ?: return
        val outgoing = queueManager.currentTrack.value
        val started = crossfade.start(
            item = item,
            fromPositionMs = player.currentPosition,
            durationMs = crossfadeMs,
            tailVolume = baseVolume,
            // The incoming track is resolved and buffered from scratch below;
            // until the main player is genuinely sounding, there is nothing to
            // fade in and the blend waits.
            incomingReady = { player.playbackState == Player.STATE_READY && player.isPlaying },
        )
        if (!started) return // Fall through to the ordinary end-of-track path.

        // The outgoing track never reaches STATE_ENDED on the main player now —
        // we pre-empt it — so scrobble here instead, exactly as that handler
        // would have.
        outgoing?.let { serviceScope.launch { scrobblingService.scrobbleTrack(it) } }

        // Start the incoming track silent; the ramp brings it up.
        crossfadeGain = 0f
        onTrackEnded()
    }

    /**
     * Watches the play head so a blend can begin before the track runs out.
     *
     * There is no callback for "the playhead reached duration - blend", so this
     * has to poll — but only when there is something to poll for. The comment
     * here used to claim it ran "only while a blend length is actually set" and
     * it did nothing of the sort: the loop ticked four times a second for the
     * life of the service, and since the default blend is zero, the overwhelmingly
     * common case was waking ~345,000 times a day to evaluate `canCrossfade()`
     * as false. It now parks on the preference and on playback, so a gapless
     * user costs nothing and a blending one costs a few comparisons a second
     * while a track is actually running.
     */
    @OptIn(UnstableApi::class)
    private fun startCrossfadeWatcher() {
        serviceScope.launch {
            while (true) {
                // Suspends until a blend length is set; wakes on the next
                // preference change rather than on a timer.
                if (crossfadeMs == 0L) {
                    preferences.crossfadeDuration.first { it > 0 }
                    continue
                }
                if (!player.isPlaying) {
                    playingSignal.first { it }
                    continue
                }
                kotlinx.coroutines.delay(CROSSFADE_POLL_MS)
                if (!canCrossfade() || crossfade.isRunning) continue
                if (!player.isPlaying) continue
                if (queueManager.peekNext() == null) continue
                if (CrossfadeRamp.shouldStart(
                        positionMs = player.currentPosition,
                        durationMs = player.duration,
                        crossfadeMs = crossfadeMs,
                    )
                ) {
                    beginCrossfade()
                }
            }
        }
    }

    // --- Gapless playback -------------------------------------------------
    //
    // ExoPlayer can only play one track into the next without a gap if it holds
    // both: the second decoder has to be running before the first track ends.
    // This app otherwise keeps exactly one item in the player and re-resolves
    // on every transition, because a TIDAL stream URL queued minutes ahead
    // would have aged out by the time it was used (see QueueForwardingPlayer).
    //
    // So the player's playlist is kept as a two-item window — [current] or
    // [current, next] — and `next` is only added when it resolves to a URI that
    // survives the wait: an on-device file, or a qobuz:// URI whose resolution
    // is deferred to open time. QueueManager stays the source of truth for
    // queue position; the window is a cache in front of it, re-derived from
    // scratch by [syncGaplessNext] whenever anything moves, so it cannot drift.

    @Volatile private var gaplessEnabled = false
    @Volatile private var gaplessNoResample = true

    /**
     * What [syncGaplessNext] last evaluated, so an unchanged situation is not
     * evaluated again. Keyed on everything the decision depends on.
     */
    private data class GaplessAttempt(
        val trackId: Long,
        val enabled: Boolean,
        val crossfadeMs: Long,
        val noResample: Boolean,
    )
    @Volatile private var lastGaplessAttempt: GaplessAttempt? = null
    private var gaplessJob: kotlinx.coroutines.Job? = null

    private var consecutivePlayerErrors = 0
    private var playerErrorRecovery: kotlinx.coroutines.Job? = null

    /** Times a live station has been reconnected since it last played cleanly. */
    private var liveReconnects = 0

    /** True when this track is a live stream rather than a recording. */
    private fun isLiveStream(track: tf.monochrome.android.domain.model.Track): Boolean =
        unifiedTrackRegistry[track.id]?.source is
            tf.monochrome.android.domain.model.PlaybackSource.RadioStream

    private companion object {
        /** Retries a failing position before moving past it. */
        const val MAX_CONSECUTIVE_PLAYER_ERRORS = 2

        /**
         * The same, for a live station. Far more generous, and deliberately so:
         * a dropped connection is the normal life of an Icecast stream, and the
         * budget resets the moment it plays again.
         */
        const val MAX_LIVE_PLAYER_ERRORS = 8

        /** Reconnects after the server hangs up before the station is given up on. */
        const val MAX_LIVE_RECONNECTS = 5

        /**
         * Sent on every HTTP request the player makes. A real name because a
         * fair number of community Icecast servers refuse the default one.
         */
        const val RADIO_USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"

        /**
         * Grace given to a failing position before it's retried, multiplied by
         * the attempt number — so a track gets ~1.2s and then ~2.4s to come
         * good, and is only abandoned after ~3.6s of genuinely not loading.
         */
        const val PLAYER_ERROR_RETRY_DELAY_MS = 1_200L

        /**
         * How soon after the service asks to play a refusal still counts as
         * "the start-up focus request lost", rather than the user genuinely
         * handing audio to something else mid-track.
         */
        const val FOCUS_RETRY_WINDOW_MS = 1_500L
        const val FOCUS_RETRY_DELAY_MS = 400L
        const val MAX_AUTO_PLAY_RETRIES = 2

        /** How often the play head is checked against the blend threshold. */
        const val CROSSFADE_POLL_MS = 250L
    }

    /** When the service last asked the player to start, for the check above. */
    @Volatile private var playRequestedAt = 0L
    private var autoPlayRetries = 0

    /**
     * Starts playback, remembering when we asked.
     *
     * Media3 requests audio focus on the way into play(). If that request is
     * refused it flips playWhenReady straight back off, and nothing here used
     * to notice — the track just sat loaded and paused at 0:00 until the user
     * pressed play. The refusal is most likely right after a slow load, when
     * the gap between tracks is longest, which is why it showed up on tracks
     * being heard for the first time.
     */
    private fun startPlayback() {
        playRequestedAt = System.currentTimeMillis()
        player.play()
    }

    /**
     * Removes everything the player has already finished, so the current item
     * is always index 0 and the window is easy to reason about.
     */
    private fun trimPlayedItems() {
        while (player.currentMediaItemIndex > 0) {
            player.removeMediaItem(0)
        }
    }

    /** Drops the pre-queued item, if any. Never touches what's playing. */
    private fun dropGaplessNext() {
        while (player.mediaItemCount > player.currentMediaItemIndex + 1) {
            player.removeMediaItem(player.mediaItemCount - 1)
        }
    }

    /**
     * Makes the player's second slot match whatever QueueManager says plays
     * next — adding, replacing or removing it as needed.
     *
     * Idempotent and safe to call from anywhere: it re-reads the queue rather
     * than tracking deltas, so a reorder, a shuffle toggle, a repeat-mode
     * change or a fresh queue all converge to the right thing.
     */
    private fun syncGaplessNext() {
        if (player.mediaItemCount == 0) return

        val next = queueManager.peekNext()
        // A non-zero blend overlaps the tracks instead, which needs the next
        // one started early rather than queued to follow on.
        if (!gaplessEnabled || crossfadeMs > 0L || next == null) {
            dropGaplessNext()
            return
        }

        val wantedId = next.id.toString()
        val queuedIndex = player.currentMediaItemIndex + 1
        if (player.mediaItemCount > queuedIndex &&
            player.getMediaItemAt(queuedIndex).mediaId == wantedId
        ) {
            return // Already correct.
        }
        // Don't re-run the expensive half for a situation already decided.
        //
        // Most next tracks cannot be pre-queued at all — a TIDAL or Apple URL
        // ages out, so warmUpcoming answers false. But it only answers that
        // after running the full local-library match for the track: catalogue
        // id, then ISRC, then MusicBrainz, then a metadata comparison, each a
        // database round trip. For Qobuz it instead re-enters openPartial().
        // Neither result changes until the track or the settings do, and this
        // function is called from six places — one of them every emission of
        // the queue flow. So a queue that couldn't be pre-queued was paying for
        // a full library search over and over while gapless was on, which is
        // exactly when it was least affordable.
        val attempt = GaplessAttempt(next.id, gaplessEnabled, crossfadeMs, gaplessNoResample)
        if (lastGaplessAttempt == attempt) return
        lastGaplessAttempt = attempt

        dropGaplessNext()

        // One attempt in flight at a time. Without this a burst of queue
        // emissions started a warm for each, all racing to append.
        gaplessJob?.cancel()
        gaplessJob = serviceScope.launch {
            // Only pre-queue sources whose URI outlives the wait. This also
            // warms them, so the hand-off has bytes ready.
            if (!streamResolver.warmUpcoming(next)) return@launch

            if (!sampleRatesMatch(next)) return@launch

            val item = resolveGaplessItem(next) ?: return@launch

            // The queue can move while we resolve — re-check before committing,
            // otherwise we'd append a track that is no longer next.
            if (!gaplessEnabled) return@launch
            if (queueManager.peekNext()?.id != next.id) return@launch
            if (player.mediaItemCount != player.currentMediaItemIndex + 1) return@launch

            player.addMediaItem(item)
        }
    }

    /**
     * Whether [next] plays at the same sample rate as what is playing now.
     *
     * A rate change forces DefaultAudioSink to tear down and rebuild its
     * AudioTrack, which is the gap gapless exists to remove — so pre-queuing
     * across one buys nothing and hides a hand-off that cannot be seamless.
     * That transition falls back to the ordinary per-track path instead.
     *
     * Taking the gap is what a bit-perfect player is supposed to do: the only
     * way to avoid it is to resample everything to one fixed rate, which is
     * exactly what this app's USB path exists not to do. Set a blend time if
     * you'd rather cover the transition than hear it.
     *
     * Unknown rates pass. Refusing on missing metadata would disable gapless
     * for every catalogue track that doesn't report a rate, and the fallback if
     * the guess is wrong is just the reconfigure we were trying to avoid.
     */
    @OptIn(UnstableApi::class)
    private fun sampleRatesMatch(next: tf.monochrome.android.domain.model.Track): Boolean {
        // Toggle off: the user would rather the hand-off stayed seamless and
        // let the system resample, so a rate change is no longer a reason to
        // skip pre-queuing.
        if (!gaplessNoResample) return true
        val current = player.audioFormat?.sampleRate?.takeIf { it > 0 } ?: return true
        val upcoming = unifiedTrackRegistry[next.id]?.sampleRate?.takeIf { it > 0 } ?: return true
        return current == upcoming
    }

    /**
     * Resolves [track] to a MediaItem that may be pre-queued, or null when it
     * resolves to something that can't be (a time-limited stream URL, an
     * inline DASH manifest, or nothing playable at all).
     */
    private suspend fun resolveGaplessItem(track: tf.monochrome.android.domain.model.Track): MediaItem? {
        val unified = unifiedTrackRegistry[track.id]
        val item = if (unified != null) {
            streamResolver.resolveUnifiedTrack(unified).takeIf { it.isPlayable }?.mediaItem
        } else {
            streamResolver.resolveMediaItem(track).first
        } ?: return null

        val uri = item.localConfiguration?.uri?.toString()
        return item.takeIf { GaplessEligibility.isStableUri(uri) }
    }

    private suspend fun preloadNextTracks() {
        // Warm the next 2 tracks so a skip doesn't start cold. Only durable
        // work is done — see StreamResolver.warmUpcoming; a full resolve here
        // fetched short-lived stream URLs and discarded them, which cost a
        // request at the worst possible moment and bought nothing.
        val queue = queueManager.currentQueue
        val currentIdx = queueManager.currentQueueIndex
        for (i in 1..2) {
            val nextIdx = currentIdx + i
            if (nextIdx < queue.size) {
                streamResolver.warmUpcoming(queue[nextIdx])
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
                lastAutoEq = AppliedEq(emptyList(), emptyList(), 0f, false)
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
            lastAutoEq = AppliedEq(bandsL, bandsR, preamp.toFloat(), active)
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
            lastParametricEq = AppliedEq(bands, bands, preamp.toFloat(), enabled)
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
