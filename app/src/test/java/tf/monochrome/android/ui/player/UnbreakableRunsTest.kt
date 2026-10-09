package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UnbreakableRunsTest {

    @Test
    fun `latin words are the runs`() {
        assertEquals(listOf("I", "want", "everything"), unbreakableRuns("I want  everything"))
    }

    @Test
    fun `han and kana break between any two characters`() {
        // A whole Japanese line has no spaces; treated as one word it would
        // shrink the line for no reason, since Android wraps it anywhere.
        assertEquals(listOf("無", "敵", "の", "笑", "顔"), unbreakableRuns("無敵の笑顔"))
        assertEquals(listOf("故", "事", "的", "小", "黄", "花"), unbreakableRuns("故事的小黄花"))
    }

    @Test
    fun `mixed scripts keep latin words whole`() {
        assertEquals(listOf("Hello", "世", "界", "again"), unbreakableRuns("Hello世界 again"))
    }

    @Test
    fun `hangul keeps its spaced words`() {
        assertEquals(listOf("사랑해", "너를"), unbreakableRuns("사랑해 너를"))
    }

    @Test
    fun `a line may break after a hyphen or dash`() {
        // Text wraps a "na-na-na" chain at its hyphens; as one run it would
        // shrink the whole line even at the default size.
        assertEquals(listOf("Na-", "na-", "na,", "hey"), unbreakableRuns("Na-na-na, hey"))
        assertEquals(listOf("rock-", "and-", "roll"), unbreakableRuns("rock-and-roll"))
        assertEquals(listOf("now—", "then"), unbreakableRuns("now—then"))
    }

    @Test
    fun `blank text has no runs`() {
        assertEquals(emptyList<String>(), unbreakableRuns("   "))
    }
}
