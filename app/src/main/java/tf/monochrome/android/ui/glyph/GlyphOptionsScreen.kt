// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlin.math.roundToInt
import tf.monochrome.android.R
import tf.monochrome.android.glyph.asset.GlyphAssetRepository
import tf.monochrome.android.glyph.asset.GlyphPalette
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphJudge
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer
import tf.monochrome.android.glyph.engine.GlyphScrollFamily
import tf.monochrome.android.glyph.engine.GlyphScrollMode
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * StepMania's Player Options, between choosing a song and playing it, with
 * the song already playing on a field at the top.
 *
 * The preview is the game's own playfield running this chart on autoplay, in
 * time with the song's preview when it is playing: every option below changes
 * it as it is chosen, so the speed is seen at the speed it will be read at,
 * and Mirror, Hidden, Reverse and the rest are seen before they are played.
 * Under it, one line says what is set, and the options themselves sit under
 * three glass tabs: Speed, Notes and Judge. Graphics, sound and the name on
 * the scores are in Settings, being the same for every song.
 *
 * Every choice is kept between sessions, as a StepMania profile keeps its
 * modifiers, except the music rate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphOptionsScreen(
    state: GlyphUiState,
    assets: GlyphAssetRepository,
    palette: GlyphPalette?,
    /** Where the song preview is, in song seconds, or null when it is quiet. */
    previewPosition: () -> Float?,
    /** The stretch the song preview plays, start and length. */
    previewClip: Pair<Float, Float>?,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(GlyphEvent.CloseOptions) }
    val modifiers = state.gameplay.modifiers
    var tab by rememberSaveable { mutableStateOf(OptionsTab.SPEED) }

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
                                    append(" · ").append(chart.course.difficultyLabel(chart.difficulty)).append(' ').append(chart.meter)
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

        // The playfield is the mode's own, on its own near-black.
        val chart = state.chart
        val timing = state.simfile?.timing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clipToBounds()
                .glyphTvShader(modifiers.graphics)
                .background(GlyphTheme.Ink)
                .clearAndSetSemantics { contentDescription = "Preview of the chart with these options" },
        ) {
            if (chart != null && timing != null) {
                val firstNote = chart.notes.firstOrNull()?.timeSeconds ?: 0f
                val loop = previewClip ?: (
                    (state.simfile?.sampleStartSeconds ?: firstNote) to
                        (state.simfile?.sampleLengthSeconds ?: GlyphPreviewPlayer.DEFAULT_LENGTH_SECONDS)
                    )
                GlyphPreviewField(
                    chart = chart,
                    timing = timing,
                    turn = modifiers.turn,
                    scrollMode = modifiers.scrollMode,
                    skin = modifiers.skin,
                    look = GlyphFieldLook.of(modifiers),
                    assets = assets,
                    palette = palette,
                    loop = loop,
                    receptorInset = PREVIEW_RECEPTOR_INSET,
                    noteScale = PREVIEW_NOTE_SCALE,
                    reducedMotion = modifiers.reducedMotion,
                    musicPosition = previewPosition,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            text = summaryOf(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingSm),
        )

        GlyphSegmented(
            options = OptionsTab.entries,
            selected = tab,
            label = { it.label },
            onSelect = { tab = it },
            description = "Option group",
            modifier = Modifier.padding(horizontal = MonoDimens.spacingLg),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(pageHaze)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MonoDimens.spacingLg),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingLg),
            ) {
                Spacer(Modifier.height(MonoDimens.spacingXs))
                // No backdrop for the controls: they are inside the page that
                // is the haze source, and glass frosting the layer it is drawn
                // in draws itself without end. On device that was a stack
                // overflow in the render thread the moment the page opened.
                when (tab) {
                    OptionsTab.SPEED -> SpeedOptions(state, onEvent)
                    OptionsTab.NOTES -> NoteOptions(state, onEvent)
                    OptionsTab.JUDGE -> JudgeOptions(state, onEvent)
                }
                // The last control scrolls clear of Start, the mini player
                // and the tab bar, while the page still runs under their glass.
                Spacer(Modifier.height(chromeInset + startHeight + MonoDimens.spacingLg))
            }

            CompositionLocalProvider(LocalGlassBackdrop provides pageHaze) {
                GlyphButton(
                    text = "Start",
                    icon = Icons.Default.PlayArrow,
                    primary = true,
                    filled = true,
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
}

private enum class OptionsTab(val label: String) { SPEED("Speed"), NOTES("Notes"), JUDGE("Judge") }

/** "C550 reads at 550 BPM · Mirror · Hidden": the speed, then whatever is not StepMania's default. */
@Composable
private fun summaryOf(state: GlyphUiState) = buildAnnotatedString {
    val modifiers = state.gameplay.modifiers
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
        append(speedLabel(modifiers.scrollMode))
    }
    readsAt(state)?.let { append(" reads at $it BPM") }
    val changed = buildList {
        if (modifiers.speed != 1f) add("%.1fx rate".format(modifiers.speed))
        if (modifiers.turn != GlyphTurn.OFF) add(modifiers.turn.label)
        if (modifiers.appearance != GlyphAppearance.VISIBLE) add(modifiers.appearance.label)
        if (modifiers.reverse) add("Reverse")
        if (modifiers.mini > 0f) add("Mini ${(modifiers.mini * 100).roundToInt()}%")
        if (modifiers.dark) add("No receptors")
        if (modifiers.timingWindowScale != 1f) add(GlyphJudge.forScale(modifiers.timingWindowScale).label)
        if (modifiers.failType != GlyphFailType.IMMEDIATE) add("Fail ${modifiers.failType.label}")
    }
    for (item in changed) {
        append(" · ")
        append(item)
    }
}

private fun speedLabel(mode: GlyphScrollMode): String = when (mode) {
    is GlyphScrollMode.XMod -> "%sx".format(trimmed(mode.multiplier))
    is GlyphScrollMode.CMod -> "C${mode.targetBpm.toInt()}"
    is GlyphScrollMode.MMod -> "M${mode.targetBpm.toInt()}"
}

private fun trimmed(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value).trimEnd('0')

private fun readsAt(state: GlyphUiState): Int? {
    val bpm = state.simfile?.timing?.startBpm ?: 0f
    return when (val mode = state.gameplay.modifiers.scrollMode) {
        is GlyphScrollMode.XMod -> if (bpm > 0f) (bpm * mode.multiplier).roundToInt() else null
        is GlyphScrollMode.CMod -> mode.targetBpm.roundToInt()
        is GlyphScrollMode.MMod -> mode.targetBpm.roundToInt()
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
    val index = steps.indices.minBy { abs(steps[it] - now) }

    Column {
        GlyphControlHeader(
            "Speed type",
            when (family) {
                GlyphScrollFamily.X -> "A multiple of the song's BPM"
                GlyphScrollFamily.C -> "Constant: tempo changes do not move it"
                GlyphScrollFamily.M -> "The fastest part read at the target"
            },
        )
        GlyphSegmented(
            options = GlyphScrollFamily.entries.toList(),
            selected = family,
            label = { it.label },
            onSelect = { onEvent(GlyphEvent.SetScrollFamily(it)) },
            description = "Speed type",
        )
    }
    Column {
        GlyphControlHeader("Speed", readsAt(state)?.let { "reads at $it BPM" })
        GlyphStepper(
            value = speedLabel(mode),
            onLess = { onEvent(GlyphEvent.SetScrollValue(steps[(index - 1).coerceAtLeast(0)])) },
            onMore = { onEvent(GlyphEvent.SetScrollValue(steps[(index + 1).coerceAtMost(steps.lastIndex)])) },
            lessLabel = "Slower",
            moreLabel = "Faster",
            lessEnabled = index > 0,
            moreEnabled = index < steps.lastIndex,
        )
        Spacer(Modifier.height(MonoDimens.spacingMd))
        SpeedRuler(index = index, count = steps.size)
    }
    Column {
        val rateIndex = RATES.indices.minBy { abs(RATES[it] - modifiers.speed) }
        GlyphControlHeader(
            "Music rate",
            if (modifiers.speed == 1f) "As recorded" else "Runs at this rate are marked on the scores",
        )
        GlyphStepper(
            value = "%.2fx".format(RATES[rateIndex]).replace(Regex("0x$"), "x"),
            onLess = { onEvent(GlyphEvent.SetSpeed(RATES[(rateIndex - 1).coerceAtLeast(0)])) },
            onMore = { onEvent(GlyphEvent.SetSpeed(RATES[(rateIndex + 1).coerceAtMost(RATES.lastIndex)])) },
            lessLabel = "Slower music",
            moreLabel = "Faster music",
            buttonSize = 46.dp,
            valueStyle = MaterialTheme.typography.headlineSmall,
            lessEnabled = rateIndex > 0,
            moreEnabled = rateIndex < RATES.lastIndex,
        )
    }
}

/** Where the speed sits in its range: a tick a step, the chosen one marked. */
@Composable
private fun SpeedRuler(index: Int, count: Int) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(horizontal = 10.dp)
            .clearAndSetSemantics {},
    ) {
        val last = (count - 1).coerceAtLeast(1)
        drawRect(ink.copy(alpha = 0.14f), topLeft = Offset(0f, size.height / 2f - 1f), size = Size(size.width, 2f))
        for (i in 0 until count) {
            val x = size.width * i / last
            val major = i % 4 == 0
            val h = if (major) size.height * 0.66f else size.height * 0.44f
            drawRect(ink.copy(alpha = 0.3f), topLeft = Offset(x - 1f, (size.height - h) / 2f), size = Size(2f, h))
        }
        val x = size.width * index / last
        drawRoundRect(
            color = ink,
            topLeft = Offset(x - 2.5f * density, 0f),
            size = Size(5f * density, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * density),
        )
    }
}

@Composable
private fun NoteOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val modifiers = state.gameplay.modifiers
    Column {
        GlyphControlHeader("Turn", if (modifiers.turn == GlyphTurn.OFF) "As written" else "Columns moved")
        GlyphSegmented(
            options = GlyphTurn.entries.toList(),
            selected = modifiers.turn,
            label = { it.label },
            onSelect = { onEvent(GlyphEvent.SetTurn(it)) },
            description = "Turn",
            textStyle = MaterialTheme.typography.labelMedium,
        )
    }
    Column {
        GlyphControlHeader(
            "Appearance",
            when (modifiers.appearance) {
                GlyphAppearance.VISIBLE -> "Every note"
                GlyphAppearance.HIDDEN -> "Gone before the receptors"
                GlyphAppearance.SUDDEN -> "Shows up late"
                GlyphAppearance.STEALTH -> "No notes at all"
            },
        )
        GlyphSegmented(
            options = GlyphAppearance.entries.toList(),
            selected = modifiers.appearance,
            label = { it.label },
            onSelect = { onEvent(GlyphEvent.SetAppearance(it)) },
            description = "Appearance",
        )
    }
    Column {
        GlyphControlHeader("Scroll", if (modifiers.reverse) "Notes rise to the top" else "Notes fall to the bottom")
        GlyphSegmented(
            options = listOf(false, true),
            selected = modifiers.reverse,
            label = { if (it) "Reverse" else "Standard" },
            onSelect = { onEvent(GlyphEvent.SetReverse(it)) },
            description = "Scroll",
        )
    }
    Column {
        GlyphControlHeader("Mini", if (modifiers.mini > 0f) "Notes smaller" else "Full size")
        GlyphSegmented(
            options = MINIS,
            selected = MINIS.minBy { abs(it - modifiers.mini) },
            label = { "${(it * 100).roundToInt()}%" },
            onSelect = { onEvent(GlyphEvent.SetMini(it)) },
            description = "Mini",
        )
    }
    Column {
        // StepMania's Dark, named for what it does.
        GlyphControlHeader("Receptors", if (modifiers.dark) "StepMania's Dark: no targets" else "Targets drawn")
        GlyphSegmented(
            options = listOf(false, true),
            selected = modifiers.dark,
            label = { if (it) "Hidden" else "Shown" },
            onSelect = { onEvent(GlyphEvent.SetDark(it)) },
            description = "Receptors",
        )
    }
    Column {
        GlyphControlHeader("Noteskin", null)
        GlyphSegmented(
            options = GlyphSkin.entries.toList(),
            selected = modifiers.skin,
            label = { it.label },
            onSelect = { onEvent(GlyphEvent.SetSkin(it)) },
            description = "Noteskin",
        )
    }
}

@Composable
private fun JudgeOptions(state: GlyphUiState, onEvent: (GlyphEvent) -> Unit) {
    val modifiers = state.gameplay.modifiers
    val judge = GlyphJudge.forScale(modifiers.timingWindowScale)
    Column {
        GlyphControlHeader(
            "Judge",
            when (judge) {
                GlyphJudge.J1 -> "J1: the widest windows. Marked on the scores"
                GlyphJudge.J4 -> "J4: StepMania's standard windows"
                GlyphJudge.JUSTICE -> "Justice: the tightest. Marked on the scores"
                else -> "${judge.label}: marked on the scores"
            },
        )
        GlyphSegmented(
            options = GlyphJudge.entries.toList(),
            selected = judge,
            label = { "J${it.level}" },
            onSelect = { onEvent(GlyphEvent.SetTimingWindowScale(it.scale)) },
            description = "Judge",
            textStyle = MaterialTheme.typography.labelMedium,
        )
    }
    Column {
        GlyphControlHeader(
            "Fail",
            when (modifiers.failType) {
                GlyphFailType.IMMEDIATE -> "The song stops when the bar empties"
                GlyphFailType.IMMEDIATE_CONTINUE -> "Failed when it empties; the song plays on"
                GlyphFailType.END_OF_SONG -> "Only a bar empty at the end fails"
                GlyphFailType.OFF -> "You never fail"
            },
        )
        GlyphSegmented(
            options = GlyphFailType.entries.toList(),
            selected = modifiers.failType,
            label = {
                when (it) {
                    GlyphFailType.IMMEDIATE -> "Immediate"
                    GlyphFailType.IMMEDIATE_CONTINUE -> "Imm. cont."
                    GlyphFailType.END_OF_SONG -> "End"
                    GlyphFailType.OFF -> "Off"
                }
            },
            onSelect = { onEvent(GlyphEvent.SetFailType(it)) },
            description = "Fail",
            textStyle = MaterialTheme.typography.labelMedium,
        )
    }
}

private val PREVIEW_HEIGHT = 220.dp
private val PREVIEW_RECEPTOR_INSET = 18.dp
private const val PREVIEW_NOTE_SCALE = 0.6f

private val X_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f, 3.5f, 4f, 5f, 6f, 8f)
private val C_SPEEDS = (100..1000 step 50).map { it.toFloat() }
private val M_SPEEDS = (200..1000 step 50).map { it.toFloat() }
private val RATES = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f)
private val MINIS = listOf(0f, 0.25f, 0.5f)
