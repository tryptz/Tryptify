package tf.monochrome.android.visualizer

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Suppress("ViewConstructor") // Programmatic-only view; requires ProjectMEngineRepository
class ProjectMRendererView @JvmOverloads constructor(
    context: Context,
    private val repository: ProjectMEngineRepository,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val visualizerRenderer = VisualizerRenderer(repository)

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(visualizerRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun updatePlayback(isPlaying: Boolean) {
        repository.setPlaybackPaused(!isPlaying)
        renderMode = if (isPlaying) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        if (!isPlaying) {
            requestRender()
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
