// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphSimfile
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.editor.DraftNote
import tf.monochrome.android.glyph.editor.GlyphChartDraft
import tf.monochrome.android.glyph.editor.GlyphEditTool
import tf.monochrome.android.glyph.editor.GlyphSnap
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScoreSnapshot
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.glyph.input.GlyphKeyBindings
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
    val screen: GlyphScreen = GlyphScreen.MENU,
    /** What the song list is picking a song for. */
    val songListMode: GlyphSongListMode = GlyphSongListMode.PLAY,
    val songs: List<GlyphSong> = emptyList(),
    val isLoadingSongs: Boolean = true,
    val songQuery: String = "",
    /** The music wheel's open folder, StepMania's one open section; null when all are closed. */
    val wheelFolder: String? = null,
    /** The wheel item at the centre, by [tf.monochrome.android.glyph.data.GlyphWheel.Item.key]. */
    val wheelFocus: String? = null,
    /** The wheel's swish as it turns. */
    val wheelSound: Boolean = true,
    /** How the song list and the leaderboard are ordered; kept between sessions. */
    val songSort: GlyphSongSort = GlyphSongSort(),
    val leaderboard: GlyphLeaderboardUi = GlyphLeaderboardUi(),
    val selectedSong: GlyphSong? = null,
    val simfile: GlyphSimfile? = null,
    val selectedDifficulty: StepManiaDifficulty? = null,
    val generation: GlyphGenerationState? = null,
    val packs: GlyphPacksUi = GlyphPacksUi(),
    /** Charting on Tryptify's server by the minute: the listener's minutes and packs. */
    val charting: GlyphChartingUi = GlyphChartingUi(),
    /** Fast (a GPU server) or Slow (a CPU server); kept between sessions. */
    val chartMode: tf.monochrome.android.audio.stepmania.ChartMode = tf.monochrome.android.audio.stepmania.ChartMode.FAST,
    /** The separation servers there are, for what each mode can do. */
    val chartServers: GlyphChartServers = GlyphChartServers(),
    /** Songs waiting or being charted, so their Generate says so. */
    val queuedTracks: Set<String> = emptySet(),
    /** "Chart these songs?", while it is being asked. */
    val batchOffer: GlyphBatchOffer? = null,
    /** Set when a chart is about to be made and no folder for the own pack is chosen yet: ask. */
    val askPackFolder: Boolean = false,
    /** The name on the high score tables. */
    val playerName: String = "",
    /** Tapping a song plays it from its drop; kept between sessions. */
    val previews: Boolean = true,
    /**
     * The song whose picture is behind the song list, which changes as its
     * preview starts and goes as the preview stops; null between them.
     */
    val backdrop: GlyphBackdrop? = null,
    /**
     * A song preview has been started and not stopped: while it is, the song
     * card's beat light runs its frame clock; with none it is still and runs
     * nothing.
     */
    val previewPlaying: Boolean = false,
    /** Best StepMania grade per difficulty of the selected song, for the menu. */
    val bestGrades: Map<StepManiaDifficulty, GlyphAttempt> = emptyMap(),
    val gameplay: GlyphGameplayUi = GlyphGameplayUi(),
    val training: GlyphTrainingUi = GlyphTrainingUi(),
    val results: GlyphResultsUi? = null,
    val assetWarning: String? = null,
    val error: String? = null,
    /** The keyboard's lanes; kept between sessions. */
    val keys: GlyphKeyBindings = GlyphKeyBindings.DEFAULT,
    /** How late the sound reaches the player, in ms; every judgement and the field allow for it. */
    val audioOffsetMs: Int = 0,
    /** The output that offset belongs to, by name, or null for one with no slot. */
    val syncOutput: String? = null,
    /** Settings opened on its Sync tab from Settings › Audio, to measure the output. */
    val syncFocus: Boolean = false,
    /** Closing that Sync tab leaves the mode, back to where it was opened from. */
    val leaveRequested: Boolean = false,
    /** How late the recent runs were hit, for the Sync settings; null when too few say. */
    val recentLateMs: Int? = null,
    /** Tap to sync, while the click track plays or its reading waits; null otherwise. */
    val tapSync: GlyphTapSyncUi? = null,
    val editor: GlyphEditorUi? = null,
    val multiplayer: GlyphMultiplayerUi = GlyphMultiplayerUi(),
    /** The run on now is a room's: its score goes round the room, and its end to the results there. */
    val inRoomPlay: Boolean = false,
) {
    val chart: GlyphChart? get() = selectedDifficulty?.let { simfile?.chart(it) }
}

/**
 * The picture behind the song list: [trackId]'s chart background or artwork
 * ([tf.monochrome.android.glyph.data.GlyphSong.backdropUri]), or, when
 * [imageUri] is null, the list's own grid.
 */
data class GlyphBackdrop(val trackId: String, val imageUri: String?)

/**
 * Where the mode is. [MENU] is the front door; [HOME] is the song list, which
 * Play and the map editor both pick a song from.
 */
enum class GlyphScreen { MENU, HOME, OPTIONS, LEADERBOARD, SETTINGS, PACKS, CHARTING, EDITOR, GAMEPLAY, TRAINING, RESULTS, MULTIPLAYER }

/** Whether the song list is choosing a song to play, a chart to edit, or a chart for the room. */
enum class GlyphSongListMode { PLAY, EDIT, ROOM }

/**
 * Multiplayer: the rooms server's session as the rooms screen and the game
 * see it, with what this phone knows on top (whether it is signed in, and
 * whether it has the picked chart's song).
 */
data class GlyphMultiplayerUi(
    /** A Supabase account is signed in: the name other players see is its. */
    val signedIn: Boolean = false,
    val playerName: String = "",
    /** A rooms server is under Settings › Connections. */
    val hasServer: Boolean = false,
    val session: tf.monochrome.android.glyph.multiplayer.RoomSession = tf.monochrome.android.glyph.multiplayer.RoomSession(),
    /** Whether this phone has the picked chart's song; null until it has looked. */
    val haveSong: Boolean? = null,
    /** Why the song is missing, when it is. */
    val missing: String? = null,
    /** Local wall-clock ms the song starts at, from the server's start until it has. */
    val startAtMs: Long? = null,
    /** The room's song going up (host) or coming down (player), 0 to 1; null when neither. */
    val songTransfer: Float? = null,
    /** The host's song is too big to share as it is, and [songTransfer] is its smaller copy being made. */
    val songShrinking: Boolean = false,
    /** Why the last share or fetch failed, when it did. */
    val transferError: RoomTransferError? = null,
) {
    /** The room, when in one. */
    val room: tf.monochrome.android.glyph.multiplayer.RoomView? get() = session.room

    /** Everyone's live score this song, best first, for the HUD. */
    val opponents: List<tf.monochrome.android.glyph.multiplayer.RoomPlayer>
        get() {
            val room = session.room ?: return emptyList()
            return room.players.filter { !it.spectating }
                .map { p -> session.frames[p.id]?.let { p.copy(live = it) } ?: p }
                .sortedByDescending { it.live.score }
        }
}

/**
 * The map editor: one chart of one song, as a draft on the beat grid.
 *
 * [timing] is the song's tempo map. A song with one tempo can have it set
 * here ([tempoEditable]); one with tempo changes keeps its own, untouched.
 */
data class GlyphEditorUi(
    val song: GlyphSong,
    val difficulty: StepManiaDifficulty,
    val meter: Int,
    val draft: GlyphChartDraft,
    val timing: GlyphTiming,
    val tempoEditable: Boolean,
    /** No chart for this difficulty on disk yet. */
    val isNew: Boolean,
    val snap: GlyphSnap = GlyphSnap.SIXTEENTH,
    val tool: GlyphEditTool = GlyphEditTool.TAP,
    /** A hold's first tap, waiting for the second that sets its end. */
    val pendingHold: DraftNote? = null,
    val canUndo: Boolean = false,
    /** Changed since the last save. */
    val dirty: Boolean = false,
    val playing: Boolean = false,
    /** The song's length, the grid's end. */
    val durationSeconds: Float = 0f,
    val message: String? = null,
    /** A test play is running from the editor and comes back to it. */
    val testing: Boolean = false,
)

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
 * Charting, as [tf.monochrome.android.audio.stepmania.ChartQueue] has it: one
 * song, or a batch of them.
 *
 * [stage] is the service's text verbatim rather than a re-worded version, so
 * what the player reads is what is actually happening: which server is
 * separating the song, and which one it fell back to.
 */
data class GlyphGenerationState(
    /** The song shown: the first one in flight, else the last one finished. */
    val trackId: String,
    val title: String = "",
    /** One song's progress, or the whole batch's; 0 to 1. */
    val fraction: Float,
    val stage: String,
    val backendName: String? = null,
    /** Why a lone song failed; a batch counts its failures in [failed]. */
    val failure: String? = null,
    /** Whether anything is waiting or in flight. */
    val active: Boolean = true,
    /** Songs finished in the batch, failed ones included, and the batch's size. */
    val done: Int = 0,
    val total: Int = 1,
    val failed: Int = 0,
    /** The first failed song of a batch, with why. */
    val firstFailure: String? = null,
) {
    val isFinished: Boolean get() = !active
    val isBatch: Boolean get() = total > 1
}

/**
 * The line as the panel shows it: one song with its own progress and stages,
 * as a single chart always was, or a batch with how far it is in all. Null
 * when there is nothing to show.
 */
internal fun generationOf(queue: tf.monochrome.android.audio.stepmania.ChartQueueState): GlyphGenerationState? {
    if (queue.total == 0) return null
    val current = queue.running.firstOrNull()
    if (queue.total == 1) {
        val outcome = queue.finished.singleOrNull()
        val job = current?.job ?: queue.waiting.firstOrNull()
        return GlyphGenerationState(
            trackId = job?.trackId ?: outcome?.trackId.orEmpty(),
            title = job?.title ?: outcome?.title.orEmpty(),
            fraction = when {
                outcome != null -> if (outcome.failure == null) 1f else 0f
                else -> current?.fraction ?: 0f
            },
            stage = when {
                outcome?.failure != null -> "Failed"
                outcome != null -> "Complete"
                else -> current?.stage ?: "Waiting"
            },
            backendName = outcome?.backendName,
            failure = outcome?.failure,
            active = queue.active,
            done = queue.finished.size,
            total = 1,
            failed = queue.failed,
        )
    }
    val last = queue.finished.lastOrNull()
    val next = queue.waiting.firstOrNull()
    val backends = queue.finished.mapNotNull { it.backendName }.distinct()
    return GlyphGenerationState(
        trackId = current?.job?.trackId ?: last?.trackId ?: next?.trackId.orEmpty(),
        title = current?.job?.title ?: last?.title ?: next?.title.orEmpty(),
        fraction = if (queue.active) queue.fraction else 1f,
        stage = current?.stage ?: if (queue.active) "Waiting" else "Complete",
        // Named when one server made them all; a batch that fell back says so in its notification.
        backendName = if (!queue.active) backends.singleOrNull() else null,
        active = queue.active,
        done = queue.finished.size,
        total = queue.total,
        failed = queue.failed,
        firstFailure = queue.finished.firstOrNull { it.failure != null }?.let { "${it.title}: ${it.failure}" },
    )
}

/**
 * "Chart these songs?", before a folder or the whole library joins the line:
 * how many, how much music, and what each mode would take.
 */
data class GlyphBatchOffer(
    /** What is being charted: a folder's name, or null for every song. */
    val folder: String?,
    val trackIds: List<String>,
    val audioSeconds: Long,
    /** The mode chosen in the dialog; starts as the setting. */
    val mode: tf.monochrome.android.audio.stepmania.ChartMode,
    val fast: GlyphModeEstimate,
    val slow: GlyphModeEstimate,
)

/** Which separation servers Settings › Connections has, by what they run on. */
data class GlyphChartServers(
    val gpu: Boolean = false,
    val cpu: Boolean = false,
    /** A server that has not said yet: added before servers were asked. */
    val unknown: Boolean = false,
) {
    val any: Boolean get() = gpu || cpu || unknown
}

/** What one mode would take for a batch, or why it cannot. */
data class GlyphModeEstimate(
    /** "about 6 minutes"; null when the mode has no server. */
    val time: String?,
    /** "about 7¢"; null when the server does not say what it charges. */
    val cost: String?,
    /** Where it runs, or what is missing: "on the GPU", "needs a CPU server". */
    val note: String,
) {
    val available: Boolean get() = time != null
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
    /** When the life bar emptied, wall-clock ms; 0 while it has not. The screen shakes from here. */
    val failedAtMs: Long = 0L,
    /** That fail stopped the run (Immediate): it holds under FAILED, then shows the results. */
    val failStops: Boolean = false,
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
    /** StepMania's Dark: no receptors, the notes are read against nothing. Shown as "Receptors: Hidden". */
    val dark: Boolean = false,
    /** StepMania's Mini, 0 to 0.5: notes drawn this much smaller, halved. */
    val mini: Float = 0f,
    val failType: GlyphFailType =
        GlyphFailType.IMMEDIATE,
    val metronome: Boolean = false,
    val timingWindowScale: Float = 1f,
    val hitboxScale: Float = 1f,
    /**
     * How much of the width the lanes take when the phone is held sideways
     * ([GlyphFieldBounds]); portrait always gives them all of it. Looks and
     * where the lanes are touched, not how they are judged.
     */
    val landscapeFieldWidth: Float = DEFAULT_LANDSCAPE_FIELD,
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

/**
 * StepMania packs: the ones imported, the one importing, and the player's own
 * pack — the charts made in the app, saved as a pack in a folder they chose.
 */
data class GlyphChartingUi(
    val account: tf.monochrome.android.audio.stepmania.ChartingAccount = tf.monochrome.android.audio.stepmania.ChartingAccount(),
    /** A line to show once, as a string resource; null when there is none. */
    @androidx.annotation.StringRes val notice: Int? = null,
)

/**
 * The Packs screen: the packs imported, an import under way, and the own
 * pack — the charts made in the app, saved as a pack in a folder they chose.
 */
data class GlyphPacksUi(
    val imported: List<tf.monochrome.android.glyph.pack.GlyphPackLibrary.Pack> = emptyList(),
    val importing: GlyphPackImportUi? = null,
    /** The folder the own pack is saved in, readably; null when none is chosen. */
    val folderName: String? = null,
    /** How many charts the app has made, which is what the own pack holds. */
    val ownCharts: Int = 0,
    /** What is being written while the own pack is saved or zipped; null when idle. */
    val saving: String? = null,
    /** How the last import or save ended, until dismissed. */
    val message: String? = null,
)

/** The pack importing now, and how many wait behind it. */
data class GlyphPackImportUi(
    val name: String,
    val fraction: Float,
    val stage: String,
    val queued: Int,
)

/**
 * Tap to sync as the Sync settings show it.
 *
 * [offsetMs] is the absolute offset the taps say, not a change to the current
 * one; null until enough taps have counted.
 */
data class GlyphTapSyncUi(
    val playing: Boolean,
    val taps: Int,
    val needed: Int,
    val offsetMs: Int? = null,
    val spreadMs: Int? = null,
    val steady: Boolean = false,
    /** The reading is late enough to be a whole click off; worth a second go. */
    val couldBeAClickOff: Boolean = false,
    /** Which click the track is on, so the pad can pulse with the sound; -1 before the first. */
    val beat: Int = -1,
)

/** A song share or fetch that failed: which it was, and the reason given. */
data class RoomTransferError(val sharing: Boolean, val reason: String)
