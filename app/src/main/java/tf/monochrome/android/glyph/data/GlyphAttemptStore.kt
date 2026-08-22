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

    /** The best full run for [chartId], for the "beat your ghost" target. */
    suspend fun bestAttempt(chartId: String): GlyphAttempt? =
        attempts(chartId).filter { it.isFullRun }.maxByOrNull { it.score }

    /**
     * The most recent attempt carrying a usable ghost.
     *
     * Usable is doing real work: a ghost whose arrays disagree is treated as
     * absent, so a truncated write can never crash playback.
     */
    suspend fun latestGhost(chartId: String): GlyphGhost? =
        attempts(chartId).firstNotNullOfOrNull { attempt ->
            attempt.ghost?.takeIf { it.isConsistent && it.size > 0 }
        }

    /**
     * Append [attempt], keeping at most [MAX_ATTEMPTS_PER_CHART].
     *
     * Trimming keeps the highest score regardless of age alongside the most
     * recent runs, so a personal best is never aged out by a run of bad ones.
     */
    suspend fun save(attempt: GlyphAttempt): Unit = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val existing = attempts(attempt.chartId)
            val best = existing.filter { it.isFullRun }.maxByOrNull { it.score }
            val combined = (listOf(attempt) + existing)
                .sortedByDescending { it.playedAtEpochMs }

            val kept = LinkedHashSet<GlyphAttempt>()
            kept += attempt
            if (best != null) kept += best
            for (candidate in combined) {
                if (kept.size >= MAX_ATTEMPTS_PER_CHART) break
                kept += candidate
            }

            val file = fileFor(attempt.chartId)
            runCatching {
                // Written to a sibling and renamed so a kill mid-write leaves
                // the previous history intact rather than a half file.
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(json.encodeToString(kept.toList()))
                if (!temporary.renameTo(file)) {
                    file.writeText(temporary.readText())
                    temporary.delete()
                }
            }.onFailure {
                Log.w(TAG, "could not save attempt for ${attempt.chartId}: ${it.message}")
            }
        }
    }

    suspend fun clear(chartId: String): Unit = writeLock.withLock {
        withContext(Dispatchers.IO) { fileFor(chartId).delete() }
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

        /** Enough for a personal best plus a season of recent runs. */
        const val MAX_ATTEMPTS_PER_CHART = 25
    }
}
