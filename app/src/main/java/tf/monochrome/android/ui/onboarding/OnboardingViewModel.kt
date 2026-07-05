package tf.monochrome.android.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.UsbAudioRouter
import tf.monochrome.android.data.auth.SpotifyAuthManager
import tf.monochrome.android.data.local.scanner.MediaStoreSource
import tf.monochrome.android.data.local.scanner.ScanWorker
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.util.safTreeUriToPath
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME, PERMISSIONS, FOLDERS, DOWNLOADS, STREAMING, AUDIO_OUTPUT, TOUR, DONE
}

/** A library root picked in the Folders step. [trackCount] null = still counting. */
data class FolderEntry(
    val path: String,
    val displayName: String,
    val trackCount: Int? = null,
)

/**
 * First-run wizard state. Everything the user picks (folders, download
 * location, bit-perfect, Spotify tokens) is persisted the moment it's
 * chosen, so backing out mid-flow or process death loses nothing; only
 * `onboarding_complete` itself is written when the user leaves the flow.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: PreferencesManager,
    private val mediaStoreSource: MediaStoreSource,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val usbAudioRouter: UsbAudioRouter,
) : ViewModel() {

    private val _step = MutableStateFlow(OnboardingStep.WELCOME)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * Whether this session has fired the media-permission request at least
     * once. Accompanist can't distinguish "never asked" from "permanently
     * denied" (both report !shouldShowRationale), so the settings deep-link
     * is only shown once we know a request actually happened.
     */
    private val _hasRequestedMediaPermission = MutableStateFlow(false)
    val hasRequestedMediaPermission: StateFlow<Boolean> = _hasRequestedMediaPermission.asStateFlow()
    fun markMediaPermissionRequested() { _hasRequestedMediaPermission.value = true }

    private val _folders = MutableStateFlow<List<FolderEntry>>(emptyList())
    val folders: StateFlow<List<FolderEntry>> = _folders.asStateFlow()

    /** Set when a picked SAF tree can't be resolved to a device path. */
    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    // Streaming / audio state passed straight through from the owning singletons.
    val spotifyConnected: StateFlow<Boolean> = spotifyAuthManager.isConnected
    val spotifyConnecting: StateFlow<Boolean> = spotifyAuthManager.isConnecting
    val spotifyUserName: StateFlow<String?> = spotifyAuthManager.connectedUserName
    val spotifyError: StateFlow<String?> = spotifyAuthManager.errorMessage
    val usbDevice = usbAudioRouter.usbOutputDevice

    val downloadFolderUri: StateFlow<String?> = preferences.downloadFolderUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val usbBitPerfectEnabled: StateFlow<Boolean> = preferences.usbBitPerfectEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Re-running onboarding from Settings: show folders that are already
        // configured instead of pretending the user has none.
        viewModelScope.launch {
            val roots = preferences.userFolderRoots.first()
            if (roots.isNotEmpty() && _folders.value.isEmpty()) {
                _folders.value = roots.map { FolderEntry(it, folderDisplayName(it)) }
                roots.forEach { countTracks(it) }
            }
        }
    }

    // ── Step navigation ─────────────────────────────────────────────

    fun next() {
        val current = _step.value
        if (current == OnboardingStep.FOLDERS && _folders.value.isEmpty()) return
        OnboardingStep.entries.getOrNull(current.ordinal + 1)?.let { _step.value = it }
    }

    fun back() {
        OnboardingStep.entries.getOrNull(_step.value.ordinal - 1)?.let { _step.value = it }
    }

    // ── Folders ─────────────────────────────────────────────────────

    fun addFolder(uri: Uri) {
        val path = safTreeUriToPath(uri)
        if (path == null) {
            _folderError.value =
                "That folder can't be scanned — pick a folder on device storage."
            return
        }
        _folderError.value = null
        if (_folders.value.any { it.path == path }) return
        _folders.update { it + FolderEntry(path, folderDisplayName(path)) }
        viewModelScope.launch {
            preferences.addUserFolderRoot(path)
            countTracks(path)
        }
    }

    fun removeFolder(path: String) {
        _folders.update { list -> list.filterNot { it.path == path } }
        viewModelScope.launch { preferences.removeUserFolderRoot(path) }
    }

    private fun countTracks(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = mediaStoreSource.countAudioUnderPath(path)
            _folders.update { list ->
                list.map { if (it.path == path) it.copy(trackCount = count) else it }
            }
        }
    }

    private fun folderDisplayName(path: String) =
        path.substringAfterLast('/').ifBlank { path }

    // ── Downloads / streaming / audio ───────────────────────────────

    fun setDownloadFolder(uriString: String?) {
        viewModelScope.launch { preferences.setDownloadFolderUri(uriString) }
    }

    fun connectSpotify(activityContext: Context) = spotifyAuthManager.connect(activityContext)

    fun usbDeviceLabel(device: android.media.AudioDeviceInfo): String =
        usbAudioRouter.describe(device)

    fun setBitPerfect(enabled: Boolean) {
        viewModelScope.launch { preferences.setUsbBitPerfectEnabled(enabled) }
    }

    // ── Exit ────────────────────────────────────────────────────────

    /**
     * Marks onboarding done and kicks off the initial scan. Called from
     * both the Done step and "Skip setup" — a skipping user has no roots
     * configured, which means a whole-device scan, so they still land on
     * a populating library rather than a dead one.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            ScanWorker.enqueue(appContext)
            preferences.setOnboardingComplete(true)
        }
    }
}
