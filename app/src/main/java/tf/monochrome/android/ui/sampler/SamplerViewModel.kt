// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.CaptureEligibilityResolver
import tf.monochrome.android.audio.sampler.CleanCaptureTap
import tf.monochrome.android.audio.sampler.ProcessedCaptureTap
import tf.monochrome.android.audio.sampler.SampleCaptureEngine
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.SamplePreviewPlayer
import tf.monochrome.android.audio.sampler.stems.BackendKind
import tf.monochrome.android.audio.sampler.stems.BackendStatus
import tf.monochrome.android.audio.sampler.stems.InstallProgress
import tf.monochrome.android.audio.sampler.stems.InstallResult
import tf.monochrome.android.audio.sampler.stems.Stem
import tf.monochrome.android.audio.sampler.stems.StemBackendRegistry
import tf.monochrome.android.audio.sampler.stems.StemModel
import tf.monochrome.android.audio.sampler.stems.StemModelManager
import tf.monochrome.android.audio.sampler.stems.StemProgress
import tf.monochrome.android.audio.sampler.stems.StemQuality
import tf.monochrome.android.audio.sampler.stems.StemSeparator
import tf.monochrome.android.data.samples.SampleRepository
import tf.monochrome.android.domain.patterns.CaptureSource
import tf.monochrome.android.domain.patterns.SampleCategory
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.player.PlaybackPositionSource
import tf.monochrome.android.player.QueueManager
import javax.inject.Inject

/**
 * State and intent for the Sampler screen.
 *
 * Capture is a three-stage thing and the state machine reflects that: idle →
 * recording → editing. Editing holds the whole captured buffer in memory,
 * which is deliberate and bounded — [SampleCaptureEngine] caps a capture at
 * twenty seconds by default, so the working buffer is a few megabytes and the
 * edits can all be non-destructive previews over one array.
 *
 * The undo model is a stack of buffers rather than an inverse of each edit.
 * At these sizes the copies are cheap, and "undo" that has to reason about
 * how to un-normalize a buffer is a source of bugs for no benefit.
 */
@HiltViewModel
class SamplerViewModel @Inject constructor(
    private val capture: SampleCaptureEngine,
    private val samples: SampleRepository,
    private val cleanTap: CleanCaptureTap,
    private val processedTap: ProcessedCaptureTap,
    private val queueManager: QueueManager,
    private val eligibilityResolver: CaptureEligibilityResolver,
    private val positions: PlaybackPositionSource,
    private val preview: SamplePreviewPlayer,
    private val separator: StemSeparator,
    private val backends: StemBackendRegistry,
    private val models: StemModelManager,
) : ViewModel() {

    enum class Stage { IDLE, RECORDING, EDITING }

    /**
     * The Stem Studio's state.
     *
     * [results] holds the separated audio in memory rather than on disk: a
     * stem is not a sample until the user says so, and writing four files for
     * a split they might discard would leave rubbish behind on every attempt.
     */
    data class StemsState(
        val running: Boolean = false,
        val progress: StemProgress = StemProgress(0f, ""),
        val quality: StemQuality = StemQuality.BALANCED,
        val results: Map<Stem, SampleEdits.Buffer> = emptyMap(),
        val levels: Map<Stem, Float> = emptyMap(),
    ) {
        val hasResults: Boolean get() = results.isNotEmpty()
    }

    /**
     * What is separating audio, and what could be.
     *
     * [activeBackend] is the one actually running rather than the best one that
     * exists, because the brief is explicit that a fallback must be visible: a
     * job on the CPU takes many times longer than one on the NPU, and someone
     * watching a progress bar crawl is owed the reason.
     */
    data class AiState(
        val backends: List<BackendStatus> = emptyList(),
        val activeBackend: BackendKind? = null,
        val degraded: Boolean = false,
        val installed: StemModel? = null,
        /** Offered for download. Null when nothing runs on this device. */
        val available: StemModel? = null,
        /** A newer version of what is installed. */
        val updateAvailable: StemModel? = null,
        /** Why the best model in the catalogue will not run here. */
        val blockedReason: String? = null,
        val installing: Boolean = false,
        val progress: InstallProgress? = null,
        val checking: Boolean = false,
        val storageBytes: Long = 0L,
        val message: String? = null,
    )

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val source: CaptureSource = CaptureSource.CLEAN,
        val buffer: SampleEdits.Buffer? = null,
        val peaks: FloatArray = FloatArray(0),
        /** Window and selection, in frames. See [WaveView]. */
        val view: WaveView = WaveView(),
        val gainDb: Float = 0f,
        val snapToZero: Boolean = true,
        val loopPreview: Boolean = false,
        /** Set while an existing library sample is being re-edited. */
        val editingSampleId: Long? = null,
        val name: String = "",
        val category: SampleCategory = SampleCategory.USER,
        val saving: Boolean = false,
        val canUndo: Boolean = false,
        val stems: StemsState = StemsState(),
        val ai: AiState = AiState(),
        val message: String? = null,
    ) {
        val frames: Int get() = buffer?.frames ?: 0

        /** The selection, or the whole buffer when nothing is selected. */
        val effectiveRange: IntRange
            get() {
                val total = frames
                if (total <= 0) return IntRange.EMPTY
                return if (view.hasSelection) {
                    view.selectionStart.coerceIn(0, total) until view.selectionEnd.coerceIn(0, total)
                } else {
                    0 until total
                }
            }

        val selectedFrames: Int
            get() = (effectiveRange.last + 1 - effectiveRange.first).coerceAtLeast(0)

        val selectedDurationMs: Long
            get() {
                val rate = buffer?.sampleRate ?: return 0
                return if (rate <= 0) 0 else selectedFrames * 1000L / rate
            }

        val totalDurationMs: Long
            get() {
                val rate = buffer?.sampleRate ?: return 0
                return if (rate <= 0) 0 else frames * 1000L / rate
            }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val captureState: StateFlow<SampleCaptureEngine.CaptureState> = capture.state
    val eligibility: StateFlow<SampleCaptureEngine.Eligibility> = capture.eligibility

    /** Drives the editor's playhead and the play/stop key. */
    val previewState: StateFlow<SamplePreviewPlayer.State> = preview.state

    val library: StateFlow<List<SampleRef>> = samples.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val undoStack = ArrayDeque<SampleEdits.Buffer>()
    private var meterJob: Job? = null
    private var stemJob: Job? = null
    private var installJob: Job? = null

    init {
        refreshEligibility()
        // Provenance is a property of the track, so it has to be re-derived
        // whenever the track changes — including mid-capture, where a change
        // revokes the capture rather than letting it finish.
        viewModelScope.launch {
            queueManager.currentTrack.collect { eligibilityResolver.refresh(capture) }
        }
    }

    override fun onCleared() {
        capture.cancel()
        preview.stop()
        meterJob?.cancel()
        stemJob?.cancel()
        super.onCleared()
    }

    /**
     * Re-reads whether the current item may be captured.
     *
     * Called on entry and whenever the track changes. The gate defaults closed,
     * so a track whose provenance cannot be established is not capturable —
     * the failure mode of the check is "you cannot save this", never "we
     * assumed you could".
     */
    fun refreshEligibility() {
        viewModelScope.launch { eligibilityResolver.refresh(capture) }
    }

    fun setSource(source: CaptureSource) {
        if (_ui.value.stage == Stage.RECORDING) return
        _ui.value = _ui.value.copy(source = source)
    }

    // ── capture ─────────────────────────────────────────────────────────

    fun startRecording() {
        refreshEligibility()
        if (!eligibility.value.allowed) {
            _ui.value = _ui.value.copy(message = eligibility.value.reason)
            return
        }
        val source = _ui.value.source
        val rate = when (source) {
            CaptureSource.CLEAN -> cleanTap.observedSampleRate
            CaptureSource.PROCESSED -> processedTap.observedSampleRate
        }
        if (!capture.arm(source, rate)) {
            _ui.value = _ui.value.copy(message = "Couldn't start capture")
            return
        }
        _ui.value = _ui.value.copy(stage = Stage.RECORDING, message = null)

        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            while (isActive && capture.isArmed) {
                capture.publishProgress()
                delay(METER_INTERVAL_MS)
            }
            // The engine stops itself when the buffer fills; without this the
            // screen would sit on RECORDING with nothing recording.
            capture.publishProgress()
            if (_ui.value.stage == Stage.RECORDING) stopRecording()
        }
    }

    fun stopRecording() {
        capture.stop()
        meterJob?.cancel()
        meterJob = null
        val captured = capture.finish()
        if (captured == null) {
            _ui.value = _ui.value.copy(
                stage = Stage.IDLE,
                message = "Nothing was captured",
            )
            return
        }

        val buffer = SampleEdits.Buffer(captured.left, captured.right, captured.sampleRate)
        undoStack.clear()
        // The start handle lands on the first transient rather than at zero.
        // A capture almost always begins with a little silence, and the
        // difference between a hit that lands on the beat and one that lags is
        // exactly that silence.
        // The selection opens on the first transient rather than at zero. A
        // capture almost always begins with a little silence, and the
        // difference between a hit that lands on the beat and one that lags is
        // exactly that silence.
        val onset = SampleEdits.firstOnset(buffer)
        openEditor(buffer, name = suggestName(), selectionStart = onset)
    }

    /** Opens [buffer] in the editor, showing all of it with [selectionStart] onward selected. */
    private fun openEditor(
        buffer: SampleEdits.Buffer,
        name: String,
        selectionStart: Int = 0,
        sampleId: Long? = null,
        category: SampleCategory = SampleCategory.USER,
    ) {
        undoStack.clear()
        stemJob?.cancel()
        stemJob = null
        val view = WaveView(
            viewStart = 0,
            viewEnd = buffer.frames,
            selectionStart = selectionStart.coerceIn(0, buffer.frames),
            selectionEnd = buffer.frames,
        )
        _ui.value = _ui.value.copy(
            stage = Stage.EDITING,
            buffer = buffer,
            peaks = SampleEdits.peaksOfRange(buffer, view.viewStart, view.viewEnd, PEAK_BUCKETS),
            view = view,
            gainDb = 0f,
            name = name,
            category = category,
            editingSampleId = sampleId,
            canUndo = false,
            // Stems belong to the buffer they came from; carrying them across
            // to a different one would offer the user a split of something
            // they are no longer looking at.
            stems = StemsState(),
            message = null,
        )
    }

    /**
     * Loads a saved sample back into the editor.
     *
     * Saving from here overwrites the row rather than making a copy — see
     * [save] — so an edit to something already in the library behaves the way
     * editing anything else does.
     */
    fun editExisting(sample: SampleRef) {
        viewModelScope.launch {
            val audio = samples.loadAudio(sample)
            if (audio == null) {
                _ui.value = _ui.value.copy(message = "Couldn't open ${sample.name}")
                return@launch
            }
            preview.stop()
            openEditor(
                buffer = SampleEdits.Buffer(audio.left, audio.right, audio.sampleRate),
                name = sample.name,
                sampleId = sample.id,
                category = sample.category,
            )
        }
    }

    /** Decodes any audio file the platform can read and opens it in the editor. */
    fun importFile(uri: android.net.Uri, displayName: String?) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(message = "Reading file…")
            val decoded = samples.decode(uri)
            if (decoded == null) {
                _ui.value = _ui.value.copy(message = "Couldn't read that file")
                return@launch
            }
            preview.stop()
            openEditor(
                buffer = SampleEdits.Buffer(decoded.left, decoded.right, decoded.sampleRate),
                name = displayName?.substringBeforeLast('.') ?: "Imported sample",
            )
        }
    }

    fun cancelCapture() {
        capture.cancel()
        preview.stop()
        meterJob?.cancel()
        meterJob = null
        undoStack.clear()
        _ui.value = UiState(source = _ui.value.source)
    }

    // ── editing ─────────────────────────────────────────────────────────

    /**
     * Takes a new window / selection from the editor.
     *
     * Peaks are recomputed only when the *window* moves. A selection drag
     * emits on every frame of the gesture, and re-walking the buffer for each
     * one is the difference between a smooth drag and a stuttering one — the
     * drawing does not change, only which part of it is highlighted.
     */
    fun setView(next: WaveView) {
        val state = _ui.value
        val buffer = state.buffer ?: return
        var clamped = next.coerceInto(buffer.frames)

        if (state.snapToZero && clamped.hasSelection &&
            (clamped.selectionStart != state.view.selectionStart ||
                clamped.selectionEnd != state.view.selectionEnd)
        ) {
            // Snapping is applied to the value the user is choosing, not to the
            // audio, so it costs nothing and can be turned off without having
            // to undo anything.
            clamped = clamped.copy(
                selectionStart = SampleEdits.snapToZeroCrossing(buffer, clamped.selectionStart),
                selectionEnd = SampleEdits.snapToZeroCrossing(buffer, clamped.selectionEnd),
            )
        }

        val windowMoved = clamped.viewStart != state.view.viewStart ||
            clamped.viewEnd != state.view.viewEnd
        _ui.value = state.copy(
            view = clamped,
            peaks = if (windowMoved) {
                SampleEdits.peaksOfRange(buffer, clamped.viewStart, clamped.viewEnd, PEAK_BUCKETS)
            } else {
                state.peaks
            },
        )
    }

    fun selectAll() {
        val buffer = _ui.value.buffer ?: return
        setView(_ui.value.view.copy(selectionStart = 0, selectionEnd = buffer.frames))
    }

    fun zoomToSelection() {
        val state = _ui.value
        if (!state.view.hasSelection) return
        setView(
            state.view.copy(
                viewStart = state.view.selectionStart,
                viewEnd = state.view.selectionEnd,
            ),
        )
    }

    fun zoomOut() {
        val buffer = _ui.value.buffer ?: return
        setView(_ui.value.view.copy(viewStart = 0, viewEnd = buffer.frames))
    }

    fun setSnapToZero(enabled: Boolean) {
        _ui.value = _ui.value.copy(snapToZero = enabled)
    }

    fun setLoopPreview(enabled: Boolean) {
        _ui.value = _ui.value.copy(loopPreview = enabled)
    }

    fun setGainDb(db: Float) {
        _ui.value = _ui.value.copy(gainDb = db.coerceIn(-24f, 24f))
    }

    fun setName(name: String) {
        _ui.value = _ui.value.copy(name = name)
    }

    fun setCategory(category: SampleCategory) {
        _ui.value = _ui.value.copy(category = category)
    }

    // ── operations ──────────────────────────────────────────────────────
    //
    // Each acts on the selection, or on the whole buffer when there is none.
    // That rule is what lets the same six buttons serve "clean up this hit" and
    // "fix this one syllable" without a mode.

    fun cropToSelection() = applyRangeEdit { buffer, range ->
        SampleEdits.trim(buffer, range.first, range.last + 1)
    }

    fun deleteSelection() = applyRangeEdit { buffer, range ->
        SampleEdits.deleteRange(buffer, range.first, range.last + 1)
    }

    fun silenceSelection() = applyRangeEdit { buffer, range ->
        SampleEdits.silenceRange(buffer, range.first, range.last + 1)
    }

    fun normalize() = applyRangeEdit { buffer, range ->
        SampleEdits.normalizeRange(buffer, range.first, range.last + 1)
    }

    fun fadeIn() = applyRangeEdit { buffer, range ->
        SampleEdits.fadeInRange(buffer, range.first, range.last + 1)
    }

    fun fadeOut() = applyRangeEdit { buffer, range ->
        SampleEdits.fadeOutRange(buffer, range.first, range.last + 1)
    }

    fun reverse() = applyRangeEdit { buffer, range ->
        SampleEdits.reverseRange(buffer, range.first, range.last + 1)
    }

    fun applyGain() = applyRangeEdit { buffer, range ->
        SampleEdits.gainRange(buffer, range.first, range.last + 1, _ui.value.gainDb)
    }

    fun toMono() = applyRangeEdit { buffer, _ -> SampleEdits.toMono(buffer) }

    fun trimSilence() = applyRangeEdit { buffer, _ -> SampleEdits.trimSilence(buffer) }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        preview.stop()
        val view = _ui.value.view.coerceInto(previous.frames)
        _ui.value = _ui.value.copy(
            buffer = previous,
            view = view,
            peaks = SampleEdits.peaksOfRange(previous, view.viewStart, view.viewEnd, PEAK_BUCKETS),
            canUndo = undoStack.isNotEmpty(),
        )
    }

    /**
     * Runs an operation over the effective range and pushes the result.
     *
     * The window and selection are re-clamped afterwards because an operation
     * that shortens the buffer — crop, delete, trim silence — invalidates
     * both, and a selection left pointing past the end is how an editor ends
     * up destroying audio the user cannot see.
     */
    private fun applyRangeEdit(
        transform: (SampleEdits.Buffer, IntRange) -> SampleEdits.Buffer,
    ) {
        val state = _ui.value
        val buffer = state.buffer ?: return
        val range = state.effectiveRange
        if (range.isEmpty()) return

        val edited = transform(buffer, range)
        if (edited === buffer) return

        preview.stop()
        undoStack.addLast(buffer)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()

        val view = if (edited.frames != buffer.frames) {
            // Length changed: show the whole thing again rather than leaving
            // the user zoomed into a window that no longer means anything.
            WaveView.whole(edited.frames)
        } else {
            state.view.coerceInto(edited.frames)
        }
        _ui.value = state.copy(
            buffer = edited,
            view = view,
            peaks = SampleEdits.peaksOfRange(edited, view.viewStart, view.viewEnd, PEAK_BUCKETS),
            canUndo = true,
        )
    }

    // ── stem studio ─────────────────────────────────────────────────────

    /**
     * Splits what is in the editor into stems.
     *
     * Runs on [Dispatchers.Default] and is held as a cancellable job, because
     * separation is seconds of arithmetic and the rest of the app has to stay
     * live through it. Cancelling drops the partial result: half-separated
     * stems are not useful, and keeping them would mean explaining which ones
     * finished.
     */
    fun separateStems(quality: StemQuality = StemQuality.BALANCED) {
        val buffer = _ui.value.buffer ?: return
        if (_ui.value.stems.running) return
        preview.stop()

        stemJob?.cancel()
        _ui.value = _ui.value.copy(
            stems = StemsState(running = true, progress = StemProgress(0f, "Starting"), quality = quality),
        )
        stemJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    separator.separate(
                        input = buffer,
                        requested = separator.availableStems(buffer),
                        quality = quality,
                    ) { progress ->
                        // Hop back to the view model's scope to publish; the
                        // separator calls this from its own dispatcher.
                        _ui.value = _ui.value.copy(
                            stems = _ui.value.stems.copy(progress = progress),
                        )
                    }
                }
            }

            if (result.isFailure) {
                // Cancellation is not a failure the user needs told about;
                // they asked for it.
                if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) {
                    _ui.value = _ui.value.copy(stems = StemsState())
                    return@launch
                }
                _ui.value = _ui.value.copy(
                    stems = StemsState(),
                    message = "Stem separation failed",
                )
                return@launch
            }

            val stems = result.getOrDefault(emptyMap())
            _ui.value = _ui.value.copy(
                stems = StemsState(
                    running = false,
                    quality = quality,
                    results = stems,
                    // Everything audible to begin with; the mixer is for
                    // auditioning combinations, not for hiding things.
                    levels = stems.keys.associateWith { 1f },
                ),
                message = if (stems.isEmpty()) "Nothing to separate" else null,
            )
        }
    }

    fun cancelStems() {
        stemJob?.cancel()
        stemJob = null
        _ui.value = _ui.value.copy(stems = StemsState())
    }

    fun setStemLevel(stem: Stem, level: Float) {
        val stems = _ui.value.stems
        _ui.value = _ui.value.copy(
            stems = stems.copy(levels = stems.levels + (stem to level.coerceIn(0f, 1f))),
        )
    }

    /** Auditions one stem on its own. */
    fun playStem(stem: Stem) {
        val buffer = _ui.value.stems.results[stem] ?: return
        preview.play(buffer, 0, buffer.frames, looping = _ui.value.loopPreview)
    }

    /**
     * Plays the stems mixed at their current levels.
     *
     * Summed here rather than by playing four previews at once: four
     * AudioTracks would drift against each other, and the mixer's whole
     * purpose is hearing them locked together.
     */
    fun playStemMix() {
        val stems = _ui.value.stems
        if (stems.results.isEmpty()) return
        val first = stems.results.values.first()
        val left = FloatArray(first.frames)
        val right = if (first.right != null) FloatArray(first.frames) else null
        for ((stem, buffer) in stems.results) {
            val level = stems.levels[stem] ?: 1f
            if (level <= 0f) continue
            for (i in 0 until minOf(first.frames, buffer.frames)) {
                left[i] += buffer.left[i] * level
                right?.let { it[i] += (buffer.right?.get(i) ?: buffer.left[i]) * level }
            }
        }
        preview.play(
            SampleEdits.Buffer(left, right, first.sampleRate),
            0,
            first.frames,
            looping = _ui.value.loopPreview,
        )
    }

    /** Loads one stem into the editor, replacing what is there. */
    fun editStem(stem: Stem) {
        val buffer = _ui.value.stems.results[stem] ?: return
        val base = _ui.value.name.ifBlank { "Sample" }
        openEditor(buffer, name = "$base ${stem.label}")
    }

    /** Writes one stem to the library as a sample in its own right. */
    fun saveStem(stem: Stem, onSaved: (SampleRef) -> Unit = {}) {
        val buffer = _ui.value.stems.results[stem] ?: return
        viewModelScope.launch {
            val saved = persistStem(stem, buffer)
            if (saved == null) {
                _ui.value = _ui.value.copy(message = "Couldn't save ${stem.label}")
            } else {
                _ui.value = _ui.value.copy(message = "Saved ${saved.name}")
                onSaved(saved)
            }
        }
    }

    fun saveAllStems() {
        val stems = _ui.value.stems.results
        if (stems.isEmpty()) return
        viewModelScope.launch {
            var saved = 0
            for ((stem, buffer) in stems) {
                if (persistStem(stem, buffer) != null) saved += 1
            }
            _ui.value = _ui.value.copy(message = "Added $saved stems to the library")
        }
    }

    private suspend fun persistStem(stem: Stem, buffer: SampleEdits.Buffer): SampleRef? {
        val state = _ui.value
        val track = queueManager.currentTrack.value
        val base = state.name.ifBlank { suggestName() }
        return samples.save(
            buffer = buffer,
            name = "$base — ${stem.label}",
            // Stems file themselves under the obvious category, so a drums
            // stem lands with the drums rather than in USER with everything
            // else the user has ever made.
            category = when (stem) {
                Stem.DRUMS -> SampleCategory.PERCUSSION
                Stem.BASS -> SampleCategory.BASS
                Stem.VOCALS -> SampleCategory.VOCALS
                // Guitar and piano come out of six-stem models as whole
                // performances rather than one-shots, so they file with the
                // loops. The stem's own type is kept in `stemType` either way,
                // which is what the library actually filters on.
                Stem.GUITAR, Stem.PIANO, Stem.OTHER -> SampleCategory.LOOPS
            },
            captureSource = state.source,
            sourceTrackTitle = track?.title,
            sourceArtist = track?.displayArtist,
            sourceTimestampMs = positions.positionMs(),
            stemType = stem.id,
            sourceSampleId = state.editingSampleId,
        )
    }

    // ── the AI model ────────────────────────────────────────────────────

    /**
     * Where the catalogue lives.
     *
     * `releases/latest/download/` rather than a pinned tag, so publishing a new
     * release is the whole of shipping a model update — no app change, no
     * hard-coded version that goes stale the moment it is written.
     */
    private val manifestUrl =
        "https://github.com/tryptz/Tryptify/releases/latest/download/model-manifest.json"

    /** Reads what is on disk. Cheap — no network, so it is safe on screen open. */
    fun refreshAiState() {
        val statuses = backends.statuses()
        val active = backends.active()?.kind
        _ui.value = _ui.value.copy(
            ai = _ui.value.ai.copy(
                backends = statuses,
                activeBackend = active,
                degraded = backends.isDegraded(),
                installed = models.installed(),
                storageBytes = models.installedBytes(),
            ),
        )
    }

    /**
     * Asks the catalogue what exists.
     *
     * Separate from [refreshAiState] because this one touches the network, and
     * a screen that quietly phoned home every time it opened would be a
     * different app from the one the brief describes.
     */
    fun checkForModels() {
        if (_ui.value.ai.checking) return
        _ui.value = _ui.value.copy(ai = _ui.value.ai.copy(checking = true, message = null))

        viewModelScope.launch {
            val catalog = models.fetchCatalog(manifestUrl).getOrNull()
            val installed = models.installed()

            if (catalog == null) {
                _ui.value = _ui.value.copy(
                    ai = _ui.value.ai.copy(
                        checking = false,
                        message = "Could not reach the model catalogue",
                    ),
                )
                return@launch
            }

            val sdk = android.os.Build.VERSION.SDK_INT
            val abis = android.os.Build.SUPPORTED_ABIS.toList()
            val best = catalog.best(sdk, abis, allowNpu = true)

            // When nothing is installable, say why rather than showing an empty
            // panel. The first blocked model's reason is the useful one: an
            // empty catalogue has nothing to explain, a blocked one does.
            val blocked = if (best == null) {
                catalog.models.firstNotNullOfOrNull { it.blockedReason(sdk, abis) }
            } else {
                null
            }

            val update = best?.takeIf { installed != null && catalog.isUpdate(installed, it) }

            _ui.value = _ui.value.copy(
                ai = _ui.value.ai.copy(
                    checking = false,
                    installed = installed,
                    available = if (installed == null) best else null,
                    updateAvailable = update,
                    blockedReason = blocked,
                    message = when {
                        best == null && blocked == null -> "No AI model published yet"
                        installed != null && update == null -> "Up to date"
                        else -> null
                    },
                ),
            )
        }
    }

    fun installModel() {
        val ai = _ui.value.ai
        val model = ai.updateAvailable ?: ai.available ?: return
        if (ai.installing) return

        installJob?.cancel()
        _ui.value = _ui.value.copy(
            ai = ai.copy(installing = true, progress = null, message = null),
        )

        installJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                models.install(model) { progress ->
                    _ui.value = _ui.value.copy(
                        ai = _ui.value.ai.copy(progress = progress),
                    )
                }
            }

            _ui.value = _ui.value.copy(
                ai = _ui.value.ai.copy(
                    installing = false,
                    progress = null,
                    installed = models.installed(),
                    available = null,
                    updateAvailable = null,
                    storageBytes = models.installedBytes(),
                    message = when (result) {
                        is InstallResult.Installed -> null
                        is InstallResult.Failed -> result.reason
                    },
                ),
            )
            refreshAiState()
        }
    }

    /**
     * Stops the download but keeps what arrived.
     *
     * The manager treats a cancelled install exactly like a dropped connection,
     * which is what makes resuming free rather than starting again.
     */
    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        _ui.value = _ui.value.copy(
            ai = _ui.value.ai.copy(
                installing = false,
                progress = null,
                message = "Paused — resuming will pick up where it stopped",
            ),
        )
    }

    fun deleteModel() {
        installJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { models.uninstall() }
            _ui.value = _ui.value.copy(
                ai = _ui.value.ai.copy(
                    installed = null,
                    updateAvailable = null,
                    storageBytes = 0L,
                    message = "Model deleted",
                ),
            )
            refreshAiState()
        }
    }

    val separatorDescription: String get() = separator.description

    // ── audition ────────────────────────────────────────────────────────

    /** Plays the selection, or the whole buffer when nothing is selected. */
    fun playSelection() {
        val state = _ui.value
        val buffer = state.buffer ?: return
        val range = state.effectiveRange
        if (range.isEmpty()) return
        preview.play(buffer, range.first, range.last + 1, looping = state.loopPreview)
    }

    fun stopPreview() = preview.stop()

    // ── saving ──────────────────────────────────────────────────────────

    /**
     * Writes what is on screen to the library.
     *
     * The selection is what gets saved, so "select the bit you want, hit save"
     * is the whole flow and cropping first is optional. The pending gain is
     * folded in here rather than as the fader moves — re-running an edit chain
     * over a multi-megabyte array on every frame of a drag is visible as lag,
     * and the fader's number already says what it will do.
     *
     * When [UiState.editingSampleId] is set the existing row is updated in
     * place instead of a second copy appearing in the library, which is what
     * anyone re-opening a sample to fix it expects.
     */
    fun save(onSaved: (SampleRef) -> Unit = {}) {
        val state = _ui.value
        val buffer = state.buffer ?: return
        if (state.saving) return
        val range = state.effectiveRange
        if (range.isEmpty()) return
        _ui.value = state.copy(saving = true)
        preview.stop()

        viewModelScope.launch {
            var out = SampleEdits.trim(buffer, range.first, range.last + 1)
            out = SampleEdits.gainDb(out, state.gainDb)
            // A short fade on both ends unconditionally. A cut that lands
            // mid-cycle starts on a step discontinuity, and a grid of those at
            // sixteenth notes is the click-per-step that makes a sampler sound
            // broken. Two milliseconds is inaudible as a fade and removes it
            // completely — and it costs nothing when the edges were already
            // snapped to zero crossings.
            out = SampleEdits.fadeIn(out, EDGE_FADE_MS)
            out = SampleEdits.fadeOut(out, EDGE_FADE_MS)

            val track = queueManager.currentTrack.value
            val saved = samples.save(
                buffer = out,
                name = state.name.ifBlank { suggestName() },
                category = state.category,
                captureSource = state.source,
                sourceTrackTitle = track?.title,
                sourceArtist = track?.displayArtist,
                sourceTimestampMs = positions.positionMs(),
                replacingId = state.editingSampleId,
            )

            if (saved == null) {
                _ui.value = _ui.value.copy(saving = false, message = "Couldn't save sample")
            } else {
                undoStack.clear()
                _ui.value = UiState(source = state.source, message = "Saved ${saved.name}")
                onSaved(saved)
            }
        }
    }

    fun deleteSample(sample: SampleRef) {
        viewModelScope.launch { samples.delete(sample.id) }
    }

    fun renameSample(sample: SampleRef, name: String) {
        viewModelScope.launch { samples.rename(sample.id, name) }
    }

    fun recategorize(sample: SampleRef, category: SampleCategory) {
        viewModelScope.launch { samples.setCategory(sample.id, category) }
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    private fun suggestName(): String {
        val track = queueManager.currentTrack.value?.title
        val stamp = (positions.positionMs() / 1000).toInt()
        return if (track.isNullOrBlank()) {
            "Sample ${System.currentTimeMillis() % 100000}"
        } else {
            "${track.take(24)} @${stamp}s"
        }
    }

    companion object {
        private const val METER_INTERVAL_MS = 50L
        private const val EDGE_FADE_MS = 2f
        private const val MAX_UNDO = 12

        /**
         * Peak pairs drawn across the editor's width. Roughly two per pixel on
         * a phone, which is enough that the wave reads as a shape rather than
         * a comb, and few enough that recomputing on every zoom is free.
         */
        private const val PEAK_BUCKETS = 900
    }
}
