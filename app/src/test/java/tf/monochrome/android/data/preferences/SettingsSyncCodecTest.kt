package tf.monochrome.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncCodecTest {

    private fun roundTrip(values: Map<String, Any>): Map<String, Any> =
        SettingsSyncCodec.decode(SettingsSyncCodec.encode(values))

    @Test
    fun `preserves a Boolean`() {
        val out = roundTrip(mapOf("k" to true))
        assertEquals(true, out["k"])
        assertTrue(out["k"] is Boolean)
    }

    @Test
    fun `preserves an Int as Int`() {
        val out = roundTrip(mapOf("k" to 42))
        assertEquals(42, out["k"])
        assertTrue("Int must not decode as Long", out["k"] is Int)
    }

    @Test
    fun `preserves a Long as Long`() {
        // 5_000_000_000 doesn't fit in Int — the type tag is what keeps it a Long.
        val out = roundTrip(mapOf("k" to 5_000_000_000L))
        assertEquals(5_000_000_000L, out["k"])
        assertTrue("Long must not decode as Int", out["k"] is Long)
    }

    @Test
    fun `preserves a Float as Float`() {
        val out = roundTrip(mapOf("k" to 1.25f))
        assertEquals(1.25f, out["k"] as Float, 0f)
        assertTrue("Float must not decode as Double", out["k"] is Float)
    }

    @Test
    fun `preserves a Double as Double`() {
        val out = roundTrip(mapOf("k" to 3.14159))
        assertEquals(3.14159, out["k"] as Double, 0.0)
        assertTrue(out["k"] is Double)
    }

    @Test
    fun `preserves a String including empty`() {
        val out = roundTrip(mapOf("a" to "hello", "b" to ""))
        assertEquals("hello", out["a"])
        assertEquals("", out["b"])
    }

    @Test
    fun `preserves a StringSet order-independently`() {
        val out = roundTrip(mapOf("k" to setOf("x", "y", "z")))
        assertEquals(setOf("x", "y", "z"), out["k"])
    }

    @Test
    fun `round-trips a representative multi-type snapshot unchanged`() {
        val original = mapOf(
            "theme" to "dark",
            "font_scale" to 1.15f,
            "spectrum_fft_size" to 8192,
            "some_long" to 9_000_000_000L,
            "dynamic_colors" to false,
            "favs" to setOf("1", "2"),
            "preamp" to -6.0,
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `skips values of an unsupported type on encode`() {
        // A nested list isn't a supported type — it's dropped, not thrown.
        val json = SettingsSyncCodec.encode(mapOf("ok" to 1, "bad" to listOf(1, 2, 3)))
        val out = SettingsSyncCodec.decode(json)
        assertEquals(1, out["ok"])
        assertNull(out["bad"])
    }

    @Test
    fun `decode of garbage yields an empty map, never throws`() {
        assertTrue(SettingsSyncCodec.decode("not json at all").isEmpty())
        assertTrue(SettingsSyncCodec.decode("").isEmpty())
        assertTrue(SettingsSyncCodec.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `decode skips entries with a missing or unknown type tag`() {
        val json = """{"a":{"t":"i","v":5},"b":{"v":7},"c":{"t":"zz","v":1}}"""
        val out = SettingsSyncCodec.decode(json)
        assertEquals(5, out["a"])
        assertNull(out["b"])
        assertNull(out["c"])
    }

    // ─── Merging with the cloud copy ─────────────────────────────────────────
    // These are what keep a push from deleting cloud settings and a pull from
    // reverting local ones.

    private fun snap(vararg pairs: Pair<String, Any>) = SettingsSyncCodec.encode(mapOf(*pairs))

    @Test
    fun `a push never removes a key the cloud has`() {
        val cloud = snap("a" to 1, "b" to true)
        val merged = SettingsSyncCodec.decode(SettingsSyncCodec.merge(cloud, snap("a" to 2))!!)
        assertEquals(2, merged["a"])
        assertEquals(true, merged["b"])
    }

    @Test
    fun `a push keeps cloud entries this build cannot decode`() {
        val cloud = """{"future":{"t":"zz","v":[1,2]},"a":{"t":"i","v":1}}"""
        val merged = SettingsSyncCodec.merge(cloud, snap("a" to 2))!!
        assertTrue(merged.contains("\"future\""))
        assertTrue(merged.contains("\"zz\""))
    }

    @Test
    fun `a push refuses to overwrite a cloud copy it cannot read`() {
        assertNull(SettingsSyncCodec.merge("[\"not an object\"]", snap("a" to 1)))
    }

    @Test
    fun `seeding an empty account uploads the local snapshot`() {
        val merged = SettingsSyncCodec.decode(SettingsSyncCodec.merge(null, snap("a" to 1))!!)
        assertEquals(mapOf<String, Any>("a" to 1), merged)
    }

    @Test
    fun `only keys changed since the agreed snapshot count as local edits`() {
        val base = snap("a" to 1, "b" to 1)
        val local = snap("a" to 1, "b" to 2, "c" to 3)
        assertEquals(setOf("b", "c"), SettingsSyncCodec.changedSince(base, local))
    }

    @Test
    fun `without an agreed snapshot the cloud copy wins`() {
        assertTrue(SettingsSyncCodec.changedSince(null, snap("a" to 1)).isEmpty())
    }

    @Test
    fun `a pull applies the cloud copy except for local edits`() {
        val cloud = snap("a" to 10, "b" to 10)
        val applied = SettingsSyncCodec.decode(SettingsSyncCodec.without(cloud, setOf("b")))
        assertEquals(mapOf<String, Any>("a" to 10), applied)
    }

    @Test
    fun `a push sends only the local edits`() {
        val local = snap("a" to 1, "b" to 2)
        assertEquals(mapOf<String, Any>("b" to 2), SettingsSyncCodec.decode(SettingsSyncCodec.only(local, setOf("b"))))
    }
}
