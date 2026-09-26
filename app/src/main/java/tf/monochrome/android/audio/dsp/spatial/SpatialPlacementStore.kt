package tf.monochrome.android.audio.dsp.spatial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The spatial map's live placement, shared by the map screen (which edits it)
 * and DownmixProcessor (which plays it).
 *
 * A drag updates [current] at once — the audio thread reads it at the next
 * buffer, so the sound moves with the finger — while the write to disk waits
 * until the finger has been still for a moment, rather than saving sixty times
 * a second.
 */
@Singleton
@OptIn(FlowPreview::class)
class SpatialPlacementStore @Inject constructor(
    private val preferences: PreferencesManager,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SpatialPlacement.DEFAULT)
    val state: StateFlow<SpatialPlacement> = _state.asStateFlow()

    /** What is playing now. Safe to read from the audio thread. */
    val current: SpatialPlacement get() = _state.value

    /**
     * The chosen headphone target as the placer takes it ([HeadphoneTarget]),
     * worked out here — the curves are read from assets — never on the audio
     * thread, which only compares the reference and hands it on.
     */
    @Volatile var targetCurve: FloatArray = FloatArray(ChannelPlacerPoints)
        private set

    /** The targets the map offers: AutoEQ's own. */
    val targets: List<tf.monochrome.android.domain.model.EqTarget>
        get() = tf.monochrome.android.audio.eq.FrequencyTargets.getAllTargets()

    init {
        // The playback service can start without the activity that usually
        // does this (a headset button, say).
        tf.monochrome.android.audio.eq.FrequencyTargets.init(context)
        scope.launch {
            _state.map { it.targetId }.distinctUntilChanged().collect { id ->
                val targets = tf.monochrome.android.audio.eq.FrequencyTargets
                val target = targets.getTargetById(id)?.data.orEmpty()
                targetCurve = HeadphoneTarget.curve(target, ChannelPlacerPoints) {
                    tf.monochrome.android.audio.atmos.ChannelPlacerNative.targetFreq(it)
                }
            }
        }
        scope.launch {
            // The saved placement, unless the map was touched before it loaded.
            val saved = preferences.spatialPlacement.first()
            _state.compareAndSet(SpatialPlacement.DEFAULT, saved)
            _state.drop(1).debounce(SAVE_AFTER_MS).collect { preferences.setSpatialPlacement(it) }
        }
    }

    fun update(transform: (SpatialPlacement) -> SpatialPlacement) {
        while (true) {
            val before = _state.value
            if (_state.compareAndSet(before, transform(before))) return
        }
    }

    private companion object {
        const val SAVE_AFTER_MS = 400L
        // Plain constant: reading ChannelPlacerNative's would load the native library.
        const val ChannelPlacerPoints = 64
    }
}
