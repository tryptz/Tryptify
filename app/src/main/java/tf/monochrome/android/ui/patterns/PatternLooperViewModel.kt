// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.sampler.NativeSamplerBridge
import tf.monochrome.android.audio.sampler.SamplePlaybackEngine
import tf.monochrome.android.data.samples.SampleRepository
import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternChannel
import tf.monochrome.android.domain.patterns.PatternLimits
import tf.monochrome.android.domain.patterns.PatternOps
import tf.monochrome.android.domain.patterns.PatternRepository
import tf.monochrome.android.domain.patterns.PatternSummary
import tf.monochrome.android.domain.patterns.PatternSwitchMode
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.domain.patterns.SpeedPitchPreset
import tf.monochrome.android.domain.patterns.Step
import tf.monochrome.android.domain.patterns.StretchQuality
import tf.monochrome.android.domain.patterns.TransportState
import javax.inject.Inject

/**
 * State and intent for the Patterns screen.
 *
 * The division of labour that matters here: **the engine is authoritative for
 * time, this class is authoritative for content.** The playhead, the active
 * pattern and the record edits all come *from* the audio thread; the pattern
 * being edited lives here and is pushed *to* it. Nothing in this class is ever
 * on the path of a sounding step — a trig toggle reaches the engine over the
 * lock-free queue on the calling thread, and only then goes to Room in the
 * background.
 *
 * That is also why an edit is applied to the in-memory copy first: the UI must
 * repaint on the same frame as the tap, not after a database write comes back.
 */
@HiltViewModel
class PatternLooperViewModel @Inject constructor(
    private val engine: SamplePlaybackEngine,
    private val patterns: PatternRepository,
    private val samples: SampleRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val pattern: Pattern? = null,
        val bank: List<PatternSummary> = emptyList(),
        val selectedChannel: Int = 0,
        /** Which 16-step page of a long pattern the grid is showing. */
        val page: Int = 0,
        val editingChannel: Int? = null,
        val pendingCopy: Pattern? = null,
        val stretchQuality: StretchQuality = StretchQuality.BALANCED,
    ) {
        val pageCount: Int
            get() {
                val length = pattern?.lengthSteps ?: 16
                return ((length + STEPS_PER_PAGE - 1) / STEPS_PER_PAGE).coerceAtLeast(1)
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val transport: StateFlow<TransportState> = engine.transport
    val channelTriggers: StateFlow<IntArray> = engine.channelTriggers
    val engineAvailable: StateFlow<Boolean> = engine.available

    val library: StateFlow<List<SampleRef>> = samples.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var persistJob: Job? = null

    init {
        engine.acquire()

        viewModelScope.launch {
            val seeded = patterns.seedIfEmpty()
            val first = seeded.firstOrNull()
            _ui.value = _ui.value.copy(loading = false, pattern = first)
            engine.publishAll(seeded)
            first?.let { engine.queuePattern(it.bankSlot, immediate = true) }
        }

        patterns.observeBank()
            .onEach { bank -> _ui.value = _ui.value.copy(bank = bank) }
            .launchIn(viewModelScope)

        // A hit recorded live is written by the audio thread straight into the
        // engine's copy of the pattern. Room and this class do not see it, so
        // the counter is the signal to read the engine's decision back — the
        // alternative is polling the pattern every frame for something that
        // happens a few times a bar.
        engine.recordEdits
            .onEach { if (it > 0) refreshFromEngineRecording() }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }

    // ── selection ───────────────────────────────────────────────────────

    fun selectChannel(index: Int) {
        _ui.value = _ui.value.copy(selectedChannel = index)
    }

    fun selectPage(page: Int) {
        _ui.value = _ui.value.copy(page = page.coerceIn(0, _ui.value.pageCount - 1))
    }

    fun openChannelEditor(index: Int?) {
        _ui.value = _ui.value.copy(editingChannel = index)
    }

    // ── steps ───────────────────────────────────────────────────────────

    /**
     * Toggles a trig.
     *
     * Three writes, in the order that matters: the in-memory pattern so the
     * button repaints this frame, the engine so the change is audible on the
     * very next pass of the loop, and Room last because nothing is waiting on
     * it.
     */
    fun toggleStep(channelIndex: Int, stepIndex: Int) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.toggleStep(pattern, channelIndex, stepIndex)
        val step = updated.channelAt(channelIndex)?.step(stepIndex) ?: return
        _ui.value = _ui.value.copy(pattern = updated)
        engine.writeStep(pattern.bankSlot, channelIndex, stepIndex, step)
        persistStep(updated, channelIndex, stepIndex, step)
    }

    /** Sets a whole run at once — what dragging across the grid produces. */
    fun paintSteps(channelIndex: Int, stepIndices: Iterable<Int>, enabled: Boolean) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.setSteps(pattern, channelIndex, stepIndices, enabled)
        _ui.value = _ui.value.copy(pattern = updated)
        val channel = updated.channelAt(channelIndex) ?: return
        for (index in stepIndices) {
            engine.writeStep(pattern.bankSlot, channelIndex, index, channel.step(index))
        }
        savePattern(updated)
    }

    fun setStepVelocity(channelIndex: Int, stepIndex: Int, velocity: Int) {
        val pattern = _ui.value.pattern ?: return
        val channel = pattern.channelAt(channelIndex) ?: return
        val step = channel.step(stepIndex).copy(
            enabled = true,
            velocity = velocity.coerceIn(1, 127),
        )
        val updated = pattern.withChannel(channelIndex) { it.withStep(stepIndex, step) }
        _ui.value = _ui.value.copy(pattern = updated)
        engine.writeStep(pattern.bankSlot, channelIndex, stepIndex, step)
        persistStep(updated, channelIndex, stepIndex, step)
    }

    fun clearChannel(channelIndex: Int) {
        val pattern = _ui.value.pattern ?: return
        val updated = pattern.withChannel(channelIndex) { it.clearedSteps() }
        _ui.value = _ui.value.copy(pattern = updated)
        engine.clearChannel(pattern.bankSlot, channelIndex)
        savePattern(updated)
    }

    // ── channels ────────────────────────────────────────────────────────

    fun addChannel() {
        val pattern = _ui.value.pattern ?: return
        if (pattern.channels.size >= PatternLimits.MAX_CHANNELS) return
        val updated = PatternOps.addChannel(pattern)
        _ui.value = _ui.value.copy(pattern = updated)
        savePatternAndPublish(updated)
    }

    fun removeChannel(index: Int) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.removeChannel(pattern, index)
        if (updated === pattern) return
        _ui.value = _ui.value.copy(
            pattern = updated,
            selectedChannel = _ui.value.selectedChannel.coerceAtMost(updated.channels.lastIndex),
            editingChannel = null,
        )
        savePatternAndPublish(updated)
    }

    fun renameChannel(index: Int, name: String) {
        updateChannel(index, publish = false) { it.copy(name = name.trim().ifBlank { it.name }) }
    }

    fun assignSample(channelIndex: Int, sample: SampleRef?) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.assignSample(
            pattern,
            channelIndex,
            sample?.id,
            name = sample?.name?.takeIf { pattern.channelAt(channelIndex)?.sampleId == null },
        )
        _ui.value = _ui.value.copy(pattern = updated)
        // A new sound has to be resident before the engine can be told which
        // slot it is in, so this goes through the full publish rather than a
        // parameter command.
        savePatternAndPublish(updated)
    }

    fun setChannelVolume(index: Int, volume: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_VOLUME, volume.coerceIn(0f, 2f)) {
            it.copy(volume = volume.coerceIn(0f, 2f))
        }

    fun setChannelPan(index: Int, pan: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_PAN, pan.coerceIn(-1f, 1f)) {
            it.copy(pan = pan.coerceIn(-1f, 1f))
        }

    fun setChannelPitch(index: Int, semitones: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_PITCH, semitones.coerceIn(-24f, 24f)) {
            it.copy(pitch = semitones.coerceIn(-24f, 24f))
        }

    fun setChannelSampleStart(index: Int, start: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_SAMPLE_START, start.coerceIn(0f, 1f)) {
            it.copy(sampleStart = start.coerceIn(0f, 1f))
        }

    fun setChannelSampleEnd(index: Int, end: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_SAMPLE_END, end.coerceIn(0f, 1f)) {
            it.copy(sampleEnd = end.coerceIn(0f, 1f))
        }

    fun setChannelAttack(index: Int, ms: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_ATTACK, ms.coerceIn(0f, 2000f)) {
            it.copy(attackMs = ms.coerceIn(0f, 2000f))
        }

    fun setChannelRelease(index: Int, ms: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_RELEASE, ms.coerceIn(0f, 5000f)) {
            it.copy(releaseMs = ms.coerceIn(0f, 5000f))
        }

    fun setChannelFilter(index: Int, hz: Float) =
        updateChannel(index, NativeSamplerBridge.PARAM_FILTER, hz.coerceIn(20f, 22000f)) {
            it.copy(filterHz = hz.coerceIn(20f, 22000f))
        }

    fun setChannelReverse(index: Int, reverse: Boolean) =
        updateChannel(index, NativeSamplerBridge.PARAM_REVERSE, if (reverse) 1f else 0f) {
            it.copy(reverse = reverse)
        }

    /**
     * Speed, in playback rate multiples.
     *
     * Unlinked this is a real time stretch — the sample takes longer or less
     * long without changing note. Linked it is tape, and the pitch readout
     * moves with it.
     */
    fun setChannelSpeed(index: Int, speed: Float) {
        val clamped = speed.coerceIn(PatternChannel.MIN_SPEED, PatternChannel.MAX_SPEED)
        updateChannel(index, NativeSamplerBridge.PARAM_SPEED, clamped) { it.copy(speed = clamped) }
    }

    fun setChannelLinked(index: Int, linked: Boolean) =
        updateChannel(index, NativeSamplerBridge.PARAM_LINKED, if (linked) 1f else 0f) {
            it.copy(linked = linked)
        }

    /**
     * Applies a preset to both controls at once.
     *
     * Sent as three separate parameter commands rather than a republish: the
     * engine reads them when the next voice starts, so a preset tapped mid-loop
     * lands on the next trig instead of interrupting the one that is sounding.
     */
    fun applySpeedPitchPreset(index: Int, preset: SpeedPitchPreset) {
        val pattern = _ui.value.pattern ?: return
        if (index !in pattern.channels.indices) return
        val updated = pattern.withChannel(index) {
            it.copy(speed = preset.speed, pitch = preset.semitones, linked = preset.linked)
        }
        _ui.value = _ui.value.copy(pattern = updated)
        engine.setChannelParam(pattern.bankSlot, index, NativeSamplerBridge.PARAM_SPEED, preset.speed)
        engine.setChannelParam(pattern.bankSlot, index, NativeSamplerBridge.PARAM_PITCH, preset.semitones)
        engine.setChannelParam(
            pattern.bankSlot, index, NativeSamplerBridge.PARAM_LINKED,
            if (preset.linked) 1f else 0f,
        )
        savePattern(updated)
    }

    /** Back to playing the sample as recorded. Speed, pitch and link only. */
    fun resetChannelSpeedPitch(index: Int) {
        val pattern = _ui.value.pattern ?: return
        if (index !in pattern.channels.indices) return
        val updated = pattern.withChannel(index) {
            it.copy(speed = 1f, pitch = 0f, linked = false)
        }
        _ui.value = _ui.value.copy(pattern = updated)
        engine.setChannelParam(pattern.bankSlot, index, NativeSamplerBridge.PARAM_SPEED, 1f)
        engine.setChannelParam(pattern.bankSlot, index, NativeSamplerBridge.PARAM_PITCH, 0f)
        engine.setChannelParam(pattern.bankSlot, index, NativeSamplerBridge.PARAM_LINKED, 0f)
        savePattern(updated)
    }

    /** The same, for every channel in the pattern. */
    fun resetAllSpeedPitch() {
        val pattern = _ui.value.pattern ?: return
        val updated = pattern.copy(
            channels = pattern.channels.map { it.copy(speed = 1f, pitch = 0f, linked = false) },
        )
        _ui.value = _ui.value.copy(pattern = updated)
        updated.channels.indices.forEach { i ->
            engine.setChannelParam(pattern.bankSlot, i, NativeSamplerBridge.PARAM_SPEED, 1f)
            engine.setChannelParam(pattern.bankSlot, i, NativeSamplerBridge.PARAM_PITCH, 0f)
            engine.setChannelParam(pattern.bankSlot, i, NativeSamplerBridge.PARAM_LINKED, 0f)
        }
        savePattern(updated)
    }

    /** Global. Read when a voice starts, so it lands on the next trig. */
    fun setStretchQuality(quality: StretchQuality) {
        _ui.value = _ui.value.copy(stretchQuality = quality)
        engine.setStretchQuality(quality.id)
    }

    fun toggleMute(index: Int) {
        val current = _ui.value.pattern?.channelAt(index) ?: return
        updateChannel(index, NativeSamplerBridge.PARAM_MUTE, if (current.muted) 0f else 1f) {
            it.copy(muted = !it.muted)
        }
    }

    /** Exclusive solo — the engine and the model agree that only one row wins. */
    fun toggleSolo(index: Int) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.soloChannel(pattern, index)
        _ui.value = _ui.value.copy(pattern = updated)
        updated.channels.forEachIndexed { i, channel ->
            engine.setChannelParam(
                pattern.bankSlot, i, NativeSamplerBridge.PARAM_SOLO,
                if (channel.soloed) 1f else 0f,
            )
        }
        savePattern(updated)
    }

    private fun updateChannel(
        index: Int,
        param: Int? = null,
        value: Float = 0f,
        publish: Boolean = false,
        transform: (PatternChannel) -> PatternChannel,
    ) {
        val pattern = _ui.value.pattern ?: return
        if (index !in pattern.channels.indices) return
        val updated = pattern.withChannel(index, transform)
        _ui.value = _ui.value.copy(pattern = updated)
        param?.let { engine.setChannelParam(pattern.bankSlot, index, it, value) }
        if (publish) savePatternAndPublish(updated) else savePattern(updated)
    }

    // ── pattern bank ────────────────────────────────────────────────────

    fun selectPattern(bankSlot: Int) {
        viewModelScope.launch {
            val loaded = patterns.loadSlot(bankSlot) ?: return@launch
            engine.publishPattern(loaded)
            engine.queuePattern(bankSlot)
            // The grid follows immediately even when the switch is queued: the
            // user is about to edit the pattern they just chose, and making
            // them wait a bar to see it would be nonsense. The playhead stays
            // on the pattern that is actually sounding, which is what the
            // NEXT badge in the bank is for.
            _ui.value = _ui.value.copy(pattern = loaded, page = 0, selectedChannel = 0)
        }
    }

    fun createPattern() {
        viewModelScope.launch {
            val used = patterns.usedSlots()
            val slot = PatternOps.firstFreeSlot(used) ?: return@launch
            val created = patterns.save(PatternOps.newPattern(slot))
            engine.publishPattern(created)
            _ui.value = _ui.value.copy(pattern = created, page = 0, selectedChannel = 0)
            engine.queuePattern(slot)
        }
    }

    fun duplicatePattern() {
        val source = _ui.value.pattern ?: return
        viewModelScope.launch {
            val used = patterns.usedSlots()
            val slot = PatternOps.firstFreeSlot(used) ?: return@launch
            val copy = patterns.save(PatternOps.duplicate(source, slot))
            engine.publishPattern(copy)
            _ui.value = _ui.value.copy(pattern = copy, page = 0)
            engine.queuePattern(slot)
        }
    }

    fun renamePattern(name: String) {
        val pattern = _ui.value.pattern ?: return
        val updated = pattern.copy(name = name.trim().ifBlank { pattern.name })
        _ui.value = _ui.value.copy(pattern = updated)
        savePattern(updated)
    }

    fun deletePattern() {
        val pattern = _ui.value.pattern ?: return
        viewModelScope.launch {
            patterns.delete(pattern.id)
            val remaining = patterns.loadBank()
            val next = remaining.firstOrNull()
            _ui.value = _ui.value.copy(pattern = next, page = 0, selectedChannel = 0)
            next?.let { engine.queuePattern(it.bankSlot, immediate = true) }
        }
    }

    fun clearPattern() {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.clearSteps(pattern)
        _ui.value = _ui.value.copy(pattern = updated)
        engine.clearPattern(pattern.bankSlot)
        savePattern(updated)
    }

    fun copyPattern() {
        _ui.value = _ui.value.copy(pendingCopy = _ui.value.pattern)
    }

    fun pastePattern() {
        val source = _ui.value.pendingCopy ?: return
        val target = _ui.value.pattern ?: return
        if (source.id == target.id) return
        val merged = PatternOps.paste(source, target)
        _ui.value = _ui.value.copy(pattern = merged)
        savePatternAndPublish(merged)
    }

    fun setPatternLength(length: Int) {
        val pattern = _ui.value.pattern ?: return
        val updated = PatternOps.resize(pattern, length)
        _ui.value = _ui.value.copy(
            pattern = updated,
            page = _ui.value.page.coerceAtMost(
                ((updated.lengthSteps + STEPS_PER_PAGE - 1) / STEPS_PER_PAGE - 1).coerceAtLeast(0),
            ),
        )
        engine.setPatternLength(pattern.bankSlot, updated.lengthSteps)
        savePattern(updated)
    }

    // ── transport ───────────────────────────────────────────────────────

    fun play() = engine.play()
    fun stop() = engine.stop()
    fun setBpm(bpm: Float) = engine.setBpm(bpm)
    fun setSwing(swing: Float) = engine.setSwing(swing)
    fun setMetronome(on: Boolean) = engine.setMetronome(on)
    fun setCountIn(bars: Int) = engine.setCountIn(bars)
    fun setRecordQuantum(quantum: Int) = engine.setRecordQuantum(quantum)
    fun setSwitchMode(mode: PatternSwitchMode) = engine.setSwitchMode(mode)

    /**
     * REC arms recording and starts the loop if it is not already running,
     * which is what makes "REC + PLAY" one gesture rather than two.
     */
    fun toggleRecord() {
        val recording = transport.value.recording
        engine.setRecording(!recording)
        if (!recording && !transport.value.playing) engine.play()
    }

    fun trigger(channelIndex: Int, velocity: Float = 1f) = engine.trigger(channelIndex, velocity)

    // ── persistence ─────────────────────────────────────────────────────

    /**
     * Coalesced whole-pattern save.
     *
     * Dragging a fader emits a change per frame; writing three tables on each
     * one would put the database in the way of a gesture. The last write in a
     * burst wins, which is exactly right for a continuous control.
     */
    private fun savePattern(pattern: Pattern) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
            val saved = patterns.save(pattern)
            // Only the ids can have changed — the repository preserves channel
            // rows — so adopting them is safe even if the user has edited
            // something else during the debounce. Adopting the whole saved
            // copy would not be: it is a snapshot from before those edits.
            val current = _ui.value.pattern
            if (current != null && current.id == 0L && current.bankSlot == saved.bankSlot) {
                _ui.value = _ui.value.copy(
                    pattern = current.copy(
                        id = saved.id,
                        channels = current.channels.mapIndexed { index, channel ->
                            if (channel.id == 0L) {
                                channel.copy(id = saved.channels.getOrNull(index)?.id ?: 0L)
                            } else {
                                channel
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun savePatternAndPublish(pattern: Pattern) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            val saved = patterns.save(pattern)
            _ui.value = _ui.value.copy(pattern = saved)
            engine.publishPattern(saved)
        }
    }

    /** Single-trig write; no debounce, because a tap is not a gesture. */
    private fun persistStep(pattern: Pattern, channelIndex: Int, stepIndex: Int, step: Step) {
        val channelId = pattern.channelAt(channelIndex)?.id ?: return
        viewModelScope.launch {
            if (channelId <= 0) {
                // The pattern has never been written, so there is no channel
                // row to attach a step to yet. Save the whole thing once and
                // subsequent taps take the cheap path.
                val saved = patterns.save(pattern)
                _ui.value = _ui.value.copy(pattern = saved)
            } else {
                patterns.saveStep(channelId, stepIndex, step)
            }
        }
    }

    /**
     * Pulls live-recorded hits back out of the engine.
     *
     * The engine writes them itself, on the audio thread, at the step its own
     * quantizer chose — which is the only correct answer, because only the
     * audio thread knows where the playhead was when the tap arrived. So the
     * UI reads that back rather than recomputing it, merges it into the
     * pattern it is showing, and persists the result through the normal path.
     *
     * Merge, not replace: steps the user has toggled since the read was
     * requested must not be undone by an answer computed before they tapped.
     * Only trigs the engine has that the model does not are taken.
     */
    private fun refreshFromEngineRecording() {
        val pattern = _ui.value.pattern ?: return
        if (!transport.value.recording) return
        viewModelScope.launch {
            val recorded = engine.readSteps(pattern.bankSlot, pattern.channels.size) ?: return@launch
            val current = _ui.value.pattern ?: return@launch
            if (current.bankSlot != pattern.bankSlot) return@launch

            var merged = current
            var changed = false
            recorded.forEachIndexed { channelIndex, steps ->
                steps.forEachIndexed { stepIndex, step ->
                    // Re-read from `merged` each time rather than caching the
                    // channel: it is replaced on every write below.
                    val channel = merged.channelAt(channelIndex) ?: return@forEachIndexed
                    if (step.enabled && !channel.step(stepIndex).enabled) {
                        merged = merged.withChannel(channelIndex) { it.withStep(stepIndex, step) }
                        changed = true
                    }
                }
            }
            if (!changed) return@launch
            _ui.value = _ui.value.copy(pattern = merged)
            patterns.save(merged)
        }
    }

    companion object {
        /** Steps drawn per grid page — two rows of eight. */
        const val STEPS_PER_PAGE = 16
        private const val SAVE_DEBOUNCE_MS = 400L
    }
}
