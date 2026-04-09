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
import tf.monochrome.android.data.db.dao.DayAggregate
import tf.monochrome.android.data.db.dao.HourAggregate
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.db.dao.QualityAggregate
import tf.monochrome.android.data.db.dao.SourceAggregate
import tf.monochrome.android.data.db.dao.TopAlbumAggregate
import tf.monochrome.android.data.db.dao.TopArtistAggregate
import tf.monochrome.android.data.db.dao.TopTrackAggregate
import tf.monochrome.android.data.db.dao.WeekdayAggregate
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
    val topTracks: List<TopTrackAggregate> = emptyList(),
    val topArtists: List<TopArtistAggregate> = emptyList(),
    val topAlbums: List<TopAlbumAggregate> = emptyList(),
    val playsByDay: List<DayAggregate> = emptyList(),
    val playsByHour: List<HourAggregate> = emptyList(),
    val playsByWeekday: List<WeekdayAggregate> = emptyList(),
    val playsByQuality: List<QualityAggregate> = emptyList(),
    val playsBySource: List<SourceAggregate> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playEventDao: PlayEventDao
) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.Month)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

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
            StatsUiState(
                range = r,
                totalPlays = values[0] as Int,
                totalSeconds = values[1] as Long,
                uniqueTracks = values[2] as Int,
                uniqueArtists = values[3] as Int,
                uniqueAlbums = values[4] as Int,
                topTracks = values[5] as List<TopTrackAggregate>,
                topArtists = values[6] as List<TopArtistAggregate>,
                topAlbums = values[7] as List<TopAlbumAggregate>,
                playsByDay = values[8] as List<DayAggregate>,
                playsByHour = values[9] as List<HourAggregate>,
                playsByWeekday = values[10] as List<WeekdayAggregate>,
                playsByQuality = values[11] as List<QualityAggregate>,
                playsBySource = values[12] as List<SourceAggregate>
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setRange(r: StatsRange) { _range.value = r }
}
