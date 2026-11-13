package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the system-wide AutoEQ Quick Settings tile shows. */
class SystemEqQuickTileTest {

    private val bands = """[{"type":"PEAKING","freq":100.0,"gain":-3.0,"q":1.0}]"""

    @Test
    fun `on only while the equalizer and system-wide are both on`() {
        assertEquals(SystemEqTileLook(false, SystemEqTileLook.Detail.OFF, null), SystemEqTileLook.of(false, false, bands, "Focal Bathys"))
        // System-wide means nothing with the equalizer off.
        assertEquals(SystemEqTileLook(false, SystemEqTileLook.Detail.OFF, null), SystemEqTileLook.of(false, true, bands, "Focal Bathys"))
        assertEquals(SystemEqTileLook(false, SystemEqTileLook.Detail.OFF, null), SystemEqTileLook.of(true, false, bands, "Focal Bathys"))
        assertEquals(SystemEqTileLook(true, SystemEqTileLook.Detail.ON, "Focal Bathys"), SystemEqTileLook.of(true, true, bands, "Focal Bathys"))
    }

    @Test
    fun `on with no headphone named is still on`() {
        assertEquals(SystemEqTileLook(true, SystemEqTileLook.Detail.ON, null), SystemEqTileLook.of(true, true, bands, null))
        assertEquals(SystemEqTileLook(true, SystemEqTileLook.Detail.ON, null), SystemEqTileLook.of(true, true, bands, "  "))
    }

    @Test
    fun `on with no correction saved says so`() {
        for (none in listOf(null, "", "  ", "[]", "[ ]")) {
            assertEquals(none.toString(), SystemEqTileLook(true, SystemEqTileLook.Detail.NO_CORRECTION, null), SystemEqTileLook.of(true, true, none, "Focal Bathys"))
        }
        assertTrue(SystemEqTileLook.hasCorrection(bands))
        assertFalse(SystemEqTileLook.hasCorrection("[]"))
    }
}
