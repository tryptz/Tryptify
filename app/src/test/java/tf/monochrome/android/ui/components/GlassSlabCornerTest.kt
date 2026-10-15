package tf.monochrome.android.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every glass pane's slab is rounded like the pane, and tells the shader its
 * corner (docs/ui-invariants.md, "A rounded-rect slab passes its corner as
 * lensCorner").
 *
 * The player's audio sheet drew a square `drawRect` under `playerGlass` with no
 * `lensCorner`: the shader lit the rectangle's square edges, the sheet's
 * rounded clip cut the corners off, and its rim stopped short of every corner
 * while the pills beside it ran theirs all the way round. This reads every
 * `.playerGlass(` in the UI and the slab drawn under it.
 */
class GlassSlabCornerTest {

    @Test
    fun `no glass slab is a square, and a rounded one passes its corner`() {
        val problems = mutableListOf<String>()
        val sources = File("src/main/java/tf/monochrome/android/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("no UI sources found", sources.isNotEmpty())
        var slabs = 0
        for (file in sources) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf(".playerGlass(", from)
                if (at < 0) break
                val open = at + ".playerGlass".length
                val close = closingParen(text, open)
                val args = text.substring(open, close)
                // What the modifier's Canvas draws: the next few lines.
                val after = text.substring(close, minOf(text.length, close + 900))
                val line = text.substring(0, at).count { it == '\n' } + 1
                val where = "${file.name}:$line"
                if (Regex("""\bdrawRect\(""").containsMatchIn(after)) {
                    problems += "$where draws a square slab under playerGlass"
                }
                if (after.contains("drawRoundRect(")) {
                    slabs++
                    if (!args.contains("lensCorner")) problems += "$where rounds its slab but passes no lensCorner"
                }
                from = close
            }
        }
        assertTrue("found no rounded glass slabs: the scan is not reading the sources", slabs >= 3)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** The index of the `)` matching the `(` at [open]. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return text.length
    }
}
