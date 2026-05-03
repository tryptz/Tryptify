package tf.monochrome.android.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.player.PlaybackService
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.StreamResolver
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queueManager: QueueManager,
    private val streamResolver: StreamResolver,
    private val repository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val downloadManager: DownloadManager,
    private val preferences: PreferencesManager,
    private val projectMEngineRepository: ProjectMEngineRepository,
    private val unifiedTrackRegistry: tf.monochrome.android.player.UnifiedTrackRegistry,
    private val qobuzIdRegistry: tf.monochrome.android.data.api.QobuzIdRegistry,
    private val trackShareHelper: tf.monochrome.android.share.TrackShareHelper,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
    private val bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController,
) : ViewModel() {

    /**
     * Reference-counted spectrum tap subscription. Screens call acquire on
     * mount and release on dispose; the analyzer keeps running as long as any
     * one subscriber holds a stake.
     */
    fun acquireSpectrum() = spectrumAnalyzer.acquire()
    fun releaseSpectrum() = spectrumAnalyzer.release()

    // --- State from QueueManager (runs in-process, no IPC needed) ---
    val currentTrack: StateFlow<Track?> = queueManager.currentTrack
    val queue: StateFlow<List<Track>> = queueManager.queue
    val currentIndex: StateFlow<Int> = queueManager.currentIndex
    val shuffleEnabled: StateFlow<Boolean> = queueManager.shuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = queueManager.repeatMode

    // --- Player state (observed from MediaController) ---
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    val progress: StateFlow<Float>
        get() = MutableStateFlow(
            if (_durationMs.value > 0) _positionMs.value.toFloat() / _durationMs.value.toFloat()
            else 0f
        ).asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    // --- Active Track Meta (Lyrics / Liked) ---
    private val _isCurrentTrackLiked = MutableStateFlow(false)
    val isCurrentTrackLiked: StateFlow<Boolean> = _isCurrentTrackLiked.asStateFlow()

    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading.asStateFlow()

    // --- Parity Settings ---
    val visualizerSensitivity: StateFlow<Int> = preferences.visualizerSensitivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 50)
    val visualizerBrightness: StateFlow<Int> = preferences.visualizerBrightness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 80)
    val nowPlayingViewMode: StateFlow<NowPlayingViewMode> = preferences.nowPlayingViewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlayingViewMode.COVER_ART)
    val romajiLyrics: StateFlow<Boolean> = preferences.romajiLyrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val visualizerEngineEnabled: StateFlow<Boolean> = preferences.visualizerEngineEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val visualizerShowFps: StateFlow<Boolean> = preferences.visualizerShowFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val visualizerFullscreen: StateFlow<Boolean> = preferences.visualizerFullscreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val visualizerTouchWaveform: StateFlow<Boolean> = preferences.visualizerTouchWaveform
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val _visualizerCompact = MutableStateFlow(false)
    val visualizerCompact: StateFlow<Boolean> = _visualizerCompact.asStateFlow()

    // --- Spectrum analyzer (global prefs) ---
    val spectrumAnalyzerEnabled: StateFlow<Boolean> = preferences.spectrumAnalyzerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val spectrumShowOnNowPlaying: StateFlow<Boolean> = preferences.spectrumShowOnNowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setSpectrumShowOnNowPlaying(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpectrumShowOnNowPlaying(enabled) }
    }

    val visualizerAutoShuffle: StateFlow<Boolean> = projectMEngineRepository.autoShuffle
    val visualizerEngineStatus: StateFlow<VisualizerEngineStatus> = projectMEngineRepository.engineStatus
    val visualizerPresets: StateFlow<List<VisualizerPreset>> = projectMEngineRepository.presets
    val currentVisualizerPreset: StateFlow<VisualizerPreset?> = projectMEngineRepository.currentPreset
    val visualizerFavoritePresetIds: StateFlow<Set<String>> = projectMEngineRepository.favoritePresetIds
    val visualizerRepository: ProjectMEngineRepository
        get() = projectMEngineRepository

    // --- Playback Speed ---
    val playbackSpeed: StateFlow<Float> = preferences.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    // --- Volume ---
    val volume: StateFlow<Float> = preferences.volume
        .map { it.toFloat() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    // --- Global Favorites State ---
    val favoriteTrackIds: StateFlow<Set<Long>> = libraryRepository.getFavoriteTracks()
        .map { tracks -> tracks.map { it.id }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val playlists: StateFlow<List<tf.monochrome.android.data.db.entity.UserPlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Legacy Track IDs → their UnifiedTrack source live in UnifiedTrackRegistry
    // (a @Singleton) so PlaybackService's notification / media-button skip path
    // can resolve unified tracks too. Don't redeclare here.


    init {
        connectToService()
        startPositionPolling()
        observeCurrentTrackMeta()
        viewModelScope.launch {
            downloadManager.observeAllActiveDownloads().collectLatest { active ->
                _activeDownloads.value = active
            }
        }
        viewModelScope.launch {
            preferences.spectrumFftSize.collect { size ->
                spectrumAnalyzer.fftSize = size
            }
        }
    }

    private fun observeCurrentTrackMeta() {
        viewModelScope.launch {
            // Re-fetch when track OR romaji setting changes
            kotlinx.coroutines.flow.combine(currentTrack, romajiLyrics) { track, _ -> track }
                .collectLatest { track ->
                    if (track != null) {
                        // Start fetching lyrics
                        _isLyricsLoading.value = true
                        _currentLyrics.value = null
                        
                        launch {
                            // Pass the full Track so the repository can fall
                            // back to LRCLib (track + artist + album +
                            // duration) when TIDAL returns no lyrics.
                            _currentLyrics.value = repository.getLyrics(track.id, track).getOrNull()
                            _isLyricsLoading.value = false
                        }

                        // Observe liked status
                        libraryRepository.isFavoriteTrack(track.id).collectLatest { isLiked ->
                            _isCurrentTrackLiked.value = isLiked
                        }
                    } else {
                        _currentLyrics.value = null
                        _isLyricsLoading.value = false
                        _isCurrentTrackLiked.value = false
                    }
                }
        }
    }

    fun toggleLikeCurrentTrack() {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavoriteTrack(track)
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            libraryRepository.toggleFavoriteTrack(track)
        }
    }

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync().also { future ->
            future.addListener({
                try {
                    mediaController = future.get()
                    setupPlayerListener()
                    syncState()
                } catch (_: Exception) {
                    // Service not yet available — will retry or user will trigger playback
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                syncState()
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                syncState()
            }
        })
    }

    private fun syncState() {
        mediaController?.let { mc ->
            _isPlaying.value = mc.isPlaying
            _durationMs.value = mc.duration.coerceAtLeast(0)
            _positionMs.value = mc.currentPosition.coerceAtLeast(0)
        }
    }

    private fun startPositionPolling() {
        viewModelScope.launch {
            while (isActive) {
                mediaController?.let { mc ->
                    if (mc.isPlaying) {
                        _positionMs.value = mc.currentPosition.coerceAtLeast(0)
                        _durationMs.value = mc.duration.coerceAtLeast(0)
                    }
                }
                delay(250) // 4 updates/sec for smooth progress
            }
        }
    }

    // --- Actions ---

    /** Play a single track (sets a 1-item queue). */
    fun playTrack(track: Track) {
        queueManager.setQueue(listOf(track), 0)
        resolveAndPlay()
    }

    /** Play a track within a list (sets the full list as queue). */
    fun playTrack(track: Track, trackList: List<Track>) {
        queueManager.playTrackInQueue(track, trackList)
        resolveAndPlay()
    }

    /** Play all tracks starting from index 0. */
    fun playAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queueManager.setQueue(tracks, 0)
        resolveAndPlay()
    }

    /** Shuffle-play all tracks. */
    fun shufflePlay(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        try {
            queueManager.setQueue(tracks, 0)
            queueManager.toggleShuffle()
            resolveAndPlay()
        } catch (t: Throwable) {
            android.util.Log.e("PlayerViewModel", "shufflePlay(${tracks.size}) failed", t)
        }
    }

    // --- Unified Track playback (local files, collections) ---

    /** Play a UnifiedTrack within a list — the correct path for local/collection files. */
    fun playUnifiedTrack(track: UnifiedTrack, trackList: List<UnifiedTrack>) {
        val legacyTracks = trackList.map { it.toLegacyTrack() }
        // Store source mappings so resolveAndPlay can find the right playback source
        trackList.forEach { ut ->
            val legacyId = ut.toLegacyTrack().id
            unifiedTrackRegistry.put(legacyId, ut)
        }
        val legacyTrack = track.toLegacyTrack()
        queueManager.playTrackInQueue(legacyTrack, legacyTracks)
        resolveAndPlay()
    }

    /** Play all unified tracks starting from index 0. */
    fun playAllUnified(tracks: List<UnifiedTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { ut ->
            unifiedTrackRegistry.put(ut.toLegacyTrack().id, ut)
        }
        val legacyTracks = tracks.map { it.toLegacyTrack() }
        queueManager.setQueue(legacyTracks, 0)
        resolveAndPlay()
    }

    /** Shuffle-play all unified tracks. */
    fun shufflePlayUnified(tracks: List<UnifiedTrack>) {
        if (tracks.isEmpty()) return
        try {
            tracks.forEach { ut ->
                unifiedTrackRegistry.put(ut.toLegacyTrack().id, ut)
            }
            val legacyTracks = tracks.map { it.toLegacyTrack() }
            queueManager.setQueue(legacyTracks, 0)
            queueManager.toggleShuffle()
            resolveAndPlay()
        } catch (t: Throwable) {
            android.util.Log.e("PlayerViewModel", "shufflePlayUnified(${tracks.size}) failed", t)
        }
    }

    /** Add tracks to end of current queue. */
    fun addToQueue(tracks: List<Track>) {
        queueManager.addToQueue(tracks)
    }

    /** Insert a track right after the current one. */
    fun playNext(track: Track) {
        queueManager.addNextInQueue(track)
    }

    fun togglePlayPause() {
        mediaController?.let { mc ->
            if (mc.isPlaying) mc.pause() else mc.play()
        }
    }

    fun skipToNext() {
        val next = queueManager.next()
        if (next != null) {
            resolveAndPlay()
        } else {
            mediaController?.stop()
        }
    }

    fun skipToPrevious() {
        val position = mediaController?.currentPosition ?: 0
        if (position > 3000) {
            mediaController?.seekTo(0)
            return
        }
        val prev = queueManager.previous()
        if (prev != null) {
            resolveAndPlay()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun seekToFraction(fraction: Float) {
        val pos = (fraction * _durationMs.value).toLong()
        seekTo(pos)
    }

    fun toggleShuffle() {
        try {
            queueManager.toggleShuffle()
        } catch (t: Throwable) {
            android.util.Log.e("PlayerViewModel", "toggleShuffle failed", t)
        }
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
    }

    fun setVolume(newVolume: Float) {
        viewModelScope.launch {
            preferences.setVolume(newVolume.toDouble())
            mediaController?.volume = newVolume
            // Mirror to the libusb bypass path. mediaController.volume
            // routes to Player.volume → DefaultAudioSink, which we
            // skip when bypass is hot, so a slider drag would
            // otherwise have zero audible effect on the DAC. Matches
            // the existing delegate-path semantics: no ReplayGain
            // re-application on a mid-playback drag (PlaybackService
            // re-applies on the next STATE_READY).
            bypassVolumeController.setVolume(newVolume)
        }
    }

    fun cycleNowPlayingViewMode() {
        viewModelScope.launch {
            val current = nowPlayingViewMode.value
            val next = when (current) {
                NowPlayingViewMode.COVER_ART -> NowPlayingViewMode.VISUALIZER
                NowPlayingViewMode.VISUALIZER -> NowPlayingViewMode.COVER_ART
                NowPlayingViewMode.LYRICS -> NowPlayingViewMode.COVER_ART
                NowPlayingViewMode.QUEUE -> NowPlayingViewMode.COVER_ART
            }
            if (next == NowPlayingViewMode.VISUALIZER) {
                preferences.setVisualizerEngineEnabled(true)
            }
            preferences.setNowPlayingViewMode(next)
        }
    }

    fun setNowPlayingViewMode(mode: NowPlayingViewMode) {
        viewModelScope.launch {
            if (mode == NowPlayingViewMode.VISUALIZER) {
                preferences.setVisualizerEngineEnabled(true)
            }
            preferences.setNowPlayingViewMode(mode)
        }
    }

    fun setVisualizerShuffle(enabled: Boolean) {
        projectMEngineRepository.setShuffleEnabled(enabled)
    }

    fun nextVisualizerPreset() {
        projectMEngineRepository.nextPreset()
    }

    fun selectVisualizerPreset(preset: VisualizerPreset) {
        projectMEngineRepository.selectPreset(preset)
    }

    fun toggleVisualizerFavoritePreset(presetId: String) {
        projectMEngineRepository.toggleFavoritePreset(presetId)
    }

    fun setVisualizerPlaybackPaused(paused: Boolean) {
        projectMEngineRepository.setPlaybackPaused(paused)
    }

    fun toggleVisualizerCompact() {
        _visualizerCompact.value = !_visualizerCompact.value
    }

    fun toggleVisualizerFullscreen() {
        viewModelScope.launch {
            val current = visualizerFullscreen.value
            preferences.setVisualizerFullscreen(!current)
        }
    }

    fun skipToQueueIndex(index: Int) {
        queueManager.skipToIndex(index)
        resolveAndPlay()
    }

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch { preferences.setPlaybackSpeed(speed) }
    }

    private fun resolveAndPlay() {
        val track = queueManager.currentTrack.value ?: return
        viewModelScope.launch {
            try {
                // Resolution priority:
                //   1. unifiedTrackRegistry — local files / collections / Qobuz
                //      tracks already promoted to UnifiedTrack at queue time.
                //   2. qobuzIdRegistry — synthesize a QobuzCached UnifiedTrack
                //      for tracks whose ids came in via getQobuzAlbum /
                //      getQobuzArtist / searchQobuz but were enqueued as
                //      legacy Track (e.g. an album-detail screen tap). Without
                //      this step the legacy path below would call TIDAL
                //      /track/?id=<qobuzId> which either 404s or returns the
                //      wrong track because the numeric id doesn't match.
                //   3. Legacy TIDAL path.
                val unifiedTrack = unifiedTrackRegistry[track.id]
                    ?: synthesizeQobuzUnifiedTrack(track)
                if (unifiedTrack != null) {
                    // Make sure the synthesized UnifiedTrack lands in the
                    // registry so PlaybackService.onMediaItemTransition can
                    // tag the history row with the right source. Otherwise
                    // Recently Played would re-resolve via TIDAL after process
                    // death.
                    unifiedTrackRegistry.put(track.id, unifiedTrack)
                    val resolved = streamResolver.resolveUnifiedTrack(unifiedTrack)
                    if (!resolved.isPlayable) {
                        skipToNext()
                        return@launch
                    }
                    mediaController?.let { mc ->
                        mc.setMediaItem(resolved.mediaItem)
                        mc.prepare()
                        mc.play()
                    }
                } else {
                    // Legacy API path
                    val (mediaItem, _) = streamResolver.resolveMediaItem(track)
                    if (mediaItem == null) {
                        skipToNext()
                        return@launch
                    }
                    mediaController?.let { mc ->
                        mc.setMediaItem(mediaItem)
                        mc.prepare()
                        mc.play()
                    }
                }
            } catch (_: Exception) {
                // On error skip to next
                skipToNext()
            }
        }
    }

    /**
     * Build a transient UnifiedTrack with PlaybackSource.QobuzCached for a
     * legacy Track whose id was previously registered as Qobuz. Used by
     * resolveAndPlay so a tap on a Qobuz-album track row routes through the
     * cache-on-demand path rather than TIDAL streaming.
     */
    private fun synthesizeQobuzUnifiedTrack(track: Track): UnifiedTrack? {
        if (!qobuzIdRegistry.isQobuzTrack(track.id)) return null
        return UnifiedTrack(
            id = "qobuz_${track.id}",
            title = track.title,
            durationSeconds = track.duration,
            trackNumber = track.trackNumber,
            discNumber = track.volumeNumber,
            explicit = track.explicit,
            artistName = track.displayArtist.ifBlank { "Unknown Artist" },
            artistNames = track.artists.map { it.name }
                .ifEmpty { listOfNotNull(track.artist?.name) },
            albumArtistName = track.artist?.name,
            albumTitle = track.album?.title,
            albumId = track.album?.id?.toString(),
            artworkUri = track.coverUrl,
            source = tf.monochrome.android.domain.model.PlaybackSource.QobuzCached(qobuzId = track.id),
            sourceType = tf.monochrome.android.domain.model.SourceType.QOBUZ,
        )
    }

    // --- Downloads ---

    private val _activeDownloads = MutableStateFlow<Map<Long, tf.monochrome.android.data.downloads.TrackDownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<Long, tf.monochrome.android.data.downloads.TrackDownloadState>> = _activeDownloads.asStateFlow()

    // Live download state for whichever track is currently playing — drives
    // the player's download button visual feedback (queued/downloading arc,
    // completed checkmark, failed indicator).
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentTrackDownloadState: StateFlow<tf.monochrome.android.data.downloads.TrackDownloadState> =
        currentTrack
            .flatMapLatest { track ->
                if (track == null) flowOf(tf.monochrome.android.data.downloads.TrackDownloadState())
                else downloadManager.observeDownloadState(track.id)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                tf.monochrome.android.data.downloads.TrackDownloadState()
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentTrackDownloaded: StateFlow<Boolean> =
        currentTrack
            .flatMapLatest { track ->
                if (track == null) flowOf(false)
                else libraryRepository.isDownloadedFlow(track.id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun downloadTrack(track: Track) {
        downloadManager.downloadTrack(track)
        observeTrackDownload(track.id)
    }

    fun downloadAllTracks(tracks: List<Track>) {
        downloadManager.downloadTracks(tracks)
        tracks.forEach { observeTrackDownload(it.id) }
    }

    /**
     * Hand the track's local audio file to Android's share sheet. Resolves
     * a downloaded copy first, then a Qobuz cache hit; if neither exists
     * the call is a no-op (logged in TrackShareHelper).
     */
    fun shareTrack(track: Track) {
        viewModelScope.launch {
            trackShareHelper.shareTrack(track)
        }
    }

    fun shareDownloadedTrack(entity: tf.monochrome.android.data.db.entity.DownloadedTrackEntity) {
        trackShareHelper.shareDownloadedTrack(entity)
    }

    private fun observeTrackDownload(trackId: Long) {
        viewModelScope.launch {
            downloadManager.observeDownloadState(trackId).collectLatest { state ->
                _activeDownloads.value = _activeDownloads.value.toMutableMap().apply {
                    if (state.status == tf.monochrome.android.data.downloads.DownloadStatus.IDLE) {
                        remove(trackId)
                    } else {
                        put(trackId, state)
                    }
                }
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            libraryRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    fun createPlaylist(name: String, description: String? = null) {
        viewModelScope.launch {
            libraryRepository.createPlaylist(name, description)
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onCleared()
    }

    // --- Formatting helpers ---
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
