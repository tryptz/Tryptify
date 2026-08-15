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
import tf.monochrome.android.data.samples.SampleRepository
import tf.monochrome.android.domain.patterns.CaptureSource
import tf.monochrome.android.domain.patterns.SampleCategory
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.player.PlaybackPositionSource
import tf.monochrome.android.player.QueueManager
import javax.inject.Inject
import kotlin.math.roundToInt

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
) : ViewModel() {

    enum class Stage { IDLE, RECORDING, EDITING }

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val source: CaptureSource = CaptureSource.CLEAN,
        val buffer: SampleEdits.Buffer? = null,
        val peaks: FloatArray = FloatArray(0),
        val startFraction: Float = 0f,
        val endFraction: Float = 1f,
        val gainDb: Float = 0f,
        val name: String = "",
        val category: SampleCategory = SampleCategory.USER,
        val saving: Boolean = false,
        val canUndo: Boolean = false,
        val message: String? = null,
    ) {
        val trimmedFrames: Int
            get() {
                val total = buffer?.frames ?: return 0
                return ((endFraction - startFraction) * total).roundToInt().coerceAtLeast(0)
            }

        val trimmedDurationMs: Long
            get() {
                val rate = buffer?.sampleRate ?: return 0
                return if (rate <= 0) 0 else trimmedFrames * 1000L / rate
            }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val captureState: StateFlow<SampleCaptureEngine.CaptureState> = capture.state
    val eligibility: StateFlow<SampleCaptureEngine.Eligibility> = capture.eligibility

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
        val onset = SampleEdits.firstOnset(buffer)
        _ui.value = _ui.value.copy(
            stage = Stage.EDITING,
            buffer = buffer,
            peaks = SampleEdits.peaks(buffer),
            startFraction = (onset.toFloat() / buffer.frames.coerceAtLeast(1)).coerceIn(0f, 0.9f),
            endFraction = 1f,
            gainDb = 0f,
            name = suggestName(),
            category = SampleCategory.USER,
            message = null,
        )
    }

    fun cancelCapture() {
        capture.cancel()
        meterJob?.cancel()
        meterJob = null
        undoStack.clear()
        _ui.value = UiState(source = _ui.value.source)
    }

    // ── editing ─────────────────────────────────────────────────────────

    fun setTrim(start: Float, end: Float) {
        _ui.value = _ui.value.copy(
            startFraction = start.coerceIn(0f, 1f),
            endFraction = end.coerceIn(0f, 1f),
        )
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

    fun normalize() = applyEdit { SampleEdits.normalize(it) }

    fun fadeIn() = applyEdit { SampleEdits.fadeIn(it, DEFAULT_FADE_MS) }

    fun fadeOut() = applyEdit { SampleEdits.fadeOut(it, DEFAULT_FADE_MS) }

    fun reverse() = applyEdit { SampleEdits.reverse(it) }

    fun toMono() = applyEdit { SampleEdits.toMono(it) }

    /** Cuts the buffer down to the current selection and resets the handles. */
    fun applyTrim() {
        val state = _ui.value
        val buffer = state.buffer ?: return
        val start = (state.startFraction * buffer.frames).roundToInt()
        val end = (state.endFraction * buffer.frames).roundToInt()
        val trimmed = SampleEdits.trim(buffer, start, end)
        if (trimmed === buffer) return
        undoStack.addLast(buffer)
        _ui.value = state.copy(
            buffer = trimmed,
            peaks = SampleEdits.peaks(trimmed),
            startFraction = 0f,
            endFraction = 1f,
            canUndo = true,
        )
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        _ui.value = _ui.value.copy(
            buffer = previous,
            peaks = SampleEdits.peaks(previous),
            startFraction = 0f,
            endFraction = 1f,
            canUndo = undoStack.isNotEmpty(),
        )
    }

    private fun applyEdit(transform: (SampleEdits.Buffer) -> SampleEdits.Buffer) {
        val buffer = _ui.value.buffer ?: return
        val edited = transform(buffer)
        if (edited === buffer) return
        undoStack.addLast(buffer)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        _ui.value = _ui.value.copy(
            buffer = edited,
            peaks = SampleEdits.peaks(edited),
            canUndo = true,
        )
    }

    // ── saving ──────────────────────────────────────────────────────────

    /**
     * Writes the sample to the library.
     *
     * The trim and the gain are applied here rather than as the user moves the
     * handles: an edit chain over a five-megabyte array on every frame of a
     * drag would be visible as lag, and the preview does not need it — the
     * waveform already shows the selection, and the level is a number.
     */
    fun save(onSaved: (SampleRef) -> Unit = {}) {
        val state = _ui.value
        val buffer = state.buffer ?: return
        if (state.saving) return
        _ui.value = state.copy(saving = true)

        viewModelScope.launch {
            val start = (state.startFraction * buffer.frames).roundToInt()
            val end = (state.endFraction * buffer.frames).roundToInt()
            var out = SampleEdits.trim(buffer, start, end)
            out = SampleEdits.gainDb(out, state.gainDb)
            // A short fade on both ends unconditionally. A trim that lands
            // mid-cycle starts on a step discontinuity, and a grid of those at
            // sixteenth notes is the click-per-step that makes a sampler sound
            // broken. Two milliseconds is inaudible as a fade and completely
            // removes it.
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
        private const val DEFAULT_FADE_MS = 15f
        private const val EDGE_FADE_MS = 2f
        private const val MAX_UNDO = 12
    }
}
