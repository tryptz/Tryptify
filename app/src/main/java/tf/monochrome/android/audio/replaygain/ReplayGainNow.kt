// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.replaygain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What ReplayGain is doing to the song playing, for the line under its setting
 * ("This song: -4.2 dB"), so a listener can tell it is working, and when a
 * song has nothing for it to work from. Written by the player each time it
 * sets the stage's gain.
 */
@Singleton
class ReplayGainNow @Inject constructor() {

    /** [tagged]: the song carries gain tags. [gainDb]: what the stage applies to it. */
    data class Reading(val tagged: Boolean, val gainDb: Float)

    private val _reading = MutableStateFlow<Reading?>(null)

    /** Null while ReplayGain is off or nothing is playing. */
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    fun set(reading: Reading?) {
        _reading.value = reading
    }
}
