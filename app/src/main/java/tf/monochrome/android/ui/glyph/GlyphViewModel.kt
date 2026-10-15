// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder
import tf.monochrome.android.audio.sampler.stems.BackendKind
import tf.monochrome.android.audio.sampler.stems.InstallResult
import tf.monochrome.android.audio.sampler.stems.StemBackendRegistry
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import tf.monochrome.android.audio.stepmania.StepManiaGenerationWorker
import tf.monochrome.android.audio.stepmania.DropFinder
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.audio.stepmania.StepManiaRequest
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.SscChartWriter
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.glyph.data.GlyphCalibration
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.data.GlyphGhostRecorder
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.data.GlyphSortKey
import tf.monochrome.android.glyph.data.GlyphSongRepository
import tf.monochrome.android.glyph.editor.DraftNote
import tf.monochrome.android.glyph.editor.GlyphChartDraft
import tf.monochrome.android.glyph.editor.GlyphEditTool
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphAudioTransport
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphJudgementEvent
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.GlyphTimingWindows
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.glyph.engine.withTurn
import tf.monochrome.android.glyph.input.GlyphKeyBindings
import tf.monochrome.android.glyph.training.GlyphGauntletFinder
import tf.monochrome.android.glyph.training.GlyphGauntlets
import tf.monochrome.android.glyph.training.GlyphLoopSegment
import tf.monochrome.android.glyph.training.GlyphMetronome
import tf.monochrome.android.glyph.training.GlyphWaveformBuilder

/**
 * The mode's single source of truth.
 *
 * Everything authoritative lives here or below: the transport owns time, the
 * engine owns the rules, and this owns which of them is running. Composables
 * receive [GlyphUiState] and send [GlyphEvent]s, and hold nothing.
 *
 * The gameplay tick deserves a note. It runs as a coroutine rather than off a
 * frame callback, and it does two things: pump the transport for a real audio
 * position, and hand that position to the engine. The engine is what decides
 * whether a note was missed. Drawing is separate and reads the interpolated
 * clock directly, so a dropped frame costs a frame of animation and never a
 * judgement — which is the property that makes the mode fair on a slow device.
 */
@HiltViewModel
class GlyphViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songs: GlyphSongRepository,
    private val attempts: GlyphAttemptStore,
    private val stemModels: StemModelManager,
    private val stemBackends: StemBackendRegistry,
    /**
     * Exposed rather than plumbed through navigation: the pack is a singleton
     * whose rasters are scoped to a run, and handing it down the composable
     * tree from the nav host would give it a lifetime nothing manages.
     */
    val assets: GlyphAssetRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(GlyphUiState())
    val ui: StateFlow<GlyphUiState> = _ui.asStateFlow()

    /**
     * The live engine.
     *
     * Held outside the state on purpose: it changes many times a second and
     * putting it in an immutable snapshot would either copy it constantly or
     * make the snapshot a lie. The playfield reads it directly inside its draw
     * scope, which is the one place that is correct.
     */
    var engine: GlyphGameplayEngine? = null
        private set

    /**
     * The loop the engine's scoring window was last set for, so a loop picked
     * or cleared mid-run is noticed on the next tick (see [armEngineWindow]).
     */
    private var armedLoop: GlyphLoopSegment? = null
    private var engineArmed = false

    /**
     * Swap in a new engine and free the old one. The native engine holds
     * memory outside the heap, so dropping the reference is not enough; a
     * closed engine is inert, so a playfield that still holds it for one more
     * frame draws nothing rather than reading freed memory.
     */
    private fun replaceEngine(next: GlyphGameplayEngine?) {
        val previous = engine
        engine = next
        engineArmed = false
        armedLoop = null
        previous?.close()
    }

    /**
     * Point the engine's scoring window at the loop being practised.
     *
     * Notes outside a loop are set aside rather than timed out: without this
     * the first advance of every pass marked each note before the loop as a
     * missed one. With no loop the window opens at [fromSeconds], so a loop
     * cleared mid-song does not suddenly owe every note behind the playhead.
     */
    private fun armEngineWindow(fromSeconds: Float) {
        val running = engine ?: return
        val loop = transport.loop
        if (engineArmed && loop == armedLoop) return
        if (loop != null) {
            running.reset(loop.startSeconds, loop.endSeconds)
        } else if (engineArmed) {
            running.reset(fromSeconds = fromSeconds)
        }
        armedLoop = loop
        engineArmed = true
    }

    val transport = GlyphAudioTransport(context)

    private val ghostRecorder = GlyphGhostRecorder()
    private val metronome = GlyphMetronome()

    /**
     * The ghost being played back, if any.
     *
     * Held here rather than in the state for the same reason the engine is: it
     * is read while drawing, and a few thousand entries have no business
     * travelling through recomposition.
     */
    var activeGhost: GlyphGhost? = null
        private set

    /**
     * The latest judgement per lane, for the receptor explosion.
     *
     * A plain mutable map read inside the playfield's draw scope rather than
     * state pushed through recomposition: it changes several times a second
     * during a stream and none of it is worth a recomposition.
     */
    private val laneFlashes = java.util.EnumMap<GlyphLane, LaneFlash>(GlyphLane::class.java)

    fun flashes(): Map<GlyphLane, LaneFlash> = laneFlashes

    /**
     * When the combo last crossed a milestone, for the burst.
     *
     * Milestones rather than every hit: a burst on each note would be constant
     * through a stream and would stop reading as an event at all.
     */
    private var comboBurstNanos = 0L
    private var lastComboMilestone = 0

    fun comboBurstNanos(): Long = comboBurstNanos

    /** The combo as of the last tick, for the hit rays' colour. Read while drawing. */
    @Volatile private var liveCombo = 0

    fun liveCombo(): Int = liveCombo

    /** The run scored StepMania's way, fed the same events as the ghost. */
    private var stepScore: StepManiaScoreKeeper? = null
    private var introJob: Job? = null

    /** The song list's preview from the drop; built on first use. */
    private val preview by lazy { GlyphPreviewPlayer(context, viewModelScope) }
    private var previewJob: Job? = null

    private var tickJob: Job? = null
    private var waveformJob: Job? = null
    private var selectionJob: Job? = null
    private var lastPublishNanos = 0L
    private var countInEndsAtSeconds: Float? = null
    /**
     * The background jobs this screen has seen queued or running. A finished
     * job is only shown if it is one of these: WorkManager keeps finished jobs
     * for a day, and one that ended long before the screen opened is not news.
     */
    private val watchedGenerations = HashSet<java.util.UUID>()
    private val handledGenerations = HashSet<java.util.UUID>()
    private var modelJob: Job? = null
    private var runStartedAtMs = 0L

    /** Trypt Mania's own preferences: the player options and the high score name. */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        loadOptions()
        refreshModel()
        watchGeneration()
        viewModelScope.launch {
            songs.songs()
                .catch { failure ->
                    Log.w(TAG, "song list failed: ${failure.message}")
                    _ui.value = _ui.value.copy(
                        isLoadingSongs = false,
                        error = "The music library could not be read.",
                    )
                }
                .collectLatest { list ->
                    _ui.value = _ui.value.copy(songs = list, isLoadingSongs = false)
                }
        }
    }

    fun onEvent(event: GlyphEvent) {
        when (event) {
            is GlyphEvent.OpenSongs ->
                _ui.value = _ui.value.copy(screen = GlyphScreen.HOME, songListMode = event.mode)
            GlyphEvent.BackToMenu -> {
                stopPreview()
                _ui.value = _ui.value.copy(screen = GlyphScreen.MENU)
            }
            GlyphEvent.OpenSettings -> openSettings()
            GlyphEvent.CloseSettings -> _ui.value = _ui.value.copy(screen = GlyphScreen.MENU)
            is GlyphEvent.SetKey -> setKey(event.lane, event.slot, event.code)
            GlyphEvent.ResetKeys -> saveKeys(GlyphKeyBindings.DEFAULT)
            is GlyphEvent.SetAudioOffset -> setAudioOffset(event.ms)
            GlyphEvent.ApplyRecentTiming -> {
                val late = _ui.value.recentLateMs ?: return
                setAudioOffset(GlyphCalibration.corrected(_ui.value.audioOffsetMs, late))
                // What the runs said is now in the offset; the next runs
                // measure whatever is left.
                _ui.value = _ui.value.copy(recentLateMs = null)
            }

            is GlyphEvent.OpenEditor -> openEditor(event.difficulty)
            GlyphEvent.CloseEditor -> closeEditor()
            is GlyphEvent.EditorTap -> editorTap(event.beat, event.lane)
            is GlyphEvent.SetSnap -> updateEditor { it.copy(snap = event.snap) }
            is GlyphEvent.SetTool -> updateEditor { it.copy(tool = event.tool, pendingHold = null) }
            GlyphEvent.EditorUndo -> editorUndo()
            GlyphEvent.EditorSave -> viewModelScope.launch { saveEditor() }
            is GlyphEvent.EditorTogglePlay -> editorTogglePlay(event.fromBeat)
            is GlyphEvent.EditorTestPlay -> editorTestPlay(event.fromBeat)
            is GlyphEvent.SetEditorTempo -> setEditorTempo(event.bpm, event.beatZeroSeconds)
            GlyphEvent.DismissEditorMessage -> updateEditor { it.copy(message = null) }

            is GlyphEvent.Search -> _ui.value = _ui.value.copy(songQuery = event.query)
            is GlyphEvent.SortSongs -> {
                val sort = _ui.value.songSort.pick(event.key)
                prefs.edit().putString(PREF_SORT_KEY, sort.key.id).putBoolean(PREF_SORT_ASCENDING, sort.ascending).apply()
                _ui.value = _ui.value.copy(songSort = sort)
            }
            GlyphEvent.OpenLeaderboard -> {
                stopPreview()
                leaderboardFrom = _ui.value.screen
                openLeaderboard()
            }
            is GlyphEvent.SetPreviews -> {
                prefs.edit().putBoolean(PREF_PREVIEWS, event.on).apply()
                _ui.value = _ui.value.copy(previews = event.on)
                if (!event.on) stopPreview()
            }
            // Back to wherever it was opened from: the menu or the song list.
            GlyphEvent.CloseLeaderboard -> _ui.value = _ui.value.copy(screen = leaderboardFrom)
            is GlyphEvent.SelectSong -> selectSong(event.trackId)
            is GlyphEvent.SelectDifficulty ->
                _ui.value = _ui.value.copy(selectedDifficulty = event.difficulty)

            GlyphEvent.GenerateChart -> generateForSelected()
            is GlyphEvent.GenerateChartFrom -> generate(event.uri, displayNameOf(event.uri), null)
            // Cancels a job still running; closes the panel of one that finished.
            GlyphEvent.CancelGeneration -> {
                if (isGenerating) StepManiaGenerationWorker.cancel(context)
                _ui.value = _ui.value.copy(generation = null)
            }
            GlyphEvent.DismissError -> _ui.value = _ui.value.copy(error = null)

            is GlyphEvent.ImportModel -> importModel(event.uri)
            GlyphEvent.CancelModelImport -> modelJob?.cancel()
            GlyphEvent.RemoveModel -> removeModel()
            GlyphEvent.DismissModelMessage -> updateModel { it.copy(message = null) }

            GlyphEvent.OpenOptions -> if (_ui.value.chart != null) {
                _ui.value = _ui.value.copy(screen = GlyphScreen.OPTIONS)
            }
            GlyphEvent.CloseOptions -> _ui.value = _ui.value.copy(screen = GlyphScreen.HOME)
            is GlyphEvent.SetTurn -> updateModifiers { it.copy(turn = event.turn) }
            is GlyphEvent.SetAppearance -> updateModifiers { it.copy(appearance = event.appearance) }
            is GlyphEvent.SetReverse -> updateModifiers { it.copy(reverse = event.reverse) }
            is GlyphEvent.SetDark -> updateModifiers { it.copy(dark = event.dark) }
            is GlyphEvent.SetMini -> updateModifiers { it.copy(mini = event.mini.coerceIn(0f, MAX_MINI)) }
            is GlyphEvent.SetFailType -> updateModifiers { it.copy(failType = event.failType) }
            is GlyphEvent.SetPlayerName -> setPlayerName(event.name)
            is GlyphEvent.SetGraphics -> updateModifiers { it.copy(graphics = event.graphics) }

            GlyphEvent.StartPlay -> start(training = false)
            GlyphEvent.StartTraining -> start(training = true)
            GlyphEvent.TogglePause -> togglePause()
            GlyphEvent.Restart -> restart()
            GlyphEvent.Quit -> quit()

            is GlyphEvent.LanePressed -> pressLane(event.lane, event.atUptimeMillis)
            is GlyphEvent.LaneReleased -> releaseLane(event.lane, event.atUptimeMillis)

            is GlyphEvent.SetSpeed -> setSpeed(event.speed)
            is GlyphEvent.SetPitchLinked -> {
                updateModifiers { it.copy(pitchLinkedToSpeed = event.linked) }
                transport.setSpeed(_ui.value.gameplay.modifiers.speed, event.linked)
            }
            GlyphEvent.ToggleMirror -> {
                updateModifiers { it.copy(turn = if (it.mirror) GlyphTurn.OFF else GlyphTurn.MIRROR) }
                rebuildEngine()
            }
            GlyphEvent.ToggleShuffle -> {
                updateModifiers { it.copy(turn = if (it.shuffle) GlyphTurn.OFF else GlyphTurn.SHUFFLE) }
                rebuildEngine()
            }
            GlyphEvent.ToggleMetronome -> updateModifiers { it.copy(metronome = !it.metronome) }
            is GlyphEvent.SetScrollFamily -> setScrollFamily(event.family)
            is GlyphEvent.SetScrollValue -> setScrollValue(event.value)
            is GlyphEvent.SetTimingWindowScale -> {
                updateModifiers { it.copy(timingWindowScale = event.scale) }
                rebuildEngine()
            }
            is GlyphEvent.SetSkin -> updateModifiers { it.copy(skin = event.skin) }
            is GlyphEvent.SetHitboxScale ->
                updateModifiers { it.copy(hitboxScale = event.scale.coerceIn(MIN_HITBOX, MAX_HITBOX)) }

            is GlyphEvent.SetLoop -> setLoop(event.startSeconds, event.endSeconds)
            GlyphEvent.ClearLoop -> {
                transport.loop = null
                _ui.value = _ui.value.copy(training = _ui.value.training.copy(loop = null))
            }
            is GlyphEvent.SetCountIn ->
                _ui.value = _ui.value.copy(training = _ui.value.training.copy(countIn = event.countIn))
            is GlyphEvent.SetGhostEnabled -> setGhostEnabled(event.enabled)
            is GlyphEvent.StartGauntlet -> startGauntlet(event.id)

            is GlyphEvent.SelectSection ->
                _ui.value = _ui.value.copy(
                    results = _ui.value.results?.copy(selectedSection = event.index),
                )
            is GlyphEvent.PractiseSection -> practiseSection(event.index)
            GlyphEvent.BackToHome -> {
                stopTick()
                transport.pause()
                _ui.value = _ui.value.copy(screen = GlyphScreen.HOME, results = null)
            }
        }
    }

    // ── selection and generation ────────────────────────────────────────

    /**
     * Select a song.
     *
     * The selection is written **synchronously**, before any disk read. It used
     * to be set at the end of a coroutine that first loaded the chart and the
     * ghost off disk, which left a window where the UI showed one song selected
     * and `selectedSong` still held the previous one — tap a song, tap Generate
     * quickly, and the conversion ran against the song you had selected before.
     * That is the whole reason this is split in two.
     */
    private fun selectSong(trackId: String) {
        val song = _ui.value.songs.firstOrNull { it.trackId == trackId } ?: return
        _ui.value = _ui.value.copy(
            selectedSong = song,
            // Cleared rather than left pointing at the last song's chart, which
            // would let Play start the wrong difficulty of the wrong song.
            simfile = null,
            selectedDifficulty = null,
        )
        loadSelection(song)
        previewSong(song)
    }

    // ── previews ────────────────────────────────────────────────────────

    /**
     * Play [song] from its drop, as StepMania's wheel plays a song's sample.
     *
     * Where the drop is, quickest first: the chart's own #SAMPLESTART; the
     * drop found on an earlier visit, remembered; or found now from the
     * loudness bars the page decodes for the selection anyway, and remembered
     * for next time. A song with none of these waits for its bars rather than
     * starting somewhere arbitrary.
     */
    private fun previewSong(song: GlyphSong) {
        previewJob?.cancel()
        if (!_ui.value.previews || song.filePath.isBlank()) {
            preview.stop()
            return
        }
        previewJob = viewModelScope.launch {
            val simfile = if (song.hasChart) songs.simfile(song.trackId) else null
            val length = simfile?.sampleLengthSeconds ?: GlyphPreviewPlayer.DEFAULT_LENGTH_SECONDS
            val start = simfile?.sampleStartSeconds
                ?: prefs.getFloat(PREF_DROP_PREFIX + song.trackId, -1f).takeIf { it >= 0f }
                ?: dropFromWaveform(song)?.also { prefs.edit().putFloat(PREF_DROP_PREFIX + song.trackId, it).apply() }
                ?: return@launch
            if (_ui.value.selectedSong?.trackId != song.trackId || _ui.value.screen != GlyphScreen.HOME) return@launch
            preview.play(Uri.fromFile(File(song.filePath)), start, length)
        }
    }

    /** The drop from the selection's loudness bars, once they have decoded. */
    private suspend fun dropFromWaveform(song: GlyphSong): Float? {
        val bars = kotlinx.coroutines.withTimeoutOrNull(WAVEFORM_WAIT_MILLIS) {
            _ui.first { state ->
                state.selectedSong?.trackId != song.trackId ||
                    (!state.training.isWaveformLoading && state.training.loudness.isNotEmpty())
            }
        }?.takeIf { it.selectedSong?.trackId == song.trackId }?.training?.loudness ?: return null
        return DropFinder.find(bars, song.durationSeconds.toFloat())
    }

    private fun stopPreview() {
        previewJob?.cancel()
        preview.stop()
    }

    /** The app left the foreground: a preview, or the editor's song, must not play on behind it. */
    fun onBackground() {
        stopPreview()
        if (_ui.value.editor?.playing == true) stopEditorPlayback()
    }

    /** Fill in what has to be read from disk, and refresh the chart state. */
    private fun loadSelection(song: GlyphSong) {
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val (refreshed, simfile) = songs.withCurrentChart(song)
            // A later selection may have landed while this was reading; its
            // result must not be overwritten by this one arriving late.
            if (_ui.value.selectedSong?.trackId != song.trackId) return@launch

            _ui.value = _ui.value.copy(
                selectedSong = refreshed,
                // The cached row comes from a Room flow that cannot see a chart
                // file being written, so the list is corrected here too.
                songs = _ui.value.songs.map {
                    if (it.trackId == refreshed.trackId) refreshed else it
                },
                simfile = simfile,
                // Default to the middle of what exists rather than the hardest:
                // the player who wants Challenge will pick it, and the one who
                // does not should not be dropped into it.
                selectedDifficulty = simfile?.availableDifficulties?.let { available ->
                    available.getOrNull(available.size / 2) ?: available.firstOrNull()
                },
                training = _ui.value.training.copy(
                    hasGhost = attempts.latestGhost(refreshed.chartId) != null,
                ),
                bestGrades = bestGradesFor(refreshed),
            )
            loadWaveform(refreshed)
        }
    }

    /**
     * Decode a coarse envelope for the segment picker.
     *
     * Deliberately coarse: the picker exists so a passage can be found by eye,
     * and two hundred bars does that as well as ten thousand. The song is
     * streamed into the bars block by block and never held whole (see
     * [GlyphWaveformBuilder]), and the decode stops between blocks once the
     * player moves on, so picking song after song no longer stacks up decodes.
     */
    private fun loadWaveform(song: GlyphSong) {
        waveformJob?.cancel()
        if (song.filePath.isBlank()) return

        waveformJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(
                training = _ui.value.training.copy(isWaveformLoading = true, waveform = emptyList(), loudness = emptyList()),
            )
            val job = coroutineContext[Job]
            val (bars, loudness) = withContext(Dispatchers.Default) {
                runCatching {
                    var builder = GlyphWaveformBuilder()
                    PcmDecoder.stream(
                        context = context,
                        uri = Uri.fromFile(File(song.filePath)),
                        isActive = { job?.isActive != false },
                        onFormat = { format -> builder = GlyphWaveformBuilder(format.estimatedFrames) },
                    ) { left, right, frames -> builder.add(left, right, frames) }
                    builder.bars(WAVEFORM_BARS) to builder.loudness(LOUDNESS_BARS)
                }.onFailure {
                    Log.w(TAG, "waveform for ${song.title} failed: ${it.message}")
                }.getOrDefault(emptyList<Float>() to emptyList())
            }
            _ui.value = _ui.value.copy(
                training = _ui.value.training.copy(
                    waveform = bars,
                    loudness = loudness,
                    isWaveformLoading = false,
                ),
            )
        }
    }

    /**
     * The file name behind a picked document.
     *
     * `Uri.lastPathSegment` is not it: a Storage Access Framework URI ends in a
     * document id — `primary:Music/Song.mp3`, or `msf:1234` on some providers —
     * so using it named the chart after an opaque id and wrote that id into the
     * simfile's title and #MUSIC tag. The provider knows the real name and this
     * asks it, falling back only when it will not answer.
     */
    private fun displayNameOf(uri: Uri): String {
        val resolved = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return resolved?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "audio"
    }

    // ── separation model ────────────────────────────────────────────────

    private inline fun updateModel(change: (GlyphModelUi) -> GlyphModelUi) {
        _ui.value = _ui.value.copy(model = change(_ui.value.model))
    }

    /** Reads what is installed. Disk only, and one small file. */
    private fun refreshModel() {
        viewModelScope.launch {
            val (installed, bytes) = withContext(Dispatchers.IO) {
                stemModels.installed() to stemModels.installedBytes()
            }
            updateModel { it.copy(installedName = installed?.name, sizeBytes = bytes) }
        }
    }

    private val isGenerating: Boolean
        get() = _ui.value.generation?.isFinished == false

    /**
     * Copy a model file off the player's storage into the app, through the
     * same installer Settings reads, so the two always agree on what is
     * installed. The installer identifies the file by its hash and refuses
     * one it does not know how to run, since a model whose outputs are read in
     * the wrong order separates the wrong instrument without any error.
     */
    private fun importModel(uri: Uri) {
        if (_ui.value.model.importing) return
        // The generator holds the current model open while it separates.
        if (isGenerating) {
            updateModel { it.copy(message = "Wait for the chart to finish, then import.") }
            return
        }
        val total = sizeOf(uri)
        modelJob = viewModelScope.launch {
            updateModel {
                it.copy(importing = true, progress = if (total > 0) 0f else null, stage = "Reading file", message = null)
            }
            var shownBytes = 0L
            val result = try {
                withContext(Dispatchers.IO) {
                    stemModels.importLocal(
                        open = {
                            context.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("That file could not be opened.")
                        },
                    ) { progress ->
                        // A copy reports every 64 kB; the panel needs a
                        // megabyte at a time, or a stage change.
                        val bytes = progress.bytesDownloaded
                        if (bytes - shownBytes < PROGRESS_STEP_BYTES && progress.stage == _ui.value.model.stage) {
                            return@importLocal
                        }
                        shownBytes = bytes
                        updateModel {
                            it.copy(
                                progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null,
                                stage = progress.stage,
                            )
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                updateModel { it.copy(importing = false, progress = null, stage = null, message = "Import cancelled.") }
                throw cancelled
            }
            val installed = withContext(Dispatchers.IO) { stemModels.installed() to stemModels.installedBytes() }
            updateModel {
                it.copy(
                    importing = false,
                    progress = null,
                    stage = null,
                    installedName = installed.first?.name,
                    sizeBytes = installed.second,
                    message = when (result) {
                        is InstallResult.Installed -> "${result.model.name} is ready. New charts will use it."
                        is InstallResult.Failed -> result.reason
                    },
                )
            }
        }
    }

    private fun removeModel() {
        if (_ui.value.model.importing) return
        if (isGenerating) {
            updateModel { it.copy(message = "Wait for the chart to finish, then remove the model.") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Free the loaded session too: it holds the whole model in
                // native memory, and nothing would use it again.
                stemBackends.of(BackendKind.CPU)?.release()
                stemModels.uninstall()
            }
            updateModel {
                it.copy(
                    installedName = null,
                    sizeBytes = 0L,
                    message = "Model removed. Charts use the built-in separator.",
                )
            }
        }
    }

    /** The picked file's size, for a progress meter; 0 when the provider does not say. */
    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

    private fun generateForSelected() {
        val song = _ui.value.selectedSong ?: return
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }
        generate(Uri.fromFile(File(song.filePath)), File(song.filePath).name, song)
    }

    /**
     * Hand the song to the background chart job ([StepManiaGenerationWorker]).
     *
     * Nothing about decoding, separation or chart generation is reimplemented
     * here — this only chooses the destination. The job runs the conversion
     * service and outlives this screen; [watchGeneration] relays its progress,
     * including which backend actually ran, unchanged.
     */
    private fun generate(source: Uri, displayName: String, song: GlyphSong?) {
        stopPreview()
        val trackId = song?.trackId ?: displayName
        val destination = songs.simfileFile(trackId)
        // A picked file is only readable while the picker's grant lasts; the
        // job may outlive this process, so it keeps the grant until it is done.
        if (source.scheme == "content") {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    source,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        _ui.value = _ui.value.copy(
            generation = GlyphGenerationState(trackId, 0f, "Preparing"),
            error = null,
        )
        StepManiaGenerationWorker.enqueue(
            context = context,
            trackId = trackId,
            source = source,
            destination = destination,
            request = StepManiaRequest(
                title = song?.title ?: displayName.substringBeforeLast('.'),
                artist = song?.artist.orEmpty(),
                musicFileName = displayName,
                difficulties = StepManiaDifficulty.entries.toSet(),
            ),
        )
    }

    /** Mirror the background chart job into [GlyphUiState.generation], for as long as the screen lives. */
    private fun watchGeneration() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(StepManiaGenerationWorker.WORK_NAME)
                // A replaced job and its replacement can both be listed for a
                // moment; the one still going is the one to show.
                .collect { infos ->
                    (infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull())?.let(::showGeneration)
                }
        }
    }

    private fun showGeneration(info: WorkInfo) {
        val trackId = info.tags
            .firstOrNull { it.startsWith(StepManiaGenerationWorker.TRACK_TAG) }
            ?.removePrefix(StepManiaGenerationWorker.TRACK_TAG)
            ?: return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                watchedGenerations += info.id
                val progress = info.progress
                _ui.value = _ui.value.copy(
                    generation = GlyphGenerationState(
                        trackId = trackId,
                        fraction = progress.getFloat(StepManiaGenerationWorker.KEY_FRACTION, 0f),
                        stage = progress.getString(StepManiaGenerationWorker.KEY_STAGE) ?: "Preparing",
                    ),
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                if (!handledGenerations.add(info.id)) return
                if (info.id in watchedGenerations) {
                    _ui.value = _ui.value.copy(
                        generation = GlyphGenerationState(
                            trackId = trackId,
                            fraction = 1f,
                            stage = "Complete",
                            backendName = info.outputData.getString(StepManiaGenerationWorker.KEY_BACKEND),
                        ),
                    )
                }
                // Re-read from disk rather than waiting for the library flow,
                // which never fires for a file written into app storage.
                _ui.value.selectedSong?.takeIf { it.trackId == trackId }?.let(::loadSelection)
            }
            WorkInfo.State.FAILED -> {
                if (!handledGenerations.add(info.id) || info.id !in watchedGenerations) return
                val failure = info.outputData.getString(StepManiaGenerationWorker.KEY_FAILURE)
                    ?: "Chart generation failed."
                Log.w(TAG, "chart generation failed: $failure")
                _ui.value = _ui.value.copy(
                    generation = GlyphGenerationState(
                        trackId = trackId,
                        fraction = 0f,
                        stage = "Failed",
                        failure = failure,
                    ),
                )
            }
            WorkInfo.State.CANCELLED -> {
                if (!handledGenerations.add(info.id) || info.id !in watchedGenerations) return
                if (_ui.value.generation?.trackId == trackId) _ui.value = _ui.value.copy(generation = null)
            }
        }
    }

    // ── running a chart ─────────────────────────────────────────────────

    private fun start(training: Boolean, fromSeconds: Float? = null) {
        val song = _ui.value.selectedSong ?: return
        val chart = _ui.value.chart ?: return
        stopPreview()
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }

        ghostRecorder.clear()
        laneFlashes.clear()
        lastPublishNanos = 0L
        comboBurstNanos = 0L
        lastComboMilestone = 0
        // An MMod read back from the preferences does not know this chart's
        // fastest tempo yet, which is what its multiplier is solved from.
        (_ui.value.gameplay.modifiers.scrollMode as? GlyphScrollMode.MMod)?.let { mmod ->
            updateModifiers { it.copy(scrollMode = GlyphScrollMode.MMod(mmod.targetBpm, chartMaxBpm())) }
        }
        replaceEngine(buildEngine(chart))
        runStartedAtMs = System.currentTimeMillis()

        transport.prepare(Uri.fromFile(File(song.filePath)))
        transport.setSpeed(
            _ui.value.gameplay.modifiers.speed,
            _ui.value.gameplay.modifiers.pitchLinkedToSpeed,
        )
        transport.loop = if (training) _ui.value.training.loop else null
        val from = transport.loop?.startSeconds ?: fromSeconds ?: 0f
        armEngineWindow(from)
        // A test play from the middle of a chart is judged from there: what
        // is behind it is set aside rather than owed as misses.
        if (fromSeconds != null) engine?.reset(fromSeconds = fromSeconds)
        resetStepScore()
        transport.seekTo(from)
        armCountIn(training, from)

        // A scored run opens on StepMania's READY, GO!, with the music held
        // until it is over, so the two words never sit over the first notes.
        // Training Ground has its own count-in and starts at once.
        val intro = !training && !_ui.value.gameplay.modifiers.reducedMotion
        _ui.value = _ui.value.copy(
            screen = if (training) GlyphScreen.TRAINING else GlyphScreen.GAMEPLAY,
            gameplay = _ui.value.gameplay.copy(
                isPlaying = true,
                isPaused = false,
                isFinished = false,
                positionSeconds = 0f,
                durationSeconds = song.durationSeconds.toFloat(),
                bpm = _ui.value.simfile?.timing?.startBpm ?: 0f,
                score = engine!!.scoreSnapshot,
                lastJudgement = null,
                life = StepManiaScoreKeeper.INITIAL_LIFE,
                percent = 0f,
                fullCombo = null,
                introStartedAtMs = if (intro) System.currentTimeMillis() else 0L,
            ),
            results = null,
        )
        introJob?.cancel()
        if (intro) {
            introJob = viewModelScope.launch {
                delay(INTRO_MILLIS)
                transport.play()
                startTick()
                _ui.value = _ui.value.copy(gameplay = _ui.value.gameplay.copy(introStartedAtMs = 0L))
            }
        } else {
            transport.play()
            startTick()
        }
    }

    /**
     * A fresh StepMania scorekeeper for the run, sized to what the engine is
     * scoring now: the whole chart, or the loop being practised.
     */
    private fun resetStepScore() {
        val running = engine ?: return
        stepScore = StepManiaScoreKeeper(
            possibleJudgements = running.scoreSnapshot.totalJudgements,
            failType = _ui.value.gameplay.modifiers.failType,
        )
        liveCombo = 0
    }

    /**
     * Build the engine for the current chart and modifiers.
     *
     * Mirror and shuffle are applied here, once, as a chart transform rather
     * than at draw time: a note that moves lane between the scoring path and
     * the rendering path is a note the player cannot hit.
     */
    private fun buildEngine(chart: GlyphChart): GlyphGameplayEngine {
        val modifiers = _ui.value.gameplay.modifiers
        return GlyphGameplayEngine(
            chart = chart.withTurn(modifiers.turn),
            windows = GlyphTimingWindows.STANDARD.scaled(modifiers.timingWindowScale),
            timing = _ui.value.simfile?.timing ?: GlyphTiming.constant(GlyphTiming.DEFAULT_BPM),
        )
    }

    /** Rebuild mid-run when a modifier changes the chart or the windows. */
    private fun rebuildEngine() {
        val chart = _ui.value.chart ?: return
        if (engine == null) return
        replaceEngine(buildEngine(chart))
        // A modifier change invalidates the run in progress, so it restarts
        // rather than continuing with a score half-earned under other rules.
        restart()
    }

    private fun startTick() {
        stopTick()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val position = transport.pump()
                val running = engine

                // Count-in: the music plays but nothing is judged until it is
                // over, so the player hears the tempo before the first note
                // matters. Advancing the engine during it would let the notes
                // under the count-in time out as misses.
                val countInEnd = countInEndsAtSeconds
                if (countInEnd != null) {
                    if (position < countInEnd) {
                        publishCountIn(position, countInEnd)
                        delay(TICK_MILLIS)
                        continue
                    }
                    countInEndsAtSeconds = null
                }

                // A loop wrapped: the pass is over, so the engine starts the
                // segment again. Without this the notes stay marked resolved
                // from the first pass and the player taps into silence.
                if (transport.didWrap && running != null) {
                    // Re-armed for the loop as it is now, not as it was
                    // when the run started.
                    engineArmed = false
                    armEngineWindow(position)
                    resetStepScore()
                    metronome.reset()
                    ghostRecorder.clear()
                    laneFlashes.clear()
                    lastPublishNanos = 0L
                    comboBurstNanos = 0L
                    lastComboMilestone = 0
                }

                tickMetronome(position)

                if (running != null) {
                    armEngineWindow(position)
                    // Judged at what the player hears, the offset behind
                    // what the player is putting out.
                    running.advanceTo(position - audioOffsetSeconds)
                    // Drained exactly once. Draining again inside publish would
                    // find an empty list and the judgement wordmark would never
                    // appear, so the events are passed along instead.
                    val judged = running.drainEvents()
                    for (event in judged) {
                        // A tail completing is not a new hit and should not
                        // re-fire the explosion its head already produced.
                        if (!event.isTail) {
                            laneFlashes[event.lane] = LaneFlash(
                                atNanos = System.nanoTime(),
                                isHit = event.judgement.isHit,
                            )
                        }
                        ghostRecorder.record(
                            songSeconds = event.songSeconds,
                            lane = event.lane.ordinal,
                            offsetSeconds = event.offsetSeconds,
                            judgement = event.judgement,
                        )
                        stepScore?.record(event)
                    }

                    // The combo alone: a full snapshot here would be built
                    // 125 times a second to read one number.
                    val combo = running.combo
                    liveCombo = combo
                    val milestone = combo / COMBO_MILESTONE
                    if (milestone > lastComboMilestone) {
                        comboBurstNanos = System.nanoTime()
                    }
                    // Tracked rather than compared against the previous combo,
                    // so a break resets the ladder and the next 50 bursts again
                    // instead of the counter having to climb past its old peak.
                    lastComboMilestone = milestone
                    // The engine ticks at 8 ms so a miss lands within a frame,
                    // but the readouts do not need 125 updates a second — each
                    // one recomposes the HUD. They are published at about 30 Hz,
                    // and immediately whenever something was judged so the
                    // wordmark is never late.
                    val now = System.nanoTime()
                    if (judged.isNotEmpty() ||
                        now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS
                    ) {
                        lastPublishNanos = now
                        publish(position, running, judged)
                    }
                    if (running.isFinished && transport.loop == null) {
                        finish()
                        return@launch
                    }
                    // StepMania's Immediate fail: the bar empties, the run ends.
                    // Never in Training Ground, which is for getting it wrong.
                    if (stepScore?.stopsRun == true && _ui.value.screen == GlyphScreen.GAMEPLAY) {
                        publish(position, running, judged)
                        finish()
                        return@launch
                    }
                }
                delay(TICK_MILLIS)
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Beats left before play begins, for the on-screen count. */
    private fun publishCountIn(position: Float, endsAt: Float) {
        val bpm = _ui.value.simfile?.timing?.startBpm ?: return
        if (bpm <= 0f) return
        val remaining = ((endsAt - position) / (60f / bpm)).toInt() + 1
        tickMetronome(position)
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(
                positionSeconds = position,
                countInBeatsRemaining = remaining.coerceAtLeast(0),
            ),
        )
    }

    /**
     * Click on beat changes.
     *
     * Driven from the beat number rather than a timer, so the metronome follows
     * the same audio clock the notes do and cannot drift away from the music.
     */
    private fun tickMetronome(position: Float) {
        if (!_ui.value.gameplay.modifiers.metronome) {
            metronome.reset()
            return
        }
        val timing = _ui.value.simfile?.timing ?: return
        metronome.tick(timing.secondsToBeat(position).toInt())
    }

    /**
     * Push readouts into the state.
     *
     * Only the numbers a person reads — score, combo, accuracy, the section.
     * Note positions are never pushed through here; the playfield reads those
     * from the engine while drawing.
     */
    private fun publish(
        position: Float,
        running: GlyphGameplayEngine,
        judged: List<GlyphJudgementEvent>,
    ) {
        val timing = _ui.value.simfile?.timing
        val beat = timing?.secondsToBeat(position) ?: 0f
        val measure = (beat / 4f).toInt().coerceAtLeast(0)
        val snapshot = running.scoreSnapshot
        val previous = _ui.value.gameplay
        val latest = judged.lastOrNull()
        // The sprite for the last step, not for a hold tail completing.
        val lastStep = judged.lastOrNull { !it.isTail && !it.isMine }
        val keeper = stepScore

        _ui.value = _ui.value.copy(
            gameplay = previous.copy(
                life = keeper?.life ?: previous.life,
                percent = keeper?.percent ?: previous.percent,
                fullCombo = keeper?.fullCombo,
                lastJudgementEarly = lastStep?.offsetSeconds?.let { it < 0f } ?: previous.lastJudgementEarly,
                positionSeconds = position,
                bpm = timing?.bpmAt(beat) ?: previous.bpm,
                measure = measure,
                sectionLabel = "Measure ${measure + 1}",
                score = snapshot,
                lastJudgement = latest?.judgement ?: previous.lastJudgement,
                lastJudgementAtMs = if (latest != null) {
                    System.currentTimeMillis()
                } else {
                    previous.lastJudgementAtMs
                },
                heldLanes = GlyphLane.entries.filterTo(HashSet()) { running.isHeld(it) },
            ),
            training = if (_ui.value.screen == GlyphScreen.TRAINING) {
                _ui.value.training.copy(
                    passCount = transport.loop?.passesAt(position) ?: 0,
                    liveOffsetMs = snapshot.meanOffsetSeconds * 1000f,
                    consistencyMs = snapshot.offsetDeviationSeconds * 1000f,
                )
            } else {
                _ui.value.training
            },
        )
    }

    private fun pressLane(lane: GlyphLane, atUptimeMillis: Long) {
        val running = engine ?: return
        if (!_ui.value.gameplay.isPlaying || _ui.value.gameplay.isPaused) return
        running.press(lane, transport.positionAt(touchNanos(atUptimeMillis)) - audioOffsetSeconds)
    }

    private fun releaseLane(lane: GlyphLane, atUptimeMillis: Long) {
        val running = engine ?: return
        running.release(lane, transport.positionAt(touchNanos(atUptimeMillis)) - audioOffsetSeconds)
    }

    /** The audio offset the player set, in seconds. */
    private val audioOffsetSeconds: Float get() = _ui.value.audioOffsetMs / 1000f

    /**
     * The song position the playfield draws at: the transport's, less the
     * audio offset, so the arrows meet the receptors when the sound reaches
     * the player rather than when the phone sends it.
     */
    fun fieldPosition(): Float = transport.positionNow() - audioOffsetSeconds

    /** Where the song preview is, in song seconds, or null when it is quiet. */
    fun previewPosition(): Float? = preview.songPositionSeconds()

    /** The stretch the song preview plays: start and length, in song seconds. */
    fun previewClip(): Pair<Float, Float>? = preview.clip

    /**
     * When the finger actually moved, on the audio clock's timebase.
     *
     * Compose stamps each pointer change with SystemClock.uptimeMillis, which
     * is CLOCK_MONOTONIC — the clock System.nanoTime reads on Android — so the
     * two compare directly. Reading "now" instead judged a touch at the moment
     * input was dispatched, up to a frame after it happened. A stamp that is
     * missing, in the future or implausibly old falls back to now.
     */
    private fun touchNanos(atUptimeMillis: Long): Long {
        val now = System.nanoTime()
        if (atUptimeMillis <= 0L) return now
        val at = atUptimeMillis * 1_000_000L
        val age = now - at
        return if (age < 0L || age > MAX_TOUCH_AGE_NANOS) now else at
    }

    private fun togglePause() {
        // Paused during READY/GO: the intro is dropped, and Resume starts the
        // music straight away.
        if (introJob?.isActive == true) {
            introJob?.cancel()
            _ui.value = _ui.value.copy(
                gameplay = _ui.value.gameplay.copy(introStartedAtMs = 0L),
            )
        }
        val paused = !_ui.value.gameplay.isPaused
        if (paused) {
            transport.pause()
            stopTick()
        } else {
            transport.play()
            startTick()
        }
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(isPaused = paused, isPlaying = !paused),
        )
    }

    private fun restart() {
        val chart = _ui.value.chart ?: return
        ghostRecorder.clear()
        laneFlashes.clear()
        comboBurstNanos = 0L
        lastComboMilestone = 0
        replaceEngine(buildEngine(chart))
        runStartedAtMs = System.currentTimeMillis()
        val testFrom = testFromSeconds.takeIf { _ui.value.editor?.testing == true }
        val from = transport.loop?.startSeconds ?: testFrom ?: 0f
        introJob?.cancel()
        armEngineWindow(from)
        if (testFrom != null) engine?.reset(fromSeconds = testFrom)
        resetStepScore()
        transport.seekTo(from)
        armCountIn(_ui.value.screen == GlyphScreen.TRAINING, from)
        transport.play()
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(
                isPaused = false,
                isPlaying = true,
                isFinished = false,
                score = engine!!.scoreSnapshot,
                lastJudgement = null,
                life = StepManiaScoreKeeper.INITIAL_LIFE,
                percent = 0f,
                fullCombo = null,
                introStartedAtMs = 0L,
            ),
        )
        startTick()
    }

    /**
     * Set the point at which judging begins.
     *
     * Only in training: a count-in on a scored run would put the first notes
     * of the chart out of reach. Expressed in beats and converted here, so it
     * stays four beats long at any practice speed.
     */
    private fun armCountIn(training: Boolean, fromSeconds: Float) {
        metronome.reset()
        val countIn = _ui.value.training.countIn
        val bpm = _ui.value.simfile?.timing?.startBpm ?: 0f
        countInEndsAtSeconds = if (training && countIn.beats > 0 && bpm > 0f) {
            fromSeconds + countIn.seconds(bpm)
        } else {
            null
        }
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(
                countInBeatsRemaining = if (countInEndsAtSeconds != null) countIn.beats else 0,
            ),
        )
    }

    private fun quit() {
        stopTick()
        introJob?.cancel()
        transport.pause()
        if (_ui.value.editor?.testing == true) {
            backToEditor(null)
            return
        }
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.HOME,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isPaused = false, introStartedAtMs = 0L),
        )
    }

    private fun setSpeed(speed: Float) {
        updateModifiers { it.copy(speed = speed) }
        transport.setSpeed(speed, _ui.value.gameplay.modifiers.pitchLinkedToSpeed)
    }

    /**
     * Follow the system's reduced-motion setting.
     *
     * Not a preference of the mode's own: the app already honours "disable
     * animations" everywhere else, and a rhythm game is the last place to make
     * someone opt out a second time.
     */
    fun setReducedMotion(reduced: Boolean) {
        updateModifiers { it.copy(reducedMotion = reduced) }
    }

    /**
     * Switch family, carrying a sensible value across.
     *
     * A multiplier and a target BPM are not the same kind of number, so moving
     * between them keeps the *reading speed* rather than the digits: leaving
     * C400 for XMod should not land on 400×.
     */
    private fun setScrollFamily(family: GlyphScrollFamily) {
        val current = _ui.value.gameplay.modifiers.scrollMode
        val maxBpm = chartMaxBpm()
        val referenceBpm = _ui.value.simfile?.timing?.startBpm ?: GlyphTiming.DEFAULT_BPM
        // What the current mode reads as, in BPM, at this chart's tempo.
        val effectiveBpm = when (current) {
            is GlyphScrollMode.CMod -> current.targetBpm
            is GlyphScrollMode.XMod -> referenceBpm * current.multiplier
            is GlyphScrollMode.MMod -> current.targetBpm
        }

        // Rounded to the steps the options screen offers: 50 BPM for CMod
        // and MMod, a quarter for XMod.
        val next = when (family) {
            GlyphScrollFamily.C ->
                GlyphScrollMode.CMod(roundTo(effectiveBpm, 50f).coerceIn(100f, 1000f))
            GlyphScrollFamily.M ->
                GlyphScrollMode.MMod(roundTo(effectiveBpm, 50f).coerceIn(200f, 1000f), maxBpm)
            GlyphScrollFamily.X -> GlyphScrollMode.XMod(
                roundTo(if (referenceBpm > 0f) effectiveBpm / referenceBpm else 1f, 0.25f).coerceIn(0.25f, 8f),
            )
        }
        updateModifiers { it.copy(scrollMode = next) }
    }

    private fun setScrollValue(value: Float) {
        val maxBpm = chartMaxBpm()
        val next = when (_ui.value.gameplay.modifiers.scrollMode) {
            is GlyphScrollMode.CMod -> GlyphScrollMode.CMod(value)
            is GlyphScrollMode.MMod -> GlyphScrollMode.MMod(value, maxBpm)
            is GlyphScrollMode.XMod -> GlyphScrollMode.XMod(value)
        }
        updateModifiers { it.copy(scrollMode = next) }
    }

    /**
     * The chart's fastest tempo, which MMod solves its multiplier against.
     *
     * Read from the timing map rather than stored, so a chart whose tempo list
     * was re-read cannot leave MMod solving against a stale ceiling.
     */
    private fun chartMaxBpm(): Float =
        _ui.value.simfile?.timing?.bpmRange?.endInclusive ?: GlyphTiming.DEFAULT_BPM

    private fun roundTo(value: Float, step: Float): Float = kotlin.math.round(value / step) * step


    private fun updateModifiers(block: (GlyphModifiers) -> GlyphModifiers) {
        val next = block(_ui.value.gameplay.modifiers)
        _ui.value = _ui.value.copy(gameplay = _ui.value.gameplay.copy(modifiers = next))
        saveOptions(next)
    }

    // ── player options, kept between sessions ───────────────────────────

    private fun loadOptions() {
        if (prefs.contains(LEGACY_PREF_DARK)) prefs.edit().remove(LEGACY_PREF_DARK).apply()
        val mode = when (prefs.getString(PREF_SCROLL_FAMILY, null)) {
            GlyphScrollFamily.X.name -> GlyphScrollMode.XMod(prefs.getFloat(PREF_SCROLL_VALUE, 2f))
            GlyphScrollFamily.M.name -> GlyphScrollMode.MMod(prefs.getFloat(PREF_SCROLL_VALUE, 400f), 0f)
            GlyphScrollFamily.C.name -> GlyphScrollMode.CMod(prefs.getFloat(PREF_SCROLL_VALUE, 400f))
            else -> GlyphScrollMode.DEFAULT
        }
        val stored = GlyphModifiers(
            scrollMode = mode,
            turn = GlyphTurn.fromId(prefs.getString(PREF_TURN, null)),
            appearance = GlyphAppearance.fromId(prefs.getString(PREF_APPEARANCE, null)),
            reverse = prefs.getBoolean(PREF_REVERSE, false),
            dark = prefs.getBoolean(PREF_HIDE_RECEPTORS, false),
            mini = prefs.getFloat(PREF_MINI, 0f).coerceIn(0f, MAX_MINI),
            failType = GlyphFailType.fromId(prefs.getString(PREF_FAIL, null)),
            metronome = prefs.getBoolean(PREF_METRONOME, false),
            timingWindowScale = prefs.getFloat(PREF_JUDGE, 1f),
            skin = GlyphSkin.fromId(prefs.getString(PREF_SKIN, null)),
            graphics = GlyphGraphics(
                hitRays = prefs.getBoolean(PREF_GFX_RAYS, true),
                rayQuality = prefs.getInt(PREF_GFX_RAY_QUALITY, 1).coerceIn(1, 4),
                noteLighting = prefs.getBoolean(PREF_GFX_LIGHTING, true),
                explosions = prefs.getBoolean(PREF_GFX_EXPLOSIONS, true),
                beatFlash = prefs.getBoolean(PREF_GFX_BEAT_FLASH, true),
                comboBurst = prefs.getBoolean(PREF_GFX_COMBO_BURST, true),
            ),
        )
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(modifiers = stored),
            playerName = prefs.getString(PREF_NAME, null).orEmpty(),
            keys = GlyphKeyBindings.decode(prefs.getString(PREF_KEYS, null)),
            audioOffsetMs = prefs.getInt(PREF_AUDIO_OFFSET, 0)
                .coerceIn(-GlyphCalibration.MAX_OFFSET_MS, GlyphCalibration.MAX_OFFSET_MS),
            previews = prefs.getBoolean(PREF_PREVIEWS, true),
            songSort = GlyphSongSort(
                key = GlyphSortKey.fromId(prefs.getString(PREF_SORT_KEY, null)),
                ascending = prefs.getBoolean(PREF_SORT_ASCENDING, true),
            ),
        )
    }

    /**
     * Everything a player sets on the options screen, as StepMania keeps a
     * profile's modifiers. Not the music rate, which StepMania resets for each
     * song: a slowed song left on by accident would mark every run after it.
     */
    private fun saveOptions(modifiers: GlyphModifiers) {
        val (family, value) = when (val mode = modifiers.scrollMode) {
            is GlyphScrollMode.XMod -> GlyphScrollFamily.X to mode.multiplier
            is GlyphScrollMode.CMod -> GlyphScrollFamily.C to mode.targetBpm
            is GlyphScrollMode.MMod -> GlyphScrollFamily.M to mode.targetBpm
        }
        prefs.edit()
            .putString(PREF_SCROLL_FAMILY, family.name)
            .putFloat(PREF_SCROLL_VALUE, value)
            .putString(PREF_TURN, modifiers.turn.id)
            .putString(PREF_APPEARANCE, modifiers.appearance.id)
            .putBoolean(PREF_REVERSE, modifiers.reverse)
            .putBoolean(PREF_HIDE_RECEPTORS, modifiers.dark)
            .putFloat(PREF_MINI, modifiers.mini)
            .putString(PREF_FAIL, modifiers.failType.id)
            .putBoolean(PREF_METRONOME, modifiers.metronome)
            .putFloat(PREF_JUDGE, modifiers.timingWindowScale)
            .putString(PREF_SKIN, modifiers.skin.id)
            .putBoolean(PREF_GFX_RAYS, modifiers.graphics.hitRays)
            .putInt(PREF_GFX_RAY_QUALITY, modifiers.graphics.rayQuality)
            .putBoolean(PREF_GFX_LIGHTING, modifiers.graphics.noteLighting)
            .putBoolean(PREF_GFX_EXPLOSIONS, modifiers.graphics.explosions)
            .putBoolean(PREF_GFX_BEAT_FLASH, modifiers.graphics.beatFlash)
            .putBoolean(PREF_GFX_COMBO_BURST, modifiers.graphics.comboBurst)
            .apply()
    }

    /**
     * The name on the high score tables: letters, digits and a few marks, at
     * most [MAX_NAME_LENGTH], as StepMania's name entry allows.
     */
    private fun setPlayerName(raw: String) {
        val name = raw.filter { it.isLetterOrDigit() || it in NAME_MARKS }
            .trim()
            .take(MAX_NAME_LENGTH)
        prefs.edit().putString(PREF_NAME, name).apply()
        _ui.value = _ui.value.copy(playerName = name)
    }

    /** The name a run is saved under: the chosen one, or PLAYER. */
    private fun effectiveName(): String = _ui.value.playerName.ifBlank { DEFAULT_NAME }

    /**
     * The leaderboard page: every chart with a full run on this phone, each
     * with its tables by difficulty, matched back to the library's song so it
     * can be sorted like the list.
     */
    private fun openLeaderboard() {
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.LEADERBOARD,
            leaderboard = _ui.value.leaderboard.copy(loading = true),
        )
        viewModelScope.launch {
            val byChart = _ui.value.songs.associateBy { it.chartId }
            val boards = attempts.allHighScores().map { (chartId, tables) ->
                val song = byChart[chartId]
                GlyphBoard(
                    chartId = chartId,
                    title = song?.title ?: tables.values.first().first().songTitle,
                    song = song,
                    tables = tables,
                )
            }
            _ui.value = _ui.value.copy(leaderboard = GlyphLeaderboardUi(loading = false, boards = boards))
        }
    }

    private suspend fun bestGradesFor(song: GlyphSong): Map<StepManiaDifficulty, GlyphAttempt> =
        StepManiaDifficulty.entries.mapNotNull { difficulty ->
            attempts.bestAttempt(song.chartId, difficulty)?.let { difficulty to it }
        }.toMap()

    // ── settings ────────────────────────────────────────────────────────

    private fun openSettings() {
        stopPreview()
        _ui.value = _ui.value.copy(screen = GlyphScreen.SETTINGS)
        viewModelScope.launch {
            val late = GlyphCalibration.lateByMs(attempts.recentRuns())
            _ui.value = _ui.value.copy(recentLateMs = late?.takeIf { it != 0 })
        }
    }

    private fun setKey(lane: GlyphLane, slot: Int, code: Int) {
        if (GlyphKeyBindings.isReserved(code) || slot !in 0 until GlyphKeyBindings.SLOTS) return
        saveKeys(_ui.value.keys.bind(lane, slot, code).bindings)
    }

    private fun saveKeys(keys: GlyphKeyBindings) {
        prefs.edit().putString(PREF_KEYS, keys.encode()).apply()
        _ui.value = _ui.value.copy(keys = keys)
    }

    private fun setAudioOffset(ms: Int) {
        val clamped = ms.coerceIn(-GlyphCalibration.MAX_OFFSET_MS, GlyphCalibration.MAX_OFFSET_MS)
        prefs.edit().putInt(PREF_AUDIO_OFFSET, clamped).apply()
        _ui.value = _ui.value.copy(audioOffsetMs = clamped)
    }

    // ── map editor ──────────────────────────────────────────────────────

    /** Earlier drafts, newest last, for undo. Not state: only its size is shown. */
    private val editorHistory = ArrayDeque<GlyphChartDraft>()
    private var editorJob: Job? = null

    private inline fun updateEditor(change: (GlyphEditorUi) -> GlyphEditorUi) {
        val editor = _ui.value.editor ?: return
        _ui.value = _ui.value.copy(editor = change(editor))
    }

    /**
     * Open [difficulty] of the selected song: its chart read onto the grid,
     * or an empty one when the song has no such chart yet. A song with no
     * simfile at all starts at its library BPM, or 120, with beat zero at the
     * top of the audio; the tempo can be set in the editor.
     */
    private fun openEditor(difficulty: StepManiaDifficulty) {
        val song = _ui.value.selectedSong ?: return
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }
        stopPreview()
        viewModelScope.launch {
            val simfile = if (song.hasChart) songs.simfile(song.trackId) else null
            val timing = simfile?.timing ?: GlyphTiming.constant(song.bpm ?: GlyphTiming.DEFAULT_BPM)
            val chart = simfile?.chart(difficulty)
            editorHistory.clear()
            transport.loop = null
            transport.prepare(Uri.fromFile(File(song.filePath)))
            transport.setSpeed(1f, true)
            _ui.value = _ui.value.copy(
                screen = GlyphScreen.EDITOR,
                editor = GlyphEditorUi(
                    song = song,
                    difficulty = difficulty,
                    meter = chart?.meter ?: difficulty.meter,
                    draft = chart?.let { GlyphChartDraft.of(it, timing) } ?: GlyphChartDraft(),
                    timing = timing,
                    tempoEditable = timing.isConstantTempo && timing.stops.isEmpty(),
                    isNew = chart == null,
                    durationSeconds = song.durationSeconds.toFloat(),
                ),
            )
        }
    }

    private fun closeEditor() {
        stopEditorPlayback()
        editorHistory.clear()
        _ui.value = _ui.value.copy(screen = GlyphScreen.HOME, editor = null)
        _ui.value.selectedSong?.let { loadSelection(it) }
    }

    /**
     * A tap on the grid, snapped. Tap and Mine toggle a note, Erase takes one
     * away, and Hold takes two taps: the head, then the end further down the
     * same lane. A first tap on a hold already there removes it.
     */
    private fun editorTap(beat: Float, lane: Int) {
        val editor = _ui.value.editor ?: return
        if (lane !in 0 until GlyphChartDraft.LANES || beat < 0f) return
        val tick = editor.snap.snap(GlyphChartDraft.tickOf(beat))
        val draft = editor.draft
        val pending = editor.pendingHold
        var nextPending: DraftNote? = null
        val next = when (editor.tool) {
            GlyphEditTool.TAP -> draft.place(tick, lane, GlyphNoteType.TAP)
            GlyphEditTool.MINE -> draft.place(tick, lane, GlyphNoteType.MINE)
            GlyphEditTool.ERASE -> draft.erase(tick, lane)
            GlyphEditTool.HOLD -> when {
                pending == null -> {
                    val existing = draft.noteAt(tick, lane)
                    if (existing != null && existing.type.isHoldLike) {
                        draft.erase(tick, lane)
                    } else {
                        nextPending = DraftNote(tick, lane, GlyphNoteType.HOLD)
                        draft
                    }
                }
                pending.lane == lane && tick > pending.tick -> draft.hold(pending.tick, tick, lane)
                pending.lane == lane && tick == pending.tick -> draft
                else -> {
                    nextPending = DraftNote(tick, lane, GlyphNoteType.HOLD)
                    draft
                }
            }
        }
        if (next != draft) {
            editorHistory.addLast(draft)
            while (editorHistory.size > MAX_UNDO) editorHistory.removeFirst()
        }
        _ui.value = _ui.value.copy(
            editor = editor.copy(
                draft = next,
                pendingHold = nextPending,
                canUndo = editorHistory.isNotEmpty(),
                dirty = editor.dirty || next != draft,
            ),
        )
    }

    private fun editorUndo() {
        val previous = editorHistory.removeLastOrNull() ?: return
        updateEditor {
            it.copy(draft = previous, pendingHold = null, canUndo = editorHistory.isNotEmpty(), dirty = true)
        }
    }

    private fun setEditorTempo(bpm: Float, beatZeroSeconds: Float) {
        updateEditor {
            if (!it.tempoEditable) return@updateEditor it
            it.copy(
                timing = GlyphTiming.constant(bpm.coerceIn(MIN_EDITOR_BPM, MAX_EDITOR_BPM), beatZeroSeconds),
                dirty = true,
            )
        }
    }

    /**
     * Write the draft into the song's simfile, around everything else in it,
     * and read it back: a file this cannot parse again is not written, so a
     * save never leaves a song without its charts. True when it saved.
     */
    private suspend fun saveEditor(): Boolean {
        val editor = _ui.value.editor ?: return false
        if (editor.draft.notes.none { it.type.isScorable }) {
            updateEditor { it.copy(message = "Place a step first: a chart with nothing to hit is not saved.") }
            return false
        }
        val song = editor.song
        val file = songs.simfileFile(song.trackId)
        val written = withContext(Dispatchers.IO) {
            runCatching {
                val noteData = editor.draft.toNoteData()
                val existing = if (file.exists()) file.readText() else null
                val timing = editor.timing
                var text = if (existing != null) {
                    SscChartWriter.withChart(existing, editor.difficulty, editor.meter, noteData)
                } else {
                    SscChartWriter.newSimfile(
                        title = song.title,
                        artist = song.artist,
                        musicFileName = File(song.filePath).name,
                        bpm = timing.startBpm,
                        beatZeroSeconds = timing.offsetSeconds,
                        difficulty = editor.difficulty,
                        meter = editor.meter,
                        noteData = noteData,
                    )
                }
                if (existing != null && editor.tempoEditable) {
                    text = SscChartWriter.withTempo(text, timing.startBpm, timing.offsetSeconds)
                }
                val parsed = SscParser.parse(text)?.takeIf { it.chart(editor.difficulty) != null }
                    ?: error("the chart did not read back")
                // Written beside the old file and moved over it, so a save cut
                // short leaves the old charts, never half a file.
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(text)
                if (!temp.renameTo(file)) {
                    file.writeText(text)
                    temp.delete()
                }
                parsed
            }.onFailure { Log.w(TAG, "saving the chart failed: ${it.message}") }.getOrNull()
        }
        if (written == null) {
            updateEditor { it.copy(message = "The chart could not be saved.") }
            return false
        }
        val (refreshed, _) = songs.withCurrentChart(song)
        _ui.value = _ui.value.copy(
            selectedSong = if (_ui.value.selectedSong?.trackId == song.trackId) refreshed else _ui.value.selectedSong,
            songs = _ui.value.songs.map { if (it.trackId == song.trackId) refreshed else it },
            simfile = if (_ui.value.selectedSong?.trackId == song.trackId) written else _ui.value.simfile,
            selectedDifficulty = editor.difficulty,
            editor = _ui.value.editor?.copy(song = refreshed, isNew = false, dirty = false, message = "Saved."),
        )
        return true
    }

    /** Play the song from [fromBeat] under the grid, or stop it. */
    private fun editorTogglePlay(fromBeat: Float) {
        val editor = _ui.value.editor ?: return
        if (editor.playing) {
            stopEditorPlayback()
            return
        }
        transport.seekTo(editor.timing.beatToSeconds(fromBeat).coerceAtLeast(0f))
        transport.play()
        updateEditor { it.copy(playing = true) }
        editorJob?.cancel()
        editorJob = viewModelScope.launch {
            // The clock is fed real readings as in a run; the grid reads it.
            while (isActive) {
                val position = transport.pump()
                val end = _ui.value.editor?.durationSeconds ?: 0f
                if (end > 0f && position >= end) {
                    stopEditorPlayback()
                    break
                }
                delay(EDITOR_TICK_MILLIS)
            }
        }
    }

    private fun stopEditorPlayback() {
        editorJob?.cancel()
        editorJob = null
        transport.pause()
        updateEditor { it.copy(playing = false) }
    }

    /** Where the editor's song is, in beats, while it plays. Read while drawing. */
    fun editorBeat(): Float? {
        val editor = _ui.value.editor ?: return null
        if (!editor.playing) return null
        return editor.timing.secondsToBeat(transport.positionNow())
    }

    /** Save, then play the chart from [fromBeat]; quitting or finishing comes back here. */
    private fun editorTestPlay(fromBeat: Float) {
        val editor = _ui.value.editor ?: return
        stopEditorPlayback()
        viewModelScope.launch {
            if (editor.dirty || editor.isNew) {
                if (!saveEditor()) return@launch
            }
            val saved = _ui.value.editor ?: return@launch
            val from = saved.timing.beatToSeconds(fromBeat).coerceAtLeast(0f)
            _ui.value = _ui.value.copy(
                selectedSong = saved.song,
                selectedDifficulty = saved.difficulty,
                editor = saved.copy(testing = true, message = null),
            )
            if (_ui.value.chart == null) {
                updateEditor { it.copy(testing = false, message = "The chart could not be loaded to play.") }
                return@launch
            }
            testFromSeconds = from
            start(training = false, fromSeconds = from)
        }
    }

    /** The screen the leaderboard was opened from, which Back returns to. */
    private var leaderboardFrom = GlyphScreen.HOME

    /** Where a test play started, so Restart starts it there again. */
    private var testFromSeconds: Float? = null

    /** From a test play back to the grid, with how it went. */
    private fun backToEditor(message: String?) {
        stopTick()
        introJob?.cancel()
        transport.pause()
        replaceEngine(null)
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.EDITOR,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isPaused = false, introStartedAtMs = 0L),
            editor = _ui.value.editor?.copy(testing = false, playing = false, message = message),
        )
    }

    // ── training ────────────────────────────────────────────────────────

    private fun setLoop(startSeconds: Float, endSeconds: Float) {
        val duration = _ui.value.selectedSong?.durationSeconds?.toFloat() ?: return
        if (endSeconds - startSeconds < GlyphLoopSegment.MINIMUM_SECONDS) return
        val segment = GlyphLoopSegment(startSeconds, endSeconds).coerceInto(duration)
        transport.loop = segment
        _ui.value = _ui.value.copy(training = _ui.value.training.copy(loop = segment, passCount = 0))
    }

    private fun setGhostEnabled(enabled: Boolean) = viewModelScope.launch {
        val chartId = _ui.value.selectedSong?.chartId
        val stored = if (chartId != null) attempts.latestGhost(chartId) else null
        // A ghost whose arrays disagree is treated as absent rather than played
        // — reading past the end of a shorter one mid-song would be a crash.
        val usable = stored?.takeIf { it.isConsistent && it.size > 0 }
        activeGhost = if (enabled) usable else null
        _ui.value = _ui.value.copy(
            training = _ui.value.training.copy(
                ghostEnabled = enabled && usable != null,
                hasGhost = usable != null,
            ),
        )
    }

    /**
     * Start a drill on the passage of this chart it is about.
     *
     * The gauntlets are selection criteria over the player's own chart, not
     * authored exercises: practising the densest stream in the song you are
     * stuck on beats practising a synthetic one. A chart with none of that
     * pattern says so rather than looping an arbitrary passage.
     */
    private fun startGauntlet(id: String) {
        val gauntlet = GlyphGauntlets.byId(id) ?: return
        val chart = _ui.value.chart ?: return
        val segment = GlyphGauntletFinder.findSegment(chart, id)

        if (segment == null) {
            _ui.value = _ui.value.copy(
                error = "This chart has no ${gauntlet.name.lowercase()} passage to drill.",
            )
            return
        }

        transport.loop = segment
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.TRAINING,
            training = _ui.value.training.copy(
                gauntlet = gauntlet,
                loop = segment,
                passCount = 0,
            ),
            // The drill's target is tighter than the standard windows, which is
            // the point of the timing one in particular.
            gameplay = _ui.value.gameplay.copy(
                modifiers = _ui.value.gameplay.modifiers.copy(
                    timingWindowScale = if (id == "timing") 0.7f else 1f,
                ),
            ),
        )
        start(training = true)
    }

    /** Open a weak section as a loop and go straight to Training Ground. */
    private fun practiseSection(index: Int) {
        val section = _ui.value.results?.sections?.getOrNull(index) ?: return
        setLoop(section.startSeconds, section.endSeconds)
        _ui.value = _ui.value.copy(screen = GlyphScreen.TRAINING, results = null)
        start(training = true)
    }

    // ── finishing ───────────────────────────────────────────────────────

    private fun finish() = viewModelScope.launch {
        stopTick()
        transport.pause()

        val running = engine ?: return@launch
        // A test play from the editor is not a run: nothing is saved, and the
        // editor says how it went.
        if (_ui.value.editor?.testing == true) {
            val keeper = stepScore
            backToEditor(
                keeper?.let {
                    "Test play: ${StepManiaScoreKeeper.formatPercent(it.percent)} " +
                        "(${it.grade.label}), max combo ${running.scoreSnapshot.maxCombo}."
                },
            )
            return@launch
        }
        val song = _ui.value.selectedSong ?: return@launch
        val difficulty = _ui.value.selectedDifficulty ?: return@launch
        val snapshot = running.scoreSnapshot
        val modifiers = _ui.value.gameplay.modifiers
        val loop = transport.loop
        val keeper = stepScore

        val previousBest = attempts.bestAttempt(song.chartId, difficulty)
        val attempt = GlyphAttempt(
            id = UUID.randomUUID().toString(),
            chartId = song.chartId,
            songTitle = song.title,
            difficulty = difficulty,
            playedAtEpochMs = runStartedAtMs,
            score = snapshot.score,
            accuracy = snapshot.finalAccuracy,
            maxCombo = snapshot.maxCombo,
            judgementCounts = snapshot.counts.entries.associate { it.key.name to it.value },
            early = snapshot.early,
            late = snapshot.late,
            meanOffsetMs = snapshot.meanOffsetSeconds * 1000f,
            deviationMs = snapshot.offsetDeviationSeconds * 1000f,
            speed = modifiers.speed,
            mirror = modifiers.mirror,
            segmentStartSeconds = loop?.startSeconds,
            segmentEndSeconds = loop?.endSeconds,
            ghost = ghostRecorder.build(),
            percent = keeper?.percent ?: -1f,
            stepGrade = keeper?.grade?.name,
            fullCombo = keeper?.fullCombo?.name,
            failed = keeper?.grade == GlyphStepGrade.FAILED,
            holdsHeld = keeper?.holdsHeld ?: 0,
            holdsLetGo = keeper?.holdsLetGo ?: 0,
            minesHit = keeper?.minesHit ?: 0,
            playerName = effectiveName(),
            judgeScale = modifiers.timingWindowScale,
            turn = modifiers.turn.id,
        )
        attempts.save(attempt)
        val machine = attempts.highScores(song.chartId, difficulty)

        _ui.value = _ui.value.copy(
            screen = GlyphScreen.RESULTS,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isFinished = true),
            results = GlyphResultsUi(
                attempt = attempt,
                previousBest = previousBest,
                sections = buildSections(running),
                machineScores = machine,
            ),
            training = _ui.value.training.copy(hasGhost = true),
            bestGrades = bestGradesFor(song),
        )
    }

    /**
     * Accuracy per section, for the graph and the practise-this tap target.
     *
     * Sections are fixed spans rather than musical phrases: the chart carries no
     * phrase marks, and a regular grid is honest about what it is. Sections with
     * no notes are dropped so the graph does not show a run of empty zeroes
     * through an instrumental break.
     */
    private fun buildSections(running: GlyphGameplayEngine): List<GlyphSectionResult> {
        val chart = running.chart
        val end = chart.lastNoteSeconds
        if (end <= 0f) return emptyList()

        val ghost = ghostRecorder.build()
        val span = (end / TARGET_SECTIONS).coerceAtLeast(MIN_SECTION_SECONDS)
        val sections = ArrayList<GlyphSectionResult>()

        var index = 0
        var start = 0f
        while (start < end) {
            val stop = (start + span).coerceAtMost(end)
            val fromMs = (start * 1000f).toInt()
            val toMs = (stop * 1000f).toInt()
            val indices = ghost.between(fromMs, toMs)

            if (indices.isNotEmpty()) {
                var weight = 0f
                var misses = 0
                for (position in indices) {
                    val judgement = ghost.judgementAt(position)
                    weight += judgement.weight
                    if (judgement == GlyphJudgement.MISS) misses += 1
                }
                sections += GlyphSectionResult(
                    index = index,
                    startSeconds = start,
                    endSeconds = stop,
                    accuracy = weight / indices.size,
                    noteCount = indices.size,
                    missCount = misses,
                )
                index += 1
            }
            start = stop
        }
        return sections
    }

    override fun onCleared() {
        previewJob?.cancel()
        preview.release()
        stopTick()
        replaceEngine(null)
        metronome.release()
        transport.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "GlyphViewModel"

        const val PREFS_NAME = "trypt_mania"
        const val PREF_SKIN = "skin"
        const val PREF_SCROLL_FAMILY = "scroll_family"
        const val PREF_SCROLL_VALUE = "scroll_value"
        const val PREF_TURN = "turn"
        const val PREF_APPEARANCE = "appearance"
        const val PREF_REVERSE = "reverse"
        /**
         * StepMania's Dark. Its old key, [LEGACY_PREF_DARK], is dropped
         * rather than read: the option was labelled "Dark", which reads as a
         * colour theme, so a saved "on" was far more often a surprise than a
         * choice, and the receptors come back once.
         */
        const val PREF_HIDE_RECEPTORS = "hide_receptors"
        const val LEGACY_PREF_DARK = "dark"
        const val PREF_MINI = "mini"
        const val PREF_FAIL = "fail"
        const val PREF_METRONOME = "metronome"
        const val PREF_JUDGE = "judge"
        const val PREF_NAME = "player_name"
        const val PREF_SORT_KEY = "song_sort"
        const val PREF_PREVIEWS = "previews"
        const val PREF_KEYS = "keys"
        const val PREF_AUDIO_OFFSET = "audio_offset_ms"

        /** Drafts kept for undo. */
        const val MAX_UNDO = 100
        const val EDITOR_TICK_MILLIS = 16L
        const val MIN_EDITOR_BPM = 30f
        const val MAX_EDITOR_BPM = 400f

        /** Per track: the drop found from its loudness, so the next preview starts at once. */
        const val PREF_DROP_PREFIX = "drop_"

        /** How long a preview waits for the selection's loudness bars. */
        const val WAVEFORM_WAIT_MILLIS = 20_000L
        const val PREF_SORT_ASCENDING = "song_sort_ascending"
        const val PREF_GFX_RAYS = "gfx_hit_rays"
        const val PREF_GFX_RAY_QUALITY = "gfx_ray_quality"
        const val PREF_GFX_LIGHTING = "gfx_note_lighting"
        const val PREF_GFX_EXPLOSIONS = "gfx_explosions"
        const val PREF_GFX_BEAT_FLASH = "gfx_beat_flash"
        const val PREF_GFX_COMBO_BURST = "gfx_combo_burst"

        const val MAX_MINI = 0.5f
        const val MAX_NAME_LENGTH = 12
        const val NAME_MARKS = " .-_"
        const val DEFAULT_NAME = "PLAYER"

        /** READY, then GO!, before the music: StepMania's own intro, shortened for a phone. */
        const val INTRO_MILLIS = 1_800L

        const val PROGRESS_STEP_BYTES = 1L shl 20

        /** Older than this, a touch stamp is not trusted (see touchNanos). */
        const val MAX_TOUCH_AGE_NANOS = 250_000_000L

        /**
         * The tick is not the frame clock. It polls the audio position often
         * enough that a miss is registered within a frame or two at 60 Hz,
         * while leaving rendering free to run at whatever rate the display
         * offers. Eight milliseconds is comfortably under the tightest
         * judgement window.
         */
        /** Every this many combo, the playfield marks it. */
        const val COMBO_MILESTONE = 50

        const val TICK_MILLIS = 8L

        /**
         * How often the readouts are pushed into state — about 30 Hz. The
         * numbers on screen change no faster than an eye can follow them, and
         * recomposing the HUD at the tick rate would cost more than the
         * playfield does.
         */
        const val PUBLISH_INTERVAL_NANOS = 33_000_000L

        const val TARGET_SECTIONS = 16
        const val MIN_SECTION_SECONDS = 4f

        /** Enough bars to find a passage by eye; more would be wasted work. */
        const val WAVEFORM_BARS = 220

        /** About a second a level on a four-minute song: fine enough to land on a drop. */
        const val LOUDNESS_BARS = 240
    }
}
