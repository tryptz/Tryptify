package tf.monochrome.android.ui.eq

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.R
import tf.monochrome.android.data.assistant.ClaudeEq
import tf.monochrome.android.data.assistant.ClaudeEqClient
import tf.monochrome.android.data.import_.ParsedEqProfile
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject

/** What the Ask Claude sheet is doing. */
sealed interface AskClaudeState {
    data object Idle : AskClaudeState
    data object Working : AskClaudeState
    data class Proposed(val proposal: ClaudeEq.Proposal) : AskClaudeState
    data class Failed(val why: Int) : AskClaudeState
}

@HiltViewModel
class AskClaudeEqViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
    private val client: ClaudeEqClient,
) : ViewModel() {

    /** Whether a key is saved. The key itself never leaves the preferences but to go to Claude. */
    val hasKey: StateFlow<Boolean> = preferences.claudeApiKey.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow<AskClaudeState>(AskClaudeState.Idle)
    val state: StateFlow<AskClaudeState> = _state.asStateFlow()

    fun saveKey(key: String) = viewModelScope.launch { preferences.setClaudeApiKey(key) }

    fun forgetKey() = viewModelScope.launch { preferences.setClaudeApiKey("") }

    /** Asks for filters from [picture], [wish] and, when given, the filters in use. */
    fun ask(picture: Uri?, wish: String, current: List<EqBand>?, currentPreamp: Float) {
        if (picture == null && wish.isBlank()) {
            _state.value = AskClaudeState.Failed(R.string.eq_claude_err_nothing)
            return
        }
        _state.value = AskClaudeState.Working
        viewModelScope.launch {
            val key = preferences.claudeApiKey.first()
            val jpeg = picture?.let { uri -> runCatching { withContext(Dispatchers.IO) { smallJpeg(uri) } }.getOrNull() }
            if (picture != null && jpeg == null) {
                _state.value = AskClaudeState.Failed(R.string.eq_claude_err_picture)
                return@launch
            }
            _state.value = when (val result = client.ask(key, jpeg, ClaudeEq.request(wish, current, currentPreamp))) {
                is ClaudeEqClient.Result.Proposed ->
                    if (result.proposal.profile.bands.isEmpty() && result.proposal.summary.isEmpty()) {
                        AskClaudeState.Failed(R.string.eq_claude_err_answer)
                    } else {
                        AskClaudeState.Proposed(result.proposal)
                    }
                ClaudeEqClient.Result.Failed.KEY_REFUSED -> AskClaudeState.Failed(R.string.eq_claude_err_key)
                ClaudeEqClient.Result.Failed.BUSY -> AskClaudeState.Failed(R.string.eq_claude_err_busy)
                ClaudeEqClient.Result.Failed.NETWORK -> AskClaudeState.Failed(R.string.eq_claude_err_network)
                ClaudeEqClient.Result.Failed.DECLINED -> AskClaudeState.Failed(R.string.eq_claude_err_refused)
                ClaudeEqClient.Result.Failed.UNREADABLE -> AskClaudeState.Failed(R.string.eq_claude_err_answer)
            }
        }
    }

    fun reset() {
        _state.value = AskClaudeState.Idle
    }

    /** The picture as a JPEG no longer than [LONG_EDGE] a side: enough to read a graph by, a fraction of a photo's size. */
    private fun smallJpeg(uri: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > LONG_EDGE) {
                val scale = LONG_EDGE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private companion object {
        const val LONG_EDGE = 1568
    }
}

/**
 * Ask Claude to lay out the equaliser: from a picture of a frequency response
 * graph, from what the listener says they want, or by adjusting the filters
 * they have. Claude proposes; nothing changes until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskClaudeEqSheet(
    currentBands: List<EqBand>,
    currentPreamp: Float,
    onApply: (ParsedEqProfile) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AskClaudeEqViewModel = hiltViewModel(),
) {
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var wish by rememberSaveable { mutableStateOf("") }
    var picture by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fromCurrent by rememberSaveable { mutableStateOf(currentBands.isNotEmpty()) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picture = uri
    }

    ModalBottomSheet(onDismissRequest = { viewModel.reset(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.eq_claude_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.eq_claude_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!hasKey) {
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
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) {
                        Text(stringResource(if (picture == null) R.string.eq_claude_pick_image else R.string.eq_claude_image_chosen))
                    }
                    if (picture != null) TextButton(onClick = { picture = null }) { Text(stringResource(R.string.eq_claude_remove_image)) }
                }
                OutlinedTextField(
                    value = wish,
                    onValueChange = { wish = it },
                    label = { Text(stringResource(R.string.eq_claude_wish_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (currentBands.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fromCurrent, onCheckedChange = { fromCurrent = it })
                        Text(stringResource(R.string.eq_claude_send_current), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                when (val s = state) {
                    AskClaudeState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.eq_claude_working), style = MaterialTheme.typography.bodyMedium)
                    }
                    is AskClaudeState.Failed -> Text(
                        stringResource(s.why),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is AskClaudeState.Proposed -> {
                        val profile = s.proposal.profile
                        if (s.proposal.summary.isNotEmpty()) Text(s.proposal.summary, style = MaterialTheme.typography.bodyMedium)
                        if (profile.bands.isNotEmpty()) {
                            Text(
                                stringResource(R.string.eq_claude_result, profile.bands.size, decimal(profile.preamp)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            for (band in profile.bands) {
                                Text(
                                    stringResource(
                                        R.string.eq_claude_band,
                                        band.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) },
                                        decimal(band.freq), decimal(band.gain), decimal(band.q),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    AskClaudeState.Idle -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val proposed = (state as? AskClaudeState.Proposed)?.proposal?.profile?.takeIf { it.bands.isNotEmpty() }
                    if (proposed != null) {
                        Button(onClick = { onApply(proposed); viewModel.reset(); onDismiss() }) {
                            Text(stringResource(R.string.eq_claude_apply))
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.ask(picture, wish, currentBands.takeIf { fromCurrent }, currentPreamp) },
                        enabled = state != AskClaudeState.Working,
                    ) { Text(stringResource(if (proposed == null) R.string.eq_claude_ask else R.string.eq_claude_ask_again)) }
                }
                TextButton(onClick = { viewModel.forgetKey() }) { Text(stringResource(R.string.eq_claude_forget_key)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun decimal(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)
