package tf.monochrome.android.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.data.db.dao.HistoryDao
import javax.inject.Inject

/**
 * Simple Spotify-Wrapped-style ViewModel backed by HistoryDao.
 * Separate from the richer [StatsViewModel] which uses PlayEventDao aggregates.
 */
@HiltViewModel
class ListeningStatsViewModel @Inject constructor(
    private val historyDao: HistoryDao
) : ViewModel() {

    val topArtists: StateFlow<List<HistoryDao.TopArtist>> = historyDao.getTopArtists(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topAlbums: StateFlow<List<HistoryDao.TopAlbum>> = historyDao.getTopAlbums(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalPlays: StateFlow<Int> = historyDao.getHistoryCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
