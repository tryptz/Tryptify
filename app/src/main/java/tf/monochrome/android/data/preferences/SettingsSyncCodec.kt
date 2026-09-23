package tf.monochrome.android.data.preferences

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Pure, framework-free codec for the settings-sync snapshot.
 *
 * DataStore stores seven distinct primitive types, but JSON collapses
 * Int/Long/Float/Double into a single "number" — so a naive encoder silently
 * corrupts a Long into an Int or a Float into a Double on the way back. Every
 * value is therefore tagged with its type (`t`) alongside its value (`v`), which
 * makes the round-trip exact and lets the whole thing be unit-tested without
 * Android, DataStore, or Supabase.
 *
 * Supported value types: Boolean, Int, Long, Float, Double, String, Set<String>.
 */
object SettingsSyncCodec {
    private val json = Json { ignoreUnknownKeys = true }

    private const val T_BOOL = "b"
    private const val T_INT = "i"
    private const val T_LONG = "l"
    private const val T_FLOAT = "f"
    private const val T_DOUBLE = "d"
    private const val T_STRING = "s"
    private const val T_STRING_SET = "ss"

    /**
     * Encode a `name -> value` snapshot to a tagged JSON object string. Values
     * of an unsupported type are skipped rather than throwing.
     */
    fun encode(values: Map<String, Any>): String {
        val obj = buildJsonObject {
            values.forEach { (name, value) ->
                val entry: JsonObject? = when (value) {
                    is Boolean -> tagged(T_BOOL, JsonPrimitive(value))
                    is Int -> tagged(T_INT, JsonPrimitive(value))
                    is Long -> tagged(T_LONG, JsonPrimitive(value))
                    is Float -> tagged(T_FLOAT, JsonPrimitive(value))
                    is Double -> tagged(T_DOUBLE, JsonPrimitive(value))
                    is String -> tagged(T_STRING, JsonPrimitive(value))
                    is Set<*> -> tagged(T_STRING_SET, buildJsonArray {
                        value.forEach { add(JsonPrimitive(it.toString())) }
                    })
                    else -> null
                }
                if (entry != null) put(name, entry)
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Decode a tagged JSON snapshot back to a `name -> value` map with each
     * value restored to its exact original type. Malformed or unknown-typed
     * entries are skipped; a completely unparseable payload yields an empty map.
     */
    fun decode(payload: String): Map<String, Any> {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Any>(root.size)
        root.forEach { (name, element) ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val type = o["t"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val v = o["v"] ?: return@forEach
            val value: Any? = runCatching {
                when (type) {
                    T_BOOL -> v.jsonPrimitive.boolean
                    T_INT -> v.jsonPrimitive.int
                    T_LONG -> v.jsonPrimitive.long
                    T_FLOAT -> v.jsonPrimitive.float
                    T_DOUBLE -> v.jsonPrimitive.double
                    T_STRING -> v.jsonPrimitive.contentOrNull
                    T_STRING_SET -> v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    else -> null
                }
            }.getOrNull()
            if (value != null) out[name] = value
        }
        return out
    }

    /**
     * The payload to upload: the cloud's snapshot with this device's values laid
     * over it.
     *
     * The upload used to be this device's snapshot alone, and a snapshot only
     * holds the keys set on this device, so every push deleted from the cloud
     * whatever this device happened not to have — every setting a newer build
     * syncs that this one doesn't know, and, after a sign-in whose pull failed,
     * nearly everything. Merging means a push can add or change keys but never
     * remove one.
     *
     * Entries are merged as raw JSON, never decoded, so a value whose type tag
     * this build doesn't understand still survives the round trip.
     *
     * Returns null when [cloud] exists but isn't a JSON object: that is a format
     * this build can't read, and overwriting it would destroy it.
     */
    fun merge(cloud: String?, local: String): String? {
        val cloudRoot = if (cloud.isNullOrBlank()) JsonObject(emptyMap())
            else runCatching { json.parseToJsonElement(cloud).jsonObject }.getOrNull() ?: return null
        val localRoot = runCatching { json.parseToJsonElement(local).jsonObject }.getOrNull() ?: return null
        return json.encodeToString(JsonObject.serializer(), JsonObject(cloudRoot + localRoot))
    }

    /**
     * Keys this device changed since [base], the last snapshot it and the cloud
     * agreed on — the edits a pull must not overwrite.
     *
     * A pull used to apply the cloud copy wholesale, so settings changed on a
     * device that launched offline were silently reverted by the next online
     * launch. With a base, each side keeps what only it changed: keys edited
     * here survive the pull, everything else takes the cloud's value.
     *
     * No base (this account has never synced on this device) means no local
     * edits: a device signing in adopts the account's settings, as it always
     * has.
     */
    fun changedSince(base: String?, local: String): Set<String> {
        if (base.isNullOrBlank()) return emptySet()
        val baseRoot = runCatching { json.parseToJsonElement(base).jsonObject }.getOrNull() ?: return emptySet()
        val localRoot = runCatching { json.parseToJsonElement(local).jsonObject }.getOrNull() ?: return emptySet()
        return localRoot.filter { (name, value) -> baseRoot[name] != value }.keys
    }

    /** Only the entries of [payload] named in [names]; an empty object if it can't be read. */
    fun only(payload: String, names: Set<String>): String {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "{}"
        return json.encodeToString(JsonObject.serializer(), JsonObject(root.filterKeys { it in names }))
    }

    /** [payload] without the entries named in [names], or [payload] itself if it can't be read. */
    fun without(payload: String, names: Set<String>): String {
        if (names.isEmpty()) return payload
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return payload
        return json.encodeToString(JsonObject.serializer(), JsonObject(root - names))
    }

    private fun tagged(type: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", value)
        }
}
