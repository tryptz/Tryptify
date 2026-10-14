// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.DropFinder
import tf.monochrome.android.audio.stepmania.RhythmAnalysis
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.audio.stepmania.StepManiaMapGenerator
import tf.monochrome.android.audio.stepmania.StepManiaRequest
import tf.monochrome.android.audio.stepmania.StepManiaRhythmAnalyzer
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.data.GlyphSortKey
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer
import tf.monochrome.android.glyph.training.GlyphWaveformBuilder
import tf.monochrome.android.ui.glyph.GlyphBoard
import tf.monochrome.android.ui.glyph.GlyphLeaderboardUi

/** The song menu: its sort, its leaderboard order, and its previews. */
class GlyphMenuTest {

    private fun song(title: String, mapped: Boolean, seconds: Int, bpm: Float?, artist: String = "A") = GlyphSong(
        trackId = title,
        chartId = "c-$title",
        title = title,
        artist = artist,
        filePath = "/x/$title.mp3",
        artworkUri = null,
        durationSeconds = seconds,
        codec = AudioCodec.MP3,
        bpm = bpm,
        difficulties = emptyList(),
        chartState = if (mapped) GlyphChartState.READY else GlyphChartState.NOT_GENERATED,
    )

    private val songs = listOf(
        song("delta", mapped = false, seconds = 200, bpm = null),
        song("Alpha", mapped = true, seconds = 300, bpm = 140f),
        song("charlie", mapped = true, seconds = 100, bpm = 90f),
        song("bravo", mapped = false, seconds = 250, bpm = null),
    )

    private fun order(sort: GlyphSongSort) = sort.apply(songs).map { it.title }

    @Test
    fun mappedSongsComeFirstThenByName() {
        assertEquals(listOf("Alpha", "charlie", "bravo", "delta"), order(GlyphSongSort(GlyphSortKey.MAPPED)))
    }

    @Test
    fun nameIsCaseBlindAndTurnsRound() {
        assertEquals(listOf("Alpha", "bravo", "charlie", "delta"), order(GlyphSongSort(GlyphSortKey.NAME)))
        assertEquals(listOf("delta", "charlie", "bravo", "Alpha"), order(GlyphSongSort(GlyphSortKey.NAME, ascending = false)))
    }

    @Test
    fun durationIsShortestFirst() {
        assertEquals(listOf("charlie", "delta", "bravo", "Alpha"), order(GlyphSongSort(GlyphSortKey.DURATION)))
    }

    @Test
    fun songsWithNoBpmGoLastEitherWay() {
        assertEquals(listOf("charlie", "Alpha", "bravo", "delta"), order(GlyphSongSort(GlyphSortKey.BPM)))
        assertEquals(listOf("Alpha", "charlie", "bravo", "delta"), order(GlyphSongSort(GlyphSortKey.BPM, ascending = false)))
    }

    @Test
    fun pickingTheChosenKeyTurnsItRound() {
        val byName = GlyphSongSort().pick(GlyphSortKey.NAME)
        assertEquals(GlyphSongSort(GlyphSortKey.NAME, ascending = true), byName)
        assertEquals(GlyphSongSort(GlyphSortKey.NAME, ascending = false), byName.pick(GlyphSortKey.NAME))
        assertEquals(GlyphSongSort(GlyphSortKey.BPM, ascending = true), byName.pick(GlyphSortKey.BPM))
    }

    @Test
    fun theLeaderboardFollowsTheSortAndPutsMissingSongsLast() {
        val run = GlyphAttempt(
            id = "r", chartId = "x", songTitle = "Gone", difficulty = StepManiaDifficulty.EASY,
            playedAtEpochMs = 1L, score = 1, accuracy = 1f, maxCombo = 1,
        )
        val tables = mapOf(StepManiaDifficulty.EASY to listOf(run))
        val board = GlyphLeaderboardUi(
            boards = listOf(
                GlyphBoard("c-gone", "Gone", song = null, tables = tables),
                GlyphBoard("c-charlie", "charlie", songs[2], tables),
                GlyphBoard("c-Alpha", "Alpha", songs[1], tables),
            ),
        )
        assertEquals(
            listOf("Alpha", "charlie", "Gone"),
            board.sorted(GlyphSongSort(GlyphSortKey.NAME)).map { it.title },
        )
        assertEquals(
            listOf("charlie", "Alpha", "Gone"),
            board.sorted(GlyphSongSort(GlyphSortKey.DURATION)).map { it.title },
        )
    }

    @Test
    fun theDropIsWhereAQuietStretchLandsInALoudOne() {
        // Four minutes at a level a second: verse, a quiet build, the drop at
        // 120 s, then a loud chorus.
        val levels = List(240) { second ->
            when {
                second < 100 -> 0.4f
                second < 120 -> 0.15f
                else -> 0.9f
            }
        }
        val drop = DropFinder.find(levels, 240f)
        assertNotNull(drop)
        assertEquals(120f, drop!!, 2f)
    }

    @Test
    fun tooLittleToGoOnIsNoDrop() {
        assertNull(DropFinder.find(listOf(0.5f, 0.5f), 10f))
        assertNull(DropFinder.find(List(100) { 0f }, 200f))
    }

    @Test
    fun generatedChartsCarryTheirPreviewAndItReadsBack() {
        val analysis = RhythmAnalysis(
            bpm = 120f,
            offsetSeconds = 0f,
            confidence = 0.9f,
            durationSeconds = 120f,
            // 120 s of sixteenths at 120 BPM; the drums kick in hard at 64 s.
            gridStrengths = FloatArray(960) { tick -> if (tick >= 512) 0.95f else 0.2f },
        )
        val generator = StepManiaMapGenerator(StepManiaRhythmAnalyzer { _, _ -> analysis })
        val request = StepManiaRequest(
            title = "Drop",
            artist = "Test",
            musicFileName = "drop.mp3",
            difficulties = setOf(StepManiaDifficulty.EASY),
        )
        val ssc = generator.generate(SampleEdits.Buffer(FloatArray(8), null, 8), null, request).ssc
        assertTrue(ssc.contains("#SAMPLESTART:"))
        val simfile = SscParser.parse(ssc)!!
        assertEquals(64f, simfile.sampleStartSeconds!!, 1f)
        assertEquals(12f, simfile.sampleLengthSeconds!!, 0f)
    }

    @Test
    fun loudnessIsTheRmsOfTheMix() {
        val builder = GlyphWaveformBuilder(48_000)
        val samples = FloatArray(48_000) { sin(2 * PI * 440 * it / 48_000).toFloat() * 0.5f }
        builder.add(samples, samples, samples.size)
        val levels = builder.loudness(4)
        assertEquals(4, levels.size)
        // A sine's RMS is its amplitude over root two.
        levels.forEach { assertEquals(0.5f / kotlin.math.sqrt(2f), it, 0.01f) }
    }

    @Test
    fun previewsFadeInAndOutAtEachEnd() {
        assertEquals(0f, GlyphPreviewPlayer.fadeAt(0L, 12_000L), 0f)
        assertEquals(1f, GlyphPreviewPlayer.fadeAt(6_000L, 12_000L), 0f)
        assertEquals(0f, GlyphPreviewPlayer.fadeAt(12_000L, 12_000L), 0f)
        assertTrue(GlyphPreviewPlayer.fadeAt(11_000L, 12_000L) in 0.1f..0.9f)
    }
}
