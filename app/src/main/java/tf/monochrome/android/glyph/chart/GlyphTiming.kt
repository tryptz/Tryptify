// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

/**
 * The song's beat-to-second map.
 *
 * The sign convention is settled before anything reaches here. StepMania's
 * `#OFFSET` counts the seconds the *chart* leads the audio, which is the
 * opposite of what everything downstream wants; [SscParser] flips it exactly
 * once on the way in, so [offsetSeconds] is already "where beat zero is in the
 * audio" and this class does no further arithmetic on the sign. Inverting it in
 * both places is a bug that cancels out in a chart starting at zero and puts
 * every note on the wrong side of the beat in one that does not.
 *
 * Immutable and free of Android types: this is the piece that decides whether
 * a chart drifts, so it is unit-tested directly rather than through the UI.
 */
class GlyphTiming(
    /** Audio position of beat 0. Positive means the song leads the chart in. */
    val offsetSeconds: Float,
    segments: List<BpmSegment>,
    stops: List<Stop> = emptyList(),
) {

    /** A tempo that holds from [beat] until the next segment. */
    data class BpmSegment(val beat: Float, val bpm: Float)

    /** A pause of [seconds] at [beat], during which the chart does not advance. */
    data class Stop(val beat: Float, val seconds: Float)

    val segments: List<BpmSegment> = segments
        .filter { it.bpm > 0f }
        .sortedBy { it.beat }
        .ifEmpty { listOf(BpmSegment(0f, DEFAULT_BPM)) }

    val stops: List<Stop> = stops.filter { it.seconds > 0f }.sortedBy { it.beat }

    /**
     * Seconds from the start of the audio to the top of each segment.
     *
     * Precomputed so [beatToSeconds] is a binary search plus one multiply
     * rather than a walk from beat zero. A five-minute chart at 16th resolution
     * asks this question tens of thousands of times during a single load.
     */
    private val segmentStartSeconds: FloatArray = FloatArray(this.segments.size).also { starts ->
        var seconds = offsetSeconds
        for (index in this.segments.indices) {
            if (index > 0) {
                val previous = this.segments[index - 1]
                val beats = this.segments[index].beat - previous.beat
                seconds += beats * 60f / previous.bpm
            }
            starts[index] = seconds
        }
    }

    val startBpm: Float get() = segments.first().bpm

    val isConstantTempo: Boolean get() = segments.size == 1

    /** The tempo in force at [beat]. */
    fun bpmAt(beat: Float): Float = segments[segmentIndexFor(beat)].bpm

    /** Audio position, in seconds, of [beat]. */
    fun beatToSeconds(beat: Float): Float {
        val index = segmentIndexFor(beat)
        val segment = segments[index]
        val base = segmentStartSeconds[index] + (beat - segment.beat) * 60f / segment.bpm
        return base + stoppedSecondsBefore(beat)
    }

    /**
     * The beat playing at [seconds].
     *
     * Inverts [beatToSeconds] segment by segment. Stops are handled by holding
     * the beat still for their duration, which is what the player sees: the
     * arrows freeze and the music keeps its place.
     */
    fun secondsToBeat(seconds: Float): Float {
        var index = 0
        while (index + 1 < segments.size && beatToSeconds(segments[index + 1].beat) <= seconds) {
            index += 1
        }
        val segment = segments[index]
        val elapsed = seconds - segmentStartSeconds[index] - stoppedSecondsBefore(segment.beat)
        val raw = segment.beat + elapsed * segment.bpm / 60f

        if (stops.isEmpty()) return raw
        // Walk the stops inside this segment, subtracting each one the position
        // has already passed. There are only ever a handful.
        var beat = segment.beat
        var remaining = elapsed
        for (stop in stops) {
            if (stop.beat < segment.beat) continue
            val toStop = (stop.beat - beat) * 60f / segment.bpm
            if (remaining < toStop) break
            remaining -= toStop
            beat = stop.beat
            if (remaining < stop.seconds) return beat
            remaining -= stop.seconds
        }
        return beat + remaining * segment.bpm / 60f
    }

    /** Total seconds of stops strictly before [beat]. */
    private fun stoppedSecondsBefore(beat: Float): Float {
        if (stops.isEmpty()) return 0f
        var total = 0f
        for (stop in stops) {
            if (stop.beat >= beat) break
            total += stop.seconds
        }
        return total
    }

    private fun segmentIndexFor(beat: Float): Int {
        var low = 0
        var high = segments.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) / 2
            if (segments[middle].beat <= beat) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    /** The range of tempos in the song, for a "150–170" style readout. */
    val bpmRange: ClosedFloatingPointRange<Float>
        get() = segments.minOf { it.bpm }..segments.maxOf { it.bpm }

    companion object {
        /**
         * What a chart with no usable `#BPMS` is played at. A simfile that
         * reaches here is already malformed; 120 keeps it playable rather than
         * dividing by zero.
         */
        const val DEFAULT_BPM = 120f

        fun constant(bpm: Float, offsetSeconds: Float = 0f): GlyphTiming =
            GlyphTiming(offsetSeconds, listOf(BpmSegment(0f, bpm)))
    }
}
