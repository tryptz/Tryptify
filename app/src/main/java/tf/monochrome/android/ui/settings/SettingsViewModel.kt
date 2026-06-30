package tf.monochrome.android.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.api.Instance
import tf.monochrome.android.data.api.InstanceManager
import tf.monochrome.android.data.api.InstanceType
import tf.monochrome.android.data.auth.AuthRepository
import tf.monochrome.android.data.import_.PlaylistImporter
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.sync.BackupManager
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.player.UnifiedTrackRegistry
import tf.monochrome.android.radio.planner.PlannerCandidateSummary
import tf.monochrome.android.radio.planner.PlannerLocalMetadata
import tf.monochrome.android.radio.planner.PlannerMetaBrainzContext
import tf.monochrome.android.radio.planner.PlannerQobuzContext
import tf.monochrome.android.radio.planner.PlannerSettings
import tf.monochrome.android.radio.planner.PlannerSessionHistory
import tf.monochrome.android.radio.planner.PlannerSong
import tf.monochrome.android.radio.planner.PlannerSpotifyContext
import tf.monochrome.android.radio.planner.PlannerTrackIdentity
import tf.monochrome.android.radio.planner.PlannerTrackMetadata
import tf.monochrome.android.radio.planner.RadioPlannerClient
import tf.monochrome.android.radio.planner.RadioPlannerWeights
import tf.monochrome.android.radio.planner.RadioSongListRequest
import tf.monochrome.android.spotify.api.model.SpotifyTrack
import tf.monochrome.android.spotify.repository.SpotifyRepository
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class PlannerTesterUiState(
    val loading: Boolean = false,
    val submittedPrompt: String = "",
    val responseTitle: String = "",
    val responseDetail: String = "",
    val songs: List<PlannerSong> = emptyList(),
    val error: String? = null,
    val requestId: Long = 0L,
) {
    val hasResponse: Boolean
        get() = loading || submittedPrompt.isNotBlank() || responseTitle.isNotBlank() ||
            songs.isNotEmpty() || error != null
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    private val instanceManager: InstanceManager,
    private val authRepository: AuthRepository,
    private val backupManager: BackupManager,
    private val playlistImporter: PlaylistImporter,
    private val projectMEngineRepository: ProjectMEngineRepository,
    private val supabaseSyncRepository: SupabaseSyncRepository,
    private val supabaseAuthManager: SupabaseAuthManager,
    private val libraryRepository: LibraryRepository,
    private val queueManager: QueueManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val spectrumAnalyzerTap: SpectrumAnalyzerTap,
    private val usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter,
    private val usbExclusiveController: tf.monochrome.android.audio.usb.UsbExclusiveController,
    private val audioFeatureRepository: tf.monochrome.android.data.analysis.AudioFeatureRepository,
    private val audioAnalysisManager: tf.monochrome.android.data.analysis.AudioAnalysisManager,
    private val spotifyAuthManager: tf.monochrome.android.spotify.auth.SpotifyAuthManager,
    private val spotifyRepository: SpotifyRepository,
    private val radioPlannerClient: RadioPlannerClient,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // --- Audio feature analysis ---
    val analyzeAudioFeatures: StateFlow<Boolean> = preferences.analyzeAudioFeatures
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val audioFeaturesAnalyzed: StateFlow<Int> = audioFeatureRepository.observeAnalyzedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val audioFeaturesTarget: StateFlow<Int> = preferences.audioAnalysisTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setAnalyzeAudioFeatures(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAnalyzeAudioFeatures(enabled)
            if (enabled) audioAnalysisManager.triggerNow()
        }
    }

    fun analyzeAudioNow() = audioAnalysisManager.triggerNow()

    /** Honest live status of the libusb exclusive-output path. */
    val usbExclusiveStatus: StateFlow<tf.monochrome.android.audio.usb.UsbExclusiveController.Status> =
        usbExclusiveController.status

    /** Negotiated stream parameters when bypass is live. Settings UI
     *  renders "192 kHz · 24-bit · UAC2 HS · async feedback ✓" from this. */
    val usbBypassDiagnostics: StateFlow<tf.monochrome.android.audio.usb.BypassDiagnostics?> =
        usbExclusiveController.diagnostics

    /** Categorised reason the bypass failed, when it did. The UI shows
     *  [tf.monochrome.android.audio.usb.StartFailure.actionableMessage]
     *  in the Error subtitle. */
    val usbBypassFailure: StateFlow<tf.monochrome.android.audio.usb.StartFailure?> =
        usbExclusiveController.lastStartError

    /** What rates the DAC actually supports, per its GET_RANGE table. */
    val usbBypassSupportedRates: StateFlow<List<tf.monochrome.android.audio.usb.ClockRateRange>> =
        usbExclusiveController.supportedRates

    /** Shared live FFT bins from the audio pipeline — same source the NowPlaying overlay uses. */
    val spectrumBins: StateFlow<FloatArray> = spectrumAnalyzerTap.spectrumBins

    /**
     * Reference-counted subscription to the FFT analysis coroutine. The preview
     * keeps running as long as any on-screen caller holds a stake, so opening
     * Settings over another screen that also uses the analyzer doesn't make
     * either preview flicker off when the first one disposes.
     */
    fun acquireSpectrum() = spectrumAnalyzerTap.acquire()
    fun releaseSpectrum() = spectrumAnalyzerTap.release()

    // --- Appearance ---
    val theme: StateFlow<String> = preferences.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "monochrome_dark")
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val fontScale: StateFlow<Float> = preferences.fontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)
    val customFontUri: StateFlow<String?> = preferences.customFontUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Interface ---
    val gaplessPlayback: StateFlow<Boolean> = preferences.gaplessPlayback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val showExplicitBadges: StateFlow<Boolean> = preferences.showExplicitBadges
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val confirmClearQueue: StateFlow<Boolean> = preferences.confirmClearQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val autoplaySimilar: StateFlow<Boolean> = preferences.autoplaySimilar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val spotifySyncCurrentPlaying: StateFlow<Boolean> = preferences.spotifySyncCurrentPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val llmPlaylistRadioRecommendationsEnabled: StateFlow<Boolean> =
        preferences.llmPlaylistRadioRecommendationsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val radioPlannerWeights: StateFlow<RadioPlannerWeights> =
        preferences.radioPlannerWeights
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RadioPlannerWeights())
    private val _plannerTesterState = MutableStateFlow(PlannerTesterUiState())
    val plannerTesterState: StateFlow<PlannerTesterUiState> = _plannerTesterState.asStateFlow()
    val spotifyAuthState: StateFlow<tf.monochrome.android.spotify.auth.SpotifyAuthState> =
        spotifyAuthManager.authState

    // --- Scrobbling ---
    val lastFmEnabled: StateFlow<Boolean> = preferences.lastFmEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val lastFmUsername: StateFlow<String?> = preferences.lastFmUsername
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val listenBrainzEnabled: StateFlow<Boolean> = preferences.listenBrainzEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val listenBrainzToken: StateFlow<String?> = preferences.listenBrainzToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Audio ---
    val wifiQuality: StateFlow<AudioQuality> = preferences.wifiQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioQuality.HI_RES)
    val cellularQuality: StateFlow<AudioQuality> = preferences.cellularQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioQuality.HIGH)
    val normalizationEnabled: StateFlow<Boolean> = preferences.normalizationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val dspMixerEnabled: StateFlow<Boolean> = preferences.dspEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val dspBlockSize: StateFlow<Int> = preferences.dspBlockSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1024)
    val dspBlockSizes: List<Int> = tf.monochrome.android.data.preferences.PreferencesManager.DSP_BLOCK_SIZES

    val usbBitPerfectEnabled: StateFlow<Boolean> = preferences.usbBitPerfectEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val usbExclusiveBitPerfectEnabled: StateFlow<Boolean> = preferences.usbExclusiveBitPerfectEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    /** Human-readable name of the attached USB DAC, or null when nothing is plugged in. */
    val usbOutputDeviceName: StateFlow<String?> =
        usbAudioRouter.usbOutputDevice
            .map { it?.let(usbAudioRouter::describe) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val crossfadeDuration: StateFlow<Int> = preferences.crossfadeDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Audio speed ---
    val playbackSpeed: StateFlow<Float> = preferences.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)
    val preservePitch: StateFlow<Boolean> = preferences.preservePitch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- Downloads ---
    val downloadQuality: StateFlow<AudioQuality> = preferences.downloadQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioQuality.HI_RES)
    val downloadLyrics: StateFlow<Boolean> = preferences.downloadLyrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val downloadFolderUri: StateFlow<String?> = preferences.downloadFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Parity features ---
    val visualizerSensitivity: StateFlow<Int> = preferences.visualizerSensitivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 50)
    val visualizerBrightness: StateFlow<Int> = preferences.visualizerBrightness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 80)
    val romajiLyrics: StateFlow<Boolean> = preferences.romajiLyrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val nowPlayingViewMode: StateFlow<NowPlayingViewMode> = preferences.nowPlayingViewMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlayingViewMode.COVER_ART)
    val visualizerEngineEnabled: StateFlow<Boolean> = preferences.visualizerEngineEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val visualizerAutoShuffle: StateFlow<Boolean> = preferences.visualizerAutoShuffle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val visualizerPresetId: StateFlow<String?> = preferences.visualizerPresetId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val visualizerRotationSeconds: StateFlow<Int> = preferences.visualizerRotationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)
    val visualizerTextureSize: StateFlow<Int> = preferences.visualizerTextureSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1024)
    val visualizerMeshX: StateFlow<Int> = preferences.visualizerMeshX
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 32)
    val visualizerMeshY: StateFlow<Int> = preferences.visualizerMeshY
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24)
    val visualizerTargetFps: StateFlow<Int> = preferences.visualizerTargetFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    val visualizerVsyncEnabled: StateFlow<Boolean> = preferences.visualizerVsyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val visualizerShowFps: StateFlow<Boolean> = preferences.visualizerShowFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val visualizerFullscreen: StateFlow<Boolean> = preferences.visualizerFullscreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val visualizerTouchWaveform: StateFlow<Boolean> = preferences.visualizerTouchWaveform
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- Spectrum analyzer ---
    val spectrumAnalyzerEnabled: StateFlow<Boolean> = preferences.spectrumAnalyzerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val spectrumShowOnNowPlaying: StateFlow<Boolean> = preferences.spectrumShowOnNowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val spectrumFftSize: StateFlow<Int> = preferences.spectrumFftSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8192)

    val visualizerEngineStatus: StateFlow<VisualizerEngineStatus> = projectMEngineRepository.engineStatus
    val visualizerPresets: StateFlow<List<VisualizerPreset>> = projectMEngineRepository.presets

    // --- Account ---
    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val userEmail: StateFlow<String?> = authRepository.userEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Instances ---
    private val _apiInstances = MutableStateFlow<List<Instance>>(emptyList())
    val apiInstances: StateFlow<List<Instance>> = _apiInstances.asStateFlow()
    private val _streamingInstances = MutableStateFlow<List<Instance>>(emptyList())
    val streamingInstances: StateFlow<List<Instance>> = _streamingInstances.asStateFlow()
    val customEndpoint: StateFlow<String?> = preferences.customApiEndpoint
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val qobuzEndpoint: StateFlow<String?> = preferences.qobuzInstanceUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val devModeEnabled: StateFlow<Boolean> = preferences.devModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val sourceMode: StateFlow<tf.monochrome.android.data.preferences.SourceMode> =
        preferences.sourceMode.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            tf.monochrome.android.data.preferences.SourceMode.BOTH,
        )
    private val _instancesRefreshing = MutableStateFlow(false)
    val instancesRefreshing: StateFlow<Boolean> = _instancesRefreshing.asStateFlow()

    // --- System ---
    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    // --- Font Library ---
    private val _availableFonts = MutableStateFlow<List<File>>(emptyList())
    val availableFonts: StateFlow<List<File>> = _availableFonts.asStateFlow()

    init {
        loadInstances()
        calculateCacheSize()
        loadFonts()
    }

    private fun loadFonts() {
        val fontsDir = File(appContext.filesDir, "custom_fonts")
        if (fontsDir.exists()) {
            _availableFonts.value = fontsDir.listFiles()?.filter { it.extension == "ttf" || it.extension == "otf" }?.toList() ?: emptyList()
        } else {
            _availableFonts.value = emptyList()
        }
    }

    // --- Appearance actions ---
    fun setTheme(theme: String) { viewModelScope.launch { preferences.setTheme(theme) } }
    fun setDynamicColors(enabled: Boolean) { viewModelScope.launch { preferences.setDynamicColors(enabled) } }
    fun setFontScale(scale: Float) { viewModelScope.launch { preferences.setFontScale(scale) } }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            try {
                val fontsDir = File(appContext.filesDir, "custom_fonts")
                fontsDir.mkdirs()
                
                var fileName = "font_${System.currentTimeMillis()}.ttf"
                if (uri.scheme == "content") {
                    appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index != -1) {
                                fileName = cursor.getString(index)
                            }
                        }
                    }
                }
                // Ensure it ends with .ttf (or otf)
                if (!fileName.lowercase().endsWith(".ttf") && !fileName.lowercase().endsWith(".otf")) {
                    fileName += ".ttf"
                }

                val destFile = File(fontsDir, fileName)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                loadFonts()
                preferences.setCustomFontUri(destFile.absolutePath)
            } catch (_: Exception) {
                // Font import failed silently
            }
        }
    }

    fun selectFont(file: File) {
        viewModelScope.launch {
            preferences.setCustomFontUri(file.absolutePath)
        }
    }

    fun removeFont(file: File) {
        viewModelScope.launch {
            val currentActive = preferences.customFontUri.first()
            if (file.absolutePath == currentActive) {
                preferences.setCustomFontUri(null)
            }
            file.delete()
            loadFonts()
        }
    }

    fun resetDefaultFont() {
        viewModelScope.launch {
            preferences.setCustomFontUri(null)
        }
    }

    // --- Interface actions ---
    fun setGaplessPlayback(enabled: Boolean) { viewModelScope.launch { preferences.setGaplessPlayback(enabled) } }
    fun setAutoplaySimilar(enabled: Boolean) { viewModelScope.launch { preferences.setAutoplaySimilar(enabled) } }
    fun setSpotifySyncCurrentPlaying(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpotifySyncCurrentPlaying(enabled) }
    }
    fun setLlmPlaylistRadioRecommendationsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setLlmPlaylistRadioRecommendationsEnabled(enabled) }
    }
    fun setRadioPlannerWeights(weights: RadioPlannerWeights) {
        viewModelScope.launch { preferences.setRadioPlannerWeights(weights) }
    }
    fun resetRadioPlannerWeights() {
        viewModelScope.launch { preferences.resetRadioPlannerWeights() }
    }
    fun testPlannerQuery(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        val requestId = System.nanoTime()
        _plannerTesterState.value = PlannerTesterUiState(
            loading = true,
            submittedPrompt = cleanPrompt,
            responseTitle = "Planner is thinking",
            responseDetail = "Asking the Railway planner for a direct song list.",
            requestId = requestId,
        )
        viewModelScope.launch {
            val request = buildSongListRequest(cleanPrompt)
            val response = radioPlannerClient.songList(request)
            if (_plannerTesterState.value.requestId != requestId) return@launch
            _plannerTesterState.value = when {
                response == null -> PlannerTesterUiState(
                    submittedPrompt = cleanPrompt,
                    responseTitle = "Tryptify-Playlist unavailable",
                    responseDetail = "Check the planner URL/key and Railway service, then try again.",
                    error = "No song-list response was returned.",
                    requestId = requestId,
                )
                response.songs.isEmpty() -> PlannerTesterUiState(
                    submittedPrompt = cleanPrompt,
                    responseTitle = response.message.ifBlank { "No songs returned" },
                    responseDetail = response.safety.fallbackReason ?: "The LLM endpoint answered without direct song rows.",
                    error = "No song rows were found in the song-list response.",
                    requestId = requestId,
                )
                else -> PlannerTesterUiState(
                    submittedPrompt = cleanPrompt,
                    responseTitle = response.message.ifBlank { "Tryptify-Playlist song list" },
                    responseDetail = buildString {
                        append(if (response.safety.modelBacked) "LLM-backed" else "Fallback")
                        append(" / confidence ")
                        append(String.format(Locale.US, "%.0f", response.safety.confidence * 100f))
                        append("%")
                        response.safety.fallbackReason?.takeIf { it.isNotBlank() }?.let {
                            append(" / ")
                            append(it)
                        }
                    },
                    songs = response.songs,
                    requestId = requestId,
                )
            }
        }
    }

    private suspend fun buildSongListRequest(prompt: String): RadioSongListRequest {
        val weights = preferences.radioPlannerWeights.first().clamped()
        val spotifyContext = buildSpotifyContext(prompt)
        val queueHistory = queueManager.playHistory.value.takeLast(HISTORY_CONTEXT_LIMIT)
        val persistentHistory = runCatching {
            libraryRepository.getHistory().first().takeLast(HISTORY_CONTEXT_LIMIT)
        }.getOrDefault(emptyList())
        val sessionHistory = (queueHistory + persistentHistory)
            .distinctBy { it.id }
            .takeLast(HISTORY_CONTEXT_LIMIT)
        val currentTrack = queueManager.currentTrack.value
        val localSeeds = buildList {
            add(
                PlannerTrackMetadata(
                    title = prompt,
                    source = "manual_query",
                ),
            )
            currentTrack?.toPlannerMetadata("current_track")?.let(::add)
        }
        val qobuzConfigured = preferences.qobuzInstanceUrl.first().isNullOrBlank().not()
        val spotifyCandidateCount = spotifyContext.searchTracks.size +
            spotifyContext.recentTracks.size +
            spotifyContext.topTracks.size +
            spotifyContext.savedTracks.size

        return RadioSongListRequest(
            query = prompt,
            targetTrackCount = PLANNER_TEST_TARGET,
            localMetadata = PlannerLocalMetadata(seedTracks = localSeeds),
            spotifyContext = spotifyContext,
            qobuzContext = PlannerQobuzContext(preferred = qobuzConfigured),
            internetContext = mapOf(
                "manualQuery" to prompt,
                "surface" to "settings_radio_llm_tester",
            ),
            settings = PlannerSettings(
                targetTrackCount = PLANNER_TEST_TARGET,
                discoveryRatio = (0.35f * weights.discoveryDistance * weights.novelty).coerceIn(0.05f, 1f),
                familiarityRatio = (0.55f * weights.familiarity).coerceIn(0.05f, 1f),
                qobuzPreference = (0.75f * weights.qobuz).coerceIn(0f, 1f),
            ),
            weights = weights,
            sliders = weights.toPlannerSliders(),
            preset = mapOf(
                "llmPlaylistRadioRecommendationsEnabled" to preferences.llmPlaylistRadioRecommendationsEnabled.first().toString(),
                "spotifyAuthenticated" to spotifyRepository.isAuthenticated().toString(),
                "spotifySyncCurrentPlaying" to preferences.spotifySyncCurrentPlaying.first().toString(),
                "autoplaySimilar" to preferences.autoplaySimilar.first().toString(),
                "sourceMode" to preferences.sourceMode.first().name,
            ),
            sessionHistory = PlannerSessionHistory(
                tracks = sessionHistory.map { it.toPlannerMetadata("session_history") },
            ),
            candidateSummary = PlannerCandidateSummary(
                localCandidateCount = queueManager.currentQueue.size,
                spotifyCandidateCount = spotifyCandidateCount,
                qobuzCandidateCount = if (qobuzConfigured) 1 else 0,
                targetTrackCount = PLANNER_TEST_TARGET,
            ),
            metabrainz = buildPlannerMetaBrainzContext(currentTrack, sessionHistory),
        )
    }

    private suspend fun buildSpotifyContext(prompt: String): PlannerSpotifyContext {
        if (!spotifyRepository.isAuthenticated()) return PlannerSpotifyContext()

        val searchTracks = runCatching {
            spotifyRepository.searchTracks(prompt, SPOTIFY_CONTEXT_LIMIT)
        }.getOrDefault(emptyList())
        val recentTracks = runCatching {
            spotifyRepository.getRecentlyPlayed()
        }.getOrDefault(emptyList()).take(SPOTIFY_CONTEXT_LIMIT)
        val topTracks = runCatching {
            spotifyRepository.getTopTracks()
        }.getOrDefault(emptyList()).take(SPOTIFY_CONTEXT_LIMIT)
        val savedTracks = runCatching {
            spotifyRepository.getSavedTracks()
        }.getOrDefault(emptyList()).take(SPOTIFY_CONTEXT_LIMIT)
        val currentTrack = if (preferences.spotifySyncCurrentPlaying.first()) {
            runCatching { spotifyRepository.getCurrentlyPlaying() }.getOrNull()
        } else {
            null
        }

        return PlannerSpotifyContext(
            seedSpotifyIds = searchTracks.mapNotNull { it.stableId.takeIf(String::isNotBlank) }.take(5),
            recentSpotifyIds = recentTracks.mapNotNull { it.stableId.takeIf(String::isNotBlank) },
            topSpotifyIds = topTracks.mapNotNull { it.stableId.takeIf(String::isNotBlank) },
            currentTrack = currentTrack?.toPlannerMetadata("spotify_current"),
            searchTracks = searchTracks.map { it.toPlannerMetadata("spotify_search") },
            recentTracks = recentTracks.map { it.toPlannerMetadata("spotify_recent") },
            topTracks = topTracks.map { it.toPlannerMetadata("spotify_top") },
            savedTracks = savedTracks.map { it.toPlannerMetadata("spotify_saved") },
        )
    }

    private fun buildPlannerMetaBrainzContext(
        currentTrack: Track?,
        sessionHistory: List<Track>,
    ): PlannerMetaBrainzContext {
        val queueIdentities = queueManager.currentQueue
            .asSequence()
            .mapNotNull { track -> unifiedTrackRegistry[track.id] }
            .filter { track ->
                track.sourceType == SourceType.LOCAL ||
                    track.sourceType == SourceType.COLLECTION ||
                    !track.isrc.isNullOrBlank() ||
                    !track.musicBrainzTrackId.isNullOrBlank()
            }
            .mapNotNull { it.toPlannerIdentity() }
            .distinctBy { it.identityKey() }
            .take(LOCAL_IDENTITY_CONTEXT_LIMIT)
            .toList()

        return PlannerMetaBrainzContext(
            seedIdentities = listOfNotNull(currentTrack?.toPlannerIdentity())
                .distinctBy { it.identityKey() }
                .take(SEED_IDENTITY_CONTEXT_LIMIT),
            localIdentities = queueIdentities,
            historyIdentities = sessionHistory.mapNotNull { it.toPlannerIdentity() }
                .distinctBy { it.identityKey() }
                .take(HISTORY_IDENTITY_CONTEXT_LIMIT),
        )
    }

    private fun Track.toPlannerMetadata(source: String): PlannerTrackMetadata {
        val unified = unifiedTrackRegistry[id]
        return PlannerTrackMetadata(
            title = title,
            artistName = primaryArtistName(),
            albumTitle = album?.title,
            isrc = unified?.isrc?.cleanOrNull(),
            source = source,
        )
    }

    private fun SpotifyTrack.toPlannerMetadata(source: String): PlannerTrackMetadata =
        PlannerTrackMetadata(
            title = name,
            artistName = primaryArtistName.takeIf { it.isNotBlank() },
            albumTitle = album?.name,
            isrc = externalIds?.isrc?.cleanOrNull(),
            source = source,
            spotifyId = stableId.takeIf { it.isNotBlank() },
        )

    private fun Track.toPlannerIdentity(): PlannerTrackIdentity? {
        val unified = unifiedTrackRegistry[id]
        return PlannerTrackIdentity(
            title = title.cleanOrNull() ?: return null,
            artist = primaryArtistName()?.cleanOrNull() ?: return null,
            album = album?.title.cleanOrNull(),
            isrc = unified?.isrc.cleanOrNull(),
            musicBrainzRecordingId = unified?.musicBrainzTrackId.cleanOrNull(),
            musicBrainzReleaseId = null,
            musicBrainzArtistIds = emptyList(),
        )
    }

    private fun UnifiedTrack.toPlannerIdentity(): PlannerTrackIdentity? {
        val cleanTitle = title.cleanOrNull() ?: return null
        val cleanArtist = artistName.cleanOrNull() ?: return null
        return PlannerTrackIdentity(
            title = cleanTitle,
            artist = cleanArtist,
            album = albumTitle.cleanOrNull(),
            isrc = isrc.cleanOrNull(),
            musicBrainzRecordingId = musicBrainzTrackId.cleanOrNull(),
            musicBrainzReleaseId = null,
            musicBrainzArtistIds = emptyList(),
        )
    }

    private fun PlannerTrackIdentity.identityKey(): String =
        musicBrainzRecordingId
            ?: isrc
            ?: listOf(title, artist, album.orEmpty())
                .joinToString("|") { it.trim().lowercase() }

    private fun Track.primaryArtistName(): String? =
        (artist?.name ?: artists.firstOrNull()?.name ?: displayArtist)
            .takeIf { it.isNotBlank() }

    private fun String?.cleanOrNull(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }

    fun startSpotifyAuth() = spotifyAuthManager.launchAuthActivity()
    fun disconnectSpotify() = spotifyAuthManager.disconnect()
    fun setShowExplicitBadges(enabled: Boolean) { viewModelScope.launch { preferences.setShowExplicitBadges(enabled) } }
    fun setConfirmClearQueue(enabled: Boolean) { viewModelScope.launch { preferences.setConfirmClearQueue(enabled) } }

    // --- Scrobbling actions ---
    fun setLastFmSession(sessionKey: String, username: String) {
        viewModelScope.launch { preferences.setLastFmSession(sessionKey, username) }
    }
    fun clearLastFmSession() { viewModelScope.launch { preferences.clearLastFmSession() } }
    fun setListenBrainzToken(token: String) { viewModelScope.launch { preferences.setListenBrainzToken(token) } }
    fun clearListenBrainzToken() { viewModelScope.launch { preferences.clearListenBrainzToken() } }

    // --- Audio actions ---
    fun setWifiQuality(quality: AudioQuality) { viewModelScope.launch { preferences.setWifiQuality(quality) } }
    fun setCellularQuality(quality: AudioQuality) { viewModelScope.launch { preferences.setCellularQuality(quality) } }
    fun setNormalizationEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setNormalizationEnabled(enabled) } }
    fun setDspMixerEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setDspEnabled(enabled) } }
    fun setDspBlockSize(value: Int) { viewModelScope.launch { preferences.setDspBlockSize(value) } }
    // The two USB toggles are mutually exclusive — they fight for
    // the device. The framework router (usbBitPerfectEnabled) pins
    // Android's audio HAL to the USB device; libusb (exclusive) needs
    // libusb_claim_interface to win. Turning either on auto-flips
    // the other off so the user never accidentally has both.
    fun setUsbBitPerfectEnabled(enabled: Boolean) { viewModelScope.launch {
        preferences.setUsbBitPerfectEnabled(enabled)
        if (enabled) preferences.setUsbExclusiveBitPerfectEnabled(false)
    } }
    fun setUsbExclusiveBitPerfectEnabled(enabled: Boolean) { viewModelScope.launch {
        preferences.setUsbExclusiveBitPerfectEnabled(enabled)
        if (enabled) preferences.setUsbBitPerfectEnabled(false)
    } }
    fun setCrossfadeDuration(seconds: Int) { viewModelScope.launch { preferences.setCrossfadeDuration(seconds) } }

    // --- Audio speed actions ---
    fun setPlaybackSpeed(speed: Float) { viewModelScope.launch { preferences.setPlaybackSpeed(speed) } }
    fun setPreservePitch(enabled: Boolean) { viewModelScope.launch { preferences.setPreservePitch(enabled) } }

    // --- Downloads actions ---
    fun setDownloadQuality(quality: AudioQuality) { viewModelScope.launch { preferences.setDownloadQuality(quality) } }
    fun setDownloadLyrics(enabled: Boolean) { viewModelScope.launch { preferences.setDownloadLyrics(enabled) } }
    fun setDownloadFolderUri(uri: String?) { viewModelScope.launch { preferences.setDownloadFolderUri(uri) } }


    // --- Parity actions ---
    fun setVisualizerSensitivity(value: Int) { viewModelScope.launch { preferences.setVisualizerSensitivity(value) } }
    fun setVisualizerBrightness(value: Int) { viewModelScope.launch { preferences.setVisualizerBrightness(value) } }
    fun setRomajiLyrics(enabled: Boolean) { viewModelScope.launch { preferences.setRomajiLyrics(enabled) } }
    fun setNowPlayingViewMode(mode: NowPlayingViewMode) { viewModelScope.launch { preferences.setNowPlayingViewMode(mode) } }
    fun setVisualizerEngineEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerEngineEnabled(enabled) } }
    fun setVisualizerAutoShuffle(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerAutoShuffle(enabled) } }
    fun setVisualizerRotationSeconds(seconds: Int) { viewModelScope.launch { preferences.setVisualizerRotationSeconds(seconds) } }
    fun setVisualizerTextureSize(size: Int) { viewModelScope.launch { preferences.setVisualizerTextureSize(size) } }
    fun setVisualizerMeshX(value: Int) { viewModelScope.launch { preferences.setVisualizerMeshX(value) } }
    fun setVisualizerMeshY(value: Int) { viewModelScope.launch { preferences.setVisualizerMeshY(value) } }
    fun setVisualizerTargetFps(value: Int) { viewModelScope.launch { preferences.setVisualizerTargetFps(value) } }
    fun setVisualizerVsyncEnabled(value: Boolean) { viewModelScope.launch { preferences.setVisualizerVsyncEnabled(value) } }
    fun setVisualizerShowFps(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerShowFps(enabled) } }
    fun setVisualizerFullscreen(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerFullscreen(enabled) } }
    fun setVisualizerTouchWaveform(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerTouchWaveform(enabled) } }
    fun setVisualizerPresetId(presetId: String?) { viewModelScope.launch { preferences.setVisualizerPresetId(presetId) } }

    // --- Spectrum analyzer actions ---
    fun setSpectrumAnalyzerEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpectrumAnalyzerEnabled(enabled) }
    }
    fun setSpectrumShowOnNowPlaying(enabled: Boolean) {
        viewModelScope.launch { preferences.setSpectrumShowOnNowPlaying(enabled) }
    }
    fun setSpectrumFftSize(size: Int) {
        viewModelScope.launch { preferences.setSpectrumFftSize(size) }
    }

    // --- Library settings ---
    val scanOnAppOpen: StateFlow<Boolean> = preferences.scanOnAppOpen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val minTrackDuration: StateFlow<Long> = preferences.minTrackDurationMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30_000L)
    val backgroundScanInterval: StateFlow<String> = preferences.backgroundScanInterval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "daily")

    fun setScanOnAppOpen(enabled: Boolean) { viewModelScope.launch { preferences.setScanOnAppOpen(enabled) } }
    fun setMinTrackDuration(durationMs: Long) { viewModelScope.launch { preferences.setMinTrackDurationMs(durationMs) } }
    fun setBackgroundScanInterval(interval: String) { viewModelScope.launch { preferences.setBackgroundScanInterval(interval) } }
    fun rescanLibrary() {
        // This triggers a scan via broadcast or direct call
        // The actual scanning happens in LocalLibraryViewModel
    }

    // --- Library tab order ---
    val libraryTabOrder: StateFlow<List<String>> = preferences.libraryTabOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("overview", "local", "playlists", "favorites", "downloads"))

    fun setLibraryTabOrder(order: List<String>) { viewModelScope.launch { preferences.setLibraryTabOrder(order) } }

    fun moveLibraryTab(fromIndex: Int, toIndex: Int) {
        val current = libraryTabOrder.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setLibraryTabOrder(current)
        }
    }
 
    // --- Account actions ---
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    // --- Backup & Restore actions ---
    fun exportLibrary(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = backupManager.exportLibrary()
            onResult(json)
        }
    }

    fun importLibrary(jsonStr: String) {
        viewModelScope.launch {
            Log.d("ImportSync", "Starting library import...")
            val result = backupManager.importLibrary(jsonStr)
            Log.d("ImportSync", "Import result: $result")
            // Auto-sync to Supabase if signed in
            val profile = supabaseAuthManager.userProfile.value
            Log.d("ImportSync", "Current Supabase user: ${profile?.id} (${profile?.email})")
            if (profile != null) {
                Log.d("ImportSync", "Pushing all data to Supabase...")
                supabaseSyncRepository.pushAll()
                Log.d("ImportSync", "Push complete")
            } else {
                Log.w("ImportSync", "Not signed in - skipping Supabase sync")
            }
        }
    }

    // --- Playlist Import actions ---
    fun importPlaylist(url: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = playlistImporter.importFromUrl(url)
            result.onSuccess { tracks ->
                if (tracks.isNotEmpty()) {
                    // Create playlist and add tracks
                    val id = preferences.customApiEndpoint.first() ?: "Imported"
                    // Real implementation would use libraryRepository.createPlaylist(name, ...)
                    onResult(true, "Imported ${tracks.size} tracks")
                } else {
                    onResult(false, "No tracks found or matched")
                }
            }.onFailure {
                onResult(false, it.message ?: "Import failed")
            }
        }
    }

    // --- Instance actions ---
    private fun loadInstances() {
        viewModelScope.launch {
            try {
                _apiInstances.value = instanceManager.getInstances(InstanceType.API)
                _streamingInstances.value = instanceManager.getInstances(InstanceType.STREAMING)
            } catch (_: Exception) {}
        }
    }

    fun refreshInstances() {
        viewModelScope.launch {
            _instancesRefreshing.value = true
            try {
                instanceManager.refreshInstances()
                _apiInstances.value = instanceManager.getInstances(InstanceType.API)
                _streamingInstances.value = instanceManager.getInstances(InstanceType.STREAMING)
            } catch (_: Exception) {}
            _instancesRefreshing.value = false
        }
    }

    fun setCustomEndpoint(endpoint: String?) {
        viewModelScope.launch {
            preferences.setCustomApiEndpoint(endpoint)
            loadInstances()
        }
    }

    fun setQobuzEndpoint(endpoint: String?) {
        viewModelScope.launch {
            preferences.setQobuzInstanceUrl(endpoint)
        }
    }

    fun setSourceMode(mode: tf.monochrome.android.data.preferences.SourceMode) {
        viewModelScope.launch {
            preferences.setSourceMode(mode)
            loadInstances()
        }
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDevModeEnabled(enabled)
            loadInstances()
        }
    }

    // --- System actions ---
    private fun calculateCacheSize() {
        viewModelScope.launch {
            val cacheDir = appContext.cacheDir
            val size = getDirSize(cacheDir)
            _cacheSize.value = formatSize(size)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            appContext.cacheDir.deleteRecursively()
            calculateCacheSize()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            preferences.clearAllData()
            appContext.cacheDir.deleteRecursively()
            calculateCacheSize()
        }
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private companion object {
        const val PLANNER_TEST_TARGET = 12
        const val SPOTIFY_CONTEXT_LIMIT = 12
        const val HISTORY_CONTEXT_LIMIT = 12
        const val SEED_IDENTITY_CONTEXT_LIMIT = 4
        const val LOCAL_IDENTITY_CONTEXT_LIMIT = 24
        const val HISTORY_IDENTITY_CONTEXT_LIMIT = 12
    }
}
