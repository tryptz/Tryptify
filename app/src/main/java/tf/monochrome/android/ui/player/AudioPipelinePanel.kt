package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.pipeline.buildAudioPipelineSnapshot
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.audio.pipeline.AudioPipelineSnapshot
import tf.monochrome.android.audio.pipeline.PipelineField
import tf.monochrome.android.audio.pipeline.PipelineSection
import tf.monochrome.android.audio.pipeline.PipelineStage
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The signal path, stage by stage, for the track that is playing.
 *
 * Read top to bottom in the order audio moves: what the file is, what decoded
 * it, what changed its rate, what the DSP did to it, and where it went. The
 * arrows between stages are the point of the layout — this is a chain, and a
 * flat list of five headings would not say so.
 *
 * A stage whose fields are all dashes is still drawn. Its absence is a fact
 * about the pipeline ("nothing has reported a decoder yet"), and a panel that
 * hides the stages it cannot fill would leave the reader unsure whether the
 * stage is missing or the information is.
 */
@Composable
internal fun AudioPipelineContent(
    snapshot: AudioPipelineSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        snapshot.sections.forEachIndexed { index, section ->
            if (section.bypassed) {
                BypassedStage(section = section, accent = accentFor(index))
            } else {
                PipelineStageCard(section = section, accent = accentFor(index))
            }
            if (index != snapshot.sections.lastIndex) {
                PipelineConnector()
            }
        }
    }
}

/**
 * A stage the signal skips.
 *
 * The card steps aside into an indent and the through-line runs straight down
 * the lane it vacates, so the route itself says "past, not through" — which a
 * card full of "None" and "Inactive" never did. Still drawn rather than
 * hidden: that the resampler is idle is the fact worth showing.
 */
@Composable
private fun BypassedStage(section: PipelineSection, accent: Color) {
    val stroke = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Box(modifier = Modifier.fillMaxWidth()) {
        PipelineStageCard(
            section = section,
            accent = accent,
            modifier = Modifier.padding(start = BYPASS_LANE),
        )
        // matchParentSize so the lane spans exactly the card it is passing,
        // however tall that card turns out to be.
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val x = CONNECTOR_INSET.toPx() + RAIL_WIDTH.toPx() / 2f
            drawLine(
                color = stroke,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Three theme accents rotating, rather than five invented hues.
 *
 * Which hue a stage gets still carries no meaning — it is there to separate
 * the stages at a glance, and borrowing the scheme's own accents keeps the
 * panel inside whatever theme the user is running. Two stages sharing a colour
 * is fine; they are never adjacent.
 *
 * Whether a stage is *shown* in its accent at all is the meaningful part, and
 * that is [PipelineSection.engaged], applied in [PipelineStageCard]. An idle
 * stage drops to muted ink, so colour reads as "working" without any single
 * hue having to stand for a particular engine.
 */
@Composable
private fun accentFor(index: Int): Color = when (index % 3) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

private fun iconFor(stage: PipelineStage): ImageVector = when (stage) {
    PipelineStage.TRACK -> Icons.Default.MusicNote
    PipelineStage.DECODER -> Icons.Default.Memory
    PipelineStage.RESAMPLER -> Icons.Default.Tune
    PipelineStage.DSP -> Icons.Default.GraphicEq
    PipelineStage.OUTPUT -> Icons.Default.Headphones
}

@Composable
private fun PipelineStageCard(
    section: PipelineSection,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // Lit means in use. An idle stage drops to the panel's muted ink rather
    // than keeping a colour it has not earned, so the accents now read as
    // "these are the engines working on your audio" instead of as decoration.
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (section.engaged) accent else muted.copy(alpha = 0.55f)
    val chip = if (section.engaged) accent.copy(alpha = 0.16f) else muted.copy(alpha = 0.07f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MonoDimens.shapeMd,
        color = MaterialTheme.colorScheme.surfaceVariant
            .copy(alpha = if (section.engaged) 0.16f else 0.08f),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Surface(
                modifier = Modifier.size(RAIL_WIDTH),
                shape = MonoDimens.shapeSm,
                color = chip,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        iconFor(section.stage),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        section.stage.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                    if (section.engaged) {
                        Spacer(Modifier.width(6.dp))
                        // The light itself: a lit pip beside the name, so "in
                        // use" survives being read in a theme where the accent
                        // and the muted ink sit close together.
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(accent, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                section.fields.forEach { FieldRow(it) }
                section.note?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

/**
 * A label on the left, its value on the right.
 *
 * An unknown value is dimmed as well as dashed. The dash alone reads as a
 * layout placeholder; dimmed, it reads as "there is nothing here", which is
 * what it means.
 */
@Composable
private fun FieldRow(field: PipelineField) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            field.display,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (field.isKnown) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.End,
            color = if (field.isKnown) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The arrow from one stage down into the next, aligned under the icon rail. */
@Composable
private fun PipelineConnector() {
    val stroke = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .padding(start = CONNECTOR_INSET)
            .width(RAIL_WIDTH)
            .height(CONNECTOR_HEIGHT),
    ) {
        val x = size.width / 2f
        val head = 5.dp.toPx()
        drawLine(
            color = stroke,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Arrowhead, so the column of connectors reads as a direction rather
        // than as a set of dividers.
        drawLine(
            color = stroke,
            start = Offset(x - head, size.height - head),
            end = Offset(x, size.height),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = stroke,
            start = Offset(x + head, size.height - head),
            end = Offset(x, size.height),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private val RAIL_WIDTH = 36.dp
private val CONNECTOR_HEIGHT = 22.dp

/**
 * Left inset shared by the connectors and the bypass lane, so the through-line
 * is one unbroken column down the panel whether it is passing a stage or
 * entering one.
 */
private val CONNECTOR_INSET = 14.dp

/**
 * How far a bypassed card steps aside. Wide enough to clear the lane the
 * through-line runs in, which is [CONNECTOR_INSET] plus half [RAIL_WIDTH].
 */
private val BYPASS_LANE = 44.dp

/**
 * The Audio Pipeline panel, over the player.
 *
 * Same construction as `SpeedPanel`: an in-window `BoxScope` pane rather than
 * a `ModalBottomSheet`, because a modal sheet is its own window and haze
 * cannot sample the player's backdrop through one — the glass would come out
 * a solid slab.
 *
 * It differs in one way that matters. `SpeedPanel` puts the dismiss drag on
 * its whole content, which is fine for a column of sliders: they claim
 * horizontal movement, the drag claims vertical, and whichever the finger
 * goes first takes the gesture. This panel scrolls vertically, so the same
 * arrangement would have the drag and the scroll fighting over every gesture.
 * The drag lives on the grab handle alone, and the body scrolls.
 *
 * [track] supplies the fallbacks from the library row — the tags — for the
 * fields the live path has not reported. The rest comes from the view model.
 */
@Composable
internal fun BoxScope.AudioPipelinePanel(
    visible: Boolean,
    track: UnifiedTrack?,
    onDismiss: () -> Unit,
    viewModel: AudioPipelineViewModel = hiltViewModel(),
) {
    BackHandler(enabled = visible) { onDismiss() }

    val dragScope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    var panelHeight by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (visible) dragY.snapTo(0f) }
    val dragState = rememberDraggableState { delta ->
        dragScope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (panelHeight > 0f) {
                        (1f - dragY.value / panelHeight).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        // Collected inside the visibility gate, so none of the seven flows
        // behind it — nor the once-a-second poll — runs while the panel is
        // shut. That is the whole reason the view model is WhileSubscribed.
        val inputs by viewModel.inputs.collectAsStateWithLifecycle()
        val snapshot = remember(inputs, track) {
            buildAudioPipelineSnapshot(
                inputs.copy(
                    taggedCodec = track?.codec?.displayName,
                    taggedBitDepth = track?.bitDepth,
                    taggedBitRateKbps = track?.bitRate,
                )
            )
        }
        Box(
            modifier = Modifier
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .graphicsLayer { translationY = dragY.value },
        ) {
            GlassPanel(
                hazeState = LocalPlayerHaze.current,
                glass = LocalMiniPlayerGlass.current,
                avoidNavigationBar = false,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp),
                ) {
                    // The handle is the drag target. Sized generously and
                    // given the whole width, because it is the only way out
                    // by gesture once the body owns vertical movement.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    val far = panelHeight > 0f &&
                                        dragY.value > panelHeight * 0.3f
                                    if (far || velocity > 900f) {
                                        onDismiss()
                                    } else {
                                        dragY.animateTo(0f, spring(stiffness = 400f))
                                    }
                                },
                            )
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(width = 34.dp, height = 4.dp),
                                shape = MonoDimens.shapeSm,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.4f),
                            ) {}
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Audio Pipeline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    AudioPipelineContent(
                        snapshot = snapshot,
                        modifier = Modifier
                            // Capped so the player stays partly visible
                            // behind it, and scrolled because five stages do
                            // not fit on a phone.
                            .heightIn(max = PANEL_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

private val PANEL_MAX_HEIGHT = 460.dp
