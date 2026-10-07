package tf.monochrome.android.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.R
import tf.monochrome.android.audio.usb.BypassVolumeController
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassPanel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The USB DAC's volume while it plays in exclusive mode, as a liquid glass
 * bar: the player's glass progress tube, so it reads as the same material as
 * the seek bar above it, with the level in dB beside it.
 *
 * Even steps in dB from silence at the left end to 0 dB at the right (see
 * BypassVolumeController). The speaker mutes; unmuting goes back to the level
 * the mute came from, which the controller remembers so every control agrees.
 *
 * [onInteracting] is true while a finger is on the tube, so a pop-up holding
 * this bar stays up for as long as someone is dragging it.
 */
@Composable
internal fun DacVolumeBar(
    levelDb: Float,
    onLevelDb: (Float) -> Unit,
    onMute: (Boolean) -> Unit,
    tint: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    /** False on the legacy player, which predates the glass and keeps Material's slider. */
    glassTube: Boolean = true,
    onInteracting: (Boolean) -> Unit = {},
) {
    val glass = LocalPlayerGlass.current
    val muted = levelDb <= BypassVolumeController.MIN_DB
    val position = BypassVolumeController.dbToPosition(levelDb)
    val mutedLabel = stringResource(R.string.volume_muted)
    val describe: (Float) -> String = { db -> if (db <= BypassVolumeController.MIN_DB) mutedLabel else formatDb(db) }

    val onSeek: (Float) -> Unit = {
        onInteracting(true)
        onLevelDb(BypassVolumeController.positionToDb(it))
    }
    val onSeekFinished: (Float) -> Unit = {
        onLevelDb(BypassVolumeController.positionToDb(it))
        onInteracting(false)
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMute(!muted) }) {
            Icon(
                imageVector = when {
                    muted -> Icons.AutoMirrored.Filled.VolumeOff
                    levelDb < -30f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = stringResource(if (muted) R.string.action_unmute else R.string.action_mute),
                tint = contentColor,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (glassTube && glass.enabled && glass.progressGlass) {
                GlassProgressTube(
                    fraction = position,
                    tint = tint,
                    onSeek = onSeek,
                    onSeekFinished = onSeekFinished,
                    semanticsLabel = stringResource(R.string.dac_volume),
                    stateText = { describe(BypassVolumeController.positionToDb(it)) },
                )
            } else {
                PlainSlider(position, tint, onSeek, onSeekFinished)
            }
        }
        Text(
            text = describe(levelDb),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.85f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(60.dp),
        )
    }
}

/**
 * The bar as a glass pop-up at the top of the screen, for the volume keys.
 * With a DAC in exclusive mode the keys move its level rather than Android's,
 * so Android's own volume panel has nothing to show, and this takes its place.
 *
 * Shown by a key press, hidden [HIDE_AFTER_MS] after the last one, and never
 * while a finger is on it. Not while [suppressed] — the full player carries the
 * bar itself — and only for presses made with the app on screen: in the
 * background Android shows its own panel for them (the session reports the DAC
 * as a remote device), and one waiting here on return would be stale.
 *
 * A pane in the app's own window, as a sibling of the haze source: a dialog or
 * popup is a separate window and could not blur what is behind it.
 */
@Composable
internal fun DacVolumePopup(
    exclusive: StateFlow<Boolean>,
    levelDb: StateFlow<Float>,
    keyPresses: Flow<Unit>,
    onLevelDb: (Float) -> Unit,
    onMute: (Boolean) -> Unit,
    hazeState: HazeState?,
    glass: PlayerGlassSettings,
    suppressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val isExclusive by exclusive.collectAsStateWithLifecycle()
    val hidden by rememberUpdatedState(suppressed || !isExclusive)
    var requested by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    // Bumped by every press and every change made on the bar; each one
    // restarts the countdown to hiding.
    var activity by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(keyPresses, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            keyPresses.collect {
                if (!hidden) {
                    requested = true
                    activity++
                }
            }
        }
    }
    LaunchedEffect(activity, touching) {
        if (requested && !touching) {
            delay(HIDE_AFTER_MS)
            requested = false
        }
    }
    LaunchedEffect(hidden) {
        if (hidden) {
            requested = false
            // A drag cut off by the pane going away never reports its end.
            touching = false
        }
    }

    AnimatedVisibility(
        visible = requested && !hidden,
        modifier = modifier,
        enter = fadeIn(tween(150)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { -it / 2 },
    ) {
        val level by levelDb.collectAsStateWithLifecycle()
        GlassPanel(
            hazeState = hazeState,
            glass = glass,
            modifier = Modifier.statusBarsPadding(),
            avoidNavigationBar = false,
        ) {
            // GlassPanel publishes its material to its own slab only; the tube
            // has a shader of its own and reads the same local.
            CompositionLocalProvider(LocalPlayerGlass provides glass) {
                DacVolumeBar(
                    levelDb = level,
                    onLevelDb = {
                        onLevelDb(it)
                        activity++
                    },
                    onMute = {
                        onMute(it)
                        activity++
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    onInteracting = { touching = it },
                )
            }
        }
    }
}

/** How long the pop-up stays after the last key press or change. */
private const val HIDE_AFTER_MS = 2_500L

/** "−24 dB", with a true minus sign, in whole decibels. */
private fun formatDb(db: Float): String {
    val whole = db.roundToInt()
    return if (whole < 0) "−${abs(whole)} dB" else "$whole dB"
}
