package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain infrastructure for the clean-room Dolby Atmos renderer: where a track's
 * audio comes from, whether it is Atmos, the loudspeaker layout to render to, and
 * which of the three renderer modes to use. These are pure, dependency-free
 * types so they can be unit-tested on the JVM and reused across the player,
 * library and settings layers. The heavy lifting (E-AC-3 JOC decode, object
 * render) lives in `cpp/atmos`; this is the Kotlin surface that drives it.
 */

/** Where a library entry's audio bytes come from. */
enum class TrackSource {
    /** Scanned from on-device storage / MediaStore. */
    LOCAL,

    /** User-imported file (SAF pick, share-sheet, sideload). */
    IMPORTED,

    /** Apple Music via the sanctioned MusicKit path — plays through Apple's */
    /** managed player, alongside (not through) the DSP chain. */
    MUSICKIT,
}

/**
 * The three renderer modes from the plan. The setting picks how Atmos / spatial
 * content is turned into speaker feeds.
 */
enum class RendererMode(val displayName: String, val description: String) {
    /** Pass the decoded stream straight through — bit-perfect stereo / core bed, */
    /** no object rendering. Used for plain stereo and when spatialization is off. */
    PASSTHROUGH("Direct", "Bit-perfect passthrough — no spatial rendering"),

    /** Full object render: JOC objects reconstructed and placed via HRTF */
    /** (headphones) or VBAP (multichannel DAC). The differentiating path. */
    OBJECT_RENDER("Object render", "Full Atmos object rendering (HRTF / VBAP)"),

    /** Fallback: decode the core bed only and spatialize it through the HRTF */
    /** engine — for content or devices where full object decode is unavailable. */
    BED_HRTF("Bed + HRTF", "Spatialize the core bed via HRTF (no object decode)");

    companion object {
        val DEFAULT = PASSTHROUGH
    }
}

/**
 * Android `AudioFormat.CHANNEL_OUT_*` position bits. Kept here as plain values so
 * the domain model stays free of Android types (they are fixed framework
 * constants). PCM for a position mask is interleaved in ascending bit order.
 */
object AndroidChannelBits {
    const val FRONT_LEFT = 0x4
    const val FRONT_RIGHT = 0x8
    const val FRONT_CENTER = 0x10
    const val LOW_FREQUENCY = 0x20
    const val BACK_LEFT = 0x40
    const val BACK_RIGHT = 0x80
    const val FRONT_LEFT_OF_CENTER = 0x100
    const val FRONT_RIGHT_OF_CENTER = 0x200
    const val BACK_CENTER = 0x400
    const val SIDE_LEFT = 0x800
    const val SIDE_RIGHT = 0x1000
    const val TOP_CENTER = 0x2000
    const val TOP_FRONT_LEFT = 0x4000
    const val TOP_FRONT_CENTER = 0x8000
    const val TOP_FRONT_RIGHT = 0x10000
    const val TOP_BACK_LEFT = 0x20000
    const val TOP_BACK_CENTER = 0x40000
    const val TOP_BACK_RIGHT = 0x80000
    const val TOP_SIDE_LEFT = 0x100000
    const val TOP_SIDE_RIGHT = 0x200000
    const val BOTTOM_FRONT_LEFT = 0x400000
    const val BOTTOM_FRONT_CENTER = 0x800000
    const val BOTTOM_FRONT_RIGHT = 0x1000000
    const val LOW_FREQUENCY_2 = 0x2000000
    const val FRONT_WIDE_LEFT = 0x4000000
    const val FRONT_WIDE_RIGHT = 0x8000000
}

/**
 * Loudspeaker / output layout the renderer targets. The speakers, their order
 * and angles match the native speaker renderer (`cpp/atmos/speaker_renderer.h`,
 * `layout_speakers`), whose output is interleaved in Android mask-bit order.
 *
 * @property nativeId the native `OutputLayout` id (crosses JNI; not the ordinal)
 * @property channelCount real speakers, LFE included
 * @property speakerMask the Android position bits of those speakers
 * @property sinkChannelCount what the audio processor actually outputs. Media3
 *   1.5 only accepts 1-8, 10, 12 and 24 PCM channels (it derives the mask from
 *   the count), so 9.1.4 (14) and 9.1.6 (16) travel as 24-channel frames whose
 *   extra positions are silent; [sinkChannelMask] names all 24 positions so
 *   Android still routes every speaker to its real place.
 */
enum class ChannelLayout(
    val channelCount: Int,
    val label: String,
    val nativeId: Int,
    val speakerMask: Int,
    val sinkChannelCount: Int = channelCount,
) {
    STEREO(2, "2.0", 0, B.FL or B.FR),
    SURROUND_5_1(6, "5.1", 1, B.S51),
    SURROUND_7_1(8, "7.1", 2, B.S71),
    SURROUND_5_1_2(8, "5.1.2", 3, B.S51 or B.TOP_SIDES),
    SURROUND_5_1_4(10, "5.1.4", 4, B.S51 or B.TOP_QUAD),
    SURROUND_7_1_2(10, "7.1.2", 5, B.S71 or B.TOP_SIDES),
    ATMOS_7_1_4(12, "7.1.4", 6, B.S71 or B.TOP_QUAD),
    ATMOS_9_1_4(14, "9.1.4", 7, B.S71 or B.TOP_QUAD or B.WIDES, sinkChannelCount = 24),
    ATMOS_9_1_6(16, "9.1.6", 8, B.S71 or B.TOP_QUAD or B.TOP_SIDES or B.WIDES, sinkChannelCount = 24);

    /** Carries more than a stereo pair, i.e. a real speaker render. */
    val isMultichannel: Boolean get() = this != STEREO

    /** Has overhead speakers (needs API 32+ for a positional mask). */
    val hasHeight: Boolean get() = speakerMask and B.ALL_TOPS != 0

    /** Android position mask for the [sinkChannelCount]-wide frame. */
    val sinkChannelMask: Int
        get() {
            var mask = speakerMask
            // Pad with positions no layout here uses, lowest bits first, so the
            // speakers keep their mask-order slots.
            for (bit in B.PADDING) {
                if (Integer.bitCount(mask) >= sinkChannelCount) break
                mask = mask or bit
            }
            return mask
        }

    /**
     * Sink slot of each speaker (in [speakers] order): its bit's rank within
     * [sinkChannelMask]. Identity except for the padded 24-channel layouts.
     */
    fun speakerSlots(): IntArray {
        val sink = sinkChannelMask
        val slots = IntArray(channelCount)
        var speaker = 0
        var slot = 0
        for (bit in 0 until 31) {
            val b = 1 shl bit
            if (sink and b == 0) continue
            if (speakerMask and b != 0) slots[speaker++] = slot
            slot++
        }
        return slots
    }

    companion object {
        /**
         * Best layout for a device's reported maximum channel count. Falls back
         * to stereo for mono/unknown and to the largest layout that fits
         * otherwise. 8 and 10 channels are ambiguous (7.1 vs 5.1.2, 5.1.4 vs
         * 7.1.2); the more common home layout wins — pick the other manually.
         */
        fun fromChannelCount(channels: Int?): ChannelLayout = when {
            channels == null || channels <= 2 -> STEREO
            channels < 8 -> SURROUND_5_1
            channels < 10 -> SURROUND_7_1
            channels < 12 -> SURROUND_5_1_4
            channels < 14 -> ATMOS_7_1_4
            channels < 16 -> ATMOS_9_1_4
            else -> ATMOS_9_1_6
        }
    }
}

// Short aliases for the mask table above.
private object B {
    const val FL = AndroidChannelBits.FRONT_LEFT
    const val FR = AndroidChannelBits.FRONT_RIGHT
    const val S51 = FL or FR or AndroidChannelBits.FRONT_CENTER or AndroidChannelBits.LOW_FREQUENCY or
        AndroidChannelBits.BACK_LEFT or AndroidChannelBits.BACK_RIGHT
    const val S71 = S51 or AndroidChannelBits.SIDE_LEFT or AndroidChannelBits.SIDE_RIGHT
    const val TOP_SIDES = AndroidChannelBits.TOP_SIDE_LEFT or AndroidChannelBits.TOP_SIDE_RIGHT
    const val TOP_QUAD = AndroidChannelBits.TOP_FRONT_LEFT or AndroidChannelBits.TOP_FRONT_RIGHT or
        AndroidChannelBits.TOP_BACK_LEFT or AndroidChannelBits.TOP_BACK_RIGHT
    const val WIDES = AndroidChannelBits.FRONT_WIDE_LEFT or AndroidChannelBits.FRONT_WIDE_RIGHT
    const val ALL_TOPS = TOP_SIDES or TOP_QUAD or AndroidChannelBits.TOP_CENTER or
        AndroidChannelBits.TOP_FRONT_CENTER or AndroidChannelBits.TOP_BACK_CENTER
    val PADDING = intArrayOf(
        AndroidChannelBits.FRONT_LEFT_OF_CENTER, AndroidChannelBits.FRONT_RIGHT_OF_CENTER,
        AndroidChannelBits.BACK_CENTER, AndroidChannelBits.TOP_CENTER,
        AndroidChannelBits.TOP_FRONT_CENTER, AndroidChannelBits.TOP_BACK_CENTER,
        AndroidChannelBits.BOTTOM_FRONT_LEFT, AndroidChannelBits.BOTTOM_FRONT_RIGHT,
        AndroidChannelBits.BOTTOM_FRONT_CENTER, AndroidChannelBits.LOW_FREQUENCY_2,
    )
}

/**
 * Stereo downmix modes as the NATIVE pipeline understands them — the ordinals
 * cross JNI (0 binaural, 1 Lo/Ro, 2 Lt/Rt). This is NOT a user choice: there
 * is exactly one fold matrix (the fixed matrix, per the peqdb Downmix
 * Renderer spec), and whether the render is binaural instead is derived from
 * the HRTF mode — an active HRTF (built-in or SOFA) pushes [BINAURAL], HRTF
 * off pushes [LO_RO]. [LT_RT] exists only to keep the JNI ordinals aligned;
 * nothing selects it. The profile field remains for stored-blob
 * compatibility; whatever old value it holds, the derivation above wins.
 */
enum class StereoDownmixMode(val displayName: String, val description: String) {
    BINAURAL("Binaural", "Spatialize objects to headphone stereo via HRTF"),
    LO_RO("Stereo (Lo/Ro)", "Fixed-matrix stereo fold-down"),
    LT_RT("Surround (Lt/Rt)", "Unused — kept for native ordinal alignment");

    companion object {
        val DEFAULT = LO_RO
    }
}

/**
 * Dynamic Range Control applied to the rendered output.
 *
 * The FFmpeg core decode always applies the stream's per-block `dynrng` words
 * (Dolby Line mode) to the bed — that part is inherent and not switchable
 * without forking the decoder. So: OFF adds nothing on top; LIGHT/STANDARD add
 * the renderer's own gentle post-render leveling; HEAVY is true RF/night mode —
 * the stream's own `compr` gain word plus overload protection, falling back to
 * a fixed heavy curve for streams that never carry `compr`.
 */
enum class DrcMode(val displayName: String) {
    OFF("Off (Line mode)"),
    LIGHT("Light"),
    STANDARD("Standard"),
    HEAVY("Heavy (RF night)");

    companion object {
        val DEFAULT = OFF
    }
}

/**
 * A renderer configuration — the settings surfaced on the Atmos Renderer
 * Configuration page. Serializable so it persists as one JSON blob, and
 * [clamped] keeps every continuous field inside its legal range on read/write.
 */
@Serializable
data class RendererProfile(
    /** How spatial content becomes speaker feeds. */
    val mode: RendererMode = RendererMode.DEFAULT,
    /**
     * Render Atmos objects to physical speakers (5.1 .. 9.1.6) instead of
     * headphones/stereo. Off by default: with it off nothing about the stereo
     * path changes, whatever a connected device reports.
     */
    val speakerRender: Boolean = false,
    /** Take the channel count from the connected DAC instead of [layout]. */
    val autoDetectLayout: Boolean = true,
    /** Target loudspeaker layout when not auto-detecting. */
    val layout: ChannelLayout = ChannelLayout.STEREO,
    /** Fold-down used when the effective output is stereo. */
    val stereoDownmix: StereoDownmixMode = StereoDownmixMode.DEFAULT,
    /**
     * Master trim (dB) applied inside the stereo fold-down — the equivalent
     * of the peqdb Downmix Renderer's --master-gain-db preamp. The verbatim
     * matrix runs hot (a full-scale 5.1 frame sums to ~+14 dBFS), so this is
     * the knob to pull the fold below clipping. 0 = unity.
     */
    val downmixPreampDb: Float = 0f,
    /**
     * Optional LFE path in the fold (the peqdb renderer's --lfe-filter-mode):
     * Butterworth 4th-order 125 Hz low-pass on the LFE feed, dry path
     * delay-matched before summing. false = dry direct LFE (their default).
     */
    val lfeLowpass: Boolean = false,
    /**
     * The ONE spatial option: off (default) = every Atmos track goes through
     * the coefficient downmix renderer (fixed matrix + preamp + LFE filter);
     * on = objects are binauralized through the HRTF (built-in KEMAR or a
     * SOFA). The UI keeps [mode] in lockstep: PASSTHROUGH when off,
     * OBJECT_RENDER when on.
     */
    val hrtfEnabled: Boolean = false,
    /** HRTF/AutoEQ measurement id for the binaural back-end; null = built-in set. */
    val hrtfProfileId: String? = null,
    /** Binaural render wet amount for headphones (0 = dry, 1 = full HRTF). */
    val binauralStrength: Float = 1.0f,
    /** Virtualize height objects on layouts without physical top speakers. */
    val heightVirtualization: Boolean = true,
    /** Redirect the low frequencies of full-range objects to the LFE / sub. */
    val bassManagement: Boolean = true,
    /** Bass-management crossover frequency in Hz. */
    val crossoverHz: Int = 80,
    /** LFE channel trim in dB. */
    val lfeGainDb: Float = 0f,
    /** Dynamic range control profile. */
    val drc: DrcMode = DrcMode.DEFAULT,
    /** Apply dialogue-normalization (dialnorm) loudness alignment. */
    val dialogNormalization: Boolean = false,
) {
    /** Coerce every continuous field into its legal range; non-finite → default. */
    fun clamped(): RendererProfile {
        fun Float.c(min: Float, max: Float, fb: Float) = if (isFinite()) coerceIn(min, max) else fb
        return copy(
            binauralStrength = binauralStrength.c(0f, 1f, 1f),
            crossoverHz = crossoverHz.coerceIn(40, 200),
            lfeGainDb = lfeGainDb.c(-10f, 10f, 0f),
            downmixPreampDb = downmixPreampDb.c(-24f, 6f, 0f),
        )
    }

    /** The layout the renderer will actually target given the auto-detect flag. */
    fun effectiveLayout(dacChannelCount: Int?): ChannelLayout =
        if (autoDetectLayout) ChannelLayout.fromChannelCount(dacChannelCount) else layout

    /**
     * The loudspeaker layout Atmos renders to, or [ChannelLayout.STEREO] when
     * the speaker render is off or the effective layout has no more than two
     * channels (then the existing binaural / fold-down path runs unchanged).
     */
    fun speakerLayout(dacChannelCount: Int?): ChannelLayout =
        if (!speakerRender) ChannelLayout.STEREO else effectiveLayout(dacChannelCount)

    companion object {
        val DEFAULT = RendererProfile()
    }
}

/**
 * One loudspeaker position in a layout, used to draw the channel map. Angles
 * mirror the native speaker renderer (`cpp/atmos/speaker_renderer.h`): azimuth
 * 0 = front, growing clockwise (+ = right); elevation up from ear level.
 */
data class SpeakerChannel(
    val label: String,
    val azimuthDeg: Float,
    val elevationDeg: Float = 0f,
    val isLfe: Boolean = false,
) {
    val isHeight: Boolean get() = elevationDeg > 1f
}

/**
 * The speakers that make up a layout, in output channel order — Android
 * mask-bit order (FL FR FC LFE BL BR SL SR TFL TFR TBL TBR TSL TSR FWL FWR),
 * which is also the order the native renderer writes. The count matches
 * [ChannelLayout.channelCount]. Note the 7.x rears (Android BACK_*) come
 * before the sides (SIDE_*), and a 5.x layout's surrounds are the BACK pair.
 */
fun ChannelLayout.speakers(): List<SpeakerChannel> {
    val front = listOf(
        SpeakerChannel("L", -30f), SpeakerChannel("R", 30f), SpeakerChannel("C", 0f),
        SpeakerChannel("LFE", 0f, isLfe = true),
    )
    val surr5 = listOf(SpeakerChannel("Ls", -110f), SpeakerChannel("Rs", 110f))
    val surr7 = listOf(
        SpeakerChannel("Lrs", -150f), SpeakerChannel("Rrs", 150f),
        SpeakerChannel("Lss", -90f), SpeakerChannel("Rss", 90f),
    )
    val topQuad = listOf(
        SpeakerChannel("Ltf", -45f, 45f), SpeakerChannel("Rtf", 45f, 45f),
        SpeakerChannel("Ltr", -135f, 45f), SpeakerChannel("Rtr", 135f, 45f),
    )
    // x.1.2's single top pair sits overhead ("top middle"); 9.1.6's is the
    // middle of three rows, level with the others.
    val topMiddle = listOf(SpeakerChannel("Ltm", -90f, 60f), SpeakerChannel("Rtm", 90f, 60f))
    val topSides = listOf(SpeakerChannel("Lts", -90f, 45f), SpeakerChannel("Rts", 90f, 45f))
    val wides = listOf(SpeakerChannel("Lw", -60f), SpeakerChannel("Rw", 60f))
    return when (this) {
        ChannelLayout.STEREO -> listOf(SpeakerChannel("L", -30f), SpeakerChannel("R", 30f))
        ChannelLayout.SURROUND_5_1 -> front + surr5
        ChannelLayout.SURROUND_7_1 -> front + surr7
        ChannelLayout.SURROUND_5_1_2 -> front + surr5 + topMiddle
        ChannelLayout.SURROUND_5_1_4 -> front + surr5 + topQuad
        ChannelLayout.SURROUND_7_1_2 -> front + surr7 + topMiddle
        ChannelLayout.ATMOS_7_1_4 -> front + surr7 + topQuad
        ChannelLayout.ATMOS_9_1_4 -> front + surr7 + topQuad + wides
        ChannelLayout.ATMOS_9_1_6 -> front + surr7 + topQuad + topSides + wides
    }
}

private val DOLBY_ATMOS_REGEX = Regex("""dolby\s*atmos""", RegexOption.IGNORE_CASE)

/**
 * True for the Dolby codecs that *can* carry Atmos: the E-AC-3 (Dolby Digital
 * Plus) family, whose JOC side-data this app's renderer decodes, and AC-4.
 * Neither alone means a track is Atmos (plain DD+ 5.1 is also EC-3, and AC-4 is
 * often stereo); use [isDolbyAtmos] for that. Matches on the codec name, MIME
 * type, or file extension.
 */
fun isAtmosCapableCodec(
    codec: String? = null,
    mimeType: String? = null,
    fileExtension: String? = null,
): Boolean = isEac3Codec(codec, mimeType, fileExtension) || isAc4Codec(codec, mimeType, fileExtension)

/** E-AC-3 (Dolby Digital Plus, incl. Atmos JOC) by codec name, MIME or extension. */
fun isEac3Codec(
    codec: String? = null,
    mimeType: String? = null,
    fileExtension: String? = null,
): Boolean {
    val codecHit = codec?.lowercase()?.let {
        it.contains("ec-3") || it.contains("ec3") || it.contains("e-ac-3") ||
            it.contains("eac3")
    } ?: false
    val mimeHit = mimeType?.lowercase()?.let {
        it.contains("eac3") || it.contains("ec-3") || it.contains("ec3")
    } ?: false
    val extHit = fileExtension?.trimStart('.')?.lowercase()?.let {
        it == "ec3" || it == "eac3"
    } ?: false
    return codecHit || mimeHit || extHit
}

/**
 * Dolby AC-4 (ETSI TS 103 190) by codec name ("ac-4", MP4 sample entry
 * "ac-4"), MIME ("audio/ac4") or extension. There is no software AC-4 decoder
 * in the bundled FFmpeg (upstream has none), so AC-4 plays through the
 * platform decoder where the device has one, or as a passthrough bitstream to
 * an HDMI receiver that decodes it; the object renderer never sees AC-4.
 */
fun isAc4Codec(
    codec: String? = null,
    mimeType: String? = null,
    fileExtension: String? = null,
): Boolean {
    val codecHit = codec?.lowercase()?.let { it.contains("ac-4") || it == "ac4" } ?: false
    val mimeHit = mimeType?.lowercase()?.let { it.contains("ac4") || it.contains("ac-4") } ?: false
    val extHit = fileExtension?.trimStart('.')?.lowercase()?.let { it == "ac4" } ?: false
    return codecHit || mimeHit || extHit
}

/**
 * Detects Dolby Atmos content. Detection is authoritative only from the JOC
 * extension flag the native demux sets after walking the bitstream
 * ([hasJocExtension]); when that is unknown (null) this falls back to
 * best-effort signals — an Atmos/JOC MIME hint or a "Dolby Atmos" phrase in the
 * track/album text — mirroring how [isThxSpatialAudio] reads release metadata.
 *
 * Note that an EC-3 codec alone is *not* treated as Atmos here: it is only
 * Atmos-*capable* (see [isAtmosCapableCodec]); the JOC side-data is what makes a
 * stream Atmos, and only the native demux can confirm it.
 */
fun isDolbyAtmos(
    hasJocExtension: Boolean? = null,
    mimeType: String? = null,
    title: String? = null,
    version: String? = null,
    albumTitle: String? = null,
    albumVersion: String? = null,
): Boolean {
    // Authoritative signal from the native demux wins outright.
    if (hasJocExtension != null) return hasJocExtension

    val mimeHit = mimeType?.lowercase()?.let {
        it.contains("joc") || it.contains("atmos")
    } ?: false
    if (mimeHit) return true

    return listOfNotNull(version, title, albumVersion, albumTitle)
        .any { DOLBY_ATMOS_REGEX.containsMatchIn(it) }
}
