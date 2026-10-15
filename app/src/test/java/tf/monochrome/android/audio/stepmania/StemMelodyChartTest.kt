// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.SscParser

/**
 * Charts that follow the tune as well as the kit: the vocal, melody and bass
 * stems read on the drums' grid, and what the chart does with them.
 */
class StemMelodyChartTest {

    private val sampleRate = 8_000

    /** A click every half second (120 BPM), as a drum stem. */
    private fun clicks(seconds: Int): SampleEdits.Buffer {
        val left = FloatArray(sampleRate * seconds)
        var at = 0
        while (at < left.size) {
            left[at] = 1f
            if (at + 1 < left.size) left[at + 1] = -0.6f
            at += sampleRate / 2
        }
        return SampleEdits.Buffer(left, null, sampleRate)
    }

    private fun tone(into: FloatArray, startSeconds: Float, lengthSeconds: Float, hz: Float) {
        val from = (startSeconds * sampleRate).roundToInt()
        val until = minOf(into.size, from + (lengthSeconds * sampleRate).roundToInt())
        for (i in from until until) into[i] += sin(2 * PI * hz * (i - from) / sampleRate).toFloat() * 0.5f
    }

    private fun tickOf(analysis: RhythmAnalysis, seconds: Float): Int =
        ((seconds - analysis.offsetSeconds) / analysis.subdivisionSeconds).roundToInt()

    @Test
    fun aSungLineIsReadOnTheDrumsGrid() {
        val seconds = 12
        val drums = clicks(seconds)
        val vocals = FloatArray(sampleRate * seconds)
        // Short notes on the off-beat eighths, each a step higher than the last,
        // then one note held for a second and a half.
        val pitches = floatArrayOf(220f, 262f, 330f, 392f)
        val starts = (0 until 8).map { 0.25f + it * 0.5f }
        starts.forEachIndexed { index, start -> tone(vocals, start, 0.18f, pitches[index % 4]) }
        val heldStart = 6.25f
        tone(vocals, heldStart, 1.5f, 294f)
        val stems = mapOf(
            Stem.DRUMS to drums,
            Stem.VOCALS to SampleEdits.Buffer(vocals, null, sampleRate),
        )

        val analysis = StemRhythmAnalyzer().analyze(drums, stems)

        assertEquals(120f, analysis.bpm, 1.5f)
        val features = analysis.stems
        assertNotNull("a sung stem gives the chart something to follow", features)
        features!!
        // Only the stems that held anything count as heard.
        assertEquals(setOf(StemLayer.VOCALS), features.layers)

        val noteTicks = starts.map { tickOf(analysis, it) }
        for (tick in noteTicks) {
            assertTrue("a sung note starts on tick $tick", features.melodyOnsets[tick] >= 0.5f)
            assertTrue("a short note is not held", features.sustainTicks[tick] < 4)
        }
        // Halfway between two notes nothing starts.
        for (tick in noteTicks) {
            assertTrue("no note on tick ${tick + 2}", features.melodyOnsets[tick + 2] < 0.3f)
        }
        // Each of the first four notes is higher than the one before it.
        for (i in 1 until 4) {
            assertTrue(
                "the tune rises from note ${i - 1} to $i",
                features.contour[noteTicks[i]] > features.contour[noteTicks[i - 1]],
            )
        }
        // The held note rings for most of its second and a half: twelve sixteenths.
        val held = features.sustainTicks[tickOf(analysis, heldStart)]
        assertTrue("the held note is held, got $held", held in 9..13)
    }

    @Test
    fun drumsAloneHaveNoStemFeatures() {
        val drums = clicks(8)
        assertNull(StemRhythmAnalyzer().analyze(drums, mapOf(Stem.DRUMS to drums)).stems)
        // A stem the separator returned empty counts as not heard.
        val silent = SampleEdits.Buffer(FloatArray(drums.frames), null, sampleRate)
        assertNull(
            StemRhythmAnalyzer().analyze(drums, mapOf(Stem.DRUMS to drums, Stem.VOCALS to silent)).stems,
        )
    }

    @Test
    fun layerEnvelopesStreamedMatchTheWholeBuffer() {
        val drums = clicks(6)
        val other = FloatArray(drums.frames).also { tone(it, 1f, 2f, 440f) }
        val piano = FloatArray(drums.frames).also { tone(it, 2f, 1f, 523f) }
        val stems = mapOf(
            Stem.OTHER to SampleEdits.Buffer(other, other.copyOf(), sampleRate),
            Stem.PIANO to SampleEdits.Buffer(piano, null, sampleRate),
        )
        val whole = StemLayerEnvelopes(sampleRate).apply { add(stems, drums.frames, 0, drums.frames) }
        val streamed = StemLayerEnvelopes(sampleRate)
        var at = 0
        while (at < drums.frames) {
            val n = minOf(drums.frames - at, 1_337)
            streamed.add(stems, drums.frames, at, n)
            at += n
        }
        for (layer in StemLayer.entries) {
            val a = whole.builders.getValue(layer)
            val b = streamed.builders.getValue(layer)
            assertEquals(drums.frames.toLong(), b.frames)
            assertTrue(a.raw().contentEquals(b.raw()))
            assertTrue(a.crossingRates().contentEquals(b.crossingRates()))
        }
    }

    // ── what the chart does with them ───────────────────────────────────

    private val ticks = 256

    /** Drums on the beats only; nothing on the off-beat sixteenths. */
    private fun beatOnly(stems: StemFeatures?) = RhythmAnalysis(
        bpm = 120f,
        offsetSeconds = 0f,
        confidence = 0.9f,
        durationSeconds = ticks / 8f,
        gridStrengths = FloatArray(ticks) { if (it % 4 == 0) 0.9f else 0.05f },
        stems = stems,
    )

    private fun features(
        melody: (Int) -> Float = { 0f },
        sustain: (Int) -> Int = { 0 },
        contour: (Int) -> Float = { 0f },
    ) = StemFeatures(
        melodyOnsets = FloatArray(ticks) { melody(it) },
        bassOnsets = FloatArray(ticks),
        sustainTicks = IntArray(ticks) { sustain(it) },
        contour = FloatArray(ticks) { contour(it) },
        layers = setOf(StemLayer.VOCALS),
    )

    private fun charts(analysis: RhythmAnalysis) = StepManiaMapGenerator { _, _ -> analysis }.generate(
        analysis,
        StepManiaRequest("Tune", "Test", "tune.flac", difficulties = StepManiaDifficulty.entries.toSet()),
    )

    @Test
    fun silentStemsChartExactlyLikeDrumsAlone() {
        val drumsOnly = charts(beatOnly(null))
        val silent = charts(beatOnly(features()))
        assertEquals(drumsOnly.charts, silent.charts)
    }

    @Test
    fun theTuneAddsArrowsTheDrumsDidNot() {
        // Notes on the off-beat eighths and on some sixteenths, where the drums are silent.
        val tune = features(melody = { if (it % 4 == 2 || it % 8 == 3) 0.95f else 0f }, contour = { 8f })
        val drumsOnly = charts(beatOnly(null)).charts.associateBy { it.difficulty }
        val withTune = charts(beatOnly(tune)).charts.associateBy { it.difficulty }
        fun gained(difficulty: StepManiaDifficulty): Set<Int> {
            val before = drumsOnly.getValue(difficulty).events.map { it.tick }.toSet()
            return withTune.getValue(difficulty).events.map { it.tick }.toSet() - before
        }
        // Medium reads eighths: it picks up the tune's off-beat eighths.
        assertTrue(gained(StepManiaDifficulty.MEDIUM).any { it % 4 == 2 })
        // Hard and Challenge already have every eighth; they pick up its sixteenths.
        assertTrue(gained(StepManiaDifficulty.HARD).any { it % 2 == 1 })
        assertTrue(gained(StepManiaDifficulty.CHALLENGE).any { it % 2 == 1 })
        // Beginner stays on the beat whatever the tune does.
        assertTrue(withTune.getValue(StepManiaDifficulty.BEGINNER).events.all { it.tick % 4 == 0 })
    }

    @Test
    fun arrowsStepWithTheTune() {
        // Off-beat notes climbing a semitone and more each time, then falling.
        val tune = features(
            melody = { if (it % 4 == 2) 0.95f else 0f },
            contour = { tick -> 8f + 0.15f * ((tick / 4) % 8).let { if (it < 4) it else 7 - it } },
        )
        val hard = charts(beatOnly(tune)).charts.first { it.difficulty == StepManiaDifficulty.HARD }
        val tuneEvents = hard.events.filter { it.tick % 4 == 2 && it.laneCount == 1 }
        val lanes = tuneEvents.map { Integer.numberOfTrailingZeros(it.laneMask) }
        var withTheTune = 0
        var steps = 0
        for (i in 1 until tuneEvents.size) {
            val from = tuneEvents[i - 1].tick / 4 % 8
            val to = tuneEvents[i].tick / 4 % 8
            val rising = to in 1..3
            val falling = to in 5..7 || (from == 4 && to == 5)
            if (!rising && !falling) continue
            steps += 1
            val moved = lanes[i] - lanes[i - 1]
            // Off the edge it turns back, so only count steps with room to move.
            if (rising && lanes[i - 1] < 3 && moved > 0) withTheTune += 1
            if (falling && lanes[i - 1] > 0 && moved < 0) withTheTune += 1
            if ((rising && lanes[i - 1] == 3) || (falling && lanes[i - 1] == 0)) steps -= 1
        }
        assertTrue("arrows step with the tune: $withTheTune of $steps", steps > 4 && withTheTune * 10 >= steps * 7)
    }

    @Test
    fun heldNotesBecomeFreezeArrowsThatPlay() {
        // A held sung note at the start of every second measure.
        val tune = features(
            melody = { if (it % 32 == 0) 1f else 0f },
            sustain = { if (it % 32 == 0) 12 else 0 },
            contour = { 8f },
        )
        val generated = charts(beatOnly(tune))
        val byDifficulty = generated.charts.associateBy { it.difficulty }

        assertTrue(byDifficulty.getValue(StepManiaDifficulty.BEGINNER).events.none { it.holdTicks > 0 })
        for (difficulty in listOf(StepManiaDifficulty.EASY, StepManiaDifficulty.MEDIUM, StepManiaDifficulty.HARD)) {
            val chart = byDifficulty.getValue(difficulty)
            val holds = chart.events.filter { it.holdTicks > 0 }
            assertTrue("$difficulty has freeze arrows", holds.isNotEmpty())
            for (hold in holds) {
                assertEquals(12, hold.holdTicks)
                // Nothing lands on the held arrow, and nothing needs two more feet.
                val during = chart.events.filter { it.tick in hold.tick + 1..hold.tick + hold.holdTicks }
                assertTrue(during.none { it.laneMask and hold.laneMask != 0 })
                assertTrue(during.none { it.laneCount > 1 })
                if (difficulty == StepManiaDifficulty.EASY) assertTrue(during.isEmpty())
            }
        }

        // And the game reads them back as holds that end where they should.
        val parsed = SscParser.parse(generated.ssc)!!
        val medium = parsed.chart(StepManiaDifficulty.MEDIUM)!!
        val holds = medium.notes.filter { it.type == GlyphNoteType.HOLD }
        assertTrue(holds.isNotEmpty())
        // Twelve sixteenths at 120 BPM is a second and a half.
        holds.forEach { assertEquals(1.5f, it.holdDurationSeconds, 1e-3f) }
        assertTrue(generated.ssc.contains("#DESCRIPTION:Generated from the drum and vocal stems;"))
    }

    @Test
    fun noArrowRepeatsOnTheNextNoteOrUnderAHold() {
        val tune = features(
            melody = { if (it % 2 == 1) 0.9f else 0f },
            sustain = { if (it % 64 == 1) 8 else 0 },
            contour = { 8f },
        )
        for (chart in charts(beatOnly(tune)).charts) {
            val singles = chart.events.filter { it.laneCount == 1 }
            for (i in 1 until singles.size) {
                val previous = chart.events[chart.events.indexOf(singles[i]) - 1]
                if (previous.laneCount == 1) {
                    assertTrue(
                        "${chart.difficulty}: no jack at ${singles[i].tick}",
                        previous.laneMask != singles[i].laneMask,
                    )
                }
            }
        }
    }

    @Test
    fun theDescriptionNamesEveryStemHeard() {
        val all = StemFeatures(
            FloatArray(4), FloatArray(4), IntArray(4), FloatArray(4),
            layers = setOf(StemLayer.MELODY, StemLayer.BASS, StemLayer.VOCALS),
        )
        val analysis = RhythmAnalysis(120f, 0f, 1f, 2f, FloatArray(4), all)
        assertEquals("Generated from the drum, bass, vocal and melody stems", SscWriter.description(analysis))
        assertEquals(
            "Generated from the isolated drum stem",
            SscWriter.description(analysis.copy(stems = null)),
        )
    }
}
