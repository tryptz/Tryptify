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
 * Loudspeaker / output layout the renderer targets. Channel counts match the
 * bed layouts implemented by the native VBAP panner (`cpp/atmos/vbap.h`).
 */
enum class ChannelLayout(val channelCount: Int, val label: String) {
    STEREO(2, "2.0"),
    SURROUND_5_1(6, "5.1"),
    SURROUND_7_1(8, "7.1"),
    ATMOS_7_1_4(12, "7.1.4");

    companion object {
        /**
         * Best layout for a raw channel count. Falls back to stereo for
         * mono/unknown and to the nearest known layout otherwise.
         */
        fun fromChannelCount(channels: Int?): ChannelLayout = when {
            channels == null || channels <= 2 -> STEREO
            channels <= 6 -> SURROUND_5_1
            channels <= 8 -> SURROUND_7_1
            else -> ATMOS_7_1_4
        }
    }
}

/**
 * How the renderer folds a multichannel / object mix down to two channels when
 * the output is stereo (headphones or a 2.0 DAC).
 */
enum class StereoDownmixMode(val displayName: String, val description: String) {
    /** Spatialize objects to headphone stereo through the HRTF binauralizer. */
    BINAURAL("Binaural", "Spatialize objects to headphone stereo via HRTF"),

    /** Plain ITU stereo fold-down (Lo/Ro) — no surround matrix encoding. */
    LO_RO("Stereo (Lo/Ro)", "Standard stereo fold-down, no surround encoding"),

    /** Matrix-encoded (Lt/Rt) so a Pro Logic decoder can re-expand to surround. */
    LT_RT("Surround (Lt/Rt)", "Matrix-encoded for Pro Logic re-expansion");

    companion object {
        val DEFAULT = BINAURAL
    }
}

/**
 * Dynamic Range Control profile applied to the rendered output — the DD+/Atmos
 * `compr`/`dynrng` scale, from full range (Off) to the most compressed (Heavy).
 */
enum class DrcMode(val displayName: String) {
    OFF("Off (full range)"),
    LIGHT("Light"),
    STANDARD("Standard"),
    HEAVY("Heavy (night)");

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
    /** Take the channel count from the connected DAC instead of [layout]. */
    val autoDetectLayout: Boolean = true,
    /** Target loudspeaker layout when not auto-detecting. */
    val layout: ChannelLayout = ChannelLayout.STEREO,
    /** Fold-down used when the effective output is stereo. */
    val stereoDownmix: StereoDownmixMode = StereoDownmixMode.DEFAULT,
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
        )
    }

    /** The layout the renderer will actually target given the auto-detect flag. */
    fun effectiveLayout(dacChannelCount: Int?): ChannelLayout =
        if (autoDetectLayout) ChannelLayout.fromChannelCount(dacChannelCount) else layout

    companion object {
        val DEFAULT = RendererProfile()
    }
}

/**
 * One loudspeaker position in a bed layout, used to draw the channel map. Angles
 * mirror the native VBAP layout in `cpp/atmos/vbap.h`: azimuth 0 = front, growing
 * clockwise (+ = right); elevation up from the horizontal plane.
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
 * The speakers that make up a layout, in channel order. The count matches
 * [ChannelLayout.channelCount], and the set matches the native bed layouts.
 */
fun ChannelLayout.speakers(): List<SpeakerChannel> = when (this) {
    ChannelLayout.STEREO -> listOf(
        SpeakerChannel("L", -30f), SpeakerChannel("R", 30f),
    )
    ChannelLayout.SURROUND_5_1 -> listOf(
        SpeakerChannel("L", -30f), SpeakerChannel("R", 30f), SpeakerChannel("C", 0f),
        SpeakerChannel("LFE", 0f, isLfe = true),
        SpeakerChannel("Ls", -110f), SpeakerChannel("Rs", 110f),
    )
    ChannelLayout.SURROUND_7_1 -> listOf(
        SpeakerChannel("L", -30f), SpeakerChannel("R", 30f), SpeakerChannel("C", 0f),
        SpeakerChannel("LFE", 0f, isLfe = true),
        SpeakerChannel("Lss", -90f), SpeakerChannel("Rss", 90f),
        SpeakerChannel("Lrs", -150f), SpeakerChannel("Rrs", 150f),
    )
    ChannelLayout.ATMOS_7_1_4 -> listOf(
        SpeakerChannel("L", -30f), SpeakerChannel("R", 30f), SpeakerChannel("C", 0f),
        SpeakerChannel("LFE", 0f, isLfe = true),
        SpeakerChannel("Lss", -90f), SpeakerChannel("Rss", 90f),
        SpeakerChannel("Lrs", -150f), SpeakerChannel("Rrs", 150f),
        SpeakerChannel("Ltf", -45f, 45f), SpeakerChannel("Rtf", 45f, 45f),
        SpeakerChannel("Ltr", -135f, 45f), SpeakerChannel("Rtr", 135f, 45f),
    )
}

private val DOLBY_ATMOS_REGEX = Regex("""dolby\s*atmos""", RegexOption.IGNORE_CASE)

/**
 * True for the E-AC-3 (Dolby Digital Plus) family — the container that *can*
 * carry Atmos JOC side-data. Being EC-3 does not by itself mean a track is
 * Atmos (plain DD+ 5.1 is also EC-3); use [isDolbyAtmos] for that. Matches on
 * the codec name, MIME type, or file extension.
 */
fun isAtmosCapableCodec(
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
