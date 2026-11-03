// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.ui.glyph.GlyphHitLight
import tf.monochrome.android.ui.glyph.LaneFlashes

/** The light a hit flash throws on the glyphs around it. */
class GlyphHitLightTest {

    private val lane = 200
    private val note = 160
    private val receptorY = 1800f
    private val second = 1_000_000_000L

    private fun lit(flashes: LaneFlashes, now: Long) = GlyphHitLight().apply {
        update(now, flashes, lane, note, receptorY)
    }

    /** [lanes] flashed at time 0, each a hit or a miss. */
    private fun flashes(vararg lanes: Pair<GlyphLane, Boolean>) = LaneFlashes().apply {
        for ((l, isHit) in lanes) set(l, atNanos = 0L, isHit = isHit)
    }

    private fun centreOf(l: GlyphLane) = l.ordinal * lane + lane / 2f

    @Test
    fun aHitLightsItsNeighboursMostAndFarGlyphsNotAtAll() {
        val light = lit(flashes(GlyphLane.DOWN to true), now = 0L)
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
        val flash = flashes(GlyphLane.LEFT to true)
        val atHit = lit(flash, 0L).at(centreOf(GlyphLane.LEFT), receptorY)
        val later = lit(flash, second / 10).at(centreOf(GlyphLane.LEFT), receptorY)
        val gone = lit(flash, second / 4)
        assertTrue(later < atHit)
        assertFalse("a fifth of a second and it is over", gone.isLit)
    }

    @Test
    fun aMissThrowsNoLight() {
        val light = lit(flashes(GlyphLane.UP to false), 0L)
        assertFalse(light.isLit)
        assertEquals(0f, light.at(centreOf(GlyphLane.UP), receptorY), 0f)
    }

    @Test
    fun twoHitsAddUpButNeverPastFull() {
        val both = lit(flashes(GlyphLane.DOWN to true, GlyphLane.UP to true), 0L)
        val one = lit(flashes(GlyphLane.DOWN to true), 0L)
        val between = (centreOf(GlyphLane.DOWN) + centreOf(GlyphLane.UP)) / 2f
        assertTrue(both.at(between, receptorY) > one.at(between, receptorY))
        assertTrue(both.at(between, receptorY) <= 1f)
    }

    @Test
    fun laneFlashesKeepEachLanesNewestAndClearToNone() {
        val slots = LaneFlashes()
        assertEquals("none yet", 0L, slots.newestNanos())
        assertFalse(slots.has(GlyphLane.UP.ordinal))
        slots.set(GlyphLane.UP, atNanos = 5L, isHit = true)
        slots.set(GlyphLane.LEFT, atNanos = 9L, isHit = false)
        slots.set(GlyphLane.UP, atNanos = 7L, isHit = false)
        assertTrue(slots.has(GlyphLane.UP.ordinal))
        assertEquals("a lane keeps its latest judgement", 7L, slots.atNanos(GlyphLane.UP.ordinal))
        assertFalse(slots.isHit(GlyphLane.UP.ordinal))
        assertFalse(slots.has(GlyphLane.DOWN.ordinal))
        assertEquals(9L, slots.newestNanos())
        slots.clear()
        assertFalse(slots.has(GlyphLane.LEFT.ordinal))
        assertEquals(0L, slots.newestNanos())
        assertFalse("a cleared field throws no light", lit(slots, 9L).isLit)
    }
}
