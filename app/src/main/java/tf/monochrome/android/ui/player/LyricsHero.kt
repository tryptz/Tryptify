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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
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

    // Bass-reactive treatment for the active line: pulse from the shared FFT
    // tap, a frame clock for the ray sweep, and a pop-in spring per line
    // change. All disabled (and the analyzer never acquired) at intensity 0.
    val fx = LocalLyricsFx.current
    val beatIntensity = fx.bassReact
    // Prefer the player-provided shared pulse (one analyzer stake, and the
    // full-screen ray backdrop breathes in lockstep with the line pump).
    val bassPulse = LocalBeatPulse.current
        ?: if (beatIntensity > 0.01f) rememberBassPulse() else remember { mutableFloatStateOf(0f) }
    val beatTime = if (beatIntensity > 0.01f) rememberFrameSeconds() else remember { mutableFloatStateOf(0f) }
    // When the player composes a BeatRaysBackdrop behind itself, the active
    // line reports its screen position there and skips its own halo drawing.
    val rayAnchor = LocalBeatRayAnchor.current
    DisposableEffect(rayAnchor) {
        onDispose { rayAnchor?.center?.value = null }
    }
    // Pop-in: snap to 0 and spring (underdamped → overshoot) back to 1 every
    // time the active line changes, so each new line pops in right on the
    // beat that activated it. ~8% size swing keeps it punchy but readable.
    val popIn = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && beatIntensity > 0.01f) {
            popIn.snapTo(0f)
            popIn.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.38f,
                    stiffness = 500f,
                ),
            )
        }
    }
    val popScale: () -> Float = { 0.92f + 0.08f * popIn.value }

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
                .liquidGlass(),
            contentPadding = PaddingValues(top = halfViewport, bottom = halfViewport),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines) { index, line ->
            val isActive = index == currentLineIndex
            val isPast = index < currentLineIndex
            // Bass treatment rides only the active line: scale pump + pop-in
            // + god rays, all draw-phase. With a ray anchor present the
            // full-screen backdrop draws the light instead, and this line
            // just publishes where it is on screen.
            val beatModifier = if (isActive && beatIntensity > 0.01f) {
                val base = Modifier.bassBeat(
                    bassPulse, beatTime, popScale, accent, beatIntensity,
                    drawHalo = rayAnchor == null,
                )
                if (rayAnchor != null) {
                    base.onGloballyPositioned { coords ->
                        rayAnchor.center.value = coords.boundsInRoot().center
                        rayAnchor.halfSize.value = androidx.compose.ui.geometry.Size(
                            coords.size.width / 2f,
                            coords.size.height / 2f,
                        )
                    }
                } else {
                    base
                }
            } else {
                Modifier
            }
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
                )
            } else {
                // Line-level sources (LRCLib / Qobuz): illuminate the whole
                // active line at once.
                val color by animateColorAsState(
                    targetValue = when {
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
                    fontSize = 23.sp,
                    lineHeight = 29.sp,
                    letterSpacing = (-0.2).sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                )
                val lineModifier = beatModifier
                    .fillMaxWidth()
                    .clickable { onSeekTo(line.timeMs) }
                    .padding(vertical = 2.dp)
                if (isActive && LocalLyricsFx.current.rotationDegrees > 0.05f) {
                    Letters3DLine(
                        text = line.text.ifBlank { "♪" },
                        style = lineStyle,
                        color = color,
                        modifier = lineModifier,
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
) {
    // One frame clock per line, and only while it is the active one.
    val fx = LocalLyricsFx.current
    val time = if (isActive && fx.rotationDegrees > 0.05f) rememberFrameSeconds() else null
    FlowRow(
        modifier = beatModifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        var letterBase = 0
        line.words.forEach { word ->
            val target = when {
                !isActive -> if (isPast) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.6f)
                position >= word.endMs -> accent.copy(alpha = 0.9f)   // already sung
                position >= word.startMs -> accent                    // lighting up now
                else -> Color.White.copy(alpha = 0.45f)               // not yet reached
            }
            val color by animateColorAsState(targetValue = target, label = "wordColor")
            val display = word.text + " "
            val wordStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = 23.sp,
                lineHeight = 29.sp,
                letterSpacing = (-0.2).sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
            )
            if (time != null) {
                val phaseBase = letterBase
                Row {
                    display.forEachIndexed { j, ch ->
                        Letter3DText(
                            text = ch.toString(),
                            // Low spatial frequency: neighbouring letters stay
                            // nearly in phase, so the line reads as one long
                            // smooth ribbon. (0.45 rad/letter decorrelated
                            // neighbours — scattered, jittery-looking text.)
                            phase = (phaseBase + j) * LETTER_PHASE_STEP,
                            style = wordStyle.copy(shadow = letter3DShadow(fx.shadowDepth)),
                            color = color,
                            time = time,
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

/**
 * User-tunable 3D-letter animation parameters (Settings ▸ Lyrics Appearance).
 * rotationDegrees 0 disables the per-letter path entirely (plain flat text).
 */
data class LyricsFxSettings(
    val rotationDegrees: Float = 12f,
    val waveSpeed: Float = 1f,
    val shadowDepth: Float = 0.7f,
    // 0 disables the bass-reactive treatment (pump/pop/god rays) entirely.
    val bassReact: Float = 0.8f,
)

val LocalLyricsFx = compositionLocalOf { LyricsFxSettings() }

// Wave spatial frequency in radians per letter. Small on purpose: adjacent
// letters must move almost together for the wave to read as one smooth
// ribbon travelling through the line.
private const val LETTER_PHASE_STEP = 0.22f

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
 * Active-line treatment: each character is its own Text inside a per-letter
 * 3D transform — a ripple of rotation travelling along the line — with a
 * baked-in drop shadow for depth. Word-wise FlowRow keeps wrapping at word
 * boundaries like the plain Text it replaces, and the transforms are
 * draw-only: layout, font size and spacing never change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Letters3DLine(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val time = rememberFrameSeconds()
    val shadowed = style.copy(shadow = letter3DShadow(LocalLyricsFx.current.shadowDepth))
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        var letterBase = 0
        val words = text.split(" ")
        words.forEachIndexed { index, word ->
            val display = if (index < words.lastIndex) "$word " else word
            val phaseBase = letterBase
            Row {
                display.forEachIndexed { j, ch ->
                    Letter3DText(
                        text = ch.toString(),
                        phase = (phaseBase + j) * LETTER_PHASE_STEP,
                        style = shadowed,
                        color = color,
                        time = time,
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
) {
    val fx = LocalLyricsFx.current
    Box(
        modifier = Modifier.graphicsLayer {
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
            translationY = 3.dp.toPx() * sin(p)
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
            Text(
                text = line.text.ifBlank { "" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 23.sp, lineHeight = 29.sp),
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
