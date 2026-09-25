package tf.monochrome.android.audio.dsp.spatial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SpatialPlacement.DEFAULT)
    val state: StateFlow<SpatialPlacement> = _state.asStateFlow()

    /** What is playing now. Safe to read from the audio thread. */
    val current: SpatialPlacement get() = _state.value

    init {
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
    }
}
