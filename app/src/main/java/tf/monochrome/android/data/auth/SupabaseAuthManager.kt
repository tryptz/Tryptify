package tf.monochrome.android.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the PKCE code verifier in SharedPreferences so it survives
 * process death while the Custom Tab is open for Google OAuth.
 */
private class SharedPrefsCodeVerifierCache(context: Context) : CodeVerifierCache {
    private val prefs = context.getSharedPreferences("supabase_pkce", Context.MODE_PRIVATE)

    override suspend fun saveCodeVerifier(codeVerifier: String) {
        prefs.edit().putString("code_verifier", codeVerifier).apply()
    }

    override suspend fun loadCodeVerifier(): String? {
        return prefs.getString("code_verifier", null)
    }

    override suspend fun deleteCodeVerifier() {
        prefs.edit().remove("code_verifier").apply()
    }
}

data class UserProfile(
    val id: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?
)

@Singleton
class SupabaseAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://lvzorvfhhopillzlwgau.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow"
    ) {
        install(Auth) {
            flowType = FlowType.IMPLICIT
            scheme = "tf.monotrypt.android"
            host = "login-callback"
        }
        install(Postgrest)
    }

    private val auth get() = supabase.auth

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Restore existing session on app start */
    suspend fun initialize() {
        try {
            auth.awaitInitialization()
            val user = auth.currentUserOrNull()
            _userProfile.value = user?.toProfile()
        } catch (e: Exception) {
            Log.w("SupabaseAuth", "No existing session: ${e.message}")
            _userProfile.value = null
        }
    }

    /**
     * Start Google OAuth via Supabase implicit flow — generates the OAuth URL,
     * then opens it in a Custom Tab. The app receives the callback via:
     *   tf.monotrypt.android://login-callback#access_token=...&refresh_token=...
     */
    suspend fun signInWithGoogle(context: Context) {
        _isSigningIn.value = true
        _errorMessage.value = null
        try {
            val url = auth.getOAuthUrl(Google)
            Log.d("SupabaseAuth", "OAuth URL: $url")
            val customTab = CustomTabsIntent.Builder().build()
            customTab.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            _errorMessage.value = "Google sign-in failed: ${e.message}"
            _isSigningIn.value = false
        }
    }

    /**
     * Handle the OAuth callback deep-link URI returned from the browser.
     * Call this from MainActivity.onNewIntent().
     *
     * Implicit flow: tokens arrive in the URL fragment (#access_token=...&refresh_token=...)
     * PKCE fallback: code arrives as query param (?code=...)
     */
    suspend fun handleDeepLink(uri: Uri) {
        Log.d("SupabaseAuth", "Deep-link received: $uri")
        Log.d("SupabaseAuth", "Fragment: ${uri.fragment}")
        try {
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank()) {
                // Implicit flow: parse tokens from fragment
                val params = fragment.split("&").associate {
                    val (key, value) = it.split("=", limit = 2)
                    key to value
                }
                val accessToken = params["access_token"]
                val refreshToken = params["refresh_token"]
                Log.d("SupabaseAuth", "Implicit flow: accessToken=${accessToken?.take(20)}..., refreshToken=${refreshToken?.take(10)}...")

                if (accessToken != null) {
                    auth.importAuthToken(
                        accessToken,
                        refreshToken ?: "",
                        retrieveUser = true
                    )
                    val user = auth.currentUserOrNull()
                    _userProfile.value = user?.toProfile()
                    _isSigningIn.value = false
                    Log.d("SupabaseAuth", "Implicit flow login successful, user: ${user?.id}")
                } else {
                    _errorMessage.value = "No access token in callback"
                }
            } else {
                // PKCE fallback: exchange code for session
                val code = uri.getQueryParameter("code")
                Log.d("SupabaseAuth", "PKCE flow: code=$code")
                auth.exchangeCodeForSession(uri.toString())
                val user = auth.currentUserOrNull()
                _userProfile.value = user?.toProfile()
                Log.d("SupabaseAuth", "PKCE flow login successful, user: ${user?.id}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "Deep-link handling failed: ${e.message}", e)
            _errorMessage.value = "Sign-in callback failed: ${e.message}"
        } finally {
            _isSigningIn.value = false
        }
    }

    /** Sign in with email + password */
    suspend fun signInWithEmail(email: String, password: String): Result<UserProfile> {
        _isSigningIn.value = true
        _errorMessage.value = null
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val user = auth.currentUserOrNull()
                ?: return Result.failure(Exception("Sign-in succeeded but no user returned"))
            val profile = user.toProfile()
            _userProfile.value = profile
            Result.success(profile)
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Login failed"
            Result.failure(e)
        } finally {
            _isSigningIn.value = false
        }
    }

    /** Sign up with email + password, then auto sign-in */
    suspend fun signUpWithEmail(email: String, password: String): Result<UserProfile> {
        _isSigningIn.value = true
        _errorMessage.value = null
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            // Auto sign-in after registration
            signInWithEmail(email, password)
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Sign up failed"
            _isSigningIn.value = false
            Result.failure(e)
        }
    }

    /** Refresh the current user (call after returning from OAuth browser) */
    suspend fun refreshUser() {
        try {
            auth.awaitInitialization()
            val user = auth.currentUserOrNull()
            _userProfile.value = user?.toProfile()
            // Only clear signing-in state if we got a user or no OAuth is pending
            if (user != null) _isSigningIn.value = false
        } catch (_: Exception) { }
    }

    /** Sign out and clear session */
    suspend fun signOut() {
        try {
            auth.signOut()
        } catch (_: Exception) { }
        _userProfile.value = null
        _errorMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun UserInfo.toProfile() = UserProfile(
        id = id ?: "",
        displayName = userMetadata?.get("full_name")?.toString()?.removeSurrounding("\"")
            ?: userMetadata?.get("name")?.toString()?.removeSurrounding("\""),
        email = email,
        photoUrl = userMetadata?.get("avatar_url")?.toString()?.removeSurrounding("\"")
    )
}
