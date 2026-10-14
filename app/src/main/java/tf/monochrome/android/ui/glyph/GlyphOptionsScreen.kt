// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlin.math.roundToInt
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphJudge
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * StepMania's Player Options, between choosing a song and playing it.
 *
 * StepMania's options, the app's menu: a top bar with Back, the options in
 * cards of the app's glass grouped as StepMania groups them, every choice a
 * glass chip, and Start the one primary pill. The tab bar and the mini player
 * stay, as on every menu, and Start sits on them rather than under them.
 *
 * Every choice is kept between sessions, as a StepMania profile keeps its
 * modifiers, except the music rate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphOptionsScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(GlyphEvent.CloseOptions) }
    val modifiers = state.gameplay.modifiers
    val reduced = modifiers.reducedMotion
    var editingName by remember { mutableStateOf(false) }

    // The page is the backdrop the Start pill frosts: its sibling, never its
    // ancestor, or the pill would sample a layer it is drawn inside.
    val pageHaze = rememberHazeState()
    val density = LocalDensity.current
    var startHeight by remember { mutableStateOf(0.dp) }
    val chromeNow = LocalBottomChromeNow.current
    val chromeInset = LocalBottomChromeInset.current

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Player options",
                        maxLines = 1,
                        modifier = Modifier.semantics { heading() },
                    )
                    val song = state.selectedSong
                    val chart = state.chart
                    if (song != null) {
                        Text(
                            text = buildString {
                                append(song.title)
                                if (chart != null) {
                                    append(" · ").append(chart.difficulty.sscName).append(' ').append(chart.meter)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseOptions) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(pageHaze)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MonoDimens.spacingLg),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
            ) {
                Spacer(Modifier.height(MonoDimens.spacingXs))
                OptionsCard(index = 0, reduced = reduced) { SpeedOptions(state, onEvent) }
                OptionsCard(index = 1, reduced = reduced) { NoteOptions(state, onEvent) }
                OptionsCard(index = 2, reduced = reduced) { JudgingOptions(state, onEvent) }
                OptionsCard(index = 3, reduced = reduced) { GraphicsOptions(state, onEvent) }
                OptionsCard(index = 4, reduced = reduced) {
                    NameOption(name = state.playerName, onEdit = { editingName = true })
                }
                // The last card scrolls clear of Start, the mini player and
                // the tab bar, while the page still runs under their glass.
                Spacer(Modifier.height(chromeInset + startHeight + MonoDimens.spacingLg))
            }

            CompositionLocalProvider(LocalGlassBackdrop provides pageHaze) {
                GlyphButton(
                    text = "Start",
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    height = 56.dp,
                    onClickLabel = "Start the song",
                    onClick = { onEvent(GlyphEvent.StartPlay) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = MonoDimens.spacingLg)
                        .padding(bottom = chromeNow + MonoDimens.spacingSm)
                        .onSizeChanged { startHeight = with(density) { it.height.toDp() } },
                )
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

/**
 * A card of options, arriving a beat after the one above it: a short rise and
 * fade, flat, as Material's own list entrances are.
 */
@Composable
private fun OptionsCard(index: Int, reduced: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        kotlinx.coroutines.delay(index * CARD_STAGGER_MILLIS)
        enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    val density = LocalDensity.current
    GlyphPanel(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * with(density) { 16.dp.toPx() }
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * One option: its name and current value over a row of glass chips, and a
 * line saying what the choice does when that is not obvious from its name.
 */
@Composable
private fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T?,
    valueLabel: (T) -> String,
    onSelect: (T) -> Unit,
    note: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphSectionLabel(label)
            Spacer(Modifier.weight(1f))
            if (selected != null) {
                Text(
                    text = valueLabel(selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphChipRow(
            options = options,
            selected = selected,
            label = valueLabel,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val modifiers = state.gameplay.modifiers
    val mode = modifiers.scrollMode
    val family = when (mode) {
        is GlyphScrollMode.XMod -> GlyphScrollFamily.X
        is GlyphScrollMode.CMod -> GlyphScrollFamily.C
        is GlyphScrollMode.MMod -> GlyphScrollFamily.M
    }
    val (steps, now) = when (mode) {
        is GlyphScrollMode.XMod -> X_SPEEDS to mode.multiplier
        is GlyphScrollMode.CMod -> C_SPEEDS to mode.targetBpm
        is GlyphScrollMode.MMod -> M_SPEEDS to mode.targetBpm
    }
    val bpm = state.simfile?.timing?.startBpm ?: 0f
    val readsAt = when (mode) {
        is GlyphScrollMode.XMod -> if (bpm > 0f) (bpm * mode.multiplier).roundToInt() else null
        is GlyphScrollMode.CMod -> mode.targetBpm.roundToInt()
        is GlyphScrollMode.MMod -> mode.targetBpm.roundToInt()
    }

    CardTitle("Speed")
    OptionRow(
        label = "Speed type",
        options = GlyphScrollFamily.entries.toList(),
        selected = family,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetScrollFamily(it)) },
    )
    OptionRow(
        label = "Speed",
        options = steps,
        selected = steps.minBy { abs(it - now) },
        valueLabel = { step ->
            when (family) {
                GlyphScrollFamily.X -> "%.2fx".format(step)
                GlyphScrollFamily.C -> "C${step.toInt()}"
                GlyphScrollFamily.M -> "M${step.toInt()}"
            }
        },
        onSelect = { onEvent(GlyphEvent.SetScrollValue(it)) },
        note = readsAt?.let { "Reads at $it BPM. ${mode.summary}" } ?: mode.summary,
    )
    OptionRow(
        label = "Rate",
        options = RATES,
        selected = RATES.minBy { abs(it - modifiers.speed) },
        valueLabel = { "%.1fx".format(it) },
        onSelect = { onEvent(GlyphEvent.SetSpeed(it)) },
        note = if (modifiers.speed != 1f) "Scores at this rate are marked on the high score table." else null,
    )
}

@Composable
private fun NoteOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val modifiers = state.gameplay.modifiers
    CardTitle("Notes")
    OptionRow(
        label = "Turn",
        options = GlyphTurn.entries.toList(),
        selected = modifiers.turn,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetTurn(it)) },
    )
    OptionRow(
        label = "Appearance",
        options = GlyphAppearance.entries.toList(),
        selected = modifiers.appearance,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetAppearance(it)) },
    )
    OptionRow(
        label = "Scroll",
        options = listOf(false, true),
        selected = modifiers.reverse,
        valueLabel = { if (it) "Reverse" else "Standard" },
        onSelect = { onEvent(GlyphEvent.SetReverse(it)) },
        note = if (modifiers.reverse) "Receptors at the top, notes rising." else "Receptors at the bottom, notes falling.",
    )
    OptionRow(
        label = "Mini",
        options = MINIS,
        selected = MINIS.minBy { abs(it - modifiers.mini) },
        valueLabel = { "${(it * 100).roundToInt()}%" },
        onSelect = { onEvent(GlyphEvent.SetMini(it)) },
    )
    OptionRow(
        label = "Dark",
        options = listOf(false, true),
        selected = modifiers.dark,
        valueLabel = { if (it) "On" else "Off" },
        onSelect = { onEvent(GlyphEvent.SetDark(it)) },
        note = if (modifiers.dark) "No receptors: the notes are read against nothing." else null,
    )
    OptionRow(
        label = "Noteskin",
        options = GlyphSkin.entries.toList(),
        selected = modifiers.skin,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetSkin(it)) },
    )
}

@Composable
private fun JudgingOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val modifiers = state.gameplay.modifiers
    val judge = GlyphJudge.forScale(modifiers.timingWindowScale)
    CardTitle("Judging and sound")
    OptionRow(
        label = "Judge",
        options = GlyphJudge.entries.toList(),
        selected = judge,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetTimingWindowScale(it.scale)) },
        note = if (judge == GlyphJudge.J4) {
            "J4: StepMania's standard windows."
        } else {
            "Scores at this judge are marked on the high score table."
        },
    )
    OptionRow(
        label = "Fail",
        options = GlyphFailType.entries.toList(),
        selected = modifiers.failType,
        valueLabel = { it.label },
        onSelect = { onEvent(GlyphEvent.SetFailType(it)) },
        note = when (modifiers.failType) {
            GlyphFailType.IMMEDIATE -> "The song stops when the life bar empties."
            GlyphFailType.IMMEDIATE_CONTINUE -> "An empty bar is a fail, but the song plays on."
            GlyphFailType.END_OF_SONG -> "Only a bar empty at the end is a fail."
            GlyphFailType.OFF -> "Never fail."
        },
    )
    OptionRow(
        label = "Metronome",
        options = listOf(false, true),
        selected = modifiers.metronome,
        valueLabel = { if (it) "On" else "Off" },
        onSelect = { if (it != modifiers.metronome) onEvent(GlyphEvent.ToggleMetronome) },
    )
    OptionRow(
        label = "Song previews",
        options = listOf(false, true),
        selected = state.previews,
        valueLabel = { if (it) "On" else "Off" },
        onSelect = { onEvent(GlyphEvent.SetPreviews(it)) },
        note = "Tapping a song plays it from its drop. It pauses the app's music while it plays.",
    )
}

/**
 * What the playfield draws around the notes. All of it is looks: turning an
 * effect off changes nothing about how a step is judged.
 */
@Composable
private fun GraphicsOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val graphics = state.gameplay.modifiers.graphics
    val set: (GlyphGraphics) -> Unit = { onEvent(GlyphEvent.SetGraphics(it)) }
    CardTitle("Graphics")
    OnOffRow(
        label = "Hit god rays",
        on = graphics.hitRays,
        onChange = { set(graphics.copy(hitRays = it)) },
        note = "Shafts of light from each hit, coloured by the combo. Android 13 and later.",
    )
    if (graphics.hitRays) {
        OptionRow(
            label = "Ray quality",
            options = GlyphGraphics.RAY_QUALITIES,
            selected = GlyphGraphics.RAY_QUALITIES.minBy { abs(it - graphics.rayQuality) },
            valueLabel = { GlyphGraphics.rayQualityLabel(it) },
            onSelect = { set(graphics.copy(rayQuality = it)) },
            note = if (graphics.rayQuality > 1) "Smoother shafts, at more GPU work per hit." else null,
        )
        OnOffRow(
            label = "Light on notes",
            on = graphics.noteLighting,
            onChange = { set(graphics.copy(noteLighting = it)) },
        )
    }
    OnOffRow(
        label = "Explosions",
        on = graphics.explosions,
        onChange = { set(graphics.copy(explosions = it)) },
    )
    OnOffRow(
        label = "Beat flash",
        on = graphics.beatFlash,
        onChange = { set(graphics.copy(beatFlash = it)) },
        note = "Receptors flash on every beat.",
    )
    OnOffRow(
        label = "Combo burst",
        on = graphics.comboBurst,
        onChange = { set(graphics.copy(comboBurst = it)) },
        note = "A burst across the field every 50 combo.",
    )
}

@Composable
private fun OnOffRow(label: String, on: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    OptionRow(
        label = label,
        options = listOf(false, true),
        selected = on,
        valueLabel = { if (it) "On" else "Off" },
        onSelect = onChange,
        note = note,
    )
}

@Composable
private fun NameOption(name: String, onEdit: () -> Unit) {
    CardTitle("High scores")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            GlyphSectionLabel("Name")
            Text(
                text = name.ifBlank { "PLAYER" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        GlyphButton(
            text = "Change",
            height = 40.dp,
            onClickLabel = "Change your name on the high score table",
            onClick = onEdit,
        )
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

private const val CARD_STAGGER_MILLIS = 50L

private val X_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f, 3.5f, 4f, 5f, 6f, 8f)
private val C_SPEEDS = (100..1000 step 50).map { it.toFloat() }
private val M_SPEEDS = (200..1000 step 50).map { it.toFloat() }
private val RATES = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f)
private val MINIS = listOf(0f, 0.25f, 0.5f)
