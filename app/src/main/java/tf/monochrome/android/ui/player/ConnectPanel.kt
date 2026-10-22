// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.connect.ConnectController
import tf.monochrome.android.connect.ConnectDevice
import tf.monochrome.android.connect.ConnectState
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.MonoDimens
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(private val connect: ConnectController) : ViewModel() {
    val state = connect.state
    val myId: String get() = connect.myId
    fun playOn(id: String) = connect.playOn(id)
    fun remote(action: String) = connect.remote(action)
}

/**
 * Tryptify Connect in the player: what plays on another device, with its
 * controls and Play here, and the listener's devices, each a tap to move the
 * music there. A pane of the player's own glass through its `overlay` slot,
 * like the Speed panel and the audio tools (never a dialog or a
 * ModalBottomSheet: see Glass in docs/ui-invariants.md).
 */
@Composable
internal fun BoxScope.ConnectPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    BackHandler(enabled = visible) { onDismiss() }

    val dragScope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    var panelHeight by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (visible) dragY.snapTo(0f) }
    val dragState = rememberDraggableState { delta ->
        dragScope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (panelHeight > 0f) (1f - dragY.value / panelHeight).coerceIn(0f, 1f) else 1f }
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        // Collected inside the gate: nothing is watched while the panel is shut.
        val state by viewModel.state.collectAsStateWithLifecycle()
        Box(
            modifier = Modifier
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .graphicsLayer { translationY = dragY.value },
        ) {
            GlassPanel(hazeState = LocalPlayerHaze.current, glass = LocalMiniPlayerGlass.current, avoidNavigationBar = false) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                val far = panelHeight > 0f && dragY.value > panelHeight * 0.3f
                                if (far || velocity > 900f) onDismiss() else dragY.animateTo(0f, spring(stiffness = 400f))
                            },
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(width = 34.dp, height = 4.dp),
                            shape = MonoDimens.shapeSm,
                            color = LocalContentColor.current.copy(alpha = 0.4f),
                        ) {}
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.tryptify_connect), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    ConnectContent(state, viewModel.myId, viewModel::playOn, viewModel::remote)
                }
            }
        }
    }
}

@Composable
private fun ConnectContent(state: ConnectState, myId: String, onPlayOn: (String) -> Unit, onRemote: (String) -> Unit) {
    val dim = LocalContentColor.current.copy(alpha = 0.8f)
    when (state.status) {
        ConnectState.Status.SIGNED_OUT -> {
            Text(stringResource(R.string.connect_signed_out), style = MaterialTheme.typography.bodyMedium, color = dim)
            return
        }
        ConnectState.Status.ON -> Unit
        else -> {
            Text(stringResource(R.string.connect_connecting), style = MaterialTheme.typography.bodyMedium, color = dim)
            return
        }
    }
    val now = state.now
    val holder = now?.takeIf { it.playing }?.device
    val elsewhere = now?.takeIf { it.device != myId && it.track != null }
    if (elsewhere != null) {
        val online = state.devices.any { it.id == elsewhere.device }
        val name = state.devices.firstOrNull { it.id == elsewhere.device }?.name ?: elsewhere.name.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(if (online && elsewhere.playing) R.string.connect_playing_on else R.string.connect_last_played_on, name),
                style = MaterialTheme.typography.labelLarge,
                color = dim,
            )
            Text(
                listOfNotNull(elsewhere.track?.title, elsewhere.track?.artist?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (online) {
                IconButton(onClick = { onRemote("prev") }) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.action_previous)) }
                IconButton(onClick = { onRemote(if (elsewhere.playing) "pause" else "play") }) {
                    Icon(
                        if (elsewhere.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (elsewhere.playing) R.string.action_pause else R.string.action_play),
                    )
                }
                IconButton(onClick = { onRemote("next") }) { Icon(Icons.Default.SkipNext, stringResource(R.string.action_next)) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onPlayOn(myId) }) { Text(stringResource(R.string.connect_play_here)) }
        }
    }
    val devices = state.devices.sortedWith(compareBy<ConnectDevice> { it.id != myId }.thenBy { it.name })
    Column {
        for (d in devices) DeviceRow(d, isMe = d.id == myId, active = d.id == holder, canMove = holder != null && d.id != holder, onPlayOn)
    }
    if (devices.size < 2) {
        Text(stringResource(R.string.connect_alone), style = MaterialTheme.typography.bodySmall, color = dim)
    }
}

@Composable
private fun DeviceRow(d: ConnectDevice, isMe: Boolean, active: Boolean, canMove: Boolean, onPlayOn: (String) -> Unit) {
    val dim = LocalContentColor.current.copy(alpha = 0.8f)
    val detail = listOfNotNull(
        stringResource(if (isMe) R.string.connect_this_phone else if (d.kind == "android") R.string.connect_kind_app else R.string.connect_kind_web),
        if (active) stringResource(R.string.connect_playing) else null,
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MonoDimens.listRowHeight)
            .clickable(enabled = canMove) { onPlayOn(d.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (d.kind == "android") Icons.Default.Smartphone else Icons.Default.Computer,
            contentDescription = null,
            // The glass's own ink, as all text on it (Text on glass); "Playing" says which.
            tint = LocalContentColor.current,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1)
        }
        if (canMove) {
            Text(
                stringResource(if (isMe) R.string.connect_play_here else R.string.connect_play_there),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
