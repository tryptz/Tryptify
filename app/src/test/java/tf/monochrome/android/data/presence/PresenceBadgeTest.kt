package tf.monochrome.android.data.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which spectrum loop a track gets.
 *
 * The groove is the part worth guarding. A four-to-the-floor pump under a
 * jungle track is wrong in a way anyone who listens to jungle would see at a
 * glance, and the matching is keyword-based over a 771-node graph — exactly the
 * kind of rule that quietly stops covering things.
 */
class PresenceBadgeTest {

    @Test
    fun `dance genres pump on every beat`() {
        for (id in listOf("house", "deep-house", "techno", "trance", "disco", "dubstep")) {
            assertEquals(id, "four", PresenceBadge.groove(listOf(id)))
        }
    }

    @Test
    fun `broken beats are not four to the floor`() {
        for (id in listOf("drum-and-bass", "jungle", "breakbeat", "neurofunk", "liquid-dnb")) {
            assertEquals(id, "dnb", PresenceBadge.groove(listOf(id)))
        }
    }

    @Test
    fun `hard dance beats hard rock to the hard keyword`() {
        // "hardstyle" and "hard rock" both contain "hard", and they are not the
        // same rhythm. Order in the rule list is what keeps them apart.
        assertEquals("hard", PresenceBadge.groove(listOf("hardstyle")))
        assertEquals("hard", PresenceBadge.groove(listOf("uptempo-hardcore")))
        assertEquals("backbeat", PresenceBadge.groove(listOf("hard-rock")))
    }

    @Test
    fun `rap and trap are told apart`() {
        assertEquals("boombap", PresenceBadge.groove(listOf("hip-hop")))
        assertEquals("boombap", PresenceBadge.groove(listOf("conscious-rap")))
        assertEquals("trap", PresenceBadge.groove(listOf("trap")))
        assertEquals("trap", PresenceBadge.groove(listOf("drill")))
    }

    @Test
    fun `a leaf genre inherits its family's rhythm`() {
        // Lineage is passed root-last. "xtra-raw" means nothing on its own; it
        // is hardstyle four levels down, and matching only the leaf would send
        // most of the graph to the default.
        assertEquals("hard", PresenceBadge.groove(listOf("xtra-raw", "rawstyle", "hardstyle")))
        assertEquals("dnb", PresenceBadge.groove(listOf("minimal-dnb", "drum-and-bass")))
    }

    @Test
    fun `an unrecognised genre gets the plain backbeat`() {
        assertEquals(PresenceBadge.DEFAULT_GROOVE, PresenceBadge.groove(listOf("zzz-unknown")))
        assertEquals(PresenceBadge.DEFAULT_GROOVE, PresenceBadge.groove(emptyList()))
    }

    @Test
    fun `hue buckets follow the colour wheel`() {
        assertEquals(0, PresenceBadge.hueBucket(255, 0, 0))
        // Green sits a third of the way round, which with eight buckets is
        // bucket 3 (120 degrees / 45).
        assertEquals(3, PresenceBadge.hueBucket(0, 255, 0))
        assertEquals(5, PresenceBadge.hueBucket(0, 0, 255))
        // And it wraps rather than running off the end.
        assertTrue(PresenceBadge.hueBucket(255, 0, 40)!! in 0 until PresenceBadge.HUES)
    }

    @Test
    fun `a greyscale cover has no opinion about colour`() {
        // Plenty of sleeves are black, white or grey. Their hue is rounding
        // error, so it would pick a bucket at random and change on a re-extract.
        assertNull(PresenceBadge.hueBucket(20, 20, 20))
        assertNull(PresenceBadge.hueBucket(200, 200, 205))
        assertNull(PresenceBadge.hueBucket(0, 0, 0))
        assertNull(PresenceBadge.url(listOf("house"), Triple(30, 30, 30)))
    }

    @Test
    fun `every url the app can build is a file that exists`() {
        // The loops are drawn by a script and served from the repository, so a
        // groove renamed on one side and not the other is a 404 that only shows
        // up as a silently missing badge on somebody else's screen.
        val dir = File("../assets/presence")
        assertTrue("no loops at ${dir.absolutePath}", dir.isDirectory)
        val genres = listOf(
            "house", "hardstyle", "drum-and-bass", "hip-hop", "trap",
            "reggaeton", "ambient", "zzz-unknown",
        )
        for (genre in genres) {
            for (hue in 0 until PresenceBadge.HUES) {
                val url = PresenceBadge.url(listOf(genre), Triple(255, 40, 40))
                assertNotNull(genre, url)
            }
            val groove = PresenceBadge.groove(listOf(genre))
            for (hue in 0 until PresenceBadge.HUES) {
                val file = File(dir, "spectrum-$groove-$hue.webp")
                assertTrue("missing ${file.name}", file.isFile)
            }
        }
    }
}
