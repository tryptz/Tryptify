package tf.monochrome.android.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import tf.monochrome.android.data.api.model.ApiTrack
import tf.monochrome.android.data.api.model.SearchItem
import tf.monochrome.android.data.api.model.SearchResponse

/**
 * Reads HiFi API answers in the layouts current servers send.
 *
 * hifi-api (and TrypT HiFi, which ports it) does not wrap everything in
 * {version, data}. Search for artists, albums and playlists returns TIDAL's
 * top-hits answer — one page per type, {artists: {items}, albums: {items},
 * ...} — and the artist, playlist and similar-artist routes name what they
 * return: {artist: {...}, cover}, {playlist: {...}, items}, {artists: [...]}.
 * Read as a flat {items} page or a bare entity, every one of those parses
 * "successfully" into nothing, which is how album, artist and playlist search,
 * artist pages and playlist headers came up empty.
 *
 * Kept free of Android types so it can be unit tested.
 */
object HifiPayload {

    /** What a search was for, and so which page of a top-hits answer it wants. */
    enum class Kind(val key: String) {
        TRACKS("tracks"),
        ALBUMS("albums"),
        ARTISTS("artists"),
        PLAYLISTS("playlists"),
    }

    /**
     * The [kind] items in a search-shaped answer: a flat {items} page (track
     * search), the [kind] page of a top-hits answer, a bare array under the
     * [kind] key (similar artists), or a bare array. Each item is read on its
     * own, so one odd item drops only itself, not the whole page.
     */
    fun search(json: Json, body: String, kind: Kind): SearchResponse {
        val payload = unwrap(parse(json, body) ?: return SearchResponse())
        val items: JsonArray = when (payload) {
            is JsonArray -> payload
            is JsonObject -> {
                val page = payload[kind.key]
                when {
                    payload["items"] is JsonArray -> payload["items"] as JsonArray
                    page is JsonObject && page["items"] is JsonArray -> page["items"] as JsonArray
                    page is JsonArray -> page
                    else -> return SearchResponse()
                }
            }
            else -> return SearchResponse()
        }
        return SearchResponse(
            items = items.mapNotNull { runCatching { json.decodeFromJsonElement<SearchItem>(it) }.getOrNull() },
        )
    }

    /**
     * The tracks in a {items: [...]} page whose entries may each be wrapped:
     * TIDAL's recommendations list {track: {...}, sources}, album pages
     * {item: {...}, type}. Bare track entries are read as they are.
     */
    fun tracks(json: Json, body: String): List<ApiTrack> {
        val payload = unwrap(parse(json, body) ?: return emptyList())
        val items = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["items"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val track = (obj["track"] as? JsonObject) ?: (obj["item"] as? JsonObject) ?: obj
            runCatching { json.decodeFromJsonElement<ApiTrack>(track) }.getOrNull()
        }
    }

    /**
     * The entity an answer carries, as one JSON object: the {data} of a
     * wrapped answer, or the object under [key] with the answer's other fields
     * beside it ({playlist: {...}, items} reads as the playlist with its
     * items). An answer that is already the entity is returned as it is.
     */
    fun entity(json: Json, body: String, key: String): String {
        val root = parse(json, body) ?: return body
        val payload = unwrap(root)
        if (payload !== root) return payload.toString()
        val obj = root as? JsonObject ?: return body
        val inner = obj[key] as? JsonObject ?: return body
        val siblings = obj.filterKeys { it != key && it != "version" && it !in inner }
        return JsonObject(inner + siblings).toString()
    }

    private fun parse(json: Json, body: String): JsonElement? =
        runCatching { json.parseToJsonElement(body) }.getOrNull()

    /** {version, data: X} -> X; anything else unchanged. */
    private fun unwrap(root: JsonElement): JsonElement =
        (root as? JsonObject)?.get("data") ?: root
}
