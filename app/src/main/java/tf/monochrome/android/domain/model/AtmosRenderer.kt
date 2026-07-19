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
 * A renderer configuration: mode, target layout, and an optional HRTF profile id
 * (a key into the AutoEQ/HRTF measurement store) used by the binaural back-end.
 * Serializable so it can be persisted with the rest of the settings.
 */
@Serializable
data class RendererProfile(
    val mode: RendererMode = RendererMode.DEFAULT,
    val layout: ChannelLayout = ChannelLayout.STEREO,
    val hrtfProfileId: String? = null,
) {
    companion object {
        val DEFAULT = RendererProfile()
    }
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
