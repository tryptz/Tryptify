package tf.monochrome.android.audio.pipeline

/**
 * What the audio pipeline is doing to the track that is playing, as text.
 *
 * Pure Kotlin on purpose — no Android, no Media3, no Compose. Everything in
 * this file is a plain value or a function of plain values, so the awkward
 * part (deciding what is true, and what to say when nothing is) is unit
 * testable, and the panel is left with nothing to do but draw it.
 *
 * ## The rule this file exists to enforce
 *
 * **A field with no source shows an em dash. It never shows a plausible
 * number.** This is a diagnostics panel: people screenshot it and open bug
 * reports from it, and they will believe whatever it says. Several of the
 * facts a pipeline panel conventionally shows genuinely do not exist in this
 * app — there is no resampler quality setting, no anti-alias cutoff on the
 * output path, no stereo expander unless one is inserted by hand — and
 * inventing convincing values for them would make every one of those bug
 * reports wrong in a way nobody could see.
 */

/** The stages, top to bottom, in the order audio passes through them. */
enum class PipelineStage(val title: String) {
    TRACK("Track Info"),
    DECODER("Decoder"),
    RESAMPLER("Resampler"),
    DSP("DSP"),
    OUTPUT("Output Device"),
}

/**
 * One line. A null [value] is not a missing line — it is the honest answer
 * "this app does not know", and it prints as [EM_DASH].
 */
data class PipelineField(val label: String, val value: String?) {
    val display: String get() = value ?: EM_DASH
    val isKnown: Boolean get() = value != null
}

/** [note] is an aside under the fields, for when the dashes need explaining. */
data class PipelineSection(
    val stage: PipelineStage,
    val fields: List<PipelineField>,
    val note: String? = null,
)

data class AudioPipelineSnapshot(val sections: List<PipelineSection>)

const val EM_DASH = "—"

// ── Inputs ──────────────────────────────────────────────────────────────

/**
 * The decoder's own account of the stream.
 *
 * Only the playback service can see this: it comes off Media3's `Format`, and
 * `MediaController` — which is all the UI has — does not carry one. Held as
 * primitives rather than as a `Format` so this file stays Media3-free and the
 * builder can be tested without one.
 */
data class DecodedStream(
    val mimeType: String? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    /** Bits per second, as the container or the decoder reports it. */
    val bitrate: Int? = null,
    /** Bits per PCM sample after decoding; null when the decoder did not say. */
    val pcmBits: Int? = null,
    val pcmIsFloat: Boolean = false,
)

/** What the head of the processor chain sees, from `ChannelDetectorProcessor`. */
data class ChainInput(
    val sampleRate: Int,
    val channelCount: Int,
    val layoutName: String,
    val isFloat: Boolean,
)

/** How the samples are actually leaving the device. */
enum class OutputPath(val api: String) {
    /** The ordinary path: Android's mixer, then the HAL. */
    AUDIO_TRACK("AudioTrack"),

    /** Framework routing pinned to a USB DAC, still through AudioTrack. */
    USB_FRAMEWORK("AudioTrack (USB pinned)"),

    /** The iso pump: libusb talks to the DAC and Android's mixer is bypassed. */
    USB_EXCLUSIVE("libusb (UAC exclusive)"),
}

/** What the exclusive-USB pump negotiated with the DAC. Null unless streaming. */
data class UsbStream(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val detail: String? = null,
)

/**
 * Everything the builder needs, gathered by the view model.
 *
 * Nullable throughout, because on any given device at any given moment most
 * of it is genuinely unknown — nothing is playing, no DAC is attached, the
 * decoder has not reported yet.
 */
data class AudioPipelineInputs(
    val stream: DecodedStream? = null,
    val decoderName: String? = null,
    val chain: ChainInput? = null,
    /** Fallbacks from the library row, used only where the live path is silent. */
    val taggedCodec: String? = null,
    val taggedBitDepth: Int? = null,
    val taggedBitRateKbps: Int? = null,
    /** The speed/pitch resampler's ratio. 1.0 means it is not resampling. */
    val speedRatio: Float = 1f,
    val dspBlockFrames: Int? = null,
    val eqPresetName: String? = null,
    /** Width in dB of an inserted Stereo snapin, or null when none is in the chain. */
    val stereoWidthDb: Float? = null,
    val visualizerFftSize: Int? = null,
    val outputPath: OutputPath = OutputPath.AUDIO_TRACK,
    val deviceName: String? = null,
    /** What the HAL says it runs its output mix at. */
    val halSampleRateHz: Int? = null,
    val usb: UsbStream? = null,
)

// ── Formatting ──────────────────────────────────────────────────────────

internal fun hz(value: Int?): String? = value?.takeIf { it > 0 }?.let { "$it Hz" }

internal fun bits(value: Int?, isFloat: Boolean = false): String? =
    value?.takeIf { it > 0 }?.let { if (isFloat) "$it-bit float" else "$it-bit" }

internal fun kbps(bitsPerSecond: Int?): String? =
    bitsPerSecond?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" }

/**
 * Numbers here are read off a screen and pasted into bug reports, so they are
 * formatted in [java.util.Locale.ROOT] rather than the device's. A decimal
 * comma is correct for the reader and wrong for whoever receives the report —
 * and the bare `String.format` this used to be would have produced one on
 * every phone set to a European locale.
 */
internal fun millis(value: Double?): String? =
    value?.takeIf { it.isFinite() && it > 0 }
        ?.let { String.format(java.util.Locale.ROOT, "%.1f ms", it) }

/** "2 (Stereo)" — the count first, because that is the fact; the name explains it. */
internal fun channels(count: Int?, layout: String?): String? {
    if (count == null || count <= 0) return null
    val name = layout?.takeIf { it.isNotBlank() }
    return if (name == null) "$count" else "$count ($name)"
}

/**
 * A MIME type as the name people call the format.
 *
 * Falls back to the subtype rather than to null: an unmapped MIME is still
 * more informative than a dash, and "eac3-joc" tells whoever reads a bug
 * report more than "—" does.
 */
internal fun codecName(mimeType: String?, tagged: String?): String? {
    val mime = mimeType?.lowercase()
    val fromMime = when {
        mime == null -> null
        mime.endsWith("/flac") -> "FLAC"
        mime.endsWith("/alac") -> "ALAC"
        mime.endsWith("/mpeg") || mime.endsWith("/mp3") -> "MP3"
        mime.endsWith("/mp4a-latm") || mime.contains("aac") -> "AAC"
        mime.endsWith("/opus") -> "Opus"
        mime.endsWith("/vorbis") -> "Vorbis"
        mime.endsWith("/raw") -> "PCM"
        mime.endsWith("/wav") || mime.endsWith("/x-wav") -> "WAV"
        mime.endsWith("/eac3-joc") -> "E-AC-3 JOC"
        mime.endsWith("/eac3") -> "E-AC-3"
        mime.endsWith("/ac3") -> "AC-3"
        mime.endsWith("/ape") -> "APE"
        mime.endsWith("/x-ms-wma") -> "WMA"
        else -> mime.substringAfterLast('/').uppercase().takeIf { it.isNotBlank() }
    }
    return fromMime ?: tagged?.takeIf { it.isNotBlank() }
}

/** Analysis or buffering latency: how long [frames] lasts at [sampleRate]. */
internal fun latencyMs(frames: Int?, sampleRate: Int?): Double? {
    if (frames == null || frames <= 0) return null
    if (sampleRate == null || sampleRate <= 0) return null
    return frames * 1000.0 / sampleRate
}

// ── The builder ─────────────────────────────────────────────────────────

fun buildAudioPipelineSnapshot(input: AudioPipelineInputs): AudioPipelineSnapshot {
    val inRate = input.chain?.sampleRate ?: input.stream?.sampleRate
    // The chain's own view wins over the container's: it is measured at the
    // head of the processor chain, after the decoder, which is where "what is
    // actually flowing" is decided. The container is the fallback and the
    // library row is the fallback's fallback.
    val pcmBits = input.stream?.pcmBits ?: input.taggedBitDepth
    val pcmIsFloat = input.stream?.pcmIsFloat ?: input.chain?.isFloat ?: false

    val track = PipelineSection(
        PipelineStage.TRACK,
        listOf(
            PipelineField("Format", codecName(input.stream?.mimeType, input.taggedCodec)),
            PipelineField("Bit Depth", bits(pcmBits, pcmIsFloat)),
            PipelineField("Sample Rate", hz(inRate)),
            PipelineField(
                "Bitrate",
                kbps(input.stream?.bitrate)
                    ?: input.taggedBitRateKbps?.takeIf { it > 0 }?.let { "$it kbps" },
            ),
            PipelineField(
                "Channels",
                channels(
                    input.chain?.channelCount ?: input.stream?.channelCount,
                    input.chain?.layoutName,
                ),
            ),
        ),
    )

    val decoder = PipelineSection(
        PipelineStage.DECODER,
        listOf(PipelineField("Decoder Name", input.decoderName?.takeIf { it.isNotBlank() })),
        note = if (input.decoderName.isNullOrBlank()) {
            "Reported once the decoder for this track starts."
        } else {
            null
        },
    )

    // The one section where the honest answer is mostly "not here". The app
    // has no sample-rate converter on the output path: every processor in the
    // chain returns the rate it was given. Whatever converts 44.1 to 48 is
    // Android's mixer or the DAC, below anything this process can see — so the
    // out-rate is only ever known when the exclusive USB pump negotiated it,
    // or when the HAL will admit to one.
    val outRate = input.usb?.sampleRateHz ?: input.halSampleRateHz
    val conversion = when {
        outRate == null -> null
        inRate == null -> null
        outRate == inRate -> "None — the output takes the source rate"
        else -> when (input.outputPath) {
            OutputPath.USB_EXCLUSIVE -> "USB DAC (exclusive)"
            OutputPath.USB_FRAMEWORK -> "Android HAL, into a USB DAC"
            OutputPath.AUDIO_TRACK -> "Android HAL"
        }
    }
    val ratio = input.speedRatio
    val resampler = PipelineSection(
        PipelineStage.RESAMPLER,
        listOf(
            PipelineField(
                "I/O Rate",
                when {
                    inRate == null -> null
                    outRate == null -> "${inRate} Hz → $EM_DASH"
                    else -> "${inRate} Hz → ${outRate} Hz"
                },
            ),
            PipelineField("Conversion", conversion),
            PipelineField(
                "Speed resampler",
                if (kotlin.math.abs(ratio - 1f) < 1e-4f) {
                    "Inactive (1.00×)"
                } else {
                    String.format(java.util.Locale.ROOT, "Active (%.2f×)", ratio)
                },
            ),
        ),
        note = if (outRate == null) {
            "Tryptify does not resample. Any conversion happens in Android's " +
                "mixer or in the DAC, which do not report a rate here."
        } else {
            null
        },
    )

    val blockLatency = latencyMs(input.dspBlockFrames, inRate)
    val fftLatency = latencyMs(input.visualizerFftSize, inRate)
    val dsp = PipelineSection(
        PipelineStage.DSP,
        listOf(
            PipelineField(
                "PCM Format",
                when {
                    input.chain == null && pcmBits == null -> null
                    input.chain?.isFloat == true || pcmIsFloat -> "32-bit float"
                    else -> bits(pcmBits) ?: "Integer PCM"
                },
            ),
            PipelineField("Sample Rate", hz(inRate)),
            PipelineField("EQ Preset", input.eqPresetName?.takeIf { it.isNotBlank() }),
            PipelineField(
                "Stereo Expand",
                input.stereoWidthDb?.let {
                    String.format(java.util.Locale.ROOT, "%+.1f dB width", it)
                },
            ),
            PipelineField("Buffers", input.dspBlockFrames?.takeIf { it > 0 }?.let { "$it frames" }),
            PipelineField("Latency", millis(blockLatency)),
            PipelineField("Visualizer Latency", millis(fftLatency)),
            PipelineField("Output API", input.outputPath.api),
        ),
        note = if (input.stereoWidthDb == null) {
            "Stereo Expand reads the Stereo snapin's width, and none is in the mixer."
        } else {
            null
        },
    )

    val output = PipelineSection(
        PipelineStage.OUTPUT,
        listOfNotNull(
            PipelineField("Device Name", input.deviceName?.takeIf { it.isNotBlank() }),
            PipelineField("Bit Depth In", bits(pcmBits, pcmIsFloat)),
            PipelineField("Bit Depth Out", bits(input.usb?.bitsPerSample)),
            PipelineField("Sample Rate", hz(outRate)),
            input.usb?.detail?.let { PipelineField("Link", it) },
        ),
        note = if (input.outputPath != OutputPath.USB_EXCLUSIVE) {
            "Android reports what is connected, not what the DAC converts to. " +
                "The out side is only measurable over exclusive USB."
        } else {
            null
        },
    )

    return AudioPipelineSnapshot(listOf(track, decoder, resampler, dsp, output))
}
