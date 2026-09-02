package tf.monochrome.android.visualizer

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.preferences.PreferencesManager

/**
 * The one control that replaced two switches.
 *
 * The migration matters more than it looks: everyone already has the old switch
 * stored, and getting it wrong does not fail loudly -- it silently rearranges
 * how their visualizer behaves the next time they open it.
 *
 * These drive [PreferencesManager.rotationModeFrom] with a real [Preferences]
 * built from the *literal* key strings the old build wrote, rather than calling
 * the enum's mapping directly. That distinction is the whole point of the file.
 * The previous version tested the pure function with two booleans and passed
 * for months while the production read consulted `visualizer_preset_rotation`,
 * a key nothing has ever written -- so the mapping was perfect and the answer
 * was still Timer for every listener alive. A pure function over booleans
 * cannot see which key fed it; this can.
 */
class PresetRotationModeTest {

    /** Exactly what the old build wrote, spelled out rather than referenced. */
    private val autoShuffleKey = booleanPreferencesKey("visualizer_auto_shuffle")
    private val modeKey = stringPreferencesKey("visualizer_preset_rotation_mode")

    @Test
    fun `the default install had auto-shuffle on, and lands on per-track`() {
        // Nothing stored at all: a listener who never opened the setting. The
        // old key defaulted to true, so they had the per-track roll.
        assertEquals(
            PresetRotationMode.Track,
            PreferencesManager.rotationModeFrom(preferencesOf()),
        )
    }

    @Test
    fun `auto-shuffle on becomes the per-track mode`() {
        assertEquals(
            PresetRotationMode.Track,
            PreferencesManager.rotationModeFrom(preferencesOf(autoShuffleKey to true)),
        )
    }

    @Test
    fun `auto-shuffle off is taken at its word and becomes Off`() {
        // The case the migration exists for, and the one the phantom key broke:
        // this returned Timer, so the one person who had asked for less got
        // more. Landing on Off does cost them the timer they were never able to
        // decline -- that is the deliberate reading of their only switch.
        assertEquals(
            PresetRotationMode.Off,
            PreferencesManager.rotationModeFrom(preferencesOf(autoShuffleKey to false)),
        )
    }

    @Test
    fun `a stored mode wins over the migration in every case`() {
        for (mode in PresetRotationMode.entries) {
            for (legacy in listOf(true, false)) {
                assertEquals(
                    "mode=$mode legacy=$legacy",
                    mode,
                    PreferencesManager.rotationModeFrom(
                        preferencesOf(modeKey to mode.key, autoShuffleKey to legacy),
                    ),
                )
            }
        }
    }

    @Test
    fun `migration never invents the timer`() {
        // Timer is reachable only by choosing it in Settings. If this starts
        // failing, the read has drifted back onto a key nobody writes.
        for (legacy in listOf(true, false)) {
            assertTrue(
                PreferencesManager.rotationModeFrom(preferencesOf(autoShuffleKey to legacy)) !=
                    PresetRotationMode.Timer,
            )
        }
    }

    @Test
    fun `every mode survives a round trip through its stored key`() {
        for (mode in PresetRotationMode.entries) {
            assertEquals(mode, PresetRotationMode.fromKey(mode.key))
        }
    }

    @Test
    fun `an unknown or missing key falls back rather than throwing`() {
        // Read from DataStore, so it can be anything a future or older build
        // wrote there.
        assertEquals(PresetRotationMode.Default, PresetRotationMode.fromKey(null))
        assertEquals(PresetRotationMode.Default, PresetRotationMode.fromKey("sideways"))
    }

    @Test
    fun `only Off means nothing is rotating`() {
        assertFalse(PresetRotationMode.Off.isRotating)
        assertTrue(PresetRotationMode.Timer.isRotating)
        assertTrue(PresetRotationMode.Track.isRotating)
    }

    @Test
    fun `the chip restores the mode that was remembered`() {
        assertEquals(
            PresetRotationMode.Track,
            PresetRotationMode.toggled(enabled = true, remembered = PresetRotationMode.Track),
        )
        assertEquals(
            PresetRotationMode.Timer,
            PresetRotationMode.toggled(enabled = true, remembered = PresetRotationMode.Timer),
        )
    }

    @Test
    fun `turning the chip off is Off whatever was remembered`() {
        PresetRotationMode.entries.forEach { remembered ->
            assertEquals(
                "remembered=$remembered",
                PresetRotationMode.Off,
                PresetRotationMode.toggled(enabled = false, remembered = remembered),
            )
        }
    }

    @Test
    fun `turning the chip on always starts something rotating`() {
        // Off cannot be restored by a control whose whole purpose is to start
        // rotation -- that would be a switch that visibly does nothing.
        assertEquals(
            PresetRotationMode.Default,
            PresetRotationMode.toggled(enabled = true, remembered = PresetRotationMode.Off),
        )
    }

    @Test
    fun `a remembered choice survives being switched off and on`() {
        // The bug: the remembered mode lived only in memory, and the chip
        // writing Off meant nothing rotating ever reached it again. On the next
        // launch it was back to its default, so "Each track" came back as "On a
        // timer" -- the setting appearing to reset itself.
        var stored = PresetRotationMode.Track
        var remembered = PresetRotationMode.Track

        stored = PresetRotationMode.toggled(enabled = false, remembered = remembered)
        assertEquals(PresetRotationMode.Off, stored)

        // A relaunch: everything not written down is gone. `remembered` is read
        // back from storage rather than reconstructed from `stored`, which is
        // Off and says nothing about what came before it.
        assertEquals(
            PresetRotationMode.Track,
            PresetRotationMode.toggled(enabled = true, remembered = remembered),
        )
    }
}
