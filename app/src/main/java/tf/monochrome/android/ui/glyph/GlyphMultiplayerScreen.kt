// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.glyph.multiplayer.RoomPlacing
import tf.monochrome.android.glyph.multiplayer.RoomPlayer
import tf.monochrome.android.glyph.multiplayer.RoomStatus
import tf.monochrome.android.glyph.multiplayer.RoomSummary
import tf.monochrome.android.glyph.multiplayer.RoomView
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Multiplayer: the rooms, and the room. Built as the Packs page is: a top
 * bar, then glass panels down a scrolling column. Before a room: the open
 * rooms, a code to join by, a room to make. In one: its code, the chart
 * the host picked, everyone with whether they have the song and are ready,
 * the host's Start, a line of chat; while a song plays, everyone's score as
 * it stands; after it, the ranking. See docs/ui-invariants.md, "Trypt Mania".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphMultiplayerScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mp = state.multiplayer
    val session = mp.session
    val room = session.room
    BackHandler {
        if (room != null) onEvent(GlyphEvent.LeaveRoom) else onEvent(GlyphEvent.CloseMultiplayer)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tm_multiplayer), modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.CloseMultiplayer) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                session.latencyMs?.let { ms ->
                    Text(
                        stringResource(R.string.tm_mp_latency, ms),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = MonoDimens.spacingMd),
                    )
                }
                if (room == null && session.status == RoomStatus.CONNECTED) {
                    IconButton(onClick = { onEvent(GlyphEvent.RefreshRooms) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.tm_mp_refresh))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MonoDimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            session.error?.let { message ->
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = stringResource(R.string.tm_mp_ok), onClick = { onEvent(GlyphEvent.DismissRoomError) }, height = 40.dp)
                }
            }
            when {
                !mp.signedIn -> GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tm_mp_sign_in), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = stringResource(R.string.tm_mp_sign_in_button), primary = true, onClick = onSignIn)
                }
                !mp.hasServer -> GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tm_mp_no_server), style = MaterialTheme.typography.bodyMedium)
                }
                // OFF only for the moment between opening the page and the socket's first state.
                session.status == RoomStatus.CONNECTING || session.status == RoomStatus.OFF -> GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tm_mp_connecting, session.serverHost.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                session.status == RoomStatus.FAILED -> GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tm_mp_failed, session.error ?: session.serverHost.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = stringResource(R.string.tm_mp_retry), primary = true, onClick = { onEvent(GlyphEvent.RoomReconnect) })
                }
                room == null -> Lobby(mp.playerName, session.rooms, onEvent)
                else -> RoomPanel(state, room, onEvent)
            }
            // Trailing space inside the scroll, as the Packs page: the last
            // panel comes clear of the tab bar while the page runs under it.
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }
}

@Composable
private fun Lobby(playerName: String, rooms: List<RoomSummary>, onEvent: (GlyphEvent) -> Unit) {
    var roomName by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tm_mp_playing_as, playerName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(MonoDimens.spacingSm))
        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it.take(32) },
            label = { Text(stringResource(R.string.tm_mp_room_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MonoDimens.spacingSm))
        GlyphButton(text = stringResource(R.string.tm_mp_create), primary = true, onClick = { onEvent(GlyphEvent.CreateRoom(roomName)) }, modifier = Modifier.fillMaxWidth())
    }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(4) },
                label = { Text(stringResource(R.string.tm_mp_code)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MonoDimens.spacingSm))
            GlyphButton(text = stringResource(R.string.tm_mp_join), enabled = code.length == 4, onClick = { onEvent(GlyphEvent.JoinRoom(code)) })
        }
    }
    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel(stringResource(R.string.tm_mp_rooms))
        if (rooms.isEmpty()) {
            Text(stringResource(R.string.tm_mp_no_rooms), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rooms.forEach { summary ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(GlyphEvent.JoinRoom(summary.code)) }
                    .padding(vertical = MonoDimens.spacingSm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(summary.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(summary.host, summary.chart, stateLabel(summary.state)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.tm_mp_players, summary.players), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(MonoDimens.spacingSm))
                Text(summary.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RoomPanel(state: GlyphUiState, room: RoomView, onEvent: (GlyphEvent) -> Unit) {
    val mp = state.multiplayer
    val session = mp.session
    val me = session.me
    val isHost = session.isHost
    val chart = room.chart

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium)
                Text(stateLabel(room.state).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.tm_mp_code_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(room.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel(stringResource(R.string.tm_mp_chart))
        if (chart == null) {
            Text(
                if (isHost) stringResource(R.string.tm_mp_pick_hint) else stringResource(R.string.tm_mp_no_chart),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(chart.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOf(chart.artist, chart.difficulty, chart.bpm.takeIf { it > 0 }?.let { "${it.toInt()} BPM" }).filter { !it.isNullOrBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (mp.haveSong) {
                null -> Text(stringResource(R.string.tm_mp_checking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                false -> Text(mp.missing ?: stringResource(R.string.tm_mp_no_song), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                true -> Unit
            }
            RoomSongShare(mp = mp, room = room, chart = chart, isHost = isHost, onEvent = onEvent)
        }
        if (isHost && !room.playing) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            GlyphButton(text = stringResource(R.string.tm_mp_pick_chart), onClick = { onEvent(GlyphEvent.OpenSongs(GlyphSongListMode.ROOM)) }, modifier = Modifier.fillMaxWidth())
        }
    }

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel(stringResource(R.string.tm_mp_players_label))
        val shown = if (room.playing) mp.opponents else room.players
        shown.forEach { player ->
            PlayerRow(player = player, room = room, isMe = player.id == session.myId, mayKick = isHost && player.id != session.myId, onEvent = onEvent)
        }
    }

    session.ranking?.let { ranking ->
        GlyphPanel(modifier = Modifier.fillMaxWidth()) {
            GlyphSectionLabel(stringResource(R.string.tm_mp_results))
            ranking.forEach { RankingRow(it, it.id == session.myId) }
        }
    }

    if (!room.playing) {
        Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            val ready = me?.ready == true
            GlyphButton(
                text = if (ready) stringResource(R.string.tm_mp_ready) else stringResource(R.string.tm_mp_not_ready),
                enabled = chart != null && mp.haveSong == true,
                primary = ready,
                onClick = { onEvent(GlyphEvent.SetRoomReady(!ready)) },
                modifier = Modifier.weight(1f),
            )
            if (isHost) {
                val playing = room.players.filter { it.hasSong == true }
                val canStart = chart != null && mp.haveSong == true && playing.isNotEmpty() && playing.all { it.ready }
                GlyphButton(
                    text = stringResource(R.string.tm_mp_start),
                    primary = true,
                    enabled = canStart,
                    onClick = { onEvent(GlyphEvent.StartRoom) },
                    modifier = Modifier.weight(1f),
                )
            }
            GlyphButton(text = stringResource(R.string.tm_mp_leave), onClick = { onEvent(GlyphEvent.LeaveRoom) }, modifier = Modifier.weight(1f))
        }
    }

    GlyphPanel(modifier = Modifier.fillMaxWidth()) {
        GlyphSectionLabel(stringResource(R.string.tm_mp_chat))
        session.chat.takeLast(8).forEach { line ->
            Text(
                "${line.name}: ${line.text}",
                style = MaterialTheme.typography.bodySmall,
                color = if (line.id == session.myId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        var text by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(300) },
                placeholder = { Text(stringResource(R.string.tm_mp_chat_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (text.isNotBlank()) { onEvent(GlyphEvent.RoomChat(text)); text = "" } }) {
                Text(stringResource(R.string.tm_mp_send))
            }
        }
    }
}

/**
 * The room's song passed round it: the host shares it while a player is
 * without it, and a player without it fetches it with one tap, which also
 * readies them. Only on a rooms server that can (it handed this phone a
 * key), and never while a song plays.
 */
@Composable
private fun RoomSongShare(
    mp: GlyphMultiplayerUi,
    room: RoomView,
    chart: tf.monochrome.android.glyph.multiplayer.RoomChart,
    isHost: Boolean,
    onEvent: (GlyphEvent) -> Unit,
) {
    if (!mp.session.canShareSongs || room.playing) return
    val shared = room.sharedSong?.takeIf { it.hash == chart.hash }
    val progress = mp.songTransfer
    val context = androidx.compose.ui.platform.LocalContext.current
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        progress != null -> {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            Text(
                stringResource(
                    when {
                        !isHost -> R.string.tm_mp_getting_song
                        mp.songShrinking -> R.string.tm_mp_shrinking_song
                        else -> R.string.tm_mp_sharing_song
                    },
                    kotlin.math.round(progress * 100f).toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        isHost -> {
            val someoneWithout = room.players.any { it.id != mp.session.myId && it.hasSong == false }
            when {
                shared != null -> Text(stringResource(R.string.tm_mp_song_shared), style = MaterialTheme.typography.bodySmall, color = quiet)
                someoneWithout && mp.haveSong == true -> {
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(
                        text = stringResource(R.string.tm_mp_share_song),
                        icon = Icons.Default.CloudUpload,
                        onClick = { onEvent(GlyphEvent.ShareRoomSong) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        mp.haveSong == false -> if (shared != null) {
            Spacer(Modifier.height(MonoDimens.spacingSm))
            GlyphButton(
                text = stringResource(R.string.tm_mp_get_song, android.text.format.Formatter.formatShortFileSize(context, shared.bytes)),
                icon = Icons.Default.CloudDownload,
                onClick = { onEvent(GlyphEvent.FetchRoomSong) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(stringResource(R.string.tm_mp_ask_host_share), style = MaterialTheme.typography.bodySmall, color = quiet)
        }
    }
    mp.transferError?.let { failed ->
        Text(
            stringResource(if (failed.sharing) R.string.tm_mp_share_failed else R.string.tm_mp_get_failed, failed.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PlayerRow(player: RoomPlayer, room: RoomView, isMe: Boolean, mayKick: Boolean, onEvent: (GlyphEvent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(player.name)
                    if (player.id == room.hostId) append("  ★")
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isMe) FontWeight.Medium else FontWeight.Normal,
                color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val note = when {
                room.playing && player.spectating -> stringResource(R.string.tm_mp_spectating)
                room.playing -> "${player.live.score}  ·  ${player.live.combo}x  ·  ${"%.1f".format(player.live.acc * 100)}%"
                player.finished != null -> "${player.finished.score}  ·  ${player.finished.grade}"
                player.hasSong == false -> stringResource(R.string.tm_mp_no_song)
                player.hasSong == null -> stringResource(R.string.tm_mp_checking)
                player.ready -> stringResource(R.string.tm_mp_ready)
                else -> stringResource(R.string.tm_mp_not_ready)
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mayKick && !room.playing) {
            TextButton(onClick = { onEvent(GlyphEvent.KickPlayer(player.id)) }) { Text(stringResource(R.string.tm_mp_kick)) }
        }
    }
}

@Composable
private fun RankingRow(placing: RoomPlacing, isMe: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${placing.rank}.", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(MonoDimens.spacingSm))
        Column(Modifier.weight(1f)) {
            Text(placing.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isMe) FontWeight.Medium else FontWeight.Normal)
            Text(
                "${placing.result.score}  ·  ${"%.2f".format(placing.result.acc * 100)}%  ·  ${placing.result.maxCombo}x" +
                    (if (placing.result.failed) "  ·  FAILED" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(placing.result.grade, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun stateLabel(state: String): String? = when (state) {
    "lobby" -> stringResource(R.string.tm_mp_state_lobby)
    "playing" -> stringResource(R.string.tm_mp_state_playing)
    "results" -> stringResource(R.string.tm_mp_state_results)
    else -> null
}
