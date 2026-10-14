// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphJudge
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn

/**
 * StepMania's Player Options, between choosing a song and playing it.
 *
 * Laid out the way ScreenPlayerOptions is: a row per option, its name on the
 * left and its choices along the right, the chosen one underlined in the
 * player's colour and the row last touched lit. Tap a choice to take it; long
 * rows (the speeds, the rates) scroll sideways and open on what is chosen.
 *
 * Every choice is kept between sessions, as a StepMania profile keeps its
 * modifiers, except the music rate.
 */
@Composable
fun GlyphOptionsScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(GlyphEvent.CloseOptions) }
    val fonts = rememberStepFonts()
    val modifiers = state.gameplay.modifiers
    val reduced = modifiers.reducedMotion
    var focusedRow by remember { mutableStateOf(0) }
    var editingName by remember { mutableStateOf(false) }

    val rows = optionRows(state, onEvent) { editingName = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(OptionsPage, GlyphTheme.Ink)),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            OptionsHeader(state = state, fonts = fonts, reduced = reduced, onBack = { onEvent(GlyphEvent.CloseOptions) })

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> row.title }) { index, row ->
                    OptionRow(
                        row = row,
                        index = index,
                        focused = focusedRow == index,
                        fonts = fonts,
                        reduced = reduced,
                        onFocus = { focusedRow = index },
                    )
                }
            }

            StartBar(fonts = fonts, reduced = reduced) {
                onEvent(GlyphEvent.StartPlay)
            }
        }
    }

    if (editingName) {
        NameDialog(
            current = state.playerName,
            onDone = {
                onEvent(GlyphEvent.SetPlayerName(it))
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

/** One row of choices. [selected] is an index into [choices]. */
private class OptionRowModel(
    val title: String,
    val choices: List<String>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    /** A line under the row: what the choice means here, or why a run is unranked. */
    val note: String? = null,
    val noteWarns: Boolean = false,
)

private fun optionRows(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    onEditName: () -> Unit,
): List<OptionRowModel> {
    val modifiers = state.gameplay.modifiers
    val mode = modifiers.scrollMode
    val family = when (mode) {
        is GlyphScrollMode.XMod -> GlyphScrollFamily.X
        is GlyphScrollMode.CMod -> GlyphScrollFamily.C
        is GlyphScrollMode.MMod -> GlyphScrollFamily.M
    }
    val (speedValues, speedLabels, speedNow) = when (mode) {
        is GlyphScrollMode.XMod -> Triple(X_SPEEDS, X_SPEEDS.map { "%.2fx".format(it) }, mode.multiplier)
        is GlyphScrollMode.CMod -> Triple(C_SPEEDS, C_SPEEDS.map { "C${it.toInt()}" }, mode.targetBpm)
        is GlyphScrollMode.MMod -> Triple(M_SPEEDS, M_SPEEDS.map { "M${it.toInt()}" }, mode.targetBpm)
    }
    val bpm = state.simfile?.timing?.startBpm ?: 0f
    val readsAt = when (mode) {
        is GlyphScrollMode.XMod -> if (bpm > 0f) (bpm * mode.multiplier).roundToInt() else null
        is GlyphScrollMode.CMod -> mode.targetBpm.roundToInt()
        is GlyphScrollMode.MMod -> mode.targetBpm.roundToInt()
    }
    val judge = GlyphJudge.forScale(modifiers.timingWindowScale)
    val name = state.playerName.ifBlank { "PLAYER" }

    return listOf(
        OptionRowModel(
            title = "Speed Type",
            choices = GlyphScrollFamily.entries.map { it.label },
            selected = family.ordinal,
            onSelect = { onEvent(GlyphEvent.SetScrollFamily(GlyphScrollFamily.entries[it])) },
        ),
        OptionRowModel(
            title = "Speed",
            choices = speedLabels,
            selected = speedValues.indices.minBy { kotlin.math.abs(speedValues[it] - speedNow) },
            onSelect = { onEvent(GlyphEvent.SetScrollValue(speedValues[it])) },
            note = readsAt?.let { "Reads at $it BPM · ${mode.summary}" } ?: mode.summary,
        ),
        OptionRowModel(
            title = "Rate",
            choices = RATES.map { "%.1fx".format(it) },
            selected = RATES.indices.minBy { kotlin.math.abs(RATES[it] - modifiers.speed) },
            onSelect = { onEvent(GlyphEvent.SetSpeed(RATES[it])) },
            note = if (modifiers.speed != 1f) "Scores at this rate are marked on the high score table." else null,
        ),
        OptionRowModel(
            title = "Turn",
            choices = GlyphTurn.entries.map { it.label },
            selected = modifiers.turn.ordinal,
            onSelect = { onEvent(GlyphEvent.SetTurn(GlyphTurn.entries[it])) },
        ),
        OptionRowModel(
            title = "Appearance",
            choices = GlyphAppearance.entries.map { it.label },
            selected = modifiers.appearance.ordinal,
            onSelect = { onEvent(GlyphEvent.SetAppearance(GlyphAppearance.entries[it])) },
        ),
        OptionRowModel(
            title = "Scroll",
            choices = listOf("Standard", "Reverse"),
            selected = if (modifiers.reverse) 1 else 0,
            onSelect = { onEvent(GlyphEvent.SetReverse(it == 1)) },
            note = if (modifiers.reverse) "Receptors at the top, notes rising." else "Receptors at the bottom, notes falling.",
        ),
        OptionRowModel(
            title = "Mini",
            choices = MINIS.map { "${(it * 100).roundToInt()}%" },
            selected = MINIS.indices.minBy { kotlin.math.abs(MINIS[it] - modifiers.mini) },
            onSelect = { onEvent(GlyphEvent.SetMini(MINIS[it])) },
        ),
        OptionRowModel(
            title = "Dark",
            choices = listOf("Off", "On"),
            selected = if (modifiers.dark) 1 else 0,
            onSelect = { onEvent(GlyphEvent.SetDark(it == 1)) },
        ),
        OptionRowModel(
            title = "Judge",
            choices = GlyphJudge.entries.map { it.label },
            selected = judge.ordinal,
            onSelect = { onEvent(GlyphEvent.SetTimingWindowScale(GlyphJudge.entries[it].scale)) },
            note = if (judge != GlyphJudge.J4) {
                "Scores at this judge are marked on the high score table."
            } else {
                "J4: StepMania's standard windows."
            },
        ),
        OptionRowModel(
            title = "Fail",
            choices = GlyphFailType.entries.map { it.label },
            selected = modifiers.failType.ordinal,
            onSelect = { onEvent(GlyphEvent.SetFailType(GlyphFailType.entries[it])) },
        ),
        OptionRowModel(
            title = "Noteskin",
            choices = GlyphSkin.entries.map { it.label },
            selected = modifiers.skin.ordinal,
            onSelect = { onEvent(GlyphEvent.SetSkin(GlyphSkin.entries[it])) },
        ),
        OptionRowModel(
            title = "Metronome",
            choices = listOf("Off", "On"),
            selected = if (modifiers.metronome) 1 else 0,
            onSelect = { if ((it == 1) != modifiers.metronome) onEvent(GlyphEvent.ToggleMetronome) },
        ),
        OptionRowModel(
            title = "Name",
            choices = listOf(name),
            selected = 0,
            onSelect = { onEditName() },
            note = "On the high score tables. Tap to change.",
        ),
    )
}

@Composable
private fun OptionsHeader(state: GlyphUiState, fonts: GlyphStepFonts, reduced: Boolean, onBack: () -> Unit) {
    // The header bar slides down into place, as StepMania's does on entry.
    val drop = remember { Animatable(if (reduced) 0f else -1f) }
    LaunchedEffect(Unit) { drop.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = drop.value * size.height }
            .background(Brush.horizontalGradient(listOf(HeaderColour, HeaderColour.copy(alpha = 0.55f)))),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to songs", tint = GlyphTheme.Paper)
            }
            Text(
                text = "SELECT OPTIONS",
                style = fonts.screenTitle,
                color = GlyphTheme.Paper,
                modifier = Modifier.semantics { heading() },
            )
        }
        val song = state.selectedSong
        val chart = state.chart
        if (song != null) {
            Text(
                text = buildString {
                    append(song.title)
                    if (chart != null) append("  ·  ").append(chart.difficulty.sscName).append(' ').append(chart.meter)
                },
                style = fonts.value,
                color = GlyphTheme.Paper.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(
    row: OptionRowModel,
    index: Int,
    focused: Boolean,
    fonts: GlyphStepFonts,
    reduced: Boolean,
    onFocus: () -> Unit,
) {
    // Rows arrive one after another from the right, as the option rows do in
    // StepMania. Flat: a slide and a fade, nothing turned in depth.
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * ROW_STAGGER_MILLIS)
        enter.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    val highlight by androidx.compose.animation.animateColorAsState(
        targetValue = if (focused) PlayerColour.copy(alpha = 0.18f) else Color.Transparent,
        label = "optionRowHighlight",
    )
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * with(density) { 48.dp.toPx() }
            }
            .background(highlight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.title.uppercase(),
                style = fonts.rowTitle,
                color = if (focused) GlyphTheme.Paper else GlyphTheme.Paper.copy(alpha = 0.75f),
                modifier = Modifier.width(ROW_TITLE_WIDTH).padding(start = 16.dp),
                maxLines = 1,
            )
            ChoiceStrip(
                row = row,
                fonts = fonts,
                reduced = reduced,
                onPick = {
                    onFocus()
                    row.onSelect(it)
                },
                modifier = Modifier.weight(1f),
            )
        }
        row.note?.let {
            Text(
                text = it,
                style = fonts.label,
                color = if (row.noteWarns) GlyphTheme.Warning else GlyphTheme.Muted,
                modifier = Modifier.padding(start = ROW_TITLE_WIDTH, end = 16.dp, bottom = 6.dp),
                maxLines = 2,
            )
        }
    }
}

/**
 * The choices, with StepMania's underline beneath the chosen one. The
 * underline glides from choice to choice rather than jumping.
 */
@Composable
private fun ChoiceStrip(
    row: OptionRowModel,
    fonts: GlyphStepFonts,
    reduced: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Each choice's left edge and width, in the strip's own pixels.
    val bounds = remember(row.title) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val target = bounds[row.selected]

    // Open on the chosen value, so a long row (the speeds) shows it.
    LaunchedEffect(row.selected, target) {
        val (left, width) = target ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val wanted = (left + width / 2 - viewport / 2).coerceIn(0, scroll.maxValue)
        if (reduced) scroll.scrollTo(wanted) else scroll.animateScrollTo(wanted)
    }

    val underlineX by animateDpAsState(
        targetValue = with(density) { (target?.first ?: 0).toDp() },
        animationSpec = if (reduced) tween(0) else spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "optionUnderlineX",
    )
    val underlineWidth by animateDpAsState(
        targetValue = with(density) { (target?.second ?: 0).toDp() },
        animationSpec = if (reduced) tween(0) else spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "optionUnderlineWidth",
    )

    Box(modifier = modifier.horizontalScroll(scroll).padding(end = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.choices.forEachIndexed { index, label ->
                val chosen = index == row.selected
                Text(
                    text = label,
                    style = fonts.value,
                    color = if (chosen) GlyphTheme.Paper else GlyphTheme.Paper.copy(alpha = 0.55f),
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            bounds[index] = coordinates.positionInParent().x.roundToInt() to coordinates.size.width
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onPick(index) }
                        .semantics {
                            role = Role.RadioButton
                            selected = chosen
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    maxLines = 1,
                )
            }
        }
        if (target != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = underlineX + UNDERLINE_INSET)
                    .width((underlineWidth - UNDERLINE_INSET * 2).coerceAtLeast(0.dp))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlayerColour),
            )
        }
    }
}

/** START, pulsing as StepMania's "Press Start" does. Flat: it grows and glows, it does not tilt. */
@Composable
private fun StartBar(fonts: GlyphStepFonts, reduced: Boolean, onStart: () -> Unit) {
    val pulse = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "startPulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "startPulseScale",
        )
        value
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(PlayerColour, HeaderColour)))
                .clickable(onClickLabel = "Start the song", onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "START", style = fonts.screenTitle, color = GlyphTheme.Paper)
        }
    }
}

@Composable
private fun NameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(12) },
                singleLine = true,
                label = { Text("Up to 12 letters") },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** StepMania 5 default theme: the PlayerOptions header and Player 1's colour. */
internal val HeaderColour = Color(0xFF882D47)
internal val PlayerColour = Color(0xFF4B82DC)
private val OptionsPage = Color(0xFF151A2C)

private val ROW_TITLE_WIDTH: Dp = 128.dp
private val UNDERLINE_INSET: Dp = 6.dp
private const val ROW_STAGGER_MILLIS = 35L

private val X_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f, 3.5f, 4f, 5f, 6f, 8f)
private val C_SPEEDS = (100..1000 step 50).map { it.toFloat() }
private val M_SPEEDS = (200..1000 step 50).map { it.toFloat() }
private val RATES = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f)
private val MINIS = listOf(0f, 0.25f, 0.5f)
