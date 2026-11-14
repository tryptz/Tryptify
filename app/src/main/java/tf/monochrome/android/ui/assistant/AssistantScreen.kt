package tf.monochrome.android.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.data.assistant.Assistant
import tf.monochrome.android.data.assistant.ClaudeModel
import androidx.compose.material3.FilterChip
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.player.PlayerViewModel
import javax.inject.Inject

/** One thing in the conversation, as the page shows it. */
sealed interface ChatLine {
    val id: Long
    data class Mine(override val id: Long, val text: String) : ChatLine
    data class Theirs(override val id: Long, val text: String) : ChatLine
    /** Something the assistant did in the app, or was told not to. */
    data class Did(override val id: Long, val what: String, val done: Boolean) : ChatLine
    data class Problem(override val id: Long, val message: Int) : ChatLine
}

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val assistant: Assistant,
    private val preferences: PreferencesManager,
) : ViewModel(), Assistant.Host {

    val hasKey: StateFlow<Boolean> = preferences.claudeApiKey.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val auto: StateFlow<Boolean> = assistant.auto
    val model: StateFlow<ClaudeModel> = preferences.claudeModel.map { ClaudeModel.of(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ClaudeModel.DEFAULT)

    fun setModel(model: ClaudeModel) = viewModelScope.launch { preferences.setClaudeModel(model.stored) }

    private val conversation = Assistant.Conversation()
    private var nextId = 0L
    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** A change waiting for the listener's yes or no. */
    class Asking(val what: String, val answer: CompletableDeferred<Boolean>)
    private val _asking = MutableStateFlow<Asking?>(null)
    val asking: StateFlow<Asking?> = _asking.asStateFlow()

    /** What only the page can do; set while it is on screen. */
    var open: ((String) -> Boolean)? = null
    var control: ((String) -> Unit)? = null
    var playing: (() -> String)? = null

    fun saveKey(key: String) = viewModelScope.launch { preferences.setClaudeApiKey(key) }
    fun setAuto(on: Boolean) { assistant.auto.value = on }

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        add { ChatLine.Mine(it, text.trim()) }
        _busy.value = true
        viewModelScope.launch {
            val failure = assistant.send(conversation, text.trim(), this@AssistantViewModel)
            if (failure != null) add { ChatLine.Problem(it, problemText(failure)) }
            _busy.value = false
        }
    }

    fun answer(yes: Boolean) {
        _asking.value?.answer?.complete(yes)
        _asking.value = null
    }

    fun startOver() {
        if (_busy.value) return
        conversation.clear()
        _lines.value = emptyList()
    }

    override suspend fun approve(what: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _asking.value = Asking(what, answer)
        return answer.await()
    }
    override fun openScreen(screen: String): Boolean = open?.invoke(screen) ?: false
    override fun playback(action: String) { control?.invoke(action) }
    override fun nowPlaying(): String = playing?.invoke() ?: "Nothing is known about what is playing."
    override fun said(text: String) = add { ChatLine.Theirs(it, text) }
    override fun did(what: String, done: Boolean) = add { ChatLine.Did(it, what, done) }

    override fun onCleared() {
        // A turn left waiting for an answer nobody can now give is answered no.
        _asking.value?.answer?.complete(false)
    }

    private fun add(line: (Long) -> ChatLine) = _lines.update { it + line(nextId++) }

    private fun problemText(failure: Assistant.Failure): Int = when (failure) {
        Assistant.Failure.NO_KEY, Assistant.Failure.KEY_REFUSED -> R.string.eq_claude_err_key
        Assistant.Failure.BUSY -> R.string.eq_claude_err_busy
        Assistant.Failure.NETWORK -> R.string.eq_claude_err_network
        Assistant.Failure.DECLINED -> R.string.eq_claude_err_refused
        Assistant.Failure.TOO_LONG -> R.string.assistant_err_too_long
        Assistant.Failure.OTHER -> R.string.assistant_err_other
    }
}

/** Where each of the assistant's screen names leads. */
internal fun assistantRouteOf(screen: String): String? = when (screen) {
    "home" -> Screen.Home.route
    "search" -> Screen.Search.route
    "discover" -> Screen.Discover.route
    "library" -> Screen.Library.route
    "downloads" -> Screen.Downloads.route
    "now_playing" -> Screen.NowPlaying.route
    "settings" -> Screen.Settings.createRoute()
    "equalizer" -> Screen.Equalizer.route
    "parametric_eq" -> Screen.ParametricEq.route
    "mixer" -> Screen.Mixer.route
    "crossfeed" -> Screen.Crossfeed.route
    "atmos_renderer" -> Screen.AtmosRenderer.route
    "hrtf_database" -> Screen.HrtfDatabase.route
    "room_database" -> Screen.RoomDatabase.route
    "lyrics_fx_studio" -> Screen.LyricsFxStudio.route
    "music_links" -> Screen.MusicLinks.route
    "theme_store" -> Screen.ThemeStore.route
    "widget_settings" -> Screen.WidgetSettings.route
    "stats" -> Screen.Stats.createRoute()
    "profile" -> Screen.Profile.route
    "listening_party" -> Screen.ListeningParty.route
    "library_galaxy" -> Screen.LibraryGalaxy.route
    "genre_map" -> Screen.GenreMap.route
    "trypt_mania" -> Screen.Glyph.route
    "debug_log" -> Screen.DebugLog.route
    else -> null
}

/**
 * The assistant: a conversation in which Claude answers about the app and,
 * with the listener's yes each time (or on Auto, until the app closes), acts
 * in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    // What only a screen can do for the assistant, lent while this one is up.
    DisposableEffect(navController, playerViewModel) {
        viewModel.open = { screen ->
            val route = assistantRouteOf(screen)
            route != null && runCatching { navController.navigate(route) { launchSingleTop = true } }.isSuccess
        }
        viewModel.control = { action ->
            when (action) {
                "play_pause" -> playerViewModel.togglePlayPause()
                "next" -> playerViewModel.skipToNext()
                "previous" -> playerViewModel.skipToPrevious()
            }
        }
        viewModel.playing = {
            val track = playerViewModel.currentTrack.value
            if (track == null) "Nothing is playing."
            else "\"${track.title}\" by ${track.artist?.name ?: "an unknown artist"}, ${if (playerViewModel.isPlaying.value) "playing" else "paused"}."
        }
        onDispose { viewModel.open = null; viewModel.control = null; viewModel.playing = null }
    }
    LaunchedEffect(lines.size, asking) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.assistant_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            actions = {
                if (lines.isNotEmpty()) TextButton(onClick = viewModel::startOver, enabled = !busy) {
                    Text(stringResource(R.string.assistant_start_over))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        if (!hasKey) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.assistant_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(stringResource(R.string.eq_claude_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.eq_claude_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { viewModel.saveKey(keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) {
                    Text(stringResource(R.string.eq_claude_save_key))
                }
            }
            return@Column
        }

        // Auto: changes without asking, until the app closes.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.assistant_auto), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (auto) R.string.assistant_auto_on else R.string.assistant_auto_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = auto, onCheckedChange = viewModel::setAuto)
        }
        val model by viewModel.model.collectAsStateWithLifecycle()
        ClaudeModelChips(model = model, onModel = viewModel::setModel, modifier = Modifier.padding(horizontal = 20.dp))

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) item {
                Text(
                    stringResource(R.string.assistant_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(lines, key = { it.id }) { line -> Line(line) }
            if (busy && asking == null) item {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        asking?.let { ask ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.assistant_asks), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(ask.what, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.answer(true) }) { Text(stringResource(R.string.assistant_allow)) }
                    OutlinedButton(onClick = { viewModel.answer(false) }) { Text(stringResource(R.string.assistant_deny)) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp + LocalBottomChromeInset.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.assistant_placeholder)) },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.send(draft); draft = "" }, enabled = draft.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_send))
            }
        }
    }
}

/** Which model the key is spent on: the most able, the middle one, or the cheapest. One choice for the whole app. */
@Composable
fun ClaudeModelChips(model: ClaudeModel, onModel: (ClaudeModel) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in ClaudeModel.entries) {
                FilterChip(
                    selected = option == model,
                    onClick = { onModel(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    ClaudeModel.OPUS -> R.string.assistant_model_opus
                                    ClaudeModel.SONNET -> R.string.assistant_model_sonnet
                                    ClaudeModel.HAIKU -> R.string.assistant_model_haiku
                                }
                            )
                        )
                    },
                )
            }
        }
        Text(
            stringResource(
                when (model) {
                    ClaudeModel.OPUS -> R.string.assistant_model_opus_note
                    ClaudeModel.SONNET -> R.string.assistant_model_sonnet_note
                    ClaudeModel.HAIKU -> R.string.assistant_model_haiku_note
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Line(line: ChatLine) {
    when (line) {
        is ChatLine.Mine -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        is ChatLine.Theirs -> Text(line.text, style = MaterialTheme.typography.bodyLarge)
        is ChatLine.Did -> Text(
            stringResource(if (line.done) R.string.assistant_did else R.string.assistant_did_not, line.what),
            style = MaterialTheme.typography.labelLarge,
            color = if (line.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ChatLine.Problem -> Text(
            stringResource(line.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
