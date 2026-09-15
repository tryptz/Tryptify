package tf.monochrome.android.audio.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Audio Pipeline panel's contract with the truth.
 *
 * The panel is a diagnostics readout: people screenshot it and open bug
 * reports from it. Most of what is tested here is not "does it compute the
 * right number" but "does it refuse to state one it does not have" — because
 * a fabricated 48000 Hz in a bug report costs more than a dash does.
 */
class AudioPipelineSnapshotTest {

    private fun AudioPipelineSnapshot.stage(stage: PipelineStage) =
        sections.first { it.stage == stage }

    private fun AudioPipelineSnapshot.field(stage: PipelineStage, label: String) =
        stage(stage).fields.first { it.label == label }

    @Test
    fun `with nothing playing every field is a dash and none is a number`() {
        val snapshot = buildAudioPipelineSnapshot(AudioPipelineInputs())
        val printed = snapshot.sections.flatMap { it.fields }

        // "Output API" and "Speed resampler" are always knowable — the path
        // and the ratio exist whether or not audio is flowing. Everything
        // else must be honest about knowing nothing.
        val alwaysKnown = setOf("Output API", "Speed resampler")
        printed.filter { it.label !in alwaysKnown }.forEach {
            assertEquals("${it.label} invented a value", EM_DASH, it.display)
        }
        printed.forEach {
            assertFalse("${it.label} printed a null", it.display.contains("null"))
            assertFalse("${it.label} printed a zero reading", it.display.startsWith("0 "))
        }
    }

    @Test
    fun `all five stages are always present, even when empty`() {
        val snapshot = buildAudioPipelineSnapshot(AudioPipelineInputs())
        assertEquals(PipelineStage.entries.toList(), snapshot.sections.map { it.stage })
    }

    @Test
    fun `a 16-bit 44_1k FLAC reads the way the file does`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                stream = DecodedStream(
                    mimeType = "audio/flac",
                    sampleRate = 44100,
                    channelCount = 2,
                    bitrate = 1_411_000,
                    pcmBits = 16,
                ),
                chain = ChainInput(44100, 2, "Stereo", isFloat = false),
                decoderName = "c2.android.flac.decoder",
            )
        )
        assertEquals("FLAC", snapshot.field(PipelineStage.TRACK, "Format").display)
        assertEquals("16-bit", snapshot.field(PipelineStage.TRACK, "Bit Depth").display)
        assertEquals("44100 Hz", snapshot.field(PipelineStage.TRACK, "Sample Rate").display)
        assertEquals("1411 kbps", snapshot.field(PipelineStage.TRACK, "Bitrate").display)
        assertEquals("2 (Stereo)", snapshot.field(PipelineStage.TRACK, "Channels").display)
        assertEquals(
            "c2.android.flac.decoder",
            snapshot.field(PipelineStage.DECODER, "Decoder Name").display,
        )
    }

    @Test
    fun `the chain's sample rate wins over the container's`() {
        // The chain is measured at the head of the processor chain, after the
        // decoder — which is where "what is actually flowing" is decided. A
        // container that disagrees is describing the file, not the stream.
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                stream = DecodedStream(sampleRate = 44100),
                chain = ChainInput(96000, 2, "Stereo", isFloat = false),
            )
        )
        assertEquals("96000 Hz", snapshot.field(PipelineStage.TRACK, "Sample Rate").display)
    }

    @Test
    fun `tags fill in only what the live path has not reported`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                stream = DecodedStream(mimeType = "audio/flac", sampleRate = 44100),
                taggedCodec = "ALAC",
                taggedBitDepth = 24,
                taggedBitRateKbps = 900,
            )
        )
        // The live MIME wins the format...
        assertEquals("FLAC", snapshot.field(PipelineStage.TRACK, "Format").display)
        // ...and the tags supply what it was silent about.
        assertEquals("24-bit", snapshot.field(PipelineStage.TRACK, "Bit Depth").display)
        assertEquals("900 kbps", snapshot.field(PipelineStage.TRACK, "Bitrate").display)
    }

    @Test
    fun `the decoder says why it is blank rather than leaving a bare dash`() {
        val blank = buildAudioPipelineSnapshot(AudioPipelineInputs())
        assertNotNull(blank.stage(PipelineStage.DECODER).note)

        val named = buildAudioPipelineSnapshot(
            AudioPipelineInputs(decoderName = "c2.android.mp3.decoder")
        )
        assertNull(named.stage(PipelineStage.DECODER).note)
    }

    @Test
    fun `an unknown output rate is an arrow into a dash, and is explained`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(chain = ChainInput(44100, 2, "Stereo", isFloat = false))
        )
        assertEquals("44100 Hz → —", snapshot.field(PipelineStage.RESAMPLER, "I/O Rate").display)
        assertEquals(EM_DASH, snapshot.field(PipelineStage.RESAMPLER, "Conversion").display)
        assertTrue(
            snapshot.stage(PipelineStage.RESAMPLER).note.orEmpty()
                .contains("does not resample")
        )
    }

    @Test
    fun `a matching output rate says no conversion rather than naming one`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                chain = ChainInput(48000, 2, "Stereo", isFloat = false),
                halSampleRateHz = 48000,
            )
        )
        assertEquals("48000 Hz → 48000 Hz", snapshot.field(PipelineStage.RESAMPLER, "I/O Rate").display)
        assertTrue(
            snapshot.field(PipelineStage.RESAMPLER, "Conversion").display.startsWith("None")
        )
    }

    @Test
    fun `the HAL is named only when it is actually converting`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                chain = ChainInput(44100, 2, "Stereo", isFloat = false),
                halSampleRateHz = 48000,
            )
        )
        assertEquals("44100 Hz → 48000 Hz", snapshot.field(PipelineStage.RESAMPLER, "I/O Rate").display)
        assertEquals("Android HAL", snapshot.field(PipelineStage.RESAMPLER, "Conversion").display)
    }

    @Test
    fun `the speed resampler reports inactive at unity and the ratio otherwise`() {
        val idle = buildAudioPipelineSnapshot(AudioPipelineInputs(speedRatio = 1f))
        assertEquals(
            "Inactive (1.00×)",
            idle.field(PipelineStage.RESAMPLER, "Speed resampler").display,
        )
        val fast = buildAudioPipelineSnapshot(AudioPipelineInputs(speedRatio = 1.25f))
        assertEquals(
            "Active (1.25×)",
            fast.field(PipelineStage.RESAMPLER, "Speed resampler").display,
        )
    }

    @Test
    fun `no stereo stage and a flat stereo stage are different answers`() {
        val absent = buildAudioPipelineSnapshot(AudioPipelineInputs(stereoWidthDb = null))
        assertEquals(EM_DASH, absent.field(PipelineStage.DSP, "Stereo Expand").display)
        assertNotNull("a missing stereo stage must be explained", absent.stage(PipelineStage.DSP).note)

        val flat = buildAudioPipelineSnapshot(AudioPipelineInputs(stereoWidthDb = 0f))
        assertEquals("+0.0 dB width", flat.field(PipelineStage.DSP, "Stereo Expand").display)
        assertNull(flat.stage(PipelineStage.DSP).note)

        val wide = buildAudioPipelineSnapshot(AudioPipelineInputs(stereoWidthDb = 3.5f))
        assertEquals("+3.5 dB width", wide.field(PipelineStage.DSP, "Stereo Expand").display)
    }

    @Test
    fun `latency is the buffer measured at the running rate`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                chain = ChainInput(48000, 2, "Stereo", isFloat = false),
                dspBlockFrames = 1024,
                visualizerFftSize = 8192,
            )
        )
        assertEquals("1024 frames", snapshot.field(PipelineStage.DSP, "Buffers").display)
        // 1024 / 48000 = 21.333 ms
        assertEquals("21.3 ms", snapshot.field(PipelineStage.DSP, "Latency").display)
        // 8192 / 48000 = 170.667 ms
        assertEquals("170.7 ms", snapshot.field(PipelineStage.DSP, "Visualizer Latency").display)
    }

    @Test
    fun `latency is a dash rather than infinity when the rate is unknown`() {
        val snapshot = buildAudioPipelineSnapshot(AudioPipelineInputs(dspBlockFrames = 1024))
        assertEquals(EM_DASH, snapshot.field(PipelineStage.DSP, "Latency").display)
    }

    @Test
    fun `exclusive USB is the one path that knows the far side of the link`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                stream = DecodedStream(sampleRate = 44100, pcmBits = 16),
                chain = ChainInput(44100, 2, "Stereo", isFloat = false),
                outputPath = OutputPath.USB_EXCLUSIVE,
                deviceName = "Focal Bathys",
                usb = UsbStream(48000, 24, 2, detail = "UAC2 · USB 2.0 HS · async feedback"),
            )
        )
        assertEquals("Focal Bathys", snapshot.field(PipelineStage.OUTPUT, "Device Name").display)
        assertEquals("16-bit", snapshot.field(PipelineStage.OUTPUT, "Bit Depth In").display)
        assertEquals("24-bit", snapshot.field(PipelineStage.OUTPUT, "Bit Depth Out").display)
        assertEquals("48000 Hz", snapshot.field(PipelineStage.OUTPUT, "Sample Rate").display)
        assertEquals(
            "UAC2 · USB 2.0 HS · async feedback",
            snapshot.field(PipelineStage.OUTPUT, "Link").display,
        )
        // Nothing to apologise for on this path — the out side really is known.
        assertNull(snapshot.stage(PipelineStage.OUTPUT).note)
        assertEquals(
            "libusb (UAC exclusive)",
            snapshot.field(PipelineStage.DSP, "Output API").display,
        )
    }

    @Test
    fun `every other path admits it cannot see the DAC's own depth`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                outputPath = OutputPath.AUDIO_TRACK,
                deviceName = "Pixel Buds Pro",
            )
        )
        assertEquals(EM_DASH, snapshot.field(PipelineStage.OUTPUT, "Bit Depth Out").display)
        assertNotNull(snapshot.stage(PipelineStage.OUTPUT).note)
        assertEquals("AudioTrack", snapshot.field(PipelineStage.DSP, "Output API").display)
        // A link line is only added when there is a link to describe.
        assertTrue(snapshot.stage(PipelineStage.OUTPUT).fields.none { it.label == "Link" })
    }

    @Test
    fun `float PCM is called float in both places it appears`() {
        val snapshot = buildAudioPipelineSnapshot(
            AudioPipelineInputs(
                stream = DecodedStream(pcmBits = 32, pcmIsFloat = true),
                chain = ChainInput(48000, 2, "Stereo", isFloat = true),
            )
        )
        assertEquals("32-bit float", snapshot.field(PipelineStage.TRACK, "Bit Depth").display)
        assertEquals("32-bit float", snapshot.field(PipelineStage.DSP, "PCM Format").display)
    }

    @Test
    fun `codec names come from the MIME, and an unmapped one still says something`() {
        assertEquals("FLAC", codecName("audio/flac", null))
        assertEquals("MP3", codecName("audio/mpeg", null))
        assertEquals("AAC", codecName("audio/mp4a-latm", null))
        assertEquals("E-AC-3 JOC", codecName("audio/eac3-joc", null))
        assertEquals("Opus", codecName("audio/opus", null))
        // Unmapped: the subtype beats a dash in a bug report.
        assertEquals("DSF", codecName("audio/dsf", null))
        // No MIME at all: fall back to the library row's own word for it.
        assertEquals("ALAC", codecName(null, "ALAC"))
        assertNull(codecName(null, null))
    }

    @Test
    fun `a zero or negative reading is treated as no reading`() {
        // Media3 and the tag readers both use 0 and -1 for "unset" in places.
        assertNull(hz(0))
        assertNull(hz(-1))
        assertNull(bits(0))
        assertNull(kbps(0))
        assertNull(channels(0, "Stereo"))
        assertNull(latencyMs(0, 48000))
        assertNull(latencyMs(1024, 0))
    }

    @Test
    fun `channels print the count alone when the layout is unnamed`() {
        assertEquals("2 (Stereo)", channels(2, "Stereo"))
        assertEquals("6", channels(6, null))
        assertEquals("6", channels(6, "  "))
    }
}
