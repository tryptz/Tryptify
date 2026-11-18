package tf.monochrome.android.ui.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headphone list's search finds a model however it is typed: spaces,
 * hyphens, word order and a slip of the finger do not hide it.
 */
class HeadphoneSearchTest {

    private val names = listOf(
        "Sennheiser HD 600",
        "Sennheiser HD 660S2",
        "Sony WH-1000XM4",
        "Beyerdynamic DT 770 PRO 80 Ohm",
        "Focal Bathys",
        "Nothing Ear (2024)",
        "AKG K371",
        "Moondrop Blessing 3",
    )

    private fun find(query: String, list: List<String> = names): List<String> =
        HeadphoneSearch.filter(list, query, name = { HeadphoneSearch.entry(it) })

    private fun findWithSource(query: String): List<Pair<String, String>> {
        val rows = listOf("Sennheiser HD 600" to "oratory1990", "Sennheiser HD 600" to "crinacle")
        return HeadphoneSearch.filter(
            rows, query,
            name = { HeadphoneSearch.entry(it.first) },
            extra = { HeadphoneSearch.entry(it.second) },
        )
    }

    @Test
    fun `spaces and punctuation do not count`() {
        assertEquals(listOf("Sennheiser HD 600"), find("hd600"))
        assertEquals(listOf("Sennheiser HD 600"), find("HD   600"))
        assertEquals(listOf("Sennheiser HD 600"), find("hd-600"))
        assertEquals(listOf("Sony WH-1000XM4"), find("wh1000xm4"))
        assertEquals(listOf("Sony WH-1000XM4"), find("wh 1000 xm4"))
        assertEquals(listOf("Nothing Ear (2024)"), find("nothing ear 2024"))
    }

    @Test
    fun `words are found in any order`() {
        assertEquals(listOf("Sennheiser HD 600"), find("sennheiser 600"))
        assertEquals(listOf("Sennheiser HD 600"), find("600 sennheiser"))
        assertEquals(listOf("Beyerdynamic DT 770 PRO 80 Ohm"), find("dt770 80ohm"))
    }

    @Test
    fun `a typo still finds the headphone`() {
        assertEquals(listOf("Sennheiser HD 600"), find("senheiser hd600"))
        assertEquals(listOf("Sennheiser HD 600"), find("sennhieser 600"))
        assertEquals(listOf("Focal Bathys"), find("focal bathis"))
        assertEquals(listOf("Moondrop Blessing 3"), find("moondorp"))
    }

    @Test
    fun `a half-typed word with a slip is found`() {
        assertTrue(find("senhei").containsAll(listOf("Sennheiser HD 600", "Sennheiser HD 660S2")))
    }

    @Test
    fun `numbers are never guessed`() {
        // A slip in a model number would be another headphone.
        assertEquals(emptyList<String>(), find("hd 601"))
        assertEquals(listOf("Sennheiser HD 660S2"), find("hd660"))
    }

    @Test
    fun `short words are never guessed`() {
        assertEquals(emptyList<String>(), find("akx"))
        assertEquals(listOf("AKG K371"), find("akg"))
    }

    @Test
    fun `near matches only show when nothing matches exactly`() {
        // "sony" matches exactly, so "Sonu"-like near misses stay out.
        val list = names + "Sonus Faber Pryma"
        assertEquals(listOf("Sony WH-1000XM4"), find("sony", list))
    }

    @Test
    fun `accents and case do not count`() {
        assertEquals(listOf("Sennheiser HD 600"), find("SENNHÉISER hd600"))
    }

    @Test
    fun `a word can name the source`() {
        assertEquals(listOf("Sennheiser HD 600" to "oratory1990"), findWithSource("hd600 oratory"))
        assertEquals(2, findWithSource("hd 600").size)
    }

    @Test
    fun `an empty search keeps everything`() {
        assertEquals(names, find(""))
        assertEquals(names, find("  - "))
    }

    @Test
    fun `edits count a swap of two letters as one`() {
        assertEquals(1, HeadphoneSearch.prefixDistance("sennhieser", "sennheiser"))
        assertEquals(1, HeadphoneSearch.prefixDistance("senhe", "sennheiser"))
        assertEquals(0, HeadphoneSearch.prefixDistance("senn", "sennheiser"))
    }
}
