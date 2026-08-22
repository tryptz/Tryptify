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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.stepmania.StepManiaConversionService
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.audio.stepmania.StepManiaRequest
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.glyph.data.GlyphGhostRecorder
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongRepository
import tf.monochrome.android.glyph.engine.GlyphAudioTransport
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphJudgementEvent
import tf.monochrome.android.glyph.engine.GlyphTimingWindows
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphGauntlets
import tf.monochrome.android.glyph.training.GlyphLoopSegment

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
    private val conversion: StepManiaConversionService,
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

    val transport = GlyphAudioTransport(context)

    private val ghostRecorder = GlyphGhostRecorder()
    private var tickJob: Job? = null
    private var generationJob: Job? = null
    private var runStartedAtMs = 0L

    init {
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
            is GlyphEvent.Search -> _ui.value = _ui.value.copy(songQuery = event.query)
            is GlyphEvent.SelectSong -> selectSong(event.trackId)
            is GlyphEvent.SelectDifficulty ->
                _ui.value = _ui.value.copy(selectedDifficulty = event.difficulty)

            GlyphEvent.GenerateChart -> generateForSelected()
            is GlyphEvent.GenerateChartFrom -> generate(event.uri, event.displayName, null)
            GlyphEvent.CancelGeneration -> {
                generationJob?.cancel()
                _ui.value = _ui.value.copy(generation = null)
            }
            GlyphEvent.DismissError -> _ui.value = _ui.value.copy(error = null)

            GlyphEvent.StartPlay -> start(training = false)
            GlyphEvent.StartTraining -> start(training = true)
            GlyphEvent.TogglePause -> togglePause()
            GlyphEvent.Restart -> restart()
            GlyphEvent.Quit -> quit()

            is GlyphEvent.LanePressed -> pressLane(event.lane)
            is GlyphEvent.LaneReleased -> releaseLane(event.lane)

            is GlyphEvent.SetSpeed -> setSpeed(event.speed)
            is GlyphEvent.SetPitchLinked -> {
                updateModifiers { it.copy(pitchLinkedToSpeed = event.linked) }
                transport.setSpeed(_ui.value.gameplay.modifiers.speed, event.linked)
            }
            GlyphEvent.ToggleMirror -> {
                updateModifiers { it.copy(mirror = !it.mirror) }
                rebuildEngine()
            }
            GlyphEvent.ToggleShuffle -> {
                updateModifiers { it.copy(shuffle = !it.shuffle) }
                rebuildEngine()
            }
            GlyphEvent.ToggleMetronome -> updateModifiers { it.copy(metronome = !it.metronome) }
            is GlyphEvent.SetTimingWindowScale -> {
                updateModifiers { it.copy(timingWindowScale = event.scale) }
                rebuildEngine()
            }
            is GlyphEvent.SetHitboxScale ->
                updateModifiers { it.copy(hitboxScale = event.scale.coerceIn(0.75f, 1.75f)) }

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

    private fun selectSong(trackId: String) = viewModelScope.launch {
        val song = _ui.value.songs.firstOrNull { it.trackId == trackId } ?: return@launch
        val simfile = songs.simfile(trackId)
        _ui.value = _ui.value.copy(
            selectedSong = song,
            simfile = simfile,
            // Default to the middle of what exists rather than the hardest: the
            // player who wants Challenge will pick it, and the one who does not
            // should not be dropped into it.
            selectedDifficulty = simfile?.availableDifficulties?.let { available ->
                available.getOrNull(available.size / 2) ?: available.firstOrNull()
            },
            training = _ui.value.training.copy(
                hasGhost = attempts.latestGhost(songs.chartId(trackId)) != null,
            ),
        )
    }

    private fun generateForSelected() {
        val song = _ui.value.selectedSong ?: return
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }
        generate(Uri.fromFile(File(song.filePath)), File(song.filePath).name, song)
    }

    /**
     * Run the existing conversion service.
     *
     * Nothing about decoding, separation or chart generation is reimplemented
     * here — this only chooses the destination and relays progress. The service
     * reports its own stage text, including which backend actually ran, and
     * that is passed through unchanged.
     */
    private fun generate(source: Uri, displayName: String, song: GlyphSong?) {
        generationJob?.cancel()
        val trackId = song?.trackId ?: displayName
        val destination = songs.simfileFile(trackId)

        generationJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(
                generation = GlyphGenerationState(trackId, 0f, "Preparing"),
                error = null,
            )

            val request = StepManiaRequest(
                title = song?.title ?: displayName.substringBeforeLast('.'),
                artist = song?.artist.orEmpty(),
                musicFileName = displayName,
                difficulties = StepManiaDifficulty.entries.toSet(),
            )

            val result = conversion.convert(
                source = source,
                destination = Uri.fromFile(destination),
                request = request,
            ) { progress ->
                _ui.value = _ui.value.copy(
                    generation = GlyphGenerationState(
                        trackId = trackId,
                        fraction = progress.fraction,
                        stage = progress.stage,
                    ),
                )
            }

            result.fold(
                onSuccess = { success ->
                    _ui.value = _ui.value.copy(
                        generation = GlyphGenerationState(
                            trackId = trackId,
                            fraction = 1f,
                            stage = "Complete",
                            backendName = success.backendName,
                        ),
                    )
                    if (song != null) selectSong(song.trackId).join()
                },
                onFailure = { failure ->
                    Log.w(TAG, "chart generation failed: ${failure.message}")
                    _ui.value = _ui.value.copy(
                        generation = GlyphGenerationState(
                            trackId = trackId,
                            fraction = 0f,
                            stage = "Failed",
                            failure = failure.message ?: "Chart generation failed.",
                        ),
                    )
                },
            )
        }
    }

    // ── running a chart ─────────────────────────────────────────────────

    private fun start(training: Boolean) {
        val song = _ui.value.selectedSong ?: return
        val chart = _ui.value.chart ?: return
        if (song.filePath.isBlank()) {
            _ui.value = _ui.value.copy(error = "That song's file could not be located.")
            return
        }

        ghostRecorder.clear()
        engine = buildEngine(chart)
        runStartedAtMs = System.currentTimeMillis()

        transport.prepare(Uri.fromFile(File(song.filePath)))
        transport.setSpeed(
            _ui.value.gameplay.modifiers.speed,
            _ui.value.gameplay.modifiers.pitchLinkedToSpeed,
        )
        transport.loop = if (training) _ui.value.training.loop else null
        transport.seekTo(transport.loop?.startSeconds ?: 0f)
        transport.play()

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
            ),
            results = null,
        )
        startTick()
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
        val transformed = when {
            modifiers.shuffle -> chart.copy(notes = shuffleLanes(chart))
            modifiers.mirror -> chart.copy(
                notes = chart.notes.map { it.copy(lane = GlyphLane.mirrorOf(it.lane)) },
            )
            else -> chart
        }
        return GlyphGameplayEngine(
            chart = transformed,
            windows = GlyphTimingWindows.STANDARD.scaled(modifiers.timingWindowScale),
        )
    }

    /**
     * Shuffle: one lane permutation for the whole chart, seeded by the chart.
     *
     * Per-note randomness would produce patterns no hand can play. A single
     * permutation keeps every jump and stream shaped as written while changing
     * which fingers play it, and seeding it by the chart means the same song
     * shuffles the same way twice — so a player can actually practise it.
     */
    private fun shuffleLanes(chart: GlyphChart): List<GlyphNote> {
        val order = GlyphLane.entries.toMutableList()
        val random = java.util.Random(chart.chartName.hashCode().toLong())
        for (index in order.indices.reversed()) {
            val swap = random.nextInt(index + 1)
            order[index] = order[swap].also { order[swap] = order[index] }
        }
        return chart.notes.map { it.copy(lane = order[it.lane.ordinal]) }
    }

    /** Rebuild mid-run when a modifier changes the chart or the windows. */
    private fun rebuildEngine() {
        val chart = _ui.value.chart ?: return
        if (engine == null) return
        engine = buildEngine(chart)
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
                if (running != null) {
                    running.advanceTo(position)
                    // Drained exactly once. Draining again inside publish would
                    // find an empty list and the judgement wordmark would never
                    // appear, so the events are passed along instead.
                    val judged = running.drainEvents()
                    for (event in judged) {
                        ghostRecorder.record(
                            songSeconds = event.songSeconds,
                            lane = event.lane.ordinal,
                            offsetSeconds = event.offsetSeconds,
                            judgement = event.judgement,
                        )
                    }
                    publish(position, running, judged)
                    if (running.isFinished && transport.loop == null) {
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

        _ui.value = _ui.value.copy(
            gameplay = previous.copy(
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

    private fun pressLane(lane: GlyphLane) {
        val running = engine ?: return
        running.press(lane, transport.positionNow())
    }

    private fun releaseLane(lane: GlyphLane) {
        val running = engine ?: return
        running.release(lane, transport.positionNow())
    }

    private fun togglePause() {
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
        engine = buildEngine(chart)
        runStartedAtMs = System.currentTimeMillis()
        transport.seekTo(transport.loop?.startSeconds ?: 0f)
        transport.play()
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(
                isPaused = false,
                isPlaying = true,
                isFinished = false,
                score = engine!!.scoreSnapshot,
                lastJudgement = null,
            ),
        )
        startTick()
    }

    private fun quit() {
        stopTick()
        transport.pause()
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.HOME,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isPaused = false),
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

    private fun updateModifiers(block: (GlyphModifiers) -> GlyphModifiers) {
        _ui.value = _ui.value.copy(
            gameplay = _ui.value.gameplay.copy(modifiers = block(_ui.value.gameplay.modifiers)),
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
        val ghost = if (enabled && chartId != null) attempts.latestGhost(chartId) else null
        _ui.value = _ui.value.copy(
            training = _ui.value.training.copy(
                ghostEnabled = enabled && ghost != null,
                hasGhost = ghost != null,
            ),
        )
    }

    private fun startGauntlet(id: String) {
        val gauntlet = GlyphGauntlets.byId(id) ?: return
        _ui.value = _ui.value.copy(
            screen = GlyphScreen.TRAINING,
            training = _ui.value.training.copy(gauntlet = gauntlet),
        )
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
        val song = _ui.value.selectedSong ?: return@launch
        val difficulty = _ui.value.selectedDifficulty ?: return@launch
        val snapshot = running.scoreSnapshot
        val modifiers = _ui.value.gameplay.modifiers
        val loop = transport.loop

        val previousBest = attempts.bestAttempt(song.chartId)
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
        )
        attempts.save(attempt)

        _ui.value = _ui.value.copy(
            screen = GlyphScreen.RESULTS,
            gameplay = _ui.value.gameplay.copy(isPlaying = false, isFinished = true),
            results = GlyphResultsUi(
                attempt = attempt,
                previousBest = previousBest,
                sections = buildSections(running),
            ),
            training = _ui.value.training.copy(hasGhost = true),
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
        stopTick()
        transport.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "GlyphViewModel"

        /**
         * The tick is not the frame clock. It polls the audio position often
         * enough that a miss is registered within a frame or two at 60 Hz,
         * while leaving rendering free to run at whatever rate the display
         * offers. Eight milliseconds is comfortably under the tightest
         * judgement window.
         */
        const val TICK_MILLIS = 8L

        const val TARGET_SECTIONS = 16
        const val MIN_SECTION_SECONDS = 4f
    }
}
