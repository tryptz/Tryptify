// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.preferences

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.audio.replaygain.ReplayGain

/** The target loudness an update reads for each listener: nobody's music gets louder by itself. */
class ReplayGainTargetTest {

    private val mode = stringPreferencesKey("replaygain_mode")
    private val preamp = floatPreferencesKey("replaygain_preamp_db")
    private val target = floatPreferencesKey("replaygain_target_lufs")

    private fun read(vararg pairs: androidx.datastore.preferences.core.Preferences.Pair<*>) =
        PreferencesManager.replayGainTargetOf(mutablePreferencesOf(*pairs))

    @Test
    fun `a target chosen is the target`() {
        assertEquals(-16f, read(target to -16f, preamp to 6f, mode to "SMART"), 0f)
    }

    @Test
    fun `a pre-amp chosen before the target becomes the nearest target`() {
        assertEquals(-18f, read(preamp to 0f, mode to "TRACK"), 0f)
        assertEquals(-14f, read(preamp to 4f, mode to "TRACK"), 0f)
    }

    @Test
    fun `on in an earlier build with the pre-amp never moved plays where it did`() {
        // The pre-amp read 0 dB without ever being saved: -18 LUFS, as an
        // explicit 0 dB reads, not 4 dB louder at the new default.
        for (was in listOf("TRACK", "ALBUM", "SMART")) {
            assertEquals(was, -18f, read(mode to was), 0f)
        }
    }

    @Test
    fun `off, or never set, gets the new default`() {
        assertEquals(ReplayGain.DEFAULT_TARGET_LUFS, read(), 0f)
        assertEquals(ReplayGain.DEFAULT_TARGET_LUFS, read(mode to "OFF"), 0f)
    }

    @Test
    fun `a target outside the steps is not taken`() {
        assertEquals(-18f, read(target to -11f, mode to "SMART"), 0f)
    }
}
