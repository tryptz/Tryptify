package tf.monochrome.android.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tf.monochrome.android.data.api.ApiService

/**
 * Discover's service switch: what a stored choice reads back as, and how the
 * catalogue memos keep each service's answers apart.
 */
class DiscoveryCatalogTest {

    @Test
    fun `the switch offers TIDAL, Qobuz and Deezer, in that order`() {
        assertEquals(listOf(ApiService.TIDAL, ApiService.QOBUZ, ApiService.DEEZER), DISCOVERY_SERVICES)
    }

    @Test
    fun `a stored name reads back as its service`() {
        DISCOVERY_SERVICES.forEach { assertEquals(it, discoveryServiceFromName(it.name)) }
    }

    @Test
    fun `nothing stored, junk, or a service Discover can't use falls back to Qobuz`() {
        assertEquals(ApiService.QOBUZ, discoveryServiceFromName(null))
        assertEquals(ApiService.QOBUZ, discoveryServiceFromName(""))
        assertEquals(ApiService.QOBUZ, discoveryServiceFromName("SPOTIFY"))
        assertEquals(ApiService.QOBUZ, discoveryServiceFromName(ApiService.APPLE.name))
    }

    @Test
    fun `Qobuz keeps the keys answers were saved under before the switch existed`() {
        assertEquals("airod|acid storm", discoveryMemoKey(ApiService.QOBUZ, "airod|acid storm"))
    }

    @Test
    fun `no two services share a memo key`() {
        val key = "airod|acid storm"
        val keys = DISCOVERY_SERVICES.map { discoveryMemoKey(it, key) }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(discoveryMemoKey(ApiService.TIDAL, key), discoveryMemoKey(ApiService.DEEZER, key))
    }
}
