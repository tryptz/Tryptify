package tf.monochrome.android.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

enum class InstanceType { API, STREAMING }

data class Instance(
    val url: String,
    val version: String? = null
)

@Singleton
class InstanceManager @Inject constructor(
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val json: Json
) {
    companion object {
        private val UPTIME_URLS = listOf(
            "https://tidal-uptime.jiffy-puffs-1j.workers.dev/",
            "https://tidal-uptime.props-76styles.workers.dev/"
        )
        private const val CACHE_DURATION_MS = 15 * 60 * 1000L

        private val FALLBACK_API_INSTANCES = listOf(
            Instance("https://eu-central.monochrome.tf"),
            Instance("https://us-west.monochrome.tf"),
            Instance("https://arran.monochrome.tf"),
            Instance("https://api.monochrome.tf"),
            Instance("https://triton.squid.wtf"),
            Instance("https://wolf.qqdl.site"),
            Instance("https://maus.qqdl.site"),
            Instance("https://vogel.qqdl.site"),
            Instance("https://hund.qqdl.site"),
            Instance("https://tidal.kinoplus.online")
        )

        private val FALLBACK_STREAMING_INSTANCES = listOf(
            Instance("https://arran.monochrome.tf"),
            Instance("https://triton.squid.wtf"),
            Instance("https://wolf.qqdl.site"),
            Instance("https://maus.qqdl.site"),
            Instance("https://vogel.qqdl.site"),
            Instance("https://katze.qqdl.site"),
            Instance("https://hund.qqdl.site")
        )
    }

    private var cachedApiInstances: List<Instance>? = null
    private var cachedStreamingInstances: List<Instance>? = null
    private var cacheTimestamp: Long = 0L

    suspend fun getInstances(type: InstanceType): List<Instance> {
        // Dev Mode: route all requests through the user-specified custom endpoint.
        // When disabled, ignore any saved URL and fall through to the normal
        // instance resolution so stale overrides don't silently redirect traffic.
        if (preferences.devModeEnabled.first()) {
            val customEndpoint = preferences.customApiEndpoint.first()
            if (customEndpoint != null) {
                return listOf(Instance(customEndpoint.trimEnd('/')))
            }
        }

        // Check memory cache
        if (isCacheValid()) {
            val cached = when (type) {
                InstanceType.API -> cachedApiInstances
                InstanceType.STREAMING -> cachedStreamingInstances
            }
            if (!cached.isNullOrEmpty()) return cached.shuffled()
        }

        // Try loading from DataStore cache
        val storedCache = preferences.instancesCache.first()
        val storedTimestamp = preferences.instancesCacheTimestamp.first()
        if (storedCache != null && System.currentTimeMillis() - storedTimestamp < CACHE_DURATION_MS) {
            parseAndCacheInstances(storedCache)
            val cached = when (type) {
                InstanceType.API -> cachedApiInstances
                InstanceType.STREAMING -> cachedStreamingInstances
            }
            if (!cached.isNullOrEmpty()) return cached.shuffled()
        }

        // Fetch fresh instances from uptime APIs
        val fetched = fetchFromUptimeApis()
        if (fetched != null) {
            parseAndCacheInstances(fetched)
            preferences.saveInstancesCache(fetched)
            val cached = when (type) {
                InstanceType.API -> cachedApiInstances
                InstanceType.STREAMING -> cachedStreamingInstances
            }
            if (!cached.isNullOrEmpty()) return cached.shuffled()
        }

        // Fallback to hardcoded instances
        return when (type) {
            InstanceType.API -> FALLBACK_API_INSTANCES.shuffled()
            InstanceType.STREAMING -> FALLBACK_STREAMING_INSTANCES.shuffled()
        }
    }

    suspend fun refreshInstances() {
        val fetched = fetchFromUptimeApis()
        if (fetched != null) {
            parseAndCacheInstances(fetched)
            preferences.saveInstancesCache(fetched)
        }
    }

    private fun isCacheValid(): Boolean {
        return System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS
    }

    private suspend fun fetchFromUptimeApis(): String? {
        val shuffledUrls = UPTIME_URLS.shuffled()
        for (url in shuffledUrls) {
            try {
                val response = httpClient.get(url)
                val body = response.bodyAsText()
                if (body.isNotBlank()) return body
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun parseAndCacheInstances(jsonString: String) {
        try {
            val parsed = json.parseToJsonElement(jsonString)
            val apiList = mutableListOf<Instance>()
            val streamingList = mutableListOf<Instance>()

            when (parsed) {
                is JsonObject -> {
                    // Format: { api: [...], streaming: [...] }
                    parsed["api"]?.jsonArray?.forEach { element ->
                        parseInstance(element.jsonObject)?.let { apiList.add(it) }
                    }
                    parsed["streaming"]?.jsonArray?.forEach { element ->
                        parseInstance(element.jsonObject)?.let { streamingList.add(it) }
                    }
                    // Also check "instances" key
                    parsed["instances"]?.jsonArray?.forEach { element ->
                        val obj = element.jsonObject
                        val instance = parseInstance(obj)
                        if (instance != null) {
                            val type = obj["type"]?.jsonPrimitive?.content
                            when (type) {
                                "streaming" -> streamingList.add(instance)
                                else -> apiList.add(instance)
                            }
                        }
                    }
                }
                is JsonArray -> {
                    // Format: [{ url, type, version }, ...]
                    parsed.forEach { element ->
                        val obj = element.jsonObject
                        val instance = parseInstance(obj)
                        if (instance != null) {
                            val type = obj["type"]?.jsonPrimitive?.content
                            when (type) {
                                "streaming" -> streamingList.add(instance)
                                else -> apiList.add(instance)
                            }
                        }
                    }
                }
                else -> return
            }

            if (apiList.isNotEmpty()) cachedApiInstances = apiList
            if (streamingList.isNotEmpty()) cachedStreamingInstances = streamingList
            // If streaming is empty, use API instances as fallback for streaming too
            if (streamingList.isEmpty() && apiList.isNotEmpty()) {
                cachedStreamingInstances = apiList
            }
            cacheTimestamp = System.currentTimeMillis()
        } catch (_: Exception) {
            // Parse failed, will use fallback
        }
    }

    private fun parseInstance(obj: JsonObject): Instance? {
        val url = obj["url"]?.jsonPrimitive?.content ?: return null
        val version = obj["version"]?.jsonPrimitive?.content
        return Instance(url.trimEnd('/'), version)
    }
}
