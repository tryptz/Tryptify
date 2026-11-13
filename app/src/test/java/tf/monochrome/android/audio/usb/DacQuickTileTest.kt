package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the exclusive USB DAC's Quick Settings tile shows (issue #157). */
class DacQuickTileTest {

    @Test
    fun `off is released, whatever the driver still holds`() {
        assertEquals(TileLook(false, TileLook.Detail.RELEASED, null), TileLook.of(enabled = false, held = false, dacName = null))
        // The moment after the tap: the setting is off, the DAC not closed yet.
        assertEquals(TileLook(false, TileLook.Detail.RELEASED, null), TileLook.of(enabled = false, held = true, dacName = "Focal Bathys"))
    }

    @Test
    fun `on with no DAC says so`() {
        assertEquals(TileLook(true, TileLook.Detail.NO_DAC, null), TileLook.of(enabled = true, held = false, dacName = null))
        // A name left over from a DAC that has gone is not shown.
        assertEquals(TileLook(true, TileLook.Detail.NO_DAC, null), TileLook.of(enabled = true, held = false, dacName = "Focal Bathys"))
    }

    @Test
    fun `on and holding a DAC names it`() {
        assertEquals(TileLook(true, TileLook.Detail.HELD, "Focal Bathys"), TileLook.of(enabled = true, held = true, dacName = "Focal Bathys"))
    }

    @Test
    fun `a DAC without a name is still held`() {
        assertEquals(TileLook(true, TileLook.Detail.HELD, null), TileLook.of(enabled = true, held = true, dacName = null))
        assertEquals(TileLook(true, TileLook.Detail.HELD, null), TileLook.of(enabled = true, held = true, dacName = "  "))
    }
}
