package tf.monochrome.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics

/**
 * Lyrics-mode hero. Extracted from NowPlayingScreen.kt so the JIT compile
 * for `NowPlayingScreenKt.NowPlayingScreen` no longer has to inline the
 * synced/karaoke rendering paths.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LyricsHeroPanel(
    lyrics: Lyrics?,
    isLoading: Boolean,
    coverUrl: String?,
    albumColors: AlbumColors,
    positionMs: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred album cover backdrop. Modifier.blur is API 31+; on older
        // devices the modifier is a silent no-op so the cover still shows,
        // just sharper. Either way we layer a dimming gradient on top so
        // foreground text stays legible.
        if (!coverUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .graphicsLayer { alpha = 0.55f },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            albumColors.dominant.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.78f),
                            albumColors.dominant.copy(alpha = 0.65f),
                        )
                    )
                )
        )

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Loading lyrics…",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            lyrics == null || lyrics.lines.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No lyrics available for this track.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            lyrics.isSynced -> SyncedLyricsView(
                lines = lyrics.lines,
                positionMs = positionMs,
                accent = albumColors.vibrant,
                onSeekTo = onSeekTo,
            )
            else -> UnsyncedLyricsView(lines = lyrics.lines)
        }
    }
}

/**
 * Compact lyrics surface bound to the album-art slot. Unlike [LyricsHeroPanel]
 * (a full-bleed treatment), this is sized by its caller to exactly cover the
 * square cover area and is meant to be crossfaded in as the album art fades
 * out. The surface itself is fully transparent — only the lyric lines show,
 * over the player background — and they dissolve into transparency at the top
 * and bottom edges via a `DstIn` gradient mask.
 */
@Composable
internal fun LyricsHeroBox(
    lyrics: Lyrics?,
    isLoading: Boolean,
    albumColors: AlbumColors,
    positionMs: StateFlow<Long>,
    isPlaying: Boolean,
    speed: Float,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Offscreen compositing is required for the DstIn blend below to
            // mask against the already-drawn lyric content.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = (size.height * 0.16f).coerceAtMost(120f)
                val top = edge / size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        top to Color.Black,
                        1f - top to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Loading lyrics…",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            lyrics == null || lyrics.lines.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No lyrics available for this track.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            lyrics.isSynced -> SyncedLyricsView(
                lines = lyrics.lines,
                positionMs = positionMs,
                accent = albumColors.vibrant,
                onSeekTo = onSeekTo,
                isPlaying = isPlaying,
                speed = speed,
            )
            else -> UnsyncedLyricsView(lines = lyrics.lines)
        }
    }
}

@Composable
internal fun SyncedLyricsView(
    lines: List<LyricLine>,
    positionMs: StateFlow<Long>,
    accent: Color,
    onSeekTo: (Long) -> Unit,
    isPlaying: Boolean = true,
    speed: Float = 1f,
) {
    val sampled by positionMs.collectAsState()
    val listState = rememberLazyListState()
    var currentLineIndex by remember { mutableStateOf(-1) }

    // The polled position only ticks ~4x/sec and isn't speed-scaled between
    // ticks, so the highlight can lag the audio by up to a sample (worse at
    // >1x speed) — which reads as the lyrics drifting out of sync. We smoothly
    // interpolate here: re-anchor on every fresh sample (so we can never
    // cumulatively drift away from the real player clock) and advance by real
    // elapsed time × speed in between.
    var position by remember { mutableStateOf(sampled) }
    LaunchedEffect(sampled, isPlaying, speed, lines) {
        // indexOfLast gives the most recent line whose start has passed —
        // exactly the karaoke "current line" semantics. Cheap on lists of a
        // few hundred lines; no need for binary search.
        fun applyIndex(pos: Long) {
            val newIndex = lines.indexOfLast { it.timeMs <= pos }
            if (newIndex != currentLineIndex && newIndex >= 0) currentLineIndex = newIndex
        }
        if (!isPlaying) {
            position = sampled
            applyIndex(sampled)
            return@LaunchedEffect
        }
        val anchorMs = sampled
        val anchorNs = System.nanoTime()
        while (true) {
            val elapsedMs = (System.nanoTime() - anchorNs) / 1_000_000f
            position = anchorMs + (elapsedMs * speed).toLong()
            applyIndex(position)
            delay(50)
        }
    }

    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        // Coarse pass: pull the active line to roughly the vertical centre
        // (and lay it out so the fine pass below can measure it).
        listState.animateScrollToItem(index = currentLineIndex, scrollOffset = -(viewport / 2))
        // Fine pass: nudge the line's midpoint onto the exact viewport centre.
        val info = listState.layoutInfo
        val target = info.visibleItemsInfo.firstOrNull { it.index == currentLineIndex }
        if (target != null) {
            val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val itemCentre = target.offset + target.size / 2f
            listState.animateScrollBy(itemCentre - viewportCentre)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Half-height padding top and bottom lets any line — including the
        // first and last — settle at the exact vertical centre.
        val halfViewport = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            contentPadding = PaddingValues(top = halfViewport, bottom = halfViewport),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(lines) { index, line ->
            val isActive = index == currentLineIndex
            if (line.words.isNotEmpty()) {
                KaraokeWordLine(
                    line = line,
                    isActive = isActive,
                    position = position,
                    accent = accent,
                    onClick = { onSeekTo(line.timeMs) },
                )
            } else {
                val color by animateColorAsState(
                    targetValue = when {
                        isActive -> accent
                        index < currentLineIndex -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.62f)
                    },
                    label = "lyricColor",
                )
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (isActive) 24.sp else 18.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeekTo(line.timeMs) }
                        .padding(vertical = 6.dp),
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KaraokeWordLine(
    line: LyricLine,
    isActive: Boolean,
    position: Long,
    accent: Color,
    onClick: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        line.words.forEach { word ->
            val isWordActive = position in word.startMs until word.endMs
            val wasWordPlayed = position >= word.endMs
            val color by animateColorAsState(
                targetValue = when {
                    isWordActive -> accent
                    wasWordPlayed && isActive -> accent.copy(alpha = 0.85f)
                    isActive -> Color.White.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.4f)
                },
                label = "wordColor",
            )
            Text(
                text = word.text + " ",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = if (isActive) 24.sp else 18.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                ),
                color = color,
            )
        }
    }
}

@Composable
internal fun UnsyncedLyricsView(lines: List<LyricLine>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentPadding = PaddingValues(vertical = 60.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line.text.ifBlank { "" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}
