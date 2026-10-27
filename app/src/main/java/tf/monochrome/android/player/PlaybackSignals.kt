// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the player is doing, for listeners outside it that must not hold a
 * MediaController just to watch (one would keep [PlaybackService] alive with
 * nothing playing). Tryptify Connect reads it to tell the listener's other
 * devices what plays here.
 *
 * Posted only on a real change (play, pause, a seek, a new song, the speed),
 * with the position at that moment: never polled.
 */
@Singleton
class PlaybackSignals @Inject constructor() {

    /** One change: whether it plays, where the play head was, at what speed, and when (the phone's clock). */
    data class Change(val playing: Boolean, val positionMs: Long, val speed: Float, val atMs: Long, val serial: Long)

    private val _change = MutableStateFlow(Change(playing = false, positionMs = 0, speed = 1f, atMs = 0, serial = 0))
    val change: StateFlow<Change> = _change.asStateFlow()

    private fun post(player: Player) {
        _change.value = Change(
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            speed = player.playbackParameters.speed,
            atMs = System.currentTimeMillis(),
            serial = _change.value.serial + 1,
        )
    }

    /** Called by [PlaybackService] once its player exists. */
    fun attach(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = post(player)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = post(player)
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = post(player)
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) post(player)
            }
        })
    }

    /** Called by [PlaybackService] as it goes. */
    fun detach() {
        // Already not playing: nothing changed, and a post would only make Connect say the same thing again.
        val last = _change.value
        if (!last.playing) return
        _change.value = last.copy(playing = false, atMs = System.currentTimeMillis(), serial = last.serial + 1)
    }
}
