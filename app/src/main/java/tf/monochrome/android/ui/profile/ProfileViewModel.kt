package tf.monochrome.android.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.AccountAvatar
import tf.monochrome.android.data.auth.AuthRepository
import tf.monochrome.android.data.auth.ProfileAvatarStore
import tf.monochrome.android.data.auth.cleanUsername
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import javax.inject.Inject
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.UiText

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authManager: SupabaseAuthManager,
    private val authRepository: AuthRepository,
    private val supabaseSyncRepository: SupabaseSyncRepository,
    private val avatarStore: ProfileAvatarStore,
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = authManager.userProfile
    val isSigningIn: StateFlow<Boolean> = authManager.isSigningIn
    val errorMessage: StateFlow<String?> = authManager.errorMessage
    val successMessage: StateFlow<String?> = authManager.successMessage

    val avatar: StateFlow<AccountAvatar?> = avatarStore.avatars(authManager.userProfile)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), avatarStore.placeholder(authManager.userProfile.value))

    private val _avatarBusy = MutableStateFlow(false)
    val avatarBusy: StateFlow<Boolean> = _avatarBusy.asStateFlow()

    private val _avatarError = MutableStateFlow<UiText?>(null)
    val avatarError: StateFlow<UiText?> = _avatarError.asStateFlow()

    private val _nameSaving = MutableStateFlow(false)
    val nameSaving: StateFlow<Boolean> = _nameSaving.asStateFlow()

    private val _nameError = MutableStateFlow<UiText?>(null)
    val nameError: StateFlow<UiText?> = _nameError.asStateFlow()

    /** Use the picture the listener picked; [uri] is read and copied now, while the grant holds. */
    fun pickAvatar(uri: Uri) {
        val userId = userProfile.value?.id ?: return
        if (_avatarBusy.value) return
        viewModelScope.launch {
            _avatarBusy.value = true
            _avatarError.value = null
            if (!avatarStore.setCustom(userId, uri)) {
                _avatarError.value = UiText.Res(R.string.profile_picture_failed)
            }
            _avatarBusy.value = false
        }
    }

    fun useProviderPhoto() {
        userProfile.value?.id?.let(avatarStore::useProvider)
        _avatarError.value = null
    }

    fun removeAvatar() {
        userProfile.value?.id?.let(avatarStore::remove)
        _avatarError.value = null
    }

    /** Save [raw] as the username; [onSaved] runs once it is saved, so the editor can close. */
    fun saveUsername(raw: String, onSaved: () -> Unit) {
        if (_nameSaving.value) return
        val name = cleanUsername(raw)
        if (name == null) {
            _nameError.value = UiText.Res(R.string.username_invalid, listOf(tf.monochrome.android.data.auth.USERNAME_MAX))
            return
        }
        if (name == userProfile.value?.username) {
            _nameError.value = null
            onSaved()
            return
        }
        viewModelScope.launch {
            _nameSaving.value = true
            _nameError.value = null
            authManager.updateUsername(name)
                .onSuccess { onSaved() }
                .onFailure { e ->
                    _nameError.value = UiText.Res(
                        R.string.username_update_failed,
                        listOf(e.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.unknown_error)),
                    )
                }
            _nameSaving.value = false
        }
    }

    fun clearNameError() {
        _nameError.value = null
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow<tf.monochrome.android.ui.components.UiText?>(null)
    val syncStatus: StateFlow<tf.monochrome.android.ui.components.UiText?> = _syncStatus.asStateFlow()

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = null
            try {
                // Each section swallows its own error, so a failure is only
                // visible via these returned section names — don't report
                // success unless both came back clean.
                val failed = (supabaseSyncRepository.pushAll() + supabaseSyncRepository.pullAll()).distinct()
                _syncStatus.value = if (failed.isEmpty()) {
                    tf.monochrome.android.ui.components.UiText.Res(R.string.sync_complete)
                } else {
                    tf.monochrome.android.ui.components.UiText.Res(R.string.sync_with_issues, listOf(failed.joinToString(", ")))
                }
            } catch (e: Exception) {
                _syncStatus.value = tf.monochrome.android.ui.components.UiText.Res(R.string.sync_failed, listOf(e.message?.takeIf { it.isNotBlank() }?.let { tf.monochrome.android.ui.components.UiText.Raw(it) } ?: tf.monochrome.android.ui.components.UiText.Res(R.string.unknown_error)))
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun refreshUser() {
        viewModelScope.launch {
            authManager.refreshUser()
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            authManager.signInWithGoogle(context)
        }
    }

    /**
     * Called when the Profile screen returns to the foreground. If a Google
     * OAuth flow was launched but the user came back without completing it (no
     * deep-link callback), clear the otherwise-permanent "Signing in…" spinner.
     */
    fun onScreenResumed() {
        authManager.cancelPendingSignInIfNoCallback()
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            authManager.signInWithEmail(email, password)
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            authManager.signUpWithEmail(email, password)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun clearError() {
        authManager.clearError()
    }

    fun clearSuccess() {
        authManager.clearSuccess()
    }
}
