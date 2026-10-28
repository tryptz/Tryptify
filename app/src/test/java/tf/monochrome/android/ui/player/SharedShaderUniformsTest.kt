package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The glass's shaders are compiled once for the whole process and shared by
 * every pane, lens and lyric layer, and no effect made from one inherits the
 * last pane's values.
 *
 * Each used to be compiled per call site: a full SkSL parse on the main thread
 * for every pane that came into composition, 10 to 20 on an ordinary page. One
 * shader per source is safe only because an effect copies the uniforms as it
 * is made. So every effect made from a shared shader must set every uniform
 * the shader declares, on every way through to it, and bind every child
 * shader (`uArt`); one it left out would show another pane's value, on some
 * panes, some of the time. A fresh shader started at zero, which is how a
 * missing uniform could hide before.
 *
 * This reads each shader's SkSL for its `uniform` declarations and the Kotlin
 * that makes each effect, follows the helpers it calls (`bindBackdropArt`,
 * `setNoRayLight`, `setGodRayUniforms`) through their early returns and both
 * sides of each `if`, and checks nothing is left out. It also checks that each
 * source is compiled exactly once, and that no shared shader is handed to a
 * Paint or a brush: those are read later on the render thread, after another
 * pane may have changed it.
 */
class SharedShaderUniformsTest {

    /** One shared shader and every function that makes an effect from it. */
    private class Shared(
        /** The SkSL constant. */
        val source: String,
        /** The top-level `by lazy` that holds the one compiled shader. */
        val holder: String,
        /** The names other code reaches it by; a file using one is read for effects. */
        val doors: List<String>,
        /** Every function that makes an effect from it, as "path under ui/#function". */
        val sites: List<String>,
        /** The fewest uniforms its SkSL declares, `content` included, so a parse that finds none fails. */
        val atLeast: Int,
    )

    private val shared = listOf(
        Shared(
            source = "LIQUID_GLASS_SRC",
            holder = "liquidGlassShader",
            doors = listOf("liquidGlassShader"),
            sites = listOf("player/LiquidGlass.kt#liquidGlassEffect"),
            atLeast = 38,
        ),
        Shared(
            source = "LIVE_LENS_SRC",
            holder = "liveLensShader",
            doors = listOf("liveLensShader"),
            sites = listOf("player/LiveGlassLens.kt#liveLensEffect"),
            atLeast = 9,
        ),
        Shared(
            source = "GOD_RAYS_SRC",
            holder = "godRaysShader",
            doors = listOf("godRaysShader", "rememberGodRaysShader"),
            sites = listOf(
                "player/LyricsGodRays.kt#godRaysModifier",
                "player/LyricsBackdropFx.kt#LyricBackdropFx",
                "glyph/GlyphHitRays.kt#HitRaysLayer",
            ),
            atLeast = 24,
        ),
        Shared(
            source = "CURVATURE_SHADER",
            holder = "curvatureShader",
            doors = listOf("curvatureShader"),
            sites = listOf("player/LyricsCurvature.kt#curved"),
            atLeast = 6,
        ),
        Shared(
            source = "FXAA_SRC",
            holder = "fxaaShader",
            doors = listOf("fxaaShader"),
            sites = listOf("player/LiquidGlass.kt#fxaaModifier"),
            atLeast = 2,
        ),
        Shared(
            source = "DITHER_SRC",
            holder = "ditherShader",
            doors = listOf("ditherShader"),
            sites = listOf("player/LiquidGlass.kt#dithered"),
            atLeast = 1,
        ),
    )

    private val ui = File("src/main/java/tf/monochrome/android/ui")

    private val sources: List<Source> by lazy {
        ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { Source(it.relativeTo(ui).invariantSeparatorsPath, it.readText()) }
            .toList()
            .also { assertTrue("no UI sources found", it.isNotEmpty()) }
    }

    private fun source(path: String): Source =
        sources.firstOrNull { it.path == path } ?: throw AssertionError("$path is not under ui/")

    @Test
    fun `every shared shader is compiled once, for the whole process`() {
        for (s in shared) {
            val compiled = sources.flatMap { src ->
                Regex("""\bRuntimeShader\(\s*${s.source}\s*\)""").findAll(src.code).map { src to it.range.first }.toList()
            }
            assertEquals(
                "${s.source} is compiled at ${compiled.map { (src, at) -> src.where(at) }}",
                1,
                compiled.size,
            )
            val (src, at) = compiled.single()
            val holder = Regex("""(?m)^(?:private |internal )?val ${s.holder}: RuntimeShader\? by lazy \{""")
                .find(src.shape)
            assertTrue("${s.holder} is not a top-level lazy in ${src.path}", holder != null)
            val open = holder!!.range.last
            assertTrue("${s.source} is compiled outside ${s.holder}", at in open..src.matching(open))
        }
    }

    @Test
    fun `every effect made from a shared shader sets every uniform it declares first`() {
        val analyser = Analyser(sources)
        val problems = mutableListOf<String>()
        var checked = 0
        for (s in shared) {
            val uniforms = uniformsOf(s.source)
            assertTrue(
                "${s.source}: read ${uniforms.size} uniforms, expected at least ${s.atLeast}; the SkSL is not being read",
                uniforms.size >= s.atLeast,
            )
            for (site in s.sites) {
                val (path, function) = site.split('#')
                val src = source(path)
                val calls = effectCalls(src).filter { src.topLevelFunctionAt(it) == function }
                if (calls.isEmpty()) problems += "$site makes no effect: the check is not reading it"
                for (at in calls) {
                    checked++
                    val input = Regex("""^createRuntimeShaderEffect\(\s*[^,]+,\s*"(\w+)"""")
                        .find(src.code.substring(at))?.groupValues?.get(1)
                    if (input == null || uniforms[input] != "shader") {
                        problems += "${src.where(at)}: its input is not one of ${s.source}'s child shaders"
                        continue
                    }
                    val set = analyser.setBefore(src, at)
                    if (set == null) {
                        problems += "${src.where(at)}: no way through reaches the effect"
                        continue
                    }
                    val missing = uniforms.keys - input - set
                    if (missing.isNotEmpty()) {
                        problems += "${src.where(at)}: a ${s.source} effect is made without setting " +
                            "${missing.sorted()} on every path, so it would take another pane's"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("checked $checked effects; the check is not reading the sources", checked >= 8)
    }

    @Test
    fun `no effect is made from a shared shader where this test does not look`() {
        val sites = shared.flatMap { it.sites }.toSet()
        val doors = shared.flatMap { it.doors }.map { Regex("""\b$it\b""") }
        val problems = mutableListOf<String>()
        for (src in sources) {
            if (doors.none { it.containsMatchIn(src.shape) }) continue
            for (at in effectCalls(src)) {
                val site = "${src.path}#${src.topLevelFunctionAt(at)}"
                if (site !in sites) problems += "${src.where(at)} makes an effect in $site, which is not in `shared`"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no shared shader is drawn as a paint or a brush`() {
        val doors = shared.flatMap { it.doors }.map { Regex("""\b$it\b""") }
        val paint = listOf(
            Regex("""\bShaderBrush\("""),
            Regex("""\.setShader\("""),
            Regex("""\.shader\s*=(?!=)"""),
            Regex("""(?m)^\s*shader\s*=(?!=)"""),
        )
        val problems = mutableListOf<String>()
        for (src in sources) {
            if (doors.none { it.containsMatchIn(src.shape) }) continue
            for (p in paint) {
                p.findAll(src.shape).forEach { problems += "${src.where(it.range.first)} sets a paint shader beside a shared one" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the check sees a uniform that is set on only one path`() {
        val src = Source(
            "Synthetic.kt",
            """
            private fun RuntimeShader.early(stop: Boolean) {
                setFloatUniform("uA", 1f)
                if (stop) {
                    return
                }
                setFloatUniform("uB", 1f)
            }

            private fun site(shader: RuntimeShader, x: Boolean) {
                shader.early(x)
                if (x) {
                    shader.setFloatUniform("uC", 1f)
                } else {
                    shader.setFloatUniform("uC", 0f)
                    shader.setFloatUniform("uD", 0f)
                }
                if (x) shader.setFloatUniform("uE", 1f)
                listOf(1).forEach { shader.setFloatUniform("uF", 1f) }
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
            }
            """.trimIndent(),
        )
        val at = effectCalls(src).single()
        assertEquals(setOf("uA", "uC"), Analyser(listOf(src)).setBefore(src, at))
    }

    /** Where each `createRuntimeShaderEffect(` is called in [src]. */
    private fun effectCalls(src: Source): List<Int> =
        Regex("""\bcreateRuntimeShaderEffect\s*\(""").findAll(src.shape).map { it.range.first }.toList()

    /** Every `uniform` the SkSL constant [name] declares, by name, with its type; templates expanded. */
    private fun uniformsOf(name: String): Map<String, String> {
        val sksl = skslOf(name).replace(Regex("//[^\n]*"), "")
        return Regex("""(?:layout\s*\([^)]*\)\s*)?\buniform\s+(\w+)\s+(\w+)""")
            .findAll(sksl)
            .associate { it.groupValues[2] to it.groupValues[1] }
    }

    private fun skslOf(name: String): String {
        for (src in sources) {
            val m = Regex("""\bconst\s+val\s+$name\s*=\s*"{3}""").find(src.shape) ?: continue
            val start = m.range.last + 1
            val end = src.shape.indexOf("\"\"\"", start)
            return src.code.substring(start, end).replace(Regex("""\$\{?([A-Za-z_]\w*)\}?""")) { t ->
                runCatching { skslOf(t.groupValues[1]) }.getOrDefault(t.value)
            }
        }
        throw AssertionError("no const val $name under ui/")
    }

    /**
     * A Kotlin file as two views of the same length and lines: [code] with the
     * comments blanked, and [shape] with string contents blanked as well, so
     * brackets and keywords are read only where they are code.
     */
    private class Source(val path: String, text: String) {
        val code: String
        val shape: String

        init {
            val c = StringBuilder(text.length)
            val s = StringBuilder(text.length)
            fun keep(ch: Char) { c.append(ch); s.append(ch) }
            fun blank(ch: Char) { val b = if (ch == '\n') '\n' else ' '; c.append(b); s.append(b) }
            fun quoted(ch: Char) { c.append(ch); s.append(if (ch == '\n') '\n' else ' ') }
            val n = text.length
            var i = 0
            while (i < n) {
                when {
                    text.startsWith("//", i) -> while (i < n && text[i] != '\n') blank(text[i++])
                    text.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < n) {
                            if (text.startsWith("/*", i)) {
                                depth++; blank(text[i++]); blank(text[i++])
                            } else if (text.startsWith("*/", i)) {
                                depth--; blank(text[i++]); blank(text[i++])
                                if (depth == 0) break
                            } else {
                                blank(text[i++])
                            }
                        }
                    }
                    text.startsWith("\"\"\"", i) -> {
                        repeat(3) { keep(text[i++]) }
                        while (i < n && !text.startsWith("\"\"\"", i)) quoted(text[i++])
                        while (i < n && text[i] == '"') keep(text[i++])
                    }
                    text[i] == '"' -> {
                        keep(text[i++])
                        var template = 0
                        while (i < n && (template > 0 || text[i] != '"')) {
                            when {
                                text[i] == '\\' -> { quoted(text[i++]); if (i < n) quoted(text[i++]) }
                                text.startsWith("\${", i) -> { template++; quoted(text[i++]); quoted(text[i++]) }
                                else -> {
                                    if (template > 0 && text[i] == '{') template++
                                    if (template > 0 && text[i] == '}') template--
                                    quoted(text[i++])
                                }
                            }
                        }
                        if (i < n) keep(text[i++])
                    }
                    text[i] == '\'' -> {
                        keep(text[i++])
                        if (i < n && text[i] == '\\') {
                            quoted(text[i++])
                            if (i < n) quoted(text[i++])
                        }
                        while (i < n && text[i] != '\'') quoted(text[i++])
                        if (i < n) keep(text[i++])
                    }
                    else -> keep(text[i++])
                }
            }
            code = c.toString()
            shape = s.toString()
        }

        fun where(at: Int): String = "$path:${code.substring(0, at).count { it == '\n' } + 1}"

        /** The bracket closing the one at [open]. */
        fun matching(open: Int): Int {
            var depth = 0
            for (i in open until shape.length) {
                when (shape[i]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> if (--depth == 0) return i
                }
            }
            return shape.length
        }

        /** The `{` of the innermost block around [at], or -1. */
        fun enclosingBlock(at: Int): Int {
            var depth = 0
            for (i in at - 1 downTo 0) {
                when (shape[i]) {
                    '}' -> depth++
                    '{' -> if (depth == 0) return i else depth--
                }
            }
            return -1
        }

        fun topLevelFunctionAt(at: Int): String? =
            TOP_LEVEL_FUN.findAll(shape).lastOrNull { it.range.first < at }?.groupValues?.get(1)

        fun keywordAt(i: Int, word: String): Boolean =
            shape.startsWith(word, i) &&
                (i == 0 || !(shape[i - 1].isLetterOrDigit() || shape[i - 1] == '_' || shape[i - 1] == '.')) &&
                (i + word.length >= shape.length || !(shape[i + word.length].isLetterOrDigit() || shape[i + word.length] == '_'))

        fun skipSpace(from: Int): Int {
            var i = from
            while (i < shape.length && shape[i].isWhitespace()) i++
            return i
        }

        /** Where the statement starting at [from] ends: its line's end, past any brackets it opens. */
        fun statementEnd(from: Int, to: Int): Int {
            var i = from
            while (i < to) {
                when (shape[i]) {
                    '(', '[', '{' -> i = matching(i) + 1
                    '\n', ';', '}' -> return i
                    else -> i++
                }
            }
            return minOf(i, to)
        }

        fun leavesIn(from: Int, to: Int): Boolean = Regex("""\b(?:return|throw)\b""").containsMatchIn(shape.substring(from, to))

        /** Whether the `return` at [i] is the right side of an elvis, which leaves only sometimes. */
        fun afterElvis(i: Int): Boolean = shape.substring(0, i).trimEnd().endsWith("?:")

        /** The code in [from, to) with whatever sits inside braces blanked: a lambda handed to a call may never run. */
        fun outsideBlocks(from: Int, to: Int): String {
            val out = StringBuilder(to - from)
            var depth = 0
            for (i in from until to) {
                when (shape[i]) {
                    '{' -> { depth++; out.append(' ') }
                    '}' -> { depth = maxOf(0, depth - 1); out.append(' ') }
                    else -> out.append(if (depth == 0) code[i] else ' ')
                }
            }
            return out.toString()
        }

        companion object {
            val TOP_LEVEL_FUN = Regex(
                """(?m)^(?:(?:private|internal|public|inline|suspend)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""",
            )
        }
    }

    /** What a run of code sets on every way through it, and on each way out of it early. */
    private class Flow(val through: Set<String>?, val exits: List<Set<String>>)

    /** An `if` chain: its branches, whether it ends in a plain `else`, and where it ends. */
    private class IfChain(val branches: List<IntRange>, val exhaustive: Boolean, val end: Int)

    /**
     * Follows uniform writes through Kotlin, conservatively: a uniform counts
     * only where it is set on every way through. A write inside a lambda, a
     * loop or a `when` does not count, nor one on one side of an `if`.
     */
    private class Analyser(all: List<Source>) {
        /** Every function that takes a RuntimeShader, by name: the helpers a site may call. */
        private val helpers = HashMap<String, MutableList<Pair<Source, IntRange>>>()
        private val known = HashMap<String, Set<String>>()
        private val visiting = HashSet<String>()

        init {
            val anyFun = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
            for (src in all) {
                for (m in anyFun.findAll(src.shape)) {
                    val close = src.matching(m.range.last)
                    val brace = src.shape.indexOf('{', close)
                    val equals = src.shape.indexOf('=', close)
                    if (brace < 0 || (equals in 0 until brace)) continue
                    if ("RuntimeShader" !in src.shape.substring(m.range.first, brace)) continue
                    helpers.getOrPut(m.groupValues[1]) { mutableListOf() } += src to (brace + 1 until src.matching(brace))
                }
            }
        }

        /** The uniforms set on every way from the start of its block to the effect at [at]; null if none gets there. */
        fun setBefore(src: Source, at: Int): Set<String>? {
            val block = src.enclosingBlock(at)
            return walk(src, block + 1, at, emptySet()).through
        }

        private fun walk(src: Source, from: Int, to: Int, entry: Set<String>): Flow {
            val sh = src.shape
            val set = entry.toMutableSet()
            val exits = mutableListOf<Set<String>>()
            var seg = from
            fun flush(end: Int) {
                if (end > seg) set += setsIn(src, seg, end)
                seg = maxOf(seg, end)
            }
            var i = from
            while (i < to) {
                when {
                    sh[i] == '(' || sh[i] == '[' -> {
                        val close = src.matching(i)
                        if (close >= to) break
                        i = close + 1
                    }
                    sh[i] == '{' -> {
                        // A lambda, a loop, a `when`: what it sets may not run,
                        // but a return inside it is a way out.
                        flush(i)
                        val close = minOf(src.matching(i), to)
                        if (src.leavesIn(i, close)) exits += set.toSet()
                        i = close + 1
                        seg = i
                    }
                    src.keywordAt(i, "if") -> {
                        flush(i)
                        val chain = ifChain(src, i)
                        // The effect sits in this if's condition or in a branch
                        // without braces: count nothing from it.
                        if (chain.end > to) {
                            seg = to
                            break
                        }
                        val flows = chain.branches.map { walk(src, it.first, it.last + 1, set) }
                        flows.forEach { exits += it.exits }
                        if (chain.exhaustive) {
                            val through = flows.mapNotNull { it.through }
                            if (through.isEmpty()) return Flow(null, exits)
                            set.clear()
                            set += through.reduce { a, b -> a intersect b }
                        }
                        i = chain.end
                        seg = i
                    }
                    src.keywordAt(i, "return") || src.keywordAt(i, "throw") -> {
                        val end = src.statementEnd(i, sh.length)
                        if (end >= to) break
                        flush(end)
                        exits += set.toSet()
                        if (!src.afterElvis(i)) return Flow(null, exits)
                        i = end
                    }
                    else -> i++
                }
            }
            flush(to)
            return Flow(set, exits)
        }

        private fun ifChain(src: Source, at: Int): IfChain {
            val branches = mutableListOf<IntRange>()
            var i = at
            while (true) {
                val condition = src.shape.indexOf('(', i)
                val (body, end) = branch(src, src.matching(condition) + 1)
                branches += body
                val next = src.skipSpace(end)
                if (!src.keywordAt(next, "else")) return IfChain(branches, exhaustive = false, end = end)
                val after = src.skipSpace(next + 4)
                if (src.keywordAt(after, "if")) {
                    i = after
                    continue
                }
                val (last, lastEnd) = branch(src, after)
                branches += last
                return IfChain(branches, exhaustive = true, end = lastEnd)
            }
        }

        /** One branch: a braced block, or the rest of its statement. */
        private fun branch(src: Source, from: Int): Pair<IntRange, Int> {
            val j = src.skipSpace(from)
            if (j < src.shape.length && src.shape[j] == '{') {
                val close = src.matching(j)
                return (j + 1 until close) to close + 1
            }
            val end = src.statementEnd(j, src.shape.length)
            return (j until end) to end
        }

        private fun setsIn(src: Source, from: Int, to: Int): Set<String> {
            val text = src.outsideBlocks(from, to)
            val names = SET_CALL.findAll(text).map { it.groupValues[1] }.toMutableSet()
            for (call in CALL.findAll(text)) {
                val name = call.groupValues[1]
                if (name in helpers) names += helperSets(name)
            }
            return names
        }

        /** What the helper [name] sets on every way out of it. */
        private fun helperSets(name: String): Set<String> {
            known[name]?.let { return it }
            if (!visiting.add(name)) return emptySet()
            val result = helpers.getValue(name).map { (src, body) ->
                val flow = walk(src, body.first, body.last + 1, emptySet())
                (flow.exits + listOfNotNull(flow.through)).reduceOrNull { a, b -> a intersect b } ?: emptySet()
            }.reduce { a, b -> a intersect b }
            visiting -= name
            known[name] = result
            return result
        }

        companion object {
            val SET_CALL = Regex(
                """\b(?:setFloatUniform|setIntUniform|setColorUniform|setInputShader|setInputBuffer)\s*\(\s*"(\w+)"""",
            )
            val CALL = Regex("""\b(\w+)\s*\(""")
        }
    }
}
