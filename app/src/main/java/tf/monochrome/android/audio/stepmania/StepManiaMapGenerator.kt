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

        val intensity = measureIntensity(analysis)
        val charts = request.difficulties
            .sortedBy { it.ordinal }
            .map { difficulty -> buildChart(analysis, difficulty, request.title.hashCode(), intensity) }
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
     *
     * How many arrows a measure gets follows how hard the song is going
     * there ([intensity], 0 to 1 per measure): a verse is sparser than its
     * chorus, and on Hard and Challenge the loudest parts burst into
     * sixteenths. The arrows the tune does not lead walk a pattern
     * ([PATTERNS], mirrored or flipped), which may change at each measure.
     * Seeded from the title, so a song always charts the same.
     */
    private fun buildChart(
        analysis: RhythmAnalysis,
        difficulty: StepManiaDifficulty,
        seed: Int,
        intensity: FloatArray,
    ): StepChart {
        val strengths = analysis.gridStrengths
        val stems = analysis.stems
        val events = ArrayList<StepEvent>()
        val random = java.util.Random(seed.toLong() * 31 + difficulty.ordinal)
        val library = if (difficulty <= StepManiaDifficulty.EASY) GENTLE_PATTERNS else PATTERNS
        var pattern = transformed(library[random.nextInt(library.size)], random.nextInt(4))
        var patternStep = random.nextInt(pattern.size)
        var patternMeasure = -1
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
            val level = intensity.getOrElse(tick / TICKS_PER_MEASURE) { NEUTRAL_INTENSITY }
            if (!shouldPlace(difficulty, tick, strength, level)) continue

            val sustain = stems?.sustainTicks?.get(tick) ?: 0
            val holdTicks = if (!holding && minimumHold > 0 && sustain >= minimumHold) {
                minOf(sustain, MAX_HOLD_TICKS, strengths.lastIndex - tick)
                    .takeIf { it >= minimumHold } ?: 0
            } else {
                0
            }
            val jump = holdTicks == 0 && !holding && shouldJump(difficulty, tick, strength, level)
            val laneMask = if (jump) {
                // Any pair but one on the arrow just stepped on.
                val pairs = JUMP_PAIRS.filter { lastLane !in it }
                val pair = pairs[random.nextInt(pairs.size)]
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
                val patterned = if (followed == null) {
                    val measure = tick / TICKS_PER_MEASURE
                    if (measure != patternMeasure) {
                        if (patternMeasure >= 0 && random.nextFloat() < PATTERN_CHANGE) {
                            pattern = transformed(library[random.nextInt(library.size)], random.nextInt(4))
                            // Started where it does not repeat the last arrow.
                            patternStep = random.nextInt(pattern.size)
                            if (pattern[patternStep] == lastLane) patternStep += 1
                        }
                        patternMeasure = measure
                    }
                    pattern[patternStep % pattern.size].also { patternStep += 1 }
                } else {
                    null
                }
                val lane = awayFrom(
                    lane = followed ?: patterned!!,
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

    /**
     * Whether a hit this [strength] on [tick] gets an arrow, in a measure
     * going at [level] (0 its calmest, 1 its fullest). Each difficulty reads
     * beats, off-beat eighths and sixteenths, and how strong a hit has to be
     * for each moves from its calm threshold to its full one with the level.
     */
    private fun shouldPlace(
        difficulty: StepManiaDifficulty,
        tick: Int,
        strength: Float,
        level: Float,
    ): Boolean {
        val threshold = when {
            tick % 4 == 0 -> when (difficulty) {
                // Beginner's half notes, then its other beats when the song is full.
                StepManiaDifficulty.BEGINNER -> if (tick % 8 == 0) lerp(0.3f, 0f, level) else lerp(0.95f, 0.6f, level)
                StepManiaDifficulty.EASY -> lerp(0.15f, 0f, level)
                StepManiaDifficulty.MEDIUM -> lerp(0.1f, 0f, level)
                StepManiaDifficulty.HARD, StepManiaDifficulty.CHALLENGE -> 0f
            }
            tick % 2 == 0 -> when (difficulty) {
                StepManiaDifficulty.BEGINNER -> NEVER
                StepManiaDifficulty.EASY -> lerp(NEVER, 0.7f, level)
                StepManiaDifficulty.MEDIUM -> lerp(0.7f, 0.3f, level)
                StepManiaDifficulty.HARD -> lerp(0.45f, 0f, level)
                StepManiaDifficulty.CHALLENGE -> lerp(0.25f, 0f, level)
            }
            else -> when (difficulty) {
                StepManiaDifficulty.BEGINNER, StepManiaDifficulty.EASY -> NEVER
                StepManiaDifficulty.MEDIUM -> lerp(NEVER, 0.9f, level)
                StepManiaDifficulty.HARD -> lerp(0.95f, 0.5f, level)
                StepManiaDifficulty.CHALLENGE -> lerp(0.75f, 0.3f, level)
            }
        }
        return strength >= threshold
    }

    /** A jump: on a big downbeat, and on Hard and Challenge on more strong beats where the song is fullest. */
    private fun shouldJump(
        difficulty: StepManiaDifficulty,
        tick: Int,
        strength: Float,
        level: Float,
    ): Boolean {
        if (tick % 4 != 0) return false
        val downbeat = tick % TICKS_PER_MEASURE == 0
        val half = tick % 8 == 0
        return when (difficulty) {
            StepManiaDifficulty.BEGINNER, StepManiaDifficulty.EASY -> false
            StepManiaDifficulty.MEDIUM -> downbeat && strength >= 0.9f && level >= 0.75f
            StepManiaDifficulty.HARD ->
                (downbeat && strength >= 0.82f) || (half && strength >= 0.95f && level >= 0.8f)
            StepManiaDifficulty.CHALLENGE ->
                (half && strength >= 0.75f) || (strength >= 0.92f && level >= 0.8f)
        }
    }

    private fun lerp(calm: Float, full: Float, level: Float): Float = calm + (full - calm) * level

    /** [pattern] as is (0), mirrored left to right (1), flipped up and down (2), or both (3). */
    private fun transformed(pattern: IntArray, how: Int): IntArray = IntArray(pattern.size) { i ->
        var lane = pattern[i]
        if (how and 1 != 0) lane = if (lane == 0) 3 else if (lane == 3) 0 else lane
        if (how and 2 != 0) lane = if (lane == 1) 2 else if (lane == 2) 1 else lane
        lane
    }

    internal companion object {
        const val TICKS_PER_MEASURE = 16

        /**
         * The shapes single arrows walk, lanes left 0, down 1, up 2, right 3,
         * each also played mirrored or flipped. None steps on the same arrow
         * twice in a row, round its end included.
         */
        private val GENTLE_PATTERNS = arrayOf(
            intArrayOf(0, 1, 2, 3), // stairs
            intArrayOf(0, 3), // across
            intArrayOf(1, 2), // up and down
            intArrayOf(0, 1, 3, 2), // round
            intArrayOf(0, 2, 3, 1), // the other way round
            intArrayOf(0, 1, 0, 2), // from the left
        )
        private val PATTERNS = GENTLE_PATTERNS + arrayOf(
            intArrayOf(0, 1, 2, 3, 2, 1), // roll there and back
            intArrayOf(0, 2, 1, 3), // zigzag
            intArrayOf(0, 1, 3, 1), // triangle
            intArrayOf(0, 3, 1, 3, 2, 3), // round the right foot
            intArrayOf(0, 2, 0, 3, 1, 3), // candles
            intArrayOf(0, 1, 2, 3, 1, 0, 3, 2), // stairs, then crossed back
        )

        /** Every pair a jump can take. */
        private val JUMP_PAIRS = listOf(
            intArrayOf(0, 3), intArrayOf(1, 2), intArrayOf(0, 1),
            intArrayOf(2, 3), intArrayOf(0, 2), intArrayOf(1, 3),
        )

        /** How often a measure starts a new pattern rather than carrying on. */
        private const val PATTERN_CHANGE = 0.55f

        /** A threshold no hit reaches. */
        private const val NEVER = 1.01f

        /** The level of every measure when the song keeps one level throughout. */
        const val NEUTRAL_INTENSITY = 0.6f

        /** How far apart the song's quieter and fuller measures must be to count as sections. */
        private const val SECTION_SPREAD = 0.04f

        /**
         * How hard the song is going in each measure, 0 to 1: the measure's
         * mean hit (drums, tune or bass, the strongest), smoothed with its
         * neighbours so a fill does not flip it, then placed between the
         * song's quieter (15th percentile) and fuller (85th) measures. A song
         * that keeps one level is [NEUTRAL_INTENSITY] throughout.
         */
        fun measureIntensity(analysis: RhythmAnalysis): FloatArray {
            val strengths = analysis.gridStrengths
            val stems = analysis.stems
            val measures = (strengths.size + TICKS_PER_MEASURE - 1) / TICKS_PER_MEASURE
            if (measures == 0) return FloatArray(0)
            val raw = FloatArray(measures) { measure ->
                val from = measure * TICKS_PER_MEASURE
                val until = minOf(strengths.size, from + TICKS_PER_MEASURE)
                var sum = 0f
                for (tick in from until until) {
                    sum += maxOf(
                        strengths[tick],
                        (stems?.melodyOnsets?.get(tick) ?: 0f) * MELODY_WEIGHT,
                        (stems?.bassOnsets?.get(tick) ?: 0f) * BASS_WEIGHT,
                    )
                }
                sum / (until - from)
            }
            val smooth = FloatArray(measures) { m ->
                val before = raw[maxOf(0, m - 1)]
                val after = raw[minOf(measures - 1, m + 1)]
                0.25f * before + 0.5f * raw[m] + 0.25f * after
            }
            val sorted = smooth.sortedArray()
            val low = sorted[(sorted.lastIndex * 0.15f).toInt()]
            val high = sorted[(sorted.lastIndex * 0.85f + 0.5f).toInt()]
            if (high - low < SECTION_SPREAD) return FloatArray(measures) { NEUTRAL_INTENSITY }
            return FloatArray(measures) { m -> ((smooth[m] - low) / (high - low)).coerceIn(0f, 1f) }
        }

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
