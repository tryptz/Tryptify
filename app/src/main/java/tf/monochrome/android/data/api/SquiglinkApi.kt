package tf.monochrome.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SquiglinkApi - Aggregates headphone measurements from CrinGraph-compatible
 * squig.link instances. Each instance publishes a brand-grouped index at
 * <host>/data/phone_book.json; raw FR data lives at <host>/data/<name> L.txt
 * and "<name> R.txt" for the left/right channels.
 *
 * Twelve sources are queried in parallel on first fetch; per-source failures
 * are silent so one dead host doesn't poison the whole list. Cache TTL
 * matches the AutoEq path (24 h).
 */
class SquiglinkApi {
    companion object {
        private const val TAG = "SquiglinkApi"
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)

        // Verified rigs come from public posts; unverified ones are tagged
        // UNKNOWN rather than guessed — the rig filter UI surfaces those
        // separately so the user can reclassify without code changes.
        private val SOURCES = listOf(
            Source("https://squig.link", "Super* Review", MeasurementRig.IEC_711_CLONE, null),
            Source("https://precog.squig.link", "Precogvision", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://dhrme.squig.link", "DHRME", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://aftersound.squig.link", "Aftersound", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://eliseaudio.squig.link", "Elise Audio", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://jaytiss.squig.link", "Jaytiss", MeasurementRig.UNKNOWN, null),
            Source("https://csi-zone.squig.link", "CSI-Zone", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://achoreviews.squig.link", "Acho Reviews", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://kr0mka.squig.link", "kr0mka", MeasurementRig.UNKNOWN, "over-ear"),
            Source("https://dchpgall.squig.link", "dcinside", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://joycesreview.squig.link", "Joyce's Review", MeasurementRig.IEC_711_CLONE, "in-ear"),
            Source("https://listener.squig.link", "Listener (DMS)", MeasurementRig.GRAS_43AG_7, null),
        )
    }

    data class Source(
        val host: String,
        val label: String,
        val rig: MeasurementRig,
        // "in-ear", "over-ear", or null for mixed/unknown.
        val type: String?,
    )

    private var cachedHeadphones: List<Headphone>? = null
    private var lastFetchTime: Long = 0L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchHeadphones(): Result<List<Headphone>> = withContext(Dispatchers.IO) {
        if (isCacheValid()) return@withContext Result.success(cachedHeadphones!!)
        try {
            val all = coroutineScope {
                SOURCES.map { src -> async { fetchFromSource(src) } }.map { it.await() }
            }.flatten()
            val merged = mergeByName(all)
            cachedHeadphones = merged
            lastFetchTime = System.currentTimeMillis()
            Result.success(merged)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch the raw FR text for a single squig.link measurement. Tries the L
     * channel first (the convention CrinGraph follows when only one channel is
     * present) and falls back to R if L 404s.
     */
    suspend fun fetchMeasurementText(host: String, fileName: String): String? =
        withContext(Dispatchers.IO) {
            for (channel in arrayOf("L", "R")) {
                val body = fetchChannel(host, fileName, channel)
                if (body != null) return@withContext body
            }
            null
        }

    /**
     * Fetch exactly ONE channel's FR text ("L" or "R"); null when that channel
     * isn't published. No fallback on purpose — per-ear calibration needs to
     * know the difference between "here is the right ear" and "here is
     * whatever existed".
     */
    suspend fun fetchMeasurementChannelText(host: String, fileName: String, channel: String): String? =
        withContext(Dispatchers.IO) { fetchChannel(host, fileName, channel) }

    private fun fetchChannel(host: String, fileName: String, channel: String): String? =
        fetchChannelWithSample(host, fileName, channel)?.first

    /**
     * Like [fetchMeasurementChannelText] but also reports WHICH sample file
     * answered ("L" or "L1"), so the sample stepper knows its starting point.
     *
     * Two live naming conventions. Single-sample instances publish
     * "<name> L.txt"; multi-sample rigs number their sweeps with NO
     * un-numbered alias — Listener's GRAS publishes only "<name> L1.txt" —
     * so the first sample is the fallback. (Verified live: precog serves
     * "… L.txt" and 404s "… L1.txt"; listener is the exact inverse.)
     */
    suspend fun fetchMeasurementChannel(
        host: String,
        fileName: String,
        channel: String,
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        fetchChannelWithSample(host, fileName, channel)
    }

    private fun fetchChannelWithSample(
        host: String,
        fileName: String,
        channel: String,
    ): Pair<String, String>? {
        for (suffix in arrayOf(channel, "${channel}1")) {
            fetchSampleUrl(host, fileName, suffix)?.let { return it to suffix }
        }
        return null
    }

    /** Fetch one exact sample file ("L2", "R3", …); null when not published. */
    suspend fun fetchMeasurementSampleText(host: String, fileName: String, sample: String): String? =
        withContext(Dispatchers.IO) { fetchSampleUrl(host, fileName, sample) }

    /**
     * Which sample files exist for a channel — probes "<name> L.txt" and
     * "<name> L1..L6.txt" with HEAD requests (headers only, no body). Called
     * lazily on first stepper use and cached by the caller, so browsing never
     * pays for it.
     */
    suspend fun listSamples(host: String, fileName: String, channelPrefix: String): List<String> =
        withContext(Dispatchers.IO) {
            val candidates = listOf(channelPrefix) + (1..6).map { "$channelPrefix$it" }
            candidates.filter { sampleExists(host, fileName, it) }
        }

    private fun fetchSampleUrl(host: String, fileName: String, sample: String): String? {
        val name = URLEncoder.encode("$fileName $sample.txt", "UTF-8").replace("+", "%20")
        return fetchUrl("$host/data/$name")
    }

    private fun sampleExists(host: String, fileName: String, sample: String): Boolean = try {
        val name = URLEncoder.encode("$fileName $sample.txt", "UTF-8").replace("+", "%20")
        val connection = (URL("$host/data/$name").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        connection.responseCode == 200
    } catch (_: Exception) {
        false
    }

    fun clearCache() {
        cachedHeadphones = null
        lastFetchTime = 0L
    }

    private fun isCacheValid(): Boolean =
        cachedHeadphones != null && (System.currentTimeMillis() - lastFetchTime) < CACHE_TTL_MS

    private fun fetchFromSource(src: Source): List<Headphone> = try {
        val body = fetchUrl("${src.host}/data/phone_book.json") ?: return emptyList()
        parsePhoneBook(body, src)
    } catch (e: Exception) {
        Log.w(TAG, "Source ${src.host} failed: ${e.message}")
        emptyList()
    }

    /**
     * Canonical CrinGraph schema (verified against 12 live instances):
     *
     *   [{ "name": "<brand>", "phones": [
     *       { "name": "<model>", "file": "<basename>" | ["v1","v2",...],
     *         "suffix": ["", "(variant tag)", ...] }, ... ]}]
     *
     * `file` may be a string OR an array of variant basenames; when it's an
     * array the parallel `suffix` array tags each variant ("(Spring tips)",
     * "(ANC On)", etc.) and we emit one entry per variant. Optional fields
     * (reviewScore / reviewLink / price / shopLink) are ignored.
     */
    private fun parsePhoneBook(body: String, src: Source): List<Headphone> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val brands = (root as? JsonArray) ?: return emptyList()
        val out = mutableListOf<Headphone>()
        for (brand in brands) {
            val brandObj = brand as? JsonObject ?: continue
            val brandName = brandObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val phones = brandObj["phones"]?.jsonArray ?: continue
            for (phone in phones) {
                val phoneObj = phone as? JsonObject ?: continue
                val modelName = phoneObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val variants = extractVariants(phoneObj, modelName)
                for ((variantTag, fileBase) in variants) {
                    val displayName = buildString {
                        if (brandName.isNotBlank()) {
                            append(brandName); append(' ')
                        }
                        append(modelName)
                        if (variantTag.isNotBlank()) {
                            append(' '); append(variantTag)
                        }
                    }
                    out += Headphone(
                        id = "${src.label}_$displayName".replace(' ', '_').lowercase(),
                        name = displayName,
                        type = src.type ?: "in-ear",
                        measurements = listOf(
                            AutoEqMeasurement(
                                source = src.label,
                                target = "squiglink",
                                path = src.host,
                                fileName = fileBase,
                                rig = src.rig,
                                host = src.host,
                            )
                        )
                    )
                }
            }
        }
        return out
    }

    /** Returns (suffixTag, fileBasename) pairs for the variants in a phone entry. */
    private fun extractVariants(phoneObj: JsonObject, fallbackName: String): List<Pair<String, String>> {
        val fileEl = phoneObj["file"] ?: return listOf("" to fallbackName)
        val suffixEl = phoneObj["suffix"] as? JsonArray
        return when (fileEl) {
            is JsonPrimitive -> listOf("" to (fileEl.contentOrNull ?: fallbackName))
            is JsonArray -> fileEl.mapIndexed { i, el ->
                val basename = (el as? JsonPrimitive)?.contentOrNull ?: fallbackName
                val tag = (suffixEl?.getOrNull(i) as? JsonPrimitive)?.contentOrNull.orEmpty()
                tag to basename
            }
            else -> listOf("" to fallbackName)
        }
    }

    private fun mergeByName(all: List<Headphone>): List<Headphone> =
        all.groupBy { it.name }.map { (name, group) ->
            Headphone(
                id = group.first().id,
                name = name,
                type = group.first().type,
                measurements = group.flatMap { it.measurements },
            )
        }.sortedBy { it.name.lowercase() }

    private fun fetchUrl(urlString: String): String? {
        return try {
            val connection = (URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json,text/plain;q=0.9,*/*;q=0.5")
            }
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
