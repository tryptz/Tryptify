// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

/**
 * What Trypt Mania takes from a StepMania theme, found by StepMania's own
 * file names under the theme's folder: the judgement sheet, the combo's
 * digits and label, its fonts, a gameplay background, and the sounds of a
 * hit and a miss. A theme's screens are its Lua and its metrics, which Trypt
 * Mania never runs: its menus stay the app's. Pure Kotlin.
 */
class ThemeResolver(files: Collection<String>, private val themeDir: String) {

    data class Sheet(val path: String, val cols: Int, val rows: Int)

    data class Found(
        /** Six rows, Marvelous (W1) to Miss; two columns are early and late. */
        val judgements: Sheet?,
        /** The combo's digits: a bitmap font page, with the .ini that says which cell is which, if any. */
        val comboDigits: Sheet?,
        val comboDigitsIni: String?,
        val comboLabel: String?,
        val fonts: List<String>,
        val background: String?,
        val hitSound: String?,
        val missSound: String?,
    ) {
        val isEmpty: Boolean
            get() = judgements == null && comboDigits == null && comboLabel == null && fonts.isEmpty() &&
                background == null && hitSound == null && missSound == null
    }

    private val inTheme: List<String> = files.map { it.replace('\\', '/').trim('/') }
        .filter { themeDir.isEmpty() || it.startsWith("$themeDir/") }
        .map { if (themeDir.isEmpty()) it else it.removePrefix("$themeDir/") }
        // The theme's own noteskins are imported as noteskins, not read as its art.
        .filterNot { it.startsWith("NoteSkins/", ignoreCase = true) }

    fun find(): Found {
        val graphics = inTheme.filter { it.startsWith("Graphics/", ignoreCase = true) }
        val fonts = inTheme.filter { it.startsWith("Fonts/", ignoreCase = true) }
        val sounds = inTheme.filter { it.startsWith("Sounds/", ignoreCase = true) && isAudio(it) }
        val backgrounds = inTheme.filter {
            (it.startsWith("BGAnimations/", ignoreCase = true) || it.startsWith("Graphics/", ignoreCase = true)) &&
                NoteSkinNames.isImage(it)
        }

        val digits = (fonts + graphics).filter { NoteSkinNames.isImage(it) && isComboDigits(it) }
            .maxByOrNull { NoteSkinNames.sheet(it).let { (c, r) -> c * r } }
        val digitsIni = digits?.let { page ->
            val stem = NoteSkinNames.element(page)
            fonts.firstOrNull { it.endsWith(".ini", ignoreCase = true) && NoteSkinNames.element(it) == stem }
        }

        return Found(
            judgements = judgementSheet(graphics),
            comboDigits = digits?.let { sheetOf(it) },
            comboDigitsIni = digitsIni?.let(::full),
            comboLabel = graphics.firstOrNull {
                NoteSkinNames.isImage(it) && NoteSkinNames.element(it).let { e -> e == "combo label" || e.endsWith("combo [label]") || e.endsWith("/combo label") }
            }?.let(::full),
            fonts = inTheme.filter { it.substringAfterLast('.').lowercase() in setOf("ttf", "otf") }.map(::full),
            background = background(backgrounds),
            hitSound = sounds.firstOrNull { name(it).let { n -> HIT.containsMatchIn(n) && "mine" !in n } }?.let(::full),
            missSound = sounds.firstOrNull { MISS.containsMatchIn(name(it)) }?.let(::full),
        )
    }

    /** The sheet with six rows whose name says judgement: the dance one when a theme has several games'. */
    private fun judgementSheet(graphics: List<String>): Sheet? = graphics
        .filter { NoteSkinNames.isImage(it) }
        .filter { path ->
            val e = NoteSkinNames.element(path)
            ("judgment" in e || "judgement" in e || "judgelabels" in e) && "hold" !in e && "evaluation" !in e
        }
        .map { sheetOf(it) }
        .filter { it.rows == 6 && it.cols in 1..2 }
        .sortedWith(compareBy({ if ("/dance/" in it.path.lowercase()) 0 else 1 }, { if ("pump" in it.path.lowercase()) 1 else 0 }))
        .firstOrNull()

    private fun isComboDigits(path: String): Boolean {
        val e = path.lowercase().substringAfterLast('/')
        return "combo" in e && ("number" in e || "[numbers]" in e) && NoteSkinNames.sheet(path).let { (c, r) -> c * r >= 10 }
    }

    /** The gameplay screen's own background, else the theme's fallback one, else its menus'. */
    private fun background(images: List<String>): String? {
        fun pick(match: (String) -> Boolean) = images.filter { match(it.lowercase()) }.firstOrNull()
        return (pick { "screengameplay background" in it }
            ?: pick { "common fallback background" in it }
            ?: pick { "screenwithmenuelements background" in it || "_shared background" in it })
            ?.let(::full)
    }

    private fun sheetOf(path: String): Sheet {
        val (cols, rows) = NoteSkinNames.sheet(path)
        return Sheet(full(path), cols, rows)
    }

    private fun full(path: String) = if (themeDir.isEmpty()) path else "$themeDir/$path"
    private fun name(path: String) = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
    private fun isAudio(path: String) = path.substringAfterLast('.', "").lowercase() in AUDIO

    companion object {
        val AUDIO = setOf("ogg", "mp3", "wav")
        /** A hit's sound, by whole words: "assist tick", a clap, a hit (a mine's is not one). */
        private val HIT = Regex("""\b(assist tick|clap|hit)\b""")
        private val MISS = Regex("""\b(miss|combo stopped)\b""")

        /**
         * The characters of a bitmap font page, cell by cell, from its .ini's
         * `Line N=` rows (or `map X=N`); the digits 0 to 9 in order when it
         * says nothing, which is how StepMania's number fonts are laid out.
         */
        fun digitCells(ini: String?, cols: Int, rows: Int): Map<Char, Int> {
            val cells = HashMap<Char, Int>()
            if (ini != null) {
                for (match in Regex("""(?im)^\s*line\s+(\d+)\s*=\s*(.*)$""").findAll(ini)) {
                    val row = match.groupValues[1].toIntOrNull() ?: continue
                    match.groupValues[2].forEachIndexed { col, ch -> if (col < cols) cells.putIfAbsent(ch, row * cols + col) }
                }
                for (match in Regex("""(?im)^\s*map\s+(\S)\s*=\s*(\d+)\s*$""").findAll(ini)) {
                    cells[match.groupValues[1][0]] = match.groupValues[2].toInt()
                }
            }
            if (('0'..'9').any { it !in cells }) {
                for (d in 0..9) cells.putIfAbsent('0' + d, d)
            }
            return cells.filterValues { it in 0 until cols * rows }
        }
    }
}
