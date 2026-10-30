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
    fun tempoBetweenWholeHopsStaysOnTheBeat() {
        // 150.5 BPM lies between the lags' 150 and 153.8: charted as 150,
        // the arrows were half a beat off by 0:45.
        val sampleRate = 1_000
        val frames = sampleRate * 120
        val left = FloatArray(frames)
        val bpm = 150.5
        val firstBeat = 0.31
        var beat = 0
        while (true) {
            val frame = Math.round((firstBeat + beat * 60.0 / bpm) * sampleRate).toInt()
            if (frame + 1 >= frames) break
            left[frame] = 1f
            left[frame + 1] = -0.6f
            beat += 1
        }
        val buffer = SampleEdits.Buffer(left, null, sampleRate)

        val result = StemRhythmAnalyzer().analyze(buffer, buffer)

        assertEquals(150.5f, result.bpm, 0.05f)
        // The grid's beat nearest the song's end is still on the click.
        val last = firstBeat + (beat - 1) * 60.0 / bpm
        val beats = Math.round((last - result.offsetSeconds) * result.bpm / 60.0)
        val predicted = result.offsetSeconds + beats * 60.0 / result.bpm
        assertEquals(last, predicted, 0.02)
    }

    /**
     * [seconds] of a hit on every beat at [bpm], first at [firstBeat] seconds:
     * 40 ms dying away, as a drum does. A two-sample click leaves most of a
     * slow song's envelope at nothing, and the analyser reads that as no
     * pulse at all.
     */
    private fun clickTrack(bpm: Double, seconds: Int, sampleRate: Int, firstBeat: Double = 0.31): SampleEdits.Buffer {
        val left = FloatArray(sampleRate * seconds)
        val length = sampleRate / 25
        var beat = 0
        while (true) {
            val frame = Math.round((firstBeat + beat * 60.0 / bpm) * sampleRate).toInt()
            if (frame + length >= left.size) break
            for (j in 0 until length) {
                val sign = if (j % 2 == 0) 1f else -1f
                left[frame + j] += sign * Math.exp(-j / (0.008 * sampleRate)).toFloat()
            }
            beat += 1
        }
        return SampleEdits.Buffer(left, null, sampleRate)
    }

    private fun envelopeOf(buffer: SampleEdits.Buffer) = TransientEnvelopeBuilder(buffer.sampleRate).apply {
        add(buffer.left, buffer.right, 0, buffer.frames)
    }

    @Test
    fun aRateHundredsDoNotDivideKeepsTheBeat() {
        // 22050 Hz hops 220 samples: 100.23 a second, not 100. Read as 100,
        // 120 BPM came out 119.73 and the arrows drifted off.
        val buffer = clickTrack(120.0, 120, 22_050)
        val result = StemRhythmAnalyzer().analyze(buffer, buffer)
        assertEquals(120f, result.bpm, 0.05f)
        val last = 0.31 + Math.floor((120 - 1 - 0.31) * 2) * 0.5
        val beats = Math.round((last - result.offsetSeconds) * result.bpm / 60.0)
        assertEquals(last, result.offsetSeconds + beats * 60.0 / result.bpm, 0.02)
    }

    @Test
    fun theMixTempoSettlesTheOctave() {
        val envelope = envelopeOf(clickTrack(80.0, 60, 1_000))
        val drums = StemRhythmAnalyzer().analyze(envelope)
        assertEquals(80f, drums.bpm, 0.05f)

        // The mix says twice that: the chart follows it, its beats still on the clicks.
        val doubled = StemRhythmAnalyzer().analyze(envelope, octaveHint = 160.4f)
        assertEquals(160f, doubled.bpm, 0.1f)
        val beats = Math.round((0.31 - doubled.offsetSeconds) * doubled.bpm / 60.0)
        assertEquals(0.31, doubled.offsetSeconds + beats * 60.0 / doubled.bpm, 0.02)

        // No power of two from the drums (a two-step's 2.5), or out of range: the drums' own.
        assertEquals(80f, StemRhythmAnalyzer().analyze(envelope, octaveHint = 200f).bpm, 0.05f)
        assertEquals(80f, StemRhythmAnalyzer().analyze(envelope, octaveHint = 40f).bpm, 0.05f)
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
