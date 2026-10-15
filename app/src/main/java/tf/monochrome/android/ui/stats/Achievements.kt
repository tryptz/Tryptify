package tf.monochrome.android.ui.stats

import tf.monochrome.android.data.achievements.FeatureLog

/**
 * The ten achievements. Each is a count towards a goal; [goal] is null where
 * the goal is the size of something that can change between releases (the
 * settings tabs, the mixer's plugins), which [Achievements.evaluate] is given.
 */
enum class Achievement(val goal: Int?) {
    EXPLORER(25),
    EVERY_CORNER(null),
    PLUGIN_COLLECTOR(null),
    GLOBETROTTER(10),
    LYRIC_STYLIST(5),
    STREAK_30(30),
    GOLDEN_EARS(10),
    NIGHT_OWL(50),
    SIGNAL_CHAIN(5),
    ROOM_TUNED(1),
}

/** The listening side of the achievements, read from play_events. */
data class AchievementFacts(
    val newArtistsLast30Days: Int,
    val currentStreak: Int,
    val hiResSeconds: Long,
    val playsAfterMidnight: Int,
    val log: FeatureLog,
)

data class AchievementState(
    val achievement: Achievement,
    val done: Int,
    val total: Int,
    /** When it was first seen complete; null while it is still locked. */
    val unlockedAt: Long?,
    /** What is left, as ids the screen turns into words: settings tab ids, plugin names. */
    val remaining: List<String>,
    /** What is done so far, where the screen lists it: the countries heard. */
    val reached: List<String>,
) {
    val unlocked: Boolean get() = unlockedAt != null
    val fraction: Float get() = if (total == 0) 1f else done.toFloat() / total
}

object Achievements {
    const val HI_RES_HOUR_SECONDS = 3600L

    /**
     * Where each achievement stands. Once one has been seen complete it stays
     * unlocked, even if its count later falls (a streak breaks): an unlock is
     * a moment, and taking a trophy back reads as a bug.
     */
    fun evaluate(facts: AchievementFacts, settingsTabs: List<String>, plugins: List<String>, now: Long): List<AchievementState> {
        val log = facts.log
        return Achievement.entries.map { a ->
            var remaining = emptyList<String>()
            var reached = emptyList<String>()
            val (count, goal) = when (a) {
                Achievement.EXPLORER -> facts.newArtistsLast30Days to a.goal!!
                Achievement.EVERY_CORNER -> {
                    remaining = settingsTabs.filter { it !in log.settingsTabs }
                    settingsTabs.count { it in log.settingsTabs } to settingsTabs.size
                }
                Achievement.PLUGIN_COLLECTOR -> {
                    remaining = plugins.filter { it !in log.plugins }
                    plugins.count { it in log.plugins } to plugins.size
                }
                Achievement.GLOBETROTTER -> {
                    reached = log.radioCountries.sorted()
                    log.radioCountries.size to a.goal!!
                }
                Achievement.LYRIC_STYLIST -> {
                    reached = log.lyricsPresets.sorted()
                    log.lyricsPresets.size to a.goal!!
                }
                Achievement.STREAK_30 -> facts.currentStreak to a.goal!!
                Achievement.GOLDEN_EARS -> (facts.hiResSeconds / HI_RES_HOUR_SECONDS).toInt() to a.goal!!
                Achievement.NIGHT_OWL -> facts.playsAfterMidnight to a.goal!!
                Achievement.SIGNAL_CHAIN -> log.longestChain to a.goal!!
                Achievement.ROOM_TUNED -> log.autoEqRuns to a.goal!!
            }
            val stamped = log.unlockedAt[a.name]
            val unlockedAt = stamped ?: if (goal > 0 && count >= goal) now else null
            AchievementState(
                achievement = a,
                done = if (unlockedAt != null) goal else count.coerceIn(0, goal),
                total = goal,
                unlockedAt = unlockedAt,
                remaining = if (unlockedAt != null) emptyList() else remaining,
                reached = reached,
            )
        }
    }

    /** Achievements complete now that have no stamp yet, to be stamped. */
    fun newlyUnlocked(states: List<AchievementState>, stamped: Map<String, Long>): Map<String, Long> =
        states.filter { it.unlocked && it.achievement.name !in stamped }
            .associate { it.achievement.name to it.unlockedAt!! }

    /** The locked one closest to done: the "Next up" card. */
    fun nextUp(states: List<AchievementState>): AchievementState? =
        states.filter { !it.unlocked }.maxWithOrNull(compareBy<AchievementState> { it.fraction }.thenBy { -it.achievement.ordinal })

    /** Locked ones, closest first, without [except]. */
    fun inProgress(states: List<AchievementState>, except: AchievementState?): List<AchievementState> =
        states.filter { !it.unlocked && it.achievement != except?.achievement }
            .sortedWith(compareByDescending<AchievementState> { it.fraction }.thenBy { it.achievement.ordinal })

    /** Unlocked ones, newest first. */
    fun completed(states: List<AchievementState>): List<AchievementState> =
        states.filter { it.unlocked }.sortedByDescending { it.unlockedAt }
}
