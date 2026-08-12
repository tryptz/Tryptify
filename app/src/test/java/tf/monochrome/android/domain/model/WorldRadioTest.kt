package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The globe, checked against the **real bundled asset**.
 *
 * The thing worth guarding is not that the model parses — it is that every dot
 * the app will draw is a place that exists and that you can actually tune into.
 * A city with no stations, or one at coordinates off the sphere, is not a
 * cosmetic defect: it is the map making a claim it cannot keep, and neither
 * would ever be caught by eye in a 1,600-entry JSON file.
 */
class WorldRadioTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val assets = File("src/main/assets")

    private val globe: WorldRadioData by lazy {
        json.decodeFromString(
            WorldRadioData.serializer(),
            File(assets, "world_radio.json").readText(),
        )
    }

    @Test
    fun `the bundled asset parses and covers the world`() {
        assertTrue("globe looks empty: ${globe.cities.size}", globe.cities.size > 1_000)
        val countries = globe.cities.map { it.country }.distinct()
        assertTrue("only ${countries.size} countries", countries.size >= 100)
        assertTrue(
            "GeoNames is CC BY 4.0 — the credit ships with the data",
            globe.attribution.isNotBlank() && globe.license.isNotBlank(),
        )
    }

    @Test
    fun `every city is somewhere real`() {
        val scale = globe.scale
        assertTrue("scale must be positive", scale > 0)
        val offSphere = globe.cities.filter {
            it.lat / scale.toDouble() !in -90.0..90.0 ||
                it.lon / scale.toDouble() !in -180.0..180.0
        }.map { it.id }
        assertTrue("cities off the sphere: $offSphere", offSphere.isEmpty())

        val nameless = globe.cities.filter { it.name.isBlank() || it.country.isBlank() }
        assertTrue("cities with no name or country: ${nameless.map { it.id }}", nameless.isEmpty())
    }

    @Test
    fun `every dot is a city you can actually tune into`() {
        // The whole promise of the globe. A city reaches the asset only because
        // live stations were found naming it, so a zero here means the build
        // pass leaked a candidate it had not verified.
        val silent = globe.cities.filter { it.stations < 1 }.map { it.id }
        assertTrue("cities on the globe with no stations: $silent", silent.isEmpty())
    }

    @Test
    fun `city ids are unique`() {
        // The panel is keyed by id; a duplicate makes one of the two unreachable
        // and picks the wrong one to describe.
        val duplicates = globe.cities.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate city ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `the coastline is drawable`() {
        val points = globe.coastline.sumOf { it.size / 2 }
        assertTrue("coastline has only $points points", points > 2_000)
        // Flat [lon, lat, lon, lat, …]: an odd length means a longitude with no
        // latitude, and the renderer would read past the end of the run.
        val ragged = globe.coastline.count { it.size % 2 != 0 }
        assertEquals("coastline runs with an odd number of values", 0, ragged)
        val tiny = globe.coastline.count { it.size < 4 }
        assertEquals("coastline runs too short to draw a line", 0, tiny)
    }

    @Test
    fun `the cities a listener would look for first are all present`() {
        // A globe that covered 1,600 obscure towns and missed London would pass
        // every check above and still be useless.
        val staples = listOf("london", "tokyo", "new-york-city", "berlin", "paris")
        val ids = globe.cities.map { it.id }.toSet()
        val missing = staples.filterNot { staple -> ids.any { it.endsWith(staple) } }
        assertTrue("no node for $missing", missing.isEmpty())
    }

    @Test
    fun `the asset stays inside its size budget`() {
        // Parsed whole the first time the globe opens, on the IO dispatcher. The
        // ceiling is that parse, not the download.
        val kilobytes = File(assets, "world_radio.json").length() / 1024
        assertTrue("world_radio.json is $kilobytes KB", kilobytes < 400)
    }
}
