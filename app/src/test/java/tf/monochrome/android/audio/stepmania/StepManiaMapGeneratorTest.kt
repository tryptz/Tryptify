// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

class StepManiaMapGeneratorTest {

    @Test
    fun drumPulseFindsBpmAndOffset() {
        val sampleRate = 1_000
        val frames = sampleRate * 16
        val left = FloatArray(frames)
        val firstBeat = 200
        val beatFrames = 500 // 120 BPM
        var beat = firstBeat
        while (beat < frames) {
            left[beat] = 1f
            if (beat + 1 < frames) left[beat + 1] = -0.6f
            beat += beatFrames
        }
        val buffer = SampleEdits.Buffer(left, null, sampleRate)

        val result = StemRhythmAnalyzer().analyze(buffer, buffer)

        assertEquals(120f, result.bpm, 1.5f)
        assertEquals(0.2f, result.offsetSeconds, 0.04f)
        assertTrue(result.confidence > 0.7f)
        assertTrue(result.gridStrengths.size > 100)
    }

    @Test
    fun isolatedDrumsFeedAllRequestedDifficulties() {
        val analysis = RhythmAnalysis(
            bpm = 150f,
            offsetSeconds = 0.125f,
            confidence = 0.9f,
            durationSeconds = 12f,
            gridStrengths = FloatArray(176) { tick ->
                when {
                    tick % 16 == 0 -> 1f
                    tick % 4 == 0 -> 0.8f
                    tick % 2 == 0 -> 0.5f
                    else -> 0.7f
                }
            },
        )
        val analyzer = StepManiaRhythmAnalyzer { _, drums ->
            assertTrue(drums != null)
            analysis
        }
        val input = SampleEdits.Buffer(FloatArray(8_000), null, 1_000)
        val request = StepManiaRequest(
            title = "Test; Song",
            artist = "Tryptify\nTests",
            musicFileName = "test.flac",
            difficulties = setOf(
                StepManiaDifficulty.EASY,
                StepManiaDifficulty.MEDIUM,
                StepManiaDifficulty.HARD,
            ),
        )

        val result = StepManiaMapGenerator(analyzer).generate(
            mix = input,
            stems = mapOf(Stem.DRUMS to input),
            request = request,
        )

        assertEquals(3, result.charts.size)
        assertTrue(result.charts[0].events.size < result.charts[1].events.size)
        assertTrue(result.charts[1].events.size < result.charts[2].events.size)
        assertTrue(result.charts.all { chart -> chart.events.all { it.laneCount in 1..2 } })
        assertTrue(result.ssc.contains("#TITLE:Test, Song;"))
        assertTrue(result.ssc.contains("#ARTIST:Tryptify Tests;"))
        assertTrue(result.ssc.contains("#MUSIC:test.flac;"))
        assertTrue(result.ssc.contains("#OFFSET:-0.125;"))
        assertTrue(result.ssc.contains("#BPMS:0.000=150.000;"))
        assertEquals(3, result.ssc.windowed("#NOTEDATA:;".length).count { it == "#NOTEDATA:;" })
    }

    @Test
    fun sscRowsAreFourLanesAndMeasuresHaveSixteenRows() {
        val chart = StepChart(
            difficulty = StepManiaDifficulty.EASY,
            events = listOf(StepEvent(0, 0b0001), StepEvent(4, 0b0100)),
            lengthTicks = 16,
        )
        val analysis = RhythmAnalysis(120f, 0f, 1f, 2f, FloatArray(16))
        val text = SscWriter.write(
            StepManiaRequest("Rows", "Test", "rows.mp3", difficulties = setOf(StepManiaDifficulty.EASY)),
            analysis,
            listOf(chart),
        )
        val rows = text.lineSequence().filter { line ->
            line.length == 4 && line.all { it == '0' || it == '1' }
        }.toList()

        assertEquals(16, rows.size)
        assertEquals("1000", rows[0])
        assertEquals("0010", rows[4])
    }
}
