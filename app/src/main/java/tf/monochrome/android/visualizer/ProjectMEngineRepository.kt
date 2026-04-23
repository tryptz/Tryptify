package tf.monochrome.android.visualizer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.VisualizerEnginePhase
import tf.monochrome.android.domain.model.VisualizerEngineStatus
import tf.monochrome.android.domain.model.VisualizerPreset

@Singleton
class ProjectMEngineRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
    val audioBus: ProjectMAudioBus,
    private val assetInstaller: ProjectMAssetInstaller,
    private val presetCatalog: ProjectMPresetCatalog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineLock = Any()
    private val nativeBridge = ProjectMNativeBridge()

    private val _engineStatus = MutableStateFlow(
        VisualizerEngineStatus(
            phase = if (ProjectMNativeBridge.isLibraryLoaded) VisualizerEnginePhase.READY else VisualizerEnginePhase.FALLBACK,
            nativeLibraryLoaded = ProjectMNativeBridge.isLibraryLoaded,
            message = if (ProjectMNativeBridge.isLibraryLoaded) {
                "projectM native bridge loaded."
            } else {
                "Native projectM bridge unavailable. Using fallback visualizer."
            }
        )
    )
    val engineStatus: StateFlow<VisualizerEngineStatus> = _engineStatus.asStateFlow()

    private val _presets = MutableStateFlow<List<VisualizerPreset>>(emptyList())
    val presets: StateFlow<List<VisualizerPreset>> = _presets.asStateFlow()

    private val _currentPreset = MutableStateFlow<VisualizerPreset?>(null)
    val currentPreset: StateFlow<VisualizerPreset?> = _currentPreset.asStateFlow()

    private val _autoShuffle = MutableStateFlow(true)
    val autoShuffle: StateFlow<Boolean> = _autoShuffle.asStateFlow()

    private val _engineEnabled = MutableStateFlow(true)
    val engineEnabled: StateFlow<Boolean> = _engineEnabled.asStateFlow()

    private val _favoritePresetIds = MutableStateFlow<Set<String>>(emptySet())
    val favoritePresetIds: StateFlow<Set<String>> = _favoritePresetIds.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    private var installedAssets: InstalledProjectMAssets? = null
    private var textureSize: Int = 1024
    private var meshX: Int = 32
    private var meshY: Int = 24
    private var targetFps: Int = 60
    private var preferredPresetId: String? = null
    private var beatSensitivity: Int = 50
    private var brightness: Int = 80
    private var rotationSeconds: Int = 20
    private var playbackPaused: Boolean = false

    // Track whether there is an active GL surface. Only the most recent
    // onSurfaceAttached call owns the native bridge. All others become no-ops.
    private var attachedSurfaceCount: Int = 0
    private var nativeInitialized: Boolean = false

    private var lastPcmTimestampMs: Long = 0L
    private var fpsFrameCount = 0
    private var fpsStartTimeMs = 0L

    init {
        observePreferences()
        scope.launch(Dispatchers.IO) {
            prepareEngine()
        }
    }

    private fun observePreferences() {
        scope.launch {
            var heldSubscription = false
            preferences.visualizerEngineEnabled.collectLatest { enabled ->
                _engineEnabled.value = enabled
                // Acquire/release the audio bus subscription so the audio render
                // thread skips the per-frame PCM→float conversion when the engine
                // is disabled in settings.
                if (enabled && !heldSubscription) {
                    audioBus.acquire()
                    heldSubscription = true
                } else if (!enabled && heldSubscription) {
                    audioBus.release()
                    heldSubscription = false
                }
                synchronized(engineLock) {
                    if (!enabled) {
                        releaseNativeLocked()
                        updateStatus(
                            phase = VisualizerEnginePhase.FALLBACK,
                            message = "projectM disabled in settings. Showing fallback visualizer."
                        )
                    } else if (ProjectMNativeBridge.isLibraryLoaded) {
                        updateStatus(
                            phase = if (nativeInitialized) VisualizerEnginePhase.READY else _engineStatus.value.phase,
                            message = "projectM enabled and ready."
                        )
                    }
                }
            }
        }
        scope.launch {
            preferences.visualizerAutoShuffle.collectLatest { enabled ->
                _autoShuffle.value = enabled
                synchronized(engineLock) {
                    if (nativeInitialized) {
                        nativeBridge.setPresetShuffleEnabled(enabled)
                    }
                }
            }
        }
        scope.launch {
            preferences.visualizerPresetId.collectLatest { presetId ->
                preferredPresetId = presetId
                val selected = _presets.value.firstOrNull { it.id == presetId }
                if (selected != null) {
                    _currentPreset.value = selected
                    synchronized(engineLock) {
                        if (nativeInitialized) {
                            nativeBridge.setPreset(resolveAbsolutePresetPath(selected))
                        }
                    }
                }
            }
        }
        scope.launch {
            preferences.visualizerTextureSize.collectLatest { size ->
                textureSize = size
            }
        }
        scope.launch {
            preferences.visualizerMeshX.collectLatest { x ->
                meshX = x
                synchronized(engineLock) {
                    if (nativeInitialized) nativeBridge.configureQuality(meshX, meshY)
                }
            }
        }
        scope.launch {
            preferences.visualizerMeshY.collectLatest { y ->
                meshY = y
                synchronized(engineLock) {
                    if (nativeInitialized) nativeBridge.configureQuality(meshX, meshY)
                }
            }
        }
        scope.launch {
            preferences.visualizerTargetFps.collectLatest { fps ->
                targetFps = fps
                synchronized(engineLock) {
                    if (nativeInitialized) nativeBridge.configureTargetFps(targetFps)
                }
            }
        }
        scope.launch {
            preferences.visualizerSensitivity.collectLatest { value ->
                beatSensitivity = value
                synchronized(engineLock) {
                    if (nativeInitialized) nativeBridge.setBeatSensitivity(value)
                }
            }
        }
        scope.launch {
            preferences.visualizerBrightness.collectLatest { value ->
                brightness = value
                synchronized(engineLock) {
                    if (nativeInitialized) nativeBridge.setBrightness(value)
                }
            }
        }
        scope.launch {
            preferences.visualizerRotationSeconds.collectLatest { seconds ->
                rotationSeconds = seconds
                synchronized(engineLock) {
                    if (nativeInitialized) applyRotationLocked()
                }
            }
        }
        scope.launch {
            preferences.visualizerFavoritePresets.collectLatest { ids ->
                _favoritePresetIds.value = ids
            }
        }
    }

    fun prepareEngine() {
        synchronized(engineLock) {
            if (!_engineEnabled.value || !ProjectMNativeBridge.isLibraryLoaded) return
            ensureAssetsLocked()
        }
    }

    /**
     * Called from the GL thread when a new surface is ready.
     * Releases any existing native instance first (since it's tied to the
     * previous EGL context), then re-initializes on the current GL thread.
     */
    fun onSurfaceAttached(width: Int, height: Int) {
        synchronized(engineLock) {
            attachedSurfaceCount += 1

            if (!_engineEnabled.value || !ProjectMNativeBridge.isLibraryLoaded) {
                updateStatus(
                    phase = VisualizerEnginePhase.FALLBACK,
                    message = "Fallback visualizer active."
                )
                return
            }

            ensureAssetsLocked()
            val assets = installedAssets ?: return

            // Always release + re-create when a new GL surface attaches.
            // The old EGL context is gone; the native handle is invalid.
            releaseNativeLocked()

            val initialized = nativeBridge.initialize(assets.rootDir.absolutePath, width, height, meshX, meshY)
            if (!initialized) {
                updateStatus(
                    phase = VisualizerEnginePhase.ERROR,
                    message = "projectM failed to initialize. Showing fallback visualizer."
                )
                return
            }

            nativeInitialized = true
            nativeBridge.configureQuality(meshX, meshY)
            nativeBridge.configureTargetFps(targetFps)
            nativeBridge.setPresetShuffleEnabled(_autoShuffle.value)
            nativeBridge.setBeatSensitivity(beatSensitivity)
            nativeBridge.setBrightness(brightness)
            applyRotationLocked()
            applyPreferredPresetLocked()
            updateStatus(
                phase = VisualizerEnginePhase.READY,
                message = "projectM surface ready."
            )
        }
    }

    fun onSurfaceResized(width: Int, height: Int) {
        synchronized(engineLock) {
            if (nativeInitialized) {
                nativeBridge.resize(width, height)
            }
        }
    }

    /**
     * Called from the GL thread when the surface is about to be destroyed.
     */
    fun onSurfaceDetached() {
        synchronized(engineLock) {
            attachedSurfaceCount = (attachedSurfaceCount - 1).coerceAtLeast(0)
            if (attachedSurfaceCount == 0) {
                releaseNativeLocked()
                updateStatus(
                    phase = if (ProjectMNativeBridge.isLibraryLoaded && _engineEnabled.value) {
                        VisualizerEnginePhase.READY
                    } else {
                        VisualizerEnginePhase.FALLBACK
                    },
                    message = if (_engineEnabled.value) {
                        "projectM ready for the next visualizer session."
                    } else {
                        "Fallback visualizer active."
                    }
                )
            }
        }
    }

    fun renderFrame(frameTimeNanos: Long) {
        synchronized(engineLock) {
            if (!_engineEnabled.value || !nativeInitialized) return
            val frames = audioBus.drainAll()
            if (frames.isNotEmpty()) {
                lastPcmTimestampMs = frames.last().timestampMs
                for (frame in frames) {
                    nativeBridge.pushPcm(frame.samples, frame.channelCount, frame.sampleRate)
                }
            }
            val freezeFrame = playbackPaused && (System.currentTimeMillis() - lastPcmTimestampMs) > 2_000L
            if (!freezeFrame) {
                nativeBridge.renderFrame(frameTimeNanos)
                updateStatus(
                    phase = VisualizerEnginePhase.ACTIVE,
                    message = "projectM rendering bundled presets."
                )

                fpsFrameCount++
                val now = System.currentTimeMillis()
                if (now - fpsStartTimeMs >= 1000) {
                    _currentFps.value = (fpsFrameCount * 1000L / (now - fpsStartTimeMs)).toInt()
                    fpsFrameCount = 0
                    fpsStartTimeMs = now
                }
            }
        }
    }

    fun setPlaybackPaused(paused: Boolean) {
        playbackPaused = paused
        synchronized(engineLock) {
            if (nativeInitialized) nativeBridge.setPaused(paused)
        }
    }

    fun nextPreset() {
        synchronized(engineLock) {
            if (!nativeInitialized) return
            val currentPath = nativeBridge.nextPreset() ?: return
            updateCurrentPresetFromPathLocked(currentPath)
            scope.launch {
                preferences.setVisualizerPresetId(_currentPreset.value?.id)
            }
        }
    }

    fun selectPreset(preset: VisualizerPreset) {
        preferredPresetId = preset.id
        _currentPreset.value = preset
        scope.launch {
            preferences.setVisualizerPresetId(preset.id)
        }
        synchronized(engineLock) {
            if (nativeInitialized) {
                nativeBridge.setPreset(resolveAbsolutePresetPath(preset))
            }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setVisualizerAutoShuffle(enabled)
        }
    }

    fun toggleFavoritePreset(presetId: String) {
        scope.launch {
            preferences.toggleVisualizerFavoritePreset(presetId)
        }
    }

    fun setRotationSeconds(seconds: Int) {
        scope.launch {
            preferences.setVisualizerRotationSeconds(seconds)
        }
        synchronized(engineLock) {
            rotationSeconds = seconds
            if (nativeInitialized) applyRotationLocked()
        }
    }

    fun touch(x: Float, y: Float, pressure: Int, touchType: Int) {
        synchronized(engineLock) {
            if (nativeInitialized) nativeBridge.touch(x, y, pressure, touchType)
        }
    }

    fun touchDrag(x: Float, y: Float, pressure: Int) {
        synchronized(engineLock) {
            if (nativeInitialized) nativeBridge.touchDrag(x, y, pressure)
        }
    }

    fun touchDestroy(x: Float, y: Float) {
        synchronized(engineLock) {
            if (nativeInitialized) nativeBridge.touchDestroy(x, y)
        }
    }

    fun touchDestroyAll() {
        synchronized(engineLock) {
            if (nativeInitialized) nativeBridge.touchDestroyAll()
        }
    }

    fun reportAudioTapFailure(message: String) {
        updateStatus(
            phase = VisualizerEnginePhase.FALLBACK,
            message = message
        )
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private fun releaseNativeLocked() {
        if (nativeInitialized) {
            nativeBridge.release()
            nativeInitialized = false
        }
    }

    private fun ensureAssetsLocked() {
        if (installedAssets != null) return
        updateStatus(
            phase = VisualizerEnginePhase.INSTALLING,
            message = "Installing bundled projectM presets."
        )
        runCatching {
            val assets = assetInstaller.ensureInstalled()
            val presets = presetCatalog.load(assets.catalogFile)
            installedAssets = assets
            _presets.value = presets
            _currentPreset.value = preferredPresetId?.let { id ->
                presets.firstOrNull { it.id == id }
            } ?: presets.firstOrNull()
            Log.d(TAG, "Loaded ${presets.size} presets from catalog")
            updateStatus(
                phase = VisualizerEnginePhase.READY,
                message = "Bundled projectM presets ready.",
                assetRoot = assets.rootDir.absolutePath,
                assetVersion = assets.version
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to install projectM assets", error)
            updateStatus(
                phase = VisualizerEnginePhase.ERROR,
                message = "projectM assets failed to install: ${error.message ?: "unknown error"}"
            )
        }
    }

    private fun applyPreferredPresetLocked() {
        val presets = _presets.value
        if (presets.isEmpty()) return
        val selected = preferredPresetId?.let { id ->
            presets.firstOrNull { it.id == id }
        } ?: presets.first()
        if (nativeBridge.setPreset(resolveAbsolutePresetPath(selected))) {
            _currentPreset.value = selected
        } else {
            val currentPath = nativeBridge.nextPreset()
            updateCurrentPresetFromPathLocked(currentPath)
        }
    }

    private fun applyRotationLocked() {
        val seconds = rotationSeconds.coerceIn(5, 120)
        nativeBridge.setPresetShuffleEnabled(_autoShuffle.value)
        nativeBridge.configurePresetDuration(seconds)
    }

    private fun updateCurrentPresetFromPathLocked(path: String?) {
        val normalized = path ?: return
        _currentPreset.value = _presets.value.firstOrNull { preset ->
            resolveAbsolutePresetPath(preset) == normalized
        }
    }

    private fun resolveAbsolutePresetPath(preset: VisualizerPreset): String {
        val assets = installedAssets ?: assetInstaller.ensureInstalled().also { installedAssets = it }
        return File(assets.rootDir, preset.filePath).absolutePath
    }

    private fun updateStatus(
        phase: VisualizerEnginePhase,
        message: String,
        assetRoot: String? = _engineStatus.value.assetRoot,
        assetVersion: String = _engineStatus.value.assetVersion
    ) {
        _engineStatus.value = VisualizerEngineStatus(
            phase = phase,
            nativeLibraryLoaded = ProjectMNativeBridge.isLibraryLoaded,
            assetVersion = assetVersion,
            message = message,
            assetRoot = assetRoot
        )
    }

    companion object {
        private const val TAG = "ProjectMEngineRepository"
    }
}
