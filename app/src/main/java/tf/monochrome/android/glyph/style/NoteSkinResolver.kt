// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import tf.monochrome.android.glyph.asset.GlyphBeatDivision

/**
 * Finds a StepMania noteskin's art for each part of Trypt Mania's arrows, by
 * the names StepMania looks it up by, following the files that only point at
 * others:
 *
 * * `.redir`: one line, another element's or file's name;
 * * `.sprite`: an ini whose `Texture=` names the image;
 * * `.actor`, `.lua`: the image or element they load, read from their quoted
 *   strings. Read, never run: a skin whose Lua builds its art some other way
 *   resolves to the parts it does name, and the rest fall back.
 *
 * A part drawn as a 3D model (a MilkShape `.txt`, loaded by `Def.Model`)
 * resolves to the model itself, [Image.isModel], never to its texture: a
 * model's texture is a sheet of pieces wrapped round it, not a picture of the
 * note. The baker draws the model ([ModelSnapshot]).
 *
 * Every part is the Down arrow's (StepMania draws the other lanes by turning
 * it), then the skin's `Fallback` one. Paths are the archive's, so a redirect
 * into a sibling skin (`../common/...`) resolves too. Pure Kotlin.
 */
class NoteSkinResolver(
    files: Collection<String>,
    private val skinDir: String,
    private val read: (String) -> String?,
) {
    /**
     * An image a part resolved to: its path in the archive and its sprite
     * sheet; or, when [isModel], a MilkShape model to be drawn.
     */
    data class Image(val path: String, val cols: Int, val rows: Int, val doubleRes: Boolean, val isModel: Boolean = false)

    private val byDir: Map<String, List<String>> = files.map { it.replace('\\', '/').trim('/') }
        .groupBy { it.substringBeforeLast('/', "") }

    /** Each tap's art by beat division: the skin's own per division, else rows of one sheet, else one for all. */
    fun taps(): Map<GlyphBeatDivision, Pick> {
        val result = LinkedHashMap<GlyphBeatDivision, Pick>()
        val sheet = element("tap note")
        for ((index, division) in DIVISIONS.withIndex()) {
            val own = element("tap note ${division.assetSuffix}")
            result[division] = when {
                own != null -> Pick(own, frame = 0)
                // A sheet of eight or more rows is StepMania's quantized one: a row per division.
                sheet != null && sheet.rows >= DIVISIONS.size -> Pick(sheet, frame = index * sheet.cols)
                sheet != null -> Pick(sheet, frame = 0)
                else -> continue
            }
        }
        return result
    }

    /** The receptor at rest, and as it is pressed (its next frame, or its own "Go"). */
    fun receptor(): Pair<Pick, Pick>? {
        val base = element("receptor base") ?: element("receptor") ?: element("receptor waiting") ?: return null
        val pressed = element("receptor go") ?: element("receptor tap")
        val rest = Pick(base, frame = 0)
        val active = when {
            pressed != null -> Pick(pressed, frame = 0)
            base.cols * base.rows > 1 -> Pick(base, frame = 1)
            else -> rest
        }
        return rest to active
    }

    fun holdHead(roll: Boolean): Pick? {
        val kind = if (roll) "roll" else "hold"
        return (element("$kind head inactive") ?: element("$kind head") ?: element("$kind head active"))
            ?.let { Pick(it, 0) }
            ?: if (roll) holdHead(roll = false) else null
    }

    fun holdBody(roll: Boolean, active: Boolean): Pick? {
        val kind = if (roll) "roll" else "hold"
        val state = if (active) "active" else "inactive"
        return (element("$kind body $state") ?: element("$kind body"))?.let { Pick(it, 0) }
            ?: if (roll) holdBody(roll = false, active = active) else null
    }

    /** The cap at the far end of a hold: StepMania's bottom cap, or its tail. */
    fun holdTail(roll: Boolean, active: Boolean): Pick? {
        val kind = if (roll) "roll" else "hold"
        val state = if (active) "active" else "inactive"
        return (element("$kind bottomcap $state") ?: element("$kind bottomcap") ?: element("$kind tail $state"))
            ?.let { Pick(it, 0) }
            ?: if (roll) holdTail(roll = false, active = active) else null
    }

    fun mine(): Pick? = element("tap mine")?.let { Pick(it, 0) }

    fun explosion(): Pick? =
        (element("tap explosion bright") ?: element("tap explosion dim") ?: element("hold explosion"))?.let { Pick(it, 0) }

    /** A frame of an image: [frame] counts across, then down. */
    data class Pick(val image: Image, val frame: Int)

    // ── resolving ───────────────────────────────────────────────────────

    /**
     * The Down arrow's [part] ("tap note", "receptor"), or the skin's fallback
     * one; then the same in the skin it falls back on (its metrics.ini's
     * `FallbackNoteSkin`, a sibling folder), as StepMania looks a part up. A
     * variant of a skin often holds only its own arrows.
     */
    fun element(part: String): Image? {
        for (dir in chain) {
            resolve(dir, "down $part", depth = 0)?.let { return it }
            resolve(dir, "fallback $part", depth = 0)?.let { return it }
        }
        return null
    }

    /** The skin's folder, then the ones it falls back on, a few deep at most, none twice. */
    private val chain: List<String> by lazy {
        val dirs = ArrayList<String>()
        var dir: String? = skinDir
        while (dir != null && dir !in dirs && dirs.size < MAX_FALLBACKS) {
            dirs += dir
            dir = fallbackOf(dir)
        }
        dirs
    }

    private fun fallbackOf(dir: String): String? {
        val metrics = byDir[dir].orEmpty().firstOrNull { it.substringAfterLast('/').equals("metrics.ini", ignoreCase = true) }
            ?: return null
        val name = read(metrics)?.let { FALLBACK.find(it)?.groupValues?.get(1) }
            ?.trim()?.takeIf { it.isNotEmpty() && '/' !in it && it != ".." } ?: return null
        val parent = dir.substringBeforeLast('/', "")
        val sibling = if (parent.isEmpty()) name else "$parent/$name"
        // A folder of that name beside this one, matched without case, as on Windows where most were made.
        return byDir.keys.firstOrNull { it.equals(sibling, ignoreCase = true) }
    }

    private fun resolve(dir: String, name: String, depth: Int): Image? {
        if (depth > MAX_DEPTH) return null
        val wanted = name.lowercase().trim()
        val candidates = byDir[dir].orEmpty().filter { NoteSkinNames.element(it) == wanted }
        // An image itself first, then a model, then what points at one.
        candidates.firstOrNull { NoteSkinNames.isImage(it) }?.let { return image(it) }
        candidates.firstOrNull { isModel(it) }?.let { return Image(it, 1, 1, doubleRes = false, isModel = true) }
        for (file in candidates.sortedBy { POINTER_ORDER.indexOf(extensionOf(it)).let { i -> if (i < 0) 99 else i } }) {
            val text = read(file) ?: continue
            for (target in targetsIn(file, text)) {
                follow(dir, target, depth)?.let { return it }
            }
        }
        return null
    }

    /** [target] as a file name, a path, or another element of the same arrow. */
    private fun follow(dir: String, target: String, depth: Int): Image? {
        val cleaned = target.replace('\\', '/').trim().trim('"', '\'')
        if (cleaned.isEmpty()) return null
        val (targetDir, leaf) = join(dir, cleaned)
        // A file named outright.
        byDir[targetDir].orEmpty().firstOrNull { it.substringAfterLast('/').equals(leaf, ignoreCase = true) }?.let { file ->
            return when {
                NoteSkinNames.isImage(file) -> image(file)
                isModel(file) -> Image(file, 1, 1, doubleRes = false, isModel = true)
                else -> resolve(targetDir, NoteSkinNames.element(file), depth + 1)
            }
        }
        val element = NoteSkinNames.element(leaf)
        resolve(targetDir, element, depth + 1)?.let { return it }
        // "Tap Note" from a Lua's Var "Button" ... "Tap Note": the Down arrow's.
        if (DIRECTIONS.none { element.startsWith("$it ") }) resolve(targetDir, "down $element", depth + 1)?.let { return it }
        return null
    }

    /** What a pointer file names, most likely first. */
    private fun targetsIn(file: String, text: String): List<String> = when (extensionOf(file)) {
        "redir" -> listOfNotNull(text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() })
        "sprite" -> Regex("""(?im)^\s*texture\s*=\s*(.+?)\s*$""").findAll(text).map { it.groupValues[1] }.toList()
        else -> {
            // .actor, .lua, .xml: a File= first, then every quoted string, images before names.
            val files = Regex("""(?i)file\s*=\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            val quoted = Regex(""""([^"\n]{1,120})"|'([^'\n]{1,120})'""").findAll(text)
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
            (files + quoted.filter { NoteSkinNames.isImage(it) } + quoted.filterNot { NoteSkinNames.isImage(it) }).distinct()
        }
    }

    /**
     * The image a model's material names ([texture], as written in the model,
     * often with Windows' `\\`), from the model at [model]: beside it or under
     * it, matched without case. An `.ini` texture is StepMania's animated
     * one; its first frame is taken.
     */
    fun modelTexture(model: String, texture: String): String? {
        val skin = model.substringBeforeLast('/', "")
        var base = skin
        var target = texture
        // The texture, or an animated texture's .ini and then its first frame.
        repeat(2) {
            val file = find(base, target, skin) ?: return null
            if (NoteSkinNames.isImage(file)) return file
            if (extensionOf(file) != "ini") return null
            target = read(file)?.let { ANIMATED_FRAME.find(it)?.groupValues?.get(1) } ?: return null
            base = file.substringBeforeLast('/', "")
        }
        return null
    }

    /** [target] from [base], or a file of its name anywhere under [skin] (a model naming a folder since flattened). */
    private fun find(base: String, target: String, skin: String): String? {
        val (dir, leaf) = join(base, target.replace('\\', '/').trim())
        if (leaf.isEmpty()) return null
        fun named(file: String) = file.substringAfterLast('/').equals(leaf, ignoreCase = true)
        return byDir[dir].orEmpty().firstOrNull(::named)
            ?: byDir.entries.firstOrNull { (folder, files) ->
                (skin.isEmpty() || folder == skin || folder.startsWith("$skin/")) && files.any(::named)
            }?.value?.firstOrNull(::named)
    }

    /** A MilkShape ASCII model, by its first line. */
    private fun isModel(path: String): Boolean =
        extensionOf(path) in MODEL_EXTENSIONS && read(path)?.trimStart()?.startsWith(MILKSHAPE_HEADER, ignoreCase = true) == true

    private fun image(path: String): Image {
        val (cols, rows) = NoteSkinNames.sheet(path)
        return Image(path, cols, rows, NoteSkinNames.isDoubleRes(path))
    }

    /** [target] from [dir], `..` and all, as (folder, file name). Never above the archive's top. */
    private fun join(dir: String, target: String): Pair<String, String> {
        val parts = (if (dir.isEmpty()) emptyList() else dir.split('/')).toMutableList()
        val segments = target.split('/').filter { it.isNotEmpty() && it != "." }
        for (segment in segments.dropLast(1)) {
            if (segment == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) } else parts += segment
        }
        return parts.joinToString("/") to (segments.lastOrNull() ?: "")
    }

    private fun extensionOf(path: String) = path.substringAfterLast('.', "").lowercase()

    companion object {
        /** StepMania's quantized rows, top to bottom; Trypt Mania's divisions in the same order. */
        val DIVISIONS: List<GlyphBeatDivision> = GlyphBeatDivision.entries.toList()
        private val DIRECTIONS = listOf("down", "left", "up", "right", "upleft", "upright", "center", "fallback")
        private val POINTER_ORDER = listOf("redir", "sprite", "actor", "xml", "lua")
        private const val MAX_DEPTH = 6
        private val MODEL_EXTENSIONS = setOf("txt", "model")
        private val ANIMATED_FRAME = Regex("""(?im)^\s*Frame0*\d*\s*=\s*(.+?)\s*$""")
        private const val MILKSHAPE_HEADER = "// MilkShape 3D ASCII"
        private const val MAX_FALLBACKS = 4
        private val FALLBACK = Regex("""(?im)^\s*FallbackNoteSkin\s*=\s*(.+?)\s*$""")
    }
}
