package tf.monochrome.android.data.api

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Apple-style TTML lyrics, the format of Apple Music, lrc.red and the AMLL
 * TTML database.
 *
 * Each `<p begin end>` is a line. Word-timed files time each syllable in a
 * `<span begin end>`, with a whitespace text node between spans where one
 * word ends — the same boundary [LyricsText.groupSyllables] joins on. Spans
 * with `ttm:role="x-translation"` / `x-roman…` are other text, not sung, and
 * are skipped. Backing vocals (`x-bg`) are sung and kept, after the lead.
 * A `<p>` with no spans is line-timed; a file with no `begin` at all is plain.
 */
object TtmlLyricsParser {

    fun parse(ttml: String, convertToRomaji: Boolean = false): Lyrics? {
        val doc = runCatching { newBuilderFactory().newDocumentBuilder().parse(InputSource(StringReader(ttml))) }
            .getOrNull() ?: return null
        val paragraphs = doc.getElementsByTagName("p")
        val lines = mutableListOf<LyricLine>()
        var anyTimed = false
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val begin = parseTime(p.getAttribute("begin"))
            val syllables = mutableListOf<TimedSyllable>()
            collect(p, syllables)
            if (syllables.isNotEmpty()) {
                val words = LyricsText.groupSyllables(syllables, convertToRomaji)
                LyricsText.lineOf(begin ?: words.first().startMs, words)?.let {
                    lines.add(it)
                    anyTimed = true
                }
                continue
            }
            var text = sungText(p).trim()
            if (text.isEmpty()) continue
            if (convertToRomaji) text = RomajiConverter.convert(text)
            if (begin != null) anyTimed = true
            lines.add(LyricLine(timeMs = begin ?: 0L, text = text))
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines = if (anyTimed) lines.sortedBy { it.timeMs } else lines, isSynced = anyTimed)
    }

    /** Timed spans under [node], in document order; whitespace between them ends a word. */
    private fun collect(node: Node, out: MutableList<TimedSyllable>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    if (child.nodeValue.orEmpty().any { it.isWhitespace() }) endWord(out)
                }
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    if (el.tagName != "span") continue
                    val role = el.getAttribute("ttm:role")
                    if (role.startsWith("x-translation") || role.startsWith("x-roman")) continue
                    val begin = parseTime(el.getAttribute("begin"))
                    val end = parseTime(el.getAttribute("end"))
                    if (role == "x-bg" || hasChildSpan(el) || begin == null) {
                        // A container: backing vocals are their own words, so
                        // they never fuse with the lead word beside them.
                        endWord(out)
                        collect(el, out)
                        endWord(out)
                    } else {
                        val text = el.textContent.orEmpty()
                        if (text.isNotEmpty()) out.add(TimedSyllable(begin, end ?: begin, text))
                    }
                }
            }
        }
    }

    private fun endWord(out: MutableList<TimedSyllable>) {
        val last = out.lastOrNull() ?: return
        if (!last.text.last().isWhitespace()) out[out.lastIndex] = last.copy(text = last.text + " ")
    }

    private fun hasChildSpan(el: Element): Boolean {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element && c.tagName == "span") return true
        }
        return false
    }

    /** A line's text without its translation and romanisation spans. */
    private fun sungText(node: Node): String = buildString {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> append(child.nodeValue)
                Node.ELEMENT_NODE -> {
                    val role = (child as Element).getAttribute("ttm:role")
                    if (!role.startsWith("x-translation") && !role.startsWith("x-roman")) append(sungText(child))
                }
            }
        }
    }

    /** "27.395", "1:02.345", "1:02:03.4", "12.5s", "500ms" → milliseconds. */
    fun parseTime(raw: String?): Long? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.endsWith("ms")) return t.dropLast(2).toDoubleOrNull()?.roundToLong()
        var seconds = 0.0
        for (part in t.removeSuffix("s").split(':')) {
            seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        }
        return (seconds * 1000).roundToLong()
    }

    /**
     * The lyrics come from third-party servers, so no DTDs and no external
     * entities. Android's parser does not know every feature name; one it
     * rejects is one it does not implement, which is safe to skip.
     */
    private fun newBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isValidating = false
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
    }
}
