// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.data.GlyphSortKey
import tf.monochrome.android.ui.navigation.bottomChromePadding
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Every chart this phone has a high score on, as StepMania's machine rankings
 * are: one card a song, its difficulties as glass chips, and the chosen one's
 * ten best runs. Offline: it is this phone's runs and nobody else's.
 *
 * Ordered by the song list's own sort, so the two read alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphLeaderboardScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(GlyphEvent.CloseLeaderboard) }
    val board = state.leaderboard

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "Leaderboard",
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseLeaderboard) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                GlyphSortButton(sort = state.songSort, onPick = { onEvent(GlyphEvent.SortSongs(it)) })
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        when {
            board.loading && board.boards.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            board.boards.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(MonoDimens.spacingXl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No full runs yet. Play a chart through and it goes on the table here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MonoDimens.spacingLg,
                    end = MonoDimens.spacingLg,
                    top = MonoDimens.spacingXs,
                    bottom = bottomChromePadding,
                ),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
            ) {
                items(board.sorted(state.songSort), key = { it.chartId }) { entry ->
                    BoardCard(entry, state.course)
                }
            }
        }
    }
}

@Composable
private fun BoardCard(board: GlyphBoard, course: CourseType) {
    val difficulties = StepManiaDifficulty.entries.filter { it in board.tables }
    // Opens on the difficulty played most recently.
    var chosen by rememberSaveable(board.chartId) {
        mutableStateOf(
            board.tables.maxByOrNull { (_, runs) -> runs.maxOf { it.playedAtEpochMs } }?.key
                ?: difficulties.first(),
        )
    }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = board.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val song = board.song
        Text(
            text = if (song != null) {
                listOf(song.artist, song.formattedDuration, song.bpmLabel)
                    .filter { it.isNotBlank() && it != "—" }
                    .joinToString(" · ")
            } else {
                "No longer in the library"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        if (difficulties.size > 1) {
            GlyphChipRow(
                options = difficulties,
                selected = chosen,
                label = { course.difficultyLabel(it) },
                onSelect = { chosen = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MonoDimens.spacingSm))
        } else {
            GlyphSectionLabel(course.difficultyLabel(chosen))
        }
        GlyphScoreTable(runs = board.tables[chosen].orEmpty())
    }
}

/**
 * The song sort, as the app's other sort menus are: a menu of keys, the
 * chosen one marked with its direction, and choosing it again turning it
 * round. Lit while anything but the default applies.
 */
@Composable
fun GlyphSortButton(sort: GlyphSongSort, onPick: (GlyphSortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort songs, now by ${sort.key.label}",
                tint = if (sort == GlyphSongSort()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GlyphSortKey.entries.forEach { key ->
                val selected = key == sort.key
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = {
                        onPick(key)
                        open = false
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                if (sort.ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = if (sort.ascending) "Ascending" else "Descending",
                                modifier = Modifier.size(MonoDimens.iconSm),
                            )
                        }
                    },
                )
            }
        }
    }
}
