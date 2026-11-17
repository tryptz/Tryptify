package tf.monochrome.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.domain.model.RoomPreset
import tf.monochrome.android.domain.model.SavedRoom
import tf.monochrome.android.domain.model.SavedRooms
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
            SavedRoomPresets(profile = profile, custom = custom, onProfile = onProfile)
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
            // Its shape: rounded corners. Leaning walls are Trapezoid panning's, further down the page.
            LabeledSlider(
                title = stringResource(R.string.atmos_room_roundness),
                valueText = "${(custom.roundness * 100).toInt()}%",
                value = custom.roundness,
                range = 0f..1f,
                onValueChange = { set(custom.copy(roundness = it)) },
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

/**
 * The listener's own presets for My room: the rooms saved under a name, each
 * a chip that fills the values (its cross deletes it), and under them Save
 * preset, Import and Export. A room is exported and imported as a small JSON
 * file ([SavedRooms]); an imported one is saved and chosen at once.
 */
@Composable
private fun SavedRoomPresets(profile: RendererProfile, custom: CustomRoom, onProfile: (RendererProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by rememberUpdatedState(profile)
    val saved = profile.savedRooms
    // The saved room the values are now, if any: its name is the file's and the dialog's.
    val current = saved.firstOrNull { it.room == custom }
    val defaultName = current?.name ?: stringResource(R.string.atmos_room_custom)
    var naming by rememberSaveable { mutableStateOf(false) }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val room = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // One byte over the most a room file is: a bigger file is not one, and is not read through.
                        val bytes = ByteArray(SavedRooms.MAX_FILE_BYTES + 1)
                        var read = 0
                        while (read < bytes.size) {
                            val n = input.read(bytes, read, bytes.size - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read > SavedRooms.MAX_FILE_BYTES) null else SavedRooms.decode(String(bytes, 0, read, Charsets.UTF_8))
                    }
                }.getOrNull()
            }
            if (room == null) {
                toast(context.getString(R.string.crt_preset_import_failed, context.getString(R.string.atmos_room_not_a_preset)))
            } else {
                onProfile(live.copy(customRoom = room.room, savedRooms = SavedRooms.add(live.savedRooms, room.name, room.room)))
                toast(context.getString(R.string.fx_saved_named, room.name))
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val room = SavedRoom(defaultName, live.customRoom)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(SavedRooms.encode(room).toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            toast(
                if (written) context.getString(R.string.crt_preset_exported, room.name)
                else context.getString(R.string.crt_preset_export_failed),
            )
        }
    }

    if (saved.isNotEmpty()) {
        Text(
            stringResource(R.string.atmos_room_saved),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            saved.forEach { room ->
                FilterChip(
                    selected = room.room == custom,
                    onClick = { onProfile(profile.copy(customRoom = room.room)) },
                    label = { Text(room.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.crt_preset_delete, room.name),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(role = Role.Button) {
                                    onProfile(profile.copy(savedRooms = SavedRooms.remove(profile.savedRooms, room.name)))
                                },
                        )
                    },
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.fx_save_preset)) }
        // Any type: a file manager may call a .json text, octet-stream or nothing at all.
        TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.crt_preset_import)) }
        TextButton(onClick = { exporter.launch(SavedRooms.fileName(defaultName)) }) { Text(stringResource(R.string.crt_preset_export)) }
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(defaultName) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.fx_save_preset)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(SavedRooms.MAX_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.fx_preset_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = SavedRooms.name(name).isNotEmpty(),
                    onClick = {
                        val kept = SavedRooms.name(name)
                        onProfile(profile.copy(savedRooms = SavedRooms.add(profile.savedRooms, kept, custom)))
                        toast(context.getString(R.string.fx_saved_named, kept))
                        naming = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

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
