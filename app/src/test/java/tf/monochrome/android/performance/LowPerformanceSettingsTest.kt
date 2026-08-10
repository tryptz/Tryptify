package tf.monochrome.android.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps the "Low performance mode" master switch honest.
 *
 * The master owns no state of its own: it writes the three switches below it,
 * and each of those re-derives it. If the two directions ever disagreed the
 * settings screen would show a master that is off above three rows that are all
 * on — so the derivation is pinned here rather than left inline in a DataStore
 * transaction where nothing can reach it.
 */
class LowPerformanceSettingsTest {

    @Test
    fun `the master is on only when all three are on`() {
        assertTrue(
            LowPerformanceSettings(
                disableAnimations = true,
                legacyPlayer = true,
                disableLiquidGlass = true,
            ).allEnabled
        )
    }

    @Test
    fun `turning any one of the three off clears the master`() {
        val all = LowPerformanceSettings(true, true, true)
        assertFalse("animations back on", all.copy(disableAnimations = false).allEnabled)
        assertFalse("current player back", all.copy(legacyPlayer = false).allEnabled)
        assertFalse("glass back on", all.copy(disableLiquidGlass = false).allEnabled)
    }

    @Test
    fun `everything is off by default`() {
        val defaults = LowPerformanceSettings()
        assertFalse(defaults.disableAnimations)
        assertFalse(defaults.legacyPlayer)
        assertFalse(defaults.disableLiquidGlass)
        assertFalse(defaults.allEnabled)
    }

    @Test
    fun `partial states do not read as the master`() {
        assertFalse(LowPerformanceSettings(disableAnimations = true).allEnabled)
        assertFalse(LowPerformanceSettings(legacyPlayer = true).allEnabled)
        assertFalse(LowPerformanceSettings(disableLiquidGlass = true).allEnabled)
        assertFalse(
            LowPerformanceSettings(disableAnimations = true, legacyPlayer = true).allEnabled
        )
    }
}
