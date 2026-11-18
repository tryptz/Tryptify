package tf.monochrome.android.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * HeadphoneAutoEqApi - Fetches headphone profiles from jaakkopasanen/AutoEq GitHub repo
 *
 * Repo structure: results/{source}/{type}/{headphone_name}/
 * e.g. results/oratory1990/over-ear/Sennheiser HD 600/
 *
 * Uses the Git Trees API (recursive) to fetch the full file tree in a single call,
 * then parses headphone entries from the paths.
 */
class HeadphoneAutoEqApi {
    companion object {
        private const val TAG = "HeadphoneAutoEqApi"
        private const val REPO_OWNER = "jaakkopasanen"
        private const val REPO_NAME = "AutoEq"
        private const val TREE_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/git/trees/master?recursive=1"
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/master"
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)

        // Known measurement sources in the results/ folder
        private val KNOWN_TYPES = setOf("over-ear", "in-ear", "earbud")
    }

    private var cachedHeadphones: List<Headphone>? = null
    private var lastFetchTime: Long = 0L

    /**
     * Fetch all headphones from the AutoEq repo using Git Trees API.
     *
     * Parses paths like: results/oratory1990/over-ear/AKG K240 Studio
     * Deduplicates by headphone name (same model from multiple sources → multiple measurements).
     */
    suspend fun fetchHeadphones(): Result<List<Headphone>> = withContext(Dispatchers.IO) {
        try {
            if (isCacheValid()) {
                return@withContext Result.success(cachedHeadphones ?: emptyList())
            }
            val url = URL(TREE_URL)
            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e(TAG, "GitHub API returned $responseCode")
                return@withContext Result.failure(Exception("GitHub API returned $responseCode"))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseBody).jsonObject
            val tree = root["tree"]?.jsonArray ?: return@withContext Result.failure(
                Exception("No tree in response")
            )

            data class HeadphoneEntry(
                val name: String,
                val type: String,      // over-ear, in-ear, earbud
                val source: String,    // oratory1990, crinacle, Rtings, etc.
                val path: String,      // headphone result folder path in repo
                val rig: MeasurementRig
            )

            // A headphone result folder is any directory under results/ that
            // holds a measurement CSV. Keying discovery on the CSV blob — not
            // an assumed folder depth — means every source is read regardless
            // of how it nests rig / form-factor folders (oratory1990 uses a
            // bare "over-ear", Rtings uses "HMS II.3 over-ear", crinacle uses
            // "GRAS 43AG-7 over-ear", etc.).
            val headphoneFolders = mutableSetOf<String>()
            for (item in tree) {
                val obj = item.jsonObject
                val path = obj["path"]?.jsonPrimitive?.content ?: continue
                val nodeType = obj["type"]?.jsonPrimitive?.content ?: continue

                if (nodeType != "blob") continue
                if (!path.startsWith("results/")) continue
                if (!path.endsWith(".csv", ignoreCase = true)) continue

                headphoneFolders.add(path.substringBeforeLast('/'))
            }

            val entries = mutableListOf<HeadphoneEntry>()
            for (folder in headphoneFolders) {
                val parts = folder.split("/")
                // results / source / [ ...rig & form-factor folders... ] / name
                if (parts.size < 3) continue

                val source = parts[1]
                val hpName = parts.last()
                val middle = parts.subList(2, parts.size - 1)

                val hpType = detectType(middle) ?: "over-ear"
                val rig = detectRig(middle) ?: rigFor(source, hpType)

                entries.add(HeadphoneEntry(hpName, hpType, source, folder, rig))
            }

            // Group by headphone name → deduplicate, merge measurements
            val grouped = entries.groupBy { it.name }
            val headphones = grouped.map { (name, group) ->
                val primaryType = group.first().type
                val measurements = group.map { entry ->
                    AutoEqMeasurement(
                        source = entry.source,
                        target = "AutoEq",
                        path = entry.path,
                        fileName = entry.name,
                        rig = entry.rig,
                    )
                }
                Headphone(
                    id = name.replace(" ", "_").lowercase(),
                    name = name,
                    type = primaryType,
                    data = emptyList(),
                    measurements = measurements
                )
            }.sortedBy { it.name.lowercase() }

            cachedHeadphones = headphones
            lastFetchTime = System.currentTimeMillis()

            Result.success(headphones)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch measurement CSV for a specific headphone.
     *
     * `headphoneId` is the normalized (lowercase, underscored) id used for
     * cache/preferences lookup. `headphoneName` is the original-case display
     * name. The GitHub raw URLs are case-sensitive, so the fallback paths
     * need the original name — passing only the lowercased id silently
     * breaks lookups like "AKG K371" vs "akg k371".
     */
    suspend fun fetchHeadphoneMeasurement(
        headphoneId: String,
        headphoneName: String = ""
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // Cache lookup is case-insensitive so we're tolerant of either
                // the normalized id or any mixed-case variant the caller passes.
                val headphone = cachedHeadphones?.find {
                    it.id.equals(headphoneId, ignoreCase = true) ||
                        it.name.equals(headphoneName, ignoreCase = true)
                }
                val measurement = headphone?.measurements?.firstOrNull()
                val basePath = measurement?.path

                if (basePath != null) {
                    // Try known measurement file names
                    val fileNames = listOfNotNull(
                        headphone.name.let { "$it.csv" },
                        "raw.csv"
                    )
                    for (fileName in fileNames) {
                        try {
                            val rawUrl = "$RAW_BASE/$basePath/$fileName"
                            val csvData = fetchUrl(rawUrl)
                            if (csvData != null) {
                                return@withContext Result.success(csvData)
                            }
                        } catch (_: Exception) { }
                    }
                }

                // Fallback: try common patterns using the original-case name
                // when available (falls back to de-underscored id if not).
                val encodedName = headphoneName.ifBlank { headphoneId.replace("_", " ") }
                val fallbackUrls = listOf(
                    "$RAW_BASE/results/oratory1990/over-ear/$encodedName/$encodedName.csv",
                    "$RAW_BASE/results/crinacle/over-ear/$encodedName/$encodedName.csv",
                    "$RAW_BASE/results/oratory1990/in-ear/$encodedName/$encodedName.csv",
                    "$RAW_BASE/results/crinacle/in-ear/$encodedName/$encodedName.csv",
                )
                for (url in fallbackUrls) {
                    try {
                        val csvData = fetchUrl(url)
                        if (csvData != null) {
                            return@withContext Result.success(csvData)
                        }
                    } catch (_: Exception) { }
                }

                Result.failure(Exception("No measurement data found for $headphoneId"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Fetch the measurement CSV for a specific measurement by its repo path.
     * Unlike [fetchHeadphoneMeasurement] this targets the exact source folder,
     * so picking a headphone's Rtings entry loads Rtings data even when the
     * same headphone also carries oratory1990/crinacle measurements.
     */
    suspend fun fetchMeasurementByPath(
        basePath: String,
        headphoneName: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            for (url in measurementUrls(basePath, headphoneName)) {
                try {
                    val csv = fetchUrl(url)
                    if (csv != null) return@withContext Result.success(csv)
                } catch (_: Exception) { }
            }
            Result.failure(Exception("No measurement data at $basePath"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The files [fetchMeasurementByPath] tries for one measurement, in order;
     * the offline download asks for the same ones.
     */
    fun measurementUrls(basePath: String, headphoneName: String): List<String> =
        listOf("$headphoneName.csv", "raw.csv").map { "$RAW_BASE/$basePath/$it" }

    suspend fun searchHeadphones(query: String): Result<List<Headphone>> {
        val all = fetchHeadphones().getOrNull() ?: return Result.success(emptyList())
        return Result.success(all.filter { it.name.contains(query, ignoreCase = true) })
    }

    suspend fun getHeadphonesByType(type: String): Result<List<Headphone>> {
        val all = fetchHeadphones().getOrNull() ?: return Result.success(emptyList())
        return Result.success(all.filter { it.type == type })
    }

    fun clearCache() {
        cachedHeadphones = null
        lastFetchTime = 0L
    }

    private fun isCacheValid(): Boolean =
        cachedHeadphones != null && (System.currentTimeMillis() - lastFetchTime) < CACHE_TTL_MS

    /**
     * Map an AutoEq source name (the first path component under `results/`)
     * to the rig that source publicly uses. Crinacle splits by form factor:
     * over-ear runs on a GRAS 43AG-7, in-ear on a 711 clone. Anything not in
     * this map falls through to UNKNOWN so the rig filter can group them
     * separately.
     */
    private fun rigFor(source: String, type: String): MeasurementRig = when (source.lowercase()) {
        "oratory1990" -> MeasurementRig.GRAS_43AC_10
        "crinacle" -> if (type == "over-ear") MeasurementRig.GRAS_43AG_7 else MeasurementRig.IEC_711_CLONE
        "innerfidelity" -> MeasurementRig.MINIDSP_EARS
        "headphonecom", "headphones.com" -> MeasurementRig.GRAS_43AG_7
        else -> MeasurementRig.UNKNOWN
    }

    /**
     * Find the form factor among a headphone folder's intermediate path
     * segments. Sources name this folder either bare ("over-ear") or with the
     * rig prefixed ("HMS II.3 over-ear", "GRAS 43AG-7 in-ear"), so we match on
     * a form-factor suffix. Returns null when no segment carries one.
     */
    private fun detectType(segments: List<String>): String? {
        for (segment in segments) {
            val s = segment.trim().lowercase()
            for (type in KNOWN_TYPES) {
                if (s == type || s.endsWith(" $type")) return type
            }
        }
        return null
    }

    /**
     * Find the acoustic rig among a headphone folder's intermediate path
     * segments (e.g. "HMS II.3 over-ear" → HMS II.3). Returns null when no
     * segment names a recognised rig, letting the caller fall back to the
     * per-source default.
     */
    private fun detectRig(segments: List<String>): MeasurementRig? {
        for (segment in segments) {
            val rig = rigFromLabel(segment)
            if (rig != MeasurementRig.UNKNOWN) return rig
        }
        return null
    }

    /** Map a rig folder label to the rig enum. */
    private fun rigFromLabel(label: String): MeasurementRig = when {
        label.contains("5128") -> MeasurementRig.BK_5128
        label.contains("4620") -> MeasurementRig.BK_4620
        label.contains("HMS", ignoreCase = true) -> MeasurementRig.HMS_II_3
        label.contains("43AG", ignoreCase = true) -> MeasurementRig.GRAS_43AG_7
        label.contains("43AC", ignoreCase = true) -> MeasurementRig.GRAS_43AC_10
        label.contains("45CA", ignoreCase = true) -> MeasurementRig.GRAS_45CA_10
        label.contains("RA0045", ignoreCase = true) -> MeasurementRig.GRAS_RA0045
        label.contains("EARS", ignoreCase = true) -> MeasurementRig.MINIDSP_EARS
        label.contains("711") -> MeasurementRig.IEC_711_CLONE
        else -> MeasurementRig.UNKNOWN
    }

    private fun fetchUrl(urlString: String): String? {
        val connection = (URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
        }
        return if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}
