package tf.monochrome.android.visualizer

import android.content.Context
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
        onResume()
    }

    override fun onDetachedFromWindow() {
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

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            // Don't do heavy I/O here; the engine prepares assets asynchronously
            // in its init block. We just signal readiness on the GL thread.
            attached = false
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
