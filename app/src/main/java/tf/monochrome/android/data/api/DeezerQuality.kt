package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.AudioQuality

/**
 * The qualities `/api/deezer/download` serves, and the codec behind each one.
 *
 * The endpoint takes the same `quality` codes as Qobuz's `/api/download-music`
 * — it rejects anything but `5`, `6`, `7` and `27` — but Deezer's own catalogue
 * is narrower than Qobuz's: every track item reports
 * `maximum_bit_depth: 16`, `maximum_sampling_rate: 44.1` and `hires: false`.
 * So the top two codes are accepted and land on CD-quality FLAC at best; they
 * are only asked for when a track item claims hi-res, which keeps a request
 * from being spent on a tier that cannot exist.
 *
 * Kept free of Android types so it can be unit tested.
 */
object DeezerQuality {

    enum class Tier(
        /** The `quality` query value the endpoint accepts. */
        val code: String,
        val codec: String,
        val bitDepth: Int?,
        val sampleRateKhz: Double,
        val bitrateKbps: Int?,
        val label: String,
    ) {
        MP3_320("5", "MP3", null, 44.1, 320, "MP3 320 kbps"),
        FLAC_CD("6", "FLAC", 16, 44.1, null, "FLAC 16-bit / 44.1 kHz"),
        FLAC_24_96("7", "FLAC", 24, 96.0, null, "FLAC 24-bit / 96 kHz"),
        FLAC_HI_RES("27", "FLAC", 24, 192.0, null, "FLAC 24-bit / up to 192 kHz"),
    }

    /** The tiers a track can actually be served in, given its catalogue flags. */
    fun available(hires: Boolean): List<Tier> =
        if (hires) Tier.entries else listOf(Tier.MP3_320, Tier.FLAC_CD)

    /**
     * The tier to request for the listener's [quality] setting. Lossy settings
     * get MP3 320 — the lowest tier the endpoint offers. Hi-Res asks for the
     * top code only when the track claims it, else CD FLAC.
     */
    fun tierFor(quality: AudioQuality, hires: Boolean = false): Tier = when (quality) {
        AudioQuality.LOW, AudioQuality.HIGH -> Tier.MP3_320
        AudioQuality.LOSSLESS -> Tier.FLAC_CD
        AudioQuality.HI_RES -> if (hires) Tier.FLAC_HI_RES else Tier.FLAC_CD
    }

    /** The app's quality badge for a track item's catalogue flags. */
    fun badgeFor(hires: Boolean, maximumBitDepth: Int?): String? = when {
        hires -> "HI_RES_LOSSLESS"
        (maximumBitDepth ?: 0) >= 16 -> "LOSSLESS"
        else -> null
    }
}
