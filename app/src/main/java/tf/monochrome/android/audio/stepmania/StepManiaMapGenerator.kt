// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import java.util.Locale
import kotlin.math.ceil
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

/**
 * Turns decoded PCM and its separated stems into a complete SSC: the drums
 * for the beat, and the vocals, melody and bass for the tune over it.
 *
 * This is intentionally an offline service. Decode and stem separation remain
 * owned by the existing sampler pipeline; this class only consumes their float
 * buffers and never touches playback state or the realtime native engine.
 */
class StepManiaMapGenerator(
    private val rhythmAnalyzer: StepManiaRhythmAnalyzer = StemRhythmAnalyzer(),
) {

    /** From every stem the separator returned: drums for the beat, the rest for the tune. */
    fun generate(
        mix: SampleEdits.Buffer,
        stems: Map<Stem, SampleEdits.Buffer>,
        request: StepManiaRequest,
    ): GeneratedSimfile {
        require(request.title.isNotBlank()) { "title must not be blank" }
        require(request.musicFileName.isNotBlank()) { "music file name must not be blank" }
        require(request.difficulties.isNotEmpty()) { "at least one difficulty is required" }

        return generate(rhythmAnalyzer.analyze(mix, stems), request)
    }

    fun generate(
        mix: SampleEdits.Buffer,
        drumStem: SampleEdits.Buffer?,
        request: StepManiaRequest,
    ): GeneratedSimfile {
        require(request.title.isNotBlank()) { "title must not be blank" }
        require(request.musicFileName.isNotBlank()) { "music file name must not be blank" }
        require(request.difficulties.isNotEmpty()) { "at least one difficulty is required" }

        return generate(rhythmAnalyzer.analyze(mix, drumStem), request)
    }

    /** Charts from an analysis already made, for audio that was streamed rather than held. */
    fun generate(
        analysis: RhythmAnalysis,
        request: StepManiaRequest,
    ): GeneratedSimfile {
        require(request.title.isNotBlank()) { "title must not be blank" }
        require(request.musicFileName.isNotBlank()) { "music file name must not be blank" }
        require(request.difficulties.isNotEmpty()) { "at least one difficulty is required" }

        val charts = request.difficulties
            .sortedBy { it.ordinal }
            .map { difficulty -> buildChart(analysis, difficulty, request.title.hashCode()) }
        return GeneratedSimfile(
            ssc = SscWriter.write(request, analysis, charts),
            analysis = analysis,
            charts = charts,
        )
    }

    /**
     * One difficulty's arrows.
     *
     * The drums decide where the beat is; with [RhythmAnalysis.stems] the tune
     * and the bass line can add arrows of their own on that grid, at a little
     * less than their face value so the kit still leads. Where the tune led
     * an arrow, it steps the way the tune moved — further right as it rises,
     * left as it falls — and a sung note held long enough becomes a freeze
     * arrow. Without stems this is exactly the drum-only chart.
     */
    private fun buildChart(
        analysis: RhythmAnalysis,
        difficulty: StepManiaDifficulty,
        seed: Int,
    ): StepChart {
        val strengths = analysis.gridStrengths
        val stems = analysis.stems
        val events = ArrayList<StepEvent>()
        val laneOrder = if (seed and 1 == 0) LANE_ORDER_A else LANE_ORDER_B
        var noteIndex = kotlin.math.abs(seed % laneOrder.size)
        val minimumHold = minimumHoldTicks(difficulty)

        // The last single arrow, which the next one steps away from rather
        // than repeating; and the last one the tune led, with its pitch, which
        // the tune's next arrow steps on from. Drum arrows land in between, so
        // the shape the player sees is tune arrow to tune arrow.
        var lastLane = -1
        var lastTuneLane = -1
        var lastContour = 0f
        var lastMelodicTick = Int.MIN_VALUE / 2
        // The freeze arrow being held, through its tail.
        var heldLane = -1
        var heldUntil = -1

        for (tick in strengths.indices) {
            val drum = strengths[tick]
            val melody = (stems?.melodyOnsets?.get(tick) ?: 0f) * MELODY_WEIGHT
            val bass = (stems?.bassOnsets?.get(tick) ?: 0f) * BASS_WEIGHT
            val strength = maxOf(drum, melody, bass)
            val holding = tick <= heldUntil
            if (holding && !placesDuringHold(difficulty, tick)) continue
            if (!shouldPlace(difficulty, tick, strength)) continue

            val sustain = stems?.sustainTicks?.get(tick) ?: 0
            val holdTicks = if (!holding && minimumHold > 0 && sustain >= minimumHold) {
                minOf(sustain, MAX_HOLD_TICKS, strengths.lastIndex - tick)
                    .takeIf { it >= minimumHold } ?: 0
            } else {
                0
            }
            val jump = holdTicks == 0 && !holding && shouldJump(difficulty, tick, strength)
            val laneMask = if (jump) {
                val pair = JUMP_PAIRS[(noteIndex / 3) % JUMP_PAIRS.size]
                lastLane = -1
                (1 shl pair[0]) or (1 shl pair[1])
            } else {
                val contour = stems?.contour?.get(tick) ?: 0f
                val melodic = melody > drum && melody >= bass && contour != 0f
                val delta = contour - lastContour
                val followed = if (
                    melodic && lastTuneLane >= 0 && lastContour != 0f &&
                    tick - lastMelodicTick <= PHRASE_TICKS
                ) {
                    stepWithTune(lastTuneLane, delta)
                } else {
                    null
                }
                val lane = awayFrom(
                    lane = followed ?: laneOrder[noteIndex % laneOrder.size],
                    last = lastLane,
                    held = if (holding) heldLane else -1,
                    // Moved off a taken arrow, a tune-led one keeps going the way the tune went.
                    toward = if (followed != null && followed > lastTuneLane) 1 else -1,
                )
                if (melodic) {
                    lastTuneLane = lane
                    lastContour = contour
                    lastMelodicTick = tick
                }
                lastLane = lane
                if (holdTicks > 0) {
                    heldLane = lane
                    heldUntil = tick + holdTicks
                }
                1 shl lane
            }
            events += StepEvent(tick, laneMask, holdTicks)
            noteIndex += 1
        }

        val lastTail = events.maxOfOrNull { it.tick + it.holdTicks } ?: 0
        return StepChart(
            difficulty = difficulty,
            events = events,
            lengthTicks = maxOf(strengths.size, lastTail + 1, TICKS_PER_MEASURE),
        )
    }

    /**
     * The lane a step [delta] octaves up (or down, negative) from [from] lands
     * on: one lane for a step, two for a leap. Off the edge it turns back, so a
     * tune climbing past the right arrow comes down onto the one beside it.
     * Null when the pitch barely moved, for the usual pattern to take over.
     */
    private fun stepWithTune(from: Int, delta: Float): Int? {
        if (kotlin.math.abs(delta) < STEP_OCTAVES) return null
        val direction = if (delta > 0f) 1 else -1
        val distance = if (kotlin.math.abs(delta) >= LEAP_OCTAVES) 2 else 1
        val target = from + direction * distance
        return (if (target in 0..3) target else from - direction * distance).coerceIn(0, 3)
    }

    /**
     * [lane], or its nearest neighbour when it is the arrow just stepped on or
     * the one being held: the same arrow twice in a row is a jack, and an
     * arrow under a held foot cannot be hit at all. A tie goes [toward] (+1
     * right, -1 left).
     */
    private fun awayFrom(lane: Int, last: Int, held: Int, toward: Int = 1): Int {
        if (lane != last && lane != held) return lane
        for (distance in 1..3) {
            for (candidate in intArrayOf(lane + toward * distance, lane - toward * distance)) {
                if (candidate in 0..3 && candidate != last && candidate != held) return candidate
            }
        }
        return lane
    }

    /** The shortest sung note, in sixteenths, a difficulty turns into a freeze arrow; 0 for none. */
    private fun minimumHoldTicks(difficulty: StepManiaDifficulty): Int = when (difficulty) {
        StepManiaDifficulty.BEGINNER -> 0
        StepManiaDifficulty.EASY -> 8
        StepManiaDifficulty.MEDIUM -> 6
        StepManiaDifficulty.HARD, StepManiaDifficulty.CHALLENGE -> 4
    }

    /** Whether other arrows still come while a freeze arrow is held: none on Easy, beats on Medium. */
    private fun placesDuringHold(difficulty: StepManiaDifficulty, tick: Int): Boolean = when (difficulty) {
        StepManiaDifficulty.BEGINNER, StepManiaDifficulty.EASY -> false
        StepManiaDifficulty.MEDIUM -> tick % 4 == 0
        StepManiaDifficulty.HARD, StepManiaDifficulty.CHALLENGE -> true
    }

    private fun shouldPlace(
        difficulty: StepManiaDifficulty,
        tick: Int,
        strength: Float,
    ): Boolean {
        val quarter = tick % 4 == 0
        val eighth = tick % 2 == 0
        return when (difficulty) {
            StepManiaDifficulty.BEGINNER -> quarter && (tick % 8 == 0 || strength >= 0.7f)
            StepManiaDifficulty.EASY -> quarter || (eighth && strength >= 0.78f)
            StepManiaDifficulty.MEDIUM ->
                (eighth && (quarter || strength >= 0.38f)) || (!eighth && strength >= 0.94f)
            StepManiaDifficulty.HARD -> eighth || strength >= 0.62f
            StepManiaDifficulty.CHALLENGE -> eighth || strength >= 0.34f
        }
    }

    private fun shouldJump(
        difficulty: StepManiaDifficulty,
        tick: Int,
        strength: Float,
    ): Boolean {
        if (tick % TICKS_PER_MEASURE != 0) return false
        return when (difficulty) {
            StepManiaDifficulty.HARD -> strength >= 0.82f
            StepManiaDifficulty.CHALLENGE -> strength >= 0.58f
            else -> false
        }
    }

    private companion object {
        const val TICKS_PER_MEASURE = 16
        val LANE_ORDER_A = intArrayOf(0, 1, 3, 2, 1, 0, 2, 3)
        val LANE_ORDER_B = intArrayOf(3, 2, 0, 1, 2, 3, 1, 0)
        val JUMP_PAIRS = arrayOf(intArrayOf(0, 2), intArrayOf(1, 3))

        /** How much a tune's note start counts against a drum hit's. */
        const val MELODY_WEIGHT = 0.9f
        const val BASS_WEIGHT = 0.75f

        /** A pitch change, in octaves, the arrows step for (about a semitone), and one they leap for. */
        const val STEP_OCTAVES = 0.08f
        const val LEAP_OCTAVES = 0.5f

        /** After this long without a tune-led arrow, the next one starts a new phrase. */
        const val PHRASE_TICKS = 16

        /** The longest freeze arrow: two measures. */
        const val MAX_HOLD_TICKS = 32
    }
}

/** Minimal, deterministic StepMania 5 SSC serializer. */
internal object SscWriter {

    /** StepMania's own default preview length. */
    const val SAMPLE_LENGTH_SECONDS = 12f

    /** The drop, in song seconds, from the sixteenth-note strengths. */
    fun previewStart(analysis: RhythmAnalysis): Float? {
        val grid = analysis.gridStrengths
        if (grid.isEmpty()) return null
        val span = grid.size * analysis.subdivisionSeconds
        val drop = DropFinder.find(grid.asList(), span) ?: return null
        return (analysis.offsetSeconds + drop).coerceAtLeast(0f)
    }

    fun write(
        request: StepManiaRequest,
        analysis: RhythmAnalysis,
        charts: List<StepChart>,
    ): String = buildString {
        appendLine("#VERSION:0.83;")
        appendLine("#TITLE:${tag(request.title)};")
        appendLine("#ARTIST:${tag(request.artist)};")
        appendLine("#CREDIT:${tag(request.credit)};")
        appendLine("#MUSIC:${tag(request.musicFileName)};")
        appendLine("#OFFSET:${decimal(-analysis.offsetSeconds)};")
        appendLine("#BPMS:0.000=${decimal(analysis.bpm)};")
        appendLine("#DISPLAYBPM:${decimal(analysis.bpm)};")
        // Where the song is previewed from: its drop, found in the drum
        // envelope, as an author would set it by ear.
        previewStart(analysis)?.let { start ->
            appendLine("#SAMPLESTART:${decimal(start)};")
            appendLine("#SAMPLELENGTH:${decimal(SAMPLE_LENGTH_SECONDS)};")
        }
        appendLine()

        for (chart in charts) {
            appendLine("#NOTEDATA:;")
            appendLine("#CHARTNAME:Tryptify ${chart.difficulty.sscName};")
            appendLine("#STEPSTYPE:dance-single;")
            appendLine("#DESCRIPTION:${description(analysis)};")
            appendLine("#CHARTSTYLE:;")
            appendLine("#DIFFICULTY:${chart.difficulty.sscName};")
            appendLine("#METER:${chart.difficulty.meter};")
            appendLine("#RADARVALUES:;")
            appendLine("#NOTES:")
            append(noteRows(chart))
            appendLine(";")
            appendLine()
        }
    }

    /** Which stems the charts were read from, as the simfile's description says it. */
    fun description(analysis: RhythmAnalysis): String {
        val layers = analysis.stems?.layers.orEmpty()
        if (layers.isEmpty()) return "Generated from the isolated drum stem"
        val nouns = listOf("drum") + StemLayer.entries.filter { it in layers }.map { it.noun }
        return "Generated from the ${nouns.dropLast(1).joinToString(", ")} and ${nouns.last()} stems"
    }

    private fun noteRows(chart: StepChart): String {
        val measures = ceil(chart.lengthTicks / TICKS_PER_MEASURE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        // A tap is '1'; a freeze arrow is '2' on its head and '3' on its tail.
        val cells = Array(measures * TICKS_PER_MEASURE) { CharArray(4) { '0' } }
        for (event in chart.events) {
            if (event.tick !in cells.indices) continue
            for (lane in 0 until 4) {
                if (event.laneMask and (1 shl lane) == 0) continue
                val tail = event.tick + event.holdTicks
                if (event.holdTicks > 0 && tail in cells.indices) {
                    cells[event.tick][lane] = '2'
                    cells[tail][lane] = '3'
                } else {
                    cells[event.tick][lane] = '1'
                }
            }
        }

        return buildString {
            for (measure in 0 until measures) {
                for (row in 0 until TICKS_PER_MEASURE) {
                    append(cells[measure * TICKS_PER_MEASURE + row])
                    appendLine()
                }
                if (measure + 1 < measures) appendLine(",")
            }
        }
    }

    private fun tag(value: String): String = value
        .replace(';', ',')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    private fun decimal(value: Float): String = String.format(Locale.US, "%.3f", value)

    private const val TICKS_PER_MEASURE = 16
}
