// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.player

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last known playback position, readable outside the player's view model.
 *
 * `PlayerViewModel` owns the MediaController and therefore the authoritative
 * position, but a view model cannot be injected into another view model. The
 * sampler needs the position for one thing only — recording where in a track a
 * capture came from — so rather than plumbing a controller into it, the player
 * publishes here and the sampler reads.
 *
 * Advisory, not authoritative: it is whatever the player last posted, which is
 * a poll behind reality. That is fine for a provenance note in a sample's
 * metadata and would not be fine for anything that had to seek.
 */
@Singleton
class PlaybackPositionSource @Inject constructor() {

    @Volatile
    private var lastKnownMs: Long = 0L

    fun update(positionMs: Long) {
        lastKnownMs = positionMs.coerceAtLeast(0L)
    }

    fun positionMs(): Long = lastKnownMs
}
