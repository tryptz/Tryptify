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
import tf.monochrome.android.BuildConfig
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
    // Blank defaults let public forks build without Supabase credentials.
    // Sign-in fails fast at runtime with a warning in that case.
    private val isConfigured =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://unconfigured.local" },
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "unconfigured" }
    ) {
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "tf.monotrypt.android"
            host = "login-callback"
            codeVerifierCache = SharedPrefsCodeVerifierCache(context)
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

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

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
     * Start Google OAuth via Supabase PKCE flow — generates the OAuth URL,
     * then opens it in a Custom Tab. The app receives the callback via:
     *   tf.monotrypt.android://login-callback?code=...
     */
    suspend fun signInWithGoogle(context: Context) {
        _isSigningIn.value = true
        _errorMessage.value = null
        _successMessage.value = null
        try {
            val url = auth.getOAuthUrl(Google)
            Log.d("SupabaseAuth", "OAuth URL: $url")
            val customTab = CustomTabsIntent.Builder().build()
            customTab.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            _errorMessage.value = "Google sign-in failed: ${parseAuthError(e)}"
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
                val code = uri.getQueryParameter("code")
                Log.d("SupabaseAuth", "PKCE flow: code=${code?.take(20)}...")
                if (code.isNullOrBlank()) {
                    _errorMessage.value = "No code in callback"
                    return
                }
                auth.exchangeCodeForSession(code)
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
        _successMessage.value = null
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
            _errorMessage.value = parseAuthError(e)
            Result.failure(e)
        } finally {
            _isSigningIn.value = false
        }
    }

    /** Sign up with email + password */
    suspend fun signUpWithEmail(email: String, password: String): Result<UserProfile> {
        _isSigningIn.value = true
        _errorMessage.value = null
        _successMessage.value = null
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            // Check if auto-confirmed (session created immediately)
            val user = auth.currentUserOrNull()
            if (user != null) {
                val profile = user.toProfile()
                _userProfile.value = profile
                _isSigningIn.value = false
                Result.success(profile)
            } else {
                // Email confirmation required — don't attempt sign-in
                _isSigningIn.value = false
                _successMessage.value = "Account created! Check your email for a confirmation link, then sign in."
                Result.failure(Exception("Email confirmation required"))
            }
        } catch (e: Exception) {
            _errorMessage.value = parseAuthError(e)
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

    fun clearSuccess() {
        _successMessage.value = null
    }

    /** Extract a user-friendly message from Supabase auth exceptions */
    private fun parseAuthError(e: Exception): String {
        val msg = e.message ?: return "An unknown error occurred"
        return when {
            msg.contains("invalid_credentials", ignoreCase = true) ->
                "Invalid email or password. Please check your credentials and try again."
            msg.contains("user_already_exists", ignoreCase = true) ||
            msg.contains("already registered", ignoreCase = true) ->
                "An account with this email already exists. Try signing in instead."
            msg.contains("email_not_confirmed", ignoreCase = true) ->
                "Please check your email and confirm your account before signing in."
            msg.contains("weak_password", ignoreCase = true) ->
                "Password is too weak. Please use at least 8 characters with a mix of letters and numbers."
            msg.contains("signup_disabled", ignoreCase = true) ->
                "Account registration is currently disabled."
            msg.contains("over_request_rate_limit", ignoreCase = true) ||
            msg.contains("rate_limit", ignoreCase = true) ->
                "Too many attempts. Please wait a moment and try again."
            msg.contains("validation_failed", ignoreCase = true) ->
                "Please enter a valid email address."
            msg.contains("network", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("Unable to resolve host", ignoreCase = true) ->
                "Network error. Please check your internet connection and try again."
            else -> {
                // Fallback: take just the first line, stripping HTTP details
                val firstLine = msg.lines().firstOrNull() ?: "Authentication failed"
                if (firstLine.length > 120) firstLine.take(120) + "…" else firstLine
            }
        }
    }

    private fun UserInfo.toProfile() = UserProfile(
        id = id ?: "",
        displayName = userMetadata?.get("full_name")?.toString()?.removeSurrounding("\"")
            ?: userMetadata?.get("name")?.toString()?.removeSurrounding("\""),
        email = email,
        photoUrl = userMetadata?.get("avatar_url")?.toString()?.removeSurrounding("\"")
    )
}
