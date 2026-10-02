package tf.monochrome.android.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The translations as they ship, read straight from the generated XML.
 *
 * `app/l10n/generate_strings.py` refuses to write a hole, a lost placeholder or
 * a missing plural form — but only for what goes through it. Somebody fixing a
 * typo in one `values-*` file by hand bypasses all of that, and every one of
 * these failures is silent on a device: Android falls back to English for a
 * missing string, and a placeholder that vanished from a translation simply
 * drops the number from the sentence. So the shipped files are checked too.
 */
class StringResourcesTest {

    private val res = File("src/main/res")
    private val folders = listOf("values-zh-rCN", "values-ja", "values-fr", "values-es", "values-tr", "values-de")

    /** Plural categories each language actually uses (CLDR). */
    private val pluralForms = mapOf(
        "values" to setOf("one", "other"),
        "values-zh-rCN" to setOf("other"),
        "values-ja" to setOf("other"),
        "values-fr" to setOf("one", "many", "other"),
        "values-es" to setOf("one", "many", "other"),
        "values-tr" to setOf("one", "other"),
        "values-de" to setOf("one", "other"),
    )

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(folder: String): Strings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$folder/strings.xml"))
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> if (e.getAttribute("translatable") != "false") strings[name] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Strings(strings, plurals)
    }

    private val placeholder = Regex("""%\d+\$[sdf]""")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    private val english by lazy { load("values") }

    @Test
    fun `every language has exactly the English strings`() {
        folders.forEach { folder ->
            val t = load(folder)
            assertEquals("$folder: strings differ from English", english.strings.keys, t.strings.keys)
            assertEquals("$folder: plurals differ from English", english.plurals.keys, t.plurals.keys)
        }
    }

    @Test
    fun `no translation loses or invents a placeholder`() {
        folders.forEach { folder ->
            val t = load(folder)
            val bad = english.strings.filter { (key, en) ->
                t.strings[key]?.let { placeholders(it) } != placeholders(en)
            }.keys
            assertTrue("$folder: placeholders differ in $bad", bad.isEmpty())
        }
    }

    @Test
    fun `every plural has its language's forms, and keeps the count`() {
        (listOf("values") + folders).forEach { folder ->
            val t = load(folder)
            t.plurals.forEach { (key, forms) ->
                assertEquals("$folder/$key quantities", pluralForms.getValue(folder), forms.keys)
                val want = placeholders(english.plurals.getValue(key).getValue("other"))
                forms.forEach { (q, text) ->
                    val got = placeholders(text)
                    // "one" may leave the number out; no form may invent one.
                    val ok = if (q == "one") want.containsAll(got) else got == want
                    assertTrue("$folder/$key[$q] has $got, English has $want", ok)
                }
            }
        }
    }

    @Test
    fun `no unescaped apostrophe reaches aapt`() {
        // An unescaped ' in a resource is a build error at best and a
        // truncated string at worst; the generator escapes it, a hand edit
        // might not. The typographic ’ that French and Turkish use is fine.
        (listOf("values") + folders).forEach { folder ->
            val raw = File(res, "$folder/strings.xml").readText()
            val bad = Regex("""(?<!\\)'""").findAll(raw).count()
            assertEquals("$folder has $bad unescaped apostrophes", 0, bad)
        }
    }
}
