// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphAttemptStore
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.data.bestOf
import tf.monochrome.android.glyph.data.latestGhostOf
import tf.monochrome.android.glyph.data.withOneGhost

/**
 * The attempt store keeps a ghost only where "beat your ghost" can still
 * play it, and reads a file written before that, ghosts and all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GlyphAttemptStoreTest {

    // The app's own settings (AppModule.provideJson).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = GlyphAttemptStore(context, json)
    private val dir = File(context.filesDir, "glyph/attempts")

    @Before
    fun empty() {
        dir.deleteRecursively()
    }

    private fun ghost(vararg times: Int) = GlyphGhost(
        timesMs = times.toList(),
        lanes = times.map { 0 },
        offsetsMs = times.map { 0 },
        judgements = times.map { 0 },
    )

    private fun run(
        id: String,
        at: Long,
        ghost: GlyphGhost? = ghost(100, 200),
        percent: Float = 0.5f,
        difficulty: StepManiaDifficulty = StepManiaDifficulty.HARD,
        segment: Boolean = false,
    ) = GlyphAttempt(
        id = id,
        chartId = "chart",
        songTitle = "Song",
        difficulty = difficulty,
        playedAtEpochMs = at,
        score = (percent * 1000).toInt(),
        accuracy = percent,
        maxCombo = 10,
        percent = percent,
        ghost = ghost,
        segmentStartSeconds = if (segment) 10f else null,
        segmentEndSeconds = if (segment) 20f else null,
    )

    private fun storedGhosts(): Int = runBlocking { store.attempts("chart").count { it.ghost != null } }

    @Test
    fun onlyTheGhostThatCanBePlayedIsKept() = runBlocking {
        store.save(run("a", at = 1L, ghost = ghost(1)))
        store.save(run("b", at = 2L, ghost = ghost(2)))
        store.save(run("c", at = 3L, ghost = ghost(3)))
        val history = store.attempts("chart")
        assertEquals(3, history.size)
        assertEquals("one ghost on disk", 1, storedGhosts())
        assertEquals(listOf(3), store.latestGhost("chart")?.timesMs)
        // The scores are untouched.
        assertEquals(listOf("c", "b", "a"), history.map { it.id })
    }

    @Test
    fun aRunWithNoUsableGhostLeavesTheLastOneInPlay() = runBlocking {
        store.save(run("a", at = 1L, ghost = ghost(1, 2)))
        // Newer, but nothing to play back: empty, or arrays that disagree.
        store.save(run("b", at = 2L, ghost = GlyphGhost()))
        store.save(run("c", at = 3L, ghost = GlyphGhost(timesMs = listOf(1), lanes = emptyList())))
        assertEquals(listOf(1, 2), store.latestGhost("chart")?.timesMs)
        assertEquals("the unplayable ones are not kept either", 1, storedGhosts())
    }

    @Test
    fun aFileFromBeforeKeepsAllItsGhostsUntilItsNextSave() = runBlocking {
        // As the store wrote them before: every run with its ghost.
        dir.mkdirs()
        val old = listOf(run("a", at = 1L, ghost = ghost(1)), run("b", at = 2L, ghost = ghost(2)))
        val name = "${"chart".hashCode().toUInt().toString(16)}.json"
        File(dir, name).writeText(json.encodeToString(old))

        val read = store.attempts("chart")
        assertEquals("an old file still loads, ghosts and all", 2, read.count { it.ghost != null })
        assertEquals(listOf(2), store.latestGhost("chart")?.timesMs)

        store.save(run("c", at = 3L, ghost = null))
        assertEquals("trimmed on the next write", 1, storedGhosts())
        assertEquals("and the ghost in play is the one it was", listOf(2), store.latestGhost("chart")?.timesMs)
    }

    @Test
    fun bestAndGhostFromOneReadMatchTheStoresOwnAnswers() = runBlocking {
        store.save(run("a", at = 1L, percent = 0.9f))
        store.save(run("b", at = 2L, percent = 0.7f))
        store.save(run("c", at = 3L, percent = 0.95f, segment = true))
        store.save(run("d", at = 4L, percent = 0.8f, difficulty = StepManiaDifficulty.EASY))
        val history = store.attempts("chart")
        for (difficulty in StepManiaDifficulty.entries) {
            assertEquals(store.bestAttempt("chart", difficulty), bestOf(history, difficulty))
            assertEquals(store.highScores("chart", difficulty), store.highScoresIn(history, difficulty))
        }
        assertEquals("a training segment is never a best", "a", bestOf(history, StepManiaDifficulty.HARD)?.id)
        assertEquals(store.latestGhost("chart"), latestGhostOf(history))
    }

    @Test
    fun theGhostKeptIsTheOneAReadFindsFirst() {
        // Two runs at the same moment: a read sorts by time and keeps their
        // order, so the first written is the one it finds.
        val first = run("first", at = 5L, ghost = ghost(1))
        val second = run("second", at = 5L, ghost = ghost(2))
        val older = run("older", at = 1L, ghost = ghost(3))
        val kept = withOneGhost(listOf(first, second, older))
        assertSame(first, kept[0])
        assertNull(kept[1].ghost)
        assertNull(kept[2].ghost)
        assertEquals(kept.map { it.copy(ghost = null) }, listOf(first, second, older).map { it.copy(ghost = null) })
        assertNotNull(latestGhostOf(kept.sortedByDescending { it.playedAtEpochMs }))
        assertTrue(withOneGhost(emptyList()).isEmpty())
    }
}
