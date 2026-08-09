package tf.monochrome.android.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.api.Instance
import tf.monochrome.android.data.api.InstanceManager
import tf.monochrome.android.data.api.InstanceType
import tf.monochrome.android.data.auth.AuthRepository
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
    private val projectMEngineRepository: ProjectMEngineRepository,
    private val supabaseSyncRepository: SupabaseSyncRepository,
    private val supabaseAuthManager: SupabaseAuthManager,
    private val lastFmAuthManager: tf.monochrome.android.data.auth.LastFmAuthManager,
    private val spectrumAnalyzerTap: SpectrumAnalyzerTap,
    private val channelDetectorProcessor: tf.monochrome.android.audio.dsp.ChannelDetectorProcessor,
    private val usbAudioRouter: tf.monochrome.android.audio.UsbAudioRouter,
    private val usbExclusiveController: tf.monochrome.android.audio.usb.UsbExclusiveController,
    private val artworkRefreshDetector: tf.monochrome.android.data.local.scanner.ArtworkRefreshDetector,
    private val scanCoordinator: tf.monochrome.android.data.local.scanner.ScanCoordinator,
    private val downloadDao: tf.monochrome.android.data.db.dao.DownloadDao,
    private val updateChecker: tf.monochrome.android.data.update.UpdateChecker,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** True while any library scan is running — lets Settings disable the
     *  "Rescan Library Now" button and show progress. */
    val isScanning: StateFlow<Boolean> = scanCoordinator.isScanning

    /** One-shot user-facing messages (import success/failure, etc.) that the
     *  Settings screen shows as a toast. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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

    /** Live detected input format + per-channel peaks from the head of the chain. */
    val channelDetectorState: StateFlow<tf.monochrome.android.audio.dsp.ChannelDetectorProcessor.ChannelState?> =
        channelDetectorProcessor.state

    /** Reference-counted like the spectrum tap: metering runs only while shown. */
    fun acquireChannelDetector() = channelDetectorProcessor.acquire()
    fun releaseChannelDetector() = channelDetectorProcessor.release()

    // --- Appearance ---
    val theme: StateFlow<String> = preferences.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "monochrome_dark")
    val dynamicColors: StateFlow<Boolean> = preferences.dynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val fontScale: StateFlow<Float> = preferences.fontScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)
    val fontScaleFollowSystem: StateFlow<Boolean> = preferences.fontScaleFollowSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val customFontUri: StateFlow<String?> = preferences.customFontUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    // Lives inside the Lyrics FX blob (a personal field presets never touch)
    // but is surfaced in Appearance under Dynamic Colors — it's an album-art
    // glow, not a Studio material knob.
    val glowBehindArt: StateFlow<Boolean> = preferences.lyricsFx
        .map { it.glowBehindArt }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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
    /** The listener's own credentials — what scrobbling signs with, and what the field shows. */
    val lastFmApiKey: StateFlow<String> = preferences.lastFmApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val lastFmApiSecret: StateFlow<String> = preferences.lastFmApiSecret
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val lastFmConnecting: StateFlow<Boolean> = lastFmAuthManager.isConnecting
    val lastFmAuthError: StateFlow<String?> = lastFmAuthManager.errorMessage

    /** The address to paste into the Last.fm application's Callback URL field. */
    val lastFmCallbackUrl: String get() = lastFmAuthManager.callbackUrl

    /** Whether charts have a key at all — usually the bundled one, so usually true. */
    val chartsKeyAvailable: StateFlow<Boolean> = preferences.lastFmChartsApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setLastFmApiCredentials(apiKey: String, apiSecret: String) {
        viewModelScope.launch { preferences.setLastFmApiCredentials(apiKey, apiSecret) }
    }

    // --- Audio ---
    val wifiQuality: StateFlow<AudioQuality> = preferences.wifiQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioQuality.HI_RES)
    val cellularQuality: StateFlow<AudioQuality> = preferences.cellularQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioQuality.HIGH)
    val normalizationEnabled: StateFlow<Boolean> = preferences.normalizationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val dspMixerEnabled: StateFlow<Boolean> = preferences.dspEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val systemWideAutoEqEnabled: StateFlow<Boolean> = preferences.systemWideAutoEqEnabled
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
    val multichannelDownmixEnabled: StateFlow<Boolean> = preferences.multichannelDownmixEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
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
    val lyricsWordProvider: StateFlow<tf.monochrome.android.data.preferences.LyricsWordProvider> =
        preferences.lyricsWordProvider.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            tf.monochrome.android.data.preferences.LyricsWordProvider.BOTH
        )
    val lyrics3dRotation: StateFlow<Float> = preferences.lyrics3dRotation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 12f)
    val lyrics3dWaveSpeed: StateFlow<Float> = preferences.lyrics3dWaveSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
    val lyrics3dShadowDepth: StateFlow<Float> = preferences.lyrics3dShadowDepth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.7f)
    val lyricsBassReact: StateFlow<Float> = preferences.lyricsBassReact
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.8f)
    val playerDynamicColor: StateFlow<Boolean> = preferences.playerDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val playerBlurredBackground: StateFlow<Boolean> = preferences.playerBlurredBackground
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val appTargetFps: StateFlow<Int> = preferences.appTargetFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val appRenderResolution: StateFlow<Int> = preferences.appRenderResolution
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val lowPerformanceMode: StateFlow<Boolean> = preferences.lowPerformanceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val disableAnimations: StateFlow<Boolean> = preferences.disableAnimations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val legacyPlayer: StateFlow<Boolean> = preferences.legacyPlayer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val disableLiquidGlass: StateFlow<Boolean> = preferences.disableLiquidGlass
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

    /** Ensures presets are installed/loaded so the visualizer settings have data. */
    fun prepareVisualizerEngine() = projectMEngineRepository.requestPrepare()

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
    // Imported fonts, from filesDir/custom_fonts. The ten that ship in the APK
    // are a separate, constant list (BundledFonts.ALL) — they can be selected
    // but not deleted, so they don't belong in mutable state.
    private val _availableFonts = MutableStateFlow<List<File>>(emptyList())
    val availableFonts: StateFlow<List<File>> = _availableFonts.asStateFlow()

    val bundledFonts: List<tf.monochrome.android.ui.theme.BundledFont> =
        tf.monochrome.android.ui.theme.BundledFonts.ALL

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
    fun setFontScaleFollowSystem(enabled: Boolean) {
        viewModelScope.launch { preferences.setFontScaleFollowSystem(enabled) }
    }
    fun setGlowBehindArt(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferences.lyricsFx.first()
            preferences.setLyricsFx(current.copy(glowBehindArt = enabled))
        }
    }

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
                _messages.tryEmit("Font imported")
            } catch (_: Exception) {
                _messages.tryEmit("Couldn't import that font file")
            }
        }
    }

    fun selectFont(file: File) {
        viewModelScope.launch {
            preferences.setCustomFontUri(file.absolutePath)
        }
    }

    /** Select one of the fonts that ships in the APK. */
    fun selectBundledFont(font: tf.monochrome.android.ui.theme.BundledFont) {
        viewModelScope.launch {
            preferences.setCustomFontUri(tf.monochrome.android.ui.theme.BundledFonts.idOf(font))
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
    /**
     * Open Last.fm's consent page. The session key comes back through the
     * callback deep link, not from anything typed here — see [LastFmAuthManager].
     */
    fun connectLastFm(activityContext: android.content.Context) =
        lastFmAuthManager.connect(activityContext)

    fun clearLastFmSession() { viewModelScope.launch { lastFmAuthManager.disconnect() } }
    fun clearLastFmError() = lastFmAuthManager.clearError()
    fun setListenBrainzToken(token: String) { viewModelScope.launch { preferences.setListenBrainzToken(token) } }
    fun clearListenBrainzToken() { viewModelScope.launch { preferences.clearListenBrainzToken() } }

    // --- Audio actions ---
    fun setWifiQuality(quality: AudioQuality) { viewModelScope.launch { preferences.setWifiQuality(quality) } }
    fun setCellularQuality(quality: AudioQuality) { viewModelScope.launch { preferences.setCellularQuality(quality) } }
    fun setNormalizationEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setNormalizationEnabled(enabled) } }
    fun setDspMixerEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setDspEnabled(enabled) } }
    fun setSystemWideAutoEq(enabled: Boolean) { viewModelScope.launch { preferences.setSystemWideAutoEqEnabled(enabled) } }
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
    fun setMultichannelDownmixEnabled(enabled: Boolean) { viewModelScope.launch {
        preferences.setMultichannelDownmixEnabled(enabled)
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
    fun setLyricsWordProvider(mode: tf.monochrome.android.data.preferences.LyricsWordProvider) {
        viewModelScope.launch { preferences.setLyricsWordProvider(mode) }
    }
    fun setNowPlayingViewMode(mode: NowPlayingViewMode) { viewModelScope.launch { preferences.setNowPlayingViewMode(mode) } }
    fun setVisualizerEngineEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerEngineEnabled(enabled) } }
    fun setVisualizerAutoShuffle(enabled: Boolean) { viewModelScope.launch { preferences.setVisualizerAutoShuffle(enabled) } }
    fun setVisualizerRotationSeconds(seconds: Int) { viewModelScope.launch { preferences.setVisualizerRotationSeconds(seconds) } }
    fun setVisualizerTextureSize(size: Int) { viewModelScope.launch { preferences.setVisualizerTextureSize(size) } }
    fun setVisualizerMeshX(value: Int) { viewModelScope.launch { preferences.setVisualizerMeshX(value) } }
    fun setVisualizerMeshY(value: Int) { viewModelScope.launch { preferences.setVisualizerMeshY(value) } }
    fun setVisualizerTargetFps(value: Int) { viewModelScope.launch { preferences.setVisualizerTargetFps(value) } }
    fun setLyrics3dRotation(value: Float) { viewModelScope.launch { preferences.setLyrics3dRotation(value) } }
    fun setLyrics3dWaveSpeed(value: Float) { viewModelScope.launch { preferences.setLyrics3dWaveSpeed(value) } }
    fun setLyrics3dShadowDepth(value: Float) { viewModelScope.launch { preferences.setLyrics3dShadowDepth(value) } }
    fun setLyricsBassReact(value: Float) { viewModelScope.launch { preferences.setLyricsBassReact(value) } }
    fun setPlayerDynamicColor(enabled: Boolean) { viewModelScope.launch { preferences.setPlayerDynamicColor(enabled) } }
    fun setPlayerBlurredBackground(enabled: Boolean) { viewModelScope.launch { preferences.setPlayerBlurredBackground(enabled) } }
    fun setAppTargetFps(fps: Int) { viewModelScope.launch { preferences.setAppTargetFps(fps) } }
    fun setAppRenderResolution(shortSide: Int) { viewModelScope.launch { preferences.setAppRenderResolution(shortSide) } }
    // The master writes all three; each of the three re-derives the master.
    // Both directions are single DataStore transactions, so the four switches
    // are never briefly inconsistent on screen.
    fun setLowPerformanceMode(enabled: Boolean) { viewModelScope.launch { preferences.setLowPerformanceMode(enabled) } }
    fun setDisableAnimations(enabled: Boolean) { viewModelScope.launch { preferences.setDisableAnimations(enabled) } }
    fun setLegacyPlayer(enabled: Boolean) { viewModelScope.launch { preferences.setLegacyPlayer(enabled) } }
    fun setDisableLiquidGlass(enabled: Boolean) { viewModelScope.launch { preferences.setDisableLiquidGlass(enabled) } }
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
    val autoDownloadLikedSongs: StateFlow<Boolean> = preferences.autoDownloadLikedSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Only flips the flag. Downloading happens when a song is liked, so
    // switching this on can't sweep an existing Liked Songs list — see
    // LibraryRepository.autoDownloadOnLike.
    val gaplessNoResample: StateFlow<Boolean> = preferences.gaplessNoResample
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- What's New ---

    val whatsNewSeenVersion: StateFlow<Int> = preferences.whatsNewSeenVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WhatsNew.currentVersionCode)
    val whatsNewNeverShow: StateFlow<Boolean> = preferences.whatsNewNeverShow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * Whether this build's notes were still unread when Settings was opened —
     * what the "New in …" badge on the What's New header goes by.
     *
     * Latched here rather than read in the composable for two reasons.
     * [whatsNewSeenVersion] starts at an optimistic placeholder equal to the
     * current build, so a reader that samples it on first composition always
     * concludes "already read". And opening About marks the notes seen
     * immediately, which would erase the answer a moment after asking — the
     * update notice deep-links straight to that tab, so the two can genuinely
     * land in the same frame.
     */
    private val _whatsNewWasUnread = MutableStateFlow(false)
    val whatsNewWasUnread: StateFlow<Boolean> = _whatsNewWasUnread.asStateFlow()
    private var unreadLatched = false

    /** Reads the stored version once, before anything overwrites it. */
    private suspend fun latchWhatsNewUnread() {
        if (unreadLatched) return
        unreadLatched = true
        _whatsNewWasUnread.value =
            preferences.whatsNewSeenVersion.first() < WhatsNew.currentVersionCode
    }

    init {
        viewModelScope.launch { latchWhatsNewUnread() }
    }

    // --- Update availability ---

    private val _availableUpdate =
        MutableStateFlow<tf.monochrome.android.data.update.AvailableUpdate?>(null)
    val availableUpdate: StateFlow<tf.monochrome.android.data.update.AvailableUpdate?> =
        _availableUpdate.asStateFlow()

    private val updateDismissedVersion: StateFlow<String?> = preferences.updateDismissedVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * True when GitHub has a release newer than this build that the user hasn't
     * already waved away. Dismissal is per-version, so the bar comes back for
     * the *next* release rather than being silenced forever by one tap.
     */
    val showUpdateBar: StateFlow<Boolean> =
        combine(_availableUpdate, updateDismissedVersion, whatsNewNeverShow) { update, dismissed, never ->
            when {
                never -> false
                update == null -> false
                dismissed == null -> true
                else -> tf.monochrome.android.data.update.AppVersion
                    .isNewer(update.versionName, dismissed)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Cheap on start: serves the cached answer unless a day has passed. */
    fun refreshUpdateStatus(force: Boolean = false) {
        viewModelScope.launch {
            _availableUpdate.value = runCatching {
                updateChecker.check(force = force, nowMs = System.currentTimeMillis())
            }.getOrNull()
        }
    }

    /**
     * The About screen's "Check for updates" — forces a network check and says
     * what it found, since a silent no-op is a poor answer to a button press.
     */
    fun checkForUpdatesNow() {
        viewModelScope.launch {
            val found = runCatching {
                updateChecker.check(force = true, nowMs = System.currentTimeMillis())
            }.getOrNull()
            _availableUpdate.value = found
            _messages.tryEmit(
                if (found != null) "Version ${found.versionName} is available"
                else "You're on the latest version"
            )
        }
    }

    /** Hide the bar for this release only. */
    fun dismissUpdate() {
        val version = _availableUpdate.value?.versionName ?: return
        viewModelScope.launch { preferences.setUpdateDismissedVersion(version) }
    }

    /** Records that the notes for this build have been read. */
    fun markWhatsNewSeen() {
        viewModelScope.launch {
            // Never overwrite the stored version before it's been read.
            latchWhatsNewUnread()
            preferences.setWhatsNewSeenVersion(WhatsNew.currentVersionCode)
        }
    }

    /** Dismiss forever — also marks the current build seen so nothing lingers. */
    fun neverShowWhatsNew() {
        viewModelScope.launch {
            latchWhatsNewUnread()
            preferences.setWhatsNewNeverShow(true)
            preferences.setWhatsNewSeenVersion(WhatsNew.currentVersionCode)
        }
    }

    fun setGaplessNoResample(enabled: Boolean) {
        viewModelScope.launch { preferences.setGaplessNoResample(enabled) }
    }

    fun setAutoDownloadLikedSongs(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoDownloadLikedSongs(enabled) }
    }

    fun setScanOnAppOpen(enabled: Boolean) { viewModelScope.launch { preferences.setScanOnAppOpen(enabled) } }
    fun setMinTrackDuration(durationMs: Long) { viewModelScope.launch { preferences.setMinTrackDurationMs(durationMs) } }
    fun setBackgroundScanInterval(interval: String) { viewModelScope.launch { preferences.setBackgroundScanInterval(interval) } }
    fun rescanLibrary() {
        // Route through the shared ScanCoordinator (the same guard the Library
        // tab uses), so the button actually scans instead of no-op'ing.
        viewModelScope.launch { scanCoordinator.runFullScan() }
    }

    // --- Library tab order ---
    // Backed by an in-memory MutableStateFlow (mirrored from prefs) rather than
    // stateIn: moveLibraryTab used to read the prefs-backed StateFlow's .value,
    // which lags the DataStore write, so rapid up/down taps all read the same
    // stale order and lost or scrambled moves. The local flow updates
    // synchronously so successive moves compound correctly.
    private val _libraryTabOrder = MutableStateFlow(
        listOf("overview", "local", "playlists", "favorites", "downloads")
    )
    val libraryTabOrder: StateFlow<List<String>> = _libraryTabOrder.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.libraryTabOrder.collect { _libraryTabOrder.value = it }
        }
    }

    fun setLibraryTabOrder(order: List<String>) {
        _libraryTabOrder.value = order
        viewModelScope.launch { preferences.setLibraryTabOrder(order) }
    }

    fun moveLibraryTab(fromIndex: Int, toIndex: Int) {
        val current = _libraryTabOrder.value.toMutableList()
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

    /**
     * Delete every downloaded track — the actual files (SAF content:// or
     * plain paths) AND the Room rows — off the main thread. The old dialog
     * did a main-thread deleteRecursively() of only the default folder and
     * never cleared the DB, so tracks stayed listed and failed to play.
     */
    fun clearAllDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = downloadDao.getDownloadedTracks().first()
            var deleted = 0
            for (t in tracks) {
                try {
                    if (t.filePath.startsWith("content://")) {
                        androidx.documentfile.provider.DocumentFile
                            .fromSingleUri(appContext, android.net.Uri.parse(t.filePath))?.delete()
                    } else {
                        val f = File(t.filePath)
                        if (f.exists()) f.delete()
                    }
                } catch (_: Exception) { }
                downloadDao.deleteDownloadedTrack(t.id)
                deleted++
            }
            // Sweep any leftover files in the default downloads directory.
            try {
                File(appContext.getExternalFilesDir(null), "downloads").deleteRecursively()
            } catch (_: Exception) { }
            _messages.tryEmit(
                if (deleted > 0) "Deleted $deleted download${if (deleted == 1) "" else "s"}"
                else "No downloads to delete"
            )
        }
    }

    fun importLibrary(jsonStr: String) {
        viewModelScope.launch {
            Log.d("ImportSync", "Starting library import...")
            val result = backupManager.importLibrary(jsonStr)
            Log.d("ImportSync", "Import result: $result")
            if (result.isFailure) {
                // Report the real outcome instead of the old unconditional
                // "Library imported" success toast fired on a corrupt file.
                _messages.tryEmit("Import failed: invalid backup file")
                return@launch
            }
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
            _messages.tryEmit("Library imported")
        }
    }

    // Playlist imports live in SpotifyImportViewModel, which routes them
    // through SpotifyImportForegroundService — no in-ViewModel import path
    // should exist here, or a big playlist dies when the screen closes.

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
            // Recursive directory walk off the main thread — a large artwork
            // cache otherwise froze the UI / triggered an ANR.
            val size = withContext(Dispatchers.IO) { getDirSize(appContext.cacheDir) }
            _cacheSize.value = formatSize(size)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appContext.cacheDir.deleteRecursively() }
            calculateCacheSize()
            // The wipe just deleted cacheDir/artwork, which local tracks'
            // Room rows point at. Rescan now so covers come back without
            // waiting for the next app start (or a manual refresh).
            runCatching { artworkRefreshDetector.refreshIfArtworkMissing() }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preferences.clearAllData()
                appContext.cacheDir.deleteRecursively()
            }
            calculateCacheSize()
            runCatching { artworkRefreshDetector.refreshIfArtworkMissing() }
        }
    }

    /**
     * Re-enter the first-run wizard without touching any data. MainActivity
     * collects this flag, so flipping it swaps the whole tree to onboarding.
     */
    fun restartOnboarding() {
        viewModelScope.launch { preferences.setOnboardingComplete(false) }
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
