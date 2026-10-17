// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphAssetId
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphEffectArt
import tf.monochrome.android.glyph.asset.GlyphGlass
import tf.monochrome.android.glyph.asset.GlyphGlassLook
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.asset.GlyphSkinManifest
import tf.monochrome.android.glyph.asset.GlyphSpecialNote

/**
 * The StepMania retro skin and its glass.
 *
 * The glass runs here on the shipped PNGs exactly as the app runs it, and the
 * results are written to build/glyph-skin-preview so the look can be checked
 * by eye as well as by assertion.
 */
class GlyphSkinTest {

    private val root = File("src/main/assets/stepmania/skins/retro")
    private val json = Json { ignoreUnknownKeys = true }
    private val manifest: GlyphSkinManifest by lazy {
        json.decodeFromString(File(root, "manifest.json").readText())
    }
    private val look = GlyphGlassLook.from(PlayerGlassSettings.DEFAULT)!!

    private fun pixels(name: String): Triple<IntArray, Int, Int> {
        val image = TestPng.read(File(root, "$name.png"))
        return Triple(image.argb, image.width, image.height)
    }

    private fun alpha(c: Int) = (c ushr 24) and 0xFF
    private fun luma(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)

    @Test
    fun theSkinShipsWithItsLicenceAndEveryFileItNames() {
        assertTrue("StepMania's notice must travel with its art", File(root, "LICENSE.txt").readText().contains("StepMania"))
        assertTrue(manifest.pixelArt)
        val missing = manifest.assets.filter { !File(root, it.path).isFile }.map { it.path }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun everySkinImageIsSomethingThePlayfieldAsksFor() {
        val askable = buildSet {
            for (lane in GlyphLane.DANCE) {
                add(GlyphAssetCatalog.receptor(lane, active = false))
                add(GlyphAssetCatalog.receptor(lane, active = true))
                add(GlyphAssetCatalog.tapDefault(lane))
                for (division in GlyphBeatDivision.entries) add(GlyphAssetCatalog.tap(lane, division))
                add(GlyphAssetCatalog.holdHead(lane, roll = false))
                add(GlyphAssetCatalog.holdHead(lane, roll = true))
                for (art in GlyphEffectArt.entries) add(GlyphAssetCatalog.effect(art, lane))
            }
            for (roll in listOf(false, true)) for (active in listOf(false, true)) {
                add(GlyphAssetCatalog.holdBody(roll, active))
                add(GlyphAssetCatalog.holdTail(roll, active))
            }
            for (note in GlyphSpecialNote.entries) add(GlyphAssetCatalog.special(note))
        }.map(GlyphAssetId::name).toSet()
        val dead = manifest.assets.map { it.name }.filter { it !in askable }
        assertEquals("skin images nothing draws", emptyList<String>(), dead)
    }

    @Test
    fun namesOnlySomePacksHaveFallBackToOnesEveryPackHas() {
        assertEquals("hold_body", GlyphAssetCatalog.fallback(GlyphAssetCatalog.holdBody(roll = false, active = true))?.name)
        assertEquals("roll_tail", GlyphAssetCatalog.fallback(GlyphAssetCatalog.holdTail(roll = true, active = true))?.name)
        assertEquals(
            "tap_explosion",
            GlyphAssetCatalog.fallback(GlyphAssetCatalog.effect(GlyphEffectArt.TAP_EXPLOSION, GlyphLane.UP))?.name,
        )
        assertNull("a pressed receptor is not a hold", GlyphAssetCatalog.fallback(GlyphAssetCatalog.receptor(GlyphLane.UP, true)))
        assertEquals(
            "a lane-less effect has no lane variant",
            "miss_crack",
            GlyphAssetCatalog.effect(GlyphEffectArt.MISS_CRACK, GlyphLane.LEFT).name,
        )
    }

    @Test
    fun glassOffMeansThePlainSkin() {
        assertNull(GlyphGlassLook.from(PlayerGlassSettings.DEFAULT.copy(enabled = false)))
        assertEquals(GlyphSkin.RETRO_GLASS, GlyphSkin.fromId("nonsense"))
        assertEquals(GlyphSkin.CLASSIC, GlyphSkin.fromId("classic"))
    }

    @Test
    fun glassKeepsTheShapeClearsTheBodyAndLightsTheRim() {
        val (source, w, h) = pixels("tap_down_4th")
        val glass = GlyphGlass.apply(source, w, h, look)
        val depth = GlyphGlass.edgeDepth(source, w, h, tileVertically = false)

        for (i in source.indices) {
            if (alpha(source[i]) <= 16) assertEquals("outside stays as it was", source[i], glass[i])
        }
        val inner = source.indices.filter { depth[it] >= 4 }
        val rim = source.indices.filter { depth[it] == 1 }
        assertTrue(inner.isNotEmpty() && rim.isNotEmpty())
        assertTrue(
            "the body is see-through",
            inner.map { alpha(glass[it]) }.average() < inner.map { alpha(source[it]) }.average() * 0.8,
        )
        assertTrue("the rim stays solid", rim.all { alpha(glass[it]) == alpha(source[it]) })
        assertTrue(
            "the rim catches light",
            rim.map { luma(glass[it]) }.average() > rim.map { luma(source[it]) }.average(),
        )
    }

    @Test
    fun aGlassHoldBodyTilesWithoutASeam() {
        val (source, w, h) = pixels("hold_body")
        val glass = GlyphGlass.apply(source, w, h, look, tileVertically = true)
        // The top and bottom rows of the source match, so the tile joins; the
        // glass must not light either as an edge.
        val top = glass.copyOfRange(0, w)
        val bottom = glass.copyOfRange((h - 1) * w, h * w)
        val sourceTop = source.copyOfRange(0, w)
        val sourceBottom = source.copyOfRange((h - 1) * w, h * w)
        if (sourceTop.contentEquals(sourceBottom)) {
            assertTrue("glass made a seam", top.contentEquals(bottom))
        }
        // And no row is lit differently from the middle one: no band per tile.
        val middle = glass.copyOfRange((h / 2) * w, (h / 2 + 1) * w)
        val sourceMiddle = source.copyOfRange((h / 2) * w, (h / 2 + 1) * w)
        if (sourceTop.contentEquals(sourceMiddle)) {
            assertTrue("the highlight changes along the hold", top.contentEquals(middle))
        }
    }

    @Test
    fun theLightFollowsTheSlider() {
        val (source, w, h) = pixels("receptor_down")
        fun litSide(angle: Float): Double {
            val glass = GlyphGlass.apply(
                source, w, h,
                GlyphGlassLook.from(PlayerGlassSettings.DEFAULT.copy(lightAngleDeg = angle))!!,
            )
            val left = (0 until h).flatMap { y -> (0 until w / 2).map { glass[y * w + it] } }
            val right = (0 until h).flatMap { y -> (w / 2 until w).map { glass[y * w + it] } }
            return left.filter { alpha(it) > 16 }.map(::luma).average() -
                right.filter { alpha(it) > 16 }.map(::luma).average()
        }
        // 180 degrees lights from the left, 0 from the right.
        assertTrue(litSide(180f) > litSide(0f))
    }

    @Test
    fun writeThePreview() {
        val out = File("build/glyph-skin-preview").apply { mkdirs() }
        for (entry in manifest.assets) {
            val (source, w, h) = pixels(entry.name)
            val glassed = if (entry.category.startsWith("feedback/")) {
                source
            } else {
                GlyphGlass.apply(
                    source, w, h, look,
                    tileVertically = entry.name.contains("_body"),
                    openBottom = entry.name.contains("_tail"),
                )
            }
            val file = File(out, "${entry.name}.png")
            TestPng.write(file, TestPng.Image(w, h, glassed))
            // Round-trips, so what is previewed is what was computed.
            assertTrue(TestPng.read(file).argb.contentEquals(glassed))
        }
    }
}
