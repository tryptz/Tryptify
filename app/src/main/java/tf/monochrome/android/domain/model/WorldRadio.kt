package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * A city on the globe, and how much radio comes out of it.
 *
 * A city is in this dataset only because live stations were found naming it at
 * build time (`tools/fetch_world_radio.py`). That is the whole claim a dot on the
 * globe makes: you can tune into something here. Cities nobody broadcasts from
 * are absent rather than drawn empty.
 *
 * [lat] and [lon] are stored as hundredths of a degree — see [WorldRadioData.scale]
 * — because at 0.01° the error is under a screen pixel at any zoom the globe
 * offers, and integers halve the asset.
 *
 * [stations] is the count measured at build time. It sizes the dot and nothing
 * else: what a listener actually gets is fetched live when they tap, because
 * stream URLs rot far faster than the app ships.
 */
@Serializable
data class RadioCity(
    val id: String = "",
    val name: String = "",
    /** ASCII spelling, for matching and for fonts that can't render the local one. */
    val ascii: String = "",
    /** ISO 3166-1 alpha-2. */
    val country: String = "",
    val lat: Int = 0,
    val lon: Int = 0,
    val population: Int = 0,
    val stations: Int = 0,
)

/**
 * The bundled globe: where the land is, and where the radio is.
 *
 * [coastline] is Natural Earth's 110m coastline as flat `[lon, lat, lon, lat, …]`
 * runs at the same [scale] as the cities — one convention for the renderer, not
 * two. Flat arrays rather than a list of points because 5,127 two-element objects
 * is a great deal of JSON to describe a line.
 */
@Serializable
data class WorldRadioData(
    val version: Int = 1,
    val attribution: String = "",
    val license: String = "",
    /** Stored units per degree. 100 means coordinates are hundredths of a degree. */
    val scale: Int = 100,
    val coastline: List<List<Int>> = emptyList(),
    /**
     * Country borders, in the same flat form and at the same [scale].
     *
     * Natural Earth's 110m land boundaries — the same generalisation pass the
     * coastline comes from, so the two agree along a coast instead of
     * disagreeing by a pixel. Land only: no maritime lines.
     */
    val borders: List<List<Int>> = emptyList(),
    val cities: List<RadioCity> = emptyList(),
)

/**
 * One live station, as radio-browser currently describes it.
 *
 * Fetched at play time, never bundled: a URL that worked when the release was cut
 * is not a URL that works today, and a station list in the APK would be a
 * promise the app can't keep.
 */
@Serializable
data class RadioStation(
    val uuid: String = "",
    val name: String = "",
    val url: String = "",
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String = "",
    val codec: String? = null,
    val bitrate: Int = 0,
    val votes: Int = 0,
    val isHls: Boolean = false,
    val countryCode: String = "",
) {
    /** A short "AAC · 128k" for the row, or null when the station says nothing. */
    val qualityLabel: String?
        get() = listOfNull(
            codec?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", ignoreCase = true) },
            bitrate.takeIf { it > 0 }?.let { "${it}k" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** The first few tags, tidied, for the row's subtitle. */
    fun topTags(limit: Int = 3): List<String> =
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(limit)

    private fun listOfNull(vararg items: String?): List<String> = items.filterNotNull()
}
