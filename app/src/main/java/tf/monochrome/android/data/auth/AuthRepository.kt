package tf.monochrome.android.data.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth repository backed by Supabase (SupabaseAuthManager).
 * Provides app-wide sign-in state and coordinates post-login tasks.
 */
@Singleton
class AuthRepository @Inject constructor(
    val authManager: SupabaseAuthManager
) {
    val isLoggedIn: Flow<Boolean> = authManager.userProfile.map { it != null }
    val userEmail: Flow<String?> = authManager.userProfile.map { it?.email }

    suspend fun logout() {
        authManager.signOut()
    }
}
