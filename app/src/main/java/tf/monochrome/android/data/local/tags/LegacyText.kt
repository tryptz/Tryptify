package tf.monochrome.android.data.local.tags

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Tag text whose file called it Latin-1 while it was neither Latin-1 nor
 * UTF-8: an MP3 tagged years ago in the code page of its country (CP1251 for
 * Russian, GBK for Chinese, Shift-JIS for Japanese). Read as Latin-1 it is
 * the familiar garbage, "Ñïëèí" for "Сплин".
 *
 * The native reader settles plain ASCII and undeclared UTF-8 itself and marks
 * the rest ([MARK]) with the bytes kept one to a character. This decides what
 * those bytes were, for one file at a time, since a file's tags were written
 * by one tagger in one code page:
 *
 *  1. the code page of a language the phone is set to, when the bytes are
 *     well-formed in it and read as that language's script;
 *  2. else what a [Detector] says, when it is sure and names a non-Latin
 *     script: [BuiltIn], since Android keeps its own detector to itself;
 *  3. else Windows-1252, which is Latin-1 with the curly quotes and dashes
 *     Windows taggers put where Latin-1 has nothing printable (Windows-1254
 *     on a phone set to Turkish, which differs in six letters).
 *
 * A guess, as it has to be: nothing in the file says. Android's own reader
 * guesses too, differently on each version; this guesses one way everywhere.
 */
internal object LegacyText {

    /** U+E000: what the native reader puts in front of such a value. */
    const val MARK = ''

    /** A charset detector's answer: a charset's name and how sure it is, 0 to 100. */
    fun interface Detector {
        fun detect(bytes: ByteArray): Pair<String, Int>?
    }

    /**
     * A detector that needs no tables: it only answers when the bytes can be
     * one thing.
     *
     * Cyrillic in CP1251 is letters from 0xC0 up (and ё): a Russian word is
     * made of them alone, where an accented Western word mixes one or two
     * with plain letters ("Beyonc\u00e9"). For Chinese, Japanese and Korean
     * it answers when exactly one of their code pages reads the bytes
     * whole, as its own script and little else; kana settles Japanese.
     */
    object BuiltIn : Detector {
        override fun detect(bytes: ByteArray): Pair<String, Int>? {
            if (looksCyrillic(bytes)) return "windows-1251" to 100
            val fits = WIDE.filter { (charset, scripts) ->
                val text = strictly(bytes, charset) ?: return@filter false
                reads(text, scripts) && wideShare(text) >= 0.5
            }
            val kana = fits.firstOrNull { (charset, _) ->
                canonical(charset.name()) == "SJIS" && strictly(bytes, charset).orEmpty().any {
                    val script = runCatching { Character.UnicodeScript.of(it.code) }.getOrNull()
                    script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                }
            }
            val one = kana ?: fits.singleOrNull() ?: return null
            return one.first.name() to 100
        }

        private fun looksCyrillic(bytes: ByteArray): Boolean {
            fun high(b: Int) = b >= 0x80
            fun letter1251(b: Int) = b >= 0xC0 || b == 0xA8 || b == 0xB8
            fun asciiLetter(b: Int) = b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code
            var highBytes = 0
            var i = 0
            while (i < bytes.size) {
                // One word: letters, plain or high, up to the next thing that is neither.
                var sawHigh = false
                var sawPlain = false
                while (i < bytes.size) {
                    val b = bytes[i].toInt() and 0xFF
                    if (high(b)) {
                        if (!letter1251(b)) return false
                        sawHigh = true
                        highBytes++
                    } else if (asciiLetter(b)) {
                        sawPlain = true
                    } else {
                        break
                    }
                    i++
                }
                if (sawHigh && sawPlain) return false
                i++
            }
            return highBytes >= 3
        }
    }

    /** Whether any of [values] carries the mark. */
    fun hasMarked(values: Iterable<String>): Boolean = values.any { it.isNotEmpty() && it[0] == MARK }

    /**
     * The charset the marked ones among [values] were written in. [languages]
     * are the phone's, most wanted first ("ru", "zh-TW").
     */
    fun charsetFor(values: Iterable<String>, languages: List<String>, detector: Detector?): Charset {
        val joined = values.filter { it.isNotEmpty() && it[0] == MARK }
            .joinToString("\n") { it.substring(1) }
        val bytes = bytesOf(joined)
        if (bytes.isEmpty()) return WINDOWS_1252

        val guess = runCatching { (detector ?: BuiltIn).detect(bytes) }.getOrNull()
        for (language in languages) {
            val hint = hintFor(language) ?: continue
            val text = strictly(bytes, hint.charset) ?: continue
            if (!reads(text, hint.scripts)) continue
            // In a one-byte code page any byte is some letter, so "Beyonc\u00e9"
            // reads as Russian with a Russian letter at its end. A word of
            // that language is its letters alone; a Western name mixes one
            // or two with plain ones.
            if (!hint.multiByte && mixesPlainAndHigh(bytes)) continue
            // Two bytes of a Western name ("ö" and the letter after it) are a
            // well-formed Chinese character too. Chinese text is mostly such
            // pairs; a Western name has one or two among its letters.
            if (hint.multiByte && wideShare(text) < 0.3 && !sameCharset(guess?.first, hint.charset)) continue
            return hint.charset
        }
        if (guess != null && guess.second >= SURE) {
            val charset = runCatching { Charset.forName(guess.first) }.getOrNull()
            val scripts = charset?.let { NON_LATIN[canonical(it.name())] }
            if (charset != null && scripts != null) {
                val text = strictly(bytes, charset)
                if (text != null && reads(text, scripts)) return charset
            }
        }
        return if (languages.any { it.substringBefore('-').lowercase(Locale.ROOT) in TURKIC }) WINDOWS_1254 else WINDOWS_1252
    }

    /** [value] without its mark, read as [charset]; any other value as it is. */
    fun decode(value: String, charset: Charset): String {
        if (value.isEmpty() || value[0] != MARK) return value
        val bytes = bytesOf(value.substring(1))
        return strictly(bytes, charset) ?: String(bytes, charset)
    }

    /** The bytes the native reader kept one to a character. */
    private fun bytesOf(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

    private fun strictly(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    /** Whether any word of [bytes] has both plain letters and bytes above 0x7F. */
    private fun mixesPlainAndHigh(bytes: ByteArray): Boolean {
        var sawHigh = false
        var sawPlain = false
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            when {
                b >= 0x80 -> sawHigh = true
                b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code -> sawPlain = true
                else -> { sawHigh = false; sawPlain = false }
            }
            if (sawHigh && sawPlain) return true
        }
        return false
    }

    /** Whether most of [text]'s letters beyond ASCII are of [scripts]; true for no scripts asked. */
    private fun reads(text: String, scripts: Set<Character.UnicodeScript>): Boolean {
        if (scripts.isEmpty()) return true
        var beyond = 0
        var matching = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80 || !Character.isLetter(cp)) continue
            beyond++
            if (runCatching { Character.UnicodeScript.of(cp) }.getOrNull() in scripts) matching++
        }
        return beyond > 0 && matching * 10 >= beyond * 8
    }

    /** The share of [text]'s letters and digits that are beyond Latin-1: the two-byte ones. */
    private fun wideShare(text: String): Double {
        var all = 0
        var wide = 0
        for (c in text) {
            if (!c.isLetterOrDigit()) continue
            all++
            if (c.code > 0xFF) wide++
        }
        return if (all == 0) 0.0 else wide.toDouble() / all
    }

    private fun sameCharset(name: String?, charset: Charset): Boolean {
        if (name == null) return false
        val other = runCatching { Charset.forName(name) }.getOrNull() ?: return false
        return canonical(other.name()) == canonical(charset.name())
    }

    /** One name for the charsets that are the same thing here (GBK and GB18030, the Shift-JIS family). */
    private fun canonical(name: String): String = when (val n = name.uppercase(Locale.ROOT)) {
        "GBK", "GB2312", "GB18030", "X-GBK" -> "GB"
        "SHIFT_JIS", "WINDOWS-31J", "X-SJIS", "MS932" -> "SJIS"
        "EUC-KR", "X-WINDOWS-949", "MS949", "WINDOWS-949" -> "KR"
        "BIG5", "BIG5-HKSCS", "X-BIG5" -> "BIG5"
        else -> n
    }

    private class Hint(val charset: Charset, val scripts: Set<Character.UnicodeScript>, val multiByte: Boolean)

    private fun charset(vararg names: String): Charset? =
        names.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }

    private val WINDOWS_1252: Charset = charset("windows-1252") ?: Charsets.ISO_8859_1
    private val WINDOWS_1254: Charset = charset("windows-1254") ?: WINDOWS_1252
    private val TURKIC = setOf("tr", "az")

    private val HAN = setOf(Character.UnicodeScript.HAN)
    private val JAPANESE = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    )
    private val KOREAN = setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN)
    private val CYRILLIC = setOf(Character.UnicodeScript.CYRILLIC)
    private val GREEK = setOf(Character.UnicodeScript.GREEK)
    private val HEBREW = setOf(Character.UnicodeScript.HEBREW)
    private val ARABIC = setOf(Character.UnicodeScript.ARABIC)
    private val THAI = setOf(Character.UnicodeScript.THAI)

    /** The scripts a detector's answer must read as, by charset; a Latin code page is not in it. */
    private val NON_LATIN: Map<String, Set<Character.UnicodeScript>> = mapOf(
        "GB" to HAN, "BIG5" to HAN, "SJIS" to JAPANESE, "EUC-JP" to JAPANESE, "KR" to KOREAN,
        "WINDOWS-1251" to CYRILLIC, "KOI8-R" to CYRILLIC, "ISO-8859-5" to CYRILLIC,
        "WINDOWS-1253" to GREEK, "ISO-8859-7" to GREEK,
        "WINDOWS-1255" to HEBREW, "ISO-8859-8" to HEBREW, "ISO-8859-8-I" to HEBREW,
        "WINDOWS-1256" to ARABIC, "ISO-8859-6" to ARABIC,
        "WINDOWS-874" to THAI, "TIS-620" to THAI, "X-WINDOWS-874" to THAI,
    )

    private fun hintFor(language: String): Hint? {
        val tag = language.lowercase(Locale.ROOT)
        val base = tag.substringBefore('-')
        fun hint(scripts: Set<Character.UnicodeScript>, multiByte: Boolean, vararg names: String): Hint? =
            charset(*names)?.let { Hint(it, scripts, multiByte) }
        return when (base) {
            "ja" -> hint(JAPANESE, true, "windows-31j", "Shift_JIS")
            "zh" -> if ("-tw" in tag || "-hk" in tag || "-mo" in tag || "hant" in tag) {
                hint(HAN, true, "Big5")
            } else {
                hint(HAN, true, "GBK", "GB18030")
            }
            "ko" -> hint(KOREAN, true, "x-windows-949", "EUC-KR")
            "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg" -> hint(CYRILLIC, false, "windows-1251")
            "el" -> hint(GREEK, false, "windows-1253")
            "he", "iw" -> hint(HEBREW, false, "windows-1255")
            "ar", "fa", "ur" -> hint(ARABIC, false, "windows-1256")
            "th" -> hint(THAI, false, "windows-874", "x-windows-874", "TIS-620")
            else -> null
        }
    }

    /** The two-byte code pages [BuiltIn] tries, with the scripts each must read as. */
    private val WIDE: List<Pair<Charset, Set<Character.UnicodeScript>>> = listOfNotNull(
        charset("windows-31j", "Shift_JIS")?.let { it to JAPANESE },
        charset("GBK", "GB18030")?.let { it to HAN },
        charset("Big5")?.let { it to HAN },
        charset("x-windows-949", "EUC-KR")?.let { it to KOREAN },
    )

    /** How sure a detector has to be before it is believed with no language to back it. */
    private const val SURE = 50
}
