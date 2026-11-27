package io.github.chronosauros.contour.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden check of the device catalog: [CatalogDump] recomputes what today's hand-written catalog does and the result must
 * equal `android/core/src/test/resources/catalog/golden.json` byte for byte.
 *
 * The golden file is NEVER written by a normal run. It changes only through a deliberate act, together with the catalog
 * change that caused it, and the diff of golden.json is then part of the review:
 *
 *   CONTOUR_REGENERATE_GOLDEN=1 android/wsl-build.sh :core:cleanTest :core:test --tests '*CatalogGoldenTest*'
 *
 * (`cleanTest` because Gradle would otherwise skip an up-to-date test task.) See devices/README.md.
 */
class CatalogGoldenTest {
    private val golden = File(
        System.getProperty("contour.repoRoot") ?: error("contour.repoRoot is not set (run through :core:test)"),
        "android/core/src/test/resources/catalog/golden.json",
    )

    @Test
    fun dumpIsDeterministic() {
        assertEquals(CatalogDump.dump(), CatalogDump.dump(), "two dumps of the same catalog differ: the dumper is not deterministic")
    }

    @Test
    fun dumpIsLfOnly() {
        assertTrue(!CatalogDump.dump().contains('\r'), "the dump must use LF line endings")
    }

    @Test
    fun catalogEqualsGolden() {
        val actual = CatalogDump.dump()
        if (System.getenv("CONTOUR_REGENERATE_GOLDEN") == "1") {
            golden.parentFile.mkdirs()
            golden.writeBytes(actual.toByteArray(Charsets.UTF_8))
            println("CatalogGoldenTest: golden regenerated (${actual.length} chars) -> review the diff of ${golden.name}")
            return
        }
        assertTrue(golden.isFile, "golden.json missing: ${golden.path}; create it deliberately (see CatalogGoldenTest KDoc)")
        val expected = String(golden.readBytes(), Charsets.UTF_8)
        if (expected != actual) {
            val e = expected.lines()
            val a = actual.lines()
            val first = (0 until maxOf(e.size, a.size)).firstOrNull { e.getOrNull(it) != a.getOrNull(it) } ?: 0
            error(
                "the device catalog no longer equals golden.json (first difference at line ${first + 1}):\n" +
                    "  golden : ${e.getOrNull(first)}\n  actual : ${a.getOrNull(first)}\n" +
                    "If the catalog change is deliberate, regenerate golden.json as described in CatalogGoldenTest and review its diff.",
            )
        }
    }
}
