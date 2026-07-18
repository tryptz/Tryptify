package tf.monochrome.android.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.downloads.ActiveDownload
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.downloads.DownloadStatus
import javax.inject.Inject

/**
 * Shared view of in-flight downloads, consumed by the floating progress pill, the
 * top-bar circular indicator, and the downloads monitor sheet. Backed by the
 * singleton [DownloadManager] flow so every surface sees the same live state.
 */
@HiltViewModel
class DownloadCenterViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val active: StateFlow<List<ActiveDownload>> = downloadManager.observeActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _overallProgress = MutableStateFlow(0f)

    /**
     * Mean progress for the aggregate indicator. Averaging only the currently
     * in-flight downloads made the ring jump *backwards* every time one finished
     * (a completed 90% item left the list, so the remaining lower-progress items
     * pulled the mean down). Instead we accumulate progress per track across the
     * batch — a download that leaves the list is remembered as 100% — so the
     * aggregate only ever moves forward until the batch drains. FAILED items are
     * excluded so a failure doesn't peg the ring.
     */
    val overallProgress: StateFlow<Float> = _overallProgress.asStateFlow()

    init {
        viewModelScope.launch {
            val batch = mutableMapOf<Long, Float>()
            active.collect { list ->
                val inFlight = list.filter { it.status != DownloadStatus.FAILED }
                if (inFlight.isEmpty()) {
                    batch.clear()
                    _overallProgress.value = 0f
                } else {
                    val activeIds = inFlight.map { it.trackId }.toSet()
                    // Anything previously tracked but no longer listed completed.
                    batch.keys.filter { it !in activeIds }.forEach { batch[it] = 1f }
                    inFlight.forEach { batch[it.trackId] = it.progress }
                    _overallProgress.value = batch.values.average().toFloat()
                }
            }
        }
    }

    fun cancel(trackId: Long) = downloadManager.cancel(trackId)
    fun cancelAll() = downloadManager.cancelAll()
    fun retry(trackId: Long) = downloadManager.retry(trackId)
}
