package tf.monochrome.android.domain.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pattern edits, and the cases that are easy to get subtly wrong.
 *
 * The theme running through these is destructiveness: which operations are
 * allowed to lose the user's work and which are not. Shortening a pattern must
 * not delete the trigs it hides, deleting a sample must not delete the
 * channels that used it, and duplicating must not produce two patterns that
 * write to the same rows.
 */
class PatternOpsTest {

    private fun pattern(length: Int = 16) = PatternOps.newPattern(bankSlot = 0, lengthSteps = length)

    // ── creation ────────────────────────────────────────────────────────

    @Test
    fun `a new pattern starts usable`() {
        val p = pattern()
        assertEquals(16, p.lengthSteps)
        assertEquals(4, p.channels.size)
        assertEquals("A01", p.name)
        assertFalse(p.hasContent)
        // Steps are always allocated to the maximum, whatever the length is.
        assertEquals(PatternLimits.MAX_STEPS, p.channels[0].steps.size)
    }

    @Test
    fun `bank slots read as letter and number`() {
        assertEquals("A01", PatternLimits.slotName(0))
        assertEquals("A08", PatternLimits.slotName(7))
        assertEquals("B01", PatternLimits.slotName(8))
        assertEquals("H08", PatternLimits.slotName(63))
        // Out of range clamps rather than throwing — a corrupt slot in the
        // database should render as something, not crash the bank.
        assertEquals("A01", PatternLimits.slotName(-5))
        assertEquals("H08", PatternLimits.slotName(9999))
    }

    @Test
    fun `first free slot skips taken ones`() {
        assertEquals(0, PatternOps.firstFreeSlot(emptySet()))
        assertEquals(2, PatternOps.firstFreeSlot(setOf(0, 1)))
        assertEquals(1, PatternOps.firstFreeSlot(setOf(0, 2, 3)))
        assertNull(PatternOps.firstFreeSlot((0 until PatternLimits.MAX_PATTERNS).toSet()))
    }

    // ── steps ───────────────────────────────────────────────────────────

    @Test
    fun `toggling a step turns it on and back off`() {
        var p = pattern()
        p = PatternOps.toggleStep(p, 0, 4)
        assertTrue(p.channels[0].step(4).enabled)
        assertTrue(p.hasContent)

        p = PatternOps.toggleStep(p, 0, 4)
        assertFalse(p.channels[0].step(4).enabled)
        assertFalse(p.hasContent)
    }

    @Test
    fun `turning a step off discards its parameter lock`() {
        // Otherwise a step that carried a lock keeps it invisibly and
        // surprises the user the next time they tap it on.
        var p = pattern()
        p = p.withChannel(0) {
            it.withStep(3, Step(enabled = true, velocity = 40, pitch = 5, locks = Step.LOCK_PITCH))
        }
        p = PatternOps.toggleStep(p, 0, 3)
        assertEquals(Step.OFF, p.channels[0].step(3))
    }

    @Test
    fun `toggling out of range is a no-op rather than a crash`() {
        val p = pattern()
        assertEquals(p, PatternOps.toggleStep(p, 99, 0))
        assertEquals(p.channels[0], PatternOps.toggleStep(p, 0, 999).channels[0])
    }

    @Test
    fun `painting a run sets every step in it`() {
        var p = pattern()
        p = PatternOps.setSteps(p, 1, 4..7, enabled = true)
        for (index in 4..7) assertTrue("step $index", p.channels[1].step(index).enabled)
        assertFalse(p.channels[1].step(3).enabled)
        assertFalse(p.channels[1].step(8).enabled)

        p = PatternOps.setSteps(p, 1, 4..7, enabled = false)
        for (index in 4..7) assertFalse("step $index", p.channels[1].step(index).enabled)
    }

    @Test
    fun `velocity is clamped into the midi range`() {
        var p = PatternOps.setSteps(pattern(), 0, listOf(0), enabled = true, velocity = 500)
        assertEquals(127, p.channels[0].step(0).velocity)
        p = PatternOps.setSteps(pattern(), 0, listOf(0), enabled = true, velocity = -20)
        assertEquals(1, p.channels[0].step(0).velocity)
    }

    // ── length ──────────────────────────────────────────────────────────

    @Test
    fun `shortening a pattern hides trigs without destroying them`() {
        var p = pattern(32)
        p = PatternOps.toggleStep(p, 0, 24)
        assertTrue(p.hasContent)

        val short = PatternOps.resize(p, 16)
        // Not audible any more...
        assertFalse(short.hasContent)
        // ...but still there, and back when the length is restored.
        assertTrue(short.channels[0].step(24).enabled)
        assertTrue(PatternOps.resize(short, 32).hasContent)
    }

    @Test
    fun `length is clamped to what the engine supports`() {
        assertEquals(1, PatternOps.resize(pattern(), 0).lengthSteps)
        assertEquals(1, PatternOps.resize(pattern(), -8).lengthSteps)
        assertEquals(PatternLimits.MAX_STEPS, PatternOps.resize(pattern(), 999).lengthSteps)
    }

    @Test
    fun `every offered length is representable`() {
        for (option in PatternLength.entries) {
            assertEquals(option.steps, PatternOps.resize(pattern(), option.steps).lengthSteps)
        }
        assertEquals(PatternLength.TWELVE, PatternLength.nearest(12))
        assertEquals(PatternLength.SIXTEEN, PatternLength.nearest(15))
        assertEquals(PatternLength.SIXTY_FOUR, PatternLength.nearest(60))
    }

    // ── channels ────────────────────────────────────────────────────────

    @Test
    fun `channels can be added up to the engine ceiling`() {
        var p = pattern()
        repeat(PatternLimits.MAX_CHANNELS) { p = PatternOps.addChannel(p) }
        assertEquals(PatternLimits.MAX_CHANNELS, p.channels.size)
        // One more must not exceed what the native side has room for.
        assertSame(p, PatternOps.addChannel(p))
    }

    @Test
    fun `the last channel cannot be removed`() {
        var p = pattern()
        while (p.channels.size > 1) p = PatternOps.removeChannel(p, 0)
        assertEquals(1, p.channels.size)
        assertSame(p, PatternOps.removeChannel(p, 0))
    }

    @Test
    fun `moving a channel reorders without losing one`() {
        val p = PatternOps.moveChannel(pattern(), 0, 2)
        assertEquals(4, p.channels.size)
        assertEquals(listOf("Snare", "Hat", "Kick", "Perc"), p.channels.map { it.name })
        assertEquals(4, PatternOps.moveChannel(pattern(), 0, 0).channels.size)
    }

    @Test
    fun `solo is exclusive`() {
        var p = pattern()
        p = PatternOps.soloChannel(p, 1)
        assertTrue(p.channels[1].soloed)
        assertFalse(p.channels[0].soloed)

        p = PatternOps.soloChannel(p, 2)
        assertTrue(p.channels[2].soloed)
        assertFalse("soloing another channel clears the first", p.channels[1].soloed)

        // Soloing the same channel again clears it entirely.
        p = PatternOps.soloChannel(p, 2)
        assertTrue(p.channels.none { it.soloed })
    }

    // ── samples ─────────────────────────────────────────────────────────

    @Test
    fun `assigning a sample names an unnamed channel but keeps a named one`() {
        var p = pattern()
        p = PatternOps.assignSample(p, 0, sampleId = 7L, name = "Deep Kick")
        assertEquals(7L, p.channels[0].sampleId)
        assertEquals("Deep Kick", p.channels[0].name)

        // Re-assigning does not rename over what the user already has.
        p = PatternOps.assignSample(p, 0, sampleId = 9L, name = null)
        assertEquals(9L, p.channels[0].sampleId)
        assertEquals("Deep Kick", p.channels[0].name)
    }

    @Test
    fun `deleting a sample empties channels but keeps their trigs`() {
        var p = pattern()
        p = PatternOps.assignSample(p, 0, 7L)
        p = PatternOps.assignSample(p, 2, 7L)
        p = PatternOps.toggleStep(p, 0, 0)
        p = PatternOps.toggleStep(p, 2, 8)

        val after = PatternOps.detachSample(p, 7L)
        assertNull(after.channels[0].sampleId)
        assertNull(after.channels[2].sampleId)
        // The user removed a sound from their library, not their pattern.
        assertTrue(after.channels[0].step(0).enabled)
        assertTrue(after.channels[2].step(8).enabled)
        assertEquals(4, after.channels.size)
    }

    @Test
    fun `detaching a sample nothing uses changes nothing`() {
        val p = PatternOps.assignSample(pattern(), 0, 7L)
        assertSame(p, PatternOps.detachSample(p, 99L))
    }

    // ── duplicate, copy, paste ──────────────────────────────────────────

    @Test
    fun `duplicating drops row ids so the copy is inserted`() {
        var source = pattern()
        source = PatternOps.toggleStep(source, 0, 0)
        source = source.copy(
            id = 5,
            channels = source.channels.mapIndexed { index, c -> c.copy(id = (index + 1).toLong()) },
        )

        val copy = PatternOps.duplicate(source, bankSlot = 3)
        assertEquals(0L, copy.id)
        assertEquals(3, copy.bankSlot)
        assertTrue(copy.channels.all { it.id == 0L })
        // A copy that kept the ids would write both patterns on every edit.
        assertTrue(copy.channels[0].step(0).enabled)
        assertNotEquals(source.name, copy.name)
    }

    @Test
    fun `repeated duplication produces readable names`() {
        assertEquals("Beat 2", PatternOps.nextCopyName("Beat"))
        assertEquals("Beat 3", PatternOps.nextCopyName("Beat 2"))
        assertEquals("Beat 11", PatternOps.nextCopyName("Beat 10"))
        assertEquals("A01 2", PatternOps.nextCopyName("A01"))
    }

    @Test
    fun `paste takes content and leaves identity alone`() {
        var source = pattern(32)
        source = PatternOps.toggleStep(source, 0, 5)
        source = source.copy(id = 1, bankSlot = 0, name = "Source")

        val target = pattern(16).copy(
            id = 2,
            bankSlot = 4,
            name = "Target",
            channels = pattern().channels.mapIndexed { i, c -> c.copy(id = (100 + i).toLong()) },
        )

        val merged = PatternOps.paste(source, target)
        // Identity is the target's...
        assertEquals(2L, merged.id)
        assertEquals(4, merged.bankSlot)
        assertEquals("Target", merged.name)
        // ...content is the source's.
        assertEquals(32, merged.lengthSteps)
        assertTrue(merged.channels[0].step(5).enabled)
        // Channel rows are updated in place rather than deleted and re-inserted.
        assertEquals(100L, merged.channels[0].id)
        assertEquals(101L, merged.channels[1].id)
    }

    @Test
    fun `pasting a wider pattern into a narrower one keeps every channel`() {
        var source = pattern()
        repeat(4) { source = PatternOps.addChannel(source) }
        val target = pattern().copy(channels = pattern().channels.take(2))

        val merged = PatternOps.paste(source, target)
        assertEquals(8, merged.channels.size)
        // The channels beyond what the target had are new rows.
        assertEquals(0L, merged.channels[7].id)
    }

    @Test
    fun `clearing steps keeps channels and their sounds`() {
        var p = pattern()
        p = PatternOps.assignSample(p, 0, 7L)
        p = PatternOps.toggleStep(p, 0, 0)
        p = p.withChannel(0) { it.copy(volume = 0.5f, pan = -0.3f) }

        val cleared = PatternOps.clearSteps(p)
        assertFalse(cleared.hasContent)
        assertEquals(4, cleared.channels.size)
        assertEquals(7L, cleared.channels[0].sampleId)
        assertEquals(0.5f, cleared.channels[0].volume, 1e-6f)
        assertEquals(-0.3f, cleared.channels[0].pan, 1e-6f)
    }
}
