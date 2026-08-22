// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.glyph.glyphJudgementFx

/**
 * The FX configuration the judgement is drawn with.
 *
 * It borrows the lyrics renderer, so it inherits the renderer's two gates and
 * its clamp ranges. Both are easy to break by editing a number, and neither
 * fails loudly — an out-of-range value is silently coerced, and a gate crossed
 * by accident switches a whole subsystem on or off with no error.
 */
class GlyphJudgementFxTest {

    private val fx = glyphJudgementFx()

    @Test
    fun theConfigurationSurvivesItsOwnClamping() {
        // The same guarantee the shipped Studio presets get: a value outside
        // the renderer's assumed range is coerced rather than rejected, so
        // equality with clamped() is the only thing that catches a typo.
        assertEquals(fx, fx.clamped())
    }

    @Test
    fun theThreeDPathIsOnBecauseThatIsThePointOfBorrowingIt() {
        // rotationDegrees is a gate as well as an amplitude: at or below 0.05
        // the whole per-letter path is skipped and waveSpeed, wavePhaseStep,
        // waveTravelDp and shadowDepth all become dead settings.
        assertTrue("the per-letter 3D path must be enabled", fx.rotationDegrees > 0.05f)
        assertTrue(fx.liquidGlass)
    }

    @Test
    fun theAudioAnalyzerStaysOffDuringPlay() {
        // bassReact <= 0.01 gates the analyzer off. Gameplay already knows
        // exactly when a hit landed, so inferring beats from the audio would be
        // a second consumer of it for no gain — the swell is driven from the
        // judgement event instead.
        assertTrue("the beat analyzer must not run during play", fx.bassReact <= 0.01f)
    }

    @Test
    fun theBodyStaysReadableOverAMovingPlayfield() {
        // This sits over travelling arrows rather than a still album backdrop,
        // so a ghosted body that reads beautifully in the player would be
        // unreadable here.
        assertTrue("judgement text must not be see-through", fx.glassBodyOpacity >= 0.75f)
    }

    @Test
    fun itSetsNoPersonalFields() {
        // Personal fields belong to the listener and are carried across by
        // withPersonalFrom; setting them in a fixed configuration would be
        // both pointless and a way to stomp someone's own choice.
        val default = tf.monochrome.android.domain.model.LyricsFxSettings()
        assertEquals(default.customFont, fx.customFont)
        assertEquals(default.customFontPath, fx.customFontPath)
        assertEquals(default.bluetoothDelayMs, fx.bluetoothDelayMs, 0f)
        assertEquals(default.glassSampleRings, fx.glassSampleRings)
        assertEquals(default.fxaa, fx.fxaa)
        assertEquals(default.glowBehindArt, fx.glowBehindArt)
    }
}
