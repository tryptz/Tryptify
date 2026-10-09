package tf.monochrome.android.ui.settings.fonts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.fonts.FontImportReport
import tf.monochrome.android.data.fonts.FontLibrary
import tf.monochrome.android.data.fonts.ImportedFont
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject

/** The key the built-in default is listed under; its stored id is null. */
const val DEFAULT_FONT_KEY = "default"

/** Turns a listing key back into what is stored as the active font. */
internal fun fontIdForKey(key: String): String? = key.takeUnless { it == DEFAULT_FONT_KEY }

/**
 * The font browser: every font the app can use, previewed before it is chosen.
 *
 * Choosing used to be a tap on a name in a collapsed list, with the name drawn
 * in the current font — so nothing on screen said what a font looked like until
 * it was already the whole interface. Here a tap only *previews*; [apply] is
 * what changes the app.
 */
@HiltViewModel
class FontBrowserViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    private val library: FontLibrary,
) : ViewModel() {

    /** The font in use, as a listing key. */
    val activeKey: StateFlow<String> = preferences.customFontUri
        .map { it ?: DEFAULT_FONT_KEY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_KEY)

    val imported: StateFlow<List<ImportedFont>> = library.imported

    private val _picked = MutableStateFlow<String?>(null)

    /** The font being previewed: the one in use until another is tapped. */
    val previewKey: StateFlow<String> = combine(_picked, activeKey) { picked, active -> picked ?: active }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_KEY)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _report = MutableStateFlow<FontImportReport?>(null)
    val report: StateFlow<FontImportReport?> = _report.asStateFlow()

    private val _pendingDelete = MutableStateFlow<ImportedFont?>(null)
    val pendingDelete: StateFlow<ImportedFont?> = _pendingDelete.asStateFlow()

    init {
        viewModelScope.launch { library.refresh() }
    }

    fun preview(key: String) {
        _picked.value = key
    }

    /** Makes the previewed font the app's font. */
    fun apply() {
        val key = previewKey.value
        viewModelScope.launch { preferences.setCustomFontUri(fontIdForKey(key)) }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val result = library.import(uris)
                _report.value = result
                // The first new font goes straight into the preview: the
                // reason to import one is to see it.
                result.added.firstOrNull()?.let { _picked.value = it.id }
            } finally {
                _importing.value = false
            }
        }
    }

    fun dismissReport() {
        _report.value = null
    }

    fun requestDelete(font: ImportedFont) {
        _pendingDelete.value = font
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val font = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            // Deleting the font in use hands the app back to the default
            // first, so it is never pointed at a file that is gone.
            if (activeKey.value == font.id) preferences.setCustomFontUri(null)
            if (_picked.value == font.id) _picked.value = null
            library.delete(font)
        }
    }
}
