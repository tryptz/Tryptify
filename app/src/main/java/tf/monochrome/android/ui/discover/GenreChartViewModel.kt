package tf.monochrome.android.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.ChartWindow
import tf.monochrome.android.data.charts.ChartsRepository
import tf.monochrome.android.data.charts.GenreChart
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.GenreChartUseCase
import tf.monochrome.android.ui.player.PlayerViewModel
import javax.inject.Inject

/**
 * State for one genre's chart screen.
 *
 * Chart rows are not playable objects. They are claims about what the world
 * listened to, expressed as an artist and a title, and turning one into
 * something this app can play means a catalogue search. Doing that for all
 * hundred rows up front would be a hundred searches to render a list nobody has
 * touched yet, so resolution happens on tap, and "queue the chart" resolves only
 * the opening stretch.
 */
@HiltViewModel
class GenreChartViewModel @Inject constructor(
    private val genreCharts: GenreChartUseCase,
    private val charts: ChartsRepository,
) : ViewModel() {

    private val _chart = MutableStateFlow<GenreChart?>(null)
    val chart: StateFlow<GenreChart?> = _chart.asStateFlow()

    private val _window = MutableStateFlow(ChartWindow.DEFAULT)
    val window: StateFlow<ChartWindow> = _window.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** One-shot text for the snackbar; cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var genreId: String = ""

    companion object {
        /**
         * How far down the chart "play" reaches. Deep enough to be a listening
         * session, shallow enough that queueing it is a handful of searches
         * rather than a hundred.
         */
        private const val QUEUE_DEPTH = 25
    }

    fun load(genreId: String) {
        if (this.genreId == genreId && _chart.value != null) return
        this.genreId = genreId
        refresh()
    }

    fun selectWindow(window: ChartWindow) {
        if (_window.value == window) return
        _window.value = window
        refresh()
    }

    fun refresh(force: Boolean = false) {
        val id = genreId.ifBlank { return }
        viewModelScope.launch {
            _loading.value = true
            if (force) charts.invalidate()
            _chart.value = genreCharts.chart(id, _window.value)
            _loading.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Play one row, queueing the rest of the chart's opening stretch behind it. */
    fun play(entry: ChartEntry, player: PlayerViewModel) {
        viewModelScope.launch {
            val track = resolve(entry)
            if (track == null) {
                _message.value = "\"${entry.title}\" isn't in the catalogue"
                return@launch
            }
            player.playUnifiedTrack(track, listOf(track))
        }
    }

    /** Resolve the top of the chart and play it in order. */
    fun playAll(player: PlayerViewModel) {
        val entries = _chart.value?.entries.orEmpty().take(QUEUE_DEPTH)
        if (entries.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val tracks = entries.mapNotNull { resolve(it) }.distinctBy { it.id }
            _loading.value = false
            val first = tracks.firstOrNull()
            if (first == null) {
                _message.value = "None of this chart is in the catalogue"
                return@launch
            }
            if (tracks.size < entries.size) {
                _message.value = "Playing ${tracks.size} of ${entries.size} — the rest aren't in the catalogue"
            }
            player.playUnifiedTrack(first, tracks)
        }
    }

    /**
     * Turn a chart row into something playable.
     *
     * Delegates to the use case's verifying resolver rather than taking the
     * search backend's first hit: a chart row names a specific record, and a hit
     * that agrees on neither artist nor title is not that record.
     */
    private suspend fun resolve(entry: ChartEntry): UnifiedTrack? = genreCharts.resolve(entry)
}
