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
import androidx.compose.runtime.remember
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

/**
 * Walks the SOFA collection and takes a file from it: the HRTF database and
 * the room database are the same browser over different folders, and differ
 * in what they do with a file ([use]).
 */
abstract class SofaBrowseViewModel(
    /** The root folders shown (lower case, as displayed); null for all of them. */
    private val rootFolders: Set<String>?,
    private val unreachable: Int,
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
            val listing = result.getOrNull()?.let { all ->
                if (stack.isEmpty() && rootFolders != null) {
                    all.copy(folders = all.folders.filter { it.display.lowercase() in rootFolders }, files = emptyList())
                } else {
                    all
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                listing = listing,
                error = result.exceptionOrNull()?.let { UiText.Res(unreachable) },
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

    /** Downloads a .sofa and puts it to use; the status line says how that went. */
    fun apply(file: HrtfNode) {
        if (_state.value.busyFile != null) return
        _state.value = _state.value.copy(busyFile = file.href, status = null)
        val path = currentPath() + file.href
        viewModelScope.launch {
            val result = api.download(path)
            val bytes = result.getOrNull()
            val status = if (bytes == null || bytes.isEmpty()) {
                result.exceptionOrNull()?.let { errorText(it, R.string.hrtf_download_failed) } ?: UiText.Res(R.string.hrtf_download_failed)
            } else {
                use(file, bytes)
            }
            _state.value = _state.value.copy(busyFile = null, status = status)
        }
    }

    /** Takes the downloaded [bytes] of [file] into use; what to tell the listener. */
    protected abstract suspend fun use(file: HrtfNode, bytes: ByteArray): UiText
}

@HiltViewModel
class HrtfDatabaseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) : SofaBrowseViewModel(rootFolders = null, unreachable = R.string.hrtf_unreachable) {

    /** Stores the .sofa in app storage and selects it as the HRTF. */
    override suspend fun use(file: HrtfNode, bytes: ByteArray): UiText = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "hrtf").apply { mkdirs() }
            val dest = File(dir, "${file.display}.sofa".replace(Regex("[^A-Za-z0-9._-]"), "_"))
            dest.writeBytes(bytes)
            // Keep previous downloads: every stored .sofa remains a
            // selectable preset on the renderer page.
            val profile = preferences.rendererProfile.first()
            // Applying a downloaded HRTF is an explicit request to use
            // one, so the binauralizer goes on as well if it was off.
            preferences.setRendererProfile(
                profile.copy(hrtfProfileId = dest.absolutePath, hrtfEnabled = true)
            )
            UiText.Res(R.string.hrtf_applied, listOf(file.display, bytes.size / 1024))
        }.getOrElse { UiText.Res(R.string.hrtf_save_failed) }
    }
}

/**
 * The room database: the collection's folders of room impulse responses.
 * A file is read once into a room the render can play in
 * ([tf.monochrome.android.audio.atmos.AtmosRoomStore.addMeasured]) and that
 * room is selected; the SOFA itself is not kept.
 */
@HiltViewModel
class RoomDatabaseViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    private val rooms: tf.monochrome.android.audio.atmos.AtmosRoomStore,
) : SofaBrowseViewModel(rootFolders = SofaHrtfApi.ROOM_FOLDERS, unreachable = R.string.room_unreachable) {

    override suspend fun use(file: HrtfNode, bytes: ByteArray): UiText {
        val room = rooms.addMeasured(file.display, bytes) ?: return UiText.Res(R.string.room_unusable)
        val profile = preferences.rendererProfile.first()
        preferences.setRendererProfile(
            profile.copy(
                roomKind = tf.monochrome.android.domain.model.RoomKind.MEASURED,
                roomFile = room.absolutePath,
            )
        )
        return UiText.Res(R.string.room_applied, listOf(file.display))
    }
}

@Composable
fun HrtfDatabaseScreen(
    navController: NavController,
    viewModel: HrtfDatabaseViewModel = hiltViewModel(),
) = SofaBrowser(
    navController, viewModel,
    title = R.string.hrtf_hrtf_database, root = R.string.hrtf_databases, tapToUse = R.string.hrtf_tap_to_use,
)

@Composable
fun RoomDatabaseScreen(
    navController: NavController,
    viewModel: RoomDatabaseViewModel = hiltViewModel(),
) = SofaBrowser(
    navController, viewModel,
    title = R.string.room_database, root = R.string.room_databases, tapToUse = R.string.room_tap_to_use,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SofaBrowser(
    navController: NavController,
    viewModel: SofaBrowseViewModel,
    @androidx.annotation.StringRes title: Int,
    @androidx.annotation.StringRes root: Int,
    @androidx.annotation.StringRes tapToUse: Int,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hardware back navigates up a folder before leaving the screen.
    BackHandler(enabled = true) { if (!viewModel.up()) navController.popBackStackSafe() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(title))
                    Text(
                        state.location ?: stringResource(root),
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
        // Filtered when the listing or the words change, not on every status line
        // or download tick: a database folder can list hundreds of files.
        val (folders, files) = remember(listing, q) {
            listing?.folders.orEmpty().filter { q.isEmpty() || it.display.lowercase().contains(q) } to
                listing?.files.orEmpty().filter { q.isEmpty() || it.display.lowercase().contains(q) }
        }

        // A permanent floating bar, as every list search is: the databases
        // scroll up under the glass instead of starting below an inline field.
        // The status line hangs under the field in the same pane.
        val status = state.status?.resolve(LocalContext.current)
        tf.monochrome.android.ui.components.SearchOverlay(
            open = true,
            query = state.query,
            onQueryChange = viewModel::setQuery,
            placeholder = stringResource(R.string.hrtf_filter),
            onClose = null,
            autoFocus = false,
            barContent = {
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            },
        ) { searchTopInset ->
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(top = searchTopInset).padding(32.dp)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error != null -> Text(
                state.error!!.resolve(LocalContext.current),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = searchTopInset).padding(16.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                // Starts below the glass and scrolls up under it; the last
                // databases clear the floating tab bar and mini player.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = searchTopInset,
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
                            Text(listOfNotNull(file.size, stringResource(tapToUse)).joinToString(" · "))
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
}
