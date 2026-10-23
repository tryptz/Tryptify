package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import tf.monochrome.android.connect.ConnectNow
import tf.monochrome.android.connect.ConnectProtocol
import tf.monochrome.android.connect.ConnectTrack
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * Where another device's song has got to, in seconds, kept in step four times
 * a second while it plays, as Spotify's bar follows a song on another device.
 * Worked out from what that device last said (its second at a server time) and
 * this phone's reckoning of the server's clock, so nothing is sent to keep it
 * moving. Still while that device is paused.
 */
@Composable
fun rememberRemotePosition(now: ConnectNow?, serverNowMs: () -> Long): State<Double> {
    val position = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(now) {
        if (now == null) return@LaunchedEffect
        while (true) {
            position.doubleValue = ConnectProtocol.positionAt(now, serverNowMs())
            if (!now.playing) break
            delay(250)
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
 * Another device's song as the mini player's [Track]: title, artist, album and
 * cover, enough to show it, with [where] it plays after the artist. Its id
 * is a hash of where it comes from and which song it is, so the bar's cover
 * moves on when that device changes song.
 */
fun ConnectTrack.asShownTrackOn(where: String): Track = Track(
    id = "connect:$src:$id".hashCode().toLong(),
    title = title.ifBlank { "—" },
    duration = duration?.toInt() ?: 0,
    // The artist line says where it plays: "Artist · Playing on Pixel 8".
    artist = Artist(id = 0, name = listOf(artist, where).filter { it.isNotBlank() }.joinToString(" · ")),
    album = Album(id = 0, title = album, cover = cover),
)
