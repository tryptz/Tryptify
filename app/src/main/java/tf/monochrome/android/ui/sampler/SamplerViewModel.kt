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
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.sampler.CaptureEligibilityResolver
import tf.monochrome.android.audio.sampler.CleanCaptureTap
import tf.monochrome.android.audio.sampler.ProcessedCaptureTap
import tf.monochrome.android.audio.sampler.SampleCaptureEngine
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.SamplePreviewPlayer
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
) : ViewModel() {

    enum class Stage { IDLE, RECORDING, EDITING }

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
