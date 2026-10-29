// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

/**
 * What a StepMania download holds, read from its file names alone: the
 * noteskins, themes and announcers in it, wherever they sit in the archive
 * (a skin at the top, `NoteSkins/dance/<skin>`, a megapack of several, a
 * theme beside its own noteskin).
 *
 * Nothing in the archive is run. StepMania 5's themes and many of its
 * noteskins are Lua programs; what Trypt Mania takes from them is their art
 * and their sounds, found by the names StepMania itself looks them up by.
 * Pure Kotlin, so [StepManiaStyleScanTest] holds it without a phone.
 */
object StepManiaStyleScan {

    enum class Kind { NOTESKIN, THEME, ANNOUNCER }

    /** One thing found: its kind, its name (the folder's), and its folder, as a path in the archive. */
    data class Found(val kind: Kind, val name: String, val dir: String)

    /**
     * Everything in [paths] (files, `/`-separated, relative to the archive's
     * top), each folder once. A folder inside a theme is the theme's, not a
     * noteskin or an announcer of its own, except under the theme's own
     * `NoteSkins` folder, which StepMania installs as noteskins.
     */
    fun scan(paths: Collection<String>): List<Found> {
        val files = paths.map { it.replace('\\', '/').trim('/') }.filter { it.isNotEmpty() }
        // The archive's top counts too: an announcer is often zipped as its folders alone.
        val dirs = files.mapNotNull { parentOf(it) }.toSet() + files.flatMap { ancestors(it) }.toSet() + ""
        val filesIn: Map<String, List<String>> = files.groupBy { parentOf(it) ?: "" }
        val childDirs: Map<String, List<String>> = dirs.filter { it.isNotEmpty() }.groupBy { parentOf(it) ?: "" }

        val themes = dirs.filter { dir -> isTheme(childDirs[dir].orEmpty(), filesIn[dir].orEmpty()) }.toSet()
        val announcers = dirs.filter { dir -> isAnnouncer(childDirs[dir].orEmpty()) }.toSet()
        val noteskins = dirs.filter { dir ->
            filesIn[dir].orEmpty().any { isTapNote(nameOf(it)) } &&
                // A folder of a theme is the theme's, unless the theme ships it as a noteskin.
                themes.none { theme -> inside(dir, theme) && dir != theme && !"/$dir/".contains("/noteskins/", ignoreCase = true) }
        }.toSet()

        return (themes.map { Found(Kind.THEME, nameOf(it).ifEmpty { "Theme" }, it) } +
            announcers.filter { a -> themes.none { inside(a, it) } }
                .map { Found(Kind.ANNOUNCER, nameOf(it).ifEmpty { "Announcer" }, it) } +
            noteskins.map { Found(Kind.NOTESKIN, nameOf(it).ifEmpty { "Noteskin" }, it) })
            .sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }

    /** A theme: a folder holding StepMania's `Graphics` folder and one of its others, or its metrics. */
    private fun isTheme(children: List<String>, files: List<String>): Boolean {
        val names = children.map { nameOf(it).lowercase() }.toSet()
        val hasMetrics = files.any { nameOf(it).equals("metrics.ini", ignoreCase = true) }
        return "graphics" in names && (hasMetrics || names.any { it in THEME_FOLDERS })
    }

    /** An announcer: a folder of the folders StepMania's announcer looks for. */
    private fun isAnnouncer(children: List<String>): Boolean =
        children.count { nameOf(it).lowercase().let { n -> n in AnnouncerEvent.FOLDERS } } >= 2

    /** A noteskin's tap note, by any of the names StepMania looks it up by. */
    fun isTapNote(fileName: String): Boolean {
        val base = NoteSkinNames.element(fileName)
        return base.endsWith("tap note") || TAP_NOTE_QUANT.matches(base)
    }

    /** Whether [dir] is [parent] or inside it; everything is inside the archive's top. */
    private fun inside(dir: String, parent: String) = parent.isEmpty() || dir == parent || dir.startsWith("$parent/")

    fun nameOf(path: String): String = path.substringAfterLast('/')
    fun parentOf(path: String): String? = path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it != path }
    private fun ancestors(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.subList(0, it).joinToString("/") }
    }

    private val THEME_FOLDERS = setOf("bganimations", "fonts", "sounds", "languages", "scripts")
    private val TAP_NOTE_QUANT = Regex(".*tap note (4th|8th|12th|16th|24th|32nd|48th|64th|192nd)")
}

/**
 * StepMania's file names, as its noteskin loader reads them: an element's
 * name, then optionally a sprite sheet's size ("4x4", "1x8"), a resolution
 * note ("(doubleres)", "(res 128x128)") and the extension. A leading `_`
 * marks a file only reached through another (a `.lua`, a `.redir`).
 */
object NoteSkinNames {
    private val SHEET = Regex("""\s(\d+)x(\d+)$""")
    private val RES = Regex("""\s*\((doubleres|res \d+x\d+)\)""", RegexOption.IGNORE_CASE)

    /** [fileName]'s element, lower case: "Down Tap Note 4x4 (doubleres).png" is "down tap note". */
    fun element(fileName: String): String {
        var base = fileName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        base = RES.replace(base, "").trim()
        base = SHEET.replace(base, "").trim()
        return base.trimStart('_').trim()
    }

    /** The sprite sheet's columns and rows, 1×1 when the name says none. */
    fun sheet(fileName: String): Pair<Int, Int> {
        var base = fileName.substringAfterLast('/').substringBeforeLast('.')
        base = RES.replace(base, "").trim()
        val match = SHEET.find(base) ?: return 1 to 1
        val cols = match.groupValues[1].toInt().coerceIn(1, 64)
        val rows = match.groupValues[2].toInt().coerceIn(1, 64)
        return cols to rows
    }

    /** Whether the art is drawn at twice StepMania's size (its "doubleres"), so it is not pixel art. */
    fun isDoubleRes(fileName: String): Boolean = fileName.contains("(doubleres)", ignoreCase = true)

    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")
    fun isImage(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
}

/**
 * The moments an announcer speaks at, and StepMania's folder for each. Only
 * the ones a run of Trypt Mania has: ready, go, the combo, cleared, failed,
 * and the grade on the results.
 */
enum class AnnouncerEvent(val folders: List<String>) {
    READY(listOf("gameplay ready")),
    GO(listOf("gameplay here we go normal", "gameplay here we go extra", "gameplay here we go final")),
    COMBO(listOf("gameplay comment hot", "gameplay 100 combo", "gameplay 200 combo", "gameplay 300 combo")),
    COMBO_STOPPED(listOf("gameplay combo stopped")),
    DANGER(listOf("gameplay comment danger")),
    CLEARED(listOf("gameplay cleared")),
    FAILED(listOf("gameplay failed")),
    GRADE_AAAA(listOf("evaluation aaaa")),
    GRADE_AAA(listOf("evaluation aaa")),
    GRADE_AA(listOf("evaluation aa")),
    GRADE_A(listOf("evaluation a")),
    GRADE_B(listOf("evaluation b")),
    GRADE_C(listOf("evaluation c")),
    GRADE_D(listOf("evaluation d")),
    GRADE_E(listOf("evaluation e", "evaluation failed")),
    ;

    /** Where a baked announcer keeps this moment's clips. */
    val key: String get() = name.lowercase()

    companion object {
        /** Every folder name an announcer may have that Trypt Mania speaks from. */
        val FOLDERS: Set<String> = entries.flatMap { it.folders }.toSet() + setOf(
            "gameplay comment good", "select music intro", "title menu game name",
        )

        fun forFolder(folder: String): AnnouncerEvent? {
            val name = folder.lowercase().trim()
            return entries.firstOrNull { name in it.folders }
        }
    }
}
