package tf.monochrome.android.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.model.BusInputSource

/**
 * Locks the wire format of the per-bus input routing.
 *
 * Two things have to hold or saved mixer state breaks: the ordinals are the
 * values written into `dsp_state_json` and passed to native (so they must match
 * `BusInputSource` in `cpp/dsp/dsp_engine.h`), and state written before line-in
 * existed has no `inputSource` field at all and must still load as playback.
 */
class BusInputSourceTest {

    /** Engine state as written by a build that predates line-in. */
    private val legacyJson = """
        {"buses":[
          {"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[]},
          {"gain":-3,"pan":0.5,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},
          {"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},
          {"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]},
          {"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":false,"plugins":[]}
        ]}
    """.trimIndent()

    private fun jsonWithSources(vararg sources: Int): String {
        val buses = sources.joinToString(",") { src ->
            """{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,""" +
                """"inputSource":$src,"plugins":[]}"""
        }
        return """{"buses":[$buses]}"""
    }

    @Test
    fun `ordinals match the native enum`() {
        // BUS_SRC_PLAYBACK / BUS_SRC_LINE_IN / BUS_SRC_BOTH in dsp_engine.h.
        assertEquals(0, BusInputSource.Playback.ordinal)
        assertEquals(1, BusInputSource.LineIn.ordinal)
        assertEquals(2, BusInputSource.Both.ordinal)
    }

    @Test
    fun `fromOrdinal round-trips every value`() {
        BusInputSource.entries.forEach { source ->
            assertEquals(source, BusInputSource.fromOrdinal(source.ordinal))
        }
    }

    @Test
    fun `fromOrdinal falls back to playback on an unknown value`() {
        // A newer build could write a source this one doesn't know; silently
        // reading the track is the safe reading, not crashing or reading a
        // line input that may not exist.
        assertEquals(BusInputSource.Playback, BusInputSource.fromOrdinal(99))
        assertEquals(BusInputSource.Playback, BusInputSource.fromOrdinal(-1))
    }

    @Test
    fun `state written before line-in loads as playback`() {
        val buses = DspEngineManager.parseBusConfigsFromJson(legacyJson)
        assertEquals(5, buses.size)
        assertTrue(buses.all { it.inputSource == BusInputSource.Playback })
        // The rest of the legacy state still parses as it always did.
        assertTrue(buses[0].inputEnabled)
        assertEquals(-3f, buses[1].gainDb, 1e-4f)
        assertEquals(0.5f, buses[1].pan, 1e-4f)
    }

    @Test
    fun `every source value survives a parse`() {
        val buses = DspEngineManager.parseBusConfigsFromJson(jsonWithSources(0, 1, 2, 1, 0))
        assertEquals(BusInputSource.Playback, buses[0].inputSource)
        assertEquals(BusInputSource.LineIn, buses[1].inputSource)
        assertEquals(BusInputSource.Both, buses[2].inputSource)
        assertEquals(BusInputSource.LineIn, buses[3].inputSource)
    }

    @Test
    fun `an out-of-range source in stored state is clamped, not fatal`() {
        val buses = DspEngineManager.parseBusConfigsFromJson(jsonWithSources(7, 1, 0, 0, 0))
        assertEquals(BusInputSource.Playback, buses[0].inputSource)
        assertEquals(BusInputSource.LineIn, buses[1].inputSource)
    }

    @Test
    fun `state with no buses array falls back to the default layout`() {
        // "{}" is what getStateJson returns before the engine exists, and it is
        // written into DataStore on a fresh install.
        //
        // Only the non-logging fallback is covered here: the catch-all for
        // genuinely malformed JSON calls android.util.Log, which throws in this
        // module's plain-JVM tests (no Robolectric, by design).
        val buses = DspEngineManager.parseBusConfigsFromJson("{}")
        assertEquals(5, buses.size)
        assertTrue(buses[0].inputEnabled)
        assertTrue(buses.all { it.inputSource == BusInputSource.Playback })
    }
}
