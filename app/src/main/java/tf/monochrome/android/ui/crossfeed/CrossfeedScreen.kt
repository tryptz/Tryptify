// SPDX-License-Identifier: GPL-3.0-or-later
// The Crossfeed page: Off, Crossfeed or Room. Crossfeed is the speaker-angle
// model or a classic network; Room is the mixer's Binaural Room on its own,
// with its own HRTF and an LFE. Either is drawn flat from above or in 3D.

package tf.monochrome.android.ui.crossfeed

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.audio.atmos.AtmosNative
import tf.monochrome.android.audio.dsp.BinauralRoomNative
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedAlgorithm
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedKeeper
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedMode
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedState
import tf.monochrome.android.audio.dsp.crossfeed.RoomSettings
import tf.monochrome.android.domain.model.CustomRoom
import tf.monochrome.android.domain.model.RoomShape
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.mixer.fxchain.FxPresets
import tf.monochrome.android.ui.mixer.fxchain.FxVisualMath
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.mixer.fxchain.roomLayout
import tf.monochrome.android.ui.settings.AtmosRoom3d
import tf.monochrome.android.ui.settings.BUILT_IN_ROOM_DECAY
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@Composable
fun CrossfeedScreen(
    viewModel: CrossfeedViewModel,
    onBrowseHrtf: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val effect = viewModel.crossfeed
    val state by effect.state.collectAsStateWithLifecycle()
    val hrtf by viewModel.hrtf.collectAsStateWithLifecycle()
    val sofaFiles by viewModel.sofaFiles.collectAsStateWithLifecycle()
    // Back from the database: a set it downloaded is on the list.
    LaunchedEffect(Unit) { viewModel.rescan() }
    val sofaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importSofa(uri)
    }
    val accent = MaterialTheme.colorScheme.primary
    val room = state.room

    Column(modifier = modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = stringResource(R.string.crossfeed),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomChromeInset.current)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            PageCard {
                Text(
                    text = stringResource(R.string.speaker_simulation),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                // Off, Crossfeed or Room: one choice, the page's switch.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChoiceChip(
                        label = stringResource(R.string.state_off),
                        selected = !state.enabled,
                        accent = accent,
                        onClick = { effect.setMode(null) },
                    )
                    GlassChoiceChip(
                        label = stringResource(R.string.crossfeed),
                        selected = state.crossfeedOn,
                        accent = accent,
                        onClick = { effect.setMode(CrossfeedMode.CROSSFEED) },
                    )
                    GlassChoiceChip(
                        label = stringResource(R.string.atmos_section_room),
                        selected = state.roomOn,
                        accent = accent,
                        onClick = { effect.setMode(CrossfeedMode.ROOM) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (state.mode == CrossfeedMode.ROOM) R.string.crossfeed_room_explainer else R.string.crossfeed_explainer
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The picture, for the mode chosen (or last chosen, while Off).
                val drawn = state.mode == CrossfeedMode.ROOM || state.algorithm == CrossfeedAlgorithm.SPEAKER
                if (drawn) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Not translated: "2D" and "3D" read the same in every language.
                        GlassChoiceChip(label = "2D", selected = !state.view3d, accent = accent, onClick = { effect.setView3d(false) })
                        GlassChoiceChip(label = "3D", selected = state.view3d, accent = accent, onClick = { effect.setView3d(true) })
                    }
                    Spacer(Modifier.height(8.dp))
                    val lit = state.enabled
                    val stageModifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (lit) 1f else 0.45f)
                    if (state.mode == CrossfeedMode.ROOM) {
                        if (state.view3d) {
                            SpeakerRoom3d(
                                angleEachSide = room.angleDeg,
                                roomAmount = room.roomPercent / 100f,
                                room = room,
                                lfe = room.lfe,
                                playing = lit,
                                modifier = stageModifier,
                            )
                        } else {
                            RoomStage(room = room, enabled = lit, modifier = stageModifier.aspectRatio(1.2f))
                        }
                    } else if (state.view3d) {
                        // Crossfeed has no room: the pair alone, in the built-in one's space.
                        SpeakerRoom3d(
                            angleEachSide = state.speakerAngleDeg / 2f,
                            roomAmount = 0f,
                            room = null,
                            lfe = false,
                            playing = lit,
                            modifier = stageModifier,
                        )
                    } else {
                        SpeakerStage(
                            angleDeg = state.speakerAngleDeg,
                            enabled = lit,
                            modifier = stageModifier.aspectRatio(1.45f),
                        )
                    }
                }

                if (state.mode == CrossfeedMode.CROSSFEED) {
                    CrossfeedControls(state = state, effect = effect)
                } else {
                    RoomPresets(room = room, onApply = { values -> effect.updateRoom { it.withRoom(values) } })
                    RoomControls(room = room, enabled = state.enabled, onChange = effect::updateRoom)
                }
            }

            if (state.mode == CrossfeedMode.ROOM) {
                Spacer(Modifier.height(12.dp))
                // The HRTF the Room is heard through, inside the Room.
                PageCard {
                    Text(
                        text = stringResource(R.string.atmos_section_hrtf),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    HrtfChoice(
                        label = stringResource(R.string.crossfeed_hrtf_built_in),
                        selected = room.hrtfPath == null,
                        onClick = { viewModel.chooseHrtf(null) },
                    )
                    sofaFiles.forEach { file ->
                        HrtfChoice(
                            label = file.name.removeSuffix(".sofa"),
                            selected = room.hrtfPath == file.absolutePath,
                            onClick = { viewModel.chooseHrtf(file) },
                        )
                    }
                    when (hrtf) {
                        CrossfeedKeeper.Hrtf.LOADING -> StatusLine(stringResource(R.string.crossfeed_hrtf_loading))
                        CrossfeedKeeper.Hrtf.FAILED -> StatusLine(stringResource(R.string.crossfeed_hrtf_failed))
                        else -> Unit
                    }
                    Row {
                        TextButton(onClick = onBrowseHrtf) { Text(stringResource(R.string.atmos_browse_hrtf_database)) }
                        // .sofa has no MIME type of its own: any file, and the reader refuses the rest.
                        TextButton(onClick = { sofaPicker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.atmos_load_sofa_file))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                PageCard {
                    SwitchRow(
                        title = stringResource(R.string.crossfeed_lfe),
                        detail = stringResource(R.string.crossfeed_lfe_desc),
                        checked = room.lfe,
                        onChange = { on -> effect.updateRoom { it.copy(lfe = on) } },
                    )
                    if (room.lfe) {
                        LabeledSlider(
                            label = stringResource(R.string.crossfeed_lfe_crossover),
                            valueText = "${room.lfeHz.roundToInt()} Hz",
                            value = room.lfeHz,
                            range = RoomSettings.MIN_LFE_HZ..RoomSettings.MAX_LFE_HZ,
                            enabled = state.enabled,
                            onChange = { v -> effect.updateRoom { it.copy(lfeHz = v) } },
                        )
                        LabeledSlider(
                            label = stringResource(R.string.crossfeed_lfe_level),
                            valueText = dbText(room.lfeDb),
                            value = room.lfeDb,
                            range = RoomSettings.MIN_LFE_DB..RoomSettings.MAX_LFE_DB,
                            enabled = state.enabled,
                            onChange = { v -> effect.updateRoom { it.copy(lfeDb = v) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PageCard(content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

/** The crossfeed's own controls: its algorithm, and for the speaker model the angle. */
@Composable
private fun CrossfeedControls(state: CrossfeedState, effect: tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect) {
    Spacer(Modifier.height(16.dp))
    // Algorithm picker — the physical speaker model plus the
    // classic fixed networks (BS2B / Chu Moy / Jan Meier).
    Text(
        text = stringResource(R.string.algorithm),
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CrossfeedAlgorithm.entries.forEach { algo ->
            GlassChoiceChip(
                label = algo.label,
                selected = state.algorithm == algo,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { effect.setAlgorithm(algo) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = state.algorithm.blurb,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.algorithm == CrossfeedAlgorithm.SPEAKER) {
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = stringResource(R.string.speaker_angle),
            valueText = "${state.speakerAngleDeg.roundToInt()}°",
            value = state.speakerAngleDeg,
            range = CrossfeedState.MIN_ANGLE_DEG..CrossfeedState.MAX_ANGLE_DEG,
            enabled = state.enabled,
            onChange = effect::setSpeakerAngleDeg,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.crossfeed_angle_narrow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.crossfeed_angle_headphones),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.speaker_angle_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Binaural Room's presets, the same ten its card in the mixer has
 * ([FxPresets]): the real set-ups first, then, past a divider, the ones
 * that enlarge the space. A preset sets the room's nine settings and leaves
 * the LFE and the HRTF, which are this page's own. The one that matches the
 * room as it stands is lit; move a slider and it goes out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomPresets(room: RoomSettings, onApply: (FloatArray) -> Unit) {
    val presets = remember { FxPresets.forType(SnapinType.BINAURAL_ROOM) }
    val defs = remember { getParamDefs(SnapinType.BINAURAL_ROOM) }
    val current = remember(room) { room.roomParams().withIndex().associate { it.index to it.value } }
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Spacer(Modifier.height(12.dp))
    Text(text = stringResource(R.string.visualizer_presets), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        presets.forEachIndexed { i, preset ->
            if (i > 0 && presets[i - 1].mastering && !preset.mastering) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .align(Alignment.CenterVertically)
                        .width(1.dp)
                        .height(18.dp)
                        .background(outline.copy(alpha = 0.35f)),
                )
            }
            GlassChoiceChip(
                label = preset.nameRes?.let { stringResource(it) } ?: preset.name,
                selected = preset.matches(defs, current, 1f),
                accent = accent,
                onClick = { onApply(preset.resolved(defs)) },
            )
        }
    }
}

/** The Binaural Room's own settings, as the mixer's effect has them. */
@Composable
private fun RoomControls(room: RoomSettings, enabled: Boolean, onChange: ((RoomSettings) -> RoomSettings) -> Unit) {
    Spacer(Modifier.height(12.dp))
    LabeledSlider(
        label = stringResource(R.string.atmos_room_amount),
        valueText = "${room.roomPercent.roundToInt()}%",
        value = room.roomPercent,
        range = 0f..100f,
        enabled = enabled,
        onChange = { v -> onChange { it.copy(roomPercent = v) } },
    )
    LabeledSlider(
        label = stringResource(R.string.crossfeed_room_binaural),
        valueText = "${room.binauralPercent.roundToInt()}%",
        value = room.binauralPercent,
        range = 0f..100f,
        enabled = enabled,
        onChange = { v -> onChange { it.copy(binauralPercent = v) } },
    )
    LabeledSlider(
        label = stringResource(R.string.speaker_angle),
        // Each side of straight ahead, as the mixer's Angle knob.
        valueText = "±${room.angleDeg.roundToInt()}°",
        value = room.angleDeg,
        range = 0f..RoomSettings.MAX_ANGLE_DEG,
        enabled = enabled,
        onChange = { v -> onChange { it.copy(angleDeg = v) } },
    )
    LabeledSlider(
        label = stringResource(R.string.crossfeed_room_output),
        valueText = dbText(room.outputDb),
        value = room.outputDb,
        range = -12f..12f,
        enabled = enabled,
        onChange = { v -> onChange { it.copy(outputDb = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.atmos_room_custom),
        detail = null,
        checked = room.myRoom,
        onChange = { on -> onChange { it.copy(myRoom = on) } },
    )
    if (room.myRoom) {
        LabeledSlider(
            label = stringResource(R.string.atmos_room_width),
            valueText = metres(room.widthM),
            value = room.widthM,
            range = RoomSettings.MIN_SIDE_M..RoomSettings.MAX_SIDE_M,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(widthM = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.atmos_room_depth),
            valueText = metres(room.depthM),
            value = room.depthM,
            range = RoomSettings.MIN_SIDE_M..RoomSettings.MAX_SIDE_M,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(depthM = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.atmos_room_height),
            valueText = metres(room.heightM),
            value = room.heightM,
            range = RoomSettings.MIN_HEIGHT_M..RoomSettings.MAX_HEIGHT_M,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(heightM = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.atmos_room_damping),
            valueText = "${room.dampingPercent.roundToInt()}%",
            value = room.dampingPercent,
            range = RoomSettings.MIN_DAMPING..RoomSettings.MAX_DAMPING,
            enabled = enabled,
            onChange = { v -> onChange { it.copy(dampingPercent = v) } },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HrtfChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun dbText(db: Float): String {
    val r = (db * 10f).roundToInt() / 10f
    return (if (r > 0f) "+" else "") + "$r dB"
}

private fun metres(m: Float): String = "${(m * 10f).roundToInt() / 10f} m"

/**
 * The pair in the spatial renderer's room, in 3D: the same [AtmosRoom3d] the
 * mixer's Binaural Room draws, with the speakers the pair stands at lit
 * ([FxVisualMath.roomPair] on the effect's own ring), and the LFE's speaker
 * with them while the bass is outside the room. Nothing here is measured:
 * the page has no meter, so a speaker that plays is drawn at one level.
 */
@Composable
private fun SpeakerRoom3d(
    angleEachSide: Float,
    roomAmount: Float,
    room: RoomSettings?,
    lfe: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val layout = remember { roomLayout() }
    val ring = remember { BinauralRoomNative.ring }
    val count = layout.size / AtmosNative.ROOM_FIELDS
    if (count == 0) return
    val speakers: FloatArray? = if (!playing || ring == null) null else {
        val (inner, gInner, gOuter) = FxVisualMath.roomPair(angleEachSide, ring.degrees)
        FloatArray(count).also { out ->
            for (side in arrayOf(ring.left, ring.right)) {
                side.getOrNull(inner)?.takeIf { it in 0 until count }?.let { out[it] += PLAYING * gInner }
                side.getOrNull(inner + 1)?.takeIf { it in 0 until count }?.let { out[it] += PLAYING * gOuter }
            }
            if (lfe) {
                for (s in 0 until count) {
                    if (layout[s * AtmosNative.ROOM_FIELDS + 3] >= 0.5f) out[s] = PLAYING
                }
            }
            for (i in out.indices) out[i] = out[i].coerceAtMost(1f)
        }
    }
    val my = room?.myRoom == true
    val shape = remember(my, room?.widthM, room?.depthM, room?.heightM) {
        if (my && room != null) RoomShape.of(CustomRoom(width = room.widthM, depth = room.depthM, height = room.heightM))
        else RoomShape.BUILT_IN
    }
    AtmosRoom3d(
        layout = layout,
        speakers = speakers,
        objects = null,
        shape = shape,
        roomAmount = roomAmount.coerceIn(0f, 1f),
        decaySeconds = if (my && room != null) {
            FxVisualMath.roomDecaySeconds(room.widthM, room.depthM, room.heightM, room.dampingPercent / 100f)
        } else BUILT_IN_ROOM_DECAY,
        modifier = modifier.height(240.dp),
        sound = speakers != null,
    )
}

/** How bright a playing speaker is drawn, with no meter to say. */
private const val PLAYING = 0.8f

/**
 * The Room flat from above: the room's walls (the built-in room, or My room
 * at its width and depth), the listener in the middle, the pair at the angle
 * each side, and while the LFE is on its one speaker in front, feeding both
 * ears. The floor is as bright as Room lets the room in.
 */
@Composable
private fun RoomStage(room: RoomSettings, enabled: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val speakerBody = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier) {
        val width = if (room.myRoom) room.widthM else RoomShape.BUILT_IN.width
        val depth = if (room.myRoom) room.depthM else RoomShape.BUILT_IN.depth
        val pad = 12.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / width, (size.height - 2 * pad) / depth)
        val w = width * scale
        val d = depth * scale
        val left = (size.width - w) / 2f
        val top = (size.height - d) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        // The floor, lit by how much room is heard, and the walls.
        drawRoundRect(
            color = accent.copy(alpha = 0.05f + 0.20f * (room.roomPercent / 100f)),
            topLeft = Offset(left, top),
            size = Size(w, d),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
        drawRoundRect(
            color = outline.copy(alpha = 0.6f),
            topLeft = Offset(left, top),
            size = Size(w, d),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        // The pair, on a circle round the listener, at the angle each side.
        val radius = minOf(w, d) * 0.36f
        val half = Math.toRadians(room.angleDeg.toDouble()).toFloat()
        val azimuths = floatArrayOf(-half, half)
        val positions = azimuths.map { az -> Offset(cx + radius * sin(az), cy - radius * cos(az)) }
        drawCircle(
            color = outline.copy(alpha = 0.3f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
        )
        for (p in positions) {
            drawLine(
                color = accent.copy(alpha = if (enabled) 0.45f else 0.25f),
                start = p,
                end = Offset(cx, cy),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
            )
        }
        // The LFE: one speaker, low in front, to both ears.
        if (room.lfe) {
            val sub = Offset(cx, cy + radius * 0.75f)
            val headR = radius * 0.16f
            for (ear in intArrayOf(-1, 1)) {
                drawLine(
                    color = accent.copy(alpha = if (enabled) 0.6f else 0.3f),
                    start = sub,
                    end = Offset(cx + ear * headR, cy),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            val s = radius * 0.2f
            drawRoundRect(
                color = speakerBody,
                topLeft = Offset(sub.x - s / 2f, sub.y - s / 2f),
                size = Size(s, s),
                cornerRadius = CornerRadius(s * 0.15f, s * 0.15f),
            )
            drawCircle(color = if (enabled) accent else outline, radius = s * 0.32f, center = sub)
        }
        // The listener, facing the front wall.
        val headR = radius * 0.16f
        drawCircle(color = surface, radius = headR, center = Offset(cx, cy))
        drawCircle(color = outline, radius = headR, center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
        for (side in intArrayOf(-1, 1)) {
            drawCircle(
                color = outline,
                radius = headR * 0.28f,
                center = Offset(cx + side * headR, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        val nose = Path().apply {
            moveTo(cx - headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx + headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx, cy - headR * 1.38f)
            close()
        }
        drawPath(nose, color = outline)
        positions.forEachIndexed { i, p ->
            drawSpeaker(
                at = p,
                facingDeg = Math.toDegrees(azimuths[i].toDouble()).toFloat(),
                scale = radius,
                body = speakerBody,
                cone = if (enabled) accent else outline,
            )
        }
    }
}

/**
 * Top-down view of the listening setup: the listener's head at the sweet spot
 * with the two virtual speakers on an arc, ±angle/2 either side of straight
 * ahead. Redraws live as the slider moves.
 */
@Composable
private fun SpeakerStage(
    angleDeg: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val speakerBody = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.62f
        val radius = minOf(size.width * 0.36f, size.height * 0.52f)
        val half = Math.toRadians(angleDeg / 2.0)

        // Guide arc the speakers travel on (front semicircle, dashed).
        drawArc(
            color = outline.copy(alpha = 0.30f),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            ),
        )

        // Speaker azimuths measured from straight ahead (up on the canvas).
        val azimuths = floatArrayOf(-half.toFloat(), half.toFloat())
        val positions = azimuths.map { az ->
            Offset(cx + radius * sin(az), cy - radius * cos(az))
        }

        // Sound paths from each speaker to the sweet spot.
        for (p in positions) {
            drawLine(
                color = accent.copy(alpha = if (enabled) 0.45f else 0.25f),
                start = p,
                end = Offset(cx, cy),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
            )
        }

        // Angle wedge at the head with the current spread.
        val wedgeR = radius * 0.38f
        drawArc(
            color = accent.copy(alpha = 0.9f),
            startAngle = -90f - angleDeg / 2f,
            sweepAngle = angleDeg,
            useCenter = false,
            topLeft = Offset(cx - wedgeR, cy - wedgeR),
            size = Size(wedgeR * 2f, wedgeR * 2f),
            style = Stroke(width = 2.dp.toPx()),
        )

        // Listener head: circle + ears + nose, facing up.
        val headR = radius * 0.16f
        drawCircle(color = surface, radius = headR, center = Offset(cx, cy))
        drawCircle(
            color = outline,
            radius = headR,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        // Ears
        for (side in intArrayOf(-1, 1)) {
            drawCircle(
                color = outline,
                radius = headR * 0.28f,
                center = Offset(cx + side * headR, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        // Nose — small triangle on the front of the head.
        val nose = Path().apply {
            moveTo(cx - headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx + headR * 0.28f, cy - headR * 0.92f)
            lineTo(cx, cy - headR * 1.38f)
            close()
        }
        drawPath(nose, color = outline)

        // Speakers — rounded cabinet + driver, each rotated to face the head.
        positions.forEachIndexed { i, p ->
            drawSpeaker(
                at = p,
                facingDeg = Math.toDegrees(azimuths[i].toDouble()).toFloat(),
                scale = radius,
                body = speakerBody,
                cone = if (enabled) accent else outline,
            )
        }
    }
}

/**
 * One speaker cabinet at [at]. [facingDeg] is the speaker's azimuth from the
 * listener's front axis; the cabinet is drawn pointing down (toward the
 * listener when at 0°) and rotated by the azimuth so it always aims at the
 * sweet spot.
 */
private fun DrawScope.drawSpeaker(
    at: Offset,
    facingDeg: Float,
    scale: Float,
    body: Color,
    cone: Color,
) {
    val w = scale * 0.22f
    val h = scale * 0.30f
    withTransform({
        rotate(degrees = facingDeg, pivot = at)
    }) {
        drawRoundRect(
            color = body,
            topLeft = Offset(at.x - w / 2f, at.y - h),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.2f, w * 0.2f),
        )
        // Driver cone near the front (listener-facing) edge.
        drawCircle(
            color = cone,
            radius = w * 0.26f,
            center = Offset(at.x, at.y - h * 0.30f),
        )
        drawCircle(
            color = body.copy(alpha = 0.35f),
            radius = w * 0.12f,
            center = Offset(at.x, at.y - h * 0.75f),
        )
    }
}
