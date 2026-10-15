package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The matching count only goes forward. The searches run several at a time
 * and publish as they finish, so the one that counted 99 could publish after
 * the one that counted 100, and the count went back to 99 until Done.
 */
class ImportProgressTest {

    @Test
    fun `a late lower count doesn't take the shown one back`() {
        val at100 = ImportProgress.Matching(current = 100, total = 100, matched = 97)
        assertEquals(at100, nextMatching(at100, done = 99, total = 100, matched = 96))
    }

    @Test
    fun `a higher count moves it on`() {
        val at40 = ImportProgress.Matching(current = 40, total = 100, matched = 38)
        assertEquals(
            ImportProgress.Matching(current = 41, total = 100, matched = 39),
            nextMatching(at40, done = 41, total = 100, matched = 39),
        )
    }

    @Test
    fun `out of order, the count still ends at the total`() {
        var shown: ImportProgress = ImportProgress.Matching(current = 0, total = 6, matched = 0)
        for (done in listOf(2, 1, 3, 6, 4, 5)) shown = nextMatching(shown, done, total = 6, matched = done)
        assertEquals(6, (shown as ImportProgress.Matching).current)
    }

    @Test
    fun `anything but this import's matching is replaced`() {
        val done = ImportProgress.Done("id", "Old", matched = 3, total = 3, unmatched = emptyList())
        assertEquals(ImportProgress.Matching(1, 10, 1), nextMatching(done, done = 1, total = 10, matched = 1))
        // Another playlist's count, of another length, is not this one's.
        val other = ImportProgress.Matching(current = 50, total = 50, matched = 50)
        assertEquals(ImportProgress.Matching(1, 10, 0), nextMatching(other, done = 1, total = 10, matched = 0))
    }
}
