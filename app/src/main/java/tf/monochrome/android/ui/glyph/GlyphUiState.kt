// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphSimfile
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScoreSnapshot
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphLoopSegment

/**
 * Everything the Glyph screens draw, as one immutable value.
 *
 * Immutable and unidirectional throughout: composables read this and send
 * [GlyphEvent]s back, and never hold gameplay state of their own. The reason is
 * not tidiness — it is that scoring and timing must be identical whether or not
 * a frame was drawn, and state living in a composable is state that only exists
 * while something is looking at it.
 *
 * The one thing deliberately *not* in here is the note stream. Notes are read
 * from the engine inside the playfield's draw scope, because pushing several
 * hundred of them through recomposition sixty to a hundred and sixty-five times
 * a second is the difference between a mode that runs and one that stutters.
 */
data class GlyphUiState(
    val screen: GlyphScreen = GlyphScreen.HOME,
    val songs: List<GlyphSong> = emptyList(),
    val isLoadingSongs: Boolean = true,
    val songQuery: String = "",
    /** How the song list and the leaderboard are ordered; kept between sessions. */
    val songSort: GlyphSongSort = GlyphSongSort(),
    val leaderboard: GlyphLeaderboardUi = GlyphLeaderboardUi(),
    val selectedSong: GlyphSong? = null,
    val simfile: GlyphSimfile? = null,
    val selectedDifficulty: StepManiaDifficulty? = null,
    val generation: GlyphGenerationState? = null,
    val model: GlyphModelUi = GlyphModelUi(),
    /** The name on the high score tables. */
    val playerName: String = "",
    /** Tapping a song plays it from its drop; kept between sessions. */
    val previews: Boolean = true,
    /** Best StepMania grade per difficulty of the selected song, for the menu. */
    val bestGrades: Map<StepManiaDifficulty, GlyphAttempt> = emptyMap(),
    val gameplay: GlyphGameplayUi = GlyphGameplayUi(),
    val training: GlyphTrainingUi = GlyphTrainingUi(),
    val results: GlyphResultsUi? = null,
    val assetWarning: String? = null,
    val error: String? = null,
) {
    val chart: GlyphChart? get() = selectedDifficulty?.let { simfile?.chart(it) }

    val filteredSongs: List<GlyphSong>
        get() = songSort.apply(
            if (songQuery.isBlank()) songs else songs.filter { song ->
                song.title.contains(songQuery, ignoreCase = true) ||
                    song.artist.contains(songQuery, ignoreCase = true)
            },
        )
}

enum class GlyphScreen { HOME, OPTIONS, LEADERBOARD, GAMEPLAY, TRAINING, RESULTS }

/** The leaderboard page: every chart this phone has a high score on. */
data class GlyphLeaderboardUi(
    val loading: Boolean = false,
    val boards: List<GlyphBoard> = emptyList(),
) {
    /** The boards in [sort]'s order; charts whose song has left the library go last. */
    fun sorted(sort: GlyphSongSort): List<GlyphBoard> {
        val (inLibrary, gone) = boards.partition { it.song != null }
        val songOrder = sort.comparator()
        return inLibrary.sortedWith { a, b -> songOrder.compare(a.song!!, b.song!!) } +
            gone.sortedBy { it.title.lowercase() }
    }
}

/** One chart's high score tables, by difficulty. */
data class GlyphBoard(
    val chartId: String,
    val title: String,
    /** The library's song, or null when it is no longer there. */
    val song: GlyphSong?,
    val tables: Map<StepManiaDifficulty, List<GlyphAttempt>>,
)

/**
 * Conversion progress, mirroring the service's own stages.
 *
 * [stage] is the service's text verbatim rather than a re-worded version, so
 * what the player reads is what is actually happening — including an honest
 * "separating on the CPU" when no model backend is installed.
 */
data class GlyphGenerationState(
    val trackId: String,
    val fraction: Float,
    val stage: String,
    val backendName: String? = null,
    val failure: String? = null,
) {
    val isFinished: Boolean get() = fraction >= 1f || failure != null
}

/**
 * The drum separation model the chart generator can use.
 *
 * The app does not ship one: it is hundreds of megabytes, and charts still
 * generate without it, from the built-in separator. A player who wants the
 * better charts imports the model file from their own storage, here.
 */
data class GlyphModelUi(
    /** The installed model's name, or null when charts use the built-in separator. */
    val installedName: String? = null,
    val sizeBytes: Long = 0L,
    val importing: Boolean = false,
    /** How far the copy is, 0 to 1, or null when the file's size is unknown. */
    val progress: Float? = null,
    val stage: String? = null,
    /** The outcome of the last import or removal, until the panel is closed. */
    val message: String? = null,
)

/** Live gameplay readouts. Updated at a rate the eye can follow, not per frame. */
data class GlyphGameplayUi(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val positionSeconds: Float = 0f,
    val durationSeconds: Float = 0f,
    val bpm: Float = 0f,
    val measure: Int = 0,
    val sectionLabel: String = "",
    val score: GlyphScoreSnapshot = GlyphScoreSnapshot(),
    val lastJudgement: GlyphJudgement? = null,
    val lastJudgementAtMs: Long = 0L,
    val heldLanes: Set<GlyphLane> = emptySet(),
    val modifiers: GlyphModifiers = GlyphModifiers(),
    val countInBeatsRemaining: Int = 0,
    /** Whether the last judgement was early, for StepMania's early/late sprite. */
    val lastJudgementEarly: Boolean = true,
    /** StepMania's life bar, 0 to 1. */
    val life: Float = StepManiaScoreKeeper.INITIAL_LIFE,
    /** StepMania's dance-point percent so far, 0 to 1. */
    val percent: Float = 0f,
    /** The run's full combo so far, for the combo's colour. */
    val fullCombo: GlyphFullCombo? = null,
    /**
     * When READY was shown, wall-clock ms; 0 when there is no intro. The
     * music waits for it, so READY and GO never sit over the first notes.
     */
    val introStartedAtMs: Long = 0L,
) {
    val progress: Float
        get() = if (durationSeconds <= 0f) 0f else (positionSeconds / durationSeconds).coerceIn(0f, 1f)
}

/**
 * Play modifiers: StepMania's player options.
 *
 * [turn] is a chart transform and is recorded on the attempt. [speed] (the
 * music rate) and [timingWindowScale] (the Judge) change what a run is worth,
 * so a run under either is marked as such on the high score table. The rest — scroll speed,
 * appearance, reverse, dark, mini, the skin — change how the chart looks and
 * nothing about how it is judged.
 */
data class GlyphModifiers(
    val speed: Float = 1f,
    /**
     * Scroll family and value. Not scoring-relevant — how fast the notes travel
     * changes how hard a chart is to read, not what it is worth — so it is
     * deliberately absent from [altersScoring].
     */
    val scrollMode: GlyphScrollMode = GlyphScrollMode.DEFAULT,
    val pitchLinkedToSpeed: Boolean = true,
    val turn: GlyphTurn = GlyphTurn.OFF,
    val appearance: GlyphAppearance =
        GlyphAppearance.VISIBLE,
    /** Receptors at the top and notes rising, as StepMania's default scroll is. */
    val reverse: Boolean = false,
    /** No receptors: the notes are read against nothing. */
    val dark: Boolean = false,
    /** StepMania's Mini, 0 to 0.5: notes drawn this much smaller, halved. */
    val mini: Float = 0f,
    val failType: GlyphFailType =
        GlyphFailType.IMMEDIATE,
    val metronome: Boolean = false,
    val timingWindowScale: Float = 1f,
    val hitboxScale: Float = 1f,
    val reducedMotion: Boolean = false,
    /** Which effects the playfield draws. Looks only. */
    val graphics: GlyphGraphics = GlyphGraphics(),
    /** The playfield's artwork. Looks only, so not in [altersScoring]. */
    val skin: tf.monochrome.android.glyph.asset.GlyphSkin = tf.monochrome.android.glyph.asset.GlyphSkin.DEFAULT,
) {
    val mirror: Boolean get() = turn == GlyphTurn.MIRROR
    val shuffle: Boolean get() = turn == GlyphTurn.SHUFFLE

    /** True when the run is not comparable with a clean one: marked on the high score table. */
    val altersScoring: Boolean
        get() = speed != 1f || timingWindowScale != 1f
}

/** Training Ground's own state. */
data class GlyphTrainingUi(
    val loop: GlyphLoopSegment? = null,
    val countIn: GlyphCountIn = GlyphCountIn.ONE_BAR,
    val ghostEnabled: Boolean = false,
    val hasGhost: Boolean = false,
    val passCount: Int = 0,
    val waveform: List<Float> = emptyList(),
    /** RMS levels evenly over the song, which the preview finds the drop in. */
    val loudness: List<Float> = emptyList(),
    val isWaveformLoading: Boolean = false,
    val gauntlet: GlyphGauntlet? = null,
    val liveOffsetMs: Float = 0f,
    val consistencyMs: Float = 0f,
)

/** A short technical drill. */
data class GlyphGauntlet(
    val id: String,
    val name: String,
    val focus: String,
    val description: String,
    val targetAccuracy: Float,
)

/** The results screen, including what to practise next. */
data class GlyphResultsUi(
    val attempt: GlyphAttempt,
    val previousBest: GlyphAttempt?,
    /** This phone's top ten for the chart and difficulty, this run among them if it made it. */
    val machineScores: List<GlyphAttempt> = emptyList(),
    /** Accuracy per song section, in order, for the graph. */
    val sections: List<GlyphSectionResult> = emptyList(),
    val selectedSection: Int? = null,
)

/**
 * One slice of the song, with how it went.
 *
 * The point of the section breakdown is the tap target: the weakest section is
 * the one worth practising, and tapping it should open exactly that range as a
 * Training Ground loop rather than making the player find it again by hand.
 */
data class GlyphSectionResult(
    val index: Int,
    val startSeconds: Float,
    val endSeconds: Float,
    val accuracy: Float,
    val noteCount: Int,
    val missCount: Int,
) {
    val label: String
        get() = "%d:%02d".format(startSeconds.toInt() / 60, startSeconds.toInt() % 60)

    val isWeak: Boolean get() = noteCount > 0 && accuracy < 0.85f
}
