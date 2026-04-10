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

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authManager: SupabaseAuthManager,
    private val authRepository: AuthRepository,
    private val supabaseSyncRepository: SupabaseSyncRepository
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = authManager.userProfile
    val isSigningIn: StateFlow<Boolean> = authManager.isSigningIn
    val errorMessage: StateFlow<String?> = authManager.errorMessage

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncStatus.value = null
            try {
                supabaseSyncRepository.pushAll()
                supabaseSyncRepository.pullAll()
                _syncStatus.value = "Sync complete"
            } catch (e: Exception) {
                _syncStatus.value = "Sync failed: ${e.message ?: "unknown error"}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
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
}
