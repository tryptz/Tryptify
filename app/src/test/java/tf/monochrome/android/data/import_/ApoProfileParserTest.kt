package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.FilterType

/**
 * Dialect coverage for [ApoProfileParser].
 *
 * These profiles reach users from AutoEq, oratory1990, Squiglink, Wavelet and
 * Poweramp, and each writes the format slightly differently. The parser is pure
 * Kotlin precisely so those variations can be pinned here instead of only being
 * discovered on a device with a file that won't load.
 */
class ApoProfileParserTest {

    private val parametricCap = 24f
    private val autoEqCap = 12f

    @Test
    fun `parses the format the app itself exports`() {
        val text = """
            Preamp: -6.8 dB
            Filter 1: ON PK Fc 105 Hz Gain -2.9 dB Q 0.70
            Filter 2: ON PK Fc 1000 Hz Gain 3.2 dB Q 1.40
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(2, r.bands.size)
        assertEquals(-6.8f, r.preamp, 0.001f)
        assertEquals(105f, r.bands[0].freq, 0.001f)
        assertEquals(-2.9f, r.bands[0].gain, 0.001f)
        assertEquals(0.70f, r.bands[0].q, 0.001f)
        assertEquals(FilterType.PEAKING, r.bands[0].type)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `maps shelf aliases and slope-qualified shelves`() {
        val text = """
            Preamp: -5.0 dB
            Filter 1: ON LSC 12 dB Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON HS Fc 10000 Hz Gain -2.0 dB Q 0.70
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(2, r.bands.size)
        assertEquals(FilterType.LOWSHELF, r.bands[0].type)
        // The bare "12" of the slope qualifier must not be read as Fc or Gain.
        assertEquals(105f, r.bands[0].freq, 0.001f)
        assertEquals(5.5f, r.bands[0].gain, 0.001f)
        assertEquals(FilterType.HIGHSHELF, r.bands[1].type)
    }

    @Test
    fun `OFF filters are kept as disabled bands, not dropped`() {
        val text = """
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Filter 2: OFF PK Fc 200 Hz Gain 2.0 dB Q 1.0
            Filter 3: ON PK Fc 300 Hz Gain 3.0 dB Q 1.0
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(3, r.bands.size)
        assertTrue(r.bands[0].enabled)
        assertFalse(r.bands[1].enabled)
        assertTrue(r.bands[2].enabled)
    }

    @Test
    fun `None placeholders are skipped without warning`() {
        val text = """
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Filter 2: ON None
            Filter 3: ON None
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(1, r.bands.size)
        assertTrue(r.warnings.none { it.contains("unsupported", ignoreCase = true) })
    }

    @Test
    fun `unsupported filter types are skipped and reported`() {
        val text = """
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Filter 2: ON HP Fc 30 Hz Q 0.70
            Filter 3: ON LP Fc 18000 Hz Q 0.70
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(1, r.bands.size)
        val warning = r.warnings.single { it.contains("unsupported", ignoreCase = true) }
        assertTrue(warning.contains("HP"))
        assertTrue(warning.contains("LP"))
    }

    @Test
    fun `accepts EU decimal commas`() {
        val text = """
            Preamp: -6,8 dB
            Filter 1: ON PK Fc 105 Hz Gain -2,9 dB Q 0,70
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(-6.8f, r.preamp, 0.001f)
        assertEquals(-2.9f, r.bands[0].gain, 0.001f)
        assertEquals(0.70f, r.bands[0].q, 0.001f)
    }

    @Test
    fun `clamps gain to the destination ceiling and says so`() {
        val text = "Filter 1: ON PK Fc 100 Hz Gain 18.0 dB Q 1.0"

        val intoAutoEq = ApoProfileParser.parse(text, autoEqCap)
        assertEquals(12f, intoAutoEq.bands[0].gain, 0.001f)
        assertTrue(intoAutoEq.warnings.any { it.contains("12 dB") })

        // The same file is untouched by the Parametric surface's wider ceiling.
        val intoParametric = ApoProfileParser.parse(text, parametricCap)
        assertEquals(18f, intoParametric.bands[0].gain, 0.001f)
    }

    @Test
    fun `no filter count limit`() {
        val text = buildString {
            appendLine("Preamp: -3.0 dB")
            repeat(40) { i ->
                appendLine("Filter ${i + 1}: ON PK Fc ${100 + i * 100} Hz Gain 1.0 dB Q 1.0")
            }
        }

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(40, r.bands.size)
        // Ids stay sequential and unique — they key the UI's band selection.
        assertEquals(r.bands.map { it.id }, r.bands.indices.toList())
    }

    @Test
    fun `an AutoEq measurement CSV is recognised rather than failing to parse`() {
        val text = """
            frequency,raw,smoothed,error,equalization,target
            20.0,3.5,3.4,-1.2,1.2,4.7
            21.0,3.4,3.3,-1.1,1.1,4.5
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertTrue(r.looksLikeMeasurement)
        assertTrue(r.isEmpty)
    }

    @Test
    fun `CSV rows are clamped and counted like filter lines`() {
        val text = """
            type,fc,gain,q
            PK,100,40.0,1.0
        """.trimIndent()

        val r = ApoProfileParser.parse(text, autoEqCap)

        assertEquals(12f, r.bands[0].gain, 0.001f)
        assertTrue(r.warnings.any { it.contains("12 dB") })
    }

    @Test
    fun `newline-stripped SeapEngine paste is rebuilt and parses fully`() {
        // Verbatim from a chat paste: comment header and all ten filters glued
        // into ONE line — no newlines survived the copy.
        val text = "# Headphone correction: Moondrop Blessing 2# Rig: IEC-711 Clone" +
            "# Target Curve: HARMAN# Configured Bass Boost: 3 dB" +
            "# Exported from SeapEngine Professional Audio Workstation" +
            "Filter 1: ON PK Fc 20 Hz Gain 6.7 dB Q 1.4" +
            "Filter 2: ON PK Fc 50 Hz Gain 5.8 dB Q 1.2" +
            "Filter 3: ON PK Fc 100 Hz Gain 3.9 dB Q 1.0" +
            "Filter 4: ON PK Fc 200 Hz Gain 1 dB Q 1.4" +
            "Filter 5: ON PK Fc 500 Hz Gain 0.1 dB Q 1.4" +
            "Filter 6: ON PK Fc 1000 Hz Gain -0.3 dB Q 1.4" +
            "Filter 7: ON PK Fc 2000 Hz Gain 0.4 dB Q 1.8" +
            "Filter 8: ON PK Fc 4000 Hz Gain 1.7 dB Q 2.0" +
            "Filter 9: ON PK Fc 8000 Hz Gain 0.5 dB Q 2.5" +
            "Filter 10: ON PK Fc 16000 Hz Gain 4 dB Q 2.5"

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(10, r.bands.size)
        assertEquals(20f, r.bands[0].freq, 0.001f)
        assertEquals(6.7f, r.bands[0].gain, 0.001f)
        assertEquals(1.4f, r.bands[0].q, 0.001f)
        // Integer gain ("Gain 1 dB") and the final line both survive.
        assertEquals(1f, r.bands[3].gain, 0.001f)
        assertEquals(16000f, r.bands[9].freq, 0.001f)
        assertEquals(2.5f, r.bands[9].q, 0.001f)
        // No preamp in the file -> derived from the +6.7 peak.
        assertEquals(-6.7f, r.preamp, 0.001f)
    }

    @Test
    fun `the same SeapEngine profile with real newlines parses identically`() {
        val text = """
            # Headphone correction: Moondrop Blessing 2
            # Exported from SeapEngine Professional Audio Workstation
            Filter 1: ON PK Fc 20 Hz Gain 6.7 dB Q 1.4
            Filter 2: ON PK Fc 50 Hz Gain 5.8 dB Q 1.2
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(2, r.bands.size)
        assertEquals(50f, r.bands[1].freq, 0.001f)
    }

    @Test
    fun `20-filter AutoEq export with shelves and 3-decimal Qs parses fully`() {
        val text = """
            Preamp: -9.1 dB
            Filter 1: ON PK Fc 20 Hz Gain 2.92 dB Q 0.406
            Filter 2: ON PK Fc 55 Hz Gain 1.32 dB Q 0.811
            Filter 3: ON PK Fc 79 Hz Gain 0.27 dB Q 1.404
            Filter 4: ON PK Fc 277 Hz Gain -0.91 dB Q 1.167
            Filter 5: ON PK Fc 389 Hz Gain -5.82 dB Q 1.192
            Filter 6: ON LSC Fc 784 Hz Gain -1.33 dB Q 0.707
            Filter 7: ON PK Fc 817 Hz Gain 0.95 dB Q 3.595
            Filter 8: ON PK Fc 874 Hz Gain 4.13 dB Q 2.507
            Filter 9: ON PK Fc 1185 Hz Gain -2.27 dB Q 2.107
            Filter 10: ON PK Fc 2312 Hz Gain 4.65 dB Q 1.891
            Filter 11: ON PK Fc 2874 Hz Gain -6.26 dB Q 1.413
            Filter 12: ON PK Fc 3820 Hz Gain 5.39 dB Q 3.996
            Filter 13: ON PK Fc 5659 Hz Gain -8.09 dB Q 4.000
            Filter 14: ON PK Fc 6179 Hz Gain 6.52 dB Q 1.992
            Filter 15: ON HSC Fc 6977 Hz Gain -5.53 dB Q 0.707
            Filter 16: ON PK Fc 8119 Hz Gain -3.58 dB Q 3.950
            Filter 17: ON PK Fc 9126 Hz Gain 4.89 dB Q 3.825
            Filter 18: ON PK Fc 10561 Hz Gain 3.83 dB Q 3.383
            Filter 19: ON PK Fc 13000 Hz Gain -9.34 dB Q 2.131
            Filter 20: ON PK Fc 16000 Hz Gain 16.00 dB Q 0.411
        """.trimIndent()

        // Parametric surface (±24 dB): everything passes through untouched.
        val r = ApoProfileParser.parse(text, parametricCap)
        assertEquals(20, r.bands.size)
        assertEquals(-9.1f, r.preamp, 0.001f)
        assertEquals(FilterType.LOWSHELF, r.bands[5].type)
        assertEquals(784f, r.bands[5].freq, 0.001f)
        assertEquals(FilterType.HIGHSHELF, r.bands[14].type)
        assertEquals(0.406f, r.bands[0].q, 0.001f)
        assertEquals(16f, r.bands[19].gain, 0.001f)
        assertTrue(r.warnings.isEmpty())

        // AutoEQ surface (±12 dB): only Filter 20's +16 dB is clamped, loudly.
        val auto = ApoProfileParser.parse(text, autoEqCap)
        assertEquals(12f, auto.bands[19].gain, 0.001f)
        assertTrue(auto.warnings.any { it.contains("12 dB") })
    }

    @Test
    fun `band CSV rows parse`() {
        val text = """
            type,fc,gain,q
            PK,105,-2.9,0.7
            LSC,200,3.0,0.7
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(2, r.bands.size)
        assertEquals(FilterType.PEAKING, r.bands[0].type)
        assertEquals(FilterType.LOWSHELF, r.bands[1].type)
    }

    @Test
    fun `a file with no filters explains the expected format`() {
        val r = ApoProfileParser.parse("hello world\nnot an eq profile", parametricCap)

        assertTrue(r.isEmpty)
        assertTrue(r.warnings.single().contains("Filter 1: ON PK"))
    }

    @Test
    fun `missing preamp is derived from the peak boost`() {
        val text = """
            Filter 1: ON PK Fc 100 Hz Gain 4.0 dB Q 1.0
            Filter 2: ON PK Fc 200 Hz Gain 6.5 dB Q 1.0
        """.trimIndent()

        val r = ApoProfileParser.parse(text, parametricCap)

        assertEquals(-6.5f, r.preamp, 0.001f)
        assertTrue(r.warnings.any { it.contains("preamp", ignoreCase = true) })
    }

    @Test
    fun `shelves without an explicit Q get the Butterworth default`() {
        val r = ApoProfileParser.parse("Filter 1: ON LS Fc 105 Hz Gain 5.0 dB", parametricCap)

        assertEquals(0.707f, r.bands[0].q, 0.001f)
    }
}
