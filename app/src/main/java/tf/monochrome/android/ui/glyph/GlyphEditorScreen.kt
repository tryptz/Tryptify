// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.editor.GlyphChartDraft
import tf.monochrome.android.glyph.editor.GlyphEditTool
import tf.monochrome.android.glyph.editor.GlyphSnap
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Trypt Mania's map editor: one chart of one song on a beat grid.
 *
 * Time runs down the grid, measure numbers and the song's waveform in the
 * gutter, the four lanes beside them. The playhead line near the top is where
 * the song plays from and where the keyboard places notes; dragging the grid
 * moves it. A tap places what the tool says, snapped to the chosen division;
 * holds take two taps, the head and then the end. Play runs the song under
 * the grid, and Test plays the chart from just before the playhead and comes
 * back here. The grid is the mode's own, on its near-black; the bars around
 * it are the app's.
 *
 * With a keyboard: 1 to 4, or the lane keys that are not arrows (D F J K by
 * default), place notes at the playhead; up and down step it by the snap,
 * Page Up and Page Down by a measure; Space plays. The arrows only move,
 * as in StepMania's editor, even where they play the lanes in a run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphEditorScreen(
    state: GlyphUiState,
    palette: GlyphPalette?,
    /** Where the song is, in beats, while it plays under the grid. */
    playingBeat: () -> Float?,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = state.editor ?: return
    var leaving by remember { mutableStateOf(false) }
    var tempoOpen by remember { mutableStateOf(false) }
    val leave = { if (editor.dirty) leaving = true else onEvent(GlyphEvent.CloseEditor) }
    BackHandler(onBack = leave)

    // The beat under the playhead. Owned here while paused; following the
    // song while it plays.
    var playhead by rememberSaveable(editor.song.trackId, editor.difficulty) { mutableFloatStateOf(0f) }
    val lastBeat = editor.timing.secondsToBeat(editor.durationSeconds.coerceAtLeast(1f)) + EXTRA_BEATS
    LaunchedEffect(editor.playing) {
        if (!editor.playing) return@LaunchedEffect
        while (true) withFrameNanos { playingBeat()?.let { playhead = it } }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || leaving || tempoOpen) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                val stepBeats = 1f / editor.snap.perBeat
                val lane = when {
                    code in KEYCODE_1 until KEYCODE_1 + editor.draft.laneCount -> code - KEYCODE_1
                    code in ARROW_KEYS -> null
                    else -> state.keys.laneFor(code, editor.draft.course)?.ordinal
                }
                when {
                    code == KEYCODE_SPACE -> onEvent(GlyphEvent.EditorTogglePlay(playhead))
                    code == KEYCODE_PAGE_DOWN -> playhead = (playhead + 4f).coerceAtMost(lastBeat)
                    code == KEYCODE_PAGE_UP -> playhead = (playhead - 4f).coerceAtLeast(0f)
                    lane != null && !editor.playing -> onEvent(GlyphEvent.EditorTap(playhead, lane))
                    code == KEYCODE_DPAD_DOWN && !editor.playing ->
                        playhead = snapStep(playhead, stepBeats, 1).coerceAtMost(lastBeat)
                    code == KEYCODE_DPAD_UP && !editor.playing ->
                        playhead = snapStep(playhead, stepBeats, -1).coerceAtLeast(0f)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Map editor", maxLines = 1, modifier = Modifier.semantics { heading() })
                    Text(
                        text = "${editor.song.title} · ${editor.draft.course.difficultyLabel(editor.difficulty)} ${editor.meter}" +
                            if (editor.isNew) " · new" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = leave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                IconButton(onClick = { onEvent(GlyphEvent.EditorUndo) }, enabled = editor.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                GlyphButton(
                    text = if (editor.dirty || editor.isNew) "Save" else "Saved",
                    primary = editor.dirty || editor.isNew,
                    enabled = editor.dirty || editor.isNew,
                    height = 40.dp,
                    onClick = { onEvent(GlyphEvent.EditorSave) },
                    modifier = Modifier.padding(end = MonoDimens.spacingSm),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        Column(
            modifier = Modifier.padding(horizontal = MonoDimens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        ) {
            GlyphSegmented(
                options = GlyphSnap.entries,
                selected = editor.snap,
                label = { it.label },
                onSelect = { onEvent(GlyphEvent.SetSnap(it)) },
                description = "Snap",
                height = 42.dp,
                textStyle = MaterialTheme.typography.labelMedium,
            )
            GlyphSegmented(
                options = GlyphEditTool.entries,
                selected = editor.tool,
                label = { it.label },
                onSelect = { onEvent(GlyphEvent.SetTool(it)) },
                description = "Tool",
                height = 42.dp,
            )
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(GlyphTheme.Ink),
        ) {
            EditorGrid(
                state = state,
                palette = palette,
                playhead = { playhead },
                onScroll = { playhead = it.coerceIn(0f, lastBeat) },
                onEvent = onEvent,
                modifier = Modifier.fillMaxSize(),
            )
            val message = editor.message
            if (message != null) {
                LaunchedEffect(message) {
                    delay(MESSAGE_MILLIS)
                    onEvent(GlyphEvent.DismissEditorMessage)
                }
                GlyphPanel(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(MonoDimens.spacingMd)
                        .fillMaxWidth(),
                    contentPadding = MonoDimens.spacingMd,
                ) {
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val pending = editor.pendingHold
            if (pending != null) {
                Text(
                    text = "Now tap where the hold ends, in the same lane.",
                    style = MaterialTheme.typography.labelLarge,
                    color = GlyphTheme.Paper,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(MonoDimens.spacingMd)
                        .background(GlyphTheme.InkRaised, MonoDimens.shapePill)
                        .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingSm),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(MonoDimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            RoundGlassButton(
                onClick = { onEvent(GlyphEvent.EditorTogglePlay(playhead)) },
                size = 52.dp,
                label = if (editor.playing) "Stop" else "Play from the playhead",
            ) { ink ->
                Icon(if (editor.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = ink)
            }
            Column(modifier = Modifier.weight(1f)) {
                val measure = floor(playhead / GlyphChartDraft.BEATS_PER_MEASURE).toInt() + 1
                val beat = floor(playhead % GlyphChartDraft.BEATS_PER_MEASURE).toInt() + 1
                Text(
                    text = "Measure $measure · beat $beat",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                val bpm = editor.timing.bpmAt(playhead)
                TextButton(
                    onClick = { tempoOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = "${editor.draft.noteCount} notes · ${"%.1f".format(bpm)} BPM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GlyphButton(
                text = "Test",
                icon = Icons.Default.PlayArrow,
                onClickLabel = "Play the chart from just before the playhead",
                onClick = { onEvent(GlyphEvent.EditorTestPlay((playhead - TEST_LEAD_BEATS).coerceAtLeast(0f))) },
                height = 48.dp,
            )
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave the editor?") },
            text = { Text("Your changes to this chart have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onEvent(GlyphEvent.CloseEditor)
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text("Keep editing") }
            },
        )
    }
    if (tempoOpen) {
        TempoDialog(
            bpm = editor.timing.startBpm,
            beatZero = editor.timing.offsetSeconds,
            editable = editor.tempoEditable,
            onDone = { bpm, zero ->
                onEvent(GlyphEvent.SetEditorTempo(bpm, zero))
                tempoOpen = false
            },
            onDismiss = { tempoOpen = false },
        )
    }
}

/** [beat] moved one [step] either way, landing on the step's lines. */
private fun snapStep(beat: Float, step: Float, direction: Int): Float {
    val lines = beat / step
    val target = if (direction > 0) floor(lines + 1e-3f) + 1 else kotlin.math.ceil(lines - 1e-3f) - 1
    return target * step
}

/**
 * The grid itself: one canvas, scrolled by dragging and tapped to edit.
 * Only the beats on screen are drawn.
 */
@Composable
private fun EditorGrid(
    state: GlyphUiState,
    palette: GlyphPalette?,
    playhead: () -> Float,
    onScroll: (Float) -> Unit,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = state.editor ?: return
    val density = LocalDensity.current
    val beatPx = with(density) { BEAT_HEIGHT.toPx() }
    val gutterPx = with(density) { GUTTER.toPx() }
    val scroll = rememberScrollableState { delta ->
        onScroll(playhead() - delta / beatPx)
        delta
    }
    val waveform = state.training.waveform
    val measure = floor(playhead() / GlyphChartDraft.BEATS_PER_MEASURE).toInt() + 1
    val measurer = rememberTextMeasurer()
    val numberStyle = MaterialTheme.typography.labelMedium.copy(color = GlyphTheme.Paper, fontWeight = FontWeight.Bold)

    Canvas(
        modifier = modifier
            .scrollable(scroll, Orientation.Vertical, enabled = !editor.playing)
            .pointerInput(editor.playing) {
                if (editor.playing) return@pointerInput
                detectTapGestures { tap ->
                    val laneWidth = (size.width - gutterPx) / editor.draft.laneCount
                    val lane = floor((tap.x - gutterPx) / laneWidth).toInt()
                    if (lane !in 0 until editor.draft.laneCount) return@detectTapGestures
                    val beat = playhead() + (tap.y - size.height * PLAYHEAD_AT) / beatPx
                    onEvent(GlyphEvent.EditorTap(beat, lane))
                }
            }
            .semantics {
                contentDescription = "Chart grid at measure $measure. Tap a lane to place a note; drag to move."
                customActions = listOf(
                    CustomAccessibilityAction("Forward a measure") {
                        onScroll(playhead() + GlyphChartDraft.BEATS_PER_MEASURE); true
                    },
                    CustomAccessibilityAction("Back a measure") {
                        onScroll(playhead() - GlyphChartDraft.BEATS_PER_MEASURE); true
                    },
                )
            },
    ) {
        val head = playhead()
        val playY = size.height * PLAYHEAD_AT
        val laneCount = editor.draft.laneCount
        val course = editor.draft.course
        val laneWidth = (size.width - gutterPx) / laneCount
        val firstBeat = head - playY / beatPx
        val lastBeat = head + (size.height - playY) / beatPx
        fun yOf(beat: Float) = playY + (beat - head) * beatPx

        // Alternate lane grounds.
        for (lane in 0 until laneCount) {
            if (lane % 2 == 1) {
                drawRect(
                    GlyphTheme.InkPanel,
                    topLeft = Offset(gutterPx + lane * laneWidth, 0f),
                    size = Size(laneWidth, size.height),
                )
            }
        }

        // The song's waveform in the gutter, so a passage can be found by eye.
        if (waveform.isNotEmpty() && editor.durationSeconds > 0f) {
            val bars = waveform.size
            for (i in 0 until bars) {
                val seconds = editor.durationSeconds * i / bars
                val y = yOf(editor.timing.secondsToBeat(seconds))
                if (y < -4f || y > size.height + 4f) continue
                val w = (gutterPx - 8f) * waveform[i].coerceIn(0f, 1f)
                drawRect(
                    GlyphTheme.Muted.copy(alpha = 0.45f),
                    topLeft = Offset((gutterPx - w) / 2f, y - 1.5f),
                    size = Size(w.coerceAtLeast(2f), 3f),
                )
            }
        }

        // Grid lines: the snap's faintly, beats brighter, measures brightest.
        val step = 1f / editor.snap.perBeat
        var line = kotlin.math.ceil(firstBeat.coerceAtLeast(0f) / step) * step
        while (line <= lastBeat) {
            val y = yOf(line)
            val isBeat = kotlin.math.abs(line - kotlin.math.round(line)) < 1e-3f
            val isMeasure = isBeat && kotlin.math.round(line).toInt() % GlyphChartDraft.BEATS_PER_MEASURE == 0
            drawRect(
                color = when {
                    isMeasure -> GlyphTheme.Paper.copy(alpha = 0.5f)
                    isBeat -> GlyphTheme.Paper.copy(alpha = 0.2f)
                    else -> GlyphTheme.Paper.copy(alpha = 0.07f)
                },
                topLeft = Offset(if (isMeasure) 0f else gutterPx, y - if (isMeasure) 1f else 0.5f),
                size = Size(size.width, if (isMeasure) 2f else 1f),
            )
            if (isMeasure) {
                // The measure's number, just under its line in the gutter.
                val number = kotlin.math.round(line).toInt() / GlyphChartDraft.BEATS_PER_MEASURE + 1
                drawText(measurer, number.toString(), Offset(6.dp.toPx(), y + 3.dp.toPx()), numberStyle)
            }
            line += step
        }

        // Notes, holds under heads.
        val noteSize = minOf(laneWidth * 0.62f, beatPx * 0.55f)
        val firstTick = GlyphChartDraft.tickOf(firstBeat) - GlyphChartDraft.TICKS_PER_BEAT
        val lastTick = GlyphChartDraft.tickOf(lastBeat) + GlyphChartDraft.TICKS_PER_BEAT
        for (note in editor.draft.notes) {
            if (note.endTick < firstTick || note.tick > lastTick) continue
            val cx = gutterPx + (note.lane + 0.5f) * laneWidth
            val y = yOf(note.tick / GlyphChartDraft.TICKS_PER_BEAT.toFloat())
            // A dance note is coloured by its beat; a pop-kun by its button.
            val colour = if (course == CourseType.POPN) GlyphPopnArt.colour(GlyphLane.entries[note.lane]) else divisionColour(note.tick, palette)
            if (note.type.hasTail) {
                val end = yOf(note.endTick / GlyphChartDraft.TICKS_PER_BEAT.toFloat())
                drawRoundRect(
                    color = colour.copy(alpha = if (note.type == GlyphNoteType.ROLL) 0.3f else 0.45f),
                    topLeft = Offset(cx - noteSize * 0.22f, y),
                    size = Size(noteSize * 0.44f, end - y),
                    cornerRadius = CornerRadius(noteSize * 0.12f),
                )
            }
            if (note.type == GlyphNoteType.MINE) {
                drawMine(Offset(cx, y), noteSize * 0.42f)
            } else if (course == CourseType.POPN) {
                with(GlyphPopnArt) { drawPopnNote(Offset(cx, y), noteSize, GlyphLane.entries[note.lane]) }
            } else {
                drawArrow(Offset(cx, y), noteSize, note.lane, colour)
            }
        }
        editor.pendingHold?.let { pending ->
            val cx = gutterPx + (pending.lane + 0.5f) * laneWidth
            val y = yOf(pending.tick / GlyphChartDraft.TICKS_PER_BEAT.toFloat())
            drawCircle(GlyphTheme.Paper, radius = noteSize * 0.62f, center = Offset(cx, y), style = Stroke(2.dp.toPx()))
        }

        // The playhead.
        drawRect(
            color = GlyphTheme.Warning,
            topLeft = Offset(0f, playY - 1f),
            size = Size(size.width, 2f),
        )
    }
}

/** A note's colour from where it falls in the beat, as the playfield colours it. */
private fun divisionColour(tick: Int, palette: GlyphPalette?): Color {
    val row = tick % GlyphChartDraft.TICKS_PER_MEASURE
    return GlyphTheme.beatColor(GlyphBeatDivision.forRow(row, GlyphChartDraft.TICKS_PER_MEASURE), palette)
}

/** A block arrow for [lane], centred on [center]. */
private fun DrawScope.drawArrow(center: Offset, size: Float, lane: Int, colour: Color) {
    val degrees = when (lane) {
        0 -> -90f
        1 -> 180f
        2 -> 0f
        else -> 90f
    }
    val half = size / 2f
    val path = Path().apply {
        moveTo(center.x, center.y - half)
        lineTo(center.x + half * 0.9f, center.y - half * 0.05f)
        lineTo(center.x + half * 0.38f, center.y - half * 0.05f)
        lineTo(center.x + half * 0.38f, center.y + half * 0.9f)
        lineTo(center.x - half * 0.38f, center.y + half * 0.9f)
        lineTo(center.x - half * 0.38f, center.y - half * 0.05f)
        lineTo(center.x - half * 0.9f, center.y - half * 0.05f)
        close()
    }
    rotate(degrees, pivot = center) {
        drawPath(path, colour.copy(alpha = 0.55f))
        drawPath(path, colour, style = Stroke(width = size * 0.07f))
    }
}

/** A mine: a ringed dot, in the warning red. */
private fun DrawScope.drawMine(center: Offset, radius: Float) {
    drawCircle(GlyphTheme.Ink, radius = radius, center = center)
    drawCircle(GlyphTheme.Negative, radius = radius, center = center, style = Stroke(width = radius * 0.25f))
    drawCircle(GlyphTheme.Negative, radius = radius * 0.35f, center = center)
}

/** The song's tempo and where beat one is, for a song with a single tempo. */
@Composable
private fun TempoDialog(
    bpm: Float,
    beatZero: Float,
    editable: Boolean,
    onDone: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var bpmText by remember { mutableStateOf("%.2f".format(bpm)) }
    var zeroText by remember { mutableStateOf("%.3f".format(beatZero)) }
    val parsedBpm = bpmText.replace(',', '.').toFloatOrNull()?.takeIf { it in 30f..400f }
    val parsedZero = zeroText.replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo") },
        text = {
            if (!editable) {
                Text("This song changes tempo, and its tempo map is kept as it is. The editor places notes on its beats.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it.take(8) },
                        singleLine = true,
                        label = { Text("BPM") },
                        isError = parsedBpm == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = zeroText,
                        onValueChange = { zeroText = it.take(9) },
                        singleLine = true,
                        label = { Text("First beat at (seconds)") },
                        isError = parsedZero == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text(
                        "Notes stay on their beats; changing the tempo moves them in time with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (editable) {
                TextButton(
                    enabled = parsedBpm != null && parsedZero != null,
                    onClick = { onDone(parsedBpm!!, parsedZero!!) },
                ) { Text("Set") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (editable) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}

private val BEAT_HEIGHT = 112.dp
private val GUTTER = 44.dp

/** The playhead's height on the grid, a share from the top. */
private const val PLAYHEAD_AT = 0.28f

/** Room past the song's end to finish a last measure. */
private const val EXTRA_BEATS = 8f

/** A test play starts this many beats before the playhead, to come in on time. */
private const val TEST_LEAD_BEATS = 4f

private const val MESSAGE_MILLIS = 4_000L

// Android key codes the editor answers to.
private const val KEYCODE_SPACE = 62
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_PAGE_UP = 92
private const val KEYCODE_PAGE_DOWN = 93
private const val KEYCODE_1 = 8

/** Up, down, left and right: in the editor they move, never place. */
private val ARROW_KEYS = setOf(19, 20, 21, 22)
