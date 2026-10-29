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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.audio.sampler.PcmDecoder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import tf.monochrome.android.audio.stepmania.ChartJob
import tf.monochrome.android.audio.stepmania.ChartMode
import tf.monochrome.android.audio.stepmania.ChartPace
import tf.monochrome.android.audio.stepmania.ChartQueue
import tf.monochrome.android.audio.stepmania.ChartingAccountStore
import tf.monochrome.android.data.cloud.CloudError
import tf.monochrome.android.audio.stepmania.RemoteStemAnalysis
import tf.monochrome.android.audio.stepmania.StepManiaGenerationWorker
import tf.monochrome.android.glyph.pack.GlyphPackLibrary
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.glyph.pack.OwnPackStore
import tf.monochrome.android.glyph.pack.PackImportWorker
import tf.monochrome.android.glyph.pack.TreePackSink
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
import tf.monochrome.android.glyph.multiplayer.RoomChart
import tf.monochrome.android.glyph.multiplayer.RoomClient
import tf.monochrome.android.glyph.multiplayer.RoomFinished
import tf.monochrome.android.glyph.multiplayer.RoomLive
import tf.monochrome.android.glyph.multiplayer.RoomMsg
import tf.monochrome.android.glyph.multiplayer.RoomProtocol
import tf.monochrome.android.data.auth.SupabaseAuthManager
import kotlin.math.abs
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.glyph.data.GlyphCalibration
import tf.monochrome.android.glyph.data.GlyphSyncTrack
import tf.monochrome.android.glyph.data.GlyphTapSync
import tf.monochrome.android.glyph.data.bestOf
import tf.monochrome.android.glyph.data.latestGhostOf
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.data.GlyphGhostRecorder
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.TryptManiaRequest
import tf.monochrome.android.glyph.data.TryptManiaRequests
import tf.monochrome.android.glyph.data.audioFileName
import tf.monochrome.android.glyph.data.audioUri
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
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
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
    private val chartQueue: ChartQueue,
    private val chartPace: ChartPace,
    private val chartingAccount: ChartingAccountStore,
    private val remoteStems: RemoteStemAnalysis,
    private val trackLocator: tf.monochrome.android.player.LocalTrackLocator,
    private val packLibrary: GlyphPackLibrary,
    private val ownPack: OwnPackStore,
    private val rooms: RoomClient,
    /** Makes a song too big for the rooms server into a smaller copy before it is shared. */
    private val shareShrinker: tf.monochrome.android.audio.share.ShareShrinker,
    private val supabaseAuth: SupabaseAuthManager,
    /** Read only, for the player's own look behind the play ([backdropLook]). */
    preferences: tf.monochrome.android.data.preferences.PreferencesManager,
    /**
     * How late the output playing now plays: the game's audio offset is that
     * output's, and the Sync tab measures and saves it for the whole app.
     */
    private val outputSync: tf.monochrome.android.audio.sync.OutputSyncRepository,
    /**
     * Exposed rather than plumbed through navigation: the pack is a singleton
     * whose rasters are scoped to a run, and handing it down the composable
     * tree from the nav host would give it a lifetime nothing manages.
     */
    val assets: GlyphAssetRepository,
) : ViewModel() {

    /**
     * The player's colour settings, read from the player's own preferences
     * rather than copied into the game's: one switch, never two that can
     * disagree. Defaults are the player's.
     */
    val backdropLook: StateFlow<GlyphBackdropLook> = kotlinx.coroutines.flow.combine(
        preferences.dynamicColors,
        preferences.playerDynamicColor,
        preferences.colorTransitionMs,
    ) { dynamic, playerDynamic, blendMs ->
        GlyphBackdropLook(albumColored = dynamic && playerDynamic, colorTransitionMs = blendMs)
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        GlyphBackdropLook(),
    )

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
     * Plain slots read inside the playfield's draw scope rather than state
     * pushed through recomposition: they change several times a second during
     * a stream and none of it is worth a recomposition.
     */
    private val laneFlashes = LaneFlashes()

    fun flashes(): LaneFlashes = laneFlashes

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
    /** A failed run's hold under FAILED before its results; cancelled by leaving or restarting. */
    private var failJob: Job? = null

    /** The song list's preview from the drop; built on first use. */
    private val preview by lazy { GlyphPreviewPlayer(context, viewModelScope) }
    private var previewJob: Job? = null

    private var tickJob: Job? = null
    private var waveformJob: Job? = null

    /** Recent songs' bars and loudness, so coming back to one does not decode it again. */
    private val waveforms = tf.monochrome.android.glyph.training.GlyphWaveformCache()

    /** The file whose bars the state holds, or [waveformJob] is decoding. */
    private var waveformPath: String? = null
    private var selectionJob: Job? = null
    private var lastPublishNanos = 0L
    private var countInEndsAtSeconds: Float? = null
    private var runStartedAtMs = 0L

    /** The room's chart as this phone can play it, once found here; null until then, or when it cannot. */
    private var roomPlan: RoomPlan? = null
    private var roomStartJob: Job? = null

    /** The room's song going up or coming down; one at a time. */
    private var roomTransferJob: Job? = null
    private var roomFrameJob: Job? = null

    /** Trypt Mania's own preferences: the player options and the high score name. */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // A song fetched for a room is the room's: one left by a process that
        // died in a room, or by a view model cleared before it could clear it,
        // goes now. Never while a room is on, whose song it may be.
        if (rooms.session.value.room == null) {
            viewModelScope.launch(Dispatchers.IO) { clearRoomSongs(keep = null) }
        }
        loadOptions()
        watchOutputSync()
        watchCharts()
        watchChartServers()
        watchPlayRequests()
        watchSyncRequests()
        watchPacks()
        watchRooms()
        viewModelScope.launch {
            chartingAccount.account.collect { account ->
                _ui.value = _ui.value.copy(charting = _ui.value.charting.copy(account = account))
            }
        }
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
            is GlyphEvent.OpenSongs -> {
                // Ready before the first song is chosen, so its preview is not
                // the one that waits for the player to be built.
                if (event.mode == GlyphSongListMode.PLAY && _ui.value.previews) preview.warm()
                _ui.value = _ui.value.copy(screen = GlyphScreen.HOME, songListMode = event.mode)
            }
            GlyphEvent.BackToMenu -> {
                wheelFocusJob?.cancel()
                stopPreview()
                // The song list opened from a room goes back to the room.
                val back = if (_ui.value.songListMode == GlyphSongListMode.ROOM) GlyphScreen.MULTIPLAYER else GlyphScreen.MENU
                _ui.value = _ui.value.copy(screen = back)
            }
            GlyphEvent.OpenSettings -> openSettings()
            GlyphEvent.CloseSettings -> {
                stopTapSync()
                // Opened from Settings › Audio: closing goes back there.
                val leave = _ui.value.syncFocus
                _ui.value = _ui.value.copy(screen = GlyphScreen.MENU, tapSync = null, syncFocus = false, leaveRequested = leave)
            }
            GlyphEvent.LeaveHandled -> _ui.value = _ui.value.copy(leaveRequested = false)
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
            GlyphEvent.StartTapSync -> startTapSync()
            is GlyphEvent.SyncTap -> syncTap(event.atUptimeMillis)
            GlyphEvent.StopTapSync -> stopTapSync()
            GlyphEvent.ApplyTapSync -> {
                val offset = _ui.value.tapSync?.offsetMs ?: return
                stopTapSync()
                setAudioOffset(offset)
                _ui.value = _ui.value.copy(tapSync = null)
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
                // The sort makes the folders. A centred song's new folder opens,
                // so the wheel stays on it; a centred heading keeps its folder
                // while the name still stands (turning the sort round), and an
                // open folder's name the new sort does not make opens nothing.
                val state = _ui.value
                val centred = GlyphWheel.trackIdOf(state.wheelFocus)?.let { id -> state.songs.firstOrNull { it.trackId == id } }
                _ui.value = state.copy(
                    songSort = sort,
                    wheelFolder = centred?.let { GlyphWheel.sectionOf(it, sort.key) } ?: state.wheelFolder,
                )
            }
            GlyphEvent.OpenLeaderboard -> {
                wheelFocusJob?.cancel()
                stopPreview()
                leaderboardFrom = _ui.value.screen
                openLeaderboard()
            }
            is GlyphEvent.SetPreviews -> {
                prefs.edit().putBoolean(PREF_PREVIEWS, event.on).apply()
                _ui.value = _ui.value.copy(previews = event.on)
                if (!event.on) {
                    stopPreview()
                    // Without sound the picture follows the choice: the chosen song keeps its own.
                    if (_ui.value.screen == GlyphScreen.HOME) showBackdrop(_ui.value.selectedSong)
                }
            }
            // Back to wherever it was opened from: the menu or the song list.
            GlyphEvent.CloseLeaderboard -> _ui.value = _ui.value.copy(screen = leaderboardFrom)
            is GlyphEvent.SelectSong -> {
                wheelFocusJob?.cancel()
                selectSong(event.trackId)
            }
            is GlyphEvent.WheelFocus -> focusWheel(event.key)
            is GlyphEvent.ToggleFolder -> {
                val open = if (_ui.value.wheelFolder == event.name) null else event.name
                _ui.value = _ui.value.copy(wheelFolder = open, wheelFocus = GlyphWheel.folderKey(event.name))
            }
            is GlyphEvent.CloseFolder ->
                _ui.value = _ui.value.copy(wheelFolder = null, wheelFocus = GlyphWheel.folderKey(event.name))
            is GlyphEvent.SetWheelSound -> {
                prefs.edit().putBoolean(PREF_WHEEL_SOUND, event.on).apply()
                _ui.value = _ui.value.copy(wheelSound = event.on)
            }
            is GlyphEvent.SelectDifficulty ->
                _ui.value = _ui.value.copy(selectedDifficulty = event.difficulty)

            GlyphEvent.GenerateChart -> generateForSelected()
            is GlyphEvent.GenerateChartFrom -> generate(event.uri, displayNameOf(event.uri), null)
            // Cancels the batch still running; closes the panel of one that finished.
            GlyphEvent.CancelGeneration -> viewModelScope.launch {
                if (isGenerating) {
                    chartQueue.cancelAll()
                    StepManiaGenerationWorker.cancel(context)
                }
                chartQueue.clearFinished()
            }
            GlyphEvent.DismissError -> _ui.value = _ui.value.copy(error = null)

            GlyphEvent.OpenPacks -> {
                wheelFocusJob?.cancel()
                stopPreview()
                _ui.value = _ui.value.copy(screen = GlyphScreen.PACKS)
                refreshOwnCount()
            }
            GlyphEvent.ClosePacks -> _ui.value = _ui.value.copy(screen = GlyphScreen.MENU)

            GlyphEvent.OpenCharting -> {
                wheelFocusJob?.cancel()
                stopPreview()
                _ui.value = _ui.value.copy(screen = GlyphScreen.CHARTING)
                viewModelScope.launch { chartingAccount.refresh() }
            }
            GlyphEvent.CloseCharting -> _ui.value = _ui.value.copy(screen = GlyphScreen.MENU)
            GlyphEvent.RefreshCharting -> viewModelScope.launch { chartingAccount.refresh() }
            is GlyphEvent.BuyMinutes -> viewModelScope.launch {
                val pack = _ui.value.charting.account.packs.firstOrNull { it.id == event.packId } ?: return@launch
                chartingAccount.buy(pack)
                    .onSuccess { url -> _ui.value = _ui.value.copy(charting = _ui.value.charting.copy(checkoutUrl = url)) }
                    .onFailure { failure ->
                        val notice = when (failure) {
                            is CloudError.SignedOut -> R.string.tm_charting_sign_in
                            is CloudError.NotSetUp -> R.string.tm_charting_not_on_sale
                            else -> R.string.tm_charting_unreachable
                        }
                        _ui.value = _ui.value.copy(charting = _ui.value.charting.copy(notice = notice))
                    }
            }
            GlyphEvent.ChartingUrlOpened -> _ui.value = _ui.value.copy(charting = _ui.value.charting.copy(checkoutUrl = null))
            GlyphEvent.DismissChartingNotice ->
                _ui.value = _ui.value.copy(charting = _ui.value.charting.copy(notice = null))

            GlyphEvent.OpenMultiplayer -> openMultiplayer()
            GlyphEvent.CloseMultiplayer -> closeMultiplayer()
            GlyphEvent.RoomReconnect -> {
                rooms.disconnect()
                refreshRoomIdentity()
                connectRooms()
            }
            GlyphEvent.RefreshRooms -> rooms.refreshRooms()
            is GlyphEvent.CreateRoom -> {
                roomAskedAt = android.os.SystemClock.elapsedRealtime()
                rooms.createRoom(event.name)
            }
            is GlyphEvent.JoinRoom -> {
                roomAskedAt = android.os.SystemClock.elapsedRealtime()
                rooms.joinRoom(event.code)
            }
            GlyphEvent.LeaveRoom -> rooms.leaveRoom()
            GlyphEvent.PickForRoom -> pickForRoom()
            is GlyphEvent.SetRoomReady -> rooms.ready(event.ready)
            GlyphEvent.StartRoom -> rooms.start()
            is GlyphEvent.KickPlayer -> rooms.kick(event.id)
            is GlyphEvent.RoomChat -> rooms.chat(event.text)
            GlyphEvent.BackToRoom -> backToRoom()
            GlyphEvent.DismissRoomError -> rooms.clearError()
            GlyphEvent.ShareRoomSong -> shareRoomSong()
            GlyphEvent.FetchRoomSong -> fetchRoomSong()
            is GlyphEvent.ImportPackUrl -> PackImportWorker.enqueueDownload(context, event.url, event.name)
            is GlyphEvent.ImportPackFile -> PackImportWorker.enqueueFile(context, event.uri)
            is GlyphEvent.ImportPackFolder -> PackImportWorker.enqueueFolder(context, event.tree)
            GlyphEvent.CancelPackImports -> PackImportWorker.cancelAll(context)
            is GlyphEvent.RemovePack -> viewModelScope.launch {
                packLibrary.remove(event.name)
                updatePacks { it.copy(message = "${event.name} removed.") }
            }
            is GlyphEvent.ChoosePackFolder -> answerPackFolder(event.tree)
            GlyphEvent.ForgetPackFolder -> ownPack.forget()
            GlyphEvent.SaveOwnPack -> writeOwnPack(null)
            is GlyphEvent.ZipOwnPack -> writeOwnPack(event.destination)
            GlyphEvent.DismissPacksMessage -> updatePacks { it.copy(message = null) }

            is GlyphEvent.ChartFolder -> offerBatch(folder = event.name)
            GlyphEvent.ChartEverything -> offerBatch(folder = null)
            is GlyphEvent.ChooseBatchMode ->
                _ui.value.batchOffer?.let { _ui.value = _ui.value.copy(batchOffer = it.copy(mode = event.mode)) }
            GlyphEvent.ConfirmBatch -> confirmBatch()
            GlyphEvent.DismissBatch -> _ui.value = _ui.value.copy(batchOffer = null)
            is GlyphEvent.SetChartMode -> {
                prefs.edit().putString(PREF_CHART_MODE, event.mode.name).apply()
                _ui.value = _ui.value.copy(chartMode = event.mode)
            }

            GlyphEvent.OpenOptions -> if (_ui.value.chart != null) {
                // A song the wheel was about to choose must not replace the
                // one whose options are opening.
                wheelFocusJob?.cancel()
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
            is GlyphEvent.SetLandscapeFieldWidth ->
                updateModifiers { it.copy(landscapeFieldWidth = event.fraction.coerceIn(MIN_LANDSCAPE_FIELD, 1f)) }

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
                transport.stop()
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
     * song's loudness bars, decoded for it ([ensureWaveform]) and remembered
     * for next time. Those bars take reading the whole song, so a song with
     * none of these waits [DROP_WAIT_MILLIS] for them and then starts a third
     * of the way in; its drop, found after, is the next visit's start.
     */
    private fun previewSong(song: GlyphSong) {
        previewJob?.cancel()
        previewStopped = false
        if (!_ui.value.previews || song.filePath.isBlank()) {
            preview.stop()
            setPreviewPlaying(false)
            // No sound for the picture to follow: it changes as the song is chosen.
            showBackdrop(song)
            return
        }
        previewJob = viewModelScope.launch {
            val simfile = if (song.hasChart) songs.simfile(song.trackId) else null
            val length = simfile?.sampleLengthSeconds ?: GlyphPreviewPlayer.DEFAULT_LENGTH_SECONDS
            val start = simfile?.sampleStartSeconds
                ?: prefs.getFloat(PREF_DROP_PREFIX + song.trackId, -1f).takeIf { it >= 0f }
                ?: run {
                    // The drop is found from the song's loudness, which takes
                    // reading the whole song. Waited for in full, a song's first
                    // preview came seconds late; now it waits a moment, then
                    // starts a third of the way in and remembers the drop for
                    // the next visit. Only this needs the song decoded on a
                    // pick: one with a known drop starts without it.
                    if (_ui.value.selectedSong?.trackId == song.trackId) ensureWaveform(song)
                    val drop = async { dropFromWaveform(song) }
                    withTimeoutOrNull(DROP_WAIT_MILLIS) { drop.await() }
                        ?.also { prefs.edit().putFloat(PREF_DROP_PREFIX + song.trackId, it).apply() }
                        ?: run {
                            launch {
                                drop.await()?.let { prefs.edit().putFloat(PREF_DROP_PREFIX + song.trackId, it).apply() }
                            }
                            provisionalStart(song, length)
                        }
                }
            if (_ui.value.selectedSong?.trackId != song.trackId || _ui.value.screen != GlyphScreen.HOME) return@launch
            // The start can wait seconds on the waveform; the app may have
            // gone to the background in the meantime.
            if (!foreground) return@launch
            preview.play(song.audioUri, start, length)
            setPreviewPlaying(true)
            // The picture behind the list changes with the music, not before it.
            showBackdrop(song)
        }
    }

    /** Whether a preview has been started and not stopped, for the card's beat light. */
    private fun setPreviewPlaying(playing: Boolean) {
        if (_ui.value.previewPlaying != playing) _ui.value = _ui.value.copy(previewPlaying = playing)
    }

    /** [song]'s picture behind the song list; null takes it away. */
    private fun showBackdrop(song: GlyphSong?) {
        val next = song?.let { GlyphBackdrop(it.trackId, it.backdropUri) }
        if (_ui.value.backdrop != next) _ui.value = _ui.value.copy(backdrop = next)
    }

    /** The drop from the selection's loudness bars, once they have decoded. */
    /** Where a preview starts before its drop is known: a third of the way in, the clip inside the song. */
    private fun provisionalStart(song: GlyphSong, length: Float): Float {
        val duration = song.durationSeconds.toFloat()
        if (duration <= length) return 0f
        return (duration / 3f).coerceAtMost(duration - length)
    }

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
        setPreviewPlaying(false)
        previewStopped = true
        // The picture goes as the music does.
        showBackdrop(null)
    }

    /**
     * Whether the preview was stopped (a folder, a run, the app left) rather
     * than started; a preview still buffering is not playing yet, but is not
     * stopped either, and must not be started again from the top.
     */
    private var previewStopped = true

    /** Whether the app is in front; a preview never starts while it is not. */
    private var foreground = true

    /** The app is in front again. */
    fun onForeground() {
        foreground = true
    }

    /** The rooms socket was closed for the app going out of sight; it opens again on return. */
    private var roomsHidden = false

    /**
     * When a room was last asked for (created or joined), on the elapsed
     * clock. Until the server answers there is no room yet, but closing the
     * socket would lose the answer, and a room just created would be left
     * without its host.
     */
    private var roomAskedAt = 0L

    /**
     * The app is out of sight. Outside a room the rooms socket is only the
     * list of rooms and the clock, and its ping woke the radio every two
     * seconds behind the app for a list nobody could see: it closes, and
     * [onShown] opens it again. In a room it stays open, ping and all: the
     * server forgets a dropped phone's seat.
     */
    fun onHidden() {
        val session = rooms.session.value
        val open = session.status == tf.monochrome.android.glyph.multiplayer.RoomStatus.CONNECTING ||
            session.status == tf.monochrome.android.glyph.multiplayer.RoomStatus.CONNECTED
        if (!open || session.room != null) return
        if (android.os.SystemClock.elapsedRealtime() - roomAskedAt < ROOM_ANSWER_WAIT_MS) return
        rooms.disconnect()
        roomsHidden = true
    }

    /** The app is in sight again: a rooms socket [onHidden] closed opens again. */
    fun onShown() {
        if (!roomsHidden) return
        roomsHidden = false
        refreshRoomIdentity()
        connectRooms()
    }

    /** The app left the foreground: a preview, or the editor's song, must not play on behind it. */
    fun onBackground() {
        foreground = false
        // A song the wheel was about to choose would start its preview behind
        // the app once its delay ran out.
        wheelFocusJob?.cancel()
        stopPreview()
        if (_ui.value.editor?.playing == true) stopEditorPlayback()
        // A click track tapped to with the app behind would read nothing.
        stopTapSync()
    }

    /** Fill in what has to be read from disk, and refresh the chart state. */
    private fun loadSelection(song: GlyphSong) {
        selectionJob?.cancel()
        showWaveform(song)
        selectionJob = viewModelScope.launch {
            val (refreshed, simfile) = songs.withCurrentChart(song)
            // A later selection may have landed while this was reading; its
            // result must not be overwritten by this one arriving late.
            if (_ui.value.selectedSong?.trackId != song.trackId) return@launch
            // The chart's history once, for both the ghost and the grades:
            // asked for each, the same file was parsed six times a pick.
            val history = attempts.attempts(refreshed.chartId)
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
                    hasGhost = latestGhostOf(history) != null,
                ),
                bestGrades = bestGradesIn(history),
            )
        }
    }

    /**
     * The selection's bars into the state: the cached ones, or none until
     * something asks for them ([ensureWaveform]). A pick no longer decodes the
     * whole song on its own account; a song whose drop is known, picked only
     * to be played, is never decoded at all.
     */
    private fun showWaveform(song: GlyphSong) {
        // The same song again keeps its bars, or the decode on its way.
        if (song.filePath.isNotBlank() && song.filePath == waveformPath) return
        waveformJob?.cancel()
        waveformPath = song.filePath
        val cached = waveforms.get(song.filePath)
        _ui.value = _ui.value.copy(
            training = _ui.value.training.copy(
                waveform = cached?.bars.orEmpty(),
                loudness = cached?.loudness.orEmpty(),
                isWaveformLoading = false,
            ),
        )
    }

    /**
     * Decode a coarse envelope of [song], unless it is here or on its way:
     * the preview's drop is found in it, Training Ground's segment picker and
     * the editor draw it.
     *
     * Deliberately coarse: the picker exists so a passage can be found by eye,
     * and two hundred bars does that as well as ten thousand. The song is
     * streamed into the bars block by block and never held whole (see
     * [GlyphWaveformBuilder]), and the decode stops between blocks once the
     * player moves on, so picking song after song no longer stacks up decodes.
     */
    private fun ensureWaveform(song: GlyphSong) {
        if (song.filePath.isBlank()) return
        showWaveform(song)
        val training = _ui.value.training
        if (training.isWaveformLoading || training.waveform.isNotEmpty()) return

        val path = song.filePath
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
                        uri = song.audioUri,
                        isActive = { job?.isActive != false },
                        onFormat = { format -> builder = GlyphWaveformBuilder(format.estimatedFrames) },
                    ) { left, right, frames -> builder.add(left, right, frames) }
                    builder.bars(WAVEFORM_BARS) to builder.loudness(LOUDNESS_BARS)
                }.onFailure {
                    Log.w(TAG, "waveform for ${song.title} failed: ${it.message}")
                }.getOrDefault(emptyList<Float>() to emptyList())
            }
            // A decode cut short by the next pick never gets here: withContext
            // throws on its way back once this job is cancelled.
            waveforms.put(path, tf.monochrome.android.glyph.training.GlyphWaveformBars(bars, loudness))
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

    private val isGenerating: Boolean
        get() = _ui.value.generation?.active == true

    private fun generateForSelected() {
        val song = _ui.value.selectedSong ?: return
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }
        generate(song.audioUri, song.audioFileName, song)
    }

    /**
     * Put the song in the line for the background chart job
     * ([StepManiaGenerationWorker]), in the mode the settings say.
     *
     * Nothing about separation or chart generation is reimplemented here —
     * this only chooses the destination. The job outlives this screen;
     * [watchCharts] relays the line, including which server separated each
     * song, unchanged.
     */
    private fun generate(source: Uri, displayName: String, song: GlyphSong?) {
        stopPreview()
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
        val trackId = song?.trackId ?: displayName
        queueCharts(
            listOf(
                ChartJob(
                    trackId = trackId,
                    source = source.toString(),
                    destination = songs.simfileFile(trackId).absolutePath,
                    title = song?.title ?: displayName.substringBeforeLast('.'),
                    artist = song?.artist.orEmpty(),
                    musicFileName = displayName,
                    durationSeconds = song?.durationSeconds ?: 0,
                    mode = _ui.value.chartMode,
                ),
            ),
        )
    }

    /**
     * [jobs] into the line, and a worker started when none is draining it.
     * The first chart made asks where the player's own pack is kept; the
     * answer is remembered, and "not now" is too.
     */
    private fun queueCharts(jobs: List<ChartJob>) {
        if (jobs.isEmpty()) return
        if (ownPack.folder.value == null && !ownPack.declined) {
            pendingGeneration = { queueCharts(jobs) }
            _ui.value = _ui.value.copy(askPackFolder = true)
            return
        }
        _ui.value = _ui.value.copy(error = null)
        viewModelScope.launch {
            val added = chartQueue.add(jobs)
            if (added.startWorker) StepManiaGenerationWorker.start(context)
        }
    }

    /** Songs the batch buttons chart: a file to read, no chart, and not already in the line. */
    private fun chartable(song: GlyphSong): Boolean =
        song.chartState == GlyphChartState.NOT_GENERATED && song.filePath.isNotBlank() &&
            song.trackId !in _ui.value.queuedTracks

    /**
     * "Chart these songs?" for a wheel folder, or for every song when
     * [folder] is null: each mode's time and cost from [ChartPace], without
     * waking a server to ask.
     */
    private fun offerBatch(folder: String?) {
        val state = _ui.value
        val pool = if (folder == null) {
            state.songs
        } else {
            GlyphWheel.sections(state.songs, state.songSort, state.songQuery)
                .firstOrNull { it.name == folder }?.songs.orEmpty()
        }
        val picked = pool.filter(::chartable)
        if (picked.isEmpty()) {
            _ui.value = state.copy(error = "Every song here has a chart, or is being charted.")
            return
        }
        viewModelScope.launch {
            val audioSeconds = picked.sumOf { it.durationSeconds.toLong() }
            val servers = remoteStems.stemServers()
            fun estimateFor(mode: ChartMode): GlyphModeEstimate {
                val first = RemoteStemAnalysis.planFor(servers, mode).firstOrNull()
                    ?: return GlyphModeEstimate(
                        time = null,
                        cost = null,
                        note = if (mode == ChartMode.SLOW) "needs a CPU server" else "needs a separation server",
                    )
                // A server that has not said is taken for what Fast first tries: a GPU.
                val gpu = first.separatesOnGpu ?: true
                val estimate = ChartPace.estimate(audioSeconds, gpu, chartPace.snapshot(gpu), songs = picked.size)
                return GlyphModeEstimate(
                    time = ChartPace.describeDuration(estimate.seconds),
                    cost = estimate.costDollars?.let(ChartPace::describeCost),
                    note = if (gpu) "on the GPU" else "on the CPU server",
                )
            }
            val fast = estimateFor(ChartMode.FAST)
            val slow = estimateFor(ChartMode.SLOW)
            val preferred = _ui.value.chartMode
            val mode = when {
                preferred == ChartMode.SLOW && !slow.available && fast.available -> ChartMode.FAST
                else -> preferred
            }
            _ui.value = _ui.value.copy(
                batchOffer = GlyphBatchOffer(
                    folder = folder,
                    trackIds = picked.map { it.trackId },
                    audioSeconds = audioSeconds,
                    mode = mode,
                    fast = fast,
                    slow = slow,
                ),
            )
        }
    }

    private fun confirmBatch() {
        val offer = _ui.value.batchOffer ?: return
        _ui.value = _ui.value.copy(batchOffer = null)
        val byId = _ui.value.songs.associateBy { it.trackId }
        val jobs = offer.trackIds.mapNotNull(byId::get).filter(::chartable).map { song ->
            ChartJob(
                trackId = song.trackId,
                source = song.audioUri.toString(),
                destination = songs.simfileFile(song.trackId).absolutePath,
                title = song.title,
                artist = song.artist,
                musicFileName = song.audioFileName,
                durationSeconds = song.durationSeconds,
                mode = offer.mode,
            )
        }
        stopPreview()
        queueCharts(jobs)
    }

    // ── the music wheel ─────────────────────────────────────────────────

    private var wheelFocusJob: Job? = null

    /**
     * The wheel came to rest on [key]. A song is chosen — its chart read, its
     * preview started — after StepMania's sample delay (`SampleMusicDelay`,
     * a quarter of a second), so turning past a dozen songs starts none of
     * them; a folder stops the preview.
     */
    private fun focusWheel(key: String) {
        val before = _ui.value
        val song = before.songs.firstOrNull { GlyphWheel.songKey(it.trackId) == key }
        val leaving = GlyphWheel.trackIdOf(before.wheelFocus)?.let { id -> before.songs.firstOrNull { it.trackId == id } }
        // The open folder, kept by name: the centred song's, or — turning off a
        // song onto a heading — the folder it was in, so a song's own heading
        // does not close the folder under it.
        val folder = when {
            before.songQuery.isNotBlank() -> before.wheelFolder
            song != null -> GlyphWheel.sectionOf(song, before.songSort.key)
            leaving != null -> GlyphWheel.sectionOf(leaving, before.songSort.key)
            else -> before.wheelFolder
        }
        _ui.value = before.copy(wheelFocus = key, wheelFolder = folder)
        wheelFocusJob?.cancel()
        if (song == null) {
            // A folder stops the music once the wheel rests on it, not as it
            // passes: a wheel turning to a tapped song through a folder would
            // otherwise cut off the preview the tap just started.
            wheelFocusJob = viewModelScope.launch {
                kotlinx.coroutines.delay(SAMPLE_MUSIC_DELAY_MS)
                stopPreview()
            }
            return
        }
        wheelFocusJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SAMPLE_MUSIC_DELAY_MS)
            // Not behind the app, and not under another screen.
            if (!foreground || _ui.value.screen != GlyphScreen.HOME) return@launch
            val chosen = _ui.value.selectedSong
            if (chosen?.trackId == song.trackId) {
                // Back on the chosen song: its preview again, if a folder or a
                // run stopped it.
                if (previewStopped) previewSong(chosen)
            } else {
                selectSong(song.trackId)
            }
        }
    }

    // ── packs ───────────────────────────────────────────────────────────

    /** A chart waiting on the answer to where the own pack is saved. */
    private var pendingGeneration: (() -> Unit)? = null
    private val watchedImports = HashSet<java.util.UUID>()
    private val handledImports = HashSet<java.util.UUID>()

    private fun updatePacks(change: (GlyphPacksUi) -> GlyphPacksUi) {
        _ui.value = _ui.value.copy(packs = change(_ui.value.packs))
    }

    private fun answerPackFolder(tree: Uri?) {
        if (tree != null) ownPack.choose(tree) else ownPack.declined = true
        _ui.value = _ui.value.copy(askPackFolder = false)
        pendingGeneration?.also { pendingGeneration = null }?.invoke()
    }

    /** The charts the app made: library songs with a chart of the app's own. */
    private suspend fun ownSongs(): List<OwnPackStore.OwnSong> = withContext(Dispatchers.IO) {
        _ui.value.songs
            .filter { it.pack == null }
            .mapNotNull { song -> songs.simfileFile(song.trackId).takeIf { it.isFile }?.let { OwnPackStore.OwnSong.of(song, it) } }
    }

    private fun refreshOwnCount() {
        viewModelScope.launch {
            val made = ownSongs().size
            updatePacks { it.copy(ownCharts = made) }
        }
    }

    /** Save every made chart into the own pack's folder, or as one ZIP at [zip]. */
    private fun writeOwnPack(zip: Uri?) {
        if (_ui.value.packs.saving != null) return
        viewModelScope.launch {
            val all = ownSongs()
            if (all.isEmpty()) {
                updatePacks { it.copy(message = "No charts made yet: generate one from a song first.") }
                return@launch
            }
            updatePacks { it.copy(saving = "Starting", message = null) }
            val progress: (Int, String) -> Unit = { index, title -> updatePacks { it.copy(saving = "${index + 1} of ${all.size}: $title") } }
            val result = runCatching { if (zip != null) ownPack.zip(zip, all, progress) else ownPack.saveAll(all, progress) }
            updatePacks {
                it.copy(
                    saving = null,
                    message = result.fold(
                        onSuccess = { n -> if (zip != null) "Saved $n charts as a pack ZIP." else "Saved $n charts to your pack folder." },
                        onFailure = { failure -> "Could not save the pack: ${failure.message ?: "unknown error"}" },
                    ),
                )
            }
        }
    }

    /** Mirror imported packs, the import queue and the own pack's folder into [GlyphUiState.packs]. */
    private fun watchPacks() {
        viewModelScope.launch {
            packLibrary.ensureLoaded()
            packLibrary.packs.collect { list -> updatePacks { it.copy(imported = list) } }
        }
        viewModelScope.launch {
            ownPack.folder.collect { tree -> updatePacks { it.copy(folderName = tree?.let(TreePackSink::displayName)) } }
        }
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(PackImportWorker.WORK_NAME)
                .collect { infos ->
                    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val waiting = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    infos.filter { !it.state.isFinished }.forEach { watchedImports += it.id }
                    // How the most recent watched import ended, once.
                    val ended = infos.filter { it.state.isFinished && it.id in watchedImports && handledImports.add(it.id) }
                    val message = ended.lastOrNull()?.let { info ->
                        val name = info.outputData.getString(PackImportWorker.KEY_NAME).orEmpty()
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val skipped = info.outputData.getInt(PackImportWorker.KEY_SKIPPED, 0)
                                "$name imported: ${info.outputData.getInt(PackImportWorker.KEY_SONGS, 0)} songs." +
                                    if (skipped > 0) " $skipped had no four-panel chart." else ""
                            }
                            WorkInfo.State.FAILED -> "${name.ifBlank { "The pack" }} was not imported: " +
                                (info.outputData.getString(PackImportWorker.KEY_FAILURE) ?: "unknown error")
                            else -> "Import cancelled."
                        }
                    }
                    updatePacks { packs ->
                        packs.copy(
                            importing = when {
                                running != null -> GlyphPackImportUi(
                                    name = running.progress.getString(PackImportWorker.KEY_NAME).orEmpty().ifBlank { "Pack" },
                                    fraction = running.progress.getFloat(PackImportWorker.KEY_FRACTION, 0f),
                                    stage = running.progress.getString(PackImportWorker.KEY_STAGE) ?: "Starting",
                                    queued = waiting,
                                )
                                waiting > 0 -> GlyphPackImportUi("Pack", 0f, "Waiting", waiting - 1)
                                else -> null
                            },
                            message = message ?: packs.message,
                        )
                    }
                }
        }
    }

    /**
     * Mirror the line ([ChartQueue]) into [GlyphUiState.generation] and the
     * songs waiting, for as long as the screen lives; refresh each song whose
     * chart lands, since the library's flow never sees a chart file written.
     */
    private fun watchCharts() {
        viewModelScope.launch {
            // A batch a stopped worker left behind gets a worker again.
            if (chartQueue.claimIfIdle()) StepManiaGenerationWorker.start(context)
            var landed = emptySet<String>()
            chartQueue.state.collect { queue ->
                val made = queue.finished.filter { it.failure == null }.mapTo(HashSet()) { it.trackId }
                (made - landed).forEach(::refreshCharted)
                landed = made
                _ui.value = _ui.value.copy(
                    generation = generationOf(queue),
                    queuedTracks = queue.waiting.mapTo(HashSet()) { it.trackId } +
                        queue.running.map { it.job.trackId },
                )
            }
        }
    }

    /** Which separation servers there are, for the settings and the batch dialog. */
    private fun watchChartServers() {
        viewModelScope.launch {
            remoteStems.stemServerFlow.collect { servers ->
                _ui.value = _ui.value.copy(
                    chartServers = GlyphChartServers(
                        gpu = servers.any { it.separatesOnGpu == true },
                        cpu = servers.any { it.separatesOnGpu == false },
                        unknown = servers.any { it.separatesOnGpu == null },
                    ),
                )
            }
        }
    }

    /**
     * "Play in Trypt Mania" from a song's menu elsewhere in the app: the song
     * found on the wheel and chosen, its chart made first when it has none.
     * A song not on the phone, or not one the mode plays, says why.
     */
    /** Settings › Audio's Measure: straight to the Sync tab, and out again when it closes. */
    private fun watchSyncRequests() {
        viewModelScope.launch {
            TryptManiaRequests.syncPending.collect { pending ->
                if (!pending || !TryptManiaRequests.takeSync()) return@collect
                openSettings()
                _ui.value = _ui.value.copy(syncFocus = true)
            }
        }
    }

    private fun watchPlayRequests() {
        viewModelScope.launch {
            TryptManiaRequests.pending.collect { pending ->
                if (pending == null) return@collect
                val request = TryptManiaRequests.take() ?: return@collect
                val library = ui.first { !it.isLoadingSongs }.songs
                val (song, why) = findRequested(request, library)
                wheelFocusJob?.cancel()
                stopPreview()
                if (song == null) {
                    _ui.value = _ui.value.copy(screen = GlyphScreen.HOME, songListMode = GlyphSongListMode.PLAY, error = why)
                    return@collect
                }
                _ui.value = _ui.value.copy(
                    screen = GlyphScreen.HOME,
                    songListMode = GlyphSongListMode.PLAY,
                    songQuery = "",
                    wheelFocus = GlyphWheel.songKey(song.trackId),
                    error = null,
                )
                selectSong(song.trackId)
                if (song.chartState == GlyphChartState.NOT_GENERATED && song.trackId !in _ui.value.queuedTracks) {
                    generate(song.audioUri, song.audioFileName, song)
                }
            }
        }
    }

    /**
     * The wheel's song for [request]: by its file where the menu knew it, by
     * its download or library copy ([tf.monochrome.android.player.LocalTrackLocator]),
     * then by title, artist and length. Null, with why, when there is none.
     */
    private suspend fun findRequested(request: TryptManiaRequest, library: List<GlyphSong>): Pair<GlyphSong?, String?> {
        // A linked pack song's music is a document; the menu knows its file.
        fun pathOf(path: String) = if (path.startsWith("content:")) {
            tf.monochrome.android.util.safDocumentUriToPath(android.net.Uri.parse(path)) ?: path
        } else {
            path.removePrefix("file://")
        }
        fun byPath(path: String?) = path?.let { wanted -> library.firstOrNull { pathOf(it.filePath) == pathOf(wanted) } }
        byPath(request.filePath)?.let { return it to null }
        val local = runCatching {
            trackLocator.findLocalSource(
                title = request.title,
                artist = request.artist,
                albumTitle = request.album,
                durationSeconds = request.durationSeconds,
                catalogTrackId = request.catalogTrackId,
            )
        }.getOrNull()
        byPath(local?.filePath)?.let { return it to null }
        library.firstOrNull {
            it.title.equals(request.title, ignoreCase = true) &&
                it.artist.equals(request.artist, ignoreCase = true) &&
                (request.durationSeconds <= 0 || kotlin.math.abs(it.durationSeconds - request.durationSeconds) <= 2)
        }?.let { return it to null }
        val onPhone = request.filePath != null || local != null
        return null to if (onPhone) {
            "Trypt Mania plays the MP3 and FLAC files in your library, and \"${request.title}\" is not one of them."
        } else {
            "\"${request.title}\" is not on your phone. Download it first: Trypt Mania plays songs saved on the phone."
        }
    }

    /** [trackId]'s row, and the selection when it is the chosen song, re-read with its new chart. */
    private fun refreshCharted(trackId: String) {
        val song = _ui.value.songs.firstOrNull { it.trackId == trackId } ?: return
        if (_ui.value.selectedSong?.trackId == trackId) {
            loadSelection(song)
            return
        }
        viewModelScope.launch {
            val (refreshed, _) = songs.withCurrentChart(song)
            _ui.value = _ui.value.copy(songs = _ui.value.songs.map { if (it.trackId == trackId) refreshed else it })
        }
    }

    // ── running a chart ─────────────────────────────────────────────────

    private fun start(training: Boolean, fromSeconds: Float? = null, playAtMs: Long? = null) {
        val song = _ui.value.selectedSong ?: return
        val chart = _ui.value.chart ?: return
        wheelFocusJob?.cancel()
        stopPreview()
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }
        // Training Ground's segment picker draws the song's bars.
        if (training) ensureWaveform(song)

        // A run of this phone's own (Play again, a section to practise) after
        // a room's is no longer the room's.
        if (playAtMs == null && _ui.value.inRoomPlay) {
            stopRoomFrames()
            _ui.value = _ui.value.copy(inRoomPlay = false, multiplayer = _ui.value.multiplayer.copy(startAtMs = null))
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

        transport.prepare(song.audioUri)
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
        // A room's run always has one: the music is held until the moment
        // the server named ([playAtMs]), which READY, GO! counts down to.
        val intro = playAtMs != null || (!training && !_ui.value.gameplay.modifiers.reducedMotion)
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
                failedAtMs = 0L,
                failStops = false,
            ),
            results = null,
        )
        introJob?.cancel()
        failJob?.cancel()
        if (intro) {
            introJob = viewModelScope.launch {
                delay(playAtMs?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) } ?: INTRO_MILLIS)
                transport.play()
                startTick()
                _ui.value = _ui.value.copy(gameplay = _ui.value.gameplay.copy(introStartedAtMs = 0L))
            }
        } else {
            transport.play()
            startTick()
        }
        if (playAtMs != null) startRoomFrames()
    }

    /**
     * A fresh StepMania scorekeeper for the run, sized to what the engine is
     * scoring now: the whole chart, or the loop being practised.
     */
    private fun resetStepScore() {
        val running = engine ?: return
        stepScore = StepManiaScoreKeeper(
            possibleJudgements = running.scoreSnapshot.totalJudgements,
            // A room's song is played to its end by everyone: an empty bar
            // is a fail in the ranking, never a stop.
            failType = if (_ui.value.inRoomPlay) GlyphFailType.END_OF_SONG else _ui.value.gameplay.modifiers.failType,
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
        // Only a run on screen is rebuilt. The last run's engine stays after
        // it ends, and a modifier changed from the menus restarted it there:
        // its tick ran behind the menus, and the next song's READY played.
        // The next run is built with the new modifiers anyway.
        val screen = _ui.value.screen
        val inRun = screen == GlyphScreen.GAMEPLAY || screen == GlyphScreen.TRAINING ||
            _ui.value.editor?.testing == true
        if (!inRun) return
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
                            laneFlashes.set(event.lane, atNanos = System.nanoTime(), isHit = event.judgement.isHit)
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
                    // The bar has emptied. The screen shakes; on StepMania's
                    // Immediate the run also stops where it is, holds under
                    // FAILED, then shows its results. Never in Training
                    // Ground, which is for getting it wrong.
                    val keeper = stepScore
                    if (keeper != null && keeper.failed && _ui.value.gameplay.failedAtMs == 0L &&
                        _ui.value.screen == GlyphScreen.GAMEPLAY
                    ) {
                        publish(position, running, judged)
                        val at = System.currentTimeMillis()
                        _ui.value = _ui.value.copy(
                            gameplay = _ui.value.gameplay.copy(failedAtMs = at, failStops = keeper.stopsRun),
                        )
                        if (keeper.stopsRun) {
                            transport.pause()
                            failJob = viewModelScope.launch {
                                delay(FAIL_HOLD_MILLIS)
                                // Still this run, still on its screen: not left or restarted meanwhile.
                                if (_ui.value.screen == GlyphScreen.GAMEPLAY && _ui.value.gameplay.failedAtMs == at) finish()
                            }
                            return@launch
                        }
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

    /**
     * Beats left before play begins, for the on-screen count.
     *
     * Written when the number changes, and otherwise at the readouts' own
     * rate for the playhead Training Ground's picker draws from the position:
     * written on every tick, it was 125 new states a second for one digit.
     */
    private fun publishCountIn(position: Float, endsAt: Float) {
        val bpm = _ui.value.simfile?.timing?.startBpm ?: return
        if (bpm <= 0f) return
        val remaining = (((endsAt - position) / (60f / bpm)).toInt() + 1).coerceAtLeast(0)
        tickMetronome(position)
        val now = System.nanoTime()
        if (remaining == _ui.value.gameplay.countInBeatsRemaining && now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(
                positionSeconds = position,
                countInBeatsRemaining = remaining,
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
        // A run stopped by a fail is over: a step under FAILED would still be
        // judged at the stopped music, and the results read the engine's count.
        if (_ui.value.gameplay.failStops) return
        running.press(lane, transport.positionAt(touchNanos(atUptimeMillis)) - audioOffsetSeconds)
    }

    private fun releaseLane(lane: GlyphLane, atUptimeMillis: Long) {
        val running = engine ?: return
        // Nor is a hold let go then: it would be judged dropped after the end.
        if (_ui.value.gameplay.failStops) return
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
        // A failed run has stopped for good: Resume would only start its music
        // again under FAILED.
        if (_ui.value.gameplay.failStops) return
        // A room's song waits for nobody: the panel opens over the run, for
        // Quit, and the music and the judging go on under it.
        if (_ui.value.inRoomPlay) {
            // Out of the app mid-song is out of the song: the room cannot
            // wait, and the music must not play on behind the phone.
            if (!foreground) {
                quit()
                return
            }
            _ui.value = _ui.value.copy(gameplay = _ui.value.gameplay.copy(isPaused = !_ui.value.gameplay.isPaused))
            return
        }
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
                failedAtMs = 0L,
                failStops = false,
            ),
        )
        failJob?.cancel()
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
        failJob?.cancel()
        transport.pause()
        // Out of a room's song before its end: a fail in the ranking, and
        // back to the room rather than to the songs.
        if (_ui.value.inRoomPlay) {
            leaveRoomRun(failed = true)
            return
        }
        if (_ui.value.editor?.testing == true) {
            backToEditor(null)
            return
        }
        // Back to the songs: the next run prepares its own.
        transport.stop()
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
        _ui.value = _ui.value.copy(
            chartMode = ChartMode.entries.firstOrNull { it.name == prefs.getString(PREF_CHART_MODE, null) } ?: ChartMode.FAST,
        )
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
            landscapeFieldWidth = prefs.getFloat(PREF_LANDSCAPE_FIELD, DEFAULT_LANDSCAPE_FIELD)
                .coerceIn(MIN_LANDSCAPE_FIELD, 1f),
            skin = GlyphSkin.fromId(prefs.getString(PREF_SKIN, null)),
            graphics = GlyphGraphics(
                hitRays = prefs.getBoolean(PREF_GFX_RAYS, true),
                rayQuality = prefs.getInt(PREF_GFX_RAY_QUALITY, 1).coerceIn(1, 4),
                noteLighting = prefs.getBoolean(PREF_GFX_LIGHTING, true),
                explosions = prefs.getBoolean(PREF_GFX_EXPLOSIONS, true),
                beatFlash = prefs.getBoolean(PREF_GFX_BEAT_FLASH, true),
                comboBurst = prefs.getBoolean(PREF_GFX_COMBO_BURST, true),
                tvShader = GlyphTvShader.fromId(prefs.getString(PREF_GFX_TV_SHADER, null)),
                tv = loadTvTuning(),
            ),
        )
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(modifiers = stored),
            playerName = prefs.getString(PREF_NAME, null).orEmpty(),
            keys = GlyphKeyBindings.decode(prefs.getString(PREF_KEYS, null)),
            audioOffsetMs = outputSync.delayMs.value,
            previews = prefs.getBoolean(PREF_PREVIEWS, true),
            wheelSound = prefs.getBoolean(PREF_WHEEL_SOUND, true),
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
            .putFloat(PREF_LANDSCAPE_FIELD, modifiers.landscapeFieldWidth)
            .putString(PREF_SKIN, modifiers.skin.id)
            .putBoolean(PREF_GFX_RAYS, modifiers.graphics.hitRays)
            .putInt(PREF_GFX_RAY_QUALITY, modifiers.graphics.rayQuality)
            .putBoolean(PREF_GFX_LIGHTING, modifiers.graphics.noteLighting)
            .putBoolean(PREF_GFX_EXPLOSIONS, modifiers.graphics.explosions)
            .putBoolean(PREF_GFX_BEAT_FLASH, modifiers.graphics.beatFlash)
            .putBoolean(PREF_GFX_COMBO_BURST, modifiers.graphics.comboBurst)
            .putString(PREF_GFX_TV_SHADER, modifiers.graphics.tvShader.id)
            .also { saveTvTuning(it, modifiers.graphics.tv) }
            .apply()
    }

    /**
     * The TV's sliders, one line (GlyphTvTuning.encode); a pixel size never
     * set is left to its look's default. Before the line there was a key a
     * slider, read once here and dropped at the next save.
     */
    private fun loadTvTuning(): GlyphTvTuning {
        prefs.getString(PREF_GFX_TV_TUNING, null)?.let { return GlyphTvTuning.decode(it) }
        val defaults = GlyphTvTuning()
        fun unit(key: String, default: Float) = prefs.getFloat(key, default).coerceIn(0f, 1f)
        val legacyCurve = unit(PREF_GFX_TV_CURVATURE, 0f)
        return GlyphTvTuning(
            pixelSizes = GlyphTvShader.entries.mapNotNull { shader ->
                prefs.getInt(PREF_GFX_TV_PIXEL + shader.id, 0).takeIf { it > 0 }?.let { shader to it }
            }.toMap(),
            strength = unit(PREF_GFX_TV_STRENGTH, defaults.strength),
            brightness = prefs.getFloat(PREF_GFX_TV_BRIGHTNESS, defaults.brightness)
                .coerceIn(GlyphTvTuning.MIN_BRIGHTNESS, GlyphTvTuning.MAX_BRIGHTNESS),
            scanlines = unit(PREF_GFX_TV_SCANLINES, defaults.scanlines),
            mask = unit(PREF_GFX_TV_MASK, defaults.mask),
            glow = unit(PREF_GFX_TV_GLOW, defaults.glow),
            sharpness = unit(PREF_GFX_TV_SHARPNESS, defaults.sharpness),
            chromatic = unit(PREF_GFX_TV_CHROMATIC, defaults.chromatic),
            curveTopDeg = GlyphTvTuning.degreesOf(GlyphTvTuning.LEGACY_WARP_Y * legacyCurve),
            curveSideDeg = GlyphTvTuning.degreesOf(GlyphTvTuning.LEGACY_WARP_X * legacyCurve),
        ).clamped()
    }

    private fun saveTvTuning(edit: android.content.SharedPreferences.Editor, tv: GlyphTvTuning) {
        edit.putString(PREF_GFX_TV_TUNING, tv.clamped().encode())
        for (shader in GlyphTvShader.entries) edit.remove(PREF_GFX_TV_PIXEL + shader.id)
        for (key in LEGACY_TV_KEYS) edit.remove(key)
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

    /** Each difficulty's best run in a chart's [history], for the card's grade. */
    private fun bestGradesIn(history: List<GlyphAttempt>): Map<StepManiaDifficulty, GlyphAttempt> =
        StepManiaDifficulty.entries.mapNotNull { difficulty ->
            bestOf(history, difficulty)?.let { difficulty to it }
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

    // ── tap to sync ─────────────────────────────────────────────────────

    private var tapSync: GlyphTapSync? = null
    private var tapSyncJob: Job? = null

    /**
     * Play the click track through the game's own transport and clock, so a
     * tap is timed exactly as a lane press is (see [GlyphTapSync]).
     */
    private fun startTapSync() {
        stopTapSync()
        val sync = GlyphTapSync()
        tapSync = sync
        _ui.value = _ui.value.copy(tapSync = GlyphTapSyncUi(playing = true, taps = 0, needed = GlyphTapSync.NEEDED))
        tapSyncJob = viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, SYNC_TRACK_FILE).also { file ->
                    val wav = GlyphSyncTrack.wav()
                    // Written once, and again only if the track itself changes.
                    if (file.length() != wav.size.toLong()) file.writeBytes(wav)
                }
            }
            transport.loop = null
            transport.prepare(Uri.fromFile(file))
            transport.setSpeed(1f, true)
            transport.seekTo(0f)
            transport.play()
            while (isActive) {
                val position = transport.pump()
                val beat = ((position - GlyphSyncTrack.FIRST_BEAT_SECONDS) / GlyphSyncTrack.BEAT_SECONDS)
                    .let { if (it < 0f) -1 else it.toInt() }
                val shown = _ui.value.tapSync
                if (shown != null && shown.beat != beat) _ui.value = _ui.value.copy(tapSync = shown.copy(beat = beat))
                if (sync.done || position >= GlyphSyncTrack.endSeconds) break
                delay(SYNC_TICK_MILLIS)
            }
            transport.pause()
            // Finished on its own: nothing left for a later stop to pause,
            // which by then could be a run's song.
            tapSyncJob = null
            publishTapSync(playing = false)
        }
    }

    private fun syncTap(atUptimeMillis: Long) {
        val sync = tapSync ?: return
        if (_ui.value.tapSync?.playing != true) return
        // The clock a lane press is judged on, before any offset.
        if (sync.tap(transport.positionAt(touchNanos(atUptimeMillis)))) publishTapSync(playing = !sync.done)
    }

    private fun publishTapSync(playing: Boolean) {
        val sync = tapSync ?: return
        val beat = _ui.value.tapSync?.beat ?: -1
        _ui.value = _ui.value.copy(
            tapSync = GlyphTapSyncUi(
                playing = playing,
                taps = sync.taps,
                needed = GlyphTapSync.NEEDED,
                offsetMs = sync.offsetMs?.coerceIn(-GlyphCalibration.MAX_OFFSET_MS, GlyphCalibration.MAX_OFFSET_MS),
                spreadMs = sync.spreadMs,
                steady = sync.steady,
                couldBeAClickOff = sync.couldBeAClickOff,
                beat = beat,
            ),
        )
    }

    /** Stop the click track; whatever was read stays on screen to apply. */
    private fun stopTapSync() {
        val job = tapSyncJob ?: return
        tapSyncJob = null
        job.cancel()
        transport.pause()
        if (_ui.value.tapSync != null) publishTapSync(playing = false)
    }

    /**
     * Sets the offset for the output playing now, and so for the whole app:
     * the lyrics and the visuals wait the same amount on it.
     */
    private fun setAudioOffset(ms: Int) {
        val clamped = tf.monochrome.android.audio.sync.OutputSync.clamp(ms)
        outputSync.setForCurrent(clamped)
        _ui.value = _ui.value.copy(audioOffsetMs = clamped)
    }

    /** The offset follows the output: other headphones, another delay. */
    private fun watchOutputSync() {
        viewModelScope.launch {
            combine(outputSync.output, outputSync.delayMs) { output, ms -> output to ms }.collect { (output, ms) ->
                _ui.value = _ui.value.copy(
                    audioOffsetMs = ms,
                    syncOutput = output?.let { tf.monochrome.android.audio.eq.label(context, it) },
                )
            }
        }
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
        // The grid's gutter draws the song's bars.
        ensureWaveform(song)
        viewModelScope.launch {
            val simfile = if (song.hasChart) songs.simfile(song.trackId) else null
            val timing = simfile?.timing ?: GlyphTiming.constant(song.bpm ?: GlyphTiming.DEFAULT_BPM)
            val chart = simfile?.chart(difficulty)
            editorHistory.clear()
            transport.loop = null
            transport.prepare(song.audioUri)
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
        transport.stop()
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
                // A pack song starts from the pack's chart; the edit is saved as
                // the app's own copy, and the pack's file is left as it came.
                val existing = songs.chartText(song.trackId)
                val timing = editor.timing
                var text = if (existing != null) {
                    SscChartWriter.withChart(existing, editor.difficulty, editor.meter, noteData)
                } else {
                    SscChartWriter.newSimfile(
                        title = song.title,
                        artist = song.artist,
                        musicFileName = song.audioFileName,
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

        val previousBest = bestOf(attempts.attempts(song.chartId), difficulty)
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
        if (_ui.value.inRoomPlay) {
            // The room ranks everyone once the last phone has said how it went.
            stopRoomFrames()
            rooms.finish(roomResult(failed = attempt.failed, attempt = attempt))
        }
        // A room's run of another phone's chart is not a run of this phone's:
        // it goes to the room and is not kept against the local scores.
        if (!_ui.value.inRoomPlay || roomPlan?.sameChart == true) attempts.save(attempt)
        // Read once after the save, for the table and every difficulty's best.
        val history = attempts.attempts(song.chartId)
        val machine = attempts.highScoresIn(history, difficulty)

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
            bestGrades = bestGradesIn(history),
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

    // ── multiplayer ─────────────────────────────────────────────────────

    /** The rooms server's session, the sign-in and the servers list, into the state. */
    private fun watchRooms() {
        viewModelScope.launch {
            rooms.session.collect { session ->
                _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(session = session))
            }
        }
        viewModelScope.launch {
            rooms.hasServer.collect { has ->
                _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(hasServer = has))
            }
        }
        viewModelScope.launch {
            supabaseAuth.userProfile.collect {
                refreshRoomIdentity()
                // Back from the account page signed in: the socket opens
                // without another tap.
                if (_ui.value.screen == GlyphScreen.MULTIPLAYER) connectRooms()
            }
        }
        viewModelScope.launch { rooms.events.collect { onRoomEvent(it) } }
    }

    /** Who this phone plays as: the signed-in account's name, or nobody. */
    private fun refreshRoomIdentity() {
        val signedIn = supabaseAuth.accessToken() != null
        _ui.value = _ui.value.copy(
            multiplayer = _ui.value.multiplayer.copy(
                signedIn = signedIn,
                playerName = if (signedIn) supabaseAuth.playerName() ?: effectiveName() else "",
            ),
        )
    }

    private fun openMultiplayer() {
        wheelFocusJob?.cancel()
        stopPreview()
        refreshRoomIdentity()
        _ui.value = _ui.value.copy(screen = GlyphScreen.MULTIPLAYER)
        connectRooms()
    }

    /** Opens the socket as the signed-in account; nothing without one, or without a server. */
    private fun connectRooms() {
        val token = supabaseAuth.accessToken() ?: return
        if (!_ui.value.multiplayer.hasServer) return
        rooms.connect(_ui.value.multiplayer.playerName.ifBlank { effectiveName() }, token)
    }

    /** Off the rooms screen: the socket closes, and with it any room this phone was in. */
    private fun closeMultiplayer() {
        roomsHidden = false
        roomStartJob?.cancel()
        roomTransferJob?.cancel()
        clearRoomSongs(keep = null)
        stopRoomFrames()
        roomPlan = null
        rooms.disconnect()
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.MENU,
            inRoomPlay = false,
            multiplayer = _ui.value.multiplayer.copy(
                haveSong = null, missing = null, startAtMs = null, songTransfer = null, transferError = null,
            ),
        )
    }

    private fun onRoomEvent(msg: RoomMsg) {
        when (msg) {
            is RoomMsg.Chart -> checkRoomChart(msg.chart)
            is RoomMsg.Start -> onRoomStart(msg.at, msg.hash)
            is RoomMsg.Room -> if (msg.room.chart == null && roomPlan != null) {
                roomPlan = null
                _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(haveSong = null, missing = null))
            }
            is RoomMsg.Left -> {
                roomPlan = null
                roomStartJob?.cancel()
                if (_ui.value.inRoomPlay) leaveRoomRun(failed = false)
                _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(haveSong = null, missing = null, startAtMs = null))
            }
            else -> Unit
        }
    }

    /**
     * Whether this phone can play the room's chart. The notes come with it
     * from the host, so what the phone needs is the song: the same chart on
     * this phone (by hash) is best, else a song of that title and artist, or
     * that title, of the same length within [SONG_LENGTH_SLACK_SECONDS].
     * The room is told either way, so the host sees who is in.
     */
    private fun checkRoomChart(chart: RoomChart) {
        roomStartJob?.cancel()
        // Another chart: whatever was going up or coming down was the last one's song.
        if (roomPlan?.hash != chart.hash) roomTransferJob?.cancel()
        clearRoomSongs(keep = chart.hash)
        _ui.value = _ui.value.copy(
            multiplayer = _ui.value.multiplayer.copy(haveSong = null, missing = null, songTransfer = null, transferError = null),
        )
        viewModelScope.launch {
            val plan = planRoomChart(chart)
            roomPlan = plan
            val missing = when {
                plan != null -> null
                chart.notes.isBlank() -> "The host's chart could not be read."
                else -> "${chart.title} by ${chart.artist} is not on this phone."
            }
            _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(haveSong = plan != null, missing = missing))
            rooms.have(plan != null)
        }
    }

    /** The room chart's notes, read: the simfile and the difficulty to play. */
    private fun parseRoomChart(chart: RoomChart): Pair<tf.monochrome.android.glyph.chart.GlyphSimfile, StepManiaDifficulty>? {
        val simfile = runCatching { SscParser.parse(chart.notes) }.getOrNull() ?: return null
        val difficulty = StepManiaDifficulty.entries.firstOrNull { it.sscName.equals(chart.difficulty, ignoreCase = true) }
            ?: simfile.charts.firstOrNull()?.difficulty
            ?: return null
        if (simfile.chart(difficulty) == null) return null
        return simfile to difficulty
    }

    private suspend fun planRoomChart(chart: RoomChart): RoomPlan? {
        val (simfile, difficulty) = parseRoomChart(chart) ?: return null
        // Fetched from the host already (the same chart picked again).
        downloadedRoomSong(chart.hash)?.let { file -> return planFromFile(chart, file) }
        val candidates = _ui.value.songs.filter { it.filePath.isNotBlank() && it.title.equals(chart.title, ignoreCase = true) }
        // The same chart on this phone: its own song, and its scores count.
        for (song in candidates.filter { it.hasChart }) {
            val text = songs.chartText(song.trackId) ?: continue
            if (RoomProtocol.chartHash(text) == chart.hash) return RoomPlan(song, simfile, difficulty, chart.hash, sameChart = true)
        }
        val song = candidates.firstOrNull { it.artist.equals(chart.artist, ignoreCase = true) && it.lengthMatches(chart) }
            ?: candidates.firstOrNull { it.lengthMatches(chart) }
            ?: return null
        return RoomPlan(song, simfile, difficulty, chart.hash, sameChart = false)
    }

    /**
     * A song fetched from the host as the room's: played from the file, not
     * added to the library, and its runs never count towards local scores,
     * since the chart is the host's.
     */
    private fun planFromFile(chart: RoomChart, file: File): RoomPlan? {
        val (simfile, difficulty) = parseRoomChart(chart) ?: return null
        val song = GlyphSong(
            trackId = "room:${chart.hash}",
            chartId = chart.hash,
            title = chart.title,
            artist = chart.artist,
            filePath = file.absolutePath,
            artworkUri = null,
            durationSeconds = chart.duration.roundToInt(),
            codec = tf.monochrome.android.domain.model.AudioCodec.UNKNOWN,
            bpm = chart.bpm.toFloat().takeIf { it > 0f },
            difficulties = listOf(difficulty),
            chartState = GlyphChartState.READY,
        )
        return RoomPlan(song, simfile, difficulty, chart.hash, sameChart = false)
    }

    /** Where songs fetched from a host are kept: the cache, as they belong to a room, not the library. */
    private val roomSongsDir: File get() = File(context.cacheDir, "room-songs")

    private fun roomSongStem(hash: String): String = hash.filter(Char::isLetterOrDigit).take(40)

    /** A fetched song for chart [hash], complete, if one is here. */
    private fun downloadedRoomSong(hash: String): File? {
        val stem = roomSongStem(hash)
        return roomSongsDir.listFiles()?.firstOrNull {
            it.isFile && it.name.substringBeforeLast('.') == stem && !it.name.endsWith(".part")
        }
    }

    /** Every fetched song but [keep]'s chart's: a room keeps only the song it is on. */
    private fun clearRoomSongs(keep: String?) {
        val stem = keep?.let(::roomSongStem)
        roomSongsDir.listFiles()?.forEach { file ->
            if (stem == null || file.name.substringBefore('.') != stem) file.delete()
        }
    }

    /**
     * Progress and the last failure, from the transfer's own thread too;
     * [shrinking] while the progress is the song's smaller copy being made.
     */
    private fun setRoomTransfer(progress: Float?, error: RoomTransferError? = null, shrinking: Boolean = false) {
        val step = progress?.let { (it.coerceIn(0f, 1f) * 100f).toInt() / 100f }
        val shrink = shrinking && step != null
        _ui.update { state ->
            val mp = state.multiplayer
            if (mp.songTransfer == step && mp.transferError == error && mp.songShrinking == shrink) state
            else state.copy(multiplayer = mp.copy(songTransfer = step, transferError = error, songShrinking = shrink))
        }
    }

    /**
     * Host: this phone's song for the room's chart, up to the rooms server for
     * the players without it. Streamed from where it is; the room hears it is
     * there from the server, and their buttons light up.
     *
     * A song bigger than the server takes (its `maxSongBytes`) goes up as an
     * Opus copy at 320 kbps instead, made first and deleted once it is up or
     * has failed. Where no copy can be made, or it is still too big, the song
     * goes up as it is and the server's refusal is shown, as before.
     */
    private fun shareRoomSong() {
        val plan = roomPlan ?: return
        if (roomTransferJob?.isActive == true) return
        val source = plan.song.audioUri
        val ext = RoomProtocol.songExt(plan.song.audioFileName).takeIf { it != "bin" }
            ?: context.contentResolver.getType(source)
                ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.let(RoomProtocol::songExt)
            ?: "bin"
        roomTransferJob = viewModelScope.launch {
            setRoomTransfer(0f)
            val limit = tf.monochrome.android.audio.share.ShareSize.limit(rooms.maxSongBytes())
            val prepared = withContext(Dispatchers.IO) {
                tf.monochrome.android.audio.share.ShareSize.prepare(
                    shareShrinker, source.toString(), rooms.songSize(source), ext, limit,
                    durationMs = plan.song.durationSeconds.takeIf { it > 0 }?.times(1000L),
                ) { setRoomTransfer(it, shrinking = true) }
            }
            try {
                val upload = prepared.shrunk?.let(android.net.Uri::fromFile) ?: source
                setRoomTransfer(0f)
                val result = rooms.shareSong(plan.hash, upload, prepared.ext) { setRoomTransfer(it) }
                setRoomTransfer(null, result.exceptionOrNull()?.let { RoomTransferError(sharing = true, reason = it.message.orEmpty()) })
            } finally {
                prepared.discard()
            }
        }
    }

    /**
     * A player without the song: the one the host shared, fetched, played
     * from, and readied with, as tapping it says this phone is in.
     */
    private fun fetchRoomSong() {
        val session = rooms.session.value
        val shared = session.room?.sharedSong ?: return
        val chart = session.chart?.takeIf { it.hash == shared.hash } ?: return
        if (roomTransferJob?.isActive == true) return
        roomTransferJob = viewModelScope.launch {
            setRoomTransfer(0f)
            val target = File(roomSongsDir, "${roomSongStem(shared.hash)}.${RoomProtocol.songExt("x." + shared.ext)}")
            val fetched = rooms.fetchSong(shared, target) { setRoomTransfer(it) }
            val file = fetched.getOrElse {
                setRoomTransfer(null, RoomTransferError(sharing = false, reason = it.message.orEmpty()))
                return@launch
            }
            // The room moved on while it came down: it is not this chart's song any more.
            if (rooms.session.value.chart?.hash != chart.hash) {
                file.delete()
                setRoomTransfer(null)
                return@launch
            }
            val plan = planFromFile(chart, file)
            if (plan == null) {
                setRoomTransfer(null, RoomTransferError(sharing = false, reason = "the host's chart could not be read"))
                return@launch
            }
            roomPlan = plan
            _ui.update { it.copy(multiplayer = it.multiplayer.copy(haveSong = true, missing = null, songTransfer = null, transferError = null)) }
            rooms.haveAndReady()
        }
    }

    private fun GlyphSong.lengthMatches(chart: RoomChart): Boolean =
        chart.duration <= 0.0 || abs(durationSeconds - chart.duration) <= SONG_LENGTH_SLACK_SECONDS

    /**
     * The server named the moment: everyone's music starts at [atServerMs]
     * on its clock, which this phone has measured its gap to. The run is set
     * up READY, GO! ahead of it, and the intro counts down to that moment
     * instead of running its usual length.
     */
    private fun onRoomStart(atServerMs: Long, hash: String) {
        val plan = roomPlan ?: return
        if (plan.hash != hash) return
        val playAt = rooms.localMsFor(atServerMs)
        roomStartJob?.cancel()
        _ui.value = _ui.value.copy(multiplayer = _ui.value.multiplayer.copy(startAtMs = playAt))
        roomStartJob = viewModelScope.launch {
            delay((playAt - INTRO_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L))
            _ui.value = _ui.value.copy(
                selectedSong = plan.song,
                simfile = plan.simfile,
                selectedDifficulty = plan.difficulty,
                inRoomPlay = true,
                results = null,
            )
            start(training = false, playAtMs = playAt)
        }
    }

    /** The selected song and difficulty as the room's chart: its text goes to everyone. */
    private fun pickForRoom() {
        val song = _ui.value.selectedSong ?: return
        val difficulty = _ui.value.selectedDifficulty ?: return
        viewModelScope.launch {
            val text = songs.chartText(song.trackId)
            if (text.isNullOrBlank()) {
                _ui.value = _ui.value.copy(error = "That song has no chart to share.")
                return@launch
            }
            rooms.pick(
                RoomChart(
                    hash = RoomProtocol.chartHash(text),
                    title = song.title,
                    artist = song.artist,
                    difficulty = difficulty.sscName,
                    duration = song.durationSeconds.toDouble(),
                    bpm = (song.bpm ?: _ui.value.simfile?.timing?.startBpm ?: 0f).toDouble(),
                    notes = text,
                ),
            )
            wheelFocusJob?.cancel()
            stopPreview()
            _ui.value = _ui.value.copy(screen = GlyphScreen.MULTIPLAYER)
        }
    }

    /** This phone's score as it stands, round the room a few times a second. */
    private fun startRoomFrames() {
        roomFrameJob?.cancel()
        roomFrameJob = viewModelScope.launch {
            while (isActive) {
                rooms.frame(roomLive())
                delay(ROOM_FRAME_MILLIS)
            }
        }
    }

    private fun stopRoomFrames() {
        roomFrameJob?.cancel()
        roomFrameJob = null
    }

    private fun roomLive(): RoomLive {
        val snapshot = engine?.scoreSnapshot
        val keeper = stepScore
        return RoomLive(
            score = (snapshot?.score ?: 0).toLong(),
            combo = liveCombo,
            acc = (keeper?.percent ?: 0f).toDouble(),
            hp = (keeper?.life ?: StepManiaScoreKeeper.INITIAL_LIFE).toDouble(),
            pos = (_ui.value.gameplay.positionSeconds * 1000f).toLong(),
        )
    }

    /** How this phone's run went, for the ranking: the finished figures, or the live ones on a quit. */
    private fun roomResult(failed: Boolean, attempt: GlyphAttempt? = null): RoomFinished {
        val snapshot = engine?.scoreSnapshot
        val keeper = stepScore
        return RoomFinished(
            score = (attempt?.score ?: snapshot?.score ?: 0).toLong(),
            acc = (attempt?.percent?.takeIf { it >= 0f } ?: keeper?.percent ?: 0f).toDouble(),
            maxCombo = attempt?.maxCombo ?: snapshot?.maxCombo ?: 0,
            grade = if (failed) GlyphStepGrade.FAILED.label else keeper?.grade?.label.orEmpty(),
            failed = failed,
            judgements = attempt?.judgementCounts ?: snapshot?.counts?.entries?.associate { it.key.name to it.value }.orEmpty(),
        )
    }

    /** Out of a room's run before its end: the room is told when [failed], and shown. */
    private fun leaveRoomRun(failed: Boolean) {
        stopRoomFrames()
        roomStartJob?.cancel()
        // Left by the room too (a kick, the room gone), not only by Quit: the
        // song, its tick and a READY still counting down all end here, or
        // the music started or ran on behind the rooms screen.
        stopTick()
        introJob?.cancel()
        failJob?.cancel()
        transport.stop()
        if (failed) rooms.finish(roomResult(failed = true))
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.MULTIPLAYER,
            inRoomPlay = false,
            results = null,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isPaused = false, introStartedAtMs = 0L),
            multiplayer = _ui.value.multiplayer.copy(startAtMs = null),
        )
    }

    /** From a room's results back to the room, where the ranking is. */
    private fun backToRoom() {
        stopTick()
        transport.stop()
        stopRoomFrames()
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.MULTIPLAYER,
            results = null,
            inRoomPlay = false,
            multiplayer = _ui.value.multiplayer.copy(startAtMs = null),
        )
    }

    override fun onCleared() {
        previewJob?.cancel()
        preview.release()
        stopRoomFrames()
        rooms.disconnect()
        // The socket is closed, so any room with it: its song goes too, as
        // leaving the rooms screen clears it.
        clearRoomSongs(keep = null)
        stopTick()
        replaceEngine(null)
        metronome.release()
        transport.release()
        super.onCleared()
    }

    private companion object {
        /** How long a room asked for keeps the rooms socket open behind the app, waiting for the answer. */
        const val ROOM_ANSWER_WAIT_MS = 30_000L

        /**
         * How long the wheel rests before a song is chosen: StepMania's
         * `SampleMusicDelay` was 250 ms, which with the fade read as a preview
         * slow to start. 150 still lets a turn pass songs without starting them.
         */
        const val SAMPLE_MUSIC_DELAY_MS = 150L

        /** How long a song's first preview waits for its drop before starting without it. */
        const val DROP_WAIT_MILLIS = 700L

        const val TAG = "GlyphViewModel"

        /** The click track, written to the cache once from [GlyphSyncTrack]. */
        const val SYNC_TRACK_FILE = "trypt_mania_sync_140.wav"
        const val SYNC_TICK_MILLIS = 8L

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
        const val PREF_LANDSCAPE_FIELD = "landscape_field"
        const val PREF_CHART_MODE = "chart_mode"
        const val PREF_NAME = "player_name"
        const val PREF_SORT_KEY = "song_sort"
        const val PREF_PREVIEWS = "previews"
        const val PREF_WHEEL_SOUND = "wheel_sound"
        const val PREF_KEYS = "keys"

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
        /** Followed by the look's id: one pixel size per look. */
        const val PREF_GFX_TV_PIXEL = "gfx_tv_pixel_"
        const val PREF_GFX_TV_STRENGTH = "gfx_tv_strength"
        const val PREF_GFX_TV_BRIGHTNESS = "gfx_tv_brightness"
        const val PREF_GFX_TV_SCANLINES = "gfx_tv_scanlines"
        const val PREF_GFX_TV_MASK = "gfx_tv_mask"
        const val PREF_GFX_TV_GLOW = "gfx_tv_glow"
        const val PREF_GFX_TV_SHARPNESS = "gfx_tv_sharpness"
        const val PREF_GFX_TV_CHROMATIC = "gfx_tv_chromatic"
        const val PREF_GFX_TV_CURVATURE = "gfx_tv_curvature"
        const val PREF_GFX_TV_TUNING = "gfx_tv_tuning"

        /** The keys a slider the TV's sliders were kept under before [PREF_GFX_TV_TUNING]. */
        private val LEGACY_TV_KEYS = listOf(
            PREF_GFX_TV_STRENGTH, PREF_GFX_TV_BRIGHTNESS, PREF_GFX_TV_SCANLINES, PREF_GFX_TV_MASK,
            PREF_GFX_TV_GLOW, PREF_GFX_TV_SHARPNESS, PREF_GFX_TV_CHROMATIC, PREF_GFX_TV_CURVATURE,
        )
        const val PREF_GFX_TV_SHADER = "gfx_tv_shader"

        const val MAX_MINI = 0.5f
        const val MAX_NAME_LENGTH = 12
        const val NAME_MARKS = " .-_"
        const val DEFAULT_NAME = "PLAYER"

        /** READY, then GO!, before the music: StepMania's own intro, shortened for a phone. */
        const val INTRO_MILLIS = 1_800L

        /** A failed run's stop: the shake, FAILED landing, and a beat to read it, before the results. */
        const val FAIL_HOLD_MILLIS = 2_200L

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

        /** How often a room hears this phone's score while a song plays. */
        private const val ROOM_FRAME_MILLIS = 250L

        /** A song of the room's title is taken for the same song within this much of its length. */
        private const val SONG_LENGTH_SLACK_SECONDS = 2.0
    }
}

/**
 * A room's chart as this phone will play it: the song found here, the
 * host's notes parsed, and whether the chart is the same one this phone has
 * ([sameChart]), in which case the run counts towards its local scores.
 */
private data class RoomPlan(
    val song: GlyphSong,
    val simfile: tf.monochrome.android.glyph.chart.GlyphSimfile,
    val difficulty: StepManiaDifficulty,
    val hash: String,
    val sameChart: Boolean,
)
