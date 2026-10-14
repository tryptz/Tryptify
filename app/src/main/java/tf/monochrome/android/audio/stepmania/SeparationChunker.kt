// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

/**
 * Cuts a stream of decoded audio into pieces short enough to separate one at a
 * time, each with a little of its neighbours around it.
 *
 * Every frame of the song lands in exactly one piece's *core*, in order. Around
 * the core is up to [contextFrames] of the audio before and after it, which
 * the separator hears but whose output is thrown away: a separator's output is
 * least reliable at the edges of what it was given, and a drum hit on the
 * boundary between two pieces would otherwise come out smeared or doubled.
 *
 * Holds at most one piece and its context at a time, so the memory is the same
 * for a three-minute song and an hour-long one.
 */
class SeparationChunker(
    private val coreFrames: Int,
    private val contextFrames: Int,
    private val stereo: Boolean,
    private val emit: (Chunk) -> Unit,
) {
    init {
        require(coreFrames > 0) { "a piece needs at least one frame" }
        require(contextFrames >= 0) { "context cannot be negative" }
    }

    /**
     * One piece: [left] and [right] (null for mono) with the core at
     * [coreOffset] for [coreFrames] frames, which starts [startFrame] frames
     * into the song.
     */
    class Chunk(
        val left: FloatArray,
        val right: FloatArray?,
        val coreOffset: Int,
        val coreFrames: Int,
        val startFrame: Long,
    ) {
        val frames: Int get() = left.size
    }

    private val capacity = coreFrames + 2 * contextFrames
    private val left = FloatArray(capacity)
    private val right = if (stereo) FloatArray(capacity) else null
    private var size = 0
    /** The song frame [left] index 0 holds. */
    private var bufferStart = 0L
    private var coreStart = 0L

    /** The next [frames] of the song. A mono block into a stereo chunker is copied to both sides. */
    fun add(blockLeft: FloatArray, blockRight: FloatArray?, frames: Int) {
        var offset = 0
        while (offset < frames) {
            val n = minOf(capacity - size, frames - offset)
            System.arraycopy(blockLeft, offset, left, size, n)
            right?.let { System.arraycopy(blockRight ?: blockLeft, offset, it, size, n) }
            size += n
            offset += n
            while (bufferStart + size >= coreStart + coreFrames + contextFrames) emitNext()
        }
    }

    /** The rest of the song, after the last block. */
    fun finish() {
        while (coreStart < bufferStart + size) emitNext()
    }

    private fun emitNext() {
        val end = bufferStart + size
        val coreEnd = minOf(coreStart + coreFrames, end)
        val from = maxOf(bufferStart, coreStart - contextFrames)
        val to = minOf(end, coreEnd + contextFrames)
        val start = (from - bufferStart).toInt()
        val length = (to - from).toInt()
        emit(
            Chunk(
                left = left.copyOfRange(start, start + length),
                right = right?.copyOfRange(start, start + length),
                coreOffset = (coreStart - from).toInt(),
                coreFrames = (coreEnd - coreStart).toInt(),
                startFrame = coreStart,
            ),
        )
        coreStart = coreEnd

        // Keep only what the next piece still needs: its leading context.
        val keepFrom = maxOf(bufferStart, coreStart - contextFrames)
        val drop = (keepFrom - bufferStart).toInt()
        if (drop > 0) {
            System.arraycopy(left, drop, left, 0, size - drop)
            right?.let { System.arraycopy(it, drop, it, 0, size - drop) }
            size -= drop
            bufferStart = keepFrom
        }
    }
}
