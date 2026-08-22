// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphAssetCatalog
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphDecor
import tf.monochrome.android.glyph.asset.GlyphEffectArt
import tf.monochrome.android.glyph.asset.GlyphGradeArt
import tf.monochrome.android.glyph.asset.GlyphIcon
import tf.monochrome.android.glyph.asset.GlyphJudgementArt
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphManifest
import tf.monochrome.android.glyph.asset.GlyphPaint
import tf.monochrome.android.glyph.asset.GlyphShape
import tf.monochrome.android.glyph.asset.GlyphSpecialNote
import tf.monochrome.android.glyph.asset.GlyphSvgParser

/**
 * The pack, checked against the code that names it.
 *
 * Reads the real asset directory rather than a fixture, so a file renamed or
 * dropped from the pack fails here instead of becoming an invisible note.
 */
class GlyphAssetCatalogTest {

    private val root = File("src/main/assets/stepmania/glyph")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val manifest: GlyphManifest by lazy {
        val file = File(root, "manifest.json")
        // Names the cause rather than leaving a bare FileNotFoundException.
        // This has already happened once: the repository ignores *.json
        // wholesale and needs a negation per committed asset, so the pack was
        // pushed without the one file every lookup goes through — a fresh
        // clone drew fallback squares for every note and only CI noticed.
        assertTrue(
            "${'$'}{file.path} is missing. It is committed, so this usually means a " +
                "blanket .gitignore rule swallowed it — check for a matching " +
                "negation (!${'$'}{file.path}).",
            file.isFile,
        )
        json.decodeFromString<GlyphManifest>(file.readText())
    }

    @Test
    fun theWholePackIsPresentOnDiskNotJustTheManifest() {
        // The manifest and the artwork are ignored by different .gitignore
        // rules, so one can ship without the other in either direction.
        assertTrue("the pack directory is missing entirely", root.isDirectory)
        val svgs = root.walkTopDown()
            .filter { it.isFile && it.extension == "svg" && !it.name.startsWith("preview_") }
            .count()
        assertEquals("production SVGs on disk", 117, svgs)
    }

    @Test
    fun manifestParsesAndDescribesTheWholePack() {
        assertEquals("Tryptify StepTech Glyph", manifest.name)
        assertEquals(8, manifest.designGrid)
        assertEquals(117, manifest.assets.size)
        // Names are the lookup key, so a duplicate would make one of them
        // unreachable.
        assertEquals(manifest.assets.size, manifest.byName.size)
        assertEquals(8, manifest.palette.beatColors.size)
        assertEquals(4, manifest.palette.laneAccents.size)
    }

    @Test
    fun everyManifestEntryHasAFileThatParses() {
        val failures = manifest.assets.mapNotNull { entry ->
            val file = File(root, entry.path)
            if (!file.exists()) return@mapNotNull "${entry.name}: no file at ${entry.path}"
            runCatching { file.inputStream().use(GlyphSvgParser::parse) }
                .fold(
                    onSuccess = { vector ->
                        val declared = entry.size
                        when {
                            declared == null -> "${entry.name}: malformed viewBox '${entry.viewBox}'"
                            vector.viewportWidth != declared.first ||
                                vector.viewportHeight != declared.second ->
                                "${entry.name}: viewBox disagrees with the file"
                            vector.shapes.isEmpty() -> "${entry.name}: parsed to nothing"
                            else -> null
                        }
                    },
                    onFailure = { "${entry.name}: ${it.message}" },
                )
        }
        assertEquals("assets that will not load", emptyList<String>(), failures)
    }

    @Test
    fun everyCatalogNameResolvesToAManifestEntry() {
        val ids = buildList {
            for (lane in GlyphLane.entries) {
                add(GlyphAssetCatalog.receptor(lane, active = false))
                add(GlyphAssetCatalog.receptor(lane, active = true))
                add(GlyphAssetCatalog.tapDefault(lane))
                for (division in GlyphBeatDivision.entries) {
                    add(GlyphAssetCatalog.tap(lane, division))
                }
                add(GlyphAssetCatalog.holdHead(lane, roll = false))
                add(GlyphAssetCatalog.holdHead(lane, roll = true))
            }
            add(GlyphAssetCatalog.holdBody(roll = false))
            add(GlyphAssetCatalog.holdTail(roll = false))
            add(GlyphAssetCatalog.holdBody(roll = true))
            add(GlyphAssetCatalog.holdTail(roll = true))
            for (note in GlyphSpecialNote.entries) add(GlyphAssetCatalog.special(note))
            for (art in GlyphJudgementArt.entries) add(GlyphAssetCatalog.judgement(art))
            for (art in GlyphGradeArt.entries) add(GlyphAssetCatalog.grade(art))
            for (art in GlyphEffectArt.entries) add(GlyphAssetCatalog.effect(art))
            for (icon in GlyphIcon.entries) add(GlyphAssetCatalog.icon(icon))
            for (decor in GlyphDecor.entries) add(GlyphAssetCatalog.decor(decor))
        }

        val unknown = ids.map { it.name }.filter { manifest.asset(it) == null }
        assertEquals("catalog names with no manifest entry", emptyList<String>(), unknown)
        // Every production asset should be reachable by name from the catalog;
        // an asset nothing can ask for is dead weight in the APK.
        val unreachable = manifest.assets.map { it.name } - ids.map { it.name }.toSet()
        assertEquals("manifest entries the catalog cannot name", emptyList<String>(), unreachable)
    }

    @Test
    fun onlyUiArtworkIsTintable() {
        // Notes and feedback carry their own semantic colours. If one of them
        // became tintable a global tint would flatten the beat palette.
        val wronglyTintable = manifest.assets
            .filter { it.tintable && !it.category.startsWith("ui/") }
            .map { it.name }
        assertEquals(emptyList<String>(), wronglyTintable)

        val wronglyFixed = manifest.assets
            .filter { !it.tintable && it.category.startsWith("ui/") }
            .map { it.name }
        assertEquals(emptyList<String>(), wronglyFixed)
    }

    @Test
    fun noteArtworkNeverUsesCurrentColor() {
        // The rasterizer resolves an unexpected currentColor to paper rather
        // than crashing, which would be a silently wrong note. This asserts the
        // situation never arises in the shipped pack.
        val offenders = manifest.assets
            .filter { it.category.startsWith("noteskin/") || it.category.startsWith("feedback/") }
            .filter { entry ->
                val vector = File(root, entry.path).inputStream().use(GlyphSvgParser::parse)
                vector.shapes.any { shape ->
                    shape.fill is GlyphPaint.CurrentColor ||
                        shape.stroke?.paint is GlyphPaint.CurrentColor
                }
            }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun holdBodiesTileWithoutASeam() {
        // A hold body is tiled vertically with no gap. Its artwork must reach
        // both edges of its viewBox, or every tile boundary shows a hairline.
        for (name in listOf("hold_body", "roll_body")) {
            val entry = manifest.asset(name)
            assertNotNull("$name is missing from the manifest", entry)
            val vector = File(root, entry!!.path).inputStream().use(GlyphSvgParser::parse)

            val top = vector.shapes.filterIsInstance<GlyphShape.Rect>().minOf { it.y }
            val bottom = vector.shapes.filterIsInstance<GlyphShape.Rect>()
                .maxOf { it.y + it.height }
            assertEquals("$name does not start at the top edge", 0f, top, 0.001f)
            assertEquals(
                "$name does not reach the bottom edge",
                vector.viewportHeight, bottom, 0.001f,
            )
        }
    }

    @Test
    fun playfieldPrewarmCoversEveryDrawableTheLoopUses() {
        val ids = GlyphAssetCatalog.playfieldAssets(setOf(GlyphBeatDivision.QUARTER))
            .map { it.name }
            .toSet()

        for (lane in GlyphLane.entries) {
            assertTrue(
                "receptors must be warm before play",
                GlyphAssetCatalog.receptor(lane, active = false).name in ids &&
                    GlyphAssetCatalog.receptor(lane, active = true).name in ids,
            )
        }
        assertTrue("hold bodies must be warm", "hold_body" in ids && "roll_body" in ids)
        for (art in GlyphJudgementArt.entries) {
            assertTrue("${art.assetName} must be warm", art.assetName in ids)
        }
        // A Beginner chart has no 48ths, so prewarming them would be waste.
        assertTrue("tap_left_48th" !in ids)
    }
}
