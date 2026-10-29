// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.glyph.asset.GlyphSkinAsset
import tf.monochrome.android.glyph.asset.GlyphSkinManifest

/**
 * Turns what [StepManiaStyleScan] found into Trypt Mania's own layouts, once,
 * at import, so nothing reads StepMania's formats while a run plays:
 *
 * * a noteskin into the retro skin's: one PNG per lane and part
 *   (`tap_<lane>_<division>`, `receptor_<lane>[_active]`, holds, rolls, the
 *   mine, the explosions at twice a note's size) and a [GlyphSkinManifest], as
 *   `tools/stepmania_skin/build_retro_skin.py` cuts the retro skin: the Down
 *   arrow turned for each lane, a sheet's first frame, hold bodies and caps
 *   flipped (StepMania's notes rise, Trypt Mania's fall);
 * * a theme into the names `GlyphStepArt` loads (`judgment_<w>_<early|late>.png`),
 *   the combo's digits and label, a font, a background and two sounds;
 * * an announcer into one folder of clips per [AnnouncerEvent].
 *
 * Every baked folder carries `style.json` and a `LICENSE.txt` naming where
 * the art came from: it is its authors', imported by the listener.
 */
class StepManiaStyleBaker(private val source: File, private val archiveName: String) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    // ── noteskins ───────────────────────────────────────────────────────

    /** Bakes the noteskin at [found] into [out]; false when it has no tap note to draw. */
    fun bakeNoteSkin(files: List<String>, found: StepManiaStyleScan.Found, out: File): Boolean {
        val resolver = NoteSkinResolver(files, found.dir) { path -> readText(path) }
        val taps = resolver.taps()
        val quarter = taps[tf.monochrome.android.glyph.asset.GlyphBeatDivision.QUARTER] ?: return false
        val sheets = HashMap<String, Bitmap?>()
        fun frame(pick: NoteSkinResolver.Pick): Bitmap? {
            val sheet = sheets.getOrPut(pick.image.path) { decode(pick.image.path) } ?: return null
            return cut(sheet, pick.image.cols, pick.image.rows, pick.frame)
        }
        val tapArt = frame(quarter) ?: return false
        val size = maxOf(tapArt.width, tapArt.height).coerceIn(16, 512)
        out.mkdirs()
        val assets = ArrayList<GlyphSkinAsset>()
        fun save(category: String, name: String, bitmap: Bitmap) {
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assets += GlyphSkinAsset(category, name, "$name.png")
        }

        for ((division, pick) in taps) {
            val art = frame(pick)?.let { square(it, size) } ?: continue
            for ((lane, turn) in LANES) {
                save("noteskin/taps", "tap_${lane}_${division.assetSuffix}", turned(art, turn))
                if (division == tf.monochrome.android.glyph.asset.GlyphBeatDivision.QUARTER) {
                    save("noteskin/taps", "tap_$lane", turned(art, turn))
                }
            }
        }
        resolver.receptor()?.let { (rest, active) ->
            val restArt = frame(rest)?.let { square(it, size) }
            val activeArt = frame(active)?.let { square(it, size) } ?: restArt
            for ((lane, turn) in LANES) {
                restArt?.let { save("noteskin/receptors", "receptor_$lane", turned(it, turn)) }
                activeArt?.let { save("noteskin/receptors", "receptor_${lane}_active", turned(it, turn)) }
            }
        }
        for (roll in listOf(false, true)) {
            val kind = if (roll) "roll" else "hold"
            val head = resolver.holdHead(roll)?.let(::frame)?.let { square(it, size) } ?: tapArt.let { square(it, size) }
            for ((lane, turn) in LANES) save("noteskin/holds", "${kind}_head_$lane", turned(head, turn))
            for (active in listOf(false, true)) {
                val suffix = if (active) "_active" else ""
                resolver.holdBody(roll, active)?.let(::frame)?.let { body ->
                    save("noteskin/holds", "${kind}_body$suffix", flipped(stretched(body, size)))
                }
                resolver.holdTail(roll, active)?.let(::frame)?.let { tail ->
                    save("noteskin/holds", "${kind}_tail$suffix", flipped(square(tail, size)))
                }
            }
        }
        resolver.mine()?.let(::frame)?.let { save("noteskin/special", "mine", square(it, size)) }
        resolver.explosion()?.let(::frame)?.let { glow ->
            val effect = centred(glow, size * 2)
            for ((lane, turn) in LANES) {
                save("feedback/effects", "tap_explosion_$lane", turned(effect, turn))
                save("feedback/effects", "hold_glow_$lane", turned(effect, turn))
            }
        }

        val manifest = GlyphSkinManifest(
            name = found.name,
            version = 1,
            // Small art drawn square-edged, as StepMania draws it; doubleres art is smooth.
            pixelArt = size <= 64 && !quarter.image.doubleRes,
            source = archiveName,
            license = "LICENSE.txt",
            assets = assets.sortedBy { it.name },
        )
        File(out, "manifest.json").writeText(json.encodeToString(manifest))
        writeStyle(out, StyleInfo(StepManiaStyleScan.Kind.NOTESKIN.name, found.name, archiveName))
        writeLicence(out, found)
        return true
    }

    // ── themes ──────────────────────────────────────────────────────────

    /** Bakes the theme at [found] into [out]; false when it has nothing Trypt Mania uses. */
    fun bakeTheme(files: List<String>, found: StepManiaStyleScan.Found, out: File): Boolean {
        val theme = ThemeResolver(files, found.dir).find()
        if (theme.isEmpty) return false
        out.mkdirs()
        var kept = 0
        theme.judgements?.let { sheet ->
            val image = decode(sheet.path) ?: return@let
            for ((row, window) in JUDGEMENT_ROWS.withIndex()) {
                val early = cut(image, sheet.cols, sheet.rows, row * sheet.cols) ?: continue
                val late = if (sheet.cols >= 2) cut(image, sheet.cols, sheet.rows, row * sheet.cols + 1) ?: early else early
                // A cell is often mostly empty around small letters: trimmed to
                // them, so the game sizes the word, not the cell.
                savePng(File(out, "judgment_${window}_early.png"), trimmed(early))
                savePng(File(out, "judgment_${window}_late.png"), trimmed(late))
            }
            kept += 1
        }
        theme.comboDigits?.let { sheet ->
            val image = decode(sheet.path) ?: return@let
            savePng(File(out, COMBO_DIGITS), image)
            val cells = ThemeResolver.digitCells(theme.comboDigitsIni?.let(::readText), sheet.cols, sheet.rows)
            File(out, COMBO_DIGITS_INFO).writeText(
                json.encodeToString(ComboDigits(sheet.cols, sheet.rows, cells.mapKeys { it.key.toString() })),
            )
            kept += 1
        }
        theme.comboLabel?.let { path -> decode(path)?.let { savePng(File(out, COMBO_LABEL), it); kept += 1 } }
        theme.fonts.firstOrNull()?.let { path ->
            File(out, "fonts").mkdirs()
            File(source, path).copyTo(File(out, "fonts/$FONT"), overwrite = true)
            kept += 1
        }
        theme.background?.let { path ->
            decode(path)?.let { image ->
                val scaled = fit(image, BACKGROUND_MAX)
                File(out, BACKGROUND).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                kept += 1
            }
        }
        for ((path, name) in listOf(theme.hitSound to "hit", theme.missSound to "miss")) {
            path ?: continue
            File(out, "sounds").mkdirs()
            File(source, path).copyTo(File(out, "sounds/$name.${path.substringAfterLast('.').lowercase()}"), overwrite = true)
            kept += 1
        }
        if (kept == 0) {
            out.deleteRecursively()
            return false
        }
        writeStyle(out, StyleInfo(StepManiaStyleScan.Kind.THEME.name, found.name, archiveName))
        writeLicence(out, found)
        return true
    }

    // ── announcers ──────────────────────────────────────────────────────

    /** Copies the announcer's clips for the moments a run has; false when it has none of them. */
    fun bakeAnnouncer(files: List<String>, found: StepManiaStyleScan.Found, out: File): Boolean {
        val prefix = if (found.dir.isEmpty()) "" else "${found.dir}/"
        var clips = 0
        for (path in files) {
            if (!path.startsWith(prefix)) continue
            val inside = path.removePrefix(prefix)
            val folder = inside.substringBefore('/', missingDelimiterValue = "")
            if (folder.isEmpty() || inside.count { it == '/' } != 1) continue
            val event = AnnouncerEvent.forFolder(folder) ?: continue
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension !in ThemeResolver.AUDIO) continue
            val dir = File(out, event.key).apply { mkdirs() }
            if ((dir.list()?.size ?: 0) >= MAX_CLIPS) continue
            File(source, path).copyTo(File(dir, "${dir.list()?.size ?: 0}.$extension"), overwrite = true)
            clips += 1
        }
        if (clips == 0) {
            out.deleteRecursively()
            return false
        }
        writeStyle(out, StyleInfo(StepManiaStyleScan.Kind.ANNOUNCER.name, found.name, archiveName))
        writeLicence(out, found)
        return true
    }

    // ── characters ──────────────────────────────────────────────────────

    /**
     * A dancing character: its MilkShape model and the textures its materials
     * name, and its moves. A move it carries is copied; one it points at (most
     * point at the archive's shared "Animation + Helper Bones") is kept by
     * file name, found among the imported dances when it plays. False when
     * the model does not read.
     */
    fun bakeCharacter(files: List<String>, found: StepManiaStyleScan.Found, out: File): Boolean {
        val prefix = if (found.dir.isEmpty()) "" else "${found.dir}/"
        val modelPath = files.firstOrNull { it.startsWith(prefix) && it.removePrefix(prefix).equals("model.txt", ignoreCase = true) } ?: return false
        val text = readLarge(modelPath) ?: return false
        val model = runCatching { Milkshape.parse(text) }.getOrNull() ?: return false
        if (model.meshes.isEmpty()) return false
        out.mkdirs()
        File(out, MODEL).writeText(text)
        // The textures, by the names the materials give, wherever their case.
        val inDir = files.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
        for (material in model.materials) {
            val name = material.texture.substringAfterLast('/')
            if (name.isEmpty()) continue
            val source = inDir.firstOrNull { it.substringAfterLast('/').equals(name, ignoreCase = true) } ?: continue
            decode(source)?.let { image -> savePng(File(out, "${TEXTURES}/${pngName(name)}").also { it.parentFile?.mkdirs() }, fit(image, TEXTURE_MAX)) }
        }
        // The moves: StepMania's Dance, Rest and WarmUp folders.
        val moves = LinkedHashMap<String, MutableList<String>>()
        for (path in files) {
            if (!path.startsWith(prefix)) continue
            val inside = path.removePrefix(prefix)
            val folder = inside.substringBefore('/', missingDelimiterValue = "").lowercase()
            if (folder !in MOVE_FOLDERS || inside.count { it == '/' } != 1) continue
            val fileName = inside.substringAfter('/')
            val entry = when {
                fileName.endsWith(".bones.txt", ignoreCase = true) -> {
                    File(out, "$MOVES/$folder").mkdirs()
                    File(source, path).copyTo(File(out, "$MOVES/$folder/$fileName"), overwrite = true)
                    "$OWN_PREFIX$folder/$fileName"
                }
                fileName.endsWith(".redir", ignoreCase = true) -> readText(path)?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    ?.trim()?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.endsWith(".bones.txt", ignoreCase = true) }
                else -> null
            } ?: continue
            moves.getOrPut(folder) { ArrayList() } += entry
        }
        File(out, MOVES_INFO).writeText(json.encodeToString(CharacterMoves(moves.mapValues { it.value.sorted() })))
        writeStyle(out, StyleInfo(StepManiaStyleScan.Kind.CHARACTER.name, found.name, archiveName))
        writeLicence(out, found)
        return true
    }

    /** A set of dances characters share: every .bones.txt in it, copied as they are. */
    fun bakeDances(files: List<String>, found: StepManiaStyleScan.Found, out: File): Boolean {
        val prefix = if (found.dir.isEmpty()) "" else "${found.dir}/"
        var kept = 0
        for (path in files) {
            if (!path.startsWith(prefix) || '/' in path.removePrefix(prefix)) continue
            if (!path.endsWith(".bones.txt", ignoreCase = true)) continue
            out.mkdirs()
            File(source, path).copyTo(File(out, path.substringAfterLast('/')), overwrite = true)
            kept += 1
        }
        if (kept == 0) return false
        writeStyle(out, StyleInfo(StepManiaStyleScan.Kind.DANCES.name, found.name, archiveName))
        writeLicence(out, found)
        return true
    }

    private fun pngName(name: String) = name.substringBeforeLast('.') + ".png"

    private fun readLarge(path: String): String? = runCatching {
        File(source, path).takeIf { it.isFile && it.length() <= 32L * 1024 * 1024 }?.readText()
    }.getOrNull()

    // ── images ──────────────────────────────────────────────────────────

    private fun decode(path: String): Bitmap? = runCatching {
        val file = File(source, path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        // Anything past 4096 on a side is no arrow; it is read smaller.
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
            inScaled = false
        })
    }.getOrNull()

    /** Frame [index] of a [cols]×[rows] sheet, across then down. */
    private fun cut(sheet: Bitmap, cols: Int, rows: Int, index: Int): Bitmap? {
        val w = sheet.width / cols
        val h = sheet.height / rows
        if (w <= 0 || h <= 0) return null
        val i = index.coerceIn(0, cols * rows - 1)
        return Bitmap.createBitmap(sheet, (i % cols) * w, (i / cols) * h, w, h)
    }

    /** [art] centred on a square [size] canvas, scaled down to fit when bigger. */
    private fun square(art: Bitmap, size: Int): Bitmap = centred(art, size)

    private fun centred(art: Bitmap, size: Int): Bitmap {
        val scale = minOf(1f, size.toFloat() / art.width, size.toFloat() / art.height)
        val w = (art.width * scale).toInt().coerceAtLeast(1)
        val h = (art.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(art, null, Rect((size - w) / 2, (size - h) / 2, (size - w) / 2 + w, (size - h) / 2 + h), PAINT)
        return out
    }

    /** A hold body at note size: tiled down the hold, so drawn to the square, stretched rather than cut. */
    private fun stretched(art: Bitmap, size: Int): Bitmap = Bitmap.createScaledBitmap(art, size, size, true)

    private fun turned(art: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return art
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(art, 0, 0, art.width, art.height, matrix, true)
    }

    private fun flipped(art: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(1f, -1f) }
        return Bitmap.createBitmap(art, 0, 0, art.width, art.height, matrix, false)
    }

    /** [art] cut to its visible pixels, with a pixel or two of room; as it is when all clear. */
    private fun trimmed(art: Bitmap): Bitmap {
        val w = art.width
        val h = art.height
        val pixels = IntArray(w * h)
        art.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) > TRIM_ALPHA) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return art
        val pad = maxOf(1, minOf(w, h) / 32)
        left = (left - pad).coerceAtLeast(0); top = (top - pad).coerceAtLeast(0)
        right = (right + pad).coerceAtMost(w - 1); bottom = (bottom + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(art, left, top, right - left + 1, bottom - top + 1)
    }

    private fun fit(image: Bitmap, max: Int): Bitmap {
        val scale = minOf(1f, max.toFloat() / maxOf(image.width, image.height))
        if (scale >= 1f) return image
        return Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true)
    }

    private fun savePng(file: File, bitmap: Bitmap) =
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    // ── text ────────────────────────────────────────────────────────────

    private fun readText(path: String): String? = runCatching {
        File(source, path).takeIf { it.isFile && it.length() <= 1024 * 1024 }?.readText()
    }.getOrNull()

    private fun writeStyle(out: File, info: StyleInfo) = File(out, STYLE_FILE).writeText(json.encodeToString(info))

    /** Where the art came from, and the author's own notes beside it, kept with it. */
    private fun writeLicence(out: File, found: StepManiaStyleScan.Found) {
        val notes = File(source, found.dir).listFiles().orEmpty()
            .filter { it.isFile && it.name.lowercase().let { n -> ("readme" in n || "licen" in n || "credit" in n) && n.endsWith(".txt") } }
            .take(4)
            .joinToString("\n\n") { "── ${it.name} ──\n" + it.readText().take(64 * 1024) }
        File(out, "LICENSE.txt").writeText(
            "\"${found.name}\" was imported into Trypt Mania from \"$archiveName\", a StepMania download.\n" +
                "Its art and sounds belong to their authors; Trypt Mania only cut and turned them to its own layout.\n" +
                if (notes.isNotEmpty()) "\nThe author's notes, as they came:\n\n$notes\n" else "",
        )
    }

    @Serializable
    data class StyleInfo(val kind: String, val name: String, val source: String)

    /** A character's moves by StepMania's folder (dance, rest, warmup): its own ("own:…") or a shared dance's file name. */
    @Serializable
    data class CharacterMoves(val moves: Map<String, List<String>>)

    @Serializable
    data class ComboDigits(val cols: Int, val rows: Int, val cells: Map<String, Int>)

    companion object {
        /** Each lane's turn of the Down arrow, clockwise, as the retro skin is cut. */
        private val LANES = listOf("left" to 90f, "down" to 0f, "up" to 180f, "right" to -90f)
        /** StepMania's judgement rows, top to bottom, as `GlyphStepArt.judgement` names them. */
        private val JUDGEMENT_ROWS = listOf("w1", "w2", "w3", "w4", "w5", "miss")
        private val PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private const val MAX_SIDE = 4096
        private const val BACKGROUND_MAX = 1920
        private const val MAX_CLIPS = 16
        /** Pixels fainter than this are empty when a judgement is trimmed. */
        private const val TRIM_ALPHA = 8

        const val STYLE_FILE = "style.json"
        const val MODEL = "model.txt"
        const val TEXTURES = "textures"
        const val MOVES = "moves"
        const val MOVES_INFO = "moves.json"
        const val OWN_PREFIX = "own:"
        private val MOVE_FOLDERS = setOf("dance", "rest", "warmup")
        private const val TEXTURE_MAX = 512
        const val COMBO_DIGITS = "combo_digits.png"
        const val COMBO_DIGITS_INFO = "combo_digits.json"
        const val COMBO_LABEL = "combo_label.png"
        const val FONT = "theme.ttf"
        const val BACKGROUND = "background.jpg"
    }
}
