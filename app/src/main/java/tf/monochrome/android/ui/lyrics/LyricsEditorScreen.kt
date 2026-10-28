// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.lyrics

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.R
import tf.monochrome.android.data.api.LrcFormat
import tf.monochrome.android.data.repository.LyricsStage
import tf.monochrome.android.data.repository.ServerLyricsStatus
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.ui.glyph.GlyphButton
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.settings.FxNudge
import tf.monochrome.android.ui.settings.FxToggle
import tf.monochrome.android.ui.settings.StudioPreview
import tf.monochrome.android.ui.settings.StudioSection

/**
 * The lyrics editor: the playing song's words, each line and word given its
 * time by hand, built as the Player Visuals Studio is: the real lyric
 * renderer in a condensed preview up top, the transport and the stamp
 * button under it, then the lines, then every setting in the Studio's own
 * sections. See docs/ui-invariants.md, "Lyrics".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LyricsEditorScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: LyricsEditorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val track by viewModel.track.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val selectedLine by viewModel.selectedLine.collectAsStateWithLifecycle()
    val selectedWord by viewModel.selectedWord.collectAsStateWithLifecycle()
    val wordMode by viewModel.wordMode.collectAsStateWithLifecycle()
    val stepMs by viewModel.stepMs.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val fx by viewModel.lyricsFx.collectAsStateWithLifecycle()
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    // Held as State: the play head ticks four times a second, and more at
    // lyric boundaries. Read here it recomposed the whole editor for the
    // transport's clock alone. The transport reads it, and a stamp reads it
    // when it is pressed.
    val positionState = playerViewModel.positionMs.collectAsStateWithLifecycle()
    val duration by playerViewModel.durationMs.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary

    var leaving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var inserting by remember { mutableStateOf<Int?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<Int?>(null) }

    fun leave() {
        if (dirty) leaving = true else navController.popBackStackSafe()
    }
    BackHandler { leave() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.lyrics_editor_title)) },
            navigationIcon = {
                IconButton(onClick = { leave() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            actions = {
                IconButton(onClick = viewModel::undo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.lyrics_editor_undo))
                }
                IconButton(onClick = viewModel::redo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.lyrics_editor_redo))
                }
                IconButton(onClick = viewModel::save, enabled = dirty) {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.lyrics_editor_save), tint = if (dirty) accent else Color.Unspecified)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.lyrics_editor_no_song), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        // Pinned: the real renderer, condensed, playing the copy as it is.
        Column(Modifier.padding(horizontal = 16.dp)) {
            StudioPreview(fx = fx, lyrics = draft, positionMs = viewModel.positionMs, analyzer = playerViewModel.spectrumAnalyzer)
            Spacer(Modifier.height(8.dp))
            Transport(
                isPlaying = isPlaying,
                position = positionState,
                durationMs = duration,
                onTogglePlay = playerViewModel::togglePlayPause,
                onSeek = playerViewModel::seekTo,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlyphButton(
                    text = stringResource(R.string.lyrics_editor_stamp),
                    onClick = { viewModel.stamp(positionState.value) },
                    primary = true,
                    enabled = !draft?.lines.isNullOrEmpty(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.nudgeSelected(-stepMs) }) { Text("−$stepMs") }
                TextButton(onClick = { viewModel.nudgeSelected(stepMs) }) { Text("+$stepMs") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                GlassChoiceChip(stringResource(R.string.lyrics_editor_line_mode), selected = !wordMode, accent = accent, onClick = { viewModel.wordMode.value = false })
                Spacer(Modifier.width(6.dp))
                GlassChoiceChip(stringResource(R.string.lyrics_editor_word_mode), selected = wordMode, accent = accent, onClick = { viewModel.wordMode.value = true })
                Spacer(Modifier.width(14.dp))
                for (step in listOf(10L, 50L, 100L, 500L)) {
                    GlassChoiceChip("$step ms", selected = stepMs == step, accent = accent, onClick = { viewModel.stepMs.value = step })
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        val lines = draft?.lines.orEmpty()
        val outOfOrder = remember(draft) { draft?.let { LyricsEdit.outOfOrder(it) } ?: emptySet() }
        val listState = rememberLazyListState()
        LaunchedEffect(selectedLine) {
            if (selectedLine in lines.indices) listState.animateScrollToItem(selectedLine)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 48.dp + LocalBottomChromeInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.lyrics_editor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                LineRow(
                    line = line,
                    selected = i == selectedLine,
                    outOfOrder = i in outOfOrder,
                    accent = accent,
                    onTap = {
                        viewModel.select(i)
                        playerViewModel.seekTo(line.timeMs)
                    },
                    onHold = { rowMenu = i },
                )
                if (i == selectedLine && wordMode && line.words.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                        line.words.forEachIndexed { w, word ->
                            GlassChoiceChip(
                                label = "${word.text} ${LrcFormat.stamp(word.startMs).drop(3)}",
                                selected = w == selectedWord,
                                accent = accent,
                                onClick = {
                                    viewModel.select(i, w)
                                    playerViewModel.seekTo(word.startMs)
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            item {
                StudioSection(stringResource(R.string.lyrics_editor_timing))
                FxToggle(
                    label = stringResource(R.string.lyrics_editor_word_mode),
                    checked = wordMode,
                    description = stringResource(R.string.lyrics_editor_word_mode_hint),
                    onChange = { viewModel.wordMode.value = it },
                )
                FxNudge(label = stringResource(R.string.lyrics_editor_shift_all), step = stepMs.toFloat(), unit = " ms") { viewModel.shiftAll(it.toLong()) }
                if (selectedLine in lines.indices) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (lines[selectedLine].words.isEmpty()) {
                            TextButton(onClick = { viewModel.splitWords(selectedLine) }) { Text(stringResource(R.string.lyrics_editor_split_words)) }
                        } else {
                            TextButton(onClick = { viewModel.clearWords(selectedLine) }) { Text(stringResource(R.string.lyrics_editor_clear_words)) }
                        }
                        TextButton(onClick = { editing = selectedLine }) { Text(stringResource(R.string.lyrics_editor_edit_text)) }
                        TextButton(onClick = { inserting = selectedLine }) { Text(stringResource(R.string.lyrics_editor_insert_line)) }
                        TextButton(onClick = { viewModel.deleteLine(selectedLine) }) { Text(stringResource(R.string.lyrics_editor_delete_line)) }
                    }
                }

                StudioSection(stringResource(R.string.lyrics_editor_text))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { pasting = true }) { Text(stringResource(R.string.lyrics_editor_paste)) }
                    TextButton(onClick = { inserting = lines.size - 1 }) { Text(stringResource(R.string.lyrics_editor_add_line)) }
                }

                StudioSection(stringResource(R.string.lyrics_editor_file))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            val lrc = viewModel.lrcText() ?: return@TextButton
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, (track?.title ?: "lyrics") + ".lrc")
                                putExtra(Intent.EXTRA_TEXT, lrc)
                            }
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.lyrics_editor_export)))
                        },
                        enabled = lines.isNotEmpty(),
                    ) { Text(stringResource(R.string.lyrics_editor_export)) }
                    if (hasServer) {
                        val busy = serverStatus is ServerLyricsStatus.Working || serverStatus == ServerLyricsStatus.Waiting
                        if (busy) {
                            TextButton(onClick = viewModel::cancelServer) { Text(stringResource(R.string.lyrics_server_cancel)) }
                        } else {
                            TextButton(onClick = viewModel::timeOnServer) { Text(stringResource(R.string.lyrics_server_sync)) }
                        }
                    }
                    TextButton(onClick = viewModel::forgetSaved) { Text(stringResource(R.string.lyrics_editor_forget)) }
                }
                serverStatusText(serverStatus)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                note?.let {
                    val text = when (it) {
                        EditorNote.SAVED -> stringResource(R.string.lyrics_editor_saved)
                        EditorNote.NOTHING_TO_SAVE -> stringResource(R.string.lyrics_editor_nothing_to_save)
                        EditorNote.FORGOTTEN -> stringResource(R.string.lyrics_editor_forgotten)
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall, color = accent, modifier = Modifier.padding(top = 8.dp))
                    LaunchedEffect(it) {
                        kotlinx.coroutines.delay(4_000)
                        viewModel.clearNote()
                    }
                }
            }
        }
    }

    editing?.let { index ->
        val line = lines(draft)[index]
        TextDialog(
            title = stringResource(R.string.lyrics_editor_edit_text),
            initial = line.text,
            onDismiss = { editing = null },
        ) { text ->
            viewModel.setText(index, text)
            editing = null
        }
    }
    inserting?.let { index ->
        TextDialog(
            title = stringResource(R.string.lyrics_editor_insert_line),
            initial = "",
            onDismiss = { inserting = null },
        ) { text ->
            viewModel.insertAfter(index, text, positionState.value)
            inserting = null
        }
    }
    if (pasting) {
        TextDialog(
            title = stringResource(R.string.lyrics_editor_paste),
            initial = "",
            hint = stringResource(R.string.lyrics_editor_paste_hint),
            onDismiss = { pasting = false },
        ) { text ->
            viewModel.paste(text)
            pasting = false
        }
    }
    rowMenu?.let { index ->
        AlertDialog(
            onDismissRequest = { rowMenu = null },
            title = { Text(lines(draft).getOrNull(index)?.text.orEmpty(), maxLines = 2) },
            text = {
                Column {
                    TextButton(onClick = { editing = index; rowMenu = null }) { Text(stringResource(R.string.lyrics_editor_edit_text)) }
                    TextButton(onClick = { inserting = index; rowMenu = null }) { Text(stringResource(R.string.lyrics_editor_insert_line)) }
                    if (lines(draft).getOrNull(index)?.words.isNullOrEmpty()) {
                        TextButton(onClick = { viewModel.splitWords(index); rowMenu = null }) { Text(stringResource(R.string.lyrics_editor_split_words)) }
                    } else {
                        TextButton(onClick = { viewModel.clearWords(index); rowMenu = null }) { Text(stringResource(R.string.lyrics_editor_clear_words)) }
                    }
                    TextButton(onClick = { viewModel.deleteLine(index); rowMenu = null }) { Text(stringResource(R.string.lyrics_editor_delete_line)) }
                }
            },
            confirmButton = { TextButton(onClick = { rowMenu = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.lyrics_editor_discard_title)) },
            confirmButton = {
                TextButton(onClick = { leaving = false; navController.popBackStackSafe() }) { Text(stringResource(R.string.lyrics_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.lyrics_editor_keep_editing)) }
            },
        )
    }
}

private fun lines(draft: tf.monochrome.android.domain.model.Lyrics?): List<LyricLine> = draft?.lines.orEmpty()

@Composable
private fun serverStatusText(status: ServerLyricsStatus?): String? = when (status) {
    ServerLyricsStatus.Waiting -> stringResource(R.string.lyrics_server_waiting)
    is ServerLyricsStatus.Working -> when (status.stage) {
        LyricsStage.DOWNLOADING -> stringResource(R.string.lyrics_server_downloading)
        LyricsStage.SENDING -> stringResource(R.string.lyrics_server_sending, status.host)
        LyricsStage.HEARING -> stringResource(R.string.lyrics_server_hearing, status.host)
    }
    is ServerLyricsStatus.Failed -> stringResource(R.string.lyrics_server_failed, status.reason)
    ServerLyricsStatus.NoFile -> stringResource(R.string.lyrics_server_no_file)
    ServerLyricsStatus.NoServer -> stringResource(R.string.lyrics_server_no_server)
    null -> null
}

@Composable
private fun Transport(isPlaying: Boolean, position: State<Long>, durationMs: Long, onTogglePlay: () -> Unit, onSeek: (Long) -> Unit) {
    val positionMs = position.value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onTogglePlay) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
        }
        Text(LrcFormat.stamp(positionMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { if (durationMs > 0) onSeek((it * durationMs).toLong()) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(LrcFormat.stamp(durationMs), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(line: LyricLine, selected: Boolean, outOfOrder: Boolean, accent: Color, onTap: () -> Unit, onHold: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            LrcFormat.stamp(line.timeMs),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            color = if (outOfOrder) MaterialTheme.colorScheme.error else if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outOfOrder) {
                Text(stringResource(R.string.lyrics_editor_out_of_order), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            } else if (line.words.isNotEmpty()) {
                Text("${line.words.size} ${stringResource(R.string.lyrics_editor_words_timed)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, hint: String? = null, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions.Default,
                minLines = if (hint != null) 6 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
