package tf.monochrome.android.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one control that replaced two switches.
 *
 * The mapping matters more than it looks: everyone already has the old pair
 * stored, and getting this wrong does not fail loudly, it silently rearranges
 * how their visualizer behaves the next time they open it.
 */
class PresetRotationModeTest {

    @Test
    fun `the pair everybody had maps to the timer`() {
        // Both switches defaulted on, so this is the common install.
        assertEquals(
            PresetRotationMode.Timer,
            PresetRotationMode.migratedFrom(timedRotation = true, changeEachTrack = true),
        )
    }

    @Test
    fun `only per-track becomes the per-track mode`() {
        assertEquals(
            PresetRotationMode.Track,
            PresetRotationMode.migratedFrom(timedRotation = false, changeEachTrack = true),
        )
    }

    @Test
    fun `only the timer stays the timer`() {
        assertEquals(
            PresetRotationMode.Timer,
            PresetRotationMode.migratedFrom(timedRotation = true, changeEachTrack = false),
        )
    }

    @Test
    fun `somebody who turned both off stays off`() {
        // The case the merge exists for. Landing this anywhere but Off would
        // start changing presets for the one person who had asked it not to.
        assertEquals(
            PresetRotationMode.Off,
            PresetRotationMode.migratedFrom(timedRotation = false, changeEachTrack = false),
        )
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
