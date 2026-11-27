package io.github.chronosauros.contour.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The whole-library file (BACKUP / RESTORE FROM BACKUP): the round trip, what is skipped, what is refused, what is tolerated. */
class LibraryFileTest {
    private val json = Json
    private fun band(id: String, type: FilterType, f: Double, g: Double, q: Double, on: Boolean = true) = Band(id, type, f, g, q, on)

    // two profiles: NIGHTFALL is active and has an unsaved change (its working copy differs from its SAVED version), STUDIO is archived
    private val nightfallSaved = LibrarySaved(listOf(band("n1", FilterType.LOW_SHELF, 105.0, 5.0, 0.7), band("n2", FilterType.PEAK, 3196.0, -3.0, 0.85)), -6.5)
    private val nightfall = Profile(
        id = "p-0001", name = "NIGHTFALL", subtitle = "HEDD D1", icon = "moon",
        bands = listOf(band("n1", FilterType.LOW_SHELF, 105.0, 6.5, 0.7), band("n2", FilterType.PEAK, 3196.0, -3.0, 0.85), band("n3", FilterType.HIGH_PASS, 20.0, 0.0, 0.707, on = false)),
        preampDb = -6.5, createdAt = 1_700_000_000_000, updatedAt = 1_700_000_100_000,
    )
    private val studio = Profile(
        id = "p-0002", name = "STUDIO", icon = "speaker",
        bands = listOf(band("s1", FilterType.HIGH_SHELF, 7000.0, 4.0, 0.7)),
        preampDb = null, createdAt = 1_690_000_000_000, updatedAt = 1_690_000_000_000, archived = true,
    )
    private val studioSaved = LibrarySaved(studio.bands, null)
    private val library = listOf(LibraryEntry(nightfall, nightfallSaved), LibraryEntry(studio, studioSaved))

    private fun encoded(entries: List<LibraryEntry> = library) = LibraryFile.encode(entries, "1.8.1-test", 1_732_700_000_000)
    private fun asObject(text: String) = json.parseToJsonElement(text).jsonObject
    private fun edit(text: String, change: (JsonObject) -> JsonObject): String = change(asObject(text)).toString()
    private fun withProfiles(text: String, change: (List<JsonElement>) -> List<JsonElement>): String =
        edit(text) { root -> JsonObject(root + ("profiles" to JsonArray(change(root.getValue("profiles").jsonArray)))) }
    private fun profileWith(text: String, index: Int, change: (JsonObject) -> JsonObject): String =
        withProfiles(text) { list -> list.mapIndexed { i, e -> if (i == index) change(e.jsonObject) else e } }
    private fun library(read: LibraryRead) = assertIs<LibraryRead.Library>(read)

    @Test
    fun roundTripKeepsEveryProfileInOrderWithItsSavedVersion() {
        val read = library(LibraryFile.decode(encoded()))
        assertEquals(0, read.skipped)
        assertEquals(library, read.entries)
        // order, archived, saved, subtitle, icon one by one, so a failure names what was lost
        assertEquals(listOf("NIGHTFALL", "STUDIO"), read.entries.map { it.profile.name })
        assertEquals(listOf(false, true), read.entries.map { it.profile.archived })
        assertEquals(listOf("HEDD D1", ""), read.entries.map { it.profile.subtitle })
        assertEquals(listOf("moon", "speaker"), read.entries.map { it.profile.icon })
        assertEquals(nightfallSaved, read.entries[0].saved)
        assertTrue(read.entries[0].saved != LibrarySaved(read.entries[0].profile.bands, read.entries[0].profile.preampDb), "the unsaved change survives")
    }

    @Test
    fun headerAndArchivedAreAlwaysWritten() {
        val root = asObject(encoded())
        assertEquals(LibraryFile.FORMAT, root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("1.8.1-test", root.getValue("app").jsonPrimitive.content)
        assertEquals(1_732_700_000_000, root.getValue("exportedAt").jsonPrimitive.content.toLong())
        val profiles = root.getValue("profiles").jsonArray.map { it.jsonObject }
        assertEquals(listOf(false, true), profiles.map { it.getValue("archived").jsonPrimitive.boolean })
        assertTrue(profiles.all { "saved" in it })
        assertTrue(encoded().contains("\n    \"profiles\""), "pretty printed")
    }

    // criterion 5 of the task: the JSON a two-profile library gives (one archived, one with an unsaved change), printed for the report
    @Test
    fun twoProfileLibraryFile() {
        val text = encoded()
        println("LIBRARY-FILE-BEGIN\n$text\nLIBRARY-FILE-END")
        assertEquals(2, library(LibraryFile.decode(text)).entries.size)
    }

    @Test
    fun anEntryWithoutSavedReadsWithNullSaved() {
        val text = withProfiles(encoded()) { list -> list.map { JsonObject(it.jsonObject - "saved") } }
        val read = library(LibraryFile.decode(text))
        assertEquals(listOf(null, null), read.entries.map { it.saved })
        assertEquals(library.map { it.profile }, read.entries.map { it.profile })
    }

    @Test
    fun brokenEntryIsSkippedAndCountedTheRestComeIn() {
        val text = withProfiles(encoded()) { list ->
            val broken = JsonObject(list[0].jsonObject + ("bands" to JsonPrimitive("not a list")))
            listOf(broken, list[1], list[0])
        }
        val read = library(LibraryFile.decode(text))
        assertEquals(1, read.skipped)
        assertEquals(listOf("STUDIO", "NIGHTFALL"), read.entries.map { it.profile.name })
    }

    @Test
    fun everyKindOfBrokenEntryIsSkipped() {
        fun bandsOf(p: JsonObject) = p.getValue("bands").jsonArray.map { it.jsonObject }
        fun withBand(change: (JsonObject) -> JsonObject): String = profileWith(encoded(), 0) { p ->
            JsonObject(p + ("bands" to JsonArray(bandsOf(p).mapIndexed { i, b -> if (i == 0) change(b) else b })))
        }
        val cases = mapOf(
            "filter type of a newer build" to withBand { JsonObject(it + ("type" to JsonPrimitive("NOTCH_9000"))) },
            "missing name" to profileWith(encoded(), 0) { JsonObject(it - "name") },
            "missing bands" to profileWith(encoded(), 0) { JsonObject(it - "bands") },
            "missing band frequency" to withBand { JsonObject(it - "freqHz") },
            "frequency not finite" to profileWith(encoded(), 0) { it }.replace("105.0", "1e999"),
            "frequency zero" to withBand { JsonObject(it + ("freqHz" to JsonPrimitive(0.0))) },
            "frequency negative" to withBand { JsonObject(it + ("freqHz" to JsonPrimitive(-20.0))) },
            "q zero" to withBand { JsonObject(it + ("q" to JsonPrimitive(0.0))) },
            "q negative" to withBand { JsonObject(it + ("q" to JsonPrimitive(-1.0))) },
            "gain not finite" to profileWith(encoded(), 0) { it }.replace("\"gainDb\":6.5", "\"gainDb\":-1e999"),
            "preamp not finite" to profileWith(encoded(), 0) { JsonObject(it + ("preampDb" to JsonPrimitive("x"))) },
            "saved band broken" to profileWith(encoded(), 0) { p ->
                val saved = p.getValue("saved").jsonObject
                val bands = saved.getValue("bands").jsonArray.map { it.jsonObject }
                JsonObject(p + ("saved" to JsonObject(saved + ("bands" to JsonArray(bands.map { JsonObject(it + ("q" to JsonPrimitive(0.0))) })))))
            },
            "65 bands" to profileWith(encoded(), 0) { p ->
                val one = bandsOf(p)[0]
                JsonObject(p + ("bands" to JsonArray((0 until 65).map { JsonObject(one + ("id" to JsonPrimitive("x$it"))) })))
            },
            "entry is not an object" to withProfiles(encoded()) { listOf<JsonElement>(JsonPrimitive(7)) + it.drop(1) },
        )
        for ((what, text) in cases) {
            val read = library(LibraryFile.decode(text))
            assertEquals(1, read.skipped, what)
            assertEquals(1, read.entries.size, what)
            assertEquals("STUDIO", read.entries[0].profile.name, what)
        }
        // 64 bands is still fine
        val sixtyFour = profileWith(encoded(), 0) { p ->
            val one = bandsOf(p)[0]
            JsonObject(p + ("bands" to JsonArray((0 until 64).map { JsonObject(one + ("id" to JsonPrimitive("x$it"))) })))
        }
        assertEquals(0, library(LibraryFile.decode(sixtyFour)).skipped)
    }

    @Test
    fun wrongFormatIsRefused() {
        val refused = listOf(
            "",
            "not json at all",
            "[]",
            "{}",
            "{\"format\": \"something-else\", \"version\": 1, \"profiles\": []}",
            "{\"format\": 1, \"profiles\": []}",
            "{\"version\": 1, \"profiles\": []}",
            // an EQ by Ear session and an APO text are not libraries
            "{\"bands\": [{\"type\": \"PEAK\", \"fc\": 1000, \"gain\": 3, \"q\": 1}], \"preampDb\": -3}",
            "Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 3 dB Q 1\n",
            encoded().take(encoded().length / 2),
        )
        for (text in refused) assertEquals(LibraryRead.NotLibrary, LibraryFile.decode(text), text)
    }

    @Test
    fun unknownKeysAndAHigherVersionStillRead() {
        val text = edit(encoded()) { root -> JsonObject(root + mapOf("version" to JsonPrimitive(2), "futureThing" to JsonPrimitive("x"), "settings" to JsonObject(mapOf("a" to JsonPrimitive(1))))) }
        val extra = profileWith(text, 0) { p ->
            val bands = p.getValue("bands").jsonArray.map { JsonObject(it.jsonObject + ("slope" to JsonPrimitive(24))) }
            JsonObject(p + mapOf("tags" to JsonArray(listOf(JsonPrimitive("a"))), "bands" to JsonArray(bands)))
        }
        val read = library(LibraryFile.decode(extra))
        assertEquals(0, read.skipped)
        assertEquals(library, read.entries)
    }

    @Test
    fun emptyLibraryRoundTrips() {
        val text = encoded(emptyList())
        val read = library(LibraryFile.decode(text))
        assertEquals(emptyList(), read.entries)
        assertEquals(0, read.skipped)
        assertEquals(0, asObject(text).getValue("profiles").jsonArray.size)
    }

    @Test
    fun aMissingProfilesListIsAnEmptyLibrary() {
        assertEquals(LibraryRead.Library(emptyList(), 0), LibraryFile.decode("{\"format\": \"contour-library\", \"version\": 1}"))
    }

    // A4: a backup handed to the single-profile paths (OPEN FILE, open with Contour, a share) finds no filters there
    @Test
    fun aLibraryFileIsNoEqForTheSingleProfileReaders() {
        val text = encoded()
        val apo = ApoText.parseImport(text)
        assertTrue(apo.bands.isEmpty(), "APO reader finds no filters")
        assertTrue(apo.notes.isEmpty(), "APO reader names nothing unreadable")
        assertTrue(runCatching { EqByEarJson.parse(text) }.getOrNull()?.bands.orEmpty().isEmpty(), "EQ by Ear reader finds no bands")
        assertFalse(text.isBlank())
    }
}
