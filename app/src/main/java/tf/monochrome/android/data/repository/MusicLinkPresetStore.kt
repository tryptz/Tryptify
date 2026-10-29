// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.domain.model.MusicLinkPreset
import tf.monochrome.android.domain.model.MusicLinkPresets

/**
 * The music link presets the listener saved or imported, one file in the
 * app's storage, as the CRT presets are kept. On this phone only: the links
 * in use sync, a shelf of saved sets does not.
 */
@Singleton
class MusicLinkPresetStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val lock = Mutex()
    private val _presets = MutableStateFlow(read())
    val presets: StateFlow<List<MusicLinkPreset>> = _presets.asStateFlow()

    /** Keeps [preset]; one of the same name, whatever its case, is replaced in its place. */
    suspend fun save(preset: MusicLinkPreset) = update { list ->
        val at = list.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        if (at >= 0) list.toMutableList().also { it[at] = preset } else list + preset
    }

    suspend fun delete(name: String) = update { list -> list.filterNot { it.name == name } }

    private suspend fun update(change: (List<MusicLinkPreset>) -> List<MusicLinkPreset>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = change(_presets.value)
            runCatching { file.writeText(MusicLinkPresets.encodeAll(next)) }
                .onFailure { Log.w(TAG, "could not save music link presets: ${it.message}") }
            _presets.value = next
        }
    }

    private fun read(): List<MusicLinkPreset> =
        MusicLinkPresets.decodeAll(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    private companion object {
        const val TAG = "MusicLinkPresetStore"
        const val FILE_NAME = "music_link_presets.json"
    }
}
