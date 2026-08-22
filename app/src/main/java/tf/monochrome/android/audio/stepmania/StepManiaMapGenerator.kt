// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import java.util.Locale
import kotlin.math.ceil
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

/**
 * Turns decoded PCM and an optional separated drum stem into a complete SSC.
 *
 * This is intentionally an offline service. Decode and stem separation remain
 * owned by the existing sampler pipeline; this class only consumes their float
 * buffers and never touches playback state or the realtime native engine.
 */
class StepManiaMapGenerator(
    private val rhythmAnalyzer: StepManiaRhythmAnalyzer = StemRhythmAnalyzer(),
) {

    fun generate(
        mix: SampleEdits.Buffer,
        stems: Map<Stem, SampleEdits.Buffer>,
        request: StepManiaRequest,
    ): GeneratedSimfile = generate(mix, stems[Stem.DRUMS], request)

    fun generate(
        mix: SampleEdits.Buffer,
        drumStem: SampleEdits.Buffer?,
        request: StepManiaRequest,
    ): GeneratedSimfile {
        require(request.title.isNotBlank()) { "title must not be blank" }
        require(request.musicFileName.isNotBlank()) { "music file name must not be blank" }
        require(request.difficulties.isNotEmpty()) { "at least one difficulty is required" }

        val analysis = rhythmAnalyzer.analyze(mix, drumStem)
        val charts = request.difficulties
            .sortedBy { it.ordinal }
            .map { difficulty -> buildChart(analysis, difficulty, request.title.hashCode()) }
        return GeneratedSimfile(
            ssc = SscWriter.write(request, analysis, charts),
            analysis = analysis,
            charts = charts,
        )
    }

    private fun buildChart(
        analysis: RhythmAnalysis,
        difficulty: StepManiaDifficulty,
        seed: Int,
    ): StepChart {
        val strengths = analysis.gridStrengths
        val events = ArrayList<StepEvent>()
        val laneOrder = if (seed and 1 == 0) LANE_ORDER_A else LANE_ORDER_B
        var noteIndex = kotlin.math.abs(seed % laneOrder.size)

        for (tick in strengths.indices) {
            val strength = strengths[tick]
            if (!shouldPlace(difficulty, tick, strength)) continue

            val lane = laneOrder[noteIndex % laneOrder.size]
            val jump = shouldJump(difficulty, tick, strength)
            val laneMask = if (jump) {
                val pair = JUMP_PAIRS[(noteIndex / 3) % JUMP_PAIRS.size]
                (1 shl pair[0]) or (1 shl pair[1])
            } else {
                1 shl lane
            }
            events += StepEvent(tick, laneMask)
            noteIndex += 1
        }

        return StepChart(
            difficulty = difficulty,
            events = events,
            lengthTicks = strengths.size.coerceAtLeast(TICKS_PER_MEASURE),
        )
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
    }
}

/** Minimal, deterministic StepMania 5 SSC serializer. */
internal object SscWriter {

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
        appendLine()

        for (chart in charts) {
            appendLine("#NOTEDATA:;")
            appendLine("#CHARTNAME:Tryptify ${chart.difficulty.sscName};")
            appendLine("#STEPSTYPE:dance-single;")
            appendLine("#DESCRIPTION:Generated from the isolated drum stem;")
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

    private fun noteRows(chart: StepChart): String {
        val measures = ceil(chart.lengthTicks / TICKS_PER_MEASURE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        val masks = IntArray(measures * TICKS_PER_MEASURE)
        for (event in chart.events) {
            if (event.tick in masks.indices) masks[event.tick] = masks[event.tick] or event.laneMask
        }

        return buildString {
            for (measure in 0 until measures) {
                for (row in 0 until TICKS_PER_MEASURE) {
                    val mask = masks[measure * TICKS_PER_MEASURE + row]
                    for (lane in 0 until 4) append(if (mask and (1 shl lane) != 0) '1' else '0')
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
