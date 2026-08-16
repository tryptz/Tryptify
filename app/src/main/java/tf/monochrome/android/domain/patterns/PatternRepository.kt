// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.patterns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.db.dao.PatternDao
import tf.monochrome.android.data.db.entity.PatternChannelEntity
import tf.monochrome.android.data.db.entity.PatternEntity
import tf.monochrome.android.data.db.entity.PatternStepEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for patterns, and the only place that converts between Room's
 * three tables and the [Pattern] the rest of the app works with.
 *
 * Two things it deliberately does *not* do: decide what an edit means (that is
 * [PatternOps], so the rules stay testable) and touch the audio engine (that
 * is `SamplePlaybackEngine`, so persistence latency can never reach the audio
 * path). A step toggle reaches the engine immediately over the lock-free
 * command queue and reaches Room whenever the write lands — the loop never
 * waits for the database.
 */
@Singleton
class PatternRepository @Inject constructor(
    private val dao: PatternDao,
) {

    /**
     * The bank as the pattern buttons see it: slot, name and length.
     *
     * Deliberately *not* hydrated with channels and steps. This flow re-emits
     * on every write to `sampler_patterns`, and pulling every channel and trig
     * of 64 patterns each time — while the user is tapping steps — would be a
     * database round trip per keystroke for data the bank grid does not draw.
     * The pattern being edited is held in memory by the view model and read in
     * full through [load].
     */
    fun observeBank(): Flow<List<PatternSummary>> =
        dao.observePatterns().map { patterns ->
            patterns.map {
                PatternSummary(
                    id = it.id,
                    bankSlot = it.bankSlot,
                    name = it.name,
                    lengthSteps = it.lengthSteps,
                )
            }
        }

    /** One-shot read of the whole bank. */
    suspend fun loadBank(): List<Pattern> = withContext(Dispatchers.IO) {
        val patterns = dao.allPatterns()
        patterns.map { entity -> hydrate(entity) }
    }

    suspend fun load(patternId: Long): Pattern? = withContext(Dispatchers.IO) {
        dao.pattern(patternId)?.let { hydrate(it) }
    }

    suspend fun loadSlot(bankSlot: Int): Pattern? = withContext(Dispatchers.IO) {
        dao.patternAtSlot(bankSlot)?.let { hydrate(it) }
    }

    private suspend fun hydrate(entity: PatternEntity): Pattern {
        val channels = dao.channels(entity.id)
        val steps = dao.steps(entity.id).groupBy { it.channelId }
        return Pattern(
            id = entity.id,
            bankSlot = entity.bankSlot,
            name = entity.name,
            lengthSteps = entity.lengthSteps,
            stepsPerBeat = entity.stepsPerBeat,
            beatsPerBar = entity.beatsPerBar,
            bpmOverride = entity.bpmOverride,
            channels = channels.map { channel ->
                val rows = steps[channel.id].orEmpty()
                val list = MutableList(PatternLimits.MAX_STEPS) { Step.OFF }
                for (row in rows) {
                    if (row.stepIndex in 0 until PatternLimits.MAX_STEPS) {
                        list[row.stepIndex] = Step(
                            enabled = row.enabled,
                            velocity = row.velocity,
                            pitch = row.pitch,
                            probability = row.probability,
                            pan = row.pan,
                            sampleStart = row.sampleStart,
                            locks = row.locks,
                            speedCode = row.speedCode,
                        )
                    }
                }
                PatternChannel(
                    id = channel.id,
                    name = channel.name,
                    sampleId = channel.sampleId,
                    volume = channel.volume,
                    pan = channel.pan,
                    pitch = channel.pitch,
                    speed = channel.speed,
                    linked = channel.linked,
                    sampleStart = channel.sampleStart,
                    sampleEnd = channel.sampleEnd,
                    attackMs = channel.attackMs,
                    releaseMs = channel.releaseMs,
                    filterHz = channel.filterHz,
                    reverse = channel.reverse,
                    muted = channel.muted,
                    soloed = channel.soloed,
                    enabled = channel.enabled,
                    colorArgb = channel.colorArgb,
                    steps = list,
                )
            },
        )
    }

    /**
     * Writes a whole pattern.
     *
     * Channel row ids are *preserved*, not reassigned. That is the whole point
     * of this method's shape: the caller holds the pattern in memory and keeps
     * editing it while the write happens, and [saveStep] addresses trigs by
     * channel id. Deleting and re-inserting the channels — the obvious
     * implementation — would leave the caller's copy pointing at rows that no
     * longer exist, so the next trig the user tapped would be written into
     * nothing and silently lost.
     *
     * Returns the pattern with whatever ids Room assigned to genuinely new
     * rows, so a first save of a new pattern becomes addressable.
     */
    suspend fun save(pattern: Pattern): Pattern = withContext(Dispatchers.IO) {
        val entity = PatternEntity(
            id = pattern.id,
            bankSlot = pattern.bankSlot,
            name = pattern.name,
            lengthSteps = pattern.lengthSteps,
            stepsPerBeat = pattern.stepsPerBeat,
            beatsPerBar = pattern.beatsPerBar,
            bpmOverride = pattern.bpmOverride,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val patternId = dao.upsertPattern(entity)
        val existing = dao.channels(patternId)

        val channelRows = pattern.channels.mapIndexed { index, channel ->
            PatternChannelEntity(
                // Zero means "new"; anything else keeps the row it already had.
                id = channel.id,
                patternId = patternId,
                position = index,
                name = channel.name,
                sampleId = channel.sampleId,
                volume = channel.volume,
                pan = channel.pan,
                pitch = channel.pitch,
                speed = channel.speed,
                linked = channel.linked,
                sampleStart = channel.sampleStart,
                sampleEnd = channel.sampleEnd,
                attackMs = channel.attackMs,
                releaseMs = channel.releaseMs,
                filterHz = channel.filterHz,
                reverse = channel.reverse,
                muted = channel.muted,
                soloed = channel.soloed,
                enabled = channel.enabled,
                colorArgb = channel.colorArgb,
            )
        }
        val channelIds = dao.upsertChannels(channelRows)

        // Channels the user removed. Done after the upsert so a reorder that
        // reuses ids cannot delete a row that is still in the pattern.
        val kept = channelIds.toHashSet()
        existing.filter { it.id !in kept }.forEach { dao.deleteChannel(it) }

        // REPLACE on an existing channel row deletes and re-inserts it, and
        // the step table cascades off it — so the trigs are gone by this point
        // regardless. They are rewritten wholesale below, which is correct and
        // is why this method is the debounced path rather than the per-tap one.
        dao.clearPatternSteps(patternId)

        // Only enabled steps are stored. A 64-step, 16-channel pattern with
        // eight hits in it is eight rows, not 1024 — which is what keeps a
        // pattern switch off the disk for any noticeable time.
        val stepRows = ArrayList<PatternStepEntity>()
        pattern.channels.forEachIndexed { index, channel ->
            val channelId = channelIds.getOrNull(index) ?: return@forEachIndexed
            channel.steps.forEachIndexed { stepIndex, step ->
                if (step.enabled) {
                    stepRows += PatternStepEntity(
                        channelId = channelId,
                        stepIndex = stepIndex,
                        enabled = true,
                        velocity = step.velocity,
                        pitch = step.pitch,
                        probability = step.probability,
                        pan = step.pan,
                        sampleStart = step.sampleStart,
                        locks = step.locks,
                        speedCode = step.speedCode,
                    )
                }
            }
        }
        if (stepRows.isNotEmpty()) dao.upsertSteps(stepRows)

        pattern.copy(
            id = patternId,
            channels = pattern.channels.mapIndexed { index, channel ->
                channel.copy(id = channelIds.getOrNull(index) ?: channel.id)
            },
        )
    }

    /**
     * Persists a single trig without rewriting the pattern.
     *
     * The hot path: tapping a step during playback must not turn into a
     * delete-and-reinsert of every channel the pattern has.
     */
    suspend fun saveStep(channelId: Long, stepIndex: Int, step: Step) = withContext(Dispatchers.IO) {
        if (channelId <= 0) return@withContext
        if (step.enabled) {
            dao.upsertStep(
                PatternStepEntity(
                    channelId = channelId,
                    stepIndex = stepIndex,
                    enabled = true,
                    velocity = step.velocity,
                    pitch = step.pitch,
                    probability = step.probability,
                    pan = step.pan,
                    sampleStart = step.sampleStart,
                    locks = step.locks,
                    speedCode = step.speedCode,
                ),
            )
        } else {
            dao.deleteStep(channelId, stepIndex)
        }
    }

    suspend fun delete(patternId: Long) = withContext(Dispatchers.IO) {
        dao.deletePattern(patternId)
    }

    suspend fun usedSlots(): Set<Int> = withContext(Dispatchers.IO) {
        dao.allPatterns().map { it.bankSlot }.toSet()
    }

    /**
     * Creates the starting bank the first time the feature is opened.
     *
     * Eight empty patterns rather than one: a bank with a single pattern in it
     * cannot demonstrate the thing the feature is for, and eight is what fits
     * the on-screen bank grid.
     */
    suspend fun seedIfEmpty(): List<Pattern> = withContext(Dispatchers.IO) {
        val existing = dao.allPatterns()
        if (existing.isNotEmpty()) return@withContext existing.map { hydrate(it) }
        (0 until PatternLimits.BANK_ROWS).map { slot -> save(PatternOps.newPattern(slot)) }
    }
}
