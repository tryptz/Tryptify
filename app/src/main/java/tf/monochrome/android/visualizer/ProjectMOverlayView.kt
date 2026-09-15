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
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The ambient MilkDrop layer: projectM composited over the blurred album
 * artwork, drawn as the player's background — under every player element,
 * which is why it must be a [TextureView] and not a [android.view.SurfaceView].
 *
 * ## Why this is a TextureView and the hero visualizer is not
 *
 * The hero's GLSurfaceView is a SurfaceView: SurfaceFlinger composites it
 * either behind the window (hole-punched — hidden here by the theme's opaque
 * window background) or on top of the entire window. A TextureView is an
 * ordinary view: its content sits exactly where the view sits in the z-order,
 * which is what "visualizer over the backdrop, under the controls" needs.
 * It costs an extra copy per frame and it does not get GLSurfaceView's render
 * thread for free, which is what the rest of this file manages.
 *
 * ## One engine, one surface, one thread — the lifecycle rules
 *
 * `ProjectMEngineRepository` refcounts attachments, re-initializes the native
 * engine on every attach (the engine is EGL-context-coupled), and keeps ONE
 * render-trigger slot. The rules that keep this view from fighting the hero:
 *
 *  1. The render trigger is registered in [onAttachedToWindow] and cleared in
 *     [onDetachedFromWindow], keyed by `this` — the same discipline as
 *     `ProjectMRendererView`, never inside start/stop where teardown races
 *     used to clobber the other view's registration.
 *  2. Exactly one render thread per view, guaranteed by an atomic flag: a new
 *     thread is refused while the previous one is still draining (a join
 *     timeout used to let two threads attach the engine under two contexts).
 *  3. `onSurfaceDetached` runs exactly once per attach, in the thread's
 *     finally, so the repository's count can never leak upward.
 *  4. The ambient view is only ever composed while the hero is not
 *     (the route gates it on view mode) — the two share the engine slot.
 *
 * ## The `update*` methods are diff-aware
 *
 * Compose calls the AndroidView `update` block on every recomposition of the
 * player — album-color crossfades tick many times a second. Re-uploading the
 * album texture and re-requesting frames at that rate is churn with nothing
 * to show for it, so every setter compares against the last applied value
 * and returns early when nothing changed.
 */
@Suppress("ViewConstructor") // Programmatic-only view; needs the repository.
class ProjectMOverlayView(
    context: Context,
    private val repository: ProjectMEngineRepository,
) : TextureView(context) {

    private var renderThread: RenderThread? = null

    // Set when a RenderThread enters run() and cleared in its finally. While
    // true, startRenderer refuses to spawn a second thread: the old one is
    // still draining (its join timed out), and a second onSurfaceAttached
    // would re-initialize the engine under the new thread's context while the
    // old one kept rendering into it — garbage, then a crash.
    private val threadAlive = AtomicBoolean(false)

    private val pass = AmbientCompositePass()

    @Volatile private var settings = AmbientVisualizerSettings()
    @Volatile private var scrimTone = floatArrayOf(0f, 0f, 0f)
    @Volatile private var playing = true
    private var lastAlbum: Bitmap? = null

    /** Whether frames are being produced, for the frame-rate hint below. */
    private var producingFrames = true

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
                applyFrameRateHint()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                renderThread?.resize(width, height)
                applyFrameRateHint()
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
        if (isPlaying == playing) return
        playing = isPlaying
        repository.setPlaybackPaused(!isPlaying)
        // A paused visualizer still has to draw once, or the last frame it
        // produced stays on screen without the composite over it.
        renderThread?.requestRender()
        applyFrameRateHint()
    }

    fun updateSettings(next: AmbientVisualizerSettings) {
        if (next == settings) return
        settings = next
        renderThread?.requestRender()
    }

    /** The mid-screen scrim colour, matching `PlayerBlurredArtBackground`. */
    fun updateScrimTone(red: Float, green: Float, blue: Float) {
        val current = scrimTone
        if (current[0] == red && current[1] == green && current[2] == blue) return
        scrimTone = floatArrayOf(red, green, blue)
        renderThread?.requestRender()
    }

    fun updateAlbum(bitmap: Bitmap?) {
        // Identity, not equality: a new track decodes a new Bitmap instance,
        // and a recomposition re-presenting the same instance must not reset
        // the upload state.
        if (bitmap === lastAlbum) return
        lastAlbum = bitmap
        pass.setAlbum(bitmap)
        renderThread?.requestRender()
    }

    private fun startRenderer(surface: SurfaceTexture, width: Int, height: Int) {
        if (renderThread != null) return
        if (!threadAlive.compareAndSet(false, true)) {
            // The previous thread missed its join deadline but is on its way
            // out (its finally clears this flag and detaches the engine).
            // Starting a second one now would attach the engine twice.
            Log.e(TAG, "previous ambient render thread still exiting — " +
                "refusing to start a second renderer")
            return
        }
        renderThread = RenderThread(surface, width, height).also { it.start() }
        producingFrames = true
        applyFrameRateHint()
    }

    private fun stopRenderer() {
        val thread = renderThread ?: return
        renderThread = null
        thread.finish()
        // Joined rather than left to die: the GL teardown and the repository
        // detach both happen on that thread, and returning true from
        // onSurfaceTextureDestroyed promises the surface is no longer in use.
        runCatching { thread.join(JOIN_TIMEOUT_MS) }
        if (thread.isAlive) {
            // Not fatal: the thread's finally still clears threadAlive and
            // detaches the engine exactly once — but nothing new may start
            // until it does, which startRenderer enforces.
            Log.e(TAG, "ambient render thread did not exit within " +
                "${JOIN_TIMEOUT_MS}ms; new renderers blocked until it does")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Lets the engine ask for a frame while paused (preset changes need a
        // frame to land on). Keyed by `this`, mirroring ProjectMRendererView —
        // a teardown from the other view can no longer clobber this slot.
        repository.setRenderTrigger(this) { renderThread?.requestRender() }
    }

    override fun onDetachedFromWindow() {
        // Clear before the surface goes away; keyed clear is a no-op if the
        // hero has already taken the slot back.
        repository.clearRenderTrigger(this)
        producingFrames = false
        applyFrameRateHint()
        super.onDetachedFromWindow()
    }

    /**
     * Tell the display what frame rate this surface wants — the same
     * per-surface content hint ProjectMRendererView applies: no resolution,
     * no mode id, scoped to this TextureView's surface, cleared to
     * "no preference" whenever frames stop. Without it the panel drops to its
     * idle rate and the visualizer, one frame per vblank, drops with it.
     */
    private fun applyFrameRateHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val texture = surfaceTexture ?: return
        val surface = Surface(texture)
        try {
            if (!surface.isValid) return
            val wanted = if (producingFrames) {
                display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
            } else {
                0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    wanted,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                surface.setFrameRate(wanted, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (_: IllegalStateException) {
            // The surface went away between the validity check and here.
        } finally {
            surface.release()
        }
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
            var attached = false
            try {
                if (!initEgl()) {
                    releaseEgl()
                    return
                }
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
                // Exactly once per attach, whether the loop exited cleanly,
                // threw, or was torn down — the repository's surface count
                // must never leak upward through this view.
                if (attached) runCatching { repository.onSurfaceDetached() }
                runCatching { pass.release() }
                releaseEgl()
                threadAlive.set(false)
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
