// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How the weights are licensed, which is the field that decides whether
 * Tryptify may ship them at all.
 *
 * This is an enum and not a string because it is a gate, not a label. The
 * research behind MODEL_CARD.md found that essentially every high-quality open
 * 4-stem checkpoint carries a non-commercial restriction — Meta's Demucs
 * weights are research-only regardless of the MIT code licence, and almost
 * everything else was trained on MUSDB18-HQ, whose own terms permit
 * "educational purposes only". Those restrictions travel with the weights.
 *
 * Making it a typed field means the decision lives in code that can be tested,
 * rather than in a JSON string somebody could edit without noticing what they
 * had done.
 */
enum class ModelLicense(val id: String, val label: String, val redistributable: Boolean) {
    /** Permissive. Ship it. */
    MIT("MIT", "MIT", true),
    APACHE_2("APACHE-2.0", "Apache 2.0", true),

    /** Permissive but the attribution has to appear in the app. */
    MIT_WITH_ATTRIBUTION("MIT-ATTRIBUTION", "MIT (attribution required)", true),

    /** Weights trained in-house on a cleared dataset. */
    PROPRIETARY("TRYPTIFY", "Tryptify", true),

    /** Non-commercial. Cannot be redistributed here. */
    CC_BY_NC("CC-BY-NC-4.0", "CC BY-NC 4.0", false),
    CC_BY_NC_SA("CC-BY-NC-SA-4.0", "CC BY-NC-SA 4.0", false),

    /** Meta's Demucs weights, and anything else marked for science only. */
    RESEARCH_ONLY("RESEARCH-ONLY", "Research only", false),

    /** Trained on MUSDB18-HQ, so it inherits that dataset's restriction. */
    MUSDB_ENCUMBERED("MUSDB-ENCUMBERED", "Non-commercial (MUSDB18-HQ)", false),

    /** Anything we could not positively identify. Treated as unshippable. */
    UNKNOWN("UNKNOWN", "Unverified", false),
    ;

    companion object {
        fun fromId(id: String?): ModelLicense =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Which runtime an asset is built for. */
enum class ModelFormat(val id: String) {
    ONNX("ONNX"),
    QNN_CONTEXT("QNN"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromId(id: String?): ModelFormat =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One downloadable model.
 *
 * [segmentFrames] and [modelSampleRate] are part of the manifest rather than
 * hard-coded because they belong to the weights: HTDemucs wants roughly 7.8
 * seconds at 44.1 kHz, a RoFormer wants 8, and getting it wrong does not fail
 * loudly, it just separates badly.
 *
 * [htpArchs] is only meaningful for [ModelFormat.QNN_CONTEXT]. QNN context
 * binaries are compiled for a specific Hexagon architecture, which is the
 * detail that breaks the original plan of shipping one universal asset — see
 * MODEL_CARD.md, Appendix A.
 */
data class StemModel(
    val id: String,
    val name: String,
    val version: String,
    val format: ModelFormat,
    val backend: BackendKind,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: ModelLicense,
    val sourceModel: String,
    val attribution: String?,
    val stems: Set<Stem>,
    val modelSampleRate: Int,
    val segmentFrames: Int,
    val minimumAndroid: Int,
    val abi: String,
    val htpArchs: List<String> = emptyList(),
) {
    /**
     * Whether this build may download and install these weights.
     *
     * Licence first, then the mechanical checks. A model that fails the licence
     * check is never offered no matter how well it would run.
     */
    fun installable(androidSdk: Int, abis: List<String>): Boolean =
        license.redistributable &&
            sha256.length == 64 &&
            sizeBytes > 0 &&
            url.startsWith("https://") &&
            androidSdk >= minimumAndroid &&
            abis.contains(abi)

    /** Why it is not installable, for the UI. Null when it is. */
    fun blockedReason(androidSdk: Int, abis: List<String>): String? = when {
        !license.redistributable ->
            "${license.label} — cannot be distributed with Tryptify"
        sha256.length != 64 -> "Manifest has no valid checksum"
        sizeBytes <= 0 -> "Manifest has no size"
        !url.startsWith("https://") -> "Model URL is not HTTPS"
        androidSdk < minimumAndroid -> "Needs Android API $minimumAndroid"
        !abis.contains(abi) -> "Built for $abi"
        else -> null
    }

    companion object {

        fun fromJson(json: JsonObject): StemModel? {
            fun str(key: String): String? =
                runCatching { json[key]?.jsonPrimitive?.content }.getOrNull()
                    ?.takeIf { it.isNotBlank() }

            fun int(key: String, fallback: Int): Int =
                runCatching { json[key]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: fallback

            fun long(key: String, fallback: Long): Long =
                runCatching { json[key]?.jsonPrimitive?.content?.toLong() }.getOrNull() ?: fallback

            fun strings(key: String): List<String> =
                runCatching {
                    json[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                }.getOrNull().orEmpty()

            val id = str("id") ?: return null
            val stems = strings("stems").mapNotNull { Stem.fromId(it) }.toSet()

            return StemModel(
                id = id,
                name = str("name") ?: id,
                version = str("version") ?: "0.0.0",
                format = ModelFormat.fromId(str("format")),
                backend = when (str("backend")?.uppercase()) {
                    "QNN", "QUALCOMM_NPU" -> BackendKind.QNN
                    else -> BackendKind.CPU
                },
                url = str("url").orEmpty(),
                sha256 = str("sha256")?.lowercase().orEmpty(),
                sizeBytes = long("sizeBytes", 0L),
                // An absent licence is UNKNOWN, and UNKNOWN is not
                // redistributable. Omitting the field cannot be a way to
                // sidestep the check.
                license = ModelLicense.fromId(str("license")),
                sourceModel = str("sourceModel") ?: "unknown",
                attribution = str("attribution"),
                stems = stems.ifEmpty { Stem.entries.toSet() },
                modelSampleRate = int("modelSampleRate", 44100),
                segmentFrames = int("segmentFrames", DEFAULT_SEGMENT_FRAMES),
                minimumAndroid = int("minimumAndroid", 26),
                abi = str("abi") ?: "arm64-v8a",
                htpArchs = strings("htpArchs"),
            )
        }

        /** HTDemucs' window: 7.8 s at 44.1 kHz. A sane default, not a rule. */
        const val DEFAULT_SEGMENT_FRAMES = 343980
    }
}

/**
 * The manifest published alongside a GitHub Release.
 *
 * Parsed defensively: a malformed entry is dropped rather than failing the
 * whole document, so one bad model cannot take the feature offline.
 */
data class StemModelCatalog(
    val schemaVersion: Int,
    val models: List<StemModel>,
) {

    /** Models this device and this build may actually install. */
    fun installable(androidSdk: Int, abis: List<String>): List<StemModel> =
        models.filter { it.installable(androidSdk, abis) }

    /**
     * The one to offer, preferring accelerated backends but never at the cost
     * of the licence gate.
     */
    fun best(androidSdk: Int, abis: List<String>, allowNpu: Boolean): StemModel? =
        installable(androidSdk, abis)
            .filter { allowNpu || it.backend != BackendKind.QNN }
            .maxWithOrNull(
                compareBy<StemModel> { if (it.backend == BackendKind.QNN) 1 else 0 }
                    .thenBy { it.stems.size }
                    .thenBy { versionKey(it.version) },
            )

    /** Whether [candidate] is newer than [installed]. */
    fun isUpdate(installed: StemModel?, candidate: StemModel): Boolean {
        if (installed == null) return true
        if (installed.id != candidate.id) return false
        // Content hash first: a re-release with the same version but different
        // weights is still an update, and a version bump with identical bytes
        // is not worth a download.
        if (installed.sha256 == candidate.sha256) return false
        return versionKey(candidate.version) > versionKey(installed.version)
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): StemModelCatalog? = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val array = root["models"]?.jsonArray ?: return@runCatching null
            // One malformed entry is dropped rather than failing the document.
            // A single bad model must not take the whole feature offline.
            val models = array.mapNotNull { element ->
                runCatching { StemModel.fromJson(element.jsonObject) }.getOrNull()
            }
            val schema = runCatching {
                root["schemaVersion"]?.jsonPrimitive?.content?.toInt()
            }.getOrNull() ?: 1
            StemModelCatalog(schema, models)
        }.getOrNull()

        /**
         * Comparable form of a dotted version.
         *
         * Padded per component so 1.10.0 sorts above 1.9.0, which plain string
         * comparison gets wrong and which is the classic way an update is
         * silently never offered.
         */
        fun versionKey(version: String): Long {
            val parts = version.split('.', '-', '+')
            var key = 0L
            for (i in 0 until 3) {
                val value = parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toLongOrNull() ?: 0L
                key = key * 100_000L + value.coerceIn(0L, 99_999L)
            }
            return key
        }
    }
}
