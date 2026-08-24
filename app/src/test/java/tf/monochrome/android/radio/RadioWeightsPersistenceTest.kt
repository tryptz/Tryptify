package tf.monochrome.android.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The weights are written out field by field in three parallel blocks —
 * `clamped()`, and PreferencesManager's read and write — and a copy-paste slip
 * in any one of them loses a slider silently: it moves, it saves nothing, and
 * nothing fails. A wire test used to collect that price, because every field
 * also had to reach the planner service under its own name. The service is
 * gone; this replaces the guard it was incidentally providing.
 */
class RadioWeightsPersistenceTest {

    private val fields: List<String> = RadioPlannerWeights::class.java.declaredFields
        .filter { it.type == Float::class.javaPrimitiveType }
        .map { it.name }

    private val preferencesSource = File(
        "src/main/java/tf/monochrome/android/data/preferences/PreferencesManager.kt",
    ).readText()

    private fun block(from: String, to: String): String {
        val start = preferencesSource.indexOf(from)
        assertTrue("$from no longer appears in PreferencesManager", start >= 0)
        val end = preferencesSource.indexOf(to, start)
        assertTrue("$to no longer follows $from", end > start)
        return preferencesSource.substring(start, end)
    }

    /**
     * A field left out of [RadioPlannerWeights.clamped] silently reverts to its
     * default, so the listener's setting is thrown away the moment anything
     * round-trips it. Distinct in-range values make that visible: clamping
     * cannot legitimately change any of them.
     */
    @Test
    fun `clamped carries every field through untouched`() {
        val distinct = RadioPlannerWeights(
            localLibrary = 0.11f,
            qobuz = 0.22f,
            spotifyDiscovery = 0.33f,
            canonicalVersionBias = 0.44f,
            novelty = 0.55f,
            familiarity = 0.66f,
            artistSimilarity = 0.77f,
            genreTagSimilarity = 0.88f,
            eraConsistency = 0.99f,
            avoidRecentlyPlayed = 1.11f,
            discoveryDistance = 1.22f,
        )
        assertEquals(fields.size, 11)
        assertEquals(distinct, distinct.clamped())
    }

    @Test
    fun `every weight is read back out of DataStore`() {
        val read = block("val radioPlannerWeights", "suspend fun setRadioPlannerWeights")
        val missing = fields.filterNot { read.contains("$it = prefs[") }
        assertTrue("weights never read back, so they reset on restart: $missing", missing.isEmpty())
    }

    @Test
    fun `every weight is written to DataStore`() {
        val write = block("suspend fun setRadioPlannerWeights", "suspend fun resetRadioPlannerWeights")
        val missing = fields.filterNot { write.contains("= clamped.$it") }
        assertTrue("weights never persisted, so the slider does nothing: $missing", missing.isEmpty())
    }

    /**
     * Read and write have to name the same DataStore key for a field, or the
     * setting saves under one name and loads from another — which looks exactly
     * like a slider that will not stick.
     */
    @Test
    fun `read and write agree on the key for every weight`() {
        val read = Regex("""(\w+) = prefs\[(RADIO_WEIGHT_\w+)]""")
            .findAll(block("val radioPlannerWeights", "suspend fun setRadioPlannerWeights"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        val written = Regex("""prefs\[(RADIO_WEIGHT_\w+)] = clamped\.(\w+)""")
            .findAll(block("suspend fun setRadioPlannerWeights", "suspend fun resetRadioPlannerWeights"))
            .associate { it.groupValues[2] to it.groupValues[1] }

        assertEquals("not every weight was matched to a key", fields.toSet(), read.keys)
        assertEquals("read and write disagree on which key holds which weight", read, written)
    }
}
