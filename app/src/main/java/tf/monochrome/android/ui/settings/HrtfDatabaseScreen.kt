package tf.monochrome.android.ui.settings

import androidx.compose.ui.platform.LocalContext
import tf.monochrome.android.ui.components.errorText
import tf.monochrome.android.ui.components.UiText
import tf.monochrome.android.ui.navigation.popBackStackSafe
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.HrtfListing
import tf.monochrome.android.data.api.HrtfNode
import tf.monochrome.android.data.api.SofaHrtfApi
import tf.monochrome.android.data.preferences.PreferencesManager
import java.io.File
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

data class HrtfBrowseState(
    val loading: Boolean = true,
    val listing: HrtfListing? = null,
    val error: UiText? = null,
    /** Null at the root, which the screen names in the reader's language. */
    val location: String? = null,
    val query: String = "",
    val busyFile: String? = null,   // href currently downloading
    val status: UiText? = null,     // last apply result
)

@HiltViewModel
class HrtfDatabaseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) : ViewModel() {

    private val api = SofaHrtfApi()
    private val stack = ArrayDeque<HrtfNode>()  // folders navigated into

    private val _state = MutableStateFlow(HrtfBrowseState())
    val state: StateFlow<HrtfBrowseState> = _state.asStateFlow()

    init { load() }

    private fun currentPath(): String = stack.joinToString("") { it.href }

    private fun load() {
        _state.value = _state.value.copy(loading = true, error = null, query = "")
        viewModelScope.launch {
            val result = api.list(currentPath())
            _state.value = _state.value.copy(
                loading = false,
                listing = result.getOrNull(),
                error = result.exceptionOrNull()?.let { UiText.Res(R.string.hrtf_unreachable) },
                location = if (stack.isEmpty()) null
                    else stack.joinToString(" / ") { it.display },
            )
        }
    }

    fun enter(folder: HrtfNode) { stack.addLast(folder); load() }

    /** True if it navigated up a level; false if already at the root. */
    fun up(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeLast(); load(); return true
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    /** Downloads a .sofa, stores it in app storage and selects it as the HRTF. */
    fun apply(file: HrtfNode) {
        if (_state.value.busyFile != null) return
        _state.value = _state.value.copy(busyFile = file.href, status = null)
        val path = currentPath() + file.href
        viewModelScope.launch {
            val result = api.download(path)
            val bytes = result.getOrNull()
            val status = if (bytes == null || bytes.isEmpty()) {
                result.exceptionOrNull()?.let { errorText(it, R.string.hrtf_download_failed) } ?: UiText.Res(R.string.hrtf_download_failed)
            } else withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "hrtf").apply { mkdirs() }
                    val dest = File(dir, "${file.display}.sofa".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    dest.writeBytes(bytes)
                    // Keep previous downloads — every stored .sofa remains a
                    // selectable preset on the renderer page.
                    val profile = preferences.rendererProfile.first()
                    // Applying a downloaded HRTF is an explicit request to use
                    // one — also re-enable the binauralizer if it was off.
                    preferences.setRendererProfile(
                        profile.copy(hrtfProfileId = dest.absolutePath, hrtfEnabled = true)
                    )
                    UiText.Res(R.string.hrtf_applied, listOf(file.display, bytes.size / 1024))
                }.getOrElse { UiText.Res(R.string.hrtf_save_failed) }
            }
            _state.value = _state.value.copy(busyFile = null, status = status)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrtfDatabaseScreen(
    navController: NavController,
    viewModel: HrtfDatabaseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hardware back navigates up a folder before leaving the screen.
    BackHandler(enabled = true) { if (!viewModel.up()) navController.popBackStackSafe() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.hrtf_hrtf_database))
                    Text(
                        state.location ?: stringResource(R.string.hrtf_databases),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (!viewModel.up()) navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
        )

        val listing = state.listing
        val q = state.query.trim().lowercase()
        val folders = listing?.folders.orEmpty().filter { q.isEmpty() || it.display.lowercase().contains(q) }
        val files = listing?.files.orEmpty().filter { q.isEmpty() || it.display.lowercase().contains(q) }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.hrtf_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        state.status?.let {
            Text(
                it.resolve(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error != null -> Text(
                state.error!!.resolve(LocalContext.current),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                // The last databases clear the floating tab bar and mini player.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = tf.monochrome.android.ui.navigation.bottomChromePadding,
                ),
            ) {
                items(folders, key = { "d/" + it.href }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.display) },
                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.enter(folder) },
                    )
                }
                items(files, key = { "f/" + it.href }) { file ->
                    val busy = state.busyFile == file.href
                    ListItem(
                        headlineContent = { Text(file.display) },
                        supportingContent = {
                            Text(listOfNotNull(file.size, stringResource(R.string.hrtf_tap_to_use)).joinToString(" · "))
                        },
                        leadingContent = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                        trailingContent = {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp))
                        },
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.apply(file) },
                    )
                }
                if (folders.isEmpty() && files.isEmpty()) {
                    item { Text(stringResource(R.string.hrtf_nothing_here), modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}
