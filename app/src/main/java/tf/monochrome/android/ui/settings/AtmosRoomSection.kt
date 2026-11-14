package tf.monochrome.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.audio.atmos.AtmosRoomStore
import tf.monochrome.android.domain.model.AtmosRoom
import tf.monochrome.android.domain.model.CustomRoom
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.RoomKind
import tf.monochrome.android.domain.model.RoomPreset
import java.io.File
import java.util.Locale

/**
 * A list that folds away under its [title]. Closed, which it is to begin
 * with unless [startOpen], the row says what is [chosen]; open, the list is
 * under it. The page it is on is long, and a list of rooms or ear sets grows
 * with every one that is added.
 */
@Composable
internal fun FoldingList(
    title: String,
    chosen: String?,
    startOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = !open }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (open) "" else chosen.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) content()
}

/**
 * Which room the headphone render plays in, under the Room amount: the
 * render's own, one of the measured rooms that have been added, or the room
 * the listener describes, with its size to set. The rooms fold away, and so
 * do My room's size and shape.
 */
@Composable
internal fun AtmosRoomSection(
    profile: RendererProfile,
    room: AtmosRoom?,
    measured: List<File>,
    onProfile: (RendererProfile) -> Unit,
    onDelete: (File) -> Unit,
    onBrowse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val chosen = when (profile.roomKind) {
            RoomKind.BUILT_IN -> stringResource(R.string.atmos_room_builtin)
            RoomKind.MEASURED ->
                measured.firstOrNull { it.absolutePath == profile.roomFile }?.let(AtmosRoomStore::displayName)
            RoomKind.CUSTOM -> stringResource(R.string.atmos_room_custom)
        }
        FoldingList(title = stringResource(R.string.atmos_rooms), chosen = chosen) {
            RoomChoice(
                selected = profile.roomKind == RoomKind.BUILT_IN,
                label = stringResource(R.string.atmos_room_builtin),
                onSelect = { onProfile(profile.copy(roomKind = RoomKind.BUILT_IN)) },
            )
            measured.forEach { file ->
                RoomChoice(
                    selected = profile.roomKind == RoomKind.MEASURED && profile.roomFile == file.absolutePath,
                    label = AtmosRoomStore.displayName(file),
                    onSelect = { onProfile(profile.copy(roomKind = RoomKind.MEASURED, roomFile = file.absolutePath)) },
                    onDelete = { onDelete(file) },
                )
            }
            RoomChoice(
                selected = profile.roomKind == RoomKind.CUSTOM,
                label = stringResource(R.string.atmos_room_custom),
                onSelect = { onProfile(profile.copy(roomKind = RoomKind.CUSTOM)) },
            )
        }
        if (profile.roomKind == RoomKind.CUSTOM) FoldingList(
            title = stringResource(R.string.atmos_room_shape),
            chosen = null,
            // Open: choosing My room is asking to set it.
            startOpen = true,
        ) {
            val custom = profile.customRoom.clamped()
            fun set(next: CustomRoom) = onProfile(profile.copy(customRoom = next))
            // Rooms to start from. Each fills the values below, which stay the listener's to change.
            Text(
                stringResource(R.string.atmos_room_presets),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoomPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = custom == preset.room.clamped(),
                        onClick = { set(preset.room) },
                        label = { Text(stringResource(presetName(preset))) },
                    )
                }
            }
            LabeledSlider(
                title = stringResource(R.string.atmos_room_width),
                valueText = metres(custom.width),
                value = custom.width,
                range = CustomRoom.MIN_SIDE..CustomRoom.MAX_SIDE,
                onValueChange = { set(custom.copy(width = it)) },
            )
            LabeledSlider(
                title = stringResource(R.string.atmos_room_depth),
                valueText = metres(custom.depth),
                value = custom.depth,
                range = CustomRoom.MIN_SIDE..CustomRoom.MAX_SIDE,
                onValueChange = { set(custom.copy(depth = it)) },
            )
            LabeledSlider(
                title = stringResource(R.string.atmos_room_height),
                valueText = metres(custom.height),
                value = custom.height,
                range = CustomRoom.MIN_HEIGHT..CustomRoom.MAX_HEIGHT,
                onValueChange = { set(custom.copy(height = it)) },
            )
            LabeledSlider(
                title = stringResource(R.string.atmos_room_damping),
                valueText = "${(custom.damping * 100).toInt()}%",
                value = custom.damping,
                range = CustomRoom.MIN_DAMPING..CustomRoom.MAX_DAMPING,
                onValueChange = { set(custom.copy(damping = it)) },
            )
            // Its shape: rounded corners, and walls that lean in to a smaller ceiling.
            LabeledSlider(
                title = stringResource(R.string.atmos_room_roundness),
                valueText = "${(custom.roundness * 100).toInt()}%",
                value = custom.roundness,
                range = 0f..1f,
                onValueChange = { set(custom.copy(roundness = it)) },
            )
            LabeledSlider(
                title = stringResource(R.string.atmos_envelope_width),
                valueText = "${(custom.ceilingWidth * 100).toInt()}%",
                value = custom.ceilingWidth,
                range = CustomRoom.MIN_CEILING..1f,
                onValueChange = { set(custom.copy(ceilingWidth = it)) },
            )
            LabeledSlider(
                title = stringResource(R.string.atmos_envelope_depth),
                valueText = "${(custom.ceilingDepth * 100).toInt()}%",
                value = custom.ceilingDepth,
                range = CustomRoom.MIN_CEILING..1f,
                onValueChange = { set(custom.copy(ceilingDepth = it)) },
            )
            val modes = custom.modesHz()
            RoomNote(stringResource(R.string.atmos_room_modes, hz(modes[0]), hz(modes[1]), hz(modes[2])))
        }
        // What is known of the room in use, once it is ready.
        when {
            profile.roomKind == RoomKind.BUILT_IN -> Unit
            room == null -> RoomNote(stringResource(R.string.atmos_room_working))
            // The listener's room is the size they gave it; its sound is a box's of the same volume.
            profile.roomKind == RoomKind.CUSTOM -> profile.customRoom.clamped().let { c ->
                RoomNote(
                    stringResource(
                        R.string.atmos_room_size,
                        number(c.width), number(c.depth), number(c.height), seconds(room.decaySeconds),
                    )
                )
            }
            !room.sized -> RoomNote(stringResource(R.string.atmos_room_unsized, seconds(room.decaySeconds)))
            room.height <= 0f -> RoomNote(
                stringResource(R.string.atmos_room_plan, number(room.width), number(room.depth), seconds(room.decaySeconds))
            )
            else -> RoomNote(
                stringResource(
                    R.string.atmos_room_size,
                    number(room.width), number(room.depth), number(room.height), seconds(room.decaySeconds),
                )
            )
        }
        TextButton(onClick = onBrowse) { Text(stringResource(R.string.atmos_room_browse)) }
    }
}

@Composable
private fun RoomChoice(selected: Boolean, label: String, onSelect: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.atmos_room_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoomNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun metres(value: Float) = stringResource(R.string.atmos_room_metres, number(value))

private fun presetName(preset: RoomPreset): Int = when (preset) {
    RoomPreset.BOOTH -> R.string.atmos_room_preset_booth
    RoomPreset.HOME -> R.string.atmos_room_preset_home
    RoomPreset.CONTROL -> R.string.atmos_room_preset_control
    RoomPreset.LIVE -> R.string.atmos_room_preset_live
    RoomPreset.SMALL_CLUB -> R.string.atmos_room_preset_small_club
    RoomPreset.LARGE_CLUB -> R.string.atmos_room_preset_large_club
    RoomPreset.HALL -> R.string.atmos_room_preset_hall
    RoomPreset.CHAPEL -> R.string.atmos_room_preset_chapel
    RoomPreset.ROUND -> R.string.atmos_room_preset_round
    RoomPreset.STEREO -> R.string.atmos_room_preset_stereo
}

private fun number(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun seconds(value: Float) = String.format(Locale.getDefault(), "%.2f", value)
private fun hz(value: Float) = String.format(Locale.getDefault(), "%.0f", value)
