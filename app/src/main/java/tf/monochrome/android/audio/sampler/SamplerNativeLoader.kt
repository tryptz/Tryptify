// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.util.Log

/**
 * Single entry point for `libmonochrome_sampler.so`, following the same shape
 * as [tf.monochrome.android.audio.dsp.DspNativeLoader].
 *
 * Unlike the DSP library this one is *not* loaded at startup. The mix bus is
 * in the audio path on every launch; the sampler is not, and dlopen-ing it for
 * a listener who never opens the Patterns screen is linker work on the launch
 * path for nothing. The first call to [ensureLoaded] happens when the sampler
 * engine is first touched, off the main thread.
 *
 * [available] lets callers degrade instead of crashing: a build shipped
 * without the ABI's .so should leave the Patterns screen explaining itself,
 * not take the process down.
 */
internal object SamplerNativeLoader {

    @Volatile
    private var loaded = false

    @Volatile
    private var failure: Throwable? = null

    private val lock = Any()

    /** True once the library is in the process and its symbols resolve. */
    val available: Boolean
        get() {
            ensureLoaded()
            return loaded
        }

    val loadError: Throwable?
        get() = failure

    fun ensureLoaded() {
        if (loaded || failure != null) return
        synchronized(lock) {
            if (loaded || failure != null) return
            try {
                System.loadLibrary("monochrome_sampler")
                loaded = true
            } catch (error: Throwable) {
                failure = error
                Log.e("SamplerNativeLoader", "libmonochrome_sampler.so unavailable", error)
            }
        }
    }
}
