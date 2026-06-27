package tf.monochrome.android.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.data.downloads.ActiveDownload
import tf.monochrome.android.data.downloads.DownloadManager
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

    /** Mean progress across all in-flight downloads, for the aggregate indicator. */
    val overallProgress: StateFlow<Float> = active
        .map { list -> if (list.isEmpty()) 0f else list.map { it.progress }.average().toFloat() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    fun cancel(trackId: Long) = downloadManager.cancel(trackId)
    fun cancelAll() = downloadManager.cancelAll()
}
