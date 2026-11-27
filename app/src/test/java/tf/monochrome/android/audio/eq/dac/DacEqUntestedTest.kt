package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DeviceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own list of DACs driven with a proven sibling's commands. */
class DacEqUntestedTest {

    private val walkPlay = 0x3302

    private val tp35Pro = 0x43E6

    @Test
    fun `the TP35 Pro is found by its ids and its name, however its maker spells it`() {
        for (name in listOf("TP35 Pro", "EPZ TP35 Pro", "EPZ-TP35 PRO", "tp35pro")) {
            assertSame(name, DacEqUntested.EPZ_TP35_PRO, DacEqUntested.find(walkPlay, tp35Pro, name))
            assertSame(name, DeviceTarget.SPACE_2, DacEqUntested.target(walkPlay, tp35Pro, name))
        }
    }

    @Test
    fun `a name alone is not enough, and neither are the ids`() {
        // The plain TP35 is another product, with no EQ of its own to write.
        assertNull(DacEqUntested.find(walkPlay, tp35Pro, "TP35"))
        assertNull(DacEqUntested.find(walkPlay, 0x4321, "TP35"))
        // The name on another product id, or another maker's vendor id.
        assertNull(DacEqUntested.find(walkPlay, 0x4321, "TP35 Pro"))
        assertNull(DacEqUntested.find(0x2972, tp35Pro, "TP35 Pro"))
        // The ids with no product name, or another's: nothing to go by.
        assertNull(DacEqUntested.find(walkPlay, tp35Pro, null))
        assertNull(DacEqUntested.find(walkPlay, tp35Pro, ""))
        assertNull(DacEqUntested.target(walkPlay, tp35Pro, "Some DAC"))
    }

    @Test
    fun `a DAC Contour knows is Contour's, never an untested one`() {
        assertSame(DeviceTarget.EPZ_TP65, DacEqUntested.target(walkPlay, 0x4325, "TP65"))
        assertNull(DacEqUntested.find(walkPlay, 0x4325, "TP65"))
        // Found by its ids alone in Contour, whatever it is called.
        assertNull(DacEqUntested.find(walkPlay, 0x43CC, "TP35 Pro"))
        assertSame(DeviceTarget.MAX, DacEqUntested.target(walkPlay, 0x43CC, "TP35 Pro"))
    }

    @Test
    fun `an entry with no product ids yet goes by its name within its vendor`() {
        val entry = DacEqUntested.Entry("X", walkPlay, emptySet(), "TP35", DeviceTarget.EPZ_TP65)
        assertTrue(entry.matches(walkPlay, 0x1111, "TP35 Pro"))
        assertFalse(entry.matches(0x2972, 0x1111, "TP35 Pro"))
        assertFalse(entry.matches(walkPlay, 0x1111, null))
    }

    @Test
    fun `the TP35 Pro's limits are the Space II's, inside its own gain range and under its own name`() {
        val theirs = DeviceTarget.SPACE_2.caps
        val ours = DacEqUntested.EPZ_TP35_PRO.caps()
        assertEquals("EPZ TP35 Pro", ours.name)
        assertEquals(12.0, theirs.gainMaxDb, 0.0)
        assertEquals(-10.0, ours.gainMinDb, 0.0)
        assertEquals(10.0, ours.gainMaxDb, 0.0)
        assertEquals(theirs.copy(name = ours.name, gainMinDb = -10.0, gainMaxDb = 10.0), ours)
    }

    /**
     * An untested DAC is given the narrowest proven set of commands: its
     * sibling must be one whose guard refuses whatever was not seen to work,
     * and that Contour itself would write to.
     */
    @Test
    fun `every entry is driven as a DAC Contour writes to`() {
        for (entry in DacEqUntested.all) {
            assertFalse(entry.name, entry.driveAs.readOnly)
            assertTrue(entry.name, entry.driveAs in DacEqCatalog.all)
            assertTrue(entry.name, DacEqCatalog.all.none { it.caps.name.equals(entry.name, ignoreCase = true) })
        }
        assertEquals(listOf("EPZ TP35 Pro"), DacEqUntested.all.map { it.name })
    }
}
