// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty

/**
 * Attempts and ghosts on disk, one JSON file per chart.
 *
 * A file per chart rather than one big index: the thing that is read is always
 * "the history for this chart", writes never contend across songs, and a file
 * that somehow becomes corrupt costs one song's records instead of all of them.
 *
 * Reading is defensive in a specific way. Every record carries a version, and
 * an unknown *newer* version is skipped rather than coerced — a build that
 * wrote a shape this one does not understand has records this one would
 * misread, and dropping them from the list is recoverable where silently
 * misreading them is not.
 */
@Singleton
class GlyphAttemptStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val writeLock = Mutex()

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Bumped after every save and clear: what the stats and achievements re-read on. */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = _revision

    private val root: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Attempts for [chartId], newest first.
     *
     * Never throws. A missing file is an empty history, which is the same thing
     * as far as the results screen is concerned.
     */
    suspend fun attempts(chartId: String): List<GlyphAttempt> = withContext(Dispatchers.IO) {
        val file = fileFor(chartId)
        if (!file.exists()) return@withContext emptyList()

        runCatching {
            val stored = json.decodeFromString<List<JsonObject>>(file.readText())
            stored.mapNotNull(::migrate)
        }.onFailure {
            Log.w(TAG, "attempts for $chartId are unreadable: ${it.message}")
        }.getOrDefault(emptyList())
            .sortedByDescending { it.playedAtEpochMs }
    }

    /**
     * The best full run of [difficulty] on [chartId], for the "beat your
     * ghost" target. One difficulty only: a Beginner run is not a best for
     * Challenge, which is what it was when every difficulty shared a list.
     */
    suspend fun bestAttempt(chartId: String, difficulty: StepManiaDifficulty): GlyphAttempt? =
        bestOf(attempts(chartId), difficulty)

    /**
     * The machine's high score table for one chart and difficulty: full runs,
     * best first by StepMania's percent and then by points, as StepMania ranks
     * them. Runs saved before the percent was recorded rank by accuracy.
     */
    suspend fun highScores(
        chartId: String,
        difficulty: StepManiaDifficulty,
        limit: Int = HIGH_SCORES_PER_CHART,
    ): List<GlyphAttempt> = highScoresIn(attempts(chartId), difficulty, limit)

    /** [highScores] from a chart's [history] already read, so one read serves several answers. */
    fun highScoresIn(
        history: List<GlyphAttempt>,
        difficulty: StepManiaDifficulty,
        limit: Int = HIGH_SCORES_PER_CHART,
    ): List<GlyphAttempt> = rankHighScores(history.filter { it.difficulty == difficulty }).take(limit)

    /** The most recent attempt carrying a usable ghost (see [latestGhostOf]). */
    suspend fun latestGhost(chartId: String): GlyphGhost? = latestGhostOf(attempts(chartId))

    /**
     * Every chart's high score tables, for the leaderboard page: chart id to
     * difficulty to the ten best full runs. Charts with no full run are left
     * out. One small file per chart, read in turn.
     */
    suspend fun allHighScores(): Map<String, Map<StepManiaDifficulty, List<GlyphAttempt>>> =
        withContext(Dispatchers.IO) {
            val files = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            buildMap {
                for (file in files) {
                    val runs = runCatching {
                        json.decodeFromString<List<JsonObject>>(file.readText()).mapNotNull(::migrate)
                    }.onFailure {
                        Log.w(TAG, "high scores in ${file.name} are unreadable: ${it.message}")
                    }.getOrDefault(emptyList())
                    val chartId = runs.firstOrNull()?.chartId ?: continue
                    val tables = runs.groupBy { it.difficulty }
                        .mapValues { (_, inDifficulty) ->
                            rankHighScores(inDifficulty).take(HIGH_SCORES_PER_CHART)
                        }
                        .filterValues { it.isNotEmpty() }
                    if (tables.isNotEmpty()) put(chartId, tables)
                }
            }
        }

    /**
     * The newest [limit] runs across every chart, newest first: what the
     * Sync settings read the player's timing from.
     */
    suspend fun recentRuns(limit: Int = RECENT_RUNS): List<GlyphAttempt> = withContext(Dispatchers.IO) {
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        files.flatMap { file ->
            runCatching {
                json.decodeFromString<List<JsonObject>>(file.readText()).mapNotNull(::migrate)
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.playedAtEpochMs }.take(limit)
    }

    /**
     * Append [attempt], keeping at most [MAX_ATTEMPTS_PER_CHART].
     *
     * Trimming keeps the highest score regardless of age alongside the most
     * recent runs, so a personal best is never aged out by a run of bad ones.
     * Only the ghost a later run can play back is written ([withOneGhost]); a
     * file from before that keeps its ghosts until its next save.
     */
    suspend fun save(attempt: GlyphAttempt): Unit = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val existing = attempts(attempt.chartId)
            val combined = (listOf(attempt) + existing)
                .sortedByDescending { it.playedAtEpochMs }

            // Every difficulty's high score table survives, whatever else is
            // trimmed: a record is never aged out by a run of bad ones.
            val kept = LinkedHashSet<GlyphAttempt>()
            kept += attempt
            for (difficulty in combined.map { it.difficulty }.distinct()) {
                kept += rankHighScores(combined.filter { it.difficulty == difficulty })
                    .take(HIGH_SCORES_PER_CHART)
            }
            for (candidate in combined) {
                if (kept.size >= MAX_ATTEMPTS_PER_CHART) break
                kept += candidate
            }

            val file = fileFor(attempt.chartId)
            runCatching {
                // Written to a sibling and renamed so a kill mid-write leaves
                // the previous history intact rather than a half file.
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(json.encodeToString(withOneGhost(kept.toList())))
                if (!temporary.renameTo(file)) {
                    file.writeText(temporary.readText())
                    temporary.delete()
                }
            }.onFailure {
                Log.w(TAG, "could not save attempt for ${attempt.chartId}: ${it.message}")
            }
        }
        _revision.value++
        Unit
    }

    suspend fun clear(chartId: String): Unit = writeLock.withLock {
        withContext(Dispatchers.IO) { fileFor(chartId).delete() }
        _revision.value++
        Unit
    }

    /**
     * Bring one stored record up to the current shape.
     *
     * Returns null for a record this build cannot read. Every future version
     * bump adds a branch here and leaves the ones below it alone; that is the
     * whole migration contract.
     */
    internal fun migrate(stored: JsonObject): GlyphAttempt? {
        val version = stored["v"]?.jsonPrimitive?.int ?: 1
        return when {
            version > GlyphAttempt.CURRENT_VERSION -> {
                Log.w(TAG, "skipping an attempt written by a newer build (v$version)")
                null
            }
            // Version 1 is the current shape, so it is read directly. A v2 would
            // be rewritten into v1's fields here before decoding.
            version == 1 -> runCatching {
                json.decodeFromJsonElement(GlyphAttempt.serializer(), stored)
            }.getOrNull()
            else -> null
        }
    }

    /**
     * A file name that survives whatever a chart id contains.
     *
     * Chart ids are derived from file paths, so they hold slashes and spaces
     * and anything else a filesystem allows in a name but not in a component.
     */
    private fun fileFor(chartId: String): File =
        File(root, "${chartId.hashCode().toUInt().toString(16)}.json")

    private companion object {
        const val TAG = "GlyphAttempts"
        const val DIRECTORY = "glyph/attempts"

        /** Enough for the high score tables plus a season of recent runs. */
        const val MAX_ATTEMPTS_PER_CHART = 60

        /** StepMania keeps ten per chart on a machine. */
        const val HIGH_SCORES_PER_CHART = 10

        /** How many recent runs the Sync settings read the player's timing from. */
        const val RECENT_RUNS = 10
    }
}

/** Full runs, best first: StepMania's percent, then points, then the earlier run. */
internal fun rankHighScores(runs: List<GlyphAttempt>): List<GlyphAttempt> =
    runs.filter { it.isFullRun }.sortedWith(
        compareByDescending<GlyphAttempt> { if (it.hasStepScore) it.percent else it.accuracy }
            .thenByDescending { it.score }
            .thenBy { it.playedAtEpochMs },
    )

/**
 * The ghost "beat your ghost" plays from [history] (newest first, as
 * [GlyphAttemptStore.attempts] returns it): the newest run whose ghost is
 * usable. Usable is doing real work: a ghost whose arrays disagree is treated
 * as absent, so a truncated write can never crash playback.
 */
internal fun latestGhostOf(history: List<GlyphAttempt>): GlyphGhost? =
    history.firstNotNullOfOrNull { attempt -> attempt.ghost?.takeIf { it.isConsistent && it.size > 0 } }

/** The best full run of [difficulty] in [history], as the high score table ranks it. */
internal fun bestOf(history: List<GlyphAttempt>, difficulty: StepManiaDifficulty): GlyphAttempt? =
    rankHighScores(history.filter { it.difficulty == difficulty }).firstOrNull()

/**
 * [runs] with a ghost left only on the run [latestGhostOf] would pick once
 * they are read back. Nothing else plays a stored ghost, and a ghost is most
 * of a record's size, so sixty of them made every file several times larger
 * than its scores, and every read of it, the stats' included, parsed them.
 * The pick is made in the order a read returns, newest first with ties in
 * file order, so it is the one the read finds.
 */
internal fun withOneGhost(runs: List<GlyphAttempt>): List<GlyphAttempt> {
    val keep = runs.sortedByDescending { it.playedAtEpochMs }
        .firstOrNull { attempt -> attempt.ghost?.let { it.isConsistent && it.size > 0 } == true }
    return runs.map { if (it === keep || it.ghost == null) it else it.copy(ghost = null) }
}
