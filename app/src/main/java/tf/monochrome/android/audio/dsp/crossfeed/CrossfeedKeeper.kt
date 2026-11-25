// SPDX-License-Identifier: GPL-3.0-or-later
package tf.monochrome.android.audio.dsp.crossfeed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.atmos.AtmosSofaStore
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Crossfeed page on the phone and gives its Room the HRTF chosen
 * for it.
 *
 * Until 1.9.6 crossfeed lived in memory only and was off again at every
 * start. Now what the page holds is read back at the start of the app and
 * written as it changes (device only: the Room's HRTF is a file here).
 *
 * The HRTF is a .sofa's path. Reading one takes a moment, seconds for a
 * finely measured file, so [AtmosSofaStore] reads it here, off every audio
 * thread, and the set it makes is handed to the effect, which builds the
 * room's filters with it. A file that cannot be read leaves the built-in set
 * playing, and [hrtf] says so.
 */
@Singleton
class CrossfeedKeeper @Inject constructor(
    private val effect: CrossfeedEffect,
    private val preferences: PreferencesManager,
    private val sofaStore: AtmosSofaStore,
) {
    /** Where the Room's HRTF stands, for the page. */
    enum class Hrtf { BUILT_IN, LOADING, LOADED, FAILED }

    private val _hrtf = MutableStateFlow(Hrtf.BUILT_IN)
    val hrtf: StateFlow<Hrtf> = _hrtf.asStateFlow()

    private var started = false

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            // Read back first: what is saved must not be written over by the
            // defaults the effect started with.
            CrossfeedState.decode(runCatching { preferences.crossfeedJson.first() }.getOrNull())
                ?.let { saved -> effect.update { saved } }
            effect.state
                .map { CrossfeedState.encode(it) }
                .distinctUntilChanged()
                .debounce(SAVE_AFTER_MS)
                .collect { runCatching { preferences.setCrossfeedJson(it) } }
        }
        scope.launch {
            effect.state
                .map { it.room.hrtfPath }
                .distinctUntilChanged()
                .collect { path -> load(path) }
        }
    }

    private suspend fun load(path: String?) {
        if (path == null) {
            withContext(Dispatchers.Default) { effect.setRoomHrtf(null) }
            _hrtf.value = Hrtf.BUILT_IN
            return
        }
        _hrtf.value = Hrtf.LOADING
        val ok = withContext(Dispatchers.IO) {
            val set = runCatching { sofaStore.prepared(path) }.getOrNull()
            if (set == null) {
                effect.setRoomHrtf(null)
                false
            } else {
                effect.setRoomHrtf(set)
            }
        }
        // Chosen again meanwhile: that load says how it went.
        if (effect.state.value.room.hrtfPath != path) return
        _hrtf.value = if (ok) Hrtf.LOADED else Hrtf.FAILED
        android.util.Log.i(TAG, "room HRTF ${java.io.File(path).name}: ${if (ok) "loaded" else "not an HRTF set, built-in kept"}")
    }

    private companion object {
        const val TAG = "CrossfeedRoom"
        const val SAVE_AFTER_MS = 400L
    }
}
