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

    private fun tagged(type: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", value)
        }
}
