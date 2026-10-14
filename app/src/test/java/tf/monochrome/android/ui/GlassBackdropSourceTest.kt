// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No glass is handed, as its backdrop, the haze source it is drawn inside.
 *
 * A haze source records its content into a layer, and a pane frosting that
 * source draws the layer. A pane inside the source is part of the layer it
 * draws, so the render thread walks the layer into itself until the stack is
 * gone: a native crash, SIGSEGV with "stack pointer is not in a rw map", the
 * moment the page is shown. Player options shipped exactly that once, its
 * steppers given `LocalGlassBackdrop provides pageHaze` inside the column that
 * was `hazeSource(pageHaze)`. Nothing on the JVM draws through the hardware
 * renderer, so no rendered test catches it; this reads the source instead.
 *
 * For every `hazeSource(x)` on a composable's modifier, the content lambda of
 * that composable must not provide `x` as the glass backdrop. Siblings of the
 * source may, which is the pattern every page uses.
 */
class GlassBackdropSourceTest {

    @Test
    fun noGlassFrostsTheSourceItIsInside() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offences = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> offencesIn(file.readText()).map { "${file.name}: $it" } }
            .toList()
        assertTrue("Glass frosting its own haze source:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun theCheckFindsTheShapeThatCrashed() {
        val crashed = """
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(pageHaze)
                    .verticalScroll(rememberScrollState()),
            ) {
                CompositionLocalProvider(LocalGlassBackdrop provides pageHaze) { Controls() }
            }
            CompositionLocalProvider(LocalGlassBackdrop provides pageHaze) { Start() }
        """.trimIndent()
        assertTrue(offencesIn(crashed).isNotEmpty())
        val sibling = """
            Box(Modifier.fillMaxSize().hazeSource(haze)) { Page() }
            CompositionLocalProvider(LocalGlassBackdrop provides haze) { Panel() }
        """.trimIndent()
        assertTrue(offencesIn(sibling).isEmpty())
    }

    /** The sources in [text] whose own content provides them as the backdrop. */
    private fun offencesIn(text: String): List<String> {
        val found = ArrayList<String>()
        for (match in SOURCE.findAll(text)) {
            val name = match.groupValues[1]
            val open = enclosingCallOpen(text, match.range.first) ?: continue
            val close = matching(text, open, '(', ')') ?: continue
            var brace = close + 1
            while (brace < text.length && text[brace].isWhitespace()) brace++
            if (brace >= text.length || text[brace] != '{') continue
            val end = matching(text, brace, '{', '}') ?: continue
            val content = text.substring(brace, end)
            if (Regex("""LocalGlassBackdrop\s+provides\s+$name\b""").containsMatchIn(content)) {
                found += "hazeSource($name) at ${text.substring(0, match.range.first).count { it == '\n' } + 1}"
            }
        }
        return found
    }

    /** The '(' of the call whose argument list holds [index]. */
    private fun enclosingCallOpen(text: String, index: Int): Int? {
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (depth == 0) return i else depth--
                '{', '}' -> if (depth == 0) return null
            }
            i--
        }
        return null
    }

    private fun matching(text: String, open: Int, opening: Char, closing: Char): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                opening -> depth++
                closing -> if (--depth == 0) return i
            }
        }
        return null
    }

    private companion object {
        val SOURCE = Regex("""\.hazeSource\(\s*(\w+)""")
    }
}
