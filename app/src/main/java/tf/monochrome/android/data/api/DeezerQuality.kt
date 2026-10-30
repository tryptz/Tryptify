package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.AudioQuality

/**
 * The qualities `/api/deezer/download` serves, and the codec behind each one.
 *
 * The endpoint takes the same `quality` codes as Qobuz's `/api/download-music`
 * and rejects anything but `5`, `6`, `7` and `27`. On a TrypT HiFi server
 * since 2026-10-30 they mean Deezer's own formats: `27` and `7` its hi-res
 * FLAC (`FLAC_24`, 24-bit at the rate of the track's master, up to 192 kHz),
 * `6` CD FLAC and `5` MP3 128. There is no code for MP3 320 any more.
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
        FLAC_CD("6", "FLAC", 16, 44.1, null, "FLAC 16-bit / 44.1 kHz"),
        FLAC_HI_RES("27", "FLAC", 24, 192.0, null, "FLAC 24-bit / up to 192 kHz"),
    }

    /**
     * The tier to request for the listener's [quality] setting. Lossy settings
     * get MP3 128, the only lossy file the endpoint still offers; Hi-Res asks
     * for the hi-res file, which falls back to CD FLAC on the server.
     */
    fun tierFor(quality: AudioQuality): Tier = when (quality) {
        AudioQuality.LOW, AudioQuality.HIGH -> Tier.MP3_128
        AudioQuality.LOSSLESS -> Tier.FLAC_CD
        AudioQuality.HI_RES -> Tier.FLAC_HI_RES
    }

    /** The app's quality badge for a track item's catalogue flags. */
    fun badgeFor(hires: Boolean, maximumBitDepth: Int?): String? = when {
        hires -> "HI_RES_LOSSLESS"
        (maximumBitDepth ?: 0) >= 16 -> "LOSSLESS"
        else -> null
    }
}
