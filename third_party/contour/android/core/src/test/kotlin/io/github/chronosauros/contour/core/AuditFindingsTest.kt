package io.github.chronosauros.contour.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cases from the code audit of 10.11.2026 (SYNTHETIC: values chosen to reach the code path, no hardware log behind them).
 * Each one failed on 1.6.5 in the way its name says.
 */
class AuditFindingsTest {
    private fun profile(vararg b: Band, preamp: Double? = null) = Profile("id", "t", bands = b.toList(), preampDb = preamp, createdAt = 0, updatedAt = 0)

    /** LOW SHELF +12 dB at Q 6 is inside the Dawn Pro 2's ranges (shelf Q up to 7.07) and has no real response: (A + 1/A)(1/Q - 1) + 2 < 0. */
    private val steepShelf = Band("a", FilterType.LOW_SHELF, 100.0, 12.0, 6.0)

    @Test
    fun `a family DAC names a shelf with no real response on AUTO preamp instead of throwing`() {
        // 1.6.5: IllegalArgumentException("Shelf gain/Q has no real response") out of issues(), which the EQ page calls while it draws.
        val auto = DeviceTarget.DAWN_PRO_2.issues(profile(steepShelf))
        assertTrue(auto.isNotEmpty(), "AUTO: $auto")
        // the same band with a manual preamp was always an issue; AUTO now says the same thing
        val manual = DeviceTarget.DAWN_PRO_2.issues(profile(steepShelf, preamp = 0.0))
        assertTrue(manual.isNotEmpty(), "manual: $manual")
        assertEquals(manual, auto)
        // an issue found before the preamp is still the one reported
        val alsoOutOfRange = DeviceTarget.DAWN_PRO_2.issues(profile(steepShelf, Band("b", FilterType.PEAK, 1000.0, 12.5, 1.0)))
        assertTrue(alsoOutOfRange.any { it.startsWith("Band 2: gain") }, "$alsoOutOfRange")
    }

    @Test
    fun `a profile that plans cleanly keeps its issues exactly`() {
        val pk = Band("a", FilterType.PEAK, 1000.0, 3.0, 1.0)
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(profile(pk)))
        assertEquals(emptyList(), DeviceTarget.DAWN_PRO_2.issues(profile(pk, preamp = -3.0)))
        assertTrue(DeviceTarget.DAWN_PRO_2.issues(profile(pk, preamp = 1.0)).single().startsWith("Pregain"))
        assertEquals(emptyList(), DeviceTarget.ALLEGRO_MINI.issues(profile(pk)))
    }

    @Test
    fun `a short reply from the Protocol Micro is refused by length, not by an index error`() {
        // 1.6.5: ArrayIndexOutOfBoundsException, shown to the user as "Index 5 out of bounds for length 4".
        val short = byteArrayOf(0x4B, 0x80.toByte(), 0x03, 0x02)
        assertFailsWith<IllegalArgumentException> { WalkPlay.parsePreamp(short) }
        assertFailsWith<IllegalArgumentException> { WalkPlay.parseVersion(short) }
        assertFailsWith<IllegalArgumentException> { WalkPlay.parseSlot(short) }
        // a full report reads as before
        val full = ByteArray(64).also { it[0] = 0x4B; it[4] = '0'.code.toByte(); it[5] = '.'.code.toByte(); it[6] = '2'.code.toByte(); it[36] = 3 }
        assertEquals("0.2", WalkPlay.parseVersion(full))
        assertEquals(3, WalkPlay.parseSlot(full))
        assertEquals(-7, WalkPlay.parsePreamp(ByteArray(64).also { it[5] = (-7).toByte() }))
    }

    @Test
    fun `AUTO preamp gives the same answer when it is asked again, in any order`() {
        // one PEAK band: the maximum is its gain, at its own centre frequency (which AUTO always looks at)
        val a = listOf(Band("a", FilterType.PEAK, 1000.0, 4.2, 1.0))
        val b = listOf(Band("a", FilterType.PEAK, 60.0, 9.6, 2.0))
        val firstA = Preamp.auto(a, ShelfForm.SLOPE)
        val firstB = Preamp.auto(b, ShelfForm.SLOPE)
        assertEquals(-5, firstA)
        assertEquals(-10, firstB)
        repeat(3) {
            assertEquals(firstA, Preamp.auto(a.map { it.copy() }, ShelfForm.SLOPE))
            assertEquals(firstB, Preamp.auto(b, ShelfForm.SLOPE))
        }
        // a disabled band is not part of the answer, and switching it on is a new question
        assertEquals(firstA, Preamp.auto(a + Band("c", FilterType.PEAK, 60.0, 12.0, 1.0, enabled = false), ShelfForm.SLOPE))
        assertTrue(Preamp.auto(a + Band("c", FilterType.PEAK, 60.0, 12.0, 1.0), ShelfForm.SLOPE) <= -12)
        // a value changed by the smallest step is a new question too
        assertEquals(-6, Preamp.auto(listOf(a[0].copy(gainDb = 5.1)), ShelfForm.SLOPE))
        assertEquals(0, Preamp.auto(emptyList(), ShelfForm.SLOPE))
        // an EQ with no real response fails every time it is asked (never a remembered number)
        repeat(2) { assertFailsWith<IllegalArgumentException> { Preamp.auto(listOf(steepShelf), ShelfForm.SLOPE) } }
        assertEquals(firstA, Preamp.auto(a, ShelfForm.SLOPE))
    }
}
