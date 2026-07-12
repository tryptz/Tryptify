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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
@OptIn(ExperimentalLayoutApi::class)
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
    .drawWithContent {
        drawContent()
        // Density-aware fade depth. The old cap was 120 raw PIXELS — ~40dp on
        // a 3x panel — so lines sliced off at what looked like an invisible
        // border instead of dissolving.
        val edge = (size.height * 0.18f).coerceAtMost(56.dp.toPx())
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
    // Start composed at the current line so a freshly created instance (the
    // expand morph spawns one) never flashes the top of the song before the
    // centring effect runs.
    val initialLine = remember { lines.indexOfLast { it.timeMs <= positionMs.value }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLine)
    val lastCentredLine = remember { mutableIntStateOf(initialLine) }

    // Active line = the most recent line whose start has passed. derivedStateOf
    // recomputes when the position sample or the line list changes, but only
    // notifies readers when the index value actually changes — so the highlight
    // and the auto-scroll fire exactly once per line, never on every tick. (An
    // earlier version extrapolated the position between samples, which jittered
    // the index at line boundaries — the highlight looked stuck and the
    // constant re-scroll ate taps. The polled position is stable and accurate.)
    val currentLineIndex by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position } }
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
                .padding(horizontal = 28.dp)
                .liquidGlass(tint = accent),
            contentPadding = PaddingValues(top = halfViewport, bottom = halfViewport),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines) { index, line ->
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
            if (line.words.isNotEmpty()) {
                // Only sources with real per-word timing (TIDAL enhanced LRC)
                // illuminate word-by-word.
                KaraokeLyricLine(
                    line = line,
                    isActive = isActive,
                    isPast = isPast,
                    position = position,
                    accent = accent,
                    onClick = { onSeekTo(line.timeMs) },
                    beatModifier = beatModifier,
                    anchors = lineAnchors,
                    glyphKeyBase = glyphKeyBase,
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
                // Fixed size: the active line is marked by colour/weight only.
                // A size change reflows the list and shifts every line below,
                // which makes the lyrics impossible to track while reading.
                // Tracking is identical for active/inactive for the same
                // reason — only weight may differ between the two states.
                val lineStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = fx.fontSizeSp.sp,
                    lineHeight = (fx.fontSizeSp * 1.26f).sp,
                    letterSpacing = fx.letterSpacingSp.sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                )
                val lineModifier = beatModifier
                    .fillMaxWidth()
                    .clickable { onSeekTo(line.timeMs) }
                    .padding(vertical = 2.dp)
                // Render letters individually (so each reports its position for
                // rays) whenever the 3D wave OR the god rays are active.
                val perLetter = isActive &&
                    (fx.rotationDegrees > 0.05f || (beatIntensity > 0.01f && fx.rayCount > 0))
                if (perLetter) {
                    Letters3DLine(
                        text = line.text.ifBlank { "♪" },
                        style = lineStyle,
                        color = color,
                        modifier = lineModifier,
                        anchors = lineAnchors,
                        glyphKeyBase = glyphKeyBase,
                    )
                } else {
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = lineStyle,
                        color = color,
                        textAlign = TextAlign.Center,
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
) {
    // Render letters individually while active whenever the 3D wave OR the god
    // rays are on (rays need per-glyph positions). One frame clock per line.
    val fx = LocalLyricsFx.current
    val perLetter = isActive &&
        (fx.rotationDegrees > 0.05f || (fx.bassReact > 0.01f && fx.rayCount > 0))
    val time = if (perLetter) rememberFrameSeconds() else null
    FlowRow(
        modifier = beatModifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
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
            val wordStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = fx.fontSizeSp.sp,
                lineHeight = (fx.fontSizeSp * 1.26f).sp,
                letterSpacing = fx.letterSpacingSp.sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
            )
            if (time != null) {
                val phaseBase = letterBase
                Row(horizontalArrangement = Arrangement.spacedBy(letterGapDp(fx))) {
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
}

val LocalLyricsFx = compositionLocalOf { LyricsFxSettings() }

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
private fun letterGapDp(fx: LyricsFxSettings): Dp {
    val intensity = (fx.rotationDegrees / 9f).coerceAtMost(2.5f)
    // fontSize * peakSwell = total width the glyph gains at the toward-viewer
    // peak; +1.5dp covers the extruded backing (offset 1.2dp, scaled by depth).
    return (fx.fontSizeSp * 0.09f * intensity + 1.5f).dp
}

/**
 * Active-line treatment: each character is its own Text inside a per-letter
 * 3D transform — a ripple of rotation travelling along the line — with a
 * baked-in drop shadow for depth. Word-wise FlowRow keeps wrapping at word
 * boundaries like the plain Text it replaces, and the transforms are
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
) {
    val fx = LocalLyricsFx.current
    val time = rememberFrameSeconds()
    val shadowed = style.copy(shadow = letter3DShadow(fx.shadowDepth))
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        var letterBase = 0
        val words = text.split(" ")
        words.forEachIndexed { index, word ->
            val display = if (index < words.lastIndex) "$word " else word
            val phaseBase = letterBase
            Row(horizontalArrangement = Arrangement.spacedBy(letterGapDp(fx))) {
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
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
                ),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
