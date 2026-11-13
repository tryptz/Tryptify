package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A pane of text that opens over a page of text is frosted glass, not the
 * live lens (docs/ui-invariants.md, "A pane of text over a page is frosted").
 *
 * The song menu and the player's Audio pipeline sheet were plain
 * `GlassPanel`s, which bend the live screen behind them: a fifth of
 * "Backdrop blur", clear glass, the slab at half its body. Over a list or the
 * player that left the rows behind legible through the pane's own rows, next
 * to the audio-tools sheet, which frosts. Each of these panes passes
 * `frosted = true`; this reads the call and says which one lost it.
 */
class FrostedPaneTest {

    /** The file, and the text its `GlassPanel(` call follows (so the right call is read where a file has more). */
    private val panes = listOf(
        "components/GlassOverlay.kt" to "slideOutVertically(tween(CLOSE_MS))",
        "player/AudioPipelinePanel.kt" to "BoxScope.AudioPipelinePanel(",
        "player/ConnectPanel.kt" to "BoxScope.ConnectPanel(",
        "player/MainPlayerRoute.kt" to "BoxScope.SpeedPanel(",
    )

    @Test
    fun `the overlay pane and the player's sheets are frosted`() {
        val missing = panes.mapNotNull { (path, after) ->
            val text = File("src/main/java/tf/monochrome/android/ui/$path").readText()
            val from = text.indexOf(after)
            assertTrue("$path no longer has '$after': the scan is not reading the pane", from >= 0)
            val at = text.indexOf("GlassPanel(", from)
            assertTrue("$path has no GlassPanel after '$after'", at >= 0)
            val open = at + "GlassPanel".length
            val args = text.substring(open, closingParen(text, open))
            path.takeUnless { Regex("""\bfrosted\s*=\s*true\b""").containsMatchIn(args) }
        }
        assertEquals("panes of text over a page that are not frosted", emptyList<String>(), missing)
    }

    /** The index of the `)` matching the `(` at [open]. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
        }
        return text.length
    }
}
