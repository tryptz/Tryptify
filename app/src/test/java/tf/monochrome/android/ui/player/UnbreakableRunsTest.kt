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
    fun `hyphenated words stay whole`() {
        // With hyphenation off (Compose's default) the line breaker never wraps
        // after a hyphen or en dash: measured in pieces, a long one would be
        // drawn at full size and split between two letters.
        assertEquals(listOf("Na-na-na,", "hey"), unbreakableRuns("Na-na-na, hey"))
        assertEquals(listOf("rock-and-roll"), unbreakableRuns("rock-and-roll"))
        assertEquals(listOf("867-5309,"), unbreakableRuns("867-5309,"))
        assertEquals(listOf("1990–2000"), unbreakableRuns("1990–2000"))
    }

    @Test
    fun `an em dash is a piece of its own`() {
        assertEquals(listOf("now", "—", "then"), unbreakableRuns("now—then"))
        assertEquals(listOf("well", "—"), unbreakableRuns("well—"))
        assertEquals(listOf("word", "——", "word"), unbreakableRuns("word——word"))
    }

    @Test
    fun `an ellipsis slash or exclamation may break before a letter`() {
        assertEquals(listOf("Oh…", "oh…", "oh"), unbreakableRuns("Oh…oh…oh"))
        assertEquals(listOf("and/", "or"), unbreakableRuns("and/or"))
        assertEquals(listOf("what?!", "who"), unbreakableRuns("what?!who"))
        // …but not before a digit or at the end.
        assertEquals(listOf("1/2", "why?"), unbreakableRuns("1/2 why?"))
    }

    @Test
    fun `blank text has no runs`() {
        assertEquals(emptyList<String>(), unbreakableRuns("   "))
    }
}
