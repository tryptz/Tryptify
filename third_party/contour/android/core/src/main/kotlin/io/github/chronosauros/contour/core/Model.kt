package io.github.chronosauros.contour.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Filter shapes the editor knows. Which of them a device accepts is in its [DeviceCapabilities]. Stored by name, so
 * new shapes are only ever appended.
 */
@Serializable
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS, ALL_PASS }

/** LOW PASS, HIGH PASS, BAND PASS, ALL PASS: shapes with no gain of their own (Contour keeps their [Band.gainDb] at 0). */
val FilterType.isPass: Boolean
    get() = this == FilterType.LOW_PASS || this == FilterType.HIGH_PASS ||
        this == FilterType.BAND_PASS || this == FilterType.ALL_PASS

/** LOW SHELF and HIGH SHELF: the shapes whose Q is a slope and, on some DACs, is scaled or compensated. */
val FilterType.isShelf: Boolean
    get() = this == FilterType.LOW_SHELF || this == FilterType.HIGH_SHELF

/**
 * One EQ band as the user means it (the intended filter, before any device compensation).
 * [gainDb] is ignored by the pass filters (low, high, band and all pass; Contour stores 0 for them).
 */
@Serializable
data class Band(
    val id: String,
    val type: FilterType,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
    val enabled: Boolean = true,
)

/**
 * A named profile in the library. [preampDb] = null means auto (see [Preamp.auto]).
 * Times are epoch milliseconds. [archived] is written only when true, so an active profile's file keeps
 * the pre-v1 bytes (older files without the field load as active).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val icon: String = "",
    val bands: List<Band>,
    val preampDb: Double? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val archived: Boolean = false,
) {
    /** The preamp that goes with this profile: the stored value, or the auto value when none is stored. AUTO draws the shelves in [form], the one the DAC plays ([ShelfForm]). */
    fun effectivePreampDb(form: ShelfForm): Double = preampDb ?: Preamp.auto(bands, form).toDouble()
}
