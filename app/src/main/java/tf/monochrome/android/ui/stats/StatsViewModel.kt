package tf.monochrome.android.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.db.dao.DayAggregate
import tf.monochrome.android.data.db.dao.HourAggregate
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.QualityAggregate
import tf.monochrome.android.data.db.dao.SourceAggregate
import tf.monochrome.android.data.db.dao.TopAlbumAggregate
import tf.monochrome.android.data.db.dao.TopArtistAggregate
import tf.monochrome.android.data.db.dao.TopTrackAggregate
import tf.monochrome.android.data.db.dao.WeekdayAggregate
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import javax.inject.Inject

enum class StatsRange(val label: String, val days: Int?) {
    Week("7d", 7),
    Month("30d", 30),
    Quarter("90d", 90),
    Year("1y", 365),
    AllTime("All", null)
}

data class StatsUiState(
    val range: StatsRange = StatsRange.Month,
    val totalPlays: Int = 0,
    val totalSeconds: Long = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val sessionCount: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val topTracks: List<TopTrackAggregate> = emptyList(),
    val topArtists: List<TopArtistAggregate> = emptyList(),
    val topAlbums: List<TopAlbumAggregate> = emptyList(),
    val playsByDay: List<DayAggregate> = emptyList(),
    val playsByHour: List<HourAggregate> = emptyList(),
    val playsByWeekday: List<WeekdayAggregate> = emptyList(),
    val playsByQuality: List<QualityAggregate> = emptyList(),
    val playsBySource: List<SourceAggregate> = emptyList()
) {
    val isEmpty: Boolean get() = totalPlays == 0
    val peakHour: Int? get() = playsByHour.maxByOrNull { it.playCount }?.hour
    val peakWeekday: Int? get() = playsByWeekday.maxByOrNull { it.playCount }?.weekday
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playEventDao: PlayEventDao,
    private val supabaseSync: SupabaseSyncRepository,
    private val authManager: SupabaseAuthManager,
) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.Month)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    init {
        // Kick an initial refresh so the newly-redesigned Stats screen picks
        // up plays made on other devices. No-op if the user isn't signed in.
        viewModelScope.launch { refreshFromCloudInternal(_range.value) }
    }

    private fun sinceFor(r: StatsRange): Long =
        r.days?.let { System.currentTimeMillis() - it.toLong() * 86_400_000L } ?: 0L

    val uiState: StateFlow<StatsUiState> = _range.flatMapLatest { r ->
        val since = sinceFor(r)
        combine(
            listOf(
                playEventDao.totalPlays(since),
                playEventDao.totalListeningSeconds(since),
                playEventDao.uniqueTracks(since),
                playEventDao.uniqueArtists(since),
                playEventDao.uniqueAlbums(since),
                playEventDao.sessionCount(since),
                playEventDao.topTracks(since, 20),
                playEventDao.topArtists(since, 20),
                playEventDao.topAlbums(since, 20),
                playEventDao.playsPerDay(since),
                playEventDao.playsByHour(since),
                playEventDao.playsByWeekday(since),
                playEventDao.playsByQuality(since),
                playEventDao.playsBySource(since)
            )
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val days = values[9] as List<DayAggregate>
            val (curStreak, maxStreak) = streaks(days)
            StatsUiState(
                range = r,
                totalPlays = values[0] as Int,
                totalSeconds = values[1] as Long,
                uniqueTracks = values[2] as Int,
                uniqueArtists = values[3] as Int,
                uniqueAlbums = values[4] as Int,
                sessionCount = values[5] as Int,
                currentStreakDays = curStreak,
                longestStreakDays = maxStreak,
                topTracks = values[6] as List<TopTrackAggregate>,
                topArtists = values[7] as List<TopArtistAggregate>,
                topAlbums = values[8] as List<TopAlbumAggregate>,
                playsByDay = days,
                playsByHour = values[10] as List<HourAggregate>,
                playsByWeekday = values[11] as List<WeekdayAggregate>,
                playsByQuality = values[12] as List<QualityAggregate>,
                playsBySource = values[13] as List<SourceAggregate>
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setRange(r: StatsRange) {
        _range.value = r
        viewModelScope.launch { refreshFromCloudInternal(r) }
    }

    /** User-triggered refresh (pull-to-refresh gesture). */
    fun refresh() {
        viewModelScope.launch { refreshFromCloudInternal(_range.value) }
    }

    private suspend fun refreshFromCloudInternal(r: StatsRange) {
        if (authManager.userProfile.value == null) return
        _isRefreshing.value = true
        try {
            // Pad the range by 1 day so near-cutoff plays on other devices
            // aren't missed due to clock skew.
            val since = sinceFor(r).let { if (it > 0) it - 86_400_000L else 0L }
            supabaseSync.pullPlayEventsSince(since)
            _lastSyncedAt.value = System.currentTimeMillis()
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Returns (currentStreak, longestStreak) in days from a list of
     * (dayEpoch -> playCount). Assumes the list is sorted ascending by day.
     * "Current streak" = chain ending today (or yesterday); 0 otherwise.
     */
    private fun streaks(days: List<DayAggregate>): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        val epochs = days.map { it.dayEpoch }.toSortedSet()
        var longest = 0
        var running = 0
        var prev: Long = Long.MIN_VALUE
        for (d in epochs) {
            running = if (d == prev + 1) running + 1 else 1
            if (running > longest) longest = running
            prev = d
        }
        val today = System.currentTimeMillis() / 86_400_000L
        var cur = 0
        var cursor = today
        while (cursor in epochs) {
            cur += 1
            cursor -= 1
        }
        if (cur == 0 && (today - 1) in epochs) {
            cursor = today - 1
            while (cursor in epochs) {
                cur += 1
                cursor -= 1
            }
        }
        return cur to longest
    }
}
