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
import tf.monochrome.android.R
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.data.local.scanner.MediaStoreSource
import tf.monochrome.android.data.local.scanner.ScanWorker
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.glyph.pack.GlyphPackLibrary
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.settings.settingsTabIndex
import tf.monochrome.android.util.safTreeUriToPath
import javax.inject.Inject

/** The setup steps, in order. Each is optional; the tab bar jumps between them. */
enum class OnboardingStep { PERMISSIONS, FOLDERS, SOURCES, ACCOUNT, FEATURES, READY }

/**
 * The sources set up in Settings › Connections. Picking one here saves
 * nothing by itself: it sends the listener to Connections when setup ends,
 * which is where each one is configured.
 */
enum class OnboardingSource { SERVER, CLOUD, HIFI }

/** A library root picked in the Folders step. [trackCount] null = still counting. */
data class FolderEntry(
    val path: String,
    val displayName: String,
    val trackCount: Int? = null,
)

/**
 * First-run setup state. Folders are saved the moment they are picked, so
 * backing out mid-flow or process death loses nothing; only
 * `onboarding_complete` itself is written when the listener leaves.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: PreferencesManager,
    private val mediaStoreSource: MediaStoreSource,
    private val authManager: SupabaseAuthManager,
    private val glyphPacks: GlyphPackLibrary,
) : ViewModel() {

    private val _step = MutableStateFlow(OnboardingStep.PERMISSIONS)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * Whether this session has fired the media-permission request at least
     * once. Accompanist can't tell "never asked" from "permanently denied"
     * (both report !shouldShowRationale), so the settings link is only offered
     * once a request has really happened. The same for notifications.
     */
    private val _hasRequestedMediaPermission = MutableStateFlow(false)
    val hasRequestedMediaPermission: StateFlow<Boolean> = _hasRequestedMediaPermission.asStateFlow()
    fun markMediaPermissionRequested() { _hasRequestedMediaPermission.value = true }

    private val _hasRequestedNotifications = MutableStateFlow(false)
    val hasRequestedNotifications: StateFlow<Boolean> = _hasRequestedNotifications.asStateFlow()
    fun markNotificationsRequested() { _hasRequestedNotifications.value = true }

    private val _folders = MutableStateFlow<List<FolderEntry>>(emptyList())
    val folders: StateFlow<List<FolderEntry>> = _folders.asStateFlow()

    /** Set when a picked SAF tree can't be resolved to a device path. */
    private val _folderError = MutableStateFlow<tf.monochrome.android.ui.components.UiText?>(null)
    val folderError: StateFlow<tf.monochrome.android.ui.components.UiText?> = _folderError.asStateFlow()

    /** The folders Trypt Mania's linked packs are read from; empty hides the row. */
    private val _packFolders = MutableStateFlow<List<String>>(emptyList())
    val packFolders: StateFlow<List<String>> = _packFolders.asStateFlow()

    private val _sources = MutableStateFlow<Set<OnboardingSource>>(emptySet())
    val sources: StateFlow<Set<OnboardingSource>> = _sources.asStateFlow()

    val userProfile: StateFlow<UserProfile?> = authManager.userProfile
    val isSigningIn: StateFlow<Boolean> = authManager.isSigningIn

    /**
     * The listener's UI panel glass. Setup lives outside the nav host, which
     * is what publishes it as LocalMiniPlayerGlass everywhere else.
     */
    val glass: StateFlow<PlayerGlassSettings> = preferences.miniPlayerGlass
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerGlassSettings.INITIAL)

    init {
        // Re-running setup from Settings: show folders that are already
        // configured instead of pretending the listener has none.
        viewModelScope.launch {
            val roots = preferences.userFolderRoots.first()
            if (roots.isNotEmpty() && _folders.value.isEmpty()) {
                _folders.value = roots.map { FolderEntry(it, folderDisplayName(it)) }
                roots.forEach { countTracks(it) }
            }
        }
        viewModelScope.launch {
            _packFolders.value = runCatching { glyphPacks.linkedFolders() }.getOrDefault(emptyList())
        }
        // This ViewModel is Activity-scoped, so after the listener finishes
        // once its step stays at READY. "Restart onboarding" flips
        // onboarding_complete back to false: start again from the first step.
        viewModelScope.launch {
            var wasComplete: Boolean? = null
            preferences.onboardingComplete.collect { complete ->
                if (wasComplete == true && !complete) {
                    _step.value = OnboardingStep.entries.first()
                    _sources.value = emptySet()
                }
                wasComplete = complete
            }
        }
    }

    // ── Steps ───────────────────────────────────────────────────────

    fun goTo(step: OnboardingStep) { _step.value = step }

    fun next() {
        OnboardingStep.entries.getOrNull(_step.value.ordinal + 1)?.let { _step.value = it }
    }

    fun back() {
        OnboardingStep.entries.getOrNull(_step.value.ordinal - 1)?.let { _step.value = it }
    }

    // ── Folders ─────────────────────────────────────────────────────

    fun addFolder(uri: Uri) {
        val path = safTreeUriToPath(uri)
        if (path == null) {
            _folderError.value = tf.monochrome.android.ui.components.UiText.Res(R.string.folder_unscannable)
            return
        }
        _folderError.value = null
        addFolderPath(path)
    }

    private fun addFolderPath(path: String) {
        if (_folders.value.any { it.path == path }) return
        _folders.update { it + FolderEntry(path, folderDisplayName(path)) }
        viewModelScope.launch {
            preferences.addUserFolderRoot(path)
            countTracks(path)
        }
    }

    /** Whether every linked pack folder is already a library folder or inside one. */
    fun packsAdded(folders: List<FolderEntry>, packs: List<String>): Boolean =
        packs.isNotEmpty() && packs.all { pack -> folders.any { pack == it.path || pack.startsWith("${it.path}/") } }

    /** Adds the linked packs' folders, as Settings › Library › Music folders does. */
    fun addPackFolders() {
        val roots = _folders.value
        _packFolders.value
            .filter { pack -> roots.none { pack == it.path || pack.startsWith("${it.path}/") } }
            .forEach(::addFolderPath)
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

    // ── Sources and account ─────────────────────────────────────────

    fun toggleSource(source: OnboardingSource) {
        _sources.update { if (source in it) it - source else it + source }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch { authManager.signInWithGoogle(context) }
    }

    fun signInWithDiscord(context: Context) {
        viewModelScope.launch { authManager.signInWithDiscord(context) }
    }

    /** Back from the browser without a sign-in: stop showing one in progress. */
    fun cancelPendingSignIn() = authManager.cancelPendingSignInIfNoCallback()

    // ── Exit ────────────────────────────────────────────────────────

    /**
     * Where the app opens after setup: Settings › Connections when a source
     * there was picked, so it can be set up straight away, else the library.
     */
    fun landingRoute(): String =
        if (_sources.value.isNotEmpty()) Screen.Settings.createRoute(settingsTabIndex("Connections"))
        else "library"

    /**
     * Marks setup done and starts the first scan. Called from the last step
     * and from "Skip setup": with no folders picked the scan covers the whole
     * device, so a skipping listener still lands on a library that fills in.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            ScanWorker.enqueue(appContext)
            preferences.setOnboardingComplete(true)
        }
    }
}
