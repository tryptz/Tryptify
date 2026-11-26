package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import tf.monochrome.android.connect.ConnectNow
import tf.monochrome.android.connect.ConnectProtocol
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * Where another device's song has got to, in seconds, kept in step four times
 * a second while it plays, as Spotify's bar follows a song on another device.
 * Worked out from what that device last said (its second at a server time) and
 * this phone's reckoning of the server's clock, so nothing is sent to keep it
 * moving. Still while that device is paused.
 *
 * It follows only while the screen is started. The frame clock stops with the
 * screen, but a plain delay loop does not, and this one woke the phone four
 * times a second with nothing drawing it. Coming back starts the loop again,
 * and its first pass works the position out at once.
 */
@Composable
fun rememberRemotePosition(now: ConnectNow?, serverNowMs: () -> Long): State<Double> {
    val position = remember { mutableDoubleStateOf(0.0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(now, lifecycle) {
        if (now == null) return@LaunchedEffect
        val end = now.track?.duration?.takeIf { it > 0 }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val p = ConnectProtocol.positionAt(now, serverNowMs())
                position.doubleValue = p
                // Paused, or at the end of a song nobody has moved on from: nothing more to follow.
                if (!now.playing || (end != null && p >= end)) break
                delay(250)
            }
        }
    }
    return position
}

/** m:ss, or h:mm:ss for a long one. */
fun clockOf(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A song followed on another device, as the mini player names it: the same
 * song, with [where] it plays after the artist ("Swans · Playing on Pixel
 * 8"), or alone when the song names none. The bar has one line for both.
 */
fun Track.shownPlayingOn(where: String): Track = copy(
    artist = Artist(id = artist?.id ?: 0, name = listOf(displayArtist, where).filter { it.isNotBlank() }.joinToString(" · ")),
)
