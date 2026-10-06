package tf.monochrome.android.data.api

import tf.monochrome.android.domain.model.AudioQuality
import kotlin.math.abs

/**
 * What each service's quality settings offer, in that service's own terms.
 *
 * TIDAL, Qobuz and Deezer each stream and download in a setting of their own,
 * because one tier means different files on each: "High" is AAC 320 in an
 * .m4a on TIDAL and MP3 320 on Qobuz and Deezer, and Deezer has nothing above
 * CD quality. So every option names the codec and what arrives, and a service
 * only offers the tiers it can actually send. Apple has its own ladder
 * ([tf.monochrome.android.data.preferences.AppleQuality]) and is not here.
 *
 * Kept free of Android types so it can be unit tested.
 */
object ServiceQuality {

    /** The services with a quality ladder here, in the order settings shows them. */
    val services: List<ApiService> = listOf(ApiService.TIDAL, ApiService.QOBUZ, ApiService.DEEZER)

    /** One of a service's quality settings. */
    enum class Setting { WIFI, CELLULAR, DOWNLOAD }

    /** One choice in a service's quality picker. */
    data class Option(
        val quality: AudioQuality,
        /** The codec and tier, e.g. "AAC 320 kbps". */
        val label: String,
        /** What arrives: bit depth and sample rate, or what the codec means. */
        val detail: String,
    )

    private val TIDAL_HI_RES = Option(
        AudioQuality.HI_RES, "Hi-Res FLAC",
        "Up to 24-bit / 192 kHz. CD quality when the album has no hi-res master.",
    )
    private val QOBUZ_HI_RES = Option(
        AudioQuality.HI_RES, "Hi-Res FLAC",
        "24-bit, up to 192 kHz. CD quality when the release has no hi-res master.",
    )
    private val CD_FLAC = Option(AudioQuality.LOSSLESS, "CD FLAC", "16-bit / 44.1 kHz, lossless.")
    private val DEEZER_CD_FLAC = Option(
        AudioQuality.LOSSLESS, "CD FLAC",
        "16-bit / 44.1 kHz, lossless. Deezer's best: it has no hi-res.",
    )
    private val AAC_320 = Option(AudioQuality.HIGH, "AAC 320 kbps", "Lossy AAC in an .m4a (MP4) file.")
    private val AAC_96 = Option(
        AudioQuality.LOW, "AAC 96 kbps",
        "Lossy AAC, smallest. For slow or metered connections.",
    )
    private val MP3_320 = Option(AudioQuality.HIGH, "MP3 320 kbps", "Lossy MP3.")

    /**
     * What [service] offers for [setting], best first. TIDAL downloads stop at
     * AAC 320: a TrypT HiFi server's download route answers any lossy request
     * with it, so AAC 96 would be a choice that is never honoured.
     */
    fun options(service: ApiService, setting: Setting): List<Option> = when (service) {
        ApiService.TIDAL ->
            if (setting == Setting.DOWNLOAD) listOf(TIDAL_HI_RES, CD_FLAC, AAC_320)
            else listOf(TIDAL_HI_RES, CD_FLAC, AAC_320, AAC_96)
        ApiService.QOBUZ -> listOf(QOBUZ_HI_RES, CD_FLAC, MP3_320)
        ApiService.DEEZER -> listOf(DEEZER_CD_FLAC, MP3_320)
        ApiService.APPLE -> emptyList()
    }

    /**
     * [quality] as [service] can honour it for [setting]: itself when offered,
     * else the nearest offered tier (the higher one on a tie). A value carried
     * over from the old single setting, or from another service, lands here.
     */
    fun coerce(service: ApiService, setting: Setting, quality: AudioQuality): AudioQuality {
        val offered = options(service, setting).map { it.quality }
        if (offered.isEmpty() || quality in offered) return quality
        return offered.sortedWith(
            compareBy<AudioQuality>({ abs(it.ordinal - quality.ordinal) }, { -it.ordinal })
        ).first()
    }

    /** The option [quality] shows as for [service], after [coerce]; null for Apple. */
    fun option(service: ApiService, setting: Setting, quality: AudioQuality): Option? {
        val honoured = coerce(service, setting, quality)
        return options(service, setting).firstOrNull { it.quality == honoured }
    }

    /** The tier a setting starts at when neither it nor the old setting was ever chosen. */
    fun default(setting: Setting): AudioQuality = when (setting) {
        Setting.WIFI, Setting.DOWNLOAD -> AudioQuality.HI_RES
        Setting.CELLULAR -> AudioQuality.HIGH
    }
}
