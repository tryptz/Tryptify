package tf.monochrome.android.data.api

import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

enum class InstanceType { API, STREAMING }

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

    /**
     * The TIDAL (HiFi API) server for [type]. Every type is the TIDAL server:
     * each catalogue streams and downloads from its own server, so a TIDAL
     * request never goes to the Qobuz one.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getInstances(type: InstanceType): List<Instance> =
        listOfNotNull(instanceFor(ApiService.TIDAL))

    /** The server answering for TIDAL (the HiFi API routes), or null. */
    suspend fun tidalInstanceOrNull(): Instance? = instanceFor(ApiService.TIDAL)

    /** The server answering for Qobuz (TrypT HiFi get-music routes), or null. */
    suspend fun qobuzInstanceOrNull(): Instance? = instanceFor(ApiService.QOBUZ)

    /** The server answering for Apple Music (the /api/apple routes), or null. */
    suspend fun appleInstanceOrNull(): Instance? = instanceFor(ApiService.APPLE)

    /** The server answering for Deezer (the /api/deezer routes), or null. */
    suspend fun deezerInstanceOrNull(): Instance? = instanceFor(ApiService.DEEZER)
}
