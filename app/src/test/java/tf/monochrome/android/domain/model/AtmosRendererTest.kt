package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Atmos renderer domain infrastructure: Atmos detection,
 * codec capability, channel-layout mapping and renderer-profile defaults.
 */
class AtmosRendererTest {

    // ---- isDolbyAtmos ------------------------------------------------------

    @Test
    fun `JOC extension flag is authoritative`() {
        // A confirmed JOC extension is Atmos regardless of the text.
        assertTrue(isDolbyAtmos(hasJocExtension = true, title = "Plain Song"))
        // A confirmed absence overrides even an "Atmos" phrase in the title.
        assertFalse(isDolbyAtmos(hasJocExtension = false, title = "Song (Dolby Atmos)"))
    }

    @Test
    fun `atmos or joc mime type is detected`() {
        assertTrue(isDolbyAtmos(mimeType = "audio/eac3-joc"))
        assertTrue(isDolbyAtmos(mimeType = "audio/vnd.dolby.atmos"))
        assertFalse(isDolbyAtmos(mimeType = "audio/eac3")) // plain DD+ is not Atmos
    }

    @Test
    fun `dolby atmos phrase in metadata is detected`() {
        assertTrue(isDolbyAtmos(version = "Dolby Atmos"))
        assertTrue(isDolbyAtmos(title = "Track (dolby  atmos)"))
        assertTrue(isDolbyAtmos(albumVersion = "Dolby Atmos Edition"))
        assertTrue(isDolbyAtmos(albumTitle = "Some Album (Dolby Atmos)"))
    }

    @Test
    fun `bare atmos or plain text is not detected`() {
        assertFalse(isDolbyAtmos(title = "Atmosphere")) // missing "dolby"
        assertFalse(isDolbyAtmos(version = "Remastered"))
        assertFalse(isDolbyAtmos())
    }

    // ---- isAtmosCapableCodec ----------------------------------------------

    @Test
    fun `ec3 family is atmos-capable by codec, mime or extension`() {
        assertTrue(isAtmosCapableCodec(codec = "EC-3"))
        assertTrue(isAtmosCapableCodec(codec = "E-AC-3 JOC"))
        assertTrue(isAtmosCapableCodec(mimeType = "audio/eac3"))
        assertTrue(isAtmosCapableCodec(fileExtension = ".ec3"))
        assertTrue(isAtmosCapableCodec(fileExtension = "eac3"))
    }

    @Test
    fun `lossless and lossy stereo codecs are not atmos-capable`() {
        assertFalse(isAtmosCapableCodec(codec = "FLAC"))
        assertFalse(isAtmosCapableCodec(codec = "ALAC"))
        assertFalse(isAtmosCapableCodec(mimeType = "audio/mpeg"))
        assertFalse(isAtmosCapableCodec(fileExtension = ".wav"))
        assertFalse(isAtmosCapableCodec())
    }

    @Test
    fun `AC-4 is Atmos-capable but not E-AC-3`() {
        assertTrue(isAtmosCapableCodec(mimeType = "audio/ac4"))
        assertTrue(isAtmosCapableCodec(codec = "ac-4"))
        assertTrue(isAtmosCapableCodec(fileExtension = ".ac4"))
        assertTrue(isAc4Codec(mimeType = "audio/ac4"))
        assertFalse(isEac3Codec(mimeType = "audio/ac4"))
        assertFalse(isAc4Codec(mimeType = "audio/eac3"))
        // Codec alone never makes a track Atmos.
        assertFalse(isDolbyAtmos(mimeType = "audio/ac4"))
    }

    // ---- ChannelLayout -----------------------------------------------------

    @Test
    fun `channel count maps to the nearest known layout`() {
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(null))
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(1))
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromChannelCount(2))
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromChannelCount(6))
        assertEquals(ChannelLayout.SURROUND_7_1, ChannelLayout.fromChannelCount(8))
        assertEquals(ChannelLayout.SURROUND_5_1_4, ChannelLayout.fromChannelCount(10))
        assertEquals(ChannelLayout.ATMOS_7_1_4, ChannelLayout.fromChannelCount(12))
        assertEquals(ChannelLayout.ATMOS_9_1_4, ChannelLayout.fromChannelCount(14))
        assertEquals(ChannelLayout.ATMOS_9_1_6, ChannelLayout.fromChannelCount(16))
        assertEquals(ChannelLayout.ATMOS_9_1_6, ChannelLayout.fromChannelCount(24))
    }

    @Test
    fun `speaker masks name exactly the layout's speakers`() {
        for (layout in ChannelLayout.entries) {
            assertEquals(layout.label, layout.channelCount, Integer.bitCount(layout.speakerMask))
            assertEquals(layout.label, layout.sinkChannelCount, Integer.bitCount(layout.sinkChannelMask))
            // Padding only ever adds positions; every speaker keeps its bit.
            assertEquals(layout.speakerMask, layout.sinkChannelMask and layout.speakerMask)
        }
        assertEquals(0xFC, ChannelLayout.SURROUND_5_1.speakerMask)       // Android CHANNEL_OUT_5POINT1
        assertEquals(0x18FC, ChannelLayout.SURROUND_7_1.speakerMask)     // CHANNEL_OUT_7POINT1_SURROUND
        assertEquals(0xB58FC, ChannelLayout.ATMOS_7_1_4.speakerMask)     // CHANNEL_OUT_7POINT1POINT4
    }

    @Test
    fun `sink channel counts are ones Media3 accepts`() {
        val accepted = setOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 24)
        for (layout in ChannelLayout.entries) {
            assertTrue(layout.label, layout.sinkChannelCount in accepted)
        }
        assertEquals(24, ChannelLayout.ATMOS_9_1_4.sinkChannelCount)
        assertEquals(24, ChannelLayout.ATMOS_9_1_6.sinkChannelCount)
    }

    @Test
    fun `speaker slots follow mask order and skip the padding`() {
        for (layout in ChannelLayout.entries) {
            val slots = layout.speakerSlots()
            assertEquals(layout.channelCount, slots.size)
            for (i in 1 until slots.size) assertTrue(slots[i] > slots[i - 1])
            assertTrue(slots.last() < layout.sinkChannelCount)
        }
        // Unpadded layouts are the identity.
        assertEquals((0 until 12).toList(), ChannelLayout.ATMOS_7_1_4.speakerSlots().toList())
        // 9.1.6: FL FR FC LFE BL BR (0-5), then FLC FRC BC pad (6-8), SL SR (9-10), ...
        val s916 = ChannelLayout.ATMOS_9_1_6.speakerSlots()
        assertEquals(listOf(0, 1, 2, 3, 4, 5), s916.take(6))
        assertEquals(9, s916[6])  // SL after the three front/back-centre pads
    }

    @Test
    fun `speaker render is opt-in`() {
        // Whatever a device reports, the default profile stays on the stereo path.
        assertEquals(ChannelLayout.STEREO, RendererProfile.DEFAULT.speakerLayout(12))
        val on = RendererProfile.DEFAULT.copy(speakerRender = true)
        assertEquals(ChannelLayout.ATMOS_7_1_4, on.speakerLayout(12))
        assertEquals(ChannelLayout.STEREO, on.speakerLayout(2))
        val manual = on.copy(autoDetectLayout = false, layout = ChannelLayout.ATMOS_9_1_4)
        assertEquals(ChannelLayout.ATMOS_9_1_4, manual.speakerLayout(2))
    }

    @Test
    fun `speaker map has one speaker per channel with exactly one LFE above stereo`() {
        for (layout in ChannelLayout.entries) {
            val speakers = layout.speakers()
            assertEquals(layout.channelCount, speakers.size)
            val lfeCount = speakers.count { it.isLfe }
            assertEquals(if (layout == ChannelLayout.STEREO) 0 else 1, lfeCount)
        }
        // Only 7.1.4 carries height speakers.
        assertTrue(ChannelLayout.ATMOS_7_1_4.speakers().any { it.isHeight })
        assertFalse(ChannelLayout.SURROUND_7_1.speakers().any { it.isHeight })
        assertEquals(4, ChannelLayout.ATMOS_7_1_4.speakers().count { it.isHeight })
    }

    @Test
    fun `layout channel counts match the native VBAP bed layouts`() {
        assertEquals(2, ChannelLayout.STEREO.channelCount)
        assertEquals(6, ChannelLayout.SURROUND_5_1.channelCount)
        assertEquals(8, ChannelLayout.SURROUND_7_1.channelCount)
        assertEquals(12, ChannelLayout.ATMOS_7_1_4.channelCount)
        assertEquals(14, ChannelLayout.ATMOS_9_1_4.channelCount)
        assertEquals(16, ChannelLayout.ATMOS_9_1_6.channelCount)
    }

    // ---- RendererProfile ---------------------------------------------------

    @Test
    fun `default profile is safe passthrough stereo`() {
        val p = RendererProfile.DEFAULT
        assertEquals(RendererMode.PASSTHROUGH, p.mode)
        assertEquals(ChannelLayout.STEREO, p.layout)
        assertEquals(null, p.hrtfProfileId)
    }

    @Test
    fun `renderer mode default is passthrough`() {
        assertEquals(RendererMode.PASSTHROUGH, RendererMode.DEFAULT)
    }

    @Test
    fun `default profile uses typical safe renderer settings`() {
        val p = RendererProfile.DEFAULT
        assertTrue(p.autoDetectLayout)
        // Not user-facing: the fold is the one fixed matrix, and binaural is
        // derived from the HRTF mode. The field exists for the native ordinal
        // and stored-blob compatibility only.
        assertEquals(StereoDownmixMode.LO_RO, p.stereoDownmix)
        assertEquals(1.0f, p.binauralStrength, 1e-6f)
        assertTrue(p.heightVirtualization)
        assertTrue(p.bassManagement)
        assertEquals(80, p.crossoverHz)
        assertEquals(0f, p.lfeGainDb, 1e-6f)
        assertEquals(DrcMode.OFF, p.drc)
        assertFalse(p.dialogNormalization)
    }

    @Test
    fun `clamped coerces continuous fields into range`() {
        val wild = RendererProfile(
            binauralStrength = 5f,
            crossoverHz = 5000,
            lfeGainDb = -99f,
            downmixPreampDb = 40f,
        ).clamped()
        assertEquals(6f, wild.downmixPreampDb, 1e-6f)
        assertEquals(1f, wild.binauralStrength, 1e-6f)
        assertEquals(200, wild.crossoverHz)
        assertEquals(-10f, wild.lfeGainDb, 1e-6f)

        val nan = RendererProfile(binauralStrength = Float.NaN, lfeGainDb = Float.NaN).clamped()
        assertEquals(1f, nan.binauralStrength, 1e-6f) // NaN falls back to default
        assertEquals(0f, nan.lfeGainDb, 1e-6f)
    }

    @Test
    fun `effective layout honors the auto-detect flag`() {
        val auto = RendererProfile(autoDetectLayout = true, layout = ChannelLayout.ATMOS_7_1_4)
        // Auto-detect ignores the stored layout and follows the DAC channel count.
        assertEquals(ChannelLayout.SURROUND_5_1, auto.effectiveLayout(6))
        assertEquals(ChannelLayout.STEREO, auto.effectiveLayout(null))

        val manual = RendererProfile(autoDetectLayout = false, layout = ChannelLayout.SURROUND_7_1)
        // Manual keeps the chosen layout regardless of the DAC.
        assertEquals(ChannelLayout.SURROUND_7_1, manual.effectiveLayout(2))
        assertEquals(ChannelLayout.SURROUND_7_1, manual.effectiveLayout(12))
    }
}
