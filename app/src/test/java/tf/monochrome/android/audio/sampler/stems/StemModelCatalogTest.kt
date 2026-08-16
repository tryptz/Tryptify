package tf.monochrome.android.audio.sampler.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest parsing and model selection.
 *
 * The checks that matter are mechanical: an archive has to be verifiable
 * against a full-length hash, reachable over HTTPS, and built for this device,
 * because those are the ways a download turns into a corrupt install or a
 * crash on first inference. Licence is parsed and carried for display, and
 * deliberately does not gate anything.
 */
class StemModelCatalogTest {

    private val sha = "a".repeat(64)
    private val abis = listOf("arm64-v8a")

    private fun manifest(vararg entries: String) = """
        { "schemaVersion": 1, "models": [ ${entries.joinToString(",")} ] }
    """.trimIndent()

    private fun model(
        id: String = "test",
        license: String = "MIT",
        url: String = "https://github.com/tryptz/Tryptify/releases/download/x/m.zip",
        sha256: String = sha,
        size: Long = 1000,
        minSdk: Int = 26,
        abi: String = "arm64-v8a",
        version: String = "1.0.0",
        backend: String = "CPU",
    ) = """
        {
          "id": "$id", "name": "Test", "version": "$version",
          "format": "ONNX", "backend": "$backend",
          "url": "$url", "sha256": "$sha256", "sizeBytes": $size,
          "license": "$license", "sourceModel": "test",
          "stems": ["VOCALS", "DRUMS", "BASS", "OTHER"],
          "modelSampleRate": 44100, "segmentFrames": 343980,
          "minimumAndroid": $minSdk, "abi": "$abi"
        }
    """.trimIndent()

    // ── licence is metadata ─────────────────────────────────────────────

    @Test
    fun `licences are parsed and kept`() {
        val cases = mapOf(
            "MIT" to ModelLicense.MIT,
            "CC-BY-NC-4.0" to ModelLicense.CC_BY_NC,
            "RESEARCH-ONLY" to ModelLicense.RESEARCH_ONLY,
            "MUSDB-ENCUMBERED" to ModelLicense.MUSDB_ENCUMBERED,
        )
        for ((id, expected) in cases) {
            val catalog = StemModelCatalog.parse(manifest(model(license = id)))!!
            assertEquals(expected, catalog.models.first().license)
        }
    }

    /** Licence is shown, not enforced — any of them installs. */
    @Test
    fun `licence does not block installation`() {
        for (id in listOf("MIT", "CC-BY-NC-4.0", "RESEARCH-ONLY", "MUSDB-ENCUMBERED", "nonsense")) {
            val catalog = StemModelCatalog.parse(manifest(model(license = id)))!!
            assertEquals("$id was blocked", 1, catalog.installable(34, abis).size)
            assertNull("$id reported a reason", catalog.models.first().blockedReason(34, abis))
        }
    }

    @Test
    fun `an unrecognised or missing licence reads as unspecified`() {
        val bogus = StemModelCatalog.parse(manifest(model(license = "totally-made-up")))!!
        assertEquals(ModelLicense.UNKNOWN, bogus.models.first().license)

        val json = """
            { "schemaVersion": 1, "models": [ {
              "id": "x", "version": "1.0.0", "url": "https://example.com/m.zip",
              "sha256": "$sha", "sizeBytes": 10
            } ] }
        """.trimIndent()
        val missing = StemModelCatalog.parse(json)!!
        assertEquals(ModelLicense.UNKNOWN, missing.models.first().license)
        assertTrue(missing.installable(34, abis).isNotEmpty())
    }

    /** The flag that drives an informational note in the model details. */
    @Test
    fun `restricted upstream terms are flagged for display`() {
        assertTrue(ModelLicense.RESEARCH_ONLY.restricted)
        assertTrue(ModelLicense.CC_BY_NC.restricted)
        assertTrue(ModelLicense.MUSDB_ENCUMBERED.restricted)
        assertFalse(ModelLicense.MIT.restricted)
        assertFalse(ModelLicense.PROPRIETARY.restricted)
    }

    // ── the mechanical checks ───────────────────────────────────────────

    @Test
    fun `a model without a full sha256 is refused`() {
        val catalog = StemModelCatalog.parse(manifest(model(sha256 = "abc123")))!!
        assertTrue(catalog.installable(34, abis).isEmpty())
        assertEquals("Manifest has no valid checksum", catalog.models.first().blockedReason(34, abis))
    }

    /** Weights arrive over the network; plain HTTP is not an option. */
    @Test
    fun `a non-HTTPS url is refused`() {
        val catalog = StemModelCatalog.parse(manifest(model(url = "http://example.com/m.zip")))!!
        assertTrue(catalog.installable(34, abis).isEmpty())
    }

    @Test
    fun `models for another abi or a newer android are refused`() {
        val wrongAbi = StemModelCatalog.parse(manifest(model(abi = "x86_64")))!!
        assertTrue(wrongAbi.installable(34, abis).isEmpty())

        val tooNew = StemModelCatalog.parse(manifest(model(minSdk = 99)))!!
        assertTrue(tooNew.installable(34, abis).isEmpty())
        assertEquals("Needs Android API 99", tooNew.models.first().blockedReason(34, abis))
    }

    @Test
    fun `a runnable model reports no blocking reason`() {
        val catalog = StemModelCatalog.parse(manifest(model()))!!
        assertNull(catalog.models.first().blockedReason(34, abis))
    }

    // ── selection ───────────────────────────────────────────────────────

    @Test
    fun `the npu model is preferred when allowed and skipped when not`() {
        val catalog = StemModelCatalog.parse(
            manifest(
                model(id = "cpu", backend = "CPU"),
                model(id = "npu", backend = "QNN"),
            ),
        )!!
        assertEquals("npu", catalog.best(34, abis, allowNpu = true)?.id)
        assertEquals("cpu", catalog.best(34, abis, allowNpu = false)?.id)
    }

    @Test
    fun `no runnable model returns null rather than a bad one`() {
        val catalog = StemModelCatalog.parse(manifest(model(abi = "x86_64")))!!
        assertNull(catalog.best(34, abis, allowNpu = true))
    }

    // ── stem sets ───────────────────────────────────────────────────────

    @Test
    fun `a six stem model is parsed with all six`() {
        val json = """
            { "schemaVersion": 1, "models": [ {
              "id": "htdemucs_6s", "version": "1.0.0", "format": "ONNX", "backend": "CPU",
              "url": "https://example.com/m.zip", "sha256": "$sha", "sizeBytes": 10,
              "license": "MIT",
              "stems": ["VOCALS","DRUMS","BASS","GUITAR","PIANO","OTHER"]
            } ] }
        """.trimIndent()
        val model = StemModelCatalog.parse(json)!!.models.first()
        assertEquals(Stem.SIX, model.stems)
        assertTrue(model.stems.contains(Stem.GUITAR))
        assertTrue(model.stems.contains(Stem.PIANO))
    }

    /**
     * A manifest that says nothing about stems means four, not six. Defaulting
     * the other way would have every legacy entry advertising a piano stem it
     * cannot produce.
     */
    @Test
    fun `a manifest without a stem list means four`() {
        val json = """
            { "schemaVersion": 1, "models": [ {
              "id": "x", "version": "1.0.0", "url": "https://example.com/m.zip",
              "sha256": "$sha", "sizeBytes": 10, "license": "MIT"
            } ] }
        """.trimIndent()
        assertEquals(Stem.FOUR, StemModelCatalog.parse(json)!!.models.first().stems)
    }

    /** An unrecognised stem name is dropped rather than failing the entry. */
    @Test
    fun `an unknown stem name is ignored`() {
        val json = """
            { "schemaVersion": 1, "models": [ {
              "id": "x", "version": "1.0.0", "url": "https://example.com/m.zip",
              "sha256": "$sha", "sizeBytes": 10, "license": "MIT",
              "stems": ["VOCALS","KAZOO","DRUMS"]
            } ] }
        """.trimIndent()
        val model = StemModelCatalog.parse(json)!!.models.first()
        assertEquals(setOf(Stem.VOCALS, Stem.DRUMS), model.stems)
    }

    /** More stems wins, all else equal — that is what makes 6s the default. */
    @Test
    fun `the six stem model is preferred over the four stem one`() {
        val four = """
            { "id": "four", "version": "1.0.0", "url": "https://e.com/a.zip",
              "sha256": "$sha", "sizeBytes": 10, "license": "MIT", "backend": "CPU",
              "stems": ["VOCALS","DRUMS","BASS","OTHER"] }
        """.trimIndent()
        val six = """
            { "id": "six", "version": "1.0.0", "url": "https://e.com/b.zip",
              "sha256": "$sha", "sizeBytes": 10, "license": "MIT", "backend": "CPU",
              "stems": ["VOCALS","DRUMS","BASS","GUITAR","PIANO","OTHER"] }
        """.trimIndent()
        val catalog = StemModelCatalog.parse(
            """{ "schemaVersion": 1, "models": [ $four, $six ] }""",
        )!!
        assertEquals("six", catalog.best(34, abis, allowNpu = true)?.id)
    }

    // ── versions ────────────────────────────────────────────────────────

    /**
     * The classic way an update is silently never offered: string comparison
     * says "1.10.0" < "1.9.0".
     */
    @Test
    fun `version ordering is numeric per component`() {
        fun key(v: String) = StemModelCatalog.versionKey(v)
        assertTrue(key("1.10.0") > key("1.9.0"))
        assertTrue(key("2.0.0") > key("1.99.99"))
        assertTrue(key("1.0.1") > key("1.0.0"))
        assertEquals(key("1.2.3"), key("1.2.3-beta"))
        assertEquals(key("1.0.0"), key("1.0"))
    }

    @Test
    fun `an update is offered only when the bytes actually differ`() {
        val catalog = StemModelCatalog(1, emptyList())
        val installed = StemModelCatalog.parse(manifest(model(version = "1.0.0")))!!.models.first()

        val sameBytes = installed.copy(version = "1.1.0")
        assertFalse("same hash is not an update", catalog.isUpdate(installed, sameBytes))

        val newer = installed.copy(version = "1.1.0", sha256 = "b".repeat(64))
        assertTrue(catalog.isUpdate(installed, newer))

        val older = installed.copy(version = "0.9.0", sha256 = "c".repeat(64))
        assertFalse(catalog.isUpdate(installed, older))

        assertTrue("nothing installed is always an update", catalog.isUpdate(null, newer))
    }

    @Test
    fun `an update for a different model id is not an update`() {
        val catalog = StemModelCatalog(1, emptyList())
        val installed = StemModelCatalog.parse(manifest(model(id = "a")))!!.models.first()
        val other = installed.copy(id = "b", version = "2.0.0", sha256 = "d".repeat(64))
        assertFalse(catalog.isUpdate(installed, other))
    }

    // ── parsing ─────────────────────────────────────────────────────────

    /** One malformed entry must not take the whole feature offline. */
    @Test
    fun `a broken entry is dropped and the rest survive`() {
        val json = """
            { "schemaVersion": 1, "models": [
              { "name": "no id here" },
              ${model(id = "good")}
            ] }
        """.trimIndent()
        val catalog = StemModelCatalog.parse(json)!!
        assertEquals(1, catalog.models.size)
        assertEquals("good", catalog.models.first().id)
    }

    @Test
    fun `garbage input parses to null rather than throwing`() {
        assertNull(StemModelCatalog.parse("not json at all"))
        assertNull(StemModelCatalog.parse(""))
        assertNull(StemModelCatalog.parse("{}"))
    }

    @Test
    fun `an empty catalog is valid and offers nothing`() {
        val catalog = StemModelCatalog.parse("""{"schemaVersion":1,"models":[]}""")
        assertNotNull(catalog)
        assertTrue(catalog!!.models.isEmpty())
        assertNull(catalog.best(34, abis, allowNpu = true))
    }

    @Test
    fun `checksums are compared case insensitively`() {
        val upper = StemModelCatalog.parse(manifest(model(sha256 = "A".repeat(64))))!!
        assertEquals(sha, upper.models.first().sha256)
        assertTrue(upper.installable(34, abis).isNotEmpty())
    }
}
