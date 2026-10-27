// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a song's own lyrics text becomes, whether a `.lrc` or a tag holds it. */
class LocalLyricsTest {

    @Test
    fun `lrc text is timed`() {
        val lyrics = LocalLyrics.parse("[00:01.00]one\n[00:02.00]two\n")!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf(1_000L, 2_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `plain words are kept as they are, untimed`() {
        val lyrics = LocalLyrics.parse("﻿\nfirst line\n\nsecond verse\n\n")!!
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("first line", "", "second verse"), lyrics.lines.map { it.text })
    }

    @Test
    fun `nothing but blanks is no lyrics`() {
        assertNull(LocalLyrics.parse(" \n\n"))
    }
}
