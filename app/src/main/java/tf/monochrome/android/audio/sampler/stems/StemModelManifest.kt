// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a checkpoint is licensed under.
 *
 * Metadata, shown in the model details and carried into the sample library so
 * a stem can be traced back to what produced it. It does not decide whether a
 * model can be installed — [StemModel.installable] is about whether the asset
 * will actually run on this device.
 */
enum class ModelLicense(val id: String, val label: String) {
    MIT("MIT", "MIT"),
    APACHE_2("APACHE-2.0", "Apache 2.0"),
    MIT_WITH_ATTRIBUTION("MIT-ATTRIBUTION", "MIT (attribution required)"),
    PROPRIETARY("TRYPTIFY", "Tryptify"),
    CC_BY_NC("CC-BY-NC-4.0", "CC BY-NC 4.0"),
    CC_BY_NC_SA("CC-BY-NC-SA-4.0", "CC BY-NC-SA 4.0"),
    RESEARCH_ONLY("RESEARCH-ONLY", "Research only"),
    MUSDB_ENCUMBERED("MUSDB-ENCUMBERED", "MUSDB18-HQ trained"),
    UNKNOWN("UNKNOWN", "Unspecified"),
    ;

    /** True when the upstream terms are worth surfacing in the model details. */
    val restricted: Boolean
        get() = this == CC_BY_NC || this == CC_BY_NC_SA ||
            this == RESEARCH_ONLY || this == MUSDB_ENCUMBERED

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
    /**
     * The order the model emits its stems in, which is not the order [Stem]
     * declares them and not one anyone would guess — HTDemucs emits
     * `drums, bass, other, vocals`. Get it wrong and separation still appears
     * to work: every stem is produced, every level looks right, and the vocal
     * file contains the drums. Nothing downstream can detect that, so the
     * order travels with the model rather than being inferred.
     */
    val outputOrder: List<Stem> = stems.toList(),
    val modelSampleRate: Int,
    val segmentFrames: Int,
    val minimumAndroid: Int,
    val abi: String,
    val htpArchs: List<String> = emptyList(),
) {
    /**
     * Whether this asset will actually run here.
     *
     * Every check is mechanical: the archive has to be verifiable, reachable,
     * and built for this device. Nothing here is about what the weights are
     * licensed under — that is recorded in [license] and shown in the model
     * details, not used to refuse a download.
     */
    fun installable(androidSdk: Int, abis: List<String>): Boolean =
        sha256.length == 64 &&
            sizeBytes > 0 &&
            url.startsWith("https://") &&
            androidSdk >= minimumAndroid &&
            abis.contains(abi)

    /** Why it will not run, for the UI. Null when it will. */
    fun blockedReason(androidSdk: Int, abis: List<String>): String? = when {
        sha256.length != 64 -> "Manifest has no valid checksum"
        sizeBytes <= 0 -> "Manifest has no size"
        // HTTPS is kept as a hard requirement because the download is verified
        // against a hash from the same document — fetching either over plain
        // HTTP would let one attacker rewrite both.
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
            // Kept as a list first: the array's order is the model's channel
            // order, and collapsing straight to a set would throw away the one
            // thing that says which output is which stem.
            val order = strings("stems").mapNotNull { Stem.fromId(it) }

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
                license = ModelLicense.fromId(str("license")),
                sourceModel = str("sourceModel") ?: "unknown",
                attribution = str("attribution"),
                // A manifest that does not say assumes the classic four.
                // Defaulting to six would have a four-stem model advertising a
                // piano stem it cannot produce.
                stems = order.toSet().ifEmpty { Stem.FOUR },
                outputOrder = order.ifEmpty { Stem.FOUR.toList() },
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

    /** Models that will run on this device. */
    fun installable(androidSdk: Int, abis: List<String>): List<StemModel> =
        models.filter { it.installable(androidSdk, abis) }

    /**
     * The one to offer by default: accelerated first, then the model that
     * produces the most stems, then the newest.
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
