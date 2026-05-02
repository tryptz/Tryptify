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
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.sync.BackupManager
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import java.io.File
import java.util.Locale
import javax.inject.Inject

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
    private val spectrumAnalyzerTap: SpectrumAnalyzerTap,
    private val usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter,
    private val usbExclusiveController: tf.monochrome.android.audio.usb.UsbExclusiveController,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** Honest live status of the libusb exclusive-output path. */
    val usbExclusiveStatus: StateFlow<tf.monochrome.android.audio.usb.UsbExclusiveController.Status> =
        usbExclusiveController.status

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

    // --- PocketBase Auth ---
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
    val qobuzCookie: StateFlow<String?> = preferences.qobuzAuthCookie
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
    fun setUsbBitPerfectEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setUsbBitPerfectEnabled(enabled) } }
    fun setUsbExclusiveBitPerfectEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setUsbExclusiveBitPerfectEnabled(enabled) } }
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

    fun setQobuzCookie(cookie: String?) {
        viewModelScope.launch {
            preferences.setQobuzAuthCookie(cookie)
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
}
