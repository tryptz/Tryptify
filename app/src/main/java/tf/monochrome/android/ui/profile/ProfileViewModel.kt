package tf.monochrome.android.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.AuthRepository
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.data.sync.SupabaseSyncRepository
import javax.inject.Inject
import tf.monochrome.android.R

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authManager: SupabaseAuthManager,
    private val authRepository: AuthRepository,
    private val supabaseSyncRepository: SupabaseSyncRepository
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = authManager.userProfile
    val isSigningIn: StateFlow<Boolean> = authManager.isSigningIn
    val errorMessage: StateFlow<String?> = authManager.errorMessage
    val successMessage: StateFlow<String?> = authManager.successMessage

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
