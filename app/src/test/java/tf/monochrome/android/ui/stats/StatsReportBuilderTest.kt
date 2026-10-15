package tf.monochrome.android.ui.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.db.dao.PlayMoment
import tf.monochrome.android.data.db.dao.PlayRow

class StatsReportBuilderTest {

    private val zone = ZoneId.of("Europe/Berlin")
    // Wednesday 14 October 2026, 20:00 local.
    private val now = LocalDateTime.of(2026, 10, 14, 20, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun play(
        date: LocalDate, hour: Int, minute: Int = 0,
        title: String = "Waves", artist: String = "DJ Satomi", seconds: Int = 240,
        quality: String? = "LOSSLESS", trackId: Long = 1, album: String? = "Greatest Hits",
    ) = PlayRow(at(date, hour, minute), trackId, title, artist, album, seconds, quality, null)

    private val today = LocalDate.of(2026, 10, 14)

    private fun report(
        rows: List<PlayRow>,
        prev: List<PlayRow> = emptyList(),
        range: StatsRange = StatsRange.Month,
        firstPlays: Map<String, Long>? = null,
    ): StatsReport {
        val all = rows + prev
        val window = StatsReportBuilder.window(range, now, zone, all.minOfOrNull { it.playedAt })
        val first = firstPlays ?: all.groupBy { it.artistName }.mapValues { (_, v) -> v.minOf { it.playedAt } }
        return StatsReportBuilder.build(range, window, zone, rows, prev, first, all.map { PlayMoment(it.playedAt, it.duration) })
    }

    @Test
    fun `window is local days ending today, with the same span before it`() {
        val w = StatsReportBuilder.window(StatsRange.Week, now, zone, null)
        assertEquals(LocalDate.of(2026, 10, 8), w.startDate)
        assertEquals(7, w.days)
        assertEquals(at(LocalDate.of(2026, 10, 8), 0), w.from)
        assertEquals(at(LocalDate.of(2026, 10, 1), 0), w.prevFrom)
        val all = StatsReportBuilder.window(StatsRange.AllTime, now, zone, at(LocalDate.of(2026, 9, 1), 12))
        assertEquals(44, all.days)
        assertNull(all.prevFrom)
    }

    @Test
    fun `totals and change against the previous period`() {
        val r = report(
            rows = listOf(play(today, 9), play(today.minusDays(1), 9, seconds = 360)),
            prev = listOf(play(today.minusDays(40), 9, seconds = 300)),
        )
        assertEquals(2, r.plays)
        assertEquals(600L, r.seconds)
        assertEquals(300L, r.prevSeconds)
        assertEquals(100, r.changePercent)
        assertEquals(BucketUnit.DAY, r.timelineUnit)
        assertEquals(30, r.timeline.size)
        assertEquals(240L, r.timeline.last().seconds)
    }

    @Test
    fun `plays outside the window are ignored`() {
        val r = report(rows = listOf(play(today.minusDays(45), 9), play(today, 9)))
        assertEquals(1, r.plays)
    }

    @Test
    fun `peak hour decides the persona`() {
        val r = report(listOf(play(today, 22), play(today, 22, 30), play(today, 8)))
        assertEquals(DayOfWeek.WEDNESDAY, r.heat!!.peakDay)
        assertEquals(22, r.heat!!.peakHour)
        assertEquals(Persona.NIGHT_OWL, r.heat!!.persona)
        assertEquals(2f / 3f, r.heat!!.nightShare, 0.001f)
        assertEquals(Persona.EARLY_BIRD, StatsReportBuilder.personaFor(7))
        assertEquals(Persona.EVENING, StatsReportBuilder.personaFor(18))
    }

    @Test
    fun `an artist is new when first played inside the window`() {
        val old = at(today.minusDays(200), 9)
        val r = report(
            rows = listOf(play(today, 9, artist = "Aquasky"), play(today, 10, artist = "BOUNC3"), play(today, 11, artist = "BOUNC3")),
            firstPlays = mapOf("Aquasky" to old, "BOUNC3" to at(today, 10)),
        )
        assertEquals(listOf("BOUNC3"), r.newArtists)
        assertEquals(2, r.newArtistPlays)
        val week = r.discovery.last()
        assertEquals(2, week.newPlays)
        assertEquals(1, week.knownPlays)
        assertEquals(listOf("BOUNC3"), week.newArtists)
        assertTrue(r.topArtists.first { it.name == "BOUNC3" }.isNew)
        assertFalse(r.topArtists.first { it.name == "Aquasky" }.isNew)
    }

    @Test
    fun `top tracks rank by plays and remember last period's place`() {
        val r = report(
            rows = List(3) { play(today, 9 + it, title = "Waves") } + play(today, 15, title = "Jolene", artist = "Dolly Parton"),
            prev = listOf(play(today.minusDays(35), 9, title = "Jolene", artist = "Dolly Parton")),
        )
        assertEquals(listOf("Waves", "Jolene"), r.topTracks.map { it.title })
        assertNull(r.topTracks[0].prevRank)
        assertEquals(1, r.topTracks[1].prevRank)
    }

    @Test
    fun `quality tiers read catalogue and codec names`() {
        assertEquals(QualityTier.HI_RES, qualityTierOf("HI_RES_LOSSLESS"))
        assertEquals(QualityTier.LOSSLESS, qualityTierOf("LOSSLESS"))
        assertEquals(QualityTier.LOSSLESS, qualityTierOf("FLAC"))
        assertEquals(QualityTier.LOSSY, qualityTierOf("HIGH"))
        assertEquals(QualityTier.LOSSY, qualityTierOf("MP3"))
        assertEquals(QualityTier.UNKNOWN, qualityTierOf(null))
        assertEquals(QualityTier.UNKNOWN, qualityTierOf("Unknown"))
    }

    @Test
    fun `source falls back to the track id`() {
        val streamed = play(today, 9, trackId = 42)
        val local = play(today, 9, trackId = -7)
        assertEquals(PlaySource.STREAMED, sourceOf(streamed))
        assertEquals(PlaySource.LOCAL, sourceOf(local))
        assertEquals(PlaySource.LOCAL, sourceOf(streamed.copy(source = "local")))
        assertEquals(PlaySource.STREAMED, sourceOf(local.copy(source = "tidal")))
    }

    @Test
    fun `a local play with a positive id is local`() {
        // "local_42" hashes to a positive id, which the sign alone took for a
        // stream: over half of a library's plays were counted as streamed.
        val id = localPlayIdOf(42)
        assertTrue(id > 0)
        val old = play(today, 9, trackId = id)
        assertEquals(PlaySource.STREAMED, sourceOf(old))
        assertEquals(PlaySource.LOCAL, sourceOf(old, localPlayIds = setOf(id)))
        // Recorded since: the play says so, library or not.
        assertEquals(PlaySource.LOCAL, sourceOf(old.copy(source = "local")))
        // A stream is still a stream, and a recorded source wins over the id.
        assertEquals(PlaySource.STREAMED, sourceOf(play(today, 9, trackId = 58990486), setOf(id)))
    }

    @Test
    fun `the report counts the library's plays as local`() {
        val ids = (1L..200L).map(::localPlayIdOf)
        // Before 20:00, the test's "now".
        val rows = ids.mapIndexed { i, id -> play(today, i % 20, trackId = id) }
        val report = StatsReportBuilder.build(
            StatsRange.Month,
            StatsReportBuilder.window(StatsRange.Month, now, zone, rows.minOf { it.playedAt }),
            zone, rows, emptyList(), emptyMap(), rows.map { PlayMoment(it.playedAt, it.duration) },
            localPlayIds = ids.toSet(),
        )
        assertEquals(listOf(PlaySource.LOCAL), report.sources.map { it.source })
        assertEquals(200, report.sources.single().plays)
    }

    @Test
    fun `a local play's id is the one the player records`() {
        val local = tf.monochrome.android.domain.model.UnifiedTrack(
            id = "local_42",
            title = "Waves",
            durationSeconds = 240,
            artistName = "DJ Satomi",
            source = tf.monochrome.android.domain.model.PlaybackSource.LocalFile(
                "/music/waves.flac", tf.monochrome.android.domain.model.AudioCodec.FLAC, 44_100, 16,
            ),
            sourceType = tf.monochrome.android.domain.model.SourceType.LOCAL,
        )
        assertEquals(local.legacyId, localPlayIdOf(42))
        assertEquals(local.toLegacyTrack().id, localPlayIdOf(42))
    }

    @Test
    fun `streak counts back from today and finds the longest run`() {
        // Listened Oct 1-5 (5 days), not Oct 6, then Oct 7-14 (8 days).
        val days = (1..5).map { LocalDate.of(2026, 10, it) } + (7..14).map { LocalDate.of(2026, 10, it) }
        val r = report(days.map { play(it, 12) })
        assertEquals(8, r.streak.current)
        assertEquals(8, r.streak.longestInWindow)
        assertEquals(LocalDate.of(2026, 10, 7), r.streak.longestStart)
        assertEquals(13, r.streak.daysListened)
        assertTrue(LocalDate.of(2026, 10, 6) in r.streak.daysOff)
        assertEquals(35, r.streak.calendar.size)
        assertEquals(DayOfWeek.MONDAY, r.streak.calendar.first().date.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, r.streak.calendar.last().date.dayOfWeek)
        assertTrue(r.streak.calendar.last().future)
    }

    @Test
    fun `a streak still counts when today has nothing yet`() {
        val r = report(listOf(play(today.minusDays(1), 12), play(today.minusDays(2), 12)))
        assertEquals(2, r.streak.current)
    }

    @Test
    fun `sessions split on a half-hour gap and land in length buckets`() {
        val rows = listOf(
            play(today, 9, 0, seconds = 600), play(today, 9, 10, seconds = 600), // 9:00-9:20, one 20-minute session
            play(today, 12, 0, seconds = 300), // a 5-minute session
        )
        val r = report(rows)
        assertEquals(2, r.sessions.count)
        assertEquals(1200L, r.sessions.longestSeconds)
        assertEquals(1, r.sessions.buckets[0].sessions) // under 15 min
        assertEquals(1, r.sessions.buckets[1].sessions) // 15 to 30 min
    }

    @Test
    fun `variety buckets tracks by how often they were played`() {
        val rows = List(10) { play(today, 9, it, title = "Waves") } + play(today, 15, title = "Jolene") + List(3) { play(today, 16, it, title = "2003") }
        val v = report(rows).variety
        assertEquals(3, v.uniqueTracks)
        assertEquals(14, v.plays)
        assertEquals(11, v.repeatPlays)
        assertEquals(listOf(1, 1, 0, 1), v.buckets.map { it.tracks })
        assertEquals("Waves" to 10, v.buckets[3].examples.single())
    }

    @Test
    fun `months end with this month and project its pace`() {
        val r = report(listOf(play(today, 12, seconds = 1400)))
        val oct = r.months.last()
        assertEquals(12, r.months.size)
        assertEquals(14, oct.daysElapsed)
        assertEquals(1400L * 31 / 14, oct.paceSeconds)
        assertNull(r.months.first().paceSeconds)
    }

    @Test
    fun `weekday averages count days with no listening`() {
        val r = report(listOf(play(today, 12, seconds = 600)), range = StatsRange.Week)
        val wed = r.weekdays.first { it.day == DayOfWeek.WEDNESDAY }
        assertEquals(1, wed.dates.size)
        assertEquals(600L, wed.averageSeconds)
        assertEquals(0L, r.weekdays.first { it.day == DayOfWeek.MONDAY }.averageSeconds)
    }

    @Test
    fun `a year groups the timeline by month`() {
        val r = report(listOf(play(today, 12)), range = StatsRange.Year)
        assertEquals(BucketUnit.MONTH, r.timelineUnit)
        assertEquals(BucketUnit.MONTH, r.discoveryUnit)
        assertEquals(240L, r.timeline.sumOf { it.seconds })
    }

    @Test
    fun `an empty window is empty everywhere`() {
        val r = report(emptyList())
        assertTrue(r.isEmpty)
        assertNull(r.heat)
        assertTrue(r.topArtists.isEmpty())
        assertEquals(0, r.sessions.count)
    }
}
