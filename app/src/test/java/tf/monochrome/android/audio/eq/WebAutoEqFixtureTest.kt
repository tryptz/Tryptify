package tf.monochrome.android.audio.eq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The website's AutoEQ try-out (public/js/autoeq-engine.js in the
 * tryptz/tryptify.com repository) is a port of this engine. This test holds
 * the engine to the answers the port is checked against: the fixtures here
 * (src/test/resources/web-autoeq/) and their copy in that repository's tests/,
 * which its tests/engine-parity.mjs reads. Change the engine and it fails
 * here, so the port is updated with it.
 *
 * After a deliberate engine change, rewrite the fixtures, copy them to the
 * website and port the change there:
 *   TRYPTIFY_WRITE_WEB_FIXTURE=1 ./gradlew :app:testDebugUnitTest --tests '*WebAutoEqFixtureTest*'
 *   cp app/src/test/resources/web-autoeq/autoeq-engine-fixtures.json ../tryptify.com/tests/
 *   node ../tryptify.com/tests/engine-parity.mjs
 */
class WebAutoEqFixtureTest {

    private val site = File("src/test/resources/web-autoeq")
    private val targets = File("src/main/assets/autoeq/targets")

    private val cases = listOf(
        Triple("truthear-nova.txt", "SEAP.txt", 0f),
        Triple("sennheiser-hd600.csv", "Harman_OE_2018.txt", 0f),
        Triple("sennheiser-hd600.csv", "Harman_OE_2018.txt", 10f),
    )

    private fun compute(): JsonObject = buildJsonObject {
        put("cases", buildJsonArray {
            for ((input, target, smoothing) in cases) {
                val measurement = EqDataParser.parseRawData(File(site, "inputs/$input").readText())
                val targetCurve = EqDataParser.parseRawData(File(targets, target).readText())
                for (bands in listOf(5, 10, 31)) for (maxF in listOf(10000f, 16000f, 20000f)) for (alg in AutoEqAlgorithm.entries) {
                    val fit = AutoEqEngine.runAutoEqAlgorithm(
                        measurement = AutoEqEngine.smoothCurve(measurement, smoothing),
                        target = targetCurve,
                        bandCount = bands,
                        maxFrequency = maxF,
                        sampleRate = 48000f,
                        algorithm = alg,
                    )
                    add(buildJsonObject {
                        put("input", input); put("target", target); put("smoothing", smoothing)
                        put("points", measurement.size)
                        put("bandCount", bands); put("maxFrequency", maxF); put("algorithm", alg.name)
                        put("preamp", AutoPreamp.peakBoostDb(fit, 48000f))
                        put("bands", JsonArray(fit.map {
                            buildJsonArray { add(JsonPrimitive(it.type.name)); add(JsonPrimitive(it.freq)); add(JsonPrimitive(it.gain)); add(JsonPrimitive(it.q)) }
                        }))
                    })
                }
            }
        })
    }

    @Test
    fun `the website's engine is checked against what this engine answers now`() {
        val json = Json { prettyPrint = false }
        val now = json.encodeToString(JsonObject.serializer(), compute())
        val file = File(site, "autoeq-engine-fixtures.json")
        if (System.getenv("TRYPTIFY_WRITE_WEB_FIXTURE") == "1") file.writeText(now + "\n")
        assertEquals(
            "AutoEqEngine changed: rewrite the fixtures, copy them to tryptify.com's tests/ and port the change to its public/js/autoeq-engine.js (see this test's comment)",
            file.readText().trim(), now,
        )
    }
}
