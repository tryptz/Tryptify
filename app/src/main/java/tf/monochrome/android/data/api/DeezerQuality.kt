package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.AudioQuality

/**
 * The qualities `/api/deezer/download` serves, and the codec behind each one.
 *
 * The endpoint takes the same `quality` codes as Qobuz's `/api/download-music`
 * (`5`, `6`, `7`, `27`). On a TrypT HiFi server since 2026-10-30 they mean
 * Deezer's own formats: `27` and `7` its hi-res FLAC (`FLAC_24`, 24-bit at the
 * rate of the track's master, up to 192 kHz), `6` CD FLAC and `5` MP3 128.
 * No code means MP3 320, so it is asked for by its Deezer name, `MP3_320`,
 * which a server takes since the same day's later change; an older one
 * refuses the name, and the request falls to MP3 128 ([ladder]).
 *
 * The tiers mirror Deezer's own picker: HiFi (FLAC, up to 24-bit/192 kHz),
 * High Quality (MP3 320) and Standard (MP3 128).
 *
 * Deezer keeps one hi-res file per track, and only tracks with a hi-res
 * master have one. The server asks Deezer for a list, best first, so a
 * hi-res request for any other track comes back as CD FLAC rather than
 * failing: Hi-Res is asked for every track, as the catalogue never says which
 * have it (every item reports `hires: false`). A server from before then
 * serves CD FLAC for `27`, so the same request is right on both.
 *
 * Kept free of Android types so it can be unit tested.
 */
object DeezerQuality {

    enum class Tier(
        /** The `quality` query value the endpoint accepts. */
        val code: String,
        val codec: String,
        val bitDepth: Int?,
        /** The highest rate it arrives at; a hi-res file is at its master's rate. */
        val sampleRateKhz: Double,
        val bitrateKbps: Int?,
        val label: String,
    ) {
        MP3_128("5", "MP3", null, 44.1, 128, "MP3 128 kbps"),
        MP3_320("MP3_320", "MP3", null, 44.1, 320, "MP3 320 kbps"),
        FLAC_CD("6", "FLAC", 16, 44.1, null, "FLAC 16-bit / 44.1 kHz"),
        FLAC_HI_RES("27", "FLAC", 24, 192.0, null, "FLAC 24-bit / up to 192 kHz"),
    }

    /**
     * The tier to request for the listener's [quality] setting, as Deezer's
     * picker names them: Hi-Res is HiFi (falling back to CD FLAC on the
     * server), High is High Quality, Low is Standard.
     */
    fun tierFor(quality: AudioQuality): Tier = when (quality) {
        AudioQuality.LOW -> Tier.MP3_128
        AudioQuality.HIGH -> Tier.MP3_320
        AudioQuality.LOSSLESS -> Tier.FLAC_CD
        AudioQuality.HI_RES -> Tier.FLAC_HI_RES
    }

    /**
     * The tiers to try for [tier], in order: itself, then the lossy ones
     * below it. A server refuses a tier its Deezer account cannot serve, and
     * one from before format names refuses `MP3_320`.
     */
    fun ladder(tier: Tier): List<Tier> = when (tier) {
        Tier.FLAC_HI_RES, Tier.FLAC_CD -> listOf(tier, Tier.MP3_320, Tier.MP3_128)
        Tier.MP3_320 -> listOf(Tier.MP3_320, Tier.MP3_128)
        Tier.MP3_128 -> listOf(Tier.MP3_128)
    }

    /** The app's quality badge for a track item's catalogue flags. */
    fun badgeFor(hires: Boolean, maximumBitDepth: Int?): String? = when {
        hires -> "HI_RES_LOSSLESS"
        (maximumBitDepth ?: 0) >= 16 -> "LOSSLESS"
        else -> null
    }
}
