package tf.monochrome.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
            Source("https://graph.headphones.com", "Headphones.com (Resolve)", MeasurementRig.GRAS_43AG_7, null),
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
                val name = URLEncoder.encode("$fileName $channel.txt", "UTF-8").replace("+", "%20")
                val body = fetchUrl("$host/data/$name")
                if (body != null) return@withContext body
            }
            null
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
     * phone_book.json shapes vary slightly across CrinGraph forks, but the
     * common contract is an array of brands, each with `name` and `phones`,
     * where each phone has a `phone` (display name) and optional `fileName`
     * (used for the FR data filename if it differs from the display name).
     */
    private fun parsePhoneBook(body: String, src: Source): List<Headphone> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val brands = root.jsonArray
        val out = mutableListOf<Headphone>()
        for (brand in brands) {
            val brandObj = brand as? JsonObject ?: continue
            val brandName = brandObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val phones = brandObj["phones"]?.jsonArray ?: continue
            for (phone in phones) {
                val phoneObj = phone as? JsonObject ?: continue
                val phoneName = phoneObj["phone"]?.jsonPrimitive?.contentOrNull ?: continue
                val displayName = if (brandName.isNotBlank()) "$brandName $phoneName" else phoneName
                val fileName = phoneObj["fileName"]?.jsonPrimitive?.contentOrNull ?: phoneName
                out += Headphone(
                    id = displayName.replace(' ', '_').lowercase(),
                    name = displayName,
                    type = src.type ?: "in-ear",
                    measurements = listOf(
                        AutoEqMeasurement(
                            source = src.label,
                            target = "squiglink",
                            path = src.host,
                            fileName = fileName,
                            rig = src.rig,
                            host = src.host,
                        )
                    )
                )
            }
        }
        return out
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
