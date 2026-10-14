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

    /**
     * stopPrefix[k] is the total length of the first k stops, added up in
     * order — the same sum the walk it replaces produced, so lookups return
     * exactly what they used to, only without the walk.
     */
    private val stopPrefix: FloatArray = FloatArray(this.stops.size + 1).also { prefix ->
        var total = 0f
        for (index in this.stops.indices) {
            total += this.stops[index].seconds
            prefix[index + 1] = total
        }
    }

    /**
     * Where each segment begins in the audio, stops included: what
     * [secondsToBeat] searches to find its segment. Computed with
     * [beatToSeconds] itself, so the search agrees with the old walk exactly.
     */
    private val segmentBoundarySeconds: FloatArray =
        FloatArray(this.segments.size) { index -> beatToSeconds(this.segments[index].beat) }

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
        // The last segment whose start has been reached, found by binary search
        // over the precomputed boundaries. Segment 0 is used before the first
        // boundary too.
        var low = 1
        var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segmentBoundarySeconds[middle] <= seconds) low = middle + 1 else high = middle
        }
        val index = low - 1
        val segment = segments[index]
        val elapsed = seconds - segmentStartSeconds[index] - stoppedSecondsBefore(segment.beat)
        val raw = segment.beat + elapsed * segment.bpm / 60f

        if (stops.isEmpty()) return raw
        // Walk the stops from this segment on, subtracting each one the
        // position has already passed. Those before the segment were already
        // taken out of `elapsed`.
        var beat = segment.beat
        var remaining = elapsed
        var stopIndex = firstStopAtOrAfter(segment.beat)
        while (stopIndex < stops.size) {
            val stop = stops[stopIndex]
            val toStop = (stop.beat - beat) * 60f / segment.bpm
            if (remaining < toStop) break
            remaining -= toStop
            beat = stop.beat
            if (remaining < stop.seconds) return beat
            remaining -= stop.seconds
            stopIndex += 1
        }
        return beat + remaining * segment.bpm / 60f
    }

    /** Total seconds of stops strictly before [beat]. */
    private fun stoppedSecondsBefore(beat: Float): Float {
        if (stops.isEmpty()) return 0f
        return stopPrefix[firstStopAtOrAfter(beat)]
    }

    /** Index of the first stop on or after [beat]; stops.size if there is none. */
    private fun firstStopAtOrAfter(beat: Float): Int {
        var low = 0
        var high = stops.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (stops[middle].beat < beat) low = middle + 1 else high = middle
        }
        return low
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
    val bpmRange: ClosedFloatingPointRange<Float> =
        this.segments.minOf { it.bpm }..this.segments.maxOf { it.bpm }

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
