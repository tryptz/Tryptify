// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.patterns

/**
 * Every edit a pattern can undergo, as pure functions.
 *
 * The repository persists and the engine sounds; neither decides what
 * "duplicate" or "paste" means. Keeping those decisions here is what makes
 * them testable — `PatternOpsTest` covers the cases that are easy to get
 * subtly wrong, such as a paste into a pattern with fewer channels, or a
 * length change that must not destroy the trigs it hides.
 */
object PatternOps {

    /** Names given to the first channels of a new pattern. */
    val DEFAULT_CHANNEL_NAMES = listOf("Kick", "Snare", "Hat", "Perc")

    /**
     * A new, empty pattern in [bankSlot].
     *
     * It starts with four named channels rather than none because an empty
     * grid gives the user nothing to tap — and the names are only defaults,
     * not a fixed drum layout: channels are added, removed and renamed
     * freely from here on.
     */
    fun newPattern(bankSlot: Int, lengthSteps: Int = 16): Pattern = Pattern(
        bankSlot = bankSlot,
        name = PatternLimits.slotName(bankSlot),
        lengthSteps = lengthSteps.coerceIn(1, PatternLimits.MAX_STEPS),
        channels = DEFAULT_CHANNEL_NAMES.map { PatternChannel(name = it) },
    )

    /**
     * Copies [source] into [bankSlot].
     *
     * Row ids are dropped so the copy is inserted rather than overwriting the
     * original — a duplicate that shared channel ids would write both patterns
     * every time one of them was edited.
     */
    fun duplicate(source: Pattern, bankSlot: Int, name: String? = null): Pattern = source.copy(
        id = 0,
        bankSlot = bankSlot,
        name = name ?: nextCopyName(source.name),
        channels = source.channels.map { it.copy(id = 0) },
    )

    /** "Kick" → "Kick 2" → "Kick 3", so repeated duplication stays readable. */
    fun nextCopyName(name: String): String {
        val match = Regex("^(.*?)\\s+(\\d+)$").find(name)
        return if (match != null) {
            val base = match.groupValues[1]
            val n = match.groupValues[2].toIntOrNull() ?: 1
            "$base ${n + 1}"
        } else {
            "$name 2"
        }
    }

    /** Empties every trig, keeping the channels and their sounds. */
    fun clearSteps(pattern: Pattern): Pattern =
        pattern.copy(channels = pattern.channels.map { it.clearedSteps() })

    /**
     * Pastes [source]'s content into [target]'s slot.
     *
     * The target keeps its slot, its id and its name — this is "make this
     * pattern sound like that one", not "move that pattern here". Channel ids
     * are preserved positionally where both patterns have a channel, so a
     * paste updates rows in place instead of deleting and re-inserting them.
     */
    fun paste(source: Pattern, target: Pattern): Pattern = target.copy(
        lengthSteps = source.lengthSteps,
        stepsPerBeat = source.stepsPerBeat,
        beatsPerBar = source.beatsPerBar,
        bpmOverride = source.bpmOverride,
        channels = source.channels.mapIndexed { index, channel ->
            channel.copy(id = target.channels.getOrNull(index)?.id ?: 0)
        },
    )

    /**
     * Changes the pattern's length.
     *
     * Trigs beyond the new length are kept, not deleted: shortening a 32-step
     * pattern to 16 to hear the first half, then going back, has to bring the
     * second half back with it. `PatternChannel.steps` is always full-length
     * for exactly this reason, and only the first `lengthSteps` of it sounds.
     */
    fun resize(pattern: Pattern, lengthSteps: Int): Pattern =
        pattern.copy(lengthSteps = lengthSteps.coerceIn(1, PatternLimits.MAX_STEPS))

    /** Adds a channel, up to the engine's ceiling. */
    fun addChannel(pattern: Pattern, name: String? = null): Pattern {
        if (pattern.channels.size >= PatternLimits.MAX_CHANNELS) return pattern
        val label = name ?: "Channel ${pattern.channels.size + 1}"
        return pattern.copy(channels = pattern.channels + PatternChannel(name = label))
    }

    /** Removes a channel. The last one cannot be removed — a pattern needs a row. */
    fun removeChannel(pattern: Pattern, index: Int): Pattern {
        if (index !in pattern.channels.indices || pattern.channels.size <= 1) return pattern
        return pattern.copy(channels = pattern.channels.filterIndexed { i, _ -> i != index })
    }

    fun moveChannel(pattern: Pattern, from: Int, to: Int): Pattern {
        if (from !in pattern.channels.indices || to !in pattern.channels.indices || from == to) {
            return pattern
        }
        val list = pattern.channels.toMutableList()
        list.add(to, list.removeAt(from))
        return pattern.copy(channels = list)
    }

    /**
     * Toggles a trig.
     *
     * Turning a step off keeps nothing: the returned step is [Step.OFF], so a
     * step that carried a parameter lock does not keep it invisibly and
     * surprise the user when they tap it back on.
     */
    fun toggleStep(pattern: Pattern, channelIndex: Int, stepIndex: Int, velocity: Int = 100): Pattern =
        pattern.withChannel(channelIndex) { channel ->
            val current = channel.step(stepIndex)
            val next = if (current.enabled) {
                Step.OFF
            } else {
                Step(enabled = true, velocity = velocity.coerceIn(1, 127))
            }
            channel.withStep(stepIndex, next)
        }

    /** Sets a run of steps to the same state — what a drag across the grid does. */
    fun setSteps(
        pattern: Pattern,
        channelIndex: Int,
        stepIndices: Iterable<Int>,
        enabled: Boolean,
        velocity: Int = 100,
    ): Pattern = pattern.withChannel(channelIndex) { channel ->
        var updated = channel
        for (index in stepIndices) {
            updated = updated.withStep(
                index,
                if (enabled) Step(enabled = true, velocity = velocity.coerceIn(1, 127)) else Step.OFF,
            )
        }
        updated
    }

    /** Assigns a sample to a channel, or clears it when [sampleId] is null. */
    fun assignSample(pattern: Pattern, channelIndex: Int, sampleId: Long?, name: String? = null): Pattern =
        pattern.withChannel(channelIndex) { channel ->
            channel.copy(sampleId = sampleId, name = name ?: channel.name)
        }

    /**
     * Drops a deleted sample from every channel that used it.
     *
     * The channel and its trigs stay: the user removed a sound from their
     * library, which is not the same as asking for their patterns to be
     * rewritten. The row goes silent and shows as unassigned until they put
     * something else in it.
     */
    fun detachSample(pattern: Pattern, sampleId: Long): Pattern {
        if (pattern.channels.none { it.sampleId == sampleId }) return pattern
        return pattern.copy(
            channels = pattern.channels.map {
                if (it.sampleId == sampleId) it.copy(sampleId = null) else it
            },
        )
    }

    /** Exclusive solo: soloing one channel clears every other channel's solo. */
    fun soloChannel(pattern: Pattern, channelIndex: Int): Pattern {
        val target = pattern.channels.getOrNull(channelIndex) ?: return pattern
        val turningOn = !target.soloed
        return pattern.copy(
            channels = pattern.channels.mapIndexed { index, channel ->
                channel.copy(soloed = turningOn && index == channelIndex)
            },
        )
    }

    /** The first free bank slot, or null when all 64 are taken. */
    fun firstFreeSlot(used: Collection<Int>): Int? =
        (0 until PatternLimits.MAX_PATTERNS).firstOrNull { it !in used }
}
