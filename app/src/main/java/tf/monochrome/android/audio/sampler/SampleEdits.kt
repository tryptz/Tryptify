// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

/**
 * A stretch of decoded audio, as the stem separators take and return it.
 *
 * All that is left of the Sampler's edit operations: the separators and Trypt
 * Mania's chart generator pass audio around in this shape, and nothing edits
 * it any more.
 */
object SampleEdits {

    data class Buffer(
        val left: FloatArray,
        val right: FloatArray?,
        val sampleRate: Int,
    ) {
        val frames: Int get() = left.size

        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else frames * 1000L / sampleRate

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
