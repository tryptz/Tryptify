// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * The motion blur's quality scale, shared by Trypt Mania's note trails and the
 * lyric glide: 0 is off, and each step up draws a smoother, longer smear at
 * more GPU work. The numbers behind each level are `qualityFor` in
 * `cpp/motion/motion_blur.cpp`; a level changes grain and reach, never how
 * strong the smear is.
 */
object MotionBlurQuality {
    const val OFF = 0
    const val MAX = 4

    fun coerce(level: Int): Int = level.coerceIn(OFF, MAX)
}

/**
 * Loads `libmonochrome_motion.so`, once, the first time a blur is switched on.
 * A failure is remembered, never thrown: the blur is decoration, so without
 * the library things are simply drawn sharp. On the JVM (the unit tests) the
 * load always fails.
 */
internal object MotionBlurNative {
    // nativeStep's output slots, as motion_jni.cpp numbers them.
    const val OUT_TAPS = 0
    const val OUT_SPACING = 1
    const val OUT_ALPHA = 2
    const val OUT_SIGMA = 3
    const val OUT_VELOCITY = 4
    const val OUT_COUNT = 5

    @Volatile private var attempted = false
    @Volatile private var available = false

    fun ensureLoaded(): Boolean {
        if (attempted) return available
        synchronized(this) {
            if (attempted) return available
            available = try {
                System.loadLibrary("monochrome_motion")
                true
            } catch (error: Throwable) {
                runCatching { android.util.Log.w(TAG, "motion blur unavailable: ${error.message}") }
                false
            }
            attempted = true
        }
        return available
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeReset(ptr: Long)
    external fun nativeStep(
        ptr: Long,
        frameNanos: Long,
        positionPx: Float,
        jumpPx: Float,
        quality: Int,
        spriteSizePx: Float,
        sprites: Int,
        filmExposure: Boolean,
        out: FloatArray,
    )

    private const val TAG = "TryptifyMotion"
}

/**
 * One moving thing's blur: a native speed tracker, fed once a frame with where
 * the thing is, and the trail or blur that speed calls for. Main thread only,
 * as the draw that feeds it is. Allocates nothing per frame.
 */
class MotionBlur private constructor(private var ptr: Long) : AutoCloseable {
    private val out = FloatArray(MotionBlurNative.OUT_COUNT)

    /** Faded copies to draw behind each sprite this frame; 0 = none. */
    val taps: Int get() = out[MotionBlurNative.OUT_TAPS].toInt()

    /** px between copies along the motion: copy k is k × this behind. */
    val spacingPx: Float get() = out[MotionBlurNative.OUT_SPACING]

    /** Alpha of every copy, before the sprite's own. */
    val alpha: Float get() = out[MotionBlurNative.OUT_ALPHA]

    /** Gaussian sigma, px, for a layer blurred whole; 0 = no blur. */
    val sigmaPx: Float get() = out[MotionBlurNative.OUT_SIGMA]

    /** The smoothed speed, px/s, signed with the axis the positions are on. */
    val velocity: Float get() = out[MotionBlurNative.OUT_VELOCITY]

    /**
     * Feed one frame. [positionPx] is anywhere along the axis; only its change
     * counts, and a change over [jumpPx] in one frame is a jump (a seek), not
     * a speed. [spriteSizePx] and [sprites] size the trail and share the
     * frame's copy budget between that many sprites. [filmExposure] exposes for
     * a 1/30 s frame whatever the refresh rate, for slow motion such as the
     * lyric glide that would otherwise smear under a pixel at 120 Hz.
     */
    fun step(
        frameNanos: Long,
        positionPx: Float,
        jumpPx: Float,
        quality: Int,
        spriteSizePx: Float = 0f,
        sprites: Int = 0,
        filmExposure: Boolean = false,
    ) {
        if (ptr == 0L) return
        MotionBlurNative.nativeStep(
            ptr, frameNanos, positionPx, jumpPx, quality, spriteSizePx, sprites, filmExposure, out,
        )
    }

    /** Forget the speed: the next frame starts from rest. */
    fun reset() {
        if (ptr != 0L) MotionBlurNative.nativeReset(ptr)
        out.fill(0f)
    }

    override fun close() {
        if (ptr != 0L) MotionBlurNative.nativeDestroy(ptr)
        ptr = 0L
        out.fill(0f)
    }

    companion object {
        /** A tracker, or null when the native library is not there. */
        fun create(): MotionBlur? {
            if (!MotionBlurNative.ensureLoaded()) return null
            val ptr = MotionBlurNative.nativeCreate()
            return if (ptr == 0L) null else MotionBlur(ptr)
        }
    }
}

/**
 * A [MotionBlur] for as long as [enabled] holds and this stays composed, or
 * null: off costs no tracker, no library load and no JNI call.
 */
@Composable
fun rememberMotionBlur(enabled: Boolean): MotionBlur? {
    val blur = remember(enabled) { if (enabled) MotionBlur.create() else null }
    DisposableEffect(blur) { onDispose { blur?.close() } }
    return blur
}
