package tf.monochrome.android.audio.sampler.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The licence gate.
 *
 * MODEL_CARD.md says Tryptify will not ship weights it has no right to
 * redistribute, and that claim is only worth something if something enforces
 * it. This is that something.
 *
 * The research behind that decision found that essentially every high-quality
 * open 4-stem checkpoint is encumbered: Meta's Demucs weights are research-only
 * despite MIT code, and almost everything else was trained on MUSDB18-HQ, whose
 * terms are educational-use-only. So the failure these tests guard against is
 * not hypothetical — it is what happens if someone pastes the obvious model
 * into the manifest.
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

    // ── the gate ────────────────────────────────────────────────────────

    @Test
    fun `a permissively licensed model is installable`() {
        val catalog = StemModelCatalog.parse(manifest(model(license = "MIT")))!!
        assertEquals(1, catalog.installable(34, abis).size)
    }

    /**
     * The exact case this gate exists for. Demucs' code is MIT and its weights
     * are not, and the maintainer is explicit that they are "provided only for
     * scientific purposes".
     */
    @Test
    fun `research-only weights are refused`() {
        val catalog = StemModelCatalog.parse(manifest(model(license = "RESEARCH-ONLY")))!!
        assertTrue(catalog.installable(34, abis).isEmpty())
        assertEquals(
            "Research only — cannot be distributed with Tryptify",
            catalog.models.first().blockedReason(34, abis),
        )
    }

    /** Anything trained on MUSDB18-HQ inherits its non-commercial terms. */
    @Test
    fun `MUSDB-trained weights are refused`() {
        val catalog = StemModelCatalog.parse(manifest(model(license = "MUSDB-ENCUMBERED")))!!
        assertTrue(catalog.installable(34, abis).isEmpty())
    }

    @Test
    fun `non-commercial licences are refused`() {
        for (id in listOf("CC-BY-NC-4.0", "CC-BY-NC-SA-4.0")) {
            val catalog = StemModelCatalog.parse(manifest(model(license = id)))!!
            assertTrue("$id was allowed through", catalog.installable(34, abis).isEmpty())
        }
    }

    /**
     * The important default. An unrecognised licence is not a warning, it is a
     * refusal — otherwise a typo in the manifest would open the gate.
     */
    @Test
    fun `an unrecognised licence is treated as unshippable`() {
        val catalog = StemModelCatalog.parse(manifest(model(license = "totally-fine-honest")))!!
        assertEquals(ModelLicense.UNKNOWN, catalog.models.first().license)
        assertTrue(catalog.installable(34, abis).isEmpty())
    }

    /** And so is omitting the field entirely. */
    @Test
    fun `a missing licence field is treated as unshippable`() {
        val json = """
            { "schemaVersion": 1, "models": [ {
              "id": "x", "version": "1.0.0", "url": "https://example.com/m.zip",
              "sha256": "$sha", "sizeBytes": 10
            } ] }
        """.trimIndent()
        val catalog = StemModelCatalog.parse(json)!!
        assertEquals(ModelLicense.UNKNOWN, catalog.models.first().license)
        assertTrue(catalog.installable(34, abis).isEmpty())
    }

    /**
     * The gate outranks capability. A better model that cannot be shipped is
     * still not shipped.
     */
    @Test
    fun `the best model is never an unshippable one`() {
        val catalog = StemModelCatalog.parse(
            manifest(
                model(id = "great", license = "RESEARCH-ONLY", backend = "QNN", version = "9.0.0"),
                model(id = "legal", license = "MIT", version = "1.0.0"),
            ),
        )!!
        assertEquals("legal", catalog.best(34, abis, allowNpu = true)?.id)
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
    fun `a shippable model reports no blocking reason`() {
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
    fun `no installable model returns null rather than a bad one`() {
        val catalog = StemModelCatalog.parse(manifest(model(license = "RESEARCH-ONLY")))!!
        assertNull(catalog.best(34, abis, allowNpu = true))
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
