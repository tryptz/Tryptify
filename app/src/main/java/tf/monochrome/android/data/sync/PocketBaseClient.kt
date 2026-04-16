package tf.monochrome.android.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PocketBase client for cloud data sync.
 * Mirrors the web reference at js/accounts/pocketbase.js.
 *
 * PocketBase is used ONLY for data storage (DB_users collection),
 * NOT for authentication. Auth is handled by Appwrite (GoogleAuthManager).
 * The Appwrite user ID is stored as `firebase_id` in the DB_users collection.
 */
@Singleton
class PocketBaseClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private val BASE_URL: String = tf.monochrome.android.BuildConfig.POCKETBASE_URL
        private const val COLLECTION = "DB_users"
    }

    private var cachedRecordId: String? = null
    private var cachedUid: String? = null

    /**
     * Find or create a DB_users record for the given Appwrite user ID.
     * Mirrors syncManager._getUserRecord(uid) from the web reference.
     */
    suspend fun getUserRecord(uid: String): PocketBaseUserRecord? {
        if (uid.isBlank()) return null

        // Return cached if same user
        if (cachedRecordId != null && cachedUid == uid) {
            return PocketBaseUserRecord(id = cachedRecordId!!, firebaseId = uid)
        }

        return try {
            // Look up existing record
            val response = httpClient.get("$BASE_URL/api/collections/$COLLECTION/records") {
                parameter("filter", "firebase_id=\"$uid\"")
                parameter("perPage", 1)
                parameter("sort", "-username")
            }

            val body = response.bodyAsText()
            val result = json.parseToJsonElement(body).jsonObject
            val items = result["items"]?.jsonArray ?: return null

            if (items.isNotEmpty()) {
                val record = items[0].jsonObject
                val recordId = record["id"]?.jsonPrimitive?.content ?: return null
                cachedRecordId = recordId
                cachedUid = uid
                return PocketBaseUserRecord(id = recordId, firebaseId = uid)
            }

            // Create new record
            val createResponse = httpClient.post("$BASE_URL/api/collections/$COLLECTION/records") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("firebase_id", uid)
                    put("library", "{}")
                    put("history", "[]")
                    put("user_playlists", "{}")
                    put("user_folders", "{}")
                }))
            }

            if (createResponse.status.isSuccess()) {
                val createBody = createResponse.bodyAsText()
                val record = json.parseToJsonElement(createBody).jsonObject
                val recordId = record["id"]?.jsonPrimitive?.content ?: return null
                cachedRecordId = recordId
                cachedUid = uid
                PocketBaseUserRecord(id = recordId, firebaseId = uid)
            } else {
                // Race condition: retry fetch
                val retryResponse = httpClient.get("$BASE_URL/api/collections/$COLLECTION/records") {
                    parameter("filter", "firebase_id=\"$uid\"")
                    parameter("perPage", 1)
                }
                val retryBody = retryResponse.bodyAsText()
                val retryResult = json.parseToJsonElement(retryBody).jsonObject
                val retryItems = retryResult["items"]?.jsonArray
                if (retryItems != null && retryItems.isNotEmpty()) {
                    val record = retryItems[0].jsonObject
                    val recordId = record["id"]?.jsonPrimitive?.content ?: return null
                    cachedRecordId = recordId
                    cachedUid = uid
                    PocketBaseUserRecord(id = recordId, firebaseId = uid)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get all user data from PocketBase.
     * Mirrors syncManager.getUserData() from the web reference.
     */
    suspend fun getUserData(uid: String): CloudUserData? {
        val record = getUserRecord(uid) ?: return null

        return try {
            val response = httpClient.get("$BASE_URL/api/collections/$COLLECTION/records/${record.id}")
            val body = response.bodyAsText()
            val obj = json.parseToJsonElement(body).jsonObject

            CloudUserData(
                library = safeParseField(obj["library"], "{}"),
                history = safeParseField(obj["history"], "[]"),
                userPlaylists = safeParseField(obj["user_playlists"], "{}"),
                userFolders = safeParseField(obj["user_folders"], "{}")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Update a JSON field on the user's DB_users record.
     * Mirrors syncManager._updateUserJSON() from the web reference.
     */
    suspend fun updateUserField(uid: String, field: String, data: String) {
        val record = getUserRecord(uid) ?: return

        try {
            val response = httpClient.patch("$BASE_URL/api/collections/$COLLECTION/records/${record.id}") {
                contentType(ContentType.Application.Json)
                setBody("{\"$field\":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(data))}}")
            }
        } catch (_: Exception) {
        }
    }

    fun clearCache() {
        cachedRecordId = null
        cachedUid = null
    }

    private fun safeParseField(element: JsonElement?, fallback: String): String {
        if (element == null) return fallback
        return try {
            if (element is JsonPrimitive && element.isString) {
                element.content.ifBlank { fallback }
            } else {
                element.toString()
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun buildJsonObject(builder: JsonObjectBuilder.() -> Unit): JsonObject {
        return JsonObjectBuilder().apply(builder).build()
    }

    private class JsonObjectBuilder {
        private val map = mutableMapOf<String, JsonElement>()
        fun put(key: String, value: String) { map[key] = JsonPrimitive(value) }
        fun build() = JsonObject(map)
    }
}

data class PocketBaseUserRecord(
    val id: String,
    val firebaseId: String
)

data class CloudUserData(
    val library: String,
    val history: String,
    val userPlaylists: String,
    val userFolders: String
)
