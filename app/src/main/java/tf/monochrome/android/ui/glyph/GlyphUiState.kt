// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphSimfile
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScoreSnapshot
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
    val selectedSong: GlyphSong? = null,
    val simfile: GlyphSimfile? = null,
    val selectedDifficulty: StepManiaDifficulty? = null,
    val generation: GlyphGenerationState? = null,
    val gameplay: GlyphGameplayUi = GlyphGameplayUi(),
    val training: GlyphTrainingUi = GlyphTrainingUi(),
    val results: GlyphResultsUi? = null,
    val assetWarning: String? = null,
    val error: String? = null,
) {
    val chart: GlyphChart? get() = selectedDifficulty?.let { simfile?.chart(it) }

    val filteredSongs: List<GlyphSong>
        get() = if (songQuery.isBlank()) songs else songs.filter { song ->
            song.title.contains(songQuery, ignoreCase = true) ||
                song.artist.contains(songQuery, ignoreCase = true)
        }
}

enum class GlyphScreen { HOME, GAMEPLAY, TRAINING, RESULTS }

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
) {
    val progress: Float
        get() = if (durationSeconds <= 0f) 0f else (positionSeconds / durationSeconds).coerceIn(0f, 1f)
}

/**
 * Play modifiers.
 *
 * [mirror] and [shuffle] are chart transforms and are recorded on the attempt,
 * because a run under them is not comparable with one without. [metronome] and
 * [reducedMotion] change nothing about scoring and are not recorded.
 */
data class GlyphModifiers(
    val speed: Float = 1f,
    val pitchLinkedToSpeed: Boolean = true,
    val mirror: Boolean = false,
    val shuffle: Boolean = false,
    val metronome: Boolean = false,
    val timingWindowScale: Float = 1f,
    val hitboxScale: Float = 1f,
    val reducedMotion: Boolean = false,
) {
    /** True when the run is not comparable with a clean one. */
    val altersScoring: Boolean
        get() = speed != 1f || mirror || shuffle || timingWindowScale != 1f
}

/** Training Ground's own state. */
data class GlyphTrainingUi(
    val loop: GlyphLoopSegment? = null,
    val countIn: GlyphCountIn = GlyphCountIn.ONE_BAR,
    val ghostEnabled: Boolean = false,
    val hasGhost: Boolean = false,
    val passCount: Int = 0,
    val waveform: List<Float> = emptyList(),
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
