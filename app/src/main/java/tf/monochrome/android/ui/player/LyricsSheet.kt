package tf.monochrome.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    lyrics: Lyrics?,
    isLoading: Boolean,
    positionMs: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.mode_lyrics),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                lyrics == null || lyrics.lines.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.lyrics_none_short),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                lyrics.isSynced -> {
                    SyncedLyrics(
                        lines = lyrics.lines,
                        positionMs = positionMs,
                        onSeekTo = onSeekTo
                    )
                }
                else -> {
                    UnsyncedLyrics(lines = lyrics.lines)
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: StateFlow<Long>,
    onSeekTo: (Long) -> Unit
) {
    val position by positionMs.collectAsStateWithLifecycle()
    // Bluetooth sync delay (tunable in the Player Visuals Studio): audio lands later
    // than the reported position over Bluetooth, so rewind the clock we match
    // lyrics against to keep them in step with what's heard. Wall-time latency,
    // so scaled into song time by the playback speed.
    val syncDelayMs = LyricClock.delayInSongMs(LocalLyricsFx.current.bluetoothDelayMs, LocalPlaybackSpeed.current)
    val lyricFont = rememberLyricFontFamily(LocalLyricsFx.current)
    val fontSizeSp = LocalLyricsFx.current.fontSizeSp
    val listState = rememberLazyListState()
    var currentLineIndex by remember { mutableIntStateOf(-1) }

    // Find current line based on the Bluetooth-adjusted playback position.
    LaunchedEffect(position, syncDelayMs) {
        val newIndex = lines.indexOfLast { it.timeMs <= position - syncDelayMs }
        // Allow -1 through: seeking back before the first lyric must clear the
        // highlight, not leave the previous line lit. The auto-scroll effect
        // already ignores negative indices.
        if (newIndex != currentLineIndex) {
            currentLineIndex = newIndex
        }
    }

    // Track the last time the user dragged the list, so auto-scroll backs off
    // while they're reading ahead instead of yanking the list to the current
    // line on every timestamp change.
    var lastUserScrollMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                lastUserScrollMs = System.currentTimeMillis()
            }
        }
    }

    // Auto-scroll to current line, unless the user scrolled in the last few
    // seconds.
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && System.currentTimeMillis() - lastUserScrollMs > 4_000L) {
            listState.animateScrollToItem(
                index = currentLineIndex,
                scrollOffset = -200 // Offset to center the line
            )
        }
    }

    // Measured in Bold, the weight a line takes when it lights up, so lighting
    // up never changes a line's size.
    val measureStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold).withLyricFont(lyricFont)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowWidth = maxWidth
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fxaa()
                .liquidGlass(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                val isActive = index == currentLineIndex
            
                if (line.words.isNotEmpty()) {
                    KaraokeLine(
                        line = line,
                        isActive = isActive,
                        position = position - syncDelayMs,
                        rowWidth = rowWidth,
                        onClick = { onSeekTo(line.timeMs) }
                    )
                } else {
                    // The Studio's size, unless a word alone would not fit the row.
                    val sp = rememberWordFitSp(line.text.ifBlank { "♪" }, rowWidth, fontSizeSp, measureStyle)
                    val textColor by animateColorAsState(
                        targetValue = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            index < currentLineIndex -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                        label = "lyricColor"
                    )

                    Text(
                        text = line.text.ifBlank { "♪" },
                        // The line keeps its size when it lights up (measured in
                        // Bold above); only colour and weight mark the active line.
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = sp.sp,
                            lineHeight = (sp * LINE_HEIGHT_RATIO).sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        ).withLyricFont(lyricFont),
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeekTo(line.timeMs) }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KaraokeLine(
    line: LyricLine,
    isActive: Boolean,
    position: Long,
    rowWidth: Dp,
    onClick: () -> Unit
) {
    val lyricFont = rememberLyricFontFamily(LocalLyricsFx.current)
    val measureStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold).withLyricFont(lyricFont)
    // One size for the whole line, so its words stay one size.
    val lineText = remember(line.words) { line.words.joinToString(" ") { it.text } }
    val fontSizeSp = rememberWordFitSp(lineText, rowWidth, LocalLyricsFx.current.fontSizeSp, measureStyle)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center
    ) {
        line.words.forEach { word ->
            val isWordActive = position >= word.startMs && position < word.endMs
            val wasWordPlayed = position >= word.endMs
            
            val color by animateColorAsState(
                targetValue = when {
                    isWordActive -> MaterialTheme.colorScheme.primary
                    wasWordPlayed && isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    isActive -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                label = "wordColor"
            )

            Text(
                text = word.text + " ",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * LINE_HEIGHT_RATIO).sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                ).withLyricFont(lyricFont),
                color = color
            )
        }
    }
}

@Composable
private fun UnsyncedLyrics(lines: List<LyricLine>) {
    val lyricFont = rememberLyricFontFamily(LocalLyricsFx.current)
    val fontSizeSp = LocalLyricsFx.current.fontSizeSp
    val style = MaterialTheme.typography.bodyLarge.withLyricFont(lyricFont)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowWidth = maxWidth
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fxaa()
                .liquidGlass(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(lines) { _, line ->
                val sp = rememberWordFitSp(line.text, rowWidth, fontSizeSp, style)
                Text(
                    text = line.text.ifBlank { "" },
                    style = style.copy(fontSize = sp.sp, lineHeight = (sp * LINE_HEIGHT_RATIO).sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}

/** Line height over font size, the player lyrics' ratio: 23 sp gives the sheet's old fixed 29 sp. */
private const val LINE_HEIGHT_RATIO = 1.26f
