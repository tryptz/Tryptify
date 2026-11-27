package io.github.chronosauros.contour.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The SAVED version of a profile in a library file: its bands and preamp, as the Library row draws them. */
@Serializable
data class LibrarySaved(val bands: List<Band>, val preampDb: Double? = null)

/** One profile of a library file: the profile itself (the working copy) and its SAVED version ([saved] null = the file has none, the working copy stands in). */
data class LibraryEntry(val profile: Profile, val saved: LibrarySaved? = null)

/** What [LibraryFile.decode] made of a text. */
sealed interface LibraryRead {
    /** Not JSON, or not a Contour library file. */
    data object NotLibrary : LibraryRead

    /** A library file: the entries that read (in the file's order) and how many entries could not be read and were skipped. */
    data class Library(val entries: List<LibraryEntry>, val skipped: Int) : LibraryRead
}

/**
 * The whole-library file (BACKUP / RESTORE FROM BACKUP in the Library): one JSON file with every profile in library order, archived ones included, each with
 * its SAVED version.
 *
 * ```
 * { "format": "contour-library", "version": 1, "app": "<versionName>", "exportedAt": <epoch ms>,
 *   "profiles": [ { <the Profile as its serializer writes it>, "archived": <bool, always written>, "saved": { "bands": [...], "preampDb": <n|null> } }, ... ] }
 * ```
 *
 * Reading is tolerant: unknown keys and a higher `version` are read anyway, and an entry that cannot be read (a filter type of a newer build, a missing field, a
 * value that is not a number, more than [MAX_BANDS] bands) is skipped and counted while the others come in. Ids are not an identity here: the app gives every
 * restored profile and band new ones. UNDO / REDO steps and the DAC bookkeeping are not part of the file.
 */
object LibraryFile {
    const val FORMAT = "contour-library"
    const val VERSION = 1

    /** The most bands one profile may carry in a file (the biggest DAC has fewer). */
    const val MAX_BANDS = 64

    private val writer = Json { prettyPrint = true; encodeDefaults = true }
    // non-finite numbers are read and then refused by [valid], with the rest of the entry's checks
    private val reader = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

    /** The file's text (UTF-8 when written): [entries] in library order; an entry without a SAVED version gets its working copy as one. */
    fun encode(entries: List<LibraryEntry>, app: String, exportedAt: Long): String {
        val profiles = entries.map { e ->
            val p = writer.encodeToJsonElement(Profile.serializer(), e.profile).jsonObject
            val saved = e.saved ?: LibrarySaved(e.profile.bands, e.profile.preampDb)
            // Profile leaves "archived" out when false (the profile files keep their old bytes); the library file always says it
            JsonObject(p + mapOf("archived" to JsonPrimitive(e.profile.archived), "saved" to writer.encodeToJsonElement(LibrarySaved.serializer(), saved)))
        }
        val root = JsonObject(mapOf(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(VERSION),
            "app" to JsonPrimitive(app),
            "exportedAt" to JsonPrimitive(exportedAt),
            "profiles" to JsonArray(profiles),
        ))
        return writer.encodeToString(JsonElement.serializer(), root)
    }

    /** Never throws: a text that is not a library file is [LibraryRead.NotLibrary]. */
    fun decode(text: String): LibraryRead {
        val root = try { reader.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return LibraryRead.NotLibrary
        val format = root["format"] as? JsonPrimitive
        if (format == null || !format.isString || format.content != FORMAT) return LibraryRead.NotLibrary
        val items = root["profiles"] as? JsonArray ?: JsonArray(emptyList())
        val entries = items.mapNotNull(::entry)
        return LibraryRead.Library(entries, items.size - entries.size)
    }

    private fun entry(item: JsonElement): LibraryEntry? = try {
        val obj = item as JsonObject
        val profile = reader.decodeFromJsonElement(Profile.serializer(), obj)
        val saved = obj["saved"]?.takeUnless { it is JsonNull }?.let { reader.decodeFromJsonElement(LibrarySaved.serializer(), it) }
        if (valid(profile.bands, profile.preampDb) && (saved == null || valid(saved.bands, saved.preampDb))) LibraryEntry(profile, saved) else null
    } catch (e: Exception) {
        null
    }

    private fun valid(bands: List<Band>, preampDb: Double?): Boolean =
        bands.size <= MAX_BANDS &&
            bands.map { it.id }.toSet().size == bands.size &&
            bands.all { it.freqHz.isFinite() && it.freqHz > 0.0 && it.q.isFinite() && it.q > 0.0 && it.gainDb.isFinite() } &&
            (preampDb == null || preampDb.isFinite())
}
