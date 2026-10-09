package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Release radar is only worth showing if it is right about "new" and right
 * about "by this artist". These pin both, and the NEW badge.
 */
class ReleaseRadarTest {

    private val today = LocalDate.of(2026, 10, 9)
    private var nextId = 1L

    private fun album(title: String, daysAgo: Long?, credit: String? = null, type: String = "ALBUM") = Album(
        id = nextId++,
        title = title,
        artist = credit?.let { Artist(id = it.hashCode().toLong(), name = it) },
        releaseDate = daysAgo?.let { today.minusDays(it).toString() },
        type = type,
    )

    @Test
    fun `dates read from plain days and timestamps, never from a bare year`() {
        assertEquals(LocalDate.of(2025, 5, 17), ReleaseRadar.parseDay("2025-05-17"))
        assertEquals(LocalDate.of(2025, 5, 17), ReleaseRadar.parseDay("2025-05-17T00:00:00Z"))
        assertNull(ReleaseRadar.parseDay("2025"))
        assertNull(ReleaseRadar.parseDay(null))
        assertNull(ReleaseRadar.parseDay("not a date"))
    }

    @Test
    fun `only releases from the last sixty days, and none from the future`() {
        val picked = ReleaseRadar.select(
            mapOf(
                "PPK" to listOf(
                    album("Fresh", 2),
                    album("Edge", ReleaseRadar.WINDOW_DAYS.toLong()),
                    album("Old", ReleaseRadar.WINDOW_DAYS + 1L),
                    album("Pre-order", -5),
                    album("Undated", null),
                ),
            ),
            today,
        )
        assertEquals(listOf("Fresh", "Edge"), picked.map { it.title })
    }

    @Test
    fun `a compilation credited to someone else is not their new release`() {
        val picked = ReleaseRadar.select(
            mapOf(
                "Push" to listOf(
                    album("Universal Nation 2026", 3, credit = "Push"),
                    album("Trance Hits 2026", 3, credit = "Various Artists"),
                    album("Together", 5, credit = "Push & Ferry Corsten"),
                    album("Not Them", 5, credit = "Pushkin"),
                    album("From Their Own Page", 6, credit = null),
                ),
            ),
            today,
        )
        assertEquals(setOf("Universal Nation 2026", "Together", "From Their Own Page"), picked.map { it.title }.toSet())
    }

    @Test
    fun `editions of one release count once, under the newest date`() {
        val picked = ReleaseRadar.select(
            mapOf(
                "ATB" to listOf(
                    album("Ascension", 20),
                    album("Ascension (Deluxe Edition)", 4),
                    album("Ascension [Explicit]", 10),
                ),
            ),
            today,
        )
        assertEquals(1, picked.size)
        assertEquals(today.minusDays(4).toEpochDay(), picked.single().day)
    }

    @Test
    fun `newest first, across artists`() {
        val picked = ReleaseRadar.select(
            mapOf(
                "A" to listOf(album("A old", 30), album("A new", 1)),
                "B" to listOf(album("B mid", 7)),
            ),
            today,
        )
        assertEquals(listOf("A new", "B mid", "A old"), picked.map { it.title })
    }

    @Test
    fun `the row is capped`() {
        val many = (1..40).map { album("Single $it", it.toLong(), type = "SINGLE") }
        assertEquals(ReleaseRadar.LIMIT, ReleaseRadar.select(mapOf("X" to many), today).size)
    }

    @Test
    fun `NEW means out since what was last shown, or recent on a first visit`() {
        val release = RadarRelease(albumId = 1, title = "T", artist = "A", day = today.minusDays(3).toEpochDay())
        assertTrue(ReleaseRadar.isNew(release, seenThrough = null, today = today))
        assertTrue(ReleaseRadar.isNew(release, seenThrough = today.minusDays(4).toEpochDay(), today = today))
        assertFalse(ReleaseRadar.isNew(release, seenThrough = today.minusDays(3).toEpochDay(), today = today))

        val older = release.copy(day = today.minusDays(ReleaseRadar.FIRST_VISIT_NEW_DAYS + 1L).toEpochDay())
        assertFalse(ReleaseRadar.isNew(older, seenThrough = null, today = today))
    }
}
