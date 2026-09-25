package tf.monochrome.android.data.api

import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

enum class InstanceType { API, STREAMING, DOWNLOAD }

data class Instance(
    val url: String,
    val version: String? = null
)

/**
 * Resolves each service's server from the APIs the user added under
 * Settings › Connections ([PreferencesManager.apiServers]). For every service
 * the first server in the list that serves it wins; see [ApiServers.serverFor].
 *
 * There is no public instance pool, uptime discovery, or hardcoded fallback —
 * the app only ever talks to the servers the user gives it.
 */
@Singleton
class InstanceManager @Inject constructor(
    private val preferences: PreferencesManager,
) {
    private suspend fun instanceFor(service: ApiService): Instance? =
        ApiServers.serverFor(preferences.apiServers.first(), service)?.let { Instance(it.url) }

    suspend fun getInstances(type: InstanceType): List<Instance> {
        // Downloads prefer the Qobuz server; otherwise fall through to TIDAL.
        if (type == InstanceType.DOWNLOAD) {
            instanceFor(ApiService.QOBUZ)?.let { return listOf(it) }
        }
        return listOfNotNull(instanceFor(ApiService.TIDAL))
    }

    // No remote pool to refresh — instances come only from the user's list.
    suspend fun refreshInstances() {}

    /** The server answering for Qobuz (TrypT HiFi get-music routes), or null. */
    suspend fun qobuzInstanceOrNull(): Instance? = instanceFor(ApiService.QOBUZ)

    /** The server answering for Apple Music (the /api/apple routes), or null. */
    suspend fun appleInstanceOrNull(): Instance? = instanceFor(ApiService.APPLE)

    /** The server answering for Deezer (the /api/deezer routes), or null. */
    suspend fun deezerInstanceOrNull(): Instance? = instanceFor(ApiService.DEEZER)
}
