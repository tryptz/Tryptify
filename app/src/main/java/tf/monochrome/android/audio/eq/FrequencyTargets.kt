package tf.monochrome.android.audio.eq

import android.content.Context
import tf.monochrome.android.domain.model.EqTarget
import tf.monochrome.android.domain.model.FrequencyPoint

/**
 * Predefined target frequency response curves for equalization.
 *
 * High-resolution target data (384 points per curve, 20Hz-20kHz) loaded dynamically
 * from the autoeq/targets assets directory.
 */
@Suppress("StaticFieldLeak") // Stores applicationContext only — no leak
object FrequencyTargets {

    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    private fun loadTargetFromAssets(filename: String): List<FrequencyPoint> {
        val ctx = context ?: return emptyList()
        return try {
            val raw = ctx.assets.open("autoeq/targets/$filename").bufferedReader().use { it.readText() }
            EqDataParser.parseRawData(raw)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ========== Lazy-parsed targets ==========

    private val harmanOE2018 by lazy { loadTargetFromAssets("Harman_OE_2018.txt") }
    private val harmanIE2019 by lazy { loadTargetFromAssets("Harman_IE_2019.txt") }
    private val diffuseField by lazy { loadTargetFromAssets("Diffuse_Field.txt") }
    private val knowles by lazy { loadTargetFromAssets("Knowles.txt") }
    private val moondropVdsf by lazy { loadTargetFromAssets("Moondrop_VDSF.txt") }
    private val hifiEndgame2026 by lazy { loadTargetFromAssets("HiFiEndgame_2026.txt") }
    private val peqdbUltra by lazy { loadTargetFromAssets("PEQdB_Ultra.txt") }
    private val seapTarget by lazy { loadTargetFromAssets("SEAP.txt") }
    private val seapBass by lazy { loadTargetFromAssets("SEAP_Bass.txt") }
    private val flatLine by lazy { loadTargetFromAssets("Flat_Line.txt") }

    fun getHarmanOverEar2018(): EqTarget = EqTarget(
        id = "harman_oe_2018",
        label = "Harman Over-Ear 2018",
        data = harmanOE2018,
        filename = "Harman_OE_2018.txt"
    )

    fun getHarmanInEar2019(): EqTarget = EqTarget(
        id = "harman_ie_2019",
        label = "Harman In-Ear 2019",
        data = harmanIE2019,
        filename = "Harman_IE_2019.txt"
    )

    fun getDiffuseField(): EqTarget = EqTarget(
        id = "diffuse_field",
        label = "Diffuse Field",
        data = diffuseField,
        filename = "Diffuse_Field.txt"
    )

    fun getKnowles(): EqTarget = EqTarget(
        id = "knowles",
        label = "Knowles",
        data = knowles,
        filename = "Knowles.txt"
    )

    fun getMoondropVdsf(): EqTarget = EqTarget(
        id = "moondrop",
        label = "Moondrop VDSF",
        data = moondropVdsf,
        filename = "Moondrop_VDSF.txt"
    )

    fun getHiFiEndgame2026(): EqTarget = EqTarget(
        id = "hifi_endgame",
        label = "Hi-Fi Endgame 2026",
        data = hifiEndgame2026,
        filename = "HiFiEndgame_2026.txt"
    )

    fun getPeqdbUltra(): EqTarget = EqTarget(
        id = "peqdb_ultra",
        label = "PEQdB Ultra",
        data = peqdbUltra,
        filename = "PEQdB_Ultra.txt"
    )

    fun getSeapTarget(): EqTarget = EqTarget(
        id = "seap",
        label = "Seap Target",
        data = seapTarget,
        filename = "SEAP.txt"
    )

    fun getSeapBass(): EqTarget = EqTarget(
        id = "seap_bass",
        label = "Seap Bass Boost",
        data = seapBass,
        filename = "SEAP_Bass.txt"
    )

    fun getFlat(): EqTarget = EqTarget(
        id = "flat",
        label = "Flat (Calibration)",
        data = flatLine,
        filename = "Flat_Line.txt"
    )

    fun getAllTargets(): List<EqTarget> = listOf(
        getHarmanOverEar2018(),
        getHarmanInEar2019(),
        getDiffuseField(),
        getKnowles(),
        getMoondropVdsf(),
        getHiFiEndgame2026(),
        getPeqdbUltra(),
        getSeapTarget(),
        getSeapBass(),
        getFlat()
    )

    fun getTargetById(id: String): EqTarget? = getAllTargets().find { it.id == id }
}
