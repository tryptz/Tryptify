package tf.monochrome.android.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.data.achievements.AchievementStore
import tf.monochrome.android.data.achievements.FeatureLog
import tf.monochrome.android.ui.settings.settingsPageIds

class AchievementsTest {

    private val tabs = listOf("Appearance", "Fonts", "Audio")
    private val plugins = listOf("GAIN", "REVERB", "DELAY")
    private val now = 1_000L

    private fun facts(
        log: FeatureLog = FeatureLog(),
        newArtists: Int = 0, streak: Int = 0, hiResSeconds: Long = 0, afterMidnight: Int = 0,
    ) = AchievementFacts(newArtists, streak, hiResSeconds, afterMidnight, log)

    private fun state(states: List<AchievementState>, a: Achievement) = states.first { it.achievement == a }

    @Test
    fun `a fresh install has everything locked at zero`() {
        val s = Achievements.evaluate(facts(), tabs, plugins, now)
        assertEquals(Achievement.entries.size, s.size)
        assertTrue(s.none { it.unlocked })
        assertTrue(s.all { it.done == 0 })
        assertEquals(3, state(s, Achievement.EVERY_CORNER).total)
        assertEquals(tabs, state(s, Achievement.EVERY_CORNER).remaining)
    }

    @Test
    fun `counts come from the log and the plays`() {
        val log = FeatureLog(
            settingsTabs = setOf("Fonts", "Audio", "NotATab"),
            plugins = setOf("GAIN"),
            radioCountries = setOf("JP", "BR"),
            lyricsPresets = setOf("Neon"),
            longestChain = 4,
            autoEqRuns = 0,
        )
        val s = Achievements.evaluate(facts(log, newArtists = 23, streak = 12, hiResSeconds = 9 * 3600 + 59, afterMidnight = 49), tabs, plugins, now)
        assertEquals(2, state(s, Achievement.EVERY_CORNER).done)
        assertEquals(listOf("Appearance"), state(s, Achievement.EVERY_CORNER).remaining)
        assertEquals(listOf("REVERB", "DELAY"), state(s, Achievement.PLUGIN_COLLECTOR).remaining)
        assertEquals(listOf("BR", "JP"), state(s, Achievement.GLOBETROTTER).reached)
        assertEquals(23, state(s, Achievement.EXPLORER).done)
        assertEquals(9, state(s, Achievement.GOLDEN_EARS).done)
        assertEquals(4, state(s, Achievement.SIGNAL_CHAIN).done)
        assertTrue(s.none { it.unlocked })
    }

    @Test
    fun `reaching a goal unlocks it now, and only once`() {
        val s = Achievements.evaluate(facts(FeatureLog(autoEqRuns = 3), afterMidnight = 50), tabs, plugins, now)
        assertEquals(now, state(s, Achievement.ROOM_TUNED).unlockedAt)
        assertEquals(1, state(s, Achievement.ROOM_TUNED).done)
        val fresh = Achievements.newlyUnlocked(s, emptyMap())
        assertEquals(setOf("ROOM_TUNED", "NIGHT_OWL"), fresh.keys)
        assertTrue(Achievements.newlyUnlocked(s, fresh).isEmpty())
    }

    @Test
    fun `an unlock is kept when its count falls back`() {
        val log = FeatureLog(unlockedAt = mapOf("STREAK_30" to 500L))
        val streak = state(Achievements.evaluate(facts(log, streak = 2), tabs, plugins, now), Achievement.STREAK_30)
        assertTrue(streak.unlocked)
        assertEquals(500L, streak.unlockedAt)
        assertEquals(30, streak.done)
    }

    @Test
    fun `next up is the closest locked one and in progress leaves it out`() {
        val s = Achievements.evaluate(facts(newArtists = 23, streak = 12), tabs, plugins, now)
        val next = Achievements.nextUp(s)!!
        assertEquals(Achievement.EXPLORER, next.achievement)
        val rest = Achievements.inProgress(s, next)
        assertFalse(rest.any { it.achievement == Achievement.EXPLORER })
        assertEquals(Achievement.STREAK_30, rest.first().achievement)
    }

    @Test
    fun `completed are newest first`() {
        val log = FeatureLog(unlockedAt = mapOf("NIGHT_OWL" to 10L, "ROOM_TUNED" to 20L))
        val done = Achievements.completed(Achievements.evaluate(facts(log), tabs, plugins, now))
        assertEquals(listOf(Achievement.ROOM_TUNED, Achievement.NIGHT_OWL), done.map { it.achievement })
        assertNull(Achievements.nextUp(emptyList()))
    }

    @Test
    fun `unlock stamps survive a round trip and skip bad entries`() {
        val map = mapOf("NIGHT_OWL" to 123L, "ROOM_TUNED" to 456L)
        assertEquals(map, AchievementStore.decodeUnlocked(AchievementStore.encodeUnlocked(map)))
        assertEquals(mapOf("A" to 1L), AchievementStore.decodeUnlocked(setOf("A=1", "broken", "B=x", "=5")))
    }

    @Test
    fun `the real lists are the ones the app shows`() {
        assertEquals(10, settingsPageIds.size)
        assertFalse("Visual Studio" in settingsPageIds)
        assertEquals(36, SnapinType.availableTypes().size)
    }
}
