package tf.monochrome.android.audio.usb

import kotlin.math.roundToLong

/**
 * Maps playout time to media time across speed changes.
 *
 * On the paths where the app changes speed itself — the hi-res float path and
 * exclusive USB — the output sink only ever sees the finished audio, so its
 * clock runs in *playout* time: one second of output is one second, whatever
 * speed produced it. The player needs *media* time. At a constant speed the
 * two differ by a factor; once speed changes mid-track the factor is only
 * right from the moment of the change onwards, so a single ratio applied to
 * the whole track (what the USB path used to do) jumps the position on every
 * change.
 *
 * This keeps one checkpoint per change: from playout time `outUs` onwards,
 * media advances `factor` µs per µs. Checkpoints are added at the *write*
 * position, which runs ahead of what is audible by the output's buffer, so
 * a lookup between the two still resolves against the older checkpoint — the
 * same arrangement DefaultAudioSink uses for its own speed changes.
 *
 * Before the first checkpoint, playout and media time are the same.
 *
 * Pure Kotlin, and not thread-safe: the sink calls it from the playback thread only.
 */
internal class SpeedTimeline {

    private class Checkpoint(val outUs: Long, val mediaUs: Long, val factor: Double)

    private val checkpoints = ArrayDeque<Checkpoint>()

    val isEmpty: Boolean get() = checkpoints.isEmpty()

    /** Forgets everything; from [outUs] media time equals playout time scaled by [factor]. */
    fun reset(outUs: Long, factor: Double) {
        checkpoints.clear()
        checkpoints.addLast(Checkpoint(outUs, outUs, factor))
    }

    fun clear() = checkpoints.clear()

    /** From playout time [outUs] onwards, media advances [factor] µs per µs. */
    fun setFactor(outUs: Long, factor: Double) {
        val last = checkpoints.lastOrNull()
        if (last == null) {
            reset(outUs, factor)
            return
        }
        if (last.factor == factor) return
        // A change requested before the last one took effect replaces it.
        val at = maxOf(outUs, last.outUs)
        // Not mediaAt(): that prunes, and `at` is the write position, ahead
        // of what is audible — pruning up to it would drop the checkpoints
        // the playout lookups in between still resolve against.
        val mediaUs = evaluate(at)
        if (at == last.outUs) checkpoints.removeLast()
        checkpoints.addLast(Checkpoint(at, mediaUs, factor))
    }

    /**
     * Media time at playout time [outUs]. Checkpoints the lookup has passed
     * for good are dropped, since playout only moves forward between resets.
     */
    fun mediaAt(outUs: Long): Long {
        while (checkpoints.size > 1 && checkpoints[1].outUs <= outUs) checkpoints.removeFirst()
        return evaluate(outUs)
    }

    private fun evaluate(outUs: Long): Long {
        val first = checkpoints.firstOrNull() ?: return outUs
        if (outUs < first.outUs) return outUs - first.outUs + first.mediaUs
        val c = checkpoints.lastOrNull { it.outUs <= outUs } ?: first
        return c.mediaUs + ((outUs - c.outUs) * c.factor).roundToLong()
    }

    /**
     * Media time at write position [outUs], without dropping anything.
     * [mediaAt] is for the audible position; this is for the write end, which
     * runs ahead of it.
     */
    fun mediaAtWritePosition(outUs: Long): Long = evaluate(outUs)

    /**
     * Declares that playout time [outUs] is media time [mediaUs], keeping the
     * current factor — for when the source's own timestamps say the mapping
     * has drifted (a gap in the stream, or trimmed gapless frames adding up).
     */
    fun rebase(outUs: Long, mediaUs: Long) {
        val factor = currentFactor()
        while (checkpoints.isNotEmpty() && checkpoints.last().outUs >= outUs) checkpoints.removeLast()
        checkpoints.addLast(Checkpoint(outUs, mediaUs, factor))
    }

    /** The factor in force at the write end, or 1 when there is none. */
    fun currentFactor(): Double = checkpoints.lastOrNull()?.factor ?: 1.0
}
