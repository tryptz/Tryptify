package tf.monochrome.android.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passwords of servers that ask for one, and putting them on the wire.
 *
 * A TrypT HiFi with `ACCESS_PASSWORD` set answers 401 to anything without it
 * (trypt-hifi's middleware.ts). The listener gives the password under the
 * server's address in Settings › Connections; every request and every stream
 * to that server's origin then carries it as [HEADER]: the API client's
 * interceptor (NetworkModule) and the players' data sources (PlaybackService,
 * SongPreview). Only that origin: a redirect elsewhere is sent without it.
 *
 * A device secret, like the Subsonic sign-in: kept in its own key
 * ([PreferencesManager.apiServerPasswords]), never in the server list, which
 * settings sync uploads.
 */
@Singleton
class ApiServerAccess @Inject constructor(
    private val preferences: PreferencesManager,
) {
    /** By origin ([originOf]), for the lookups on every request. */
    @Volatile private var byOrigin: Map<String, String> = emptyMap()
    @Volatile private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            preferences.apiServerPasswords.collect { saved ->
                byOrigin = toOrigins(saved)
                loaded = true
            }
        }
    }

    /**
     * The password for a request to [url], or null. Called on network and
     * loader threads; the first call may wait for the saved passwords to load,
     * so a request at start-up does not go out bare and get a 401.
     */
    fun passwordFor(url: String): String? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                byOrigin = toOrigins(runBlocking { preferences.apiServerPasswords.first() })
                loaded = true
            }
        }
        val origin = originOf(url) ?: return null
        return byOrigin[origin]
    }

    /**
     * [spec] with the password of the server it is fetched from, for a media
     * player's data source. Fine across redirects too: trypt-hifi's stream
     * links answer with the file, never a redirect elsewhere.
     */
    fun withPassword(spec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
        val password = passwordFor(spec.uri.toString()) ?: return spec
        return spec.withAdditionalHeaders(mapOf(HEADER to password))
    }

    /** Saves (or, blank, forgets) [serverUrl]'s password; it applies from the next request. */
    suspend fun set(serverUrl: String, password: String?) {
        val origin = originOf(serverUrl)
        if (origin != null) {
            byOrigin = if (password.isNullOrBlank()) byOrigin - origin else byOrigin + (origin to password)
        }
        preferences.setApiServerPassword(serverUrl, password?.takeIf { it.isNotBlank() })
    }

    companion object {
        /** What trypt-hifi reads (lib/access.ts). */
        const val HEADER = "X-Access-Key"

        /** A URL's scheme, host and port: what a password belongs to. */
        fun originOf(url: String): String? =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

        private fun toOrigins(byServer: Map<String, String>): Map<String, String> =
            byServer.mapNotNull { (server, password) -> originOf(server)?.let { it to password } }.toMap()
    }
}
