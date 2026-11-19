package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Tag text from before Unicode. Guessing wrong one way turns "Сплин" into
 * "Ñïëèí"; wrong the other way turns "Beyoncé" into "Beyoncй".
 */
class LegacyTextTest {

    /** [text] as the native reader hands it over: marked, its bytes in [charset] one to a character. */
    private fun marked(text: String, charset: String): String =
        LegacyText.MARK + String(text.toByteArray(Charset.forName(charset)), Charsets.ISO_8859_1)

    private fun read(values: List<String>, languages: List<String>, detector: LegacyText.Detector? = null): List<String> {
        val charset = LegacyText.charsetFor(values, languages, detector)
        return values.map { LegacyText.decode(it, charset) }
    }

    @Test
    fun `text without the mark is left alone`() {
        assertFalse(LegacyText.hasMarked(listOf("Plain", "日本語", "")))
        assertEquals(listOf("Plain", "日本語"), read(listOf("Plain", "日本語"), listOf("ru")))
    }

    @Test
    fun `Latin-1 stays Latin-1 on a phone in English`() {
        val values = listOf(marked("Beyoncé", "windows-1252"), marked("Mötley Crüe", "windows-1252"), "Plain")
        assertTrue(LegacyText.hasMarked(values))
        assertEquals(listOf("Beyoncé", "Mötley Crüe", "Plain"), read(values, listOf("en-US")))
    }

    @Test
    fun `Windows quotes and dashes are read, where Latin-1 has nothing`() {
        assertEquals(listOf("Don’t Stop – Live"), read(listOf(marked("Don’t Stop – Live", "windows-1252")), listOf("en")))
    }

    @Test
    fun `Russian tags are read on a phone in Russian`() {
        assertEquals(listOf("Сплин", "Выхода нет"), read(listOf(marked("Сплин", "windows-1251"), marked("Выхода нет", "windows-1251")), listOf("ru-RU")))
    }

    @Test
    fun `Russian tags are read on a phone in English too`() {
        assertEquals(listOf("Сплин - Выхода нет"), read(listOf(marked("Сплин - Выхода нет", "windows-1251")), listOf("en-US")))
    }

    @Test
    fun `a Western name is not read as Russian on a phone in Russian`() {
        assertEquals(listOf("Beyoncé", "Mötley Crüe"), read(listOf(marked("Beyoncé", "windows-1252"), marked("Mötley Crüe", "windows-1252")), listOf("ru-RU", "en")))
    }

    @Test
    fun `Chinese tags are read on a phone in Chinese`() {
        assertEquals(listOf("周杰伦", "青花瓷"), read(listOf(marked("周杰伦", "GBK"), marked("青花瓷", "GBK")), listOf("zh-CN")))
        assertEquals(listOf("周杰倫"), read(listOf(marked("周杰倫", "Big5")), listOf("zh-TW")))
    }

    @Test
    fun `a Western name is not read as Chinese on a phone in Chinese`() {
        assertEquals(listOf("Mötley Crüe"), read(listOf(marked("Mötley Crüe", "windows-1252")), listOf("zh-CN")))
    }

    @Test
    fun `Japanese tags are read on a phone in Japanese, and by their kana on any other`() {
        val values = listOf(marked("ももいろクローバー", "Shift_JIS"), marked("桃色片想い", "Shift_JIS"))
        assertEquals(listOf("ももいろクローバー", "桃色片想い"), read(values, listOf("ja-JP")))
        assertEquals(listOf("ももいろクローバー", "桃色片想い"), read(values, listOf("en-US")))
    }

    @Test
    fun `Turkish letters are read on a phone in Turkish`() {
        assertEquals(listOf("Şebnem Ferah", "Işık"), read(listOf(marked("Şebnem Ferah", "windows-1254"), marked("Işık", "windows-1254")), listOf("tr-TR")))
    }

    @Test
    fun `a detector is believed only when sure, and only about another script`() {
        val russian = listOf(marked("Да", "windows-1251"))
        // Two letters: too short for the built-in rule, so it stays Latin-1…
        assertEquals(listOf("Äà"), read(russian, listOf("en")))
        // …unless a detector is sure,
        assertEquals(listOf("Да"), read(russian, listOf("en")) { "windows-1251" to 80 })
        // and not when it is unsure, or names a Latin code page.
        assertEquals(listOf("Äà"), read(russian, listOf("en")) { "windows-1251" to 20 })
        assertEquals(listOf("Äà"), read(russian, listOf("en")) { "windows-1250" to 90 })
    }

    @Test
    fun `one file is read in one code page`() {
        val values = listOf(marked("Кино", "windows-1251"), "Plain", marked("Группа крови", "windows-1251"))
        assertEquals(listOf("Кино", "Plain", "Группа крови"), read(values, listOf("en")))
    }
}
