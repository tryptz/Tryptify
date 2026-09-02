package tf.monochrome.android.visualizer

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Suppress("ViewConstructor") // Programmatic-only view; requires ProjectMEngineRepository
class ProjectMRendererView @JvmOverloads constructor(
    context: Context,
    private val repository: ProjectMEngineRepository,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val visualizerRenderer = VisualizerRenderer(repository)

    /**
     * Whether frames are being produced, for the frame-rate hint below.
     *
     * Starts true to match the RENDERMODE_CONTINUOUSLY set in init: the surface
     * can be created before the first updatePlayback arrives, and defaulting to
     * false would withhold the hint for exactly that window.
     */
    private var producingFrames: Boolean = true

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(visualizerRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // An observer of our own rather than an override of surfaceCreated:
        // GLSurfaceView registers itself as the holder's callback and drives its
        // render thread from it, so adding a second listener is safer than
        // subclassing over its own.
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = applyFrameRateHint()
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) =
                applyFrameRateHint()

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    fun updatePlayback(isPlaying: Boolean) {
        repository.setPlaybackPaused(!isPlaying)
        renderMode = if (isPlaying) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        if (!isPlaying) {
            requestRender()
        }
        producingFrames = isPlaying
        // Held only while frames are actually being produced. Keeping a 165Hz
        // panel awake behind a visualizer that has stopped drawing would be a
        // battery cost with nothing on screen to show for it.
        applyFrameRateHint()
    }

    /**
     * Tell the display what frame rate this surface wants.
     *
     * Not the display-mode vote the app removed. That set preferredDisplayModeId
     * and preferredRefreshRate on the *window*, and a mode id names a resolution
     * and a rate together -- so the app asserted both and, on a panel whose top
     * rate is only offered at a lower resolution, argued itself down against a
     * per-app override. This is a per-surface content hint carrying one float:
     * no resolution, no mode, scoped to the visualizer's own surface, and the
     * system remains free to ignore it. Without it the panel drops to its idle
     * rate as soon as the canvas stops being touched, and the visualizer, which
     * draws one frame per vblank, drops with it.
     *
     * Cleared to 0 -- "no preference" -- whenever playback is not running.
     */
    private fun applyFrameRateHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        setSurfaceFrameRate()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setSurfaceFrameRate() {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        // The panel's own ceiling rather than a number of our own: the
        // visualizer renders every vblank it is given, so what it wants is
        // whatever this display can do.
        val wanted = if (producingFrames) {
            display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
        } else {
            0f
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    wanted,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    // Only-if-seamless can silently decline and leave the panel
                    // exactly where the bug found it.
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(wanted, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (_: IllegalStateException) {
            // The surface went away between the validity check and here.
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Lets the engine ask for a frame. Preset and mesh changes are applied
        // on the GL thread inside renderFrame, and while playback is paused the
        // render mode is WHEN_DIRTY -- without this a preset chosen on a paused
        // track would be loaded but never drawn.
        repository.setRenderTrigger(::requestRender)
        onResume()
    }

    override fun onDetachedFromWindow() {
        // Dropped first: past this point the surface is going away, and a
        // request to draw on it is at best useless.
        repository.setRenderTrigger(null)
        // Give the rate back while the surface is still valid enough to say so.
        producingFrames = false
        applyFrameRateHint()
        // Queue the detach on the GL thread so it runs in the correct OpenGL context
        // and doesn't race with renderFrame.
        queueEvent {
            visualizerRenderer.onDetach()
        }
        onPause()
        super.onDetachedFromWindow()
    }

    private class VisualizerRenderer(
        private val repository: ProjectMEngineRepository
    ) : Renderer {
        @Volatile
        private var attached = false
        // Last applied swap interval, so we only re-issue eglSwapInterval
        // when the user toggles the setting. Initialised to a sentinel that
        // doesn't match either real value so the first frame always applies.
        private var lastSwapInterval: Int = -1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            // Force re-apply on the next draw, since the EGL surface is new.
            lastSwapInterval = -1
            // Don't do heavy I/O here; the engine prepares assets asynchronously
            // in its init block. We just signal readiness on the GL thread.
            attached = false
        }

        private fun applyVsyncIfChanged() {
            // Setting controlled from Settings → Visualizer → "Disable vsync".
            // eglSwapInterval(0) lets the visualizer exceed display refresh
            // (capped only by Target FPS); (1) holds the standard vblank-locked
            // present. Adreno honours both; some drivers ignore (0) silently.
            // Effect is local to the visualizer GLSurfaceView — the Compose UI
            // thread keeps its own vsync.
            val want = if (repository.vsyncEnabled) 1 else 0
            if (want != lastSwapInterval) {
                EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), want)
                lastSwapInterval = want
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            if (!attached) {
                repository.onSurfaceAttached(width, height)
                attached = true
            } else {
                repository.onSurfaceResized(width, height)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            applyVsyncIfChanged()
            if (attached) {
                repository.renderFrame(System.nanoTime())
            } else {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }

        fun onDetach() {
            if (attached) {
                attached = false
                repository.onSurfaceDetached()
            }
        }
    }
}
