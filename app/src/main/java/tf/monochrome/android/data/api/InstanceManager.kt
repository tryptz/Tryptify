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
 * Resolves API/streaming/download endpoints SOLELY from the URLs the user
 * configures in Settings → Instances:
 *  - Tidal HiFi URL ([PreferencesManager.customApiEndpoint]) for API + streaming.
 *  - Qobuz URL ([PreferencesManager.qobuzInstanceUrl]) for downloads.
 *
 * There is no public instance pool, uptime discovery, or hardcoded fallback —
 * the app only ever talks to the server the user gives it ("dev instance mode").
 */
@Singleton
class InstanceManager @Inject constructor(
    private val preferences: PreferencesManager,
) {
    suspend fun getInstances(type: InstanceType): List<Instance> {
        // Downloads prefer the configured Qobuz instance; otherwise fall through
        // to the user's main (Tidal HiFi) endpoint.
        if (type == InstanceType.DOWNLOAD) {
            val qobuz = preferences.qobuzInstanceUrl.first()?.trim()?.takeIf { it.isNotBlank() }
            if (qobuz != null) return listOf(Instance(qobuz.trimEnd('/')))
        }
        val custom = preferences.customApiEndpoint.first()?.trim()?.takeIf { it.isNotBlank() }
        return if (custom != null) listOf(Instance(custom.trimEnd('/'))) else emptyList()
    }

    // No remote pool to refresh — instances come only from the user's URL.
    suspend fun refreshInstances() {}

    // Strict resolution of the configured Qobuz instance — null when unset.
    suspend fun qobuzInstanceOrNull(): Instance? {
        val raw = preferences.qobuzInstanceUrl.first()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return Instance(raw.trimEnd('/'))
    }

    // Apple Music instance — the server hosting /api/apple/*. Falls back to the
    // Qobuz instance URL, since it's usually the same TrypT HiFi server.
    suspend fun appleInstanceOrNull(): Instance? {
        val raw = (preferences.appleInstanceUrl.first()?.trim()?.takeIf { it.isNotBlank() }
            ?: preferences.qobuzInstanceUrl.first()?.trim()?.takeIf { it.isNotBlank() })
            ?: return null
        return Instance(raw.trimEnd('/'))
    }
}
