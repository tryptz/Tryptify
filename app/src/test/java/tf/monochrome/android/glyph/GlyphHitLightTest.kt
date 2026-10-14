// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.ui.glyph.GlyphHitLight
import tf.monochrome.android.ui.glyph.LaneFlash

/** The light a hit flash throws on the glyphs around it. */
class GlyphHitLightTest {

    private val lane = 200
    private val note = 160
    private val receptorY = 1800f
    private val second = 1_000_000_000L

    private fun lit(flashes: Map<GlyphLane, LaneFlash>, now: Long) = GlyphHitLight().apply {
        update(now, flashes, lane, note, receptorY)
    }

    private fun centreOf(l: GlyphLane) = l.ordinal * lane + lane / 2f

    @Test
    fun aHitLightsItsNeighboursMostAndFarGlyphsNotAtAll() {
        val light = lit(mapOf(GlyphLane.DOWN to LaneFlash(atNanos = 0L, isHit = true)), now = 0L)
        assertTrue(light.isLit)
        val onIt = light.at(centreOf(GlyphLane.DOWN), receptorY)
        val beside = light.at(centreOf(GlyphLane.UP), receptorY)
        val farLane = light.at(centreOf(GlyphLane.RIGHT), receptorY)
        val farUp = light.at(centreOf(GlyphLane.DOWN), receptorY - 6 * note)
        assertTrue("brightest on the struck note", onIt > beside && beside > farLane)
        assertTrue("the next lane catches real light", beside > 0.3f)
        assertEquals("past the shafts' reach there is none", 0f, farUp, 0f)
        assertTrue(onIt <= 1f)
    }

    @Test
    fun theLightIsBriefAndFades() {
        val flash = mapOf(GlyphLane.LEFT to LaneFlash(atNanos = 0L, isHit = true))
        val atHit = lit(flash, 0L).at(centreOf(GlyphLane.LEFT), receptorY)
        val later = lit(flash, second / 10).at(centreOf(GlyphLane.LEFT), receptorY)
        val gone = lit(flash, second / 4)
        assertTrue(later < atHit)
        assertFalse("a fifth of a second and it is over", gone.isLit)
    }

    @Test
    fun aMissThrowsNoLight() {
        val light = lit(mapOf(GlyphLane.UP to LaneFlash(atNanos = 0L, isHit = false)), 0L)
        assertFalse(light.isLit)
        assertEquals(0f, light.at(centreOf(GlyphLane.UP), receptorY), 0f)
    }

    @Test
    fun twoHitsAddUpButNeverPastFull() {
        val both = lit(
            mapOf(
                GlyphLane.DOWN to LaneFlash(0L, true),
                GlyphLane.UP to LaneFlash(0L, true),
            ),
            0L,
        )
        val one = lit(mapOf(GlyphLane.DOWN to LaneFlash(0L, true)), 0L)
        val between = (centreOf(GlyphLane.DOWN) + centreOf(GlyphLane.UP)) / 2f
        assertTrue(both.at(between, receptorY) > one.at(between, receptorY))
        assertTrue(both.at(between, receptorY) <= 1f)
    }
}
