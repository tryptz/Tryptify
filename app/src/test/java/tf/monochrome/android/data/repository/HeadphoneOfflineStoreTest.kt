package tf.monochrome.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tf.monochrome.android.data.api.MeasurementFetch
import tf.monochrome.android.data.api.MeasurementHttp
import tf.monochrome.android.data.repository.HeadphoneOfflineStore.StoredChannel
import tf.monochrome.android.data.repository.HeadphoneOfflineStore.StoredMeasurement
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import java.util.Locale

/**
 * The measurements kept for offline use, and the download's own rules:
 * which files it fetches, in what order, what a server's answer means.
 */
class HeadphoneOfflineStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = HeadphoneOfflineStore(tmp.root)

    private val curve = listOf(FrequencyPoint(20f, -7.88f), FrequencyPoint(1000f, 0f))
    private val curveR = listOf(FrequencyPoint(20f, -6f), FrequencyPoint(1000f, 1f))

    private val hd600 = AutoEqMeasurement(
        source = "oratory1990", target = "AutoEq",
        path = "results/oratory1990/over-ear/Sennheiser HD 600", fileName = "Sennheiser HD 600",
        rig = MeasurementRig.GRAS_43AC_10,
    )
    private fun squig(host: String, file: String) = AutoEqMeasurement(
        source = host, target = "squiglink", path = host, fileName = file, host = host,
        rig = MeasurementRig.IEC_711_CLONE,
    )

    @Test
    fun `a measurement is saved and read back`() {
        val store = store()
        assertFalse(store.has(hd600))
        store.write(hd600, StoredMeasurement(curve = curve))
        assertTrue(store.has(hd600))
        assertEquals(curve, store.read(hd600)?.single)
    }

    @Test
    fun `squig link's single file is the left ear, else the right`() {
        val both = StoredMeasurement(left = StoredChannel("L1", curve), right = StoredChannel("R1", curveR))
        assertEquals(curve, both.single)
        assertEquals(curveR, StoredMeasurement(right = StoredChannel("R", curveR)).single)
        assertEquals("L1", both.channel("L")?.sample)
        assertEquals(curveR, both.channel("R")?.curve)
        assertNull(both.channel("X"))
    }

    @Test
    fun `a measurement with nothing published is remembered but reads as missing`() {
        val store = store()
        store.write(hd600, StoredMeasurement())
        assertTrue(store.has(hd600)) // the next run skips it
        assertNull(store.read(hd600)?.single) // the screen goes to the network
        assertNull(store.read(hd600)?.channel("L"))
    }

    @Test
    fun `the key is the same in any locale and differs per measurement`() {
        val store = store()
        val key = store.keyOf(hd600)
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(key, store.keyOf(hd600))
        } finally {
            Locale.setDefault(before)
        }
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
        assertNotEquals(key, store.keyOf(squig("https://a.squig.link", "Sennheiser HD 600")))
    }

    @Test
    fun `the index keeps the list and splits by source`() {
        val store = store()
        val list = listOf(
            Headphone(id = "hd600", name = "HD 600", measurements = listOf(hd600, squig("https://a", "HD 600"))),
            Headphone(id = "iem", name = "IEM", measurements = listOf(squig("https://b", "IEM"))),
        )
        assertNull(store.readIndex())
        store.writeIndex(list)
        assertEquals(list, store.readIndex())
        val autoEqOnly = store.indexPart { it.target != "squiglink" }
        assertEquals(listOf("HD 600"), autoEqOnly.map { it.name })
        assertEquals(listOf(hd600), autoEqOnly.single().measurements)
    }

    @Test
    fun `summary counts what is saved, and delete removes it all`() {
        val store = store()
        assertTrue(store.summary().isEmpty)
        store.write(hd600, StoredMeasurement(curve = curve))
        store.write(squig("https://a", "x"), StoredMeasurement(left = StoredChannel("L", curve)))
        store.writeIndex(emptyList())
        val summary = store.summary()
        assertEquals(2, summary.measurements)
        assertTrue(summary.bytes > 0)
        assertTrue(summary.savedAt != null)
        store.clear()
        assertTrue(store.summary().isEmpty)
        assertNull(store.read(hd600))
    }

    // ── the download's rules ──────────────────────────────────────────────

    @Test
    fun `the download skips uploads and counts a measurement once`() {
        val upload = AutoEqMeasurement("User upload", "uploaded", "", "Mine", MeasurementRig.UPLOADED)
        val list = listOf(
            Headphone(id = "a", name = "A", measurements = listOf(hd600, upload)),
            Headphone(id = "b", name = "B", measurements = listOf(hd600)),
        )
        assertEquals(listOf(hd600), HeadphoneMeasurementDownloader.measurementsOf(list))
    }

    @Test
    fun `sites take turns so no one site gets every request`() {
        val a = (1..3).map { squig("https://a", "a$it") }
        val b = (1..1).map { squig("https://b", "b$it") }
        val gh = (1..2).map { hd600.copy(fileName = "g$it") }
        val order = HeadphoneMeasurementDownloader.interleaveByHost(a + gh + b).map { it.fileName }
        assertEquals(listOf("a1", "g1", "b1", "a2", "g2", "a3"), order)
    }

    @Test
    fun `the estimate grows with each source's count`() {
        val list = listOf(
            Headphone(id = "a", name = "A", measurements = listOf(hd600, squig("https://a", "A"))),
        )
        val e = HeadphoneMeasurementDownloader.estimate(list)
        assertEquals(2, e.measurements)
        assertEquals(43_000L + 28_000L, e.downloadBytes)
        assertEquals(4_096L + 8_192L, e.storedBytes)
        assertEquals(0, HeadphoneMeasurementDownloader.estimate(emptyList()).measurements)
    }

    @Test
    fun `a server's answer is read for what it means`() {
        fun classify(code: Int, retryAfter: String? = null, remaining: String? = null) =
            MeasurementHttp.classify(code, retryAfter, remaining) { "body" }
        assertEquals(MeasurementFetch.Found("body"), classify(200))
        assertEquals(MeasurementFetch.Missing, classify(404))
        assertEquals(MeasurementFetch.Missing, classify(410))
        assertEquals(MeasurementFetch.Throttled(30_000L), classify(429, retryAfter = "30"))
        assertEquals(MeasurementFetch.Throttled(MeasurementHttp.DEFAULT_RETRY_AFTER_MS), classify(503))
        // GitHub's rate limit is a 403 that says so; any other 403 is a failure.
        assertEquals(MeasurementFetch.Throttled(MeasurementHttp.DEFAULT_RETRY_AFTER_MS), classify(403, remaining = "0"))
        assertEquals(MeasurementFetch.Failed, classify(403))
        assertEquals(MeasurementFetch.Failed, classify(500))
    }

    @Test
    fun `a Retry-After is kept within reason`() {
        assertEquals(5_000L, MeasurementHttp.retryAfterMs("0"))
        assertEquals(600_000L, MeasurementHttp.retryAfterMs("86400"))
        assertEquals(MeasurementHttp.DEFAULT_RETRY_AFTER_MS, MeasurementHttp.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(MeasurementHttp.DEFAULT_RETRY_AFTER_MS, MeasurementHttp.retryAfterMs(null))
    }
}
