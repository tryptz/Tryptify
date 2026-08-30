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
}
