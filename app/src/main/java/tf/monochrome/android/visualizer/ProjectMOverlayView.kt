package tf.monochrome.android.visualizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.TextureView
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The ambient MilkDrop layer: projectM composited over the album artwork,
 * drawn as the player's background.
 *
 * ## Why this is a TextureView and the hero visualizer is not
 *
 * [ProjectMRendererView] is a `GLSurfaceView`, and a `SurfaceView` is
 * composited by SurfaceFlinger rather than by the view hierarchy — it is
 * either *behind* the window, hole-punched, where any opaque Compose content
 * above hides it, or with `setZOrderOnTop` *in front of the entire window*,
 * over the player's own controls. Neither is a layer in the middle of the
 * stack, which is exactly what ambient mode needs. (It is also why the
 * `graphicsLayer { alpha }` wrapped around the hero renderer does nothing:
 * view alpha never reaches a SurfaceView's buffer.)
 *
 * A `TextureView` is an ordinary view. It costs an extra copy per frame and
 * it does not get `GLSurfaceView`'s render thread for free, which is what the
 * rest of this file is.
 *
 * ## One engine, one surface
 *
 * `ProjectMEngineRepository` refcounts attachments and the *first* one owns
 * the native bridge; `renderFrame` needs that bridge's GL objects to be
 * current. Two views on two contexts would therefore render garbage from the
 * second. The caller must never show this at the same time as the hero
 * visualizer — the player gates it on the view mode.
 */
@Suppress("ViewConstructor") // Programmatic-only; needs the repository.
class ProjectMOverlayView(
    context: Context,
    private val repository: ProjectMEngineRepository,
) : TextureView(context) {

    private var renderThread: RenderThread? = null
    private val pass = AmbientCompositePass()

    @Volatile private var settings = AmbientVisualizerSettings()
    @Volatile private var scrimTone = floatArrayOf(0f, 0f, 0f)
    @Volatile private var playing = true

    init {
        // The composite writes an opaque frame — it *is* the background, not a
        // pane over one — so there is nothing for the compositor to blend.
        isOpaque = true
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                startRenderer(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                renderThread?.resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopRenderer()
                // True: the thread has joined, so the SurfaceTexture is ours to
                // hand back and the platform may release it.
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun updatePlayback(isPlaying: Boolean) {
        playing = isPlaying
        repository.setPlaybackPaused(!isPlaying)
        // A paused visualizer still has to draw once, or the last frame it
        // produced stays on screen without the composite over it.
        renderThread?.requestRender()
    }

    fun updateSettings(next: AmbientVisualizerSettings) {
        settings = next
        renderThread?.requestRender()
    }

    /** The mid-screen scrim colour, matching `PlayerBlurredArtBackground`. */
    fun updateScrimTone(red: Float, green: Float, blue: Float) {
        scrimTone = floatArrayOf(red, green, blue)
        renderThread?.requestRender()
    }

    fun updateAlbum(bitmap: Bitmap?) {
        pass.setAlbum(bitmap)
        renderThread?.requestRender()
    }

    private fun startRenderer(surface: SurfaceTexture, width: Int, height: Int) {
        if (renderThread != null) return
        renderThread = RenderThread(surface, width, height).also {
            it.start()
            repository.setRenderTrigger(it::requestRender)
        }
    }

    private fun stopRenderer() {
        val thread = renderThread ?: return
        renderThread = null
        repository.setRenderTrigger(null)
        thread.finish()
        // Joined rather than left to die: the GL teardown and the repository
        // detach both happen on that thread, and returning true from
        // onSurfaceTextureDestroyed promises the surface is no longer in use.
        runCatching { thread.join(JOIN_TIMEOUT_MS) }
    }

    override fun onDetachedFromWindow() {
        stopRenderer()
        super.onDetachedFromWindow()
    }

    private inner class RenderThread(
        private val surface: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("ProjectMAmbient") {

        // A Condition rather than Object.wait: `java.lang.Object` is a
        // platform class Kotlin would rather not see, and the wake conditions
        // here ("playing", "a frame was asked for", "stop") read better as one
        // guarded predicate than as a notifyAll everybody has to remember.
        private val lock = ReentrantLock()
        private val wake: Condition = lock.newCondition()
        @Volatile private var running = true
        @Volatile private var renderRequested = true
        @Volatile private var sizeChanged = true
        private var lastSwapInterval = -1

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun requestRender() = lock.withLock {
            renderRequested = true
            wake.signalAll()
        }

        fun resize(newWidth: Int, newHeight: Int) = lock.withLock {
            width = newWidth
            height = newHeight
            sizeChanged = true
            renderRequested = true
            wake.signalAll()
        }

        fun finish() = lock.withLock {
            running = false
            wake.signalAll()
        }

        override fun run() {
            if (!initEgl()) {
                releaseEgl()
                return
            }
            var attached = false
            try {
                pass.ensureCreated()
                repository.onSurfaceAttached(width, height)
                attached = true

                while (true) {
                    val stop = lock.withLock {
                        // Continuous while playing; otherwise sleep until
                        // something asks for a frame — a preset change, a
                        // setting, a new cover.
                        while (running && !playing && !renderRequested) {
                            wake.await()
                        }
                        renderRequested = false
                        !running
                    }
                    if (stop) break
                    drawFrame()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ambient render thread stopped", t)
            } finally {
                if (attached) runCatching { repository.onSurfaceDetached() }
                runCatching { pass.release() }
                releaseEgl()
            }
        }

        private fun drawFrame() {
            if (sizeChanged) {
                sizeChanged = false
                pass.resize(width, height)
                repository.onSurfaceResized(width, height)
            }
            applyVsyncIfChanged()

            GLES30.glViewport(0, 0, width, height)
            // projectM presets assume they own an opaque, usually black
            // framebuffer, and many depend on reading back what they drew last
            // frame. Nothing about that changes here: it renders exactly as it
            // does for the hero visualizer, and the transparency is worked out
            // afterwards from the pixels it produced.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            repository.renderFrame(System.nanoTime())

            val current = settings
            val composited = pass.composite(
                opacity = current.opacity,
                blackPoint = current.blackPoint,
                blend = current.blend,
                scrim = scrimTone,
            )
            if (!composited) {
                // No cover decoded yet, or the program failed to build. The
                // raw visualizer frame is already in the buffer; presenting it
                // is better than presenting black.
                Log.v(TAG, "composite skipped; presenting the raw frame")
            }
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        private fun applyVsyncIfChanged() {
            val want = if (repository.vsyncEnabled) 1 else 0
            if (want != lastSwapInterval) {
                EGL14.eglSwapInterval(display, want)
                lastSwapInterval = want
            }
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                // Presets draw geometry that expects a depth buffer, the same
                // as the GLSurfaceView config the hero renderer gets by default.
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    display, configAttribs, 0, configs, 0, 1, configCount, 0,
                ) || configCount[0] == 0
            ) {
                Log.e(TAG, "no ES3 config for the ambient overlay")
                return false
            }
            val config = configs[0] ?: return false

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(
                display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        private fun releaseEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    private companion object {
        const val TAG = "ProjectMOverlay"
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
