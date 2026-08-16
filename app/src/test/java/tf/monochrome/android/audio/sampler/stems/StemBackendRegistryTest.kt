package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * Backend selection.
 *
 * The property worth protecting here is that stem separation is never
 * unavailable. The DSP backend needs no model, no download and no network, so
 * a user who declines a large download still has a working feature — and a
 * registry that returned null in that case would turn a graceful degradation
 * into a dead button.
 */
class StemBackendRegistryTest {

    private class Fake(
        override val kind: BackendKind,
        private var available: Boolean,
        private val model: String? = null,
    ) : StemSeparationBackend {
        var initialized = false
        var released = false

        override fun status() = BackendStatus(
            kind = kind,
            available = available,
            modelName = model,
            modelVersion = if (model != null) "1.0.0" else null,
            unavailableReason = if (available) null else "Model not installed",
        )

        override fun availableStems(input: SampleEdits.Buffer) = Stem.entries.toSet()
        override suspend fun initialize(): Result<Unit> {
            initialized = true
            return Result.success(Unit)
        }

        override suspend fun separate(
            input: SampleEdits.Buffer,
            requested: Set<Stem>,
            options: SeparationOptions,
            onProgress: (StemProgress) -> Unit,
        ) = emptyMap<Stem, SampleEdits.Buffer>()

        override fun release() {
            released = true
        }

        fun setAvailable(value: Boolean) {
            available = value
        }
    }

    private fun registry(vararg backends: StemSeparationBackend) =
        StemBackendRegistry(backends.toSet())

    @Test
    fun `the npu backend wins when it is available`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.CPU, true),
            Fake(BackendKind.QNN, true),
        )
        assertEquals(BackendKind.QNN, registry.active()?.kind)
        assertFalse(registry.isDegraded())
    }

    /** The whole point of the fallback chain. */
    @Test
    fun `an unavailable npu falls through to cpu`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.CPU, true),
            Fake(BackendKind.QNN, false),
        )
        assertEquals(BackendKind.CPU, registry.active()?.kind)
    }

    @Test
    fun `with no model installed the built-in backend still runs`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.CPU, false),
            Fake(BackendKind.QNN, false),
        )
        assertEquals(BackendKind.DSP, registry.active()?.kind)
    }

    /**
     * A fallback must be visible, not silent — the brief is explicit about it,
     * and a user watching a job take ten times as long deserves the reason.
     */
    @Test
    fun `falling back is reported as degraded`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.CPU, false),
            Fake(BackendKind.QNN, false),
        )
        assertTrue(registry.isDegraded())
        assertEquals(BackendKind.DSP, registry.active()?.kind)
    }

    /**
     * Unavailable backends stay in the list. "Snapdragon NPU — model not
     * installed" is actionable; an absent row reads as "your phone cannot do
     * this".
     */
    @Test
    fun `unavailable backends are still listed with a reason`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.QNN, false),
        )
        val statuses = registry.statuses()
        assertEquals(2, statuses.size)
        assertEquals(BackendKind.QNN, statuses.first().kind)
        assertEquals("Model not installed", statuses.first().unavailableReason)
        assertEquals(null, statuses.last().unavailableReason)
    }

    @Test
    fun `statuses come back in preference order`() {
        val registry = registry(
            Fake(BackendKind.DSP, true),
            Fake(BackendKind.QNN, true),
            Fake(BackendKind.CPU, true),
        )
        assertEquals(
            listOf(BackendKind.QNN, BackendKind.CPU, BackendKind.DSP),
            registry.statuses().map { it.kind },
        )
    }

    @Test
    fun `a backend can be fetched by kind`() {
        val registry = registry(Fake(BackendKind.DSP, true), Fake(BackendKind.CPU, false))
        assertNotNull(registry.of(BackendKind.CPU))
        assertEquals(null, registry.of(BackendKind.QNN))
    }

    @Test
    fun `an empty registry reports degraded rather than crashing`() {
        val registry = registry()
        assertEquals(null, registry.active())
        assertTrue(registry.isDegraded())
        assertTrue(registry.statuses().isEmpty())
    }

    /** Availability is read at call time, not cached at construction. */
    @Test
    fun `selection follows a backend becoming available`() {
        val cpu = Fake(BackendKind.CPU, false)
        val registry = registry(Fake(BackendKind.DSP, true), cpu)
        assertEquals(BackendKind.DSP, registry.active()?.kind)
        cpu.setAvailable(true)
        assertEquals(BackendKind.CPU, registry.active()?.kind)
    }

    // ── stem sets ───────────────────────────────────────────────────────

    @Test
    fun `the stem sets are what they say they are`() {
        assertEquals(4, Stem.FOUR.size)
        assertEquals(6, Stem.SIX.size)
        assertEquals(setOf(Stem.GUITAR, Stem.PIANO), Stem.EXTENDED)
        assertTrue(Stem.SIX.containsAll(Stem.FOUR))
        assertTrue(Stem.SIX.containsAll(Stem.EXTENDED))
        assertTrue(Stem.FOUR.none { it in Stem.EXTENDED })
        assertEquals(Stem.entries.toSet(), Stem.SIX)
    }

    /** Ids are what Room stores, so they have to survive a round trip. */
    @Test
    fun `every stem round-trips through its id`() {
        for (stem in Stem.entries) assertEquals(stem, Stem.fromId(stem.id))
        assertEquals(Stem.GUITAR, Stem.fromId("guitar"))
        assertEquals(null, Stem.fromId("kazoo"))
        assertEquals(null, Stem.fromId(null))
    }

    /**
     * The DSP backend must never claim guitar or piano. Median filtering
     * separates percussive from harmonic and panning separates centred from
     * wide; neither can tell a guitar from a piano, so offering those two would
     * hand back something plausible-looking and wrong.
     */
    @Test
    fun `the dsp backend never offers the six-stem extras`() {
        val backend = DspStemBackend(DspStemSeparator())
        val stereo = SampleEdits.Buffer(FloatArray(8192), FloatArray(8192), 44100)
        val mono = SampleEdits.Buffer(FloatArray(8192), null, 44100)

        assertEquals(Stem.FOUR, backend.availableStems(stereo))
        assertTrue(backend.availableStems(mono).none { it in Stem.EXTENDED })
        // Mono also loses vocals: centre extraction needs two channels.
        assertFalse(backend.availableStems(mono).contains(Stem.VOCALS))
    }

    /** Asking a four-stem backend for six returns four, not an error. */
    @Test
    fun `asking for more stems than a backend has is not an error`() = runBlocking {
        val separator = DspStemSeparator()
        val input = SampleEdits.Buffer(FloatArray(16384), FloatArray(16384), 44100)
        val out = separator.separate(input, requested = Stem.SIX)
        assertTrue(out.keys.none { it in Stem.EXTENDED })
    }

    /**
     * The floor has to actually hold. If this backend ever reported itself
     * unavailable, a device with no model would have no separation at all.
     */
    @Test
    fun `the built-in dsp backend is unconditionally available`() = runBlocking {
        val backend = DspStemBackend(DspStemSeparator())
        val status = backend.status()
        assertTrue(status.available)
        assertEquals(null, status.unavailableReason)
        assertTrue(backend.initialize().isSuccess)

        // Stereo in, three or four stems out; a mono source loses vocals,
        // which is honest rather than a failure.
        val stereo = SampleEdits.Buffer(FloatArray(8192), FloatArray(8192), 44100)
        assertTrue(backend.availableStems(stereo).isNotEmpty())
    }
}
