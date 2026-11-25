package tf.monochrome.android.ui.crossfeed

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedKeeper
import java.io.File
import javax.inject.Inject

/**
 * The Crossfeed page: the effect itself, and for its Room the HRTFs on the
 * phone. They are the same files the Atmos page keeps (`files/hrtf`), so a
 * set downloaded or loaded for one can be picked for the other; picking one
 * here changes the Room's only.
 */
@HiltViewModel
class CrossfeedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val crossfeed: CrossfeedEffect,
    keeper: CrossfeedKeeper,
) : ViewModel() {

    /** Where the Room's HRTF stands: built in, being read, in use, or refused. */
    val hrtf: StateFlow<CrossfeedKeeper.Hrtf> = keeper.hrtf

    private val _sofaFiles = MutableStateFlow<List<File>>(emptyList())
    val sofaFiles: StateFlow<List<File>> = _sofaFiles.asStateFlow()

    init { rescan() }

    /** Lists the .sofa files again (after the database screen, say). */
    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) {
            _sofaFiles.value = hrtfDir(context)
                .listFiles { f -> f.isFile && f.name.endsWith(".sofa", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
    }

    /** The Room's HRTF: a file of [sofaFiles], or null for the built-in one. */
    fun chooseHrtf(file: File?) = crossfeed.updateRoom { it.copy(hrtfPath = file?.absolutePath) }

    /**
     * Copies a picked .sofa into the phone's HRTFs and gives it to the Room.
     * The copy is kept because a picked file's grant does not outlive the app.
     */
    fun importSofa(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val dest = File(hrtfDir(context).apply { mkdirs() }, displayName(uri))
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: error("cannot open $uri")
            }.isSuccess
            if (ok) {
                rescan()
                chooseHrtf(dest)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "custom.sofa"
        val safe = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(".sofa", ignoreCase = true)) safe else "$safe.sofa"
    }

    companion object {
        /** Where every .sofa the app keeps is, the Atmos page's as well. */
        fun hrtfDir(context: Context) = File(context.filesDir, "hrtf")
    }
}
