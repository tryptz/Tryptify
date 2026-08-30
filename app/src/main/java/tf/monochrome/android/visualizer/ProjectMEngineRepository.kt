package tf.monochrome.android.visualizer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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

    // Start in FALLBACK even when the native library is loaded: presets are
    // installed lazily on first visualizer use, and reporting READY before
    // they exist would let a GL surface attach and block its render thread
    // on the install.
    private val _engineStatus = MutableStateFlow(
        VisualizerEngineStatus(
            phase = VisualizerEnginePhase.FALLBACK,
            nativeLibraryLoaded = ProjectMNativeBridge.isLibraryLoaded,
            message = if (ProjectMNativeBridge.isLibraryLoaded) {
                "projectM idle. Presets load when the visualizer opens."
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
    @Volatile var vsyncEnabled: Boolean = true
        private set
    private var preferredPresetId: String? = null
    private var beatSensitivity: Int = 50
    private var brightness: Int = 80
    private var rotationSeconds: Int = 20
    private var playbackPaused: Boolean = false

    // Track whether there is an active GL surface. Only the most recent
    // onSurfaceAttached call owns the native bridge. All others become no-ops.
    private var attachedSurfaceCount: Int = 0
    private var nativeInitialized: Boolean = false

    // Work that has to happen with the GL context current, asked for from a
    // thread that does not have one. Switching a preset compiles its shaders,
    // and resizing the mesh reallocates against the context; called straight
    // from the settings observers on the main thread they do nothing at all,
    // which is why choosing a preset used to take effect only after the
    // visualizer was closed and reopened and the engine rebuilt on the render
    // thread. Both are drained at the top of renderFrame instead.
    //
    // Guarded by engineLock, which renderFrame already holds, so draining costs
    // one field read per frame.
    private var pendingPreset: PendingPresetRequest? = null
    private var pendingQuality: Boolean = false

    // Set by the GLSurfaceView so a change made while paused still gets a frame
    // to appear on: the view drops to RENDERMODE_WHEN_DIRTY when playback
    // stops, and without a nudge the queued preset would sit until something
    // else asked to draw.
    private var requestRender: (() -> Unit)? = null

    private var lastPcmTimestampMs: Long = 0L
    private var fpsFrameCount = 0
    private var fpsStartTimeMs = 0L

    // Preset installation/loading runs on its own minimum-priority thread so a
    // first-run extraction of the bundled preset archive can never starve the
    // shared IO dispatcher (Room, DataStore, Coil, session restore).
    private val installDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "projectm-install").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val prepareRequested = AtomicBoolean(false)

    init {
        observePreferences()
    }

    /**
     * Kick off asset installation + engine preparation in the background if it
     * hasn't happened yet. Called from the visualizer UI entry points; cheap and
     * idempotent, so callers can invoke it unconditionally. Nothing runs at app
     * startup — first launch stays free of the ~130 MB preset install.
     */
    fun requestPrepare() {
        if (installedAssets != null) return
        if (!prepareRequested.compareAndSet(false, true)) return
        scope.launch(installDispatcher) {
            try {
                prepareEngine()
            } finally {
                // Allow a retry from the UI if the install failed (ERROR phase).
                prepareRequested.set(false)
            }
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
                    requestPresetOnGlThread(PendingPresetRequest.Select(selected))
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
                requestQualityOnGlThread()
            }
        }
        scope.launch {
            preferences.visualizerMeshY.collectLatest { y ->
                meshY = y
                requestQualityOnGlThread()
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
            preferences.visualizerVsyncEnabled.collectLatest { enabled ->
                vsyncEnabled = enabled
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

    /**
     * Lets the view be asked for a frame. Cleared on detach so a dead surface
     * is never poked.
     */
    fun setRenderTrigger(trigger: (() -> Unit)?) {
        synchronized(engineLock) { requestRender = trigger }
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
            // The one place with a current GL context, so the one place these
            // can actually take effect.
            val appliedPendingWork = applyPendingGlWorkLocked()
            val frames = audioBus.drainAll()
            if (frames.isNotEmpty()) {
                lastPcmTimestampMs = frames.last().timestampMs
                for (frame in frames) {
                    nativeBridge.pushPcm(frame.samples, frame.channelCount, frame.sampleRate)
                }
            }
            // A frozen frame is skipped to save power while paused, but a preset
            // that has just been swapped in has never been drawn, so freezing
            // through it would leave the old one on screen -- the very bug this
            // is fixing, in a different disguise.
            val freezeFrame = !appliedPendingWork &&
                playbackPaused && (System.currentTimeMillis() - lastPcmTimestampMs) > 2_000L
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

    /**
     * Advance to the next preset in the playlist.
     *
     * Queued rather than run here for the same reason selectPreset is: this is
     * called from the overlay's Next button and from the player listener when a
     * track changes with auto-shuffle on, both on the main thread, and loading a
     * preset needs the GL context.
     */
    fun nextPreset() {
        requestPresetOnGlThread(PendingPresetRequest.Next)
    }

    fun selectPreset(preset: VisualizerPreset) {
        preferredPresetId = preset.id
        _currentPreset.value = preset
        scope.launch {
            preferences.setVisualizerPresetId(preset.id)
        }
        requestPresetOnGlThread(PendingPresetRequest.Select(preset))
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
        // Queued against an engine that no longer exists. The preset is not
        // lost: preferredPresetId still holds it, and applyPreferredPresetLocked
        // re-applies it when the next surface attaches.
        pendingPreset = null
        pendingQuality = false
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

    /**
     * A preset change waiting for the render thread.
     *
     * One field rather than a flag per kind, so the newest request wins instead
     * of a queued Next and a queued Select both landing on the same frame in
     * whatever order the drain happens to check them.
     */
    private sealed interface PendingPresetRequest {
        /** A preset chosen by name, from the browser or a restored preference. */
        data class Select(val preset: VisualizerPreset) : PendingPresetRequest

        /** Whatever the playlist calls next -- the Next button, or auto-shuffle. */
        data object Next : PendingPresetRequest
    }

    /**
     * Runs the work that was waiting for a GL context. Returns whether anything
     * was applied, so the caller knows this frame has something new to show.
     */
    private fun applyPendingGlWorkLocked(): Boolean {
        var applied = false
        pendingPreset?.let { request ->
            pendingPreset = null
            applied = when (request) {
                // A false here means the path is not in the playlist -- a preset
                // gone since it was chosen. Left alone deliberately: the caller
                // has already shown it as selected, and the alternative,
                // advancing the playlist, would answer a failed request by
                // displaying some third preset nobody asked for.
                is PendingPresetRequest.Select ->
                    nativeBridge.setPreset(resolveAbsolutePresetPath(request.preset))

                // Next only knows which preset it landed on after it has moved,
                // so unlike Select the exposed state and the stored preference
                // are settled here rather than by the caller.
                PendingPresetRequest.Next -> {
                    val path = nativeBridge.nextPreset()
                    if (path != null) {
                        updateCurrentPresetFromPathLocked(path)
                        val id = _currentPreset.value?.id
                        scope.launch { preferences.setVisualizerPresetId(id) }
                    }
                    path != null
                }
            }
        }
        if (pendingQuality) {
            pendingQuality = false
            nativeBridge.configureQuality(meshX, meshY)
            applied = true
        }
        return applied
    }

    /**
     * Hands [preset] to the render thread and asks for a frame to show it on.
     *
     * The trigger is captured under the lock but invoked outside it on purpose.
     * GLSurfaceView.requestRender takes its own monitor, and the render thread
     * takes engineLock while holding that one -- calling in while holding
     * engineLock is the other half of a deadlock.
     */
    private fun requestPresetOnGlThread(request: PendingPresetRequest) {
        val trigger = synchronized(engineLock) {
            pendingPreset = request
            if (nativeInitialized) requestRender else null
        }
        trigger?.invoke()
    }

    private fun requestQualityOnGlThread() {
        val trigger = synchronized(engineLock) {
            pendingQuality = true
            if (nativeInitialized) requestRender else null
        }
        trigger?.invoke()
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
