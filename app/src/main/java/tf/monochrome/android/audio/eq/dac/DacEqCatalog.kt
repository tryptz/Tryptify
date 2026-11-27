package tf.monochrome.android.audio.eq.dac

import io.github.chronosauros.contour.core.DeviceTarget

/**
 * The DACs the DAC EQ page knows: Contour's device records
 * (third_party/contour/devices/<id>.json), in the order the page lists them.
 * Contour keeps its own list to itself, so the ids are repeated here;
 * `DacEqCatalogTest` fails when a record is added there and not here.
 */
object DacEqCatalog {

    val RECORDS: List<String> = listOf(
        "crinear-protocol-micro",
        "crinear-protocol-max",
        "epz-tp65",
        "fiio-ka15",
        "fiio-k13-r2r",
        "fiio-ja11",
        "fiio-jiezi",
        "jcally-jm12",
        "kiwi-ears-ad1",
        "kiwi-ears-allegro-mini",
        "moondrop-dawn-pro-2",
        "moondrop-dusk",
        "moondrop-echo-a",
        "snowsky-melody",
        "snowsky-retro-nano",
        "snowsky-tiny-a",
        "tanchjim-space-ii",
        "trn-black-pearl",
    )

    val all: List<DeviceTarget> by lazy { RECORDS.mapNotNull { DeviceTarget.ofRecord(it) } }
}
