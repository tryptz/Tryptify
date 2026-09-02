package tf.monochrome.android.visualizer

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
class ProjectMNativeBridge {
    private var nativeHandle: Long = 0L
    private var brightness: Int = 80
    private val paused = AtomicBoolean(false)

    fun initialize(assetRoot: String, width: Int, height: Int, meshWidth: Int, meshHeight: Int): Boolean {
        if (!isLibraryLoaded) return false
        if (nativeHandle != 0L) {
            nativeResize(nativeHandle, width, height)
            return true
        }

        nativeHandle = runCatching {
            nativeCreate(
                assetRoot = assetRoot,
                presetRoot = File(assetRoot, "presets").absolutePath,
                textureRoot = File(assetRoot, "textures").absolutePath,
                width = width,
                height = height,
                meshWidth = meshWidth,
                meshHeight = meshHeight
            )
        }.getOrElse {
            Log.e(TAG, "Unable to initialize projectM", it)
            0L
        }
        return nativeHandle != 0L
    }

    fun resize(width: Int, height: Int) {
        if (nativeHandle != 0L) {
            nativeResize(nativeHandle, width, height)
        }
    }

    fun renderFrame(frameTimeNanos: Long) {
        if (nativeHandle != 0L && !paused.get()) {
            nativeRenderFrame(nativeHandle, frameTimeNanos)
        }
    }

    fun pushPcm(samples: FloatArray, channelCount: Int, sampleRate: Int) {
        if (nativeHandle != 0L && !paused.get()) {
            nativePushPcm(nativeHandle, samples, channelCount, sampleRate)
        }
    }

    fun setPreset(presetPath: String): Boolean {
        if (nativeHandle == 0L) return false
        return nativeSetPreset(nativeHandle, presetPath)
    }

    fun nextPreset(): String? {
        if (nativeHandle == 0L) return null
        return nativeNextPreset(nativeHandle)
    }

    fun setPresetShuffleEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetPresetShuffleEnabled(nativeHandle, enabled)
        }
    }

    fun setBeatSensitivity(value: Int) {
        if (nativeHandle != 0L) {
            nativeSetBeatSensitivity(nativeHandle, value.coerceIn(0, 100))
        }
    }

    fun setBrightness(value: Int) {
        brightness = value.coerceIn(0, 100)
        if (nativeHandle != 0L) {
            nativeSetBrightness(nativeHandle, brightness)
        }
    }

    fun setPaused(paused: Boolean) {
        this.paused.set(paused)
        if (nativeHandle != 0L) {
            nativeSetPaused(nativeHandle, paused)
        }
    }

    fun touch(x: Float, y: Float, pressure: Int, touchType: Int) {
        if (nativeHandle != 0L) nativeTouch(nativeHandle, x, y, pressure, touchType)
    }

    fun touchDrag(x: Float, y: Float, pressure: Int) {
        if (nativeHandle != 0L) nativeTouchDrag(nativeHandle, x, y, pressure)
    }

    fun touchDestroy(x: Float, y: Float) {
        if (nativeHandle != 0L) nativeTouchDestroy(nativeHandle, x, y)
    }

    fun touchDestroyAll() {
        if (nativeHandle != 0L) nativeTouchDestroyAll(nativeHandle)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    internal fun configureQuality(meshX: Int, meshY: Int) {
        if (nativeHandle != 0L) {
            nativeSetQuality(nativeHandle, meshX, meshY)
        }
    }

    /**
     * The rate the output is actually rendering at.
     *
     * projectM does not time itself from this -- its own clock is the wall
     * clock -- but it hands the number to presets, and Milkdrop presets are
     * written per frame: the ones that mean to move at a fixed speed divide
     * their step by `fps`. Told a number the renderer is not running at, those
     * presets move at the ratio between the two, which is why the visualizer
     * appeared to slow down when the panel dropped its refresh rate.
     *
     * Upstream asks applications to update this regularly with the measured,
     * averaged rate. That is what this is for; [configureTargetFps] writes the
     * same field from the settings slider and is corrected by this within a
     * second.
     */
    internal fun reportMeasuredFps(fps: Int) {
        if (nativeHandle != 0L) {
            nativeSetFps(nativeHandle, fps.coerceIn(1, 1000))
        }
    }

    internal fun configureTargetFps(fps: Int) {
        if (nativeHandle != 0L) {
            nativeSetFps(nativeHandle, fps)
        }
    }

    /**
     * Whether presets change on their own.
     *
     * Off uses projectM's preset lock rather than a duration long enough to
     * feel like off: the lock stops the timed transitions outright, while a
     * very long timer is still a timer and would move the preset eventually on
     * a long listen. Choosing one by hand, and Next, work either way.
     */
    internal fun setPresetRotationEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetPresetRotationEnabled(nativeHandle, enabled)
        }
    }

    internal fun configurePresetDuration(seconds: Int) {
        if (nativeHandle != 0L) {
            nativeSetPresetDuration(nativeHandle, seconds.coerceIn(5, 120))
        }
    }

    companion object {
        private const val TAG = "ProjectMNativeBridge"

        val isLibraryLoaded: Boolean by lazy {
            runCatching {
                // Load deps in link order: core → playlist → our JNI bridge.
                // CMakeLists.txt pins DEBUG_POSTFIX="" on these, so one name
                // works for both debug and release and there's no scary
                // `dlopen failed` probe in logcat on launch.
                System.loadLibrary("projectM-4")
                System.loadLibrary("projectM-4-playlist")
                System.loadLibrary("monochrome_visualizer")
                true
            }.getOrElse { error ->
                Log.w(TAG, "Native projectM bridge unavailable", error)
                false
            }
        }
    }

    private external fun nativeCreate(
        assetRoot: String,
        presetRoot: String,
        textureRoot: String,
        width: Int,
        height: Int,
        meshWidth: Int,
        meshHeight: Int
    ): Long

    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeRenderFrame(handle: Long, frameTimeNanos: Long)
    private external fun nativePushPcm(handle: Long, samples: FloatArray, channelCount: Int, sampleRate: Int)
    private external fun nativeSetPreset(handle: Long, presetPath: String): Boolean
    private external fun nativeNextPreset(handle: Long): String?
    private external fun nativeSetPresetShuffleEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetBeatSensitivity(handle: Long, value: Int)
    private external fun nativeSetBrightness(handle: Long, value: Int)
    private external fun nativeSetPaused(handle: Long, paused: Boolean)
    private external fun nativeSetQuality(handle: Long, meshWidth: Int, meshHeight: Int)
    private external fun nativeSetFps(handle: Long, fps: Int)
    private external fun nativeSetPresetDuration(handle: Long, seconds: Int)
    private external fun nativeSetPresetRotationEnabled(handle: Long, enabled: Boolean)
    private external fun nativeTouch(handle: Long, x: Float, y: Float, pressure: Int, touchType: Int)
    private external fun nativeTouchDrag(handle: Long, x: Float, y: Float, pressure: Int)
    private external fun nativeTouchDestroy(handle: Long, x: Float, y: Float)
    private external fun nativeTouchDestroyAll(handle: Long)
    private external fun nativeRelease(handle: Long)
}
