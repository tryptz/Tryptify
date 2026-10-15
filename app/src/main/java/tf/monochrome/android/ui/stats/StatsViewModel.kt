package tf.monochrome.android.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.R
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.data.achievements.AchievementStore
import tf.monochrome.android.data.achievements.FeatureLog
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import tf.monochrome.android.ui.settings.settingsPageIds

enum class StatsRange(val label: String, val days: Int?) {
    Week("7d", 7),
    Month("30d", 30),
    Quarter("90d", 90),
    Year("1y", 365),
    AllTime("All", null)
}

/**
 * The Stats screen and its chart pages.
 *
 * The report is rebuilt whenever the range changes or a play is added or
 * removed (the play count is the signal), off the main thread. A chart page
 * opens with the range its Stats page had, through the route's "range"
 * argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playEventDao: PlayEventDao,
    private val localMediaDao: tf.monochrome.android.data.local.db.LocalMediaDao,
    private val supabaseSync: SupabaseSyncRepository,
    private val authManager: SupabaseAuthManager,
    private val achievementStore: AchievementStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _range = MutableStateFlow(
        savedStateHandle.get<String>("range")?.let { name -> StatsRange.entries.firstOrNull { it.name == name } } ?: StatsRange.Month,
    )
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    /** One-shot messages for the Stats screen (e.g. "sign in to sync"). */
    private val _messages = MutableSharedFlow<tf.monochrome.android.ui.components.UiText>(extraBufferCapacity = 2)
    val messages: SharedFlow<tf.monochrome.android.ui.components.UiText> = _messages.asSharedFlow()

    /**
     * The report's refresh signal: the number of plays, when it changes. Room
     * re-emits the count after any write to play_events, the cloud id stamped
     * on a play just after it is written included, so each play rebuilt the
     * whole report twice, and a sync once per row it touched.
     */
    private val plays = playEventDao.playCount().distinctUntilChanged()

    /** Null until the first report is ready. */
    val report: StateFlow<StatsReport?> = combine(_range, plays) { r, _ -> r }
        .mapLatest { r -> withContext(Dispatchers.Default) { loadReport(r) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val achievements: StateFlow<List<AchievementState>> = combine(plays, achievementStore.log) { _, log -> log }
        .mapLatest { log -> withContext(Dispatchers.Default) { loadAchievements(log) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Pick up plays made on other devices. A no-op when signed out.
        viewModelScope.launch { refreshFromCloudInternal(_range.value) }
    }

    fun setRange(r: StatsRange) {
        if (r == _range.value) return
        _range.value = r
        viewModelScope.launch { refreshFromCloudInternal(r) }
    }

    /** User-triggered refresh (pull-to-refresh gesture). */
    fun refresh() {
        if (authManager.userProfile.value == null) {
            // Explain the no-op instead of letting the spinner vanish silently.
            _messages.tryEmit(tf.monochrome.android.ui.components.UiText.Res(R.string.stats_sign_in_to_sync))
            return
        }
        viewModelScope.launch { refreshFromCloudInternal(_range.value) }
    }

    private suspend fun loadReport(range: StatsRange): StatsReport {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val window = StatsReportBuilder.window(range, now, zone, playEventDao.firstPlayedAt())
        val rows = playEventDao.playsBetween(window.from, window.until)
        val prev = window.prevFrom?.let { playEventDao.playsBetween(it, window.from) }.orEmpty()
        val firstPlays = playEventDao.artistFirstPlays().associate { it.name to it.firstPlayed }
        // Thirteen months back covers the twelve-month trend and any streak
        // the calendar can show.
        val historyFrom = minOf(window.from, window.today.minusMonths(13).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val history = playEventDao.momentsSince(historyFrom)
        // The library's tracks as their plays are recorded, so plays from
        // before local ones said so are still counted as local (sourceOf).
        val localPlayIds = localMediaDao.allTrackIds().mapTo(HashSet(), ::localPlayIdOf)
        return StatsReportBuilder.build(range, window, zone, rows, prev, firstPlays, history, localPlayIds)
    }

    private suspend fun loadAchievements(log: FeatureLog): List<AchievementState> {
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
        )
        val states = Achievements.evaluate(facts, settingsPageIds, SnapinType.availableTypes().map { it.name }, now)
        val fresh = Achievements.newlyUnlocked(states, log.unlockedAt)
        if (fresh.isNotEmpty()) achievementStore.markUnlocked(fresh)
        return states
    }

    private suspend fun refreshFromCloudInternal(r: StatsRange) {
        if (authManager.userProfile.value == null) return
        _isRefreshing.value = true
        try {
            // Pad the range by 1 day so near-cutoff plays on other devices
            // aren't missed due to clock skew.
            val since = r.days?.let { System.currentTimeMillis() - (it + 1).toLong() * 86_400_000L } ?: 0L
            // Only claim "Synced just now" when the pull actually succeeded —
            // an offline/errored pull used to still stamp the sync time,
            // falsely telling the user their cross-device history was current.
            val pulled = supabaseSync.pullPlayEventsSince(since)
            if (pulled != null) {
                _lastSyncedAt.value = System.currentTimeMillis()
            }
        } finally {
            _isRefreshing.value = false
        }
    }
}
