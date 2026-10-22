// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The servers a build ships with: always in the list, never removable, their
 * secret never shown. Only the rooms server is built in now; the chart server
 * never is, and the one older builds put in the list is taken out by its host.
 */
class BuiltInServerTest {

    private val built = "https://parser.example.app/abc123"
    private val tidal = ApiServer(url = "https://tidal.example.org", services = setOf(ApiService.TIDAL))

    @Test
    fun itIsAlwaysThereLastAsACpuStemServer() {
        val list = BuiltInServer.ensure(listOf(tidal), built)!!
        assertEquals(listOf(tidal, BuiltInServer.entry(built)), list)
        assertEquals(false, list.last().separatesOnGpu)
        // It separates for charts and times lyrics, so both find it before it is probed.
        assertEquals(setOf(ApiService.STEMS, ApiService.LYRICS), list.last().services)
        // Slow finds it before it has been asked anything.
        assertEquals(
            listOf(list.last()),
            tf.monochrome.android.audio.stepmania.RemoteStemAnalysis.planFor(list, tf.monochrome.android.audio.stepmania.ChartMode.SLOW),
        )
        // There already: nothing to change.
        assertNull(BuiltInServer.ensure(list, built))
    }

    @Test
    fun removedItComesBack() {
        val list = BuiltInServer.ensure(emptyList(), built)!!
        val removed = list.filterNot { it.url == built }
        assertEquals(list, BuiltInServer.ensure(removed, built))
    }

    @Test
    fun anEntryForItsHostBecomesItInPlace() {
        val own = ApiServer(url = "https://PARSER.example.app/old", services = setOf(ApiService.STEMS), checkedAt = 5L, stemDevice = "cpu")
        val other = ApiServer(url = "https://parser.example.app", services = emptySet())
        val list = BuiltInServer.ensure(listOf(own, tidal, other), built)!!
        assertEquals(listOf(own.copy(url = built, services = setOf(ApiService.STEMS, ApiService.LYRICS)), tidal), list)
    }

    @Test
    fun itIsKnownAndShownWithoutItsSecret() {
        assertTrue(BuiltInServer.isBuiltIn(built, built))
        assertFalse(BuiltInServer.isBuiltIn(tidal.url, built))
        assertEquals("parser.example.app", BuiltInServer.shownAs(built))
    }

    private val rooms = "https://rooms.example.app/def456"

    @Test
    fun onlyTheRoomsServerShips() {
        val list = BuiltInServer.ensureAll(listOf(tidal), rooms)!!
        assertEquals(listOf(tidal, BuiltInServer.entry(rooms, setOf(ApiService.MULTIPLAYER))), list)
        // A rooms server separates nothing, so it says nothing of a device, and charts nothing.
        assertNull(list.last().stemDevice)
        assertEquals(setOf(ApiService.MULTIPLAYER), list.last().services)
        assertTrue(
            tf.monochrome.android.audio.stepmania.RemoteStemAnalysis.planFor(list, tf.monochrome.android.audio.stepmania.ChartMode.SLOW).isEmpty(),
        )
        // There already: nothing to change.
        assertNull(BuiltInServer.ensureAll(list, rooms))
        assertTrue(BuiltInServer.isBuiltIn(rooms, rooms))
        assertNull(BuiltInServer.ensureAll(listOf(tidal), ""))
    }

    private fun sha256(s: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun theRetiredChartServerIsTakenOutByItsHost() {
        val retired = BuiltInServer.entry(built)
        val rooms = BuiltInServer.entry(rooms, setOf(ApiService.MULTIPLAYER))
        val list = listOf(tidal, retired, rooms)
        assertEquals(listOf(tidal, rooms), BuiltInServer.withoutRetired(list, sha256("parser.example.app")))
        // The hash is of the host alone, any case, whatever the path.
        assertEquals(listOf(tidal), BuiltInServer.withoutRetired(listOf(tidal, retired.copy(url = "https://Parser.Example.App/other")), sha256("parser.example.app")))
        // Nothing of the kind, or no hash in the build: nothing changes.
        assertNull(BuiltInServer.withoutRetired(listOf(tidal, rooms), sha256("parser.example.app")))
        assertNull(BuiltInServer.withoutRetired(list, ""))
    }

    @Test
    fun aBuildWithoutOneAddsNothing() {
        assertNull(BuiltInServer.ensure(listOf(tidal), ""))
        assertFalse(BuiltInServer.isBuiltIn(tidal.url, ""))
    }
}
