package tf.monochrome.android.ui.stats

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.data.achievements.AchievementStore
import tf.monochrome.android.data.achievements.FeatureLog
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.ui.settings.settingsPageIds

/**
 * Where every achievement stands, for the whole app, and the ones just
 * completed, waiting for their unlocked pill.
 *
 * The one place an achievement is stamped complete: the Stats screen reads
 * [states] rather than working them out itself, so a goal finished while the
 * Stats screen is open is still announced, and one finished with no screen
 * watching is announced the next time the app is up ([pending] is kept until
 * the pill has shown it).
 *
 * Checked again on each play, on each entry in the achievement log (a
 * settings page opened, a plugin added, a country heard) and on each saved
 * Trypt Mania run, off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AchievementWatcher @Inject constructor(
    private val playEventDao: PlayEventDao,
    private val achievementStore: AchievementStore,
    private val glyphAttempts: GlyphAttemptStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pending = MutableStateFlow<List<Achievement>>(emptyList())

    /** Completed and not yet shown, oldest first; the pill takes them one at a time. */
    val pending: StateFlow<List<Achievement>> = _pending.asStateFlow()

    private val _tryptMania = MutableStateFlow(TryptManiaStats.EMPTY)

    /** Trypt Mania's saved runs, summed up: the Stats card, read here once for both. */
    val tryptMania: StateFlow<TryptManiaStats> = _tryptMania.asStateFlow()

    val states: StateFlow<List<AchievementState>> =
        combine(
            playEventDao.playCount().distinctUntilChanged(),
            achievementStore.log,
            glyphAttempts.revision,
        ) { _, log, revision -> log to revision }
            .mapLatest { (log, revision) -> evaluate(log, revision) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The attempt store's revision [tryptMania] was last summed at, so a play
     * counted anywhere else in the app does not read every chart's history
     * again: only a saved or cleared run changes the sum. Written only from
     * [evaluate], which runs one at a time.
     */
    private var tryptManiaRevision: Long? = null

    /** The pill has shown [achievement]. */
    fun shown(achievement: Achievement) {
        _pending.update { it - achievement }
    }

    private suspend fun evaluate(log: FeatureLog, glyphRevision: Long): List<AchievementState> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val month = StatsReportBuilder.window(StatsRange.Month, now, zone, null)
        val newArtists = playEventDao.artistFirstPlays().count { it.firstPlayed >= month.from }
        val since = month.today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = playEventDao.momentsSince(since).mapTo(HashSet()) { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }
        val facts = AchievementFacts(
            newArtistsLast30Days = newArtists,
            currentStreak = StatsReportBuilder.currentStreak({ it in days }, month.today),
            hiResSeconds = playEventDao.hiResSeconds(),
            playsAfterMidnight = playEventDao.playsAfterMidnight(),
            log = log,
            tryptMania = tryptManiaAt(glyphRevision),
        )
        val states = Achievements.evaluate(facts, settingsPageIds, SnapinType.availableTypes().map { it.name }, now)
        val fresh = Achievements.newlyUnlocked(states, log.unlockedAt)
        val announcing = achievementStore.announcing.first()
        if (fresh.isNotEmpty()) achievementStore.markUnlocked(fresh)
        if (!announcing) {
            // The first check since the pill arrived: what was already done
            // is stamped quietly, and only what is finished from now on shows.
            achievementStore.startAnnouncing()
        } else if (fresh.isNotEmpty()) {
            val done = Achievement.entries.filter { it.name in fresh }
            _pending.update { waiting -> waiting + done.filter { it !in waiting } }
        }
        return states
    }

    /** The runs summed up as of [revision]: read again only when it has moved. */
    private suspend fun tryptManiaAt(revision: Long): TryptManiaStats {
        if (revision == tryptManiaRevision) return _tryptMania.value
        val stats = TryptManiaStats.from(glyphAttempts.recentRuns(Int.MAX_VALUE))
        _tryptMania.value = stats
        tryptManiaRevision = revision
        return stats
    }
}
