package tf.monochrome.android.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.LyricsFxSettings
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lyrics-mode hero. Extracted from NowPlayingScreen.kt so the JIT compile
 * for `NowPlayingScreenKt.NowPlayingScreen` no longer has to inline the
 * synced/karaoke rendering paths.
 */
@Composable
internal fun LyricsHeroPanel(
    lyrics: Lyrics?,
    isLoading: Boolean,
    coverUrl: String?,
    albumColors: AlbumColors,
    positionMs: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // Draw-phase alpha for the blurred-artwork backdrop, so the morph overlay
    // can fade the stain in without recomposing every frame.
    backdropAlpha: () -> Float = { 1f },
    // Bottom fraction kept free of lyric lines (the backdrop still fills it),
    // so the expanded overlay can leave the player's controls readable.
    contentBottomFraction: Float = 0f,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Opaque base: the blurred art + gradient alone are translucent, and
        // as a full-screen overlay that lets the player controls ghost
        // through. Rides backdropAlpha so the morph still starts see-through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha() }
                .background(Color.Black)
        )
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
                    .dithered()
                    .blur(40.dp)
                    .graphicsLayer { alpha = 0.55f * backdropAlpha() },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dithered()
                .graphicsLayer { alpha = backdropAlpha() }
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

        // In the expanded overlay (bottom fraction reserved), also keep lines
        // clear of the status bar and the player's top bar above the panel.
        val chromeInset = if (contentBottomFraction > 0f) {
            Modifier.statusBarsPadding().padding(top = 52.dp)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(chromeInset)
                .fillMaxHeight(1f - contentBottomFraction.coerceIn(0f, 0.9f))
                .lyricsEdgeFade(),
        ) {
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
}

/**
 * Persistent full-screen album-art background (Appearance › "Blurred Album
 * Background"): the cover stretched to fill the whole player and heavily
 * blurred, the way Apple Music / Spotify paint the now-playing page. A soft
 * album-tinted scrim keeps the foreground controls and lyrics legible, and
 * the lyric liquid glass refracts these same album tones so the glass reads
 * as sitting over the artwork.
 */
@Composable
internal fun PlayerBlurredArtBackground(
    coverUrl: String?,
    albumColors: AlbumColors,
    alpha: () -> Float = { 1f },
) {
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        if (!coverUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                // Crop = fill the whole rectangle (stretch to cover), so a square
                // cover blooms across the full portrait screen.
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(64.dp)
                    .dithered(),
            )
        }
        // Album-tinted legibility scrim, Apple-Music style: a deep, moody
        // darkening across the whole background so the stretched art reads as a
        // dim backdrop, not a bright wallpaper. The middle keeps a *darkened*
        // album tone (dominant mixed toward black) for colour without brightness,
        // and the top/bottom fall off darker still for top-bar and transport
        // legibility.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dithered()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.58f),
                            lerp(albumColors.dominant, Color.Black, 0.62f).copy(alpha = 0.52f),
                            Color.Black.copy(alpha = 0.72f),
                        )
                    )
                )
        )
    }
}

/**
 * The album artwork as a full-screen blurred stain: opaque base + blurred
 * cover + dominant-colour gradient. Pure backdrop for expanded lyrics —
 * drawn behind the player content, never a foreground element.
 */
@Composable
internal fun LyricsBackdropStain(
    coverUrl: String?,
    albumColors: AlbumColors,
    alpha: () -> Float,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha() }
                .background(Color.Black)
        )
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
                    .dithered()
                    .blur(40.dp)
                    .graphicsLayer { this.alpha = 0.55f * alpha() },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dithered()
                .graphicsLayer { this.alpha = alpha() }
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
/**
 * Dissolves content into transparency at the top and bottom edges via a
 * DstIn gradient mask. Offscreen compositing is required for the blend to
 * mask against the already-drawn content.
 */
internal fun Modifier.lyricsEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    // drawWithCache builds the mask gradient once per size, not on every draw —
    // and this surface redraws every frame (the glass shader animates).
    .drawWithCache {
        // Thin edge feather only: lines reach the top and bottom borders (like
        // they now reach the side borders) instead of being padded away by a deep
        // fade. Just enough of a feather to soften the scroll clip and the glass
        // shader edge — not a visible top/bottom inset.
        val edge = (size.height * 0.05f).coerceAtMost(14.dp.toPx())
        val top = edge / size.height
        val mask = Brush.verticalGradient(
            0f to Color.Transparent,
            top to Color.Black,
            1f - top to Color.Black,
            1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
    }

@Composable
internal fun LyricsHeroBox(
    lyrics: Lyrics?,
    isLoading: Boolean,
    albumColors: AlbumColors,
    positionMs: StateFlow<Long>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.lyricsEdgeFade()) {
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
) {
    val position by positionMs.collectAsState()
    // Bluetooth sync delay: the audio reaches the ears later than the reported
    // playback position, so lyrics run ahead. Rewinding the position we match
    // against by the delay pushes the whole lyric timeline back into step with
    // what's actually being heard. (Tunable in the Player Visuals Studio.)
    val syncDelayMs = LocalLyricsFx.current.bluetoothDelayMs.toLong()
    // Start composed at the current line so a freshly created instance (the
    // expand morph spawns one) never flashes the top of the song before the
    // centring effect runs.
    val initialLine = remember {
        lines.indexOfLast { it.timeMs <= positionMs.value - syncDelayMs }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLine)
    val lastCentredLine = remember { mutableIntStateOf(initialLine) }

    // Active line = the most recent line whose start has passed. derivedStateOf
    // recomputes when the position sample or the line list changes, but only
    // notifies readers when the index value actually changes — so the highlight
    // and the auto-scroll fire exactly once per line, never on every tick. (An
    // earlier version extrapolated the position between samples, which jittered
    // the index at line boundaries — the highlight looked stuck and the
    // constant re-scroll ate taps. The polled position is stable and accurate.)
    // Keyed on the delay too so retuning it re-selects the active line at once.
    val currentLineIndex by remember(lines, syncDelayMs) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position - syncDelayMs } }
    }

    // Debug log: what's playing (once per song load) and each active-line change.
    LaunchedEffect(lines) {
        val wordLevel = lines.any { it.words.isNotEmpty() }
        LyricsDebug.log("synced lyrics loaded: ${lines.size} lines, ${if (wordLevel) "word-level (karaoke)" else "line-level"}")
    }
    LaunchedEffect(currentLineIndex) {
        val idx = currentLineIndex
        if (idx in lines.indices) {
            LyricsDebug.log("active line $idx/${lines.size}: \"${lines[idx].text.take(48)}\"")
        }
    }

    // Bass-reactive FX for the active line: pulse from the FFT tap, a frame
    // clock for the ray sweep, and a pop-in spring per line change. All
    // disabled (and the analyzer never acquired) at intensity 0.
    val fx = LocalLyricsFx.current
    val beatIntensity = fx.bassReact
    // Prefer the player-provided shared pulse (one analyzer stake; the pump
    // and the full-screen rays breathe together).
    val bassPulse = LocalBeatPulse.current
        ?: if (beatIntensity > 0.01f) rememberBassPulse() else remember { mutableFloatStateOf(0f) }
    // Shared glyph registry — the active line's letters publish their screen
    // positions here and the full-screen LyricsFxLayer (in the player, no
    // clipping ancestor) draws the rays there, so the light can never be cut.
    val glyphAnchors = LocalLyricGlyphAnchors.current
    // Pop-in: snap to 0 and spring (underdamped → overshoot) back to 1 every
    // time the active line changes, so each new line pops in right on the
    // beat that activated it. ~8% size swing keeps it punchy but readable.
    val popIn = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && beatIntensity > 0.01f && fx.popAmount > 0.001f) {
            popIn.snapTo(0f)
            popIn.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = fx.springDampingRatio,
                    stiffness = 500f,
                ),
            )
        }
    }
    val popScale: () -> Float = { (1f - fx.popAmount) + fx.popAmount * popIn.value }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Half-height padding top and bottom lets any line — including the
        // first and last — settle at the exact vertical centre.
        val halfViewport = maxHeight / 2
        // The line width is the same for every item, so read it once here.
        // A fixed bevel-safe inset (on top of the user's edge margin) keeps the
        // outermost glyphs — and their puffy 3D glass bevels — off the layer's
        // clip edge, so edge letters never get corner-cut against the border.
        val sideInset = fx.edgeMarginDp.dp + LYRIC_BEVEL_SAFE_DP
        val lineWidth = (maxWidth - sideInset * 2).coerceAtLeast(0.dp)
        // Headroom for the active line's bass bounce. The line pumps + pops via a
        // graphicsLayer scale (bassBeat), but it lives inside the lyric surface's
        // glass render-layer, which only captures `lineWidth` — so a long line
        // swelling past that would be clipped. Fit width-constrained lines to a
        // slightly narrower box that reserves the PEAK bounce scale, so at full
        // pump they just reach the edge and can never be cut. (The bass pulse
        // caps at ~1.6 in rememberBassPulse; pop-in adds a small overshoot.)
        // Short lines aren't width-constrained, so the fitter leaves them as-is.
        val bounceHeadroom = if (fx.bassReact > 0.01f) {
            val pumpPeak = 1f + fx.pumpAmount * fx.bassReact * 1.6f
            val popPeak = 1f + fx.popAmount * 0.25f
            (pumpPeak * popPeak).coerceIn(1f, 2f)
        } else {
            1f
        }
        val fitWidth = lineWidth / bounceHeadroom

        // Keyed on maxHeight as well so the active line is re-centred while
        // the surface is being resized (the expand/collapse morph animates
        // the viewport every frame).
        LaunchedEffect(currentLineIndex, maxHeight) {
            val index = currentLineIndex
            if (index < 0) return@LaunchedEffect
            // Bring the line on-screen first only if it's far away (a big seek);
            // during normal playback the next active line is already visible just
            // below centre, so this is skipped. A plain scrollToItem (no offset)
            // is enough — the centring below does the rest. The previous version
            // also ran a coarse animateScrollToItem(index, -(viewport/2)) which,
            // combined with the half-viewport top padding, scrolled the line a
            // full viewport ABOVE the viewport (offset -H) and then couldn't find
            // it to centre — the line vanished off the top from ~the 3rd line on.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
            }
            val info = listState.layoutInfo
            val target = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
            val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val itemCentre = target.offset + target.size / 2f
            // Snap when re-centring the same line (first composition, or the
            // morph resizing the viewport every frame); animate only when the
            // song has actually advanced to a new line.
            if (lastCentredLine.intValue == index) {
                listState.scrollBy(itemCentre - viewportCentre)
            } else {
                listState.animateScrollBy(itemCentre - viewportCentre)
            }
            lastCentredLine.intValue = index
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // User edge margin + a fixed bevel-safe inset, so the outermost
                // glyphs (and their glass bevels) never sit flush against the
                // clip edge where they'd be corner-cut.
                .padding(horizontal = sideInset)
                .fxaa()
                .liquidGlass(tint = accent),
            contentPadding = PaddingValues(top = halfViewport, bottom = halfViewport),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines) { index, line ->
            // Each line gets ONE row: the font is fitted to the available width so
            // a long line reaches toward the screen edges (shrinking if it must)
            // instead of wrapping or being cut off inside the compact player.
            val isActive = index == currentLineIndex
            val isPast = index < currentLineIndex
            // Bass FX rides only the active line: the line pumps + pops
            // (bassBeat) and reports its bounds; each glyph reports its screen
            // position so the full-screen layer can draw per-letter rays.
            val lineAnchors = if (isActive) glyphAnchors else null
            val beatModifier = if (isActive && beatIntensity > 0.01f) {
                Modifier.bassBeat(bassPulse, popScale, fx, lineAnchors)
            } else {
                Modifier
            }
            // Line index scopes glyph keys so an advancing active line's glyphs
            // never collide with the outgoing one's during the transition.
            val glyphKeyBase = index * 4096
            val lyricFont = rememberLyricFontFamily(fx)
            val weight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium
            // One-row fit. lineHeight stays pinned to the BASE size so the row
            // height never moves with the fitted size — the list never reflows.
            val fittedSp = rememberFittedLyricSizeSp(
                text = line.text.ifBlank { "♪" },
                availableWidth = fitWidth,
                baseSp = fx.fontSizeSp,
                style = MaterialTheme.typography.titleMedium.copy(
                    letterSpacing = fx.letterSpacingSp.sp,
                    fontWeight = weight,
                ).withLyricFont(lyricFont),
                maxRows = fx.maxWrapLines,
            )
            if (line.words.isNotEmpty()) {
                // Only sources with real per-word timing (TIDAL enhanced LRC)
                // illuminate word-by-word.
                KaraokeLyricLine(
                    line = line,
                    isActive = isActive,
                    isPast = isPast,
                    // Word-level highlight rides the same Bluetooth-adjusted clock.
                    position = position - syncDelayMs,
                    accent = accent,
                    onClick = { onSeekTo(line.timeMs) },
                    beatModifier = beatModifier,
                    anchors = lineAnchors,
                    glyphKeyBase = glyphKeyBase,
                    fontSizeSp = fittedSp,
                )
            } else {
                // Line-level sources (LRCLib / Qobuz): illuminate the whole
                // active line at once.
                val color by animateColorAsState(
                    targetValue = when {
                        // Full-opacity fill: the glass transparency is applied by
                        // the liquidGlass() shader (which owns the see-through body
                        // and the bright rim), so the letter is handed to it solid.
                        isActive -> accent
                        isPast -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.62f)
                    },
                    label = "lyricColor",
                )
                val lineStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = fittedSp.sp,
                    lineHeight = (fx.fontSizeSp * 1.26f).sp,
                    letterSpacing = fx.letterSpacingSp.sp,
                    fontWeight = weight,
                ).withLyricFont(lyricFont)
                val lineModifier = beatModifier
                    .fillMaxWidth()
                    .clickable { onSeekTo(line.timeMs) }
                    .padding(vertical = 2.dp)
                // Render letters individually whenever the 3D wave is active.
                val perLetter = isActive && fx.rotationDegrees > 0.05f
                if (perLetter) {
                    Letters3DLine(
                        text = line.text.ifBlank { "♪" },
                        style = lineStyle,
                        color = color,
                        modifier = lineModifier,
                        anchors = lineAnchors,
                        glyphKeyBase = glyphKeyBase,
                        fontSizeSp = fittedSp,
                    )
                } else {
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = lineStyle,
                        color = color,
                        textAlign = TextAlign.Center,
                        maxLines = fx.maxWrapLines,
                        softWrap = fx.maxWrapLines > 1,
                        overflow = TextOverflow.Clip,
                        modifier = lineModifier,
                    )
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KaraokeLyricLine(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    position: Long,
    accent: Color,
    onClick: () -> Unit,
    beatModifier: Modifier = Modifier,
    anchors: LyricGlyphAnchors? = null,
    glyphKeyBase: Int = 0,
    fontSizeSp: Float = 23f,
) {
    // Render letters individually while active whenever the 3D wave is on.
    // One frame clock per line.
    val fx = LocalLyricsFx.current
    val perLetter = isActive && fx.rotationDegrees > 0.05f
    val time = if (perLetter) rememberFrameSeconds() else null
    // Font + base style are identical for every word — build them ONCE per line
    // instead of per word inside the loop (only the colour varies per word).
    val lyricFont = rememberLyricFontFamily(fx)
    val wordStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fx.fontSizeSp * 1.26f).sp,
        letterSpacing = fx.letterSpacingSp.sp,
        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
    ).withLyricFont(lyricFont)
    // One row when maxWrapLines == 1, else a FlowRow that wraps between words.
    val lineModifier = beatModifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 2.dp)
    val content: @Composable () -> Unit = {
        var letterBase = 0
        line.words.forEach { word ->
            // Solid fills: the liquidGlass() shader turns the active line into
            // see-through glass, so the karaoke colours are handed to it opaque.
            val target = when {
                !isActive -> if (isPast) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.6f)
                position >= word.endMs -> accent.copy(alpha = 0.9f)   // already sung
                position >= word.startMs -> accent                    // lighting up now
                else -> Color.White.copy(alpha = 0.45f)               // not yet reached
            }
            val color by animateColorAsState(targetValue = target, label = "wordColor")
            val display = word.text + " "
            if (time != null) {
                val phaseBase = letterBase
                Row(horizontalArrangement = Arrangement.spacedBy(letterGapDp(fx, fontSizeSp))) {
                    display.forEachIndexed { j, ch ->
                        val idx = phaseBase + j
                        Letter3DText(
                            text = ch.toString(),
                            // Low spatial frequency: neighbouring letters stay
                            // nearly in phase, so the line reads as one long
                            // smooth ribbon; the step is a Studio setting.
                            phase = idx * fx.wavePhaseStep,
                            style = wordStyle.copy(shadow = letter3DShadow(fx.shadowDepth)),
                            color = color,
                            time = time,
                            anchors = anchors,
                            glyphKey = glyphKeyBase + idx,
                        )
                    }
                }
            } else {
                Text(
                    text = display,
                    style = wordStyle,
                    color = color,
                )
            }
            letterBase += display.length
        }
    }
    if (fx.maxWrapLines > 1) {
        FlowRow(modifier = lineModifier, horizontalArrangement = Arrangement.Center) { content() }
    } else {
        Row(modifier = lineModifier, horizontalArrangement = Arrangement.Center) { content() }
    }
}

val LocalLyricsFx = compositionLocalOf { LyricsFxSettings() }

/**
 * The imported font chosen for the lyrics, or null when the custom-font toggle
 * is off / the file is missing (callers then keep the app theme typeface). Built
 * from the same filesDir/custom_fonts store the Appearance settings import into.
 */
@Composable
internal fun rememberLyricFontFamily(fx: LyricsFxSettings): FontFamily? {
    if (!fx.customFont || fx.customFontPath.isBlank()) return null
    val path = fx.customFontPath
    return remember(path) {
        runCatching {
            val file = java.io.File(path)
            if (file.exists()) {
                LyricsDebug.log("custom font loaded: ${file.name}")
                FontFamily(Font(file))
            } else {
                LyricsDebug.log("custom font MISSING, falling back to theme: $path")
                null
            }
        }.onFailure { LyricsDebug.log("custom font FAILED to load ($path): ${it.message}") }
            .getOrNull()
    }
}

/** Applies [family] to this style only when non-null, so the theme font stays the default. */
internal fun TextStyle.withLyricFont(family: FontFamily?): TextStyle =
    if (family != null) copy(fontFamily = family) else this

/** Smallest lyric font we'll shrink to before a line is simply allowed to run wide. */
private const val MIN_LYRIC_SP = 11f

/**
 * Fixed side inset that keeps the outermost glyphs (and their puffy 3D glass
 * bevels) clear of the lyric layer's clip edge, so edge letters are never
 * corner-cut against the screen border. Added on top of the user's edge margin.
 */
private val LYRIC_BEVEL_SAFE_DP = 14.dp

/**
 * The largest font size (≤ [baseSp], down to [MIN_LYRIC_SP]) at which [text]
 * still fits on ONE line within [availableWidth] — so every lyric line renders
 * on a single row and reaches toward the screen edges without being cut, instead
 * of wrapping inside the compact player. Text width scales ~linearly with size,
 * so one measurement at the base size is enough to scale down. The slack factor
 * leaves room for the per-letter 3D swell and inter-letter gaps so the
 * single-row 3D path fits too.
 */
@Composable
internal fun rememberFittedLyricSizeSp(
    text: String,
    availableWidth: Dp,
    baseSp: Float,
    style: TextStyle,
    maxRows: Int = 1,
): Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, availableWidth, baseSp, maxRows, style.fontWeight, style.letterSpacing, style.fontFamily) {
        if (text.isBlank()) return@remember baseSp
        // Capacity is the total horizontal run the text may occupy across maxRows
        // rows; if it exceeds that at the base size, shrink to fit (so a line wraps
        // up to maxRows rows, then shrinks rather than wrapping further or clipping).
        val capacity = with(density) { availableWidth.toPx() } * maxRows.coerceAtLeast(1) * 0.94f
        if (capacity <= 0f) return@remember baseSp
        val measured = measurer.measure(
            text = text,
            style = style.copy(fontSize = baseSp.sp),
            maxLines = 1,
            softWrap = false,
        ).size.width.toFloat()
        if (measured <= capacity) baseSp
        else (baseSp * (capacity / measured)).coerceIn(MIN_LYRIC_SP, baseSp)
    }
}

// Crisp contact shadow: a tight, dark edge right under the glyph so the
// letterform reads as solid and sharp. (The previous wide blur — up to ~17px —
// hazed the glyph edges and made the whole line look soft; block depth now
// comes from the extruded backing glyph in Letter3DText instead.)
private fun letter3DShadow(depth: Float) = Shadow(
    color = Color.Black.copy(alpha = (0.5f + 0.4f * depth).coerceIn(0f, 1f)),
    offset = Offset(0f, 2f + 5f * depth),
    blurRadius = 1f + 4f * depth,
)

/**
 * Horizontal breathing room laid out between adjacent per-letter 3D glyphs so
 * they can never bleed into one another. Each [Letter3DText] swells toward the
 * viewer (up to ~9% of its size per unit of ripple intensity) and stamps an
 * extruded backing a couple of pixels to the right — both inside its own
 * transform layer. CJK glyphs fill their advance box with no side bearing, so
 * without a gap that peak swell plus the backing pushed neighbouring characters
 * on top of each other. The gap is exactly the peak horizontal overflow, so at
 * rest the line stays tight and at full swing the letters just kiss.
 */
private fun letterGapDp(fx: LyricsFxSettings, fontSizeSp: Float = fx.fontSizeSp): Dp {
    val intensity = (fx.rotationDegrees / 9f).coerceAtMost(2.5f)
    // fontSize * peakSwell = total width the glyph gains at the toward-viewer
    // peak; +1.5dp covers the extruded backing (offset 1.2dp, scaled by depth).
    // Sized off the ACTUAL (fitted) glyph size so a shrunk line's gaps shrink too.
    return (fontSizeSp * 0.09f * intensity + 1.5f).dp
}

/**
 * Active-line treatment: each character is its own Text inside a per-letter
 * 3D transform — a ripple of rotation travelling along the line — with a
 * baked-in drop shadow for depth. The whole line stays on ONE row (no wrap);
 * the caller fits [style]'s font size to the width. The transforms are
 * draw-only: layout, font size and spacing never change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Letters3DLine(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    anchors: LyricGlyphAnchors? = null,
    glyphKeyBase: Int = 0,
    // The fitted size the caller used for [style], so the inter-letter gap scales
    // with the actual glyph size (passed explicitly rather than read off [style]).
    fontSizeSp: Float = 23f,
) {
    val fx = LocalLyricsFx.current
    val time = rememberFrameSeconds()
    val shadowed = style.copy(shadow = letter3DShadow(fx.shadowDepth))
    // Word-wise: each word's letters stay together in a Row; the outer container
    // holds the words. A single row when maxWrapLines == 1, else a FlowRow that
    // wraps between words up to that many rows (the fit sizes it to that budget).
    val words = remember(text) { text.split(" ") }
    val content: @Composable () -> Unit = {
        var letterBase = 0
        words.forEachIndexed { index, word ->
            val display = if (index < words.lastIndex) "$word " else word
            val phaseBase = letterBase
            Row(horizontalArrangement = Arrangement.spacedBy(letterGapDp(fx, fontSizeSp))) {
                display.forEachIndexed { j, ch ->
                    val idx = phaseBase + j
                    Letter3DText(
                        text = ch.toString(),
                        phase = idx * fx.wavePhaseStep,
                        style = shadowed,
                        color = color,
                        time = time,
                        anchors = anchors,
                        glyphKey = glyphKeyBase + idx,
                    )
                }
            }
            letterBase += display.length
        }
    }
    if (fx.maxWrapLines > 1) {
        FlowRow(modifier = modifier, horizontalArrangement = Arrangement.Center) { content() }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.Center) { content() }
    }
}

@Composable
private fun Letter3DText(
    text: String,
    phase: Float,
    style: TextStyle,
    color: Color,
    time: State<Float>,
    anchors: LyricGlyphAnchors? = null,
    glyphKey: Int = 0,
) {
    val fx = LocalLyricsFx.current
    Box(
        // Report this glyph's screen position (behind the 3D transform) so the
        // full-screen LyricsFxLayer can shoot its rays from here — uncut.
        modifier = Modifier
            .reportGlyphAnchor(anchors, glyphKey, phase)
            .graphicsLayer {
            val t = time.value * fx.waveSpeed
            // Slow temporal frequency (1.5 rad/s): the wave glides instead of
            // flickering — the eye tracks a slow ripple as one smooth motion.
            val p = t * 1.5f + phase
            val intensity = (fx.rotationDegrees / 9f).coerceAtMost(2.5f)
            // Ribbon model: letters ride the wave, tilt to follow its slope
            // (rotationX = the wave's derivative), twist slowly around Y, and
            // swell slightly on the toward-viewer swing. Keeping every term
            // phase-locked to the same wave is what makes it read as 3D depth
            // instead of independent flat jiggles.
            translationY = fx.waveTravelDp.dp.toPx() * sin(p)
            rotationX = -(fx.rotationDegrees * 1.6f) * cos(p)
            rotationY = (fx.rotationDegrees * 0.9f) * sin(t * 0.8f + phase * 0.5f)
            val depth = 1f + 0.09f * intensity * cos(p)
            scaleX = depth
            scaleY = depth
            // Short camera distance = strong perspective on the tilts.
            cameraDistance = 4f * density
        },
    ) {
        // Extruded backing: the same glyph stamped in near-black a couple of
        // pixels down-right, inside the same transform layer so it tilts with
        // the letter. The pair reads as one solid letterform with block
        // depth — not a glyph plus a detached shadow. Layout offset, NOT a
        // second graphicsLayer: an extra render node per letter doubled the
        // per-frame layer updates on long lines and cost visible frames.
        Text(
            text = text,
            style = style.copy(shadow = null),
            color = Color.Black.copy(alpha = (0.45f + 0.4f * fx.shadowDepth).coerceIn(0f, 0.9f)),
            modifier = Modifier.offset(x = 1.2.dp, y = 2.2.dp),
        )
        Text(text = text, style = style, color = color)
    }
}

@Composable
internal fun UnsyncedLyricsView(lines: List<LyricLine>) {
    LaunchedEffect(lines) { LyricsDebug.log("unsynced lyrics loaded: ${lines.size} lines (no timing)") }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .fxaa()
            .liquidGlass(),
        contentPadding = PaddingValues(vertical = 60.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            val fx = LocalLyricsFx.current
            Text(
                text = line.text.ifBlank { "" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fx.fontSizeSp.sp,
                    lineHeight = (fx.fontSizeSp * 1.26f).sp,
                ).withLyricFont(rememberLyricFontFamily(fx)),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
