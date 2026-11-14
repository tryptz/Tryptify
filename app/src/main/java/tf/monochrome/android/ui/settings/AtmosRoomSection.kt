package tf.monochrome.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.R
import tf.monochrome.android.audio.atmos.AtmosRoomStore
import tf.monochrome.android.domain.model.AtmosRoom
import tf.monochrome.android.domain.model.CustomRoom
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.RoomKind
import java.io.File
import java.util.Locale

/**
 * Which room the headphone render plays in, under the Room amount: the
 * render's own, one of the measured rooms that have been added, or the room
 * the listener describes, with its size to set.
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
        if (profile.roomKind == RoomKind.CUSTOM) {
            val custom = profile.customRoom.clamped()
            fun set(next: CustomRoom) = onProfile(profile.copy(customRoom = next))
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
            val modes = custom.modesHz()
            RoomNote(stringResource(R.string.atmos_room_modes, hz(modes[0]), hz(modes[1]), hz(modes[2])))
        }
        // What is known of the room in use, once it is ready.
        when {
            profile.roomKind == RoomKind.BUILT_IN -> Unit
            room == null -> RoomNote(stringResource(R.string.atmos_room_working))
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

private fun number(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun seconds(value: Float) = String.format(Locale.getDefault(), "%.2f", value)
private fun hz(value: Float) = String.format(Locale.getDefault(), "%.0f", value)
