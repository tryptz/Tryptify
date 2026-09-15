package tf.monochrome.android.visualizer

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns what projectM drew into a translucent layer over the album artwork,
 * entirely on the GPU.
 *
 * ## Why the visualizer is copied rather than rendered into a target
 *
 * The obvious design is to point projectM at our own framebuffer and composite
 * from its texture. projectM 4.1.6 will not do it: `ProjectM::RenderFrame`
 * ends with a hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` — there is
 * a `ToDo: Allow external apps to provide a custom target framebuffer` sitting
 * on the very line — and the C API exposes only `projectm_opengl_render_frame`,
 * with no FBO-taking variant to call instead.
 *
 * So projectM draws where it insists on drawing, the default framebuffer, and
 * [composite] blits that straight into a texture with `glBlitFramebuffer`
 * before overwriting it. That is a GPU-side copy: nothing is read back to the
 * CPU, which is what keeps this affordable at 60–120 Hz. It costs one
 * full-screen resolve per frame, which on a tiler is the price of not
 * patching a pinned submodule we cannot build here.
 *
 * Preset feedback is untouched either way. Trails, warps and glow accumulate
 * in projectM's own internal FBOs; the default framebuffer only ever receives
 * the finished frame.
 *
 * Every method must be called on the thread holding the GL context.
 */
internal class AmbientCompositePass {

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var captureTexture = 0
    private var captureFbo = 0
    private var albumTexture = 0
    private var width = 0
    private var height = 0
    private var ready = false

    private var uFx = -1
    private var uAlbum = -1
    private var uScrimTone = -1
    private var uOpacity = -1
    private var uBlackPoint = -1
    private var uKnee = -1
    private var uBlend = -1

    /** Set from the UI thread, consumed on the GL thread at the next frame. */
    @Volatile
    private var pendingAlbum: Bitmap? = null

    @Volatile
    private var albumUploaded = false

    fun setAlbum(bitmap: Bitmap?) {
        pendingAlbum = bitmap
        albumUploaded = false
    }

    /** True once there is a program and an album to sample. */
    val hasAlbum: Boolean get() = albumUploaded

    fun ensureCreated(): Boolean {
        if (ready) return true
        val vertex = compile(GLES30.GL_VERTEX_SHADER, AMBIENT_VERTEX_SHADER)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, AMBIENT_FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) return false

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        // The shaders are attached and can go either way; the program keeps
        // its own reference until it is deleted.
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (linked[0] == 0) {
            Log.e(TAG, "composite program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            program = 0
            return false
        }

        uFx = GLES30.glGetUniformLocation(program, "uFx")
        uAlbum = GLES30.glGetUniformLocation(program, "uAlbum")
        uScrimTone = GLES30.glGetUniformLocation(program, "uScrimTone")
        uOpacity = GLES30.glGetUniformLocation(program, "uOpacity")
        uBlackPoint = GLES30.glGetUniformLocation(program, "uBlackPoint")
        uKnee = GLES30.glGetUniformLocation(program, "uKnee")
        uBlend = GLES30.glGetUniformLocation(program, "uBlend")

        // A full-screen triangle strip in clip space. The vertex stage derives
        // both texture coordinates from it, so there is nothing else to upload.
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quad)
        buffer.position(0)

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quad.size * 4, buffer, GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)

        GLES30.glGenTextures(1, ids, 0)
        albumTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, albumTexture)
        // Linear on a deliberately tiny cover: upscaling a 128px thumbnail with
        // bilinear filtering *is* the 64dp blur the Compose backdrop applies,
        // for one small texture instead of a full-screen gaussian per frame.
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        ready = true
        return true
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        if (newWidth == width && newHeight == height && captureFbo != 0) return
        width = newWidth
        height = newHeight
        releaseCaptureTarget()

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        captureTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        // Sampled one-to-one, so nearest costs nothing and filters nothing away.
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )

        GLES30.glGenFramebuffers(1, ids, 0)
        captureFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, captureTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "capture framebuffer incomplete: $status")
            releaseCaptureTarget()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Copy the frame projectM just drew, then draw the composite over it.
     *
     * Returns false when it could not run, so the caller can leave whatever
     * projectM produced on screen rather than presenting a blank surface.
     */
    fun composite(
        opacity: Float,
        blackPoint: Float,
        blend: VisualizerBlendMode,
        scrim: FloatArray,
    ): Boolean {
        if (!ready || captureFbo == 0 || width <= 0 || height <= 0) return false
        uploadPendingAlbum()
        if (!albumUploaded) return false

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, captureFbo)
        GLES30.glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // projectM leaves whatever state its last preset wanted, so everything
        // this pass depends on is set rather than assumed.
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glColorMask(true, true, true, true)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTexture)
        GLES30.glUniform1i(uFx, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, albumTexture)
        GLES30.glUniform1i(uAlbum, 1)
        GLES30.glUniform3f(uScrimTone, scrim[0], scrim[1], scrim[2])
        GLES30.glUniform1f(uOpacity, opacity)
        GLES30.glUniform1f(uBlackPoint, blackPoint)
        GLES30.glUniform1f(uKnee, AMBIENT_KNEE)
        GLES30.glUniform1i(uBlend, blend.ordinal)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        // Left on unit 0 so projectM's next frame does not inherit unit 1.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        return true
    }

    private fun uploadPendingAlbum() {
        val bitmap = pendingAlbum ?: return
        if (albumUploaded) return
        if (bitmap.isRecycled) {
            pendingAlbum = null
            return
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, albumTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        albumUploaded = true
    }

    private fun releaseCaptureTarget() {
        val ids = IntArray(1)
        if (captureFbo != 0) {
            ids[0] = captureFbo
            GLES30.glDeleteFramebuffers(1, ids, 0)
            captureFbo = 0
        }
        if (captureTexture != 0) {
            ids[0] = captureTexture
            GLES30.glDeleteTextures(1, ids, 0)
            captureTexture = 0
        }
    }

    fun release() {
        releaseCaptureTarget()
        val ids = IntArray(1)
        if (albumTexture != 0) {
            ids[0] = albumTexture
            GLES30.glDeleteTextures(1, ids, 0)
            albumTexture = 0
        }
        if (vbo != 0) {
            ids[0] = vbo
            GLES30.glDeleteBuffers(1, ids, 0)
            vbo = 0
        }
        if (vao != 0) {
            ids[0] = vao
            GLES30.glDeleteVertexArrays(1, ids, 0)
            vao = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        albumUploaded = false
        ready = false
        width = 0
        height = 0
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "AmbientComposite"
    }
}
