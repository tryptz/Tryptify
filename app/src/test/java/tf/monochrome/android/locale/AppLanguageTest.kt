package tf.monochrome.android.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which translation a language tag lands on.
 *
 * The tag comes from the phone or from storage, in whatever shape they keep
 * it, and getting this wrong is quiet: a reader is shown English, or a script
 * they did not choose, and nothing reports it.
 */
class AppLanguageTest {

    private fun match(tag: String) = AppLanguage.matchOption(tag)

    @Test
    fun `the offered tags match themselves`() {
        AppLanguage.OPTIONS.forEach { assertEquals(it.tag, match(it.tag)) }
    }

    @Test
    fun `a regional variant lands on its language`() {
        assertEquals("de", match("de-AT"))
        assertEquals("de", match("de-CH"))
        assertEquals("fr", match("fr-CA"))
        assertEquals("es", match("es-MX"))
        assertEquals("tr", match("tr-TR"))
        assertEquals("ja", match("ja-JP"))
        assertEquals("en", match("en-GB"))
    }

    @Test
    fun `Simplified Chinese in any of its spellings is the Chinese translation`() {
        assertEquals("zh-CN", match("zh"))
        assertEquals("zh-CN", match("zh-CN"))
        assertEquals("zh-CN", match("zh-Hans"))
        assertEquals("zh-CN", match("zh-Hans-CN"))
        assertEquals("zh-CN", match("zh-SG"))
    }

    @Test
    fun `Traditional Chinese is never handed the Simplified translation`() {
        // Taiwan, Hong Kong and Macau write Traditional; Simplified is a
        // different script to those readers, not a regional accent.
        assertEquals("", match("zh-TW"))
        assertEquals("", match("zh-HK"))
        assertEquals("", match("zh-MO"))
        assertEquals("", match("zh-Hant"))
        assertEquals("", match("zh-Hant-TW"))
    }

    @Test
    fun `nothing, or a language with no translation, follows the phone`() {
        assertEquals("", match(""))
        assertEquals("", match("   "))
        assertEquals("", match("ko"))
        assertEquals("", match("pt-BR"))
    }

    @Test
    fun `every offered language has a translation folder behind it`() {
        // A tag with no values-* folder would switch the app to English while
        // the picker claims otherwise.
        val res = File("src/main/res")
        AppLanguage.OPTIONS.filter { it.tag != "en" }.forEach { option ->
            val folder = when (option.tag) {
                "zh-CN" -> "values-zh-rCN"
                else -> "values-${option.tag}"
            }
            assertTrue("${option.tag} has no $folder/strings.xml", File(res, "$folder/strings.xml").isFile)
        }
    }

    @Test
    fun `every language is listed in its own name`() {
        // Endonyms: somebody stuck in a language they cannot read still finds
        // their own in the list.
        val names = AppLanguage.OPTIONS.associate { it.tag to it.nativeName }
        assertEquals("简体中文", names["zh-CN"])
        assertEquals("日本語", names["ja"])
        assertEquals("Türkçe", names["tr"])
        assertEquals("Deutsch", names["de"])
    }
}
