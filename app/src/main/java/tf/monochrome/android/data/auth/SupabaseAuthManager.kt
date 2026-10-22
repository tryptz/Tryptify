package tf.monochrome.android.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Discord
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
    /** The name the sign-in provider gave (Google's). Never shown as the account's name. */
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    /**
     * The name the listener chose in the app; null until they choose one.
     * Display only: it is not unique and can change at any time, so nothing
     * is ever keyed by it. Synced data is keyed by [id], the account the
     * listener signed in with (Google or email).
     */
    val username: String? = null,
)

@Singleton
class SupabaseAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @OptIn(SupabaseExperimental::class)
    val supabase: SupabaseClient = createSupabaseClient(
        supabaseUrl = "https://lvzorvfhhopillzlwgau.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx2em9ydmZoaG9waWxsemx3Z2F1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzQzNTc0NDQsImV4cCI6MjA4OTkzMzQ0NH0.Y_TN9r19WS96HyVZSQeNa0TyOqyBGuqFARaj8-7Ylow"
    ) {
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "tf.monotrypt.android"
            host = "login-callback"
            codeVerifierCache = SharedPrefsCodeVerifierCache(context)
            // The library's Android default parks the session whenever the app
            // leaves the foreground: it stops the refresh job and sets the
            // status to Initializing, and while it is Initializing there is no
            // current session to put on a request. Tryptify keeps working in
            // the background — every play is recorded there — so with the
            // default each of those writes went out under the anon key and was
            // refused by RLS. That was about two in three play uploads.
            // Off, the session stays signed in and the refresh job keeps
            // running for as long as the process does. A token that still
            // expires (the device slept through the refresh) is refreshed by
            // the library before the next request that carries it.
            enableLifecycleCallbacks = false
        }
        install(Postgrest) {
            // Every table the app reads or writes is scoped to the signed-in
            // user, so a request with no session has nothing it can do — and
            // without this it is not refused, it is sent under the anon key.
            // An insert then fails with a 401, but an update or delete matches
            // no rows and comes back as a success, which settles a pending
            // delete in SyncOutbox that never happened. With this the call
            // throws SessionRequiredException before it leaves the device, and
            // the edit stays queued.
            requireValidSession = true
        }
    }

    private val auth get() = supabase.auth

    /** The signed-in account's access token, for a server of ours to check with Supabase; null signed out. */
    fun accessToken(): String? = runCatching { auth.currentSessionOrNull()?.accessToken }.getOrNull()

    /**
     * The name other players see: the name chosen in the app, else the
     * provider's, else the email's first part; null when nobody is signed in.
     */
    fun playerName(): String? = _userProfile.value?.let { profile ->
        profile.username?.takeIf { it.isNotBlank() }
            ?: profile.displayName?.takeIf { it.isNotBlank() }
            ?: profile.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
    }

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    // The Google OAuth Custom Tab has no "cancelled" callback, so track whether
    // a deep-link callback ever arrived. If the user returns to the app without
    // one, cancelPendingSignInIfNoCallback clears the stuck spinner.
    @Volatile private var oauthCallbackReceived = false

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
    suspend fun signInWithGoogle(context: Context) = signInWithOAuth(context, Google, "Google")

    /**
     * Discord, the same Supabase OAuth flow as Google: the provider is
     * switched on (with a Discord application's client id and secret) in the
     * Supabase dashboard, under Authentication › Providers, and the callback
     * comes back through the same deep link.
     */
    suspend fun signInWithDiscord(context: Context) = signInWithOAuth(context, Discord, "Discord")

    private suspend fun signInWithOAuth(context: Context, provider: OAuthProvider, name: String) {
        oauthCallbackReceived = false
        _isSigningIn.value = true
        _errorMessage.value = null
        _successMessage.value = null
        try {
            val url = auth.getOAuthUrl(provider)
            val customTab = CustomTabsIntent.Builder().build()
            customTab.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            _errorMessage.value = "$name sign-in failed: ${parseAuthError(e)}"
            _isSigningIn.value = false
        }
    }

    /**
     * Handle the OAuth callback deep-link URI returned from the browser.
     * Call this from MainActivity.onNewIntent(). Only `?code=` (PKCE) signs in.
     */
    /**
     * Clear a stuck "Signing in…" state when the user came back from the OAuth
     * Custom Tab without completing it (there is no cancel callback, so the
     * screen calls this on resume). No-op once a deep-link callback has arrived.
     */
    fun cancelPendingSignInIfNoCallback() {
        if (_isSigningIn.value && !oauthCallbackReceived) {
            _isSigningIn.value = false
        }
    }

    suspend fun handleDeepLink(uri: Uri) {
        oauthCallbackReceived = true
        // PKCE only. A callback is a one-time code, and it signs in only with
        // the verifier this phone made when it started that sign-in, so a
        // link from anywhere else cannot sign the phone into another account.
        // Tokens in the fragment (the implicit flow) are never taken: any app
        // or web page can open this link, and taking them would let one
        // silently swap the account. Nothing of the link is logged.
        try {
            val code = uri.getQueryParameter("code")
            if (code.isNullOrBlank()) {
                if (!uri.fragment.isNullOrBlank()) Log.w("SupabaseAuth", "Sign-in link with tokens in it ignored (PKCE only)")
                _errorMessage.value = "No code in callback"
                return
            }
            auth.exchangeCodeForSession(code)
            _userProfile.value = auth.currentUserOrNull()?.toProfile()
        } catch (e: Exception) {
            Log.e("SupabaseAuth", "Deep-link handling failed: ${e.javaClass.simpleName}")
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

    /**
     * Set the account's username: the name the app shows, chosen in the app
     * and separate from the name Google gives. Saved as `username` in the
     * user's metadata, so it follows the account to every device; Google
     * rewrites only its own keys (`full_name`, `name`) on sign-in.
     */
    suspend fun updateUsername(name: String): Result<UserProfile> = try {
        val user = auth.updateUser {
            data = buildJsonObject { put(USERNAME_KEY, name) }
        }
        val profile = user.toProfile()
        _userProfile.value = profile
        Result.success(profile)
    } catch (e: Exception) {
        Result.failure(Exception(parseAuthError(e), e))
    }

    private fun UserInfo.toProfile() = UserProfile(
        id = id ?: "",
        displayName = metadataString("full_name") ?: metadataString("name"),
        email = email,
        photoUrl = metadataString("avatar_url"),
        username = metadataString(USERNAME_KEY),
    )

    private fun UserInfo.metadataString(key: String): String? =
        (userMetadata?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val USERNAME_KEY = "username"
    }
}
