package tf.monochrome.android.ui.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import tf.monochrome.android.data.db.dao.PlayMoment
import tf.monochrome.android.data.db.dao.PlayRow

/*
 * Everything the Stats screen shows, worked out from the plays themselves.
 *
 * Pure Kotlin on purpose: one place decides what a day, a session, a new
 * artist or a repeat play is, every chart reads the same answer, and the
 * arithmetic is tested without a device (StatsReportBuilderTest).
 *
 * "Time" is the sum of the tracks' lengths, as the old screen counted it. A
 * play records when it started and how long the track is, not how much of it
 * was heard.
 */

enum class QualityTier { HI_RES, LOSSLESS, LOSSY, UNKNOWN }

/**
 * Where a play came from. A play of a local file is recorded as "local"; a
 * stream leaves its source empty, and which service it was is not recorded,
 * so this does not pretend to know. See [sourceOf] for plays from before.
 */
enum class PlaySource { STREAMED, LOCAL }

/** The listener's shape, from the hour they listen most. */
enum class Persona { NIGHT_OWL, EVENING, AFTERNOON, EARLY_BIRD }

enum class BucketUnit { DAY, WEEK, MONTH }

private val LOSSLESS_NAMES = setOf("LOSSLESS", "FLAC", "ALAC", "WAV", "AIFF", "APE")

/** Catalogue tiers ("HI_RES_LOSSLESS", "LOSSLESS", "HIGH") and local codec names ("FLAC", "MP3"). */
fun qualityTierOf(quality: String?): QualityTier {
    val q = quality?.trim()?.uppercase().orEmpty()
    return when {
        q.isEmpty() || q == "UNKNOWN" -> QualityTier.UNKNOWN
        "HI_RES" in q || "HIRES" in q -> QualityTier.HI_RES
        q in LOSSLESS_NAMES -> QualityTier.LOSSLESS
        else -> QualityTier.LOSSY
    }
}

/**
 * Where [row] came from. Plays recorded before local ones said so have no
 * source, and their id alone can't tell: a local file's id is the hash of
 * "local_<n>" ([localPlayIdOf]), positive about half the time, so going by
 * the sign counted over half of them as streamed. A play without a source is
 * local when its id is one of the library's ([localPlayIds]); else it goes by
 * the sign, which is right for a stream and for a local id that is negative.
 */
fun sourceOf(row: PlayRow, localPlayIds: Set<Long> = emptySet()): PlaySource = when {
    row.source.equals("local", ignoreCase = true) -> PlaySource.LOCAL
    !row.source.isNullOrBlank() -> PlaySource.STREAMED
    row.trackId in localPlayIds -> PlaySource.LOCAL
    row.trackId > 0 -> PlaySource.STREAMED
    else -> PlaySource.LOCAL
}

/**
 * The id a play of local track [localRowId] is recorded under: the hash of
 * its library id, "local_<n>" (LocalMediaRepository, UnifiedTrack.legacyId).
 */
fun localPlayIdOf(localRowId: Long): Long = "local_$localRowId".hashCode().toLong()

/** The days a report covers, and the same span just before it. */
data class StatsWindow(
    val from: Long,
    val until: Long,
    val startDate: LocalDate,
    val today: LocalDate,
    val days: Int,
    /** Null for all time, which has nothing before it to compare with. */
    val prevFrom: Long?,
) {
    val hasPrevious: Boolean get() = prevFrom != null
}

data class TimeBucket(val start: LocalDate, val days: Int, val seconds: Long, val plays: Int, val prevSeconds: Long?)

data class DiscoveryBucket(val start: LocalDate, val days: Int, val newPlays: Int, val knownPlays: Int, val newArtists: List<String>)

data class ArtistStat(
    val name: String,
    val plays: Int,
    val seconds: Long,
    val topTrack: String,
    val topTrackPlays: Int,
    val firstPlayed: Long?,
    val prevPlays: Int,
    /** First ever played inside this window. */
    val isNew: Boolean,
)

data class TrackStat(
    val title: String,
    val artist: String,
    val album: String?,
    val plays: Int,
    val seconds: Long,
    val lengthSeconds: Int,
    /** Its place in the previous window's ranking, or null when it was not played then. */
    val prevRank: Int?,
)

data class AlbumStat(val title: String, val artist: String, val plays: Int, val seconds: Long, val topTrack: String)

data class TierShare(val tier: QualityTier, val seconds: Long, val plays: Int)

data class SourceShare(val source: PlaySource, val seconds: Long, val plays: Int)

data class CalendarDay(val date: LocalDate, val seconds: Long, val plays: Int, val future: Boolean)

data class StreakStat(
    val current: Int,
    val longestInWindow: Int,
    val longestStart: LocalDate?,
    val daysListened: Int,
    val windowDays: Int,
    /** Five Monday-first weeks ending with this one. */
    val calendar: List<CalendarDay>,
    /** The days with no listening inside the window. */
    val daysOff: List<LocalDate>,
)

data class SessionBucket(val minMinutes: Int, val maxMinutes: Int?, val sessions: Int, val seconds: Long)

data class SessionStat(
    val count: Int,
    val seconds: Long,
    val longestSeconds: Long,
    val longestDate: LocalDate?,
    val buckets: List<SessionBucket>,
    val prevAverageSeconds: Long?,
) {
    val averageSeconds: Long get() = if (count == 0) 0 else seconds / count
}

data class MonthStat(val month: YearMonth, val seconds: Long, val plays: Int, val topArtist: String?, val daysElapsed: Int?, val paceSeconds: Long?)

data class VarietyBucket(val minPlays: Int, val maxPlays: Int?, val tracks: Int, val plays: Int, val examples: List<Pair<String, Int>>)

data class VarietyStat(val uniqueTracks: Int, val plays: Int, val buckets: List<VarietyBucket>) {
    val repeatPlays: Int get() = plays - uniqueTracks
}

data class LengthBucket(val minSeconds: Int, val maxSeconds: Int?, val plays: Int, val examples: List<Pair<String, Int>>)

data class LengthStat(val averageSeconds: Int, val buckets: List<LengthBucket>)

data class WeekdayStat(val day: DayOfWeek, val seconds: Long, val dates: List<LocalDate>, val secondsPerDate: List<Long>) {
    val averageSeconds: Long get() = if (dates.isEmpty()) 0 else seconds / dates.size
}

/** Seconds by weekday (Monday first) and hour. */
data class HeatStat(
    val seconds: List<List<Long>>,
    val peakDay: DayOfWeek,
    val peakHour: Int,
    /** Share of listening that starts between 9 pm and 3 am. */
    val nightShare: Float,
    val persona: Persona,
) {
    val total: Long get() = seconds.sumOf { it.sum() }
    fun at(day: DayOfWeek, hour: Int): Long = seconds[day.value - 1][hour]
}

data class StatsReport(
    val range: StatsRange,
    val window: StatsWindow,
    val plays: Int,
    val prevPlays: Int?,
    val seconds: Long,
    val prevSeconds: Long?,
    val timelineUnit: BucketUnit,
    val timeline: List<TimeBucket>,
    val heat: HeatStat?,
    val weekdays: List<WeekdayStat>,
    val uniqueArtists: Int,
    val prevUniqueArtists: Int?,
    val newArtists: List<String>,
    val newArtistPlays: Int,
    val oneOffArtists: Int,
    val discoveryUnit: BucketUnit,
    val discovery: List<DiscoveryBucket>,
    val topArtists: List<ArtistStat>,
    val topTracks: List<TrackStat>,
    val topAlbums: List<AlbumStat>,
    val albumsPlayed: Int,
    val quality: List<TierShare>,
    val sources: List<SourceShare>,
    val streak: StreakStat,
    val sessions: SessionStat,
    val months: List<MonthStat>,
    val variety: VarietyStat,
    val lengths: LengthStat,
) {
    val isEmpty: Boolean get() = plays == 0
    val dailyAverageSeconds: Long get() = seconds / window.days.coerceAtLeast(1)
    /** Whole percent change on the previous window, or null with nothing to compare. */
    val changePercent: Int? get() = prevSeconds?.takeIf { it > 0 }?.let { Math.round((seconds.toDouble() / it - 1) * 100).toInt() }
}

object StatsReportBuilder {
    /** A gap longer than this between plays starts a new session, as `sessionCount` counts them. */
    const val SESSION_GAP_MS = 30 * 60 * 1000L

    private const val TOP = 10

    fun window(range: StatsRange, now: Long, zone: ZoneId, firstPlayedAt: Long?): StatsWindow {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (val d = range.days) {
            null -> firstPlayedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.coerceAtMost(today) ?: today
            else -> today.minusDays((d - 1).toLong())
        }
        val days = (ChronoUnit.DAYS.between(start, today) + 1).toInt()
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevFrom = range.days?.let { start.minusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        return StatsWindow(from, until, start, today, days, prevFrom)
    }

    private class Play(val row: PlayRow, val date: LocalDate, val day: DayOfWeek, val hour: Int) {
        val seconds: Long get() = row.duration.coerceAtLeast(0).toLong()
        val trackKey: String get() = row.title + '\u0000' + row.artistName
    }

    fun build(
        range: StatsRange,
        window: StatsWindow,
        zone: ZoneId,
        rows: List<PlayRow>,
        prevRows: List<PlayRow>,
        firstPlays: Map<String, Long>,
        history: List<PlayMoment>,
        localPlayIds: Set<Long> = emptySet(),
    ): StatsReport {
        fun wrap(r: PlayRow): Play {
            val t = Instant.ofEpochMilli(r.playedAt).atZone(zone)
            return Play(r, t.toLocalDate(), t.dayOfWeek, t.hour)
        }
        val plays = rows.filter { it.playedAt >= window.from && it.playedAt < window.until }.sortedBy { it.playedAt }.map(::wrap)
        val prev = if (window.hasPrevious) prevRows.filter { it.playedAt >= window.prevFrom!! && it.playedAt < window.from }.map(::wrap) else emptyList()
        val shift = window.days.toLong()

        val timelineUnit = when {
            window.days <= 31 -> BucketUnit.DAY
            window.days <= 182 -> BucketUnit.WEEK
            else -> BucketUnit.MONTH
        }
        val timeline = buckets(window, timelineUnit).map { (start, end) ->
            val inside = plays.filter { it.date >= start && it.date < end }
            TimeBucket(
                start = start,
                days = ChronoUnit.DAYS.between(start, end).toInt(),
                seconds = inside.sumOf { it.seconds },
                plays = inside.size,
                prevSeconds = if (window.hasPrevious) prev.filter { it.date >= start.minusDays(shift) && it.date < end.minusDays(shift) }.sumOf { it.seconds } else null,
            )
        }

        // ── when ──
        val heatGrid = List(7) { LongArray(24) }
        plays.forEach { heatGrid[it.day.value - 1][it.hour] += it.seconds }
        val heat = if (plays.isEmpty()) null else {
            var bestD = 0
            var bestH = 0
            for (d in 0 until 7) for (h in 0 until 24) if (heatGrid[d][h] > heatGrid[bestD][bestH]) { bestD = d; bestH = h }
            val total = heatGrid.sumOf { it.sum() }.coerceAtLeast(1)
            val night = heatGrid.sumOf { row -> (0 until 24).filter { it >= 21 || it < 3 }.sumOf { row[it] } }
            HeatStat(
                seconds = heatGrid.map { it.toList() },
                peakDay = DayOfWeek.of(bestD + 1),
                peakHour = bestH,
                nightShare = night.toFloat() / total,
                persona = personaFor(bestH),
            )
        }
        val windowDates = (0 until window.days).map { window.startDate.plusDays(it.toLong()) }
        val secondsByDate = plays.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        val weekdays = DayOfWeek.entries.map { day ->
            val dates = windowDates.filter { it.dayOfWeek == day }
            val per = dates.map { secondsByDate[it] ?: 0L }
            WeekdayStat(day, per.sum(), dates, per)
        }

        // ── who ──
        val isNewArtist = { name: String -> (firstPlays[name] ?: Long.MAX_VALUE) >= window.from }
        val byArtist = plays.filter { it.row.artistName.isNotBlank() }.groupBy { it.row.artistName }
        val prevByArtist = prev.filter { it.row.artistName.isNotBlank() }.groupingBy { it.row.artistName }.eachCount()
        val topArtists = byArtist.map { (name, list) ->
            val top = list.groupingBy { it.row.title }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            ArtistStat(
                name = name, plays = list.size, seconds = list.sumOf { it.seconds },
                topTrack = top?.key.orEmpty(), topTrackPlays = top?.value ?: 0,
                firstPlayed = firstPlays[name], prevPlays = prevByArtist[name] ?: 0, isNew = isNewArtist(name),
            )
        }.sortedWith(compareByDescending<ArtistStat> { it.plays }.thenByDescending { it.seconds }.thenBy { it.name })
        val newArtists = byArtist.keys.filter(isNewArtist).sortedBy { firstPlays[it] ?: Long.MAX_VALUE }

        val discoveryUnit = when {
            window.days <= 7 -> BucketUnit.DAY
            window.days <= 90 -> BucketUnit.WEEK
            else -> BucketUnit.MONTH
        }
        val discovery = buckets(window, discoveryUnit).map { (start, end) ->
            val inside = plays.filter { it.date >= start && it.date < end && it.row.artistName.isNotBlank() }
            val fresh = inside.count { isNewArtist(it.row.artistName) }
            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = end.atStartOfDay(zone).toInstant().toEpochMilli()
            DiscoveryBucket(
                start = start, days = ChronoUnit.DAYS.between(start, end).toInt(),
                newPlays = fresh, knownPlays = inside.size - fresh,
                newArtists = newArtists.filter { (firstPlays[it] ?: -1) in startMs until endMs },
            )
        }

        // ── what ──
        val prevRanking = prev.groupingBy { it.trackKey }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapIndexed { i, e -> e.key to i + 1 }.toMap()
        val byTrack = plays.groupBy { it.trackKey }
        val tracks = byTrack.values.map { list ->
            val r = list.first().row
            TrackStat(
                title = r.title, artist = r.artistName, album = r.albumTitle?.takeIf { it.isNotBlank() },
                plays = list.size, seconds = list.sumOf { it.seconds }, lengthSeconds = list.maxOf { it.row.duration },
                prevRank = prevRanking[list.first().trackKey],
            )
        }.sortedWith(compareByDescending<TrackStat> { it.plays }.thenByDescending { it.seconds }.thenBy { it.title })
        val byAlbum = plays.filter { !it.row.albumTitle.isNullOrBlank() }.groupBy { it.row.albumTitle!! + '\u0000' + it.row.artistName }
        val albums = byAlbum.values.map { list ->
            val top = list.groupingBy { it.row.title }.eachCount().maxByOrNull { it.value }
            AlbumStat(list.first().row.albumTitle!!, list.first().row.artistName, list.size, list.sumOf { it.seconds }, top?.key.orEmpty())
        }.sortedWith(compareByDescending<AlbumStat> { it.plays }.thenBy { it.title })

        // ── how ──
        val quality = QualityTier.entries.mapNotNull { tier ->
            val inside = plays.filter { qualityTierOf(it.row.audioQuality) == tier }
            if (inside.isEmpty()) null else TierShare(tier, inside.sumOf { it.seconds }, inside.size)
        }
        val sources = PlaySource.entries.mapNotNull { src ->
            val inside = plays.filter { sourceOf(it.row, localPlayIds) == src }
            if (inside.isEmpty()) null else SourceShare(src, inside.sumOf { it.seconds }, inside.size)
        }

        // ── how often ──
        val historyDates = HashMap<LocalDate, Pair<Long, Int>>()
        history.forEach { m ->
            val d = Instant.ofEpochMilli(m.playedAt).atZone(zone).toLocalDate()
            val (s, n) = historyDates[d] ?: (0L to 0)
            historyDates[d] = (s + m.duration.coerceAtLeast(0)) to (n + 1)
        }
        plays.forEach { p -> if (p.date !in historyDates) historyDates[p.date] = (secondsByDate[p.date] ?: 0L) to plays.count { it.date == p.date } }
        val streak = streak(window, historyDates)
        val sessions = sessions(plays.map { it.row }, zone, prev.map { it.row }.takeIf { window.hasPrevious })
        val months = months(window.today, zone, history, plays)
        val variety = variety(byTrack.mapValues { it.value.size }, byTrack.mapValues { it.value.first().row.title })
        val lengths = lengths(plays.map { it.row }, tracks)

        return StatsReport(
            range = range, window = window,
            plays = plays.size, prevPlays = if (window.hasPrevious) prev.size else null,
            seconds = plays.sumOf { it.seconds }, prevSeconds = if (window.hasPrevious) prev.sumOf { it.seconds } else null,
            timelineUnit = timelineUnit, timeline = timeline,
            heat = heat, weekdays = weekdays,
            uniqueArtists = byArtist.size, prevUniqueArtists = if (window.hasPrevious) prevByArtist.size else null,
            newArtists = newArtists, newArtistPlays = plays.count { it.row.artistName.isNotBlank() && isNewArtist(it.row.artistName) },
            oneOffArtists = byArtist.count { it.value.size == 1 },
            discoveryUnit = discoveryUnit, discovery = discovery,
            topArtists = topArtists.take(TOP), topTracks = tracks.take(TOP), topAlbums = albums.take(TOP), albumsPlayed = byAlbum.size,
            quality = quality, sources = sources,
            streak = streak, sessions = sessions, months = months, variety = variety, lengths = lengths,
        )
    }

    fun personaFor(hour: Int): Persona = when {
        hour >= 21 || hour < 3 -> Persona.NIGHT_OWL
        hour >= 17 -> Persona.EVENING
        hour >= 12 -> Persona.AFTERNOON
        else -> Persona.EARLY_BIRD
    }

    /** [start, end) date spans covering the window in [unit] steps; the last may be short. */
    internal fun buckets(window: StatsWindow, unit: BucketUnit): List<Pair<LocalDate, LocalDate>> {
        val end = window.today.plusDays(1)
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var s = window.startDate
        while (s < end) {
            val next = when (unit) {
                BucketUnit.DAY -> s.plusDays(1)
                BucketUnit.WEEK -> s.plusDays(7)
                BucketUnit.MONTH -> s.withDayOfMonth(1).plusMonths(1)
            }.coerceAtMost(end)
            out += s to next
            s = next
        }
        return out
    }

    /**
     * Days in a row with listening, ending today, or yesterday while today has
     * nothing yet: a streak is not broken until the day is over.
     */
    fun currentStreak(listened: (LocalDate) -> Boolean, today: LocalDate): Int {
        var cursor = if (listened(today)) today else today.minusDays(1)
        var current = 0
        while (listened(cursor)) { current++; cursor = cursor.minusDays(1) }
        return current
    }

    internal fun streak(window: StatsWindow, byDate: Map<LocalDate, Pair<Long, Int>>): StreakStat {
        val listened = { d: LocalDate -> (byDate[d]?.second ?: 0) > 0 }
        val current = currentStreak(listened, window.today)
        var longest = 0
        var longestStart: LocalDate? = null
        var run = 0
        var runStart: LocalDate? = null
        val off = ArrayList<LocalDate>()
        var listenedDays = 0
        for (i in 0 until window.days) {
            val d = window.startDate.plusDays(i.toLong())
            if (listened(d)) {
                listenedDays++
                if (run == 0) runStart = d
                run++
                if (run > longest) { longest = run; longestStart = runStart }
            } else {
                run = 0
                off += d
            }
        }
        val weekEnd = window.today.plusDays((7 - window.today.dayOfWeek.value).toLong())
        val calendar = (34 downTo 0).map { back ->
            val d = weekEnd.minusDays(back.toLong())
            val (s, n) = byDate[d] ?: (0L to 0)
            CalendarDay(d, s, n, future = d > window.today)
        }
        return StreakStat(current, longest, longestStart, listenedDays, window.days, calendar, off)
    }

    private val SESSION_EDGES = listOf(0, 15, 30, 60, 120)

    internal fun sessions(rows: List<PlayRow>, zone: ZoneId, prevRows: List<PlayRow>?): SessionStat {
        fun spans(list: List<PlayRow>): List<Pair<Long, Long>> {
            val sorted = list.sortedBy { it.playedAt }
            val out = ArrayList<Pair<Long, Long>>()
            var start = -1L
            var end = -1L
            var last = Long.MIN_VALUE / 2
            for (r in sorted) {
                if (start < 0 || r.playedAt - last > SESSION_GAP_MS) {
                    if (start >= 0) out += start to end
                    start = r.playedAt
                    end = r.playedAt
                }
                end = maxOf(end, r.playedAt + r.duration.coerceAtLeast(0) * 1000L)
                last = r.playedAt
            }
            if (start >= 0) out += start to end
            return out
        }
        val s = spans(rows)
        val lengths = s.map { (a, b) -> (b - a) / 1000 }
        val buckets = SESSION_EDGES.mapIndexed { i, lo ->
            val hi = SESSION_EDGES.getOrNull(i + 1)
            val inside = lengths.filter { it >= lo * 60L && (hi == null || it < hi * 60L) }
            SessionBucket(lo, hi, inside.size, inside.sum())
        }
        val longestIdx = lengths.indices.maxByOrNull { lengths[it] }
        val prevAvg = prevRows?.let { p -> spans(p).takeIf { it.isNotEmpty() }?.let { sp -> sp.sumOf { (a, b) -> (b - a) / 1000 } / sp.size } }
        return SessionStat(
            count = s.size, seconds = lengths.sum(),
            longestSeconds = longestIdx?.let { lengths[it] } ?: 0,
            longestDate = longestIdx?.let { Instant.ofEpochMilli(s[it].first).atZone(zone).toLocalDate() },
            buckets = buckets, prevAverageSeconds = prevAvg,
        )
    }

    private fun months(today: LocalDate, zone: ZoneId, history: List<PlayMoment>, plays: List<Play>): List<MonthStat> {
        val now = YearMonth.from(today)
        val sec = HashMap<YearMonth, Long>()
        val cnt = HashMap<YearMonth, Int>()
        history.forEach { m ->
            val ym = YearMonth.from(Instant.ofEpochMilli(m.playedAt).atZone(zone))
            sec[ym] = (sec[ym] ?: 0) + m.duration.coerceAtLeast(0)
            cnt[ym] = (cnt[ym] ?: 0) + 1
        }
        val topArtist = plays.groupBy { YearMonth.from(it.date) }.mapValues { (_, v) ->
            v.filter { it.row.artistName.isNotBlank() }.groupingBy { it.row.artistName }.eachCount().maxByOrNull { it.value }?.key
        }
        return (11 downTo 0).map { back ->
            val ym = now.minusMonths(back.toLong())
            val s = sec[ym] ?: 0
            val current = ym == now
            MonthStat(
                month = ym, seconds = s, plays = cnt[ym] ?: 0, topArtist = topArtist[ym],
                daysElapsed = if (current) today.dayOfMonth else null,
                paceSeconds = if (current && today.dayOfMonth > 0) s * ym.lengthOfMonth() / today.dayOfMonth else null,
            )
        }
    }

    private val VARIETY_EDGES = listOf(1, 2, 5, 10)

    internal fun variety(playsPerTrack: Map<String, Int>, titles: Map<String, String>): VarietyStat {
        val buckets = VARIETY_EDGES.mapIndexed { i, lo ->
            val hi = VARIETY_EDGES.getOrNull(i + 1)?.minus(1)
            val inside = playsPerTrack.filter { it.value >= lo && (hi == null || it.value <= hi) }
            VarietyBucket(
                minPlays = lo, maxPlays = hi, tracks = inside.size, plays = inside.values.sum(),
                examples = inside.entries.sortedByDescending { it.value }.take(7).map { (titles[it.key] ?: it.key) to it.value },
            )
        }
        return VarietyStat(playsPerTrack.size, playsPerTrack.values.sum(), buckets)
    }

    private val LENGTH_EDGES = listOf(0, 120, 180, 240, 300, 420)

    private fun lengths(rows: List<PlayRow>, tracks: List<TrackStat>): LengthStat {
        val known = rows.filter { it.duration > 0 }
        val buckets = LENGTH_EDGES.mapIndexed { i, lo ->
            val hi = LENGTH_EDGES.getOrNull(i + 1)
            val inBucket = { s: Int -> s >= lo && (hi == null || s < hi) }
            LengthBucket(
                minSeconds = lo, maxSeconds = hi, plays = known.count { inBucket(it.duration) },
                examples = tracks.filter { inBucket(it.lengthSeconds) }.take(3).map { it.title to it.lengthSeconds },
            )
        }
        return LengthStat(if (known.isEmpty()) 0 else (known.sumOf { it.duration.toLong() } / known.size).toInt(), buckets)
    }
}
