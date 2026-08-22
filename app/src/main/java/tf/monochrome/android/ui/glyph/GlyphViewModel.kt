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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.stepmania.StepManiaConversionService
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.audio.stepmania.StepManiaRequest
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.data.GlyphGhostRecorder
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongRepository
import tf.monochrome.android.glyph.engine.GlyphAudioTransport
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphJudgementEvent
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTimingWindows
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphGauntletFinder
import tf.monochrome.android.glyph.training.GlyphGauntlets
import tf.monochrome.android.glyph.training.GlyphMetronome
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

    private var tickJob: Job? = null
    private var waveformJob: Job? = null
    private var selectionJob: Job? = null
    private var lastPublishNanos = 0L
    private var countInEndsAtSeconds: Float? = null
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
            is GlyphEvent.GenerateChartFrom -> generate(event.uri, displayNameOf(event.uri), null)
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
            is GlyphEvent.SetScrollFamily -> setScrollFamily(event.family)
            is GlyphEvent.SetScrollValue -> setScrollValue(event.value)
            is GlyphEvent.SetTimingWindowScale -> {
                updateModifiers { it.copy(timingWindowScale = event.scale) }
                rebuildEngine()
            }
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
            )
            loadWaveform(refreshed)
        }
    }

    /**
     * Decode a coarse envelope for the segment picker.
     *
     * Deliberately coarse and deliberately capped: the picker exists so a
     * passage can be found by eye, and two hundred bars does that as well as
     * ten thousand. The decode is the expensive part, so it runs once per song
     * and is cancelled if the player moves on before it finishes.
     */
    private fun loadWaveform(song: GlyphSong) {
        waveformJob?.cancel()
        if (song.filePath.isBlank()) return

        waveformJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(
                training = _ui.value.training.copy(isWaveformLoading = true, waveform = emptyList()),
            )
            val bars = withContext(Dispatchers.Default) {
                runCatching {
                    val decoded = PcmDecoder.decode(
                        context = context,
                        uri = Uri.fromFile(File(song.filePath)),
                        maxFrames = WAVEFORM_MAX_FRAMES,
                    )
                    val buffer = SampleEdits.Buffer(decoded.left, decoded.right, decoded.sampleRate)
                    // peaks() alternates min and max per bucket; the picker
                    // draws a symmetric bar, so the two are folded into one
                    // magnitude here rather than at draw time.
                    val peaks = SampleEdits.peaks(buffer, WAVEFORM_BARS)
                    List(peaks.size / 2) { index ->
                        val low = peaks[index * 2]
                        val high = peaks[index * 2 + 1]
                        maxOf(kotlin.math.abs(low), kotlin.math.abs(high)).coerceIn(0f, 1f)
                    }
                }.onFailure {
                    Log.w(TAG, "waveform for ${song.title} failed: ${it.message}")
                }.getOrDefault(emptyList())
            }
            _ui.value = _ui.value.copy(
                training = _ui.value.training.copy(waveform = bars, isWaveformLoading = false),
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
                    // Re-read from disk rather than waiting for the library
                    // flow, which never fires for a file written into app storage.
                    if (song != null) loadSelection(song)
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
        laneFlashes.clear()
        lastPublishNanos = 0L
        comboBurstNanos = 0L
        lastComboMilestone = 0
        engine = buildEngine(chart)
        runStartedAtMs = System.currentTimeMillis()

        transport.prepare(Uri.fromFile(File(song.filePath)))
        transport.setSpeed(
            _ui.value.gameplay.modifiers.speed,
            _ui.value.gameplay.modifiers.pitchLinkedToSpeed,
        )
        transport.loop = if (training) _ui.value.training.loop else null
        val from = transport.loop?.startSeconds ?: 0f
        transport.seekTo(from)
        armCountIn(training, from)
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
                    running.reset()
                    metronome.reset()
                    ghostRecorder.clear()
                    laneFlashes.clear()
                    lastPublishNanos = 0L
                    comboBurstNanos = 0L
                    lastComboMilestone = 0
                }

                tickMetronome(position)

                if (running != null) {
                    running.advanceTo(position)
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
                    }

                    val combo = running.scoreSnapshot.combo
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
        laneFlashes.clear()
        comboBurstNanos = 0L
        lastComboMilestone = 0
        engine = buildEngine(chart)
        runStartedAtMs = System.currentTimeMillis()
        val from = transport.loop?.startSeconds ?: 0f
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

        val next = when (family) {
            GlyphScrollFamily.C ->
                GlyphScrollMode.CMod(nearest(GlyphScrollMode.C_STEPS, effectiveBpm))
            GlyphScrollFamily.M ->
                GlyphScrollMode.MMod(nearest(GlyphScrollMode.M_STEPS, effectiveBpm), maxBpm)
            GlyphScrollFamily.X -> GlyphScrollMode.XMod(
                nearest(
                    GlyphScrollMode.X_STEPS,
                    if (referenceBpm > 0f) effectiveBpm / referenceBpm else 1f,
                ),
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

    private fun nearest(steps: List<Float>, value: Float): Float =
        steps.minByOrNull { kotlin.math.abs(it - value) } ?: value

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
        metronome.release()
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

        /** Ten minutes at 48 kHz — the same guard the conversion service uses. */
        const val WAVEFORM_MAX_FRAMES = 48_000 * 60 * 10
    }
}
