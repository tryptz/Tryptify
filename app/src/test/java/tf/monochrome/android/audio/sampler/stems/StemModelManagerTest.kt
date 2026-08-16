package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Installing a model.
 *
 * One rule holds this whole class together: **a model is either installed and
 * verified, or it is not present at all.** The state worth designing against is
 * not "download failed" — that is easy and visible — it is a half-written file
 * that looks installed, because the symptom of that arrives days later as
 * inference producing noise, with nothing pointing at the download.
 *
 * So the interesting tests are the interrupted ones: what is on disk after a
 * connection drops, after a corrupt transfer, after a crash between renaming
 * the file and recording it.
 */
class StemModelManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var payload: ByteArray
    private lateinit var model: StemModel

    /** Serves [payload], optionally failing part-way or lying about the bytes. */
    private class FakeTransport(
        private val payload: ByteArray,
        private val manifest: String = "{}",
        /** Stop after this many bytes and throw, simulating a dropped link. */
        var failAfter: Int = -1,
        /** Serve wrong bytes, simulating corruption or a hostile mirror. */
        var corrupt: Boolean = false,
        /** Ignore the range header, as a badly behaved CDN would. */
        var ignoreRange: Boolean = false,
    ) : ModelTransport {
        var calls = 0
        var lastOffset = -1L

        override suspend fun fetchText(url: String): String = manifest

        override suspend fun download(
            url: String,
            offset: Long,
            sink: OutputStream,
            onBytes: (Long) -> Unit,
        ): Long {
            calls++
            lastOffset = offset
            val from = if (ignoreRange) 0 else offset.toInt()
            var written = 0L
            for (i in from until payload.size) {
                if (failAfter in 0..written) throw java.io.IOException("connection reset")
                sink.write(if (corrupt) payload[i].toInt() xor 0xFF else payload[i].toInt())
                written++
                onBytes(written)
            }
            return written
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manager(
        transport: ModelTransport,
        free: Long = 10_000_000_000L,
    ) = StemModelManager(temp.root, transport, { free })

    @Before
    fun setUp() {
        payload = ByteArray(4096) { (it * 31 % 251).toByte() }
        model = StemModel(
            id = "test-model",
            name = "Test",
            version = "1.0.0",
            format = ModelFormat.ONNX,
            backend = BackendKind.CPU,
            url = "https://example.com/model.bin",
            sha256 = sha256(payload),
            sizeBytes = payload.size.toLong(),
            license = ModelLicense.MIT,
            sourceModel = "test",
            attribution = null,
            stems = Stem.FOUR,
            modelSampleRate = 44100,
            segmentFrames = 343980,
            minimumAndroid = 26,
            abi = "arm64-v8a",
        )
    }

    // ── the happy path ──────────────────────────────────────────────────

    @Test
    fun `a good download installs and is recorded`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Installed)
        assertTrue(manager.isInstalled(model))
        assertEquals(model.id, manager.installed()?.id)
        assertEquals(payload.size.toLong(), manager.modelFile(model).length())
        assertTrue(manager.verifyInstalled())
    }

    /** The record has to survive a round trip, or the install is invisible. */
    @Test
    fun `the installed record round-trips every field that matters`() = runBlocking {
        manager(FakeTransport(payload)).install(model)
        val back = manager(FakeTransport(payload)).installed()!!

        assertEquals(model.id, back.id)
        assertEquals(model.version, back.version)
        assertEquals(model.sha256, back.sha256)
        assertEquals(model.sizeBytes, back.sizeBytes)
        assertEquals(model.stems, back.stems)
        assertEquals(model.segmentFrames, back.segmentFrames)
        assertEquals(model.modelSampleRate, back.modelSampleRate)
        assertEquals(model.license, back.license)
    }

    @Test
    fun `installing twice does not download twice`() = runBlocking {
        val transport = FakeTransport(payload)
        val manager = manager(transport)
        manager.install(model)
        manager.install(model)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `progress runs to completion and ends ready`() = runBlocking {
        val stages = mutableListOf<String>()
        var last = 0f
        manager(FakeTransport(payload)).install(model) {
            if (stages.lastOrNull() != it.stage) stages += it.stage
            last = it.fraction
        }
        assertEquals(1f, last, 1e-6f)
        assertTrue("stages were $stages", stages.contains("Verifying"))
        assertEquals("Ready", stages.last())
    }

    // ── interruption ────────────────────────────────────────────────────

    /**
     * The case the resume logic exists for. A dropped connection must leave the
     * bytes on disk — discarding 200 MB because a train went into a tunnel is
     * hostile — but must not leave anything that reads as installed.
     */
    @Test
    fun `a dropped connection keeps the partial and installs nothing`() = runBlocking {
        val transport = FakeTransport(payload, failAfter = 2000)
        val manager = manager(transport)
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).recoverable)
        assertFalse(manager.isInstalled(model))
        assertNull(manager.installed())
        assertFalse("no model file may exist", manager.modelFile(model).isFile)
        assertEquals(2000L, manager.resumeOffset(model))
    }

    /** And the resume itself: second attempt asks only for the remainder. */
    @Test
    fun `a resumed download completes and is byte-correct`() = runBlocking {
        val transport = FakeTransport(payload, failAfter = 2000)
        val manager = manager(transport)
        manager.install(model)

        transport.failAfter = -1
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Installed)
        assertEquals(2000L, transport.lastOffset)
        assertTrue(manager.verifyInstalled())
        assertArrayEquals(payload, manager.modelFile(model).readBytes())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("byte $i: ${expected[i]} vs ${actual[i]}")
            }
        }
    }

    /**
     * A server that ignores the range header restarts from zero. Appending that
     * to a partial file gives a file of the wrong length — and if it were the
     * right length, a file of the wrong contents. The hash is what catches it,
     * which is why verification happens before the rename and not after.
     */
    @Test
    fun `a server that ignores the range header cannot corrupt an install`() = runBlocking {
        val transport = FakeTransport(payload, failAfter = 2000)
        val manager = manager(transport)
        manager.install(model)

        transport.failAfter = -1
        transport.ignoreRange = true
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Failed)
        assertFalse(manager.isInstalled(model))
        assertFalse(manager.modelFile(model).isFile)
    }

    // ── corruption ──────────────────────────────────────────────────────

    /**
     * Wrong bytes must never reach the model file, and the partial has to go —
     * keeping it would mean resuming into the same wrong file forever.
     */
    @Test
    fun `a checksum mismatch discards everything and installs nothing`() = runBlocking {
        val manager = manager(FakeTransport(payload, corrupt = true))
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("Checksum"))
        assertFalse(manager.isInstalled(model))
        assertFalse(manager.modelFile(model).isFile)
        assertEquals("the poisoned partial must be gone", 0L, manager.resumeOffset(model))
    }

    /** A corrupt attempt must not stop a later good one. */
    @Test
    fun `a good download after a corrupt one still installs`() = runBlocking {
        val transport = FakeTransport(payload, corrupt = true)
        val manager = manager(transport)
        manager.install(model)

        transport.corrupt = false
        assertTrue(manager.install(model) is InstallResult.Installed)
        assertTrue(manager.verifyInstalled())
    }

    // ── the half-installed states ───────────────────────────────────────

    /**
     * A file without its record is not installed. This is the crash-between-
     * rename-and-record case, and the answer has to be "offer the download
     * again" rather than "fail at inference time".
     */
    @Test
    fun `a model file with no record does not count as installed`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)
        assertTrue(temp.root.resolve("installed.json").delete())

        assertNull(manager.installed())
        assertFalse(manager.isInstalled(model))
    }

    /** A record whose file was truncated is not installed either. */
    @Test
    fun `a truncated model file does not count as installed`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)

        val file = manager.modelFile(model)
        file.writeBytes(payload.copyOf(100))

        assertNull(manager.installed())
        assertFalse(manager.isInstalled(model))
    }

    @Test
    fun `a record whose file is gone does not count as installed`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)
        assertTrue(manager.modelFile(model).delete())
        assertNull(manager.installed())
    }

    @Test
    fun `nothing installed reports nothing rather than throwing`() {
        val manager = manager(FakeTransport(payload))
        assertNull(manager.installed())
        assertFalse(manager.isInstalled(model))
        assertEquals(0L, manager.resumeOffset(model))
    }

    // ── storage ─────────────────────────────────────────────────────────

    /**
     * Refusing beats filling the disk. A download that consumes the last byte
     * leaves a device that cannot write anything at all.
     */
    @Test
    fun `an install is refused when there is not enough room`() = runBlocking {
        val transport = FakeTransport(payload)
        val manager = manager(transport, free = 1000)
        val result = manager.install(model)

        assertTrue("got $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).recoverable)
        assertEquals("nothing should have been fetched", 0, transport.calls)
    }

    /** A resume only needs room for what is left. */
    @Test
    fun `a resume only requires space for the remainder`() = runBlocking {
        val transport = FakeTransport(payload, failAfter = 4000)
        manager(transport, free = 10_000_000_000L).install(model)
        assertEquals(4000L, manager(transport).resumeOffset(model))

        transport.failAfter = -1
        // Room for the last 96 bytes plus headroom, but nowhere near the whole
        // payload plus headroom.
        val tight = manager(transport, free = 64L * 1024 * 1024 + 500)
        assertTrue(tight.install(model) is InstallResult.Installed)
    }

    // ── housekeeping ────────────────────────────────────────────────────

    @Test
    fun `uninstall removes the model and the record`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)
        assertTrue(manager.installedBytes() > 0L)

        manager.uninstall()
        assertNull(manager.installed())
        assertEquals(0L, manager.installedBytes())
    }

    @Test
    fun `discarding a partial leaves an installed model alone`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)
        manager.discardPartial(model)
        assertTrue(manager.isInstalled(model))
    }

    @Test
    fun `verify catches a file that changed under us`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)
        assertTrue(manager.verifyInstalled())

        // Same length, different bytes — the one corruption a length check
        // cannot see, which is the whole reason the hash is kept.
        val file = manager.modelFile(model)
        val bytes = file.readBytes()
        bytes[500] = (bytes[500] + 1).toByte()
        file.writeBytes(bytes)

        assertFalse(manager.verifyInstalled())
    }

    @Test
    fun `an update to a new version replaces the old one`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        manager.install(model)

        val next = ByteArray(2048) { (it * 17 % 241).toByte() }
        val updated = model.copy(
            version = "1.1.0",
            sha256 = sha256(next),
            sizeBytes = next.size.toLong(),
        )
        val result = StemModelManager(temp.root, FakeTransport(next), { 10_000_000_000L })
            .install(updated)

        assertTrue("got $result", result is InstallResult.Installed)
        assertEquals("1.1.0", manager(FakeTransport(next)).installed()?.version)

        // The old version must be gone. Only the current model is named in the
        // record, so anything left behind is unreclaimable — every update
        // would double the footprint on disk forever.
        assertFalse("v1.0.0 was left behind", manager.modelFile(model).isFile)
        assertEquals(
            next.size.toLong() + temp.root.resolve("installed.json").length(),
            manager.installedBytes(),
        )
    }

    /** A stale part file from an abandoned version is swept up too. */
    @Test
    fun `an abandoned partial does not linger after a later install`() = runBlocking {
        val transport = FakeTransport(payload, failAfter = 2000)
        val manager = manager(transport)
        manager.install(model)
        assertEquals(2000L, manager.resumeOffset(model))

        val next = ByteArray(2048) { (it * 17 % 241).toByte() }
        val updated = model.copy(
            version = "2.0.0",
            sha256 = sha256(next),
            sizeBytes = next.size.toLong(),
        )
        assertTrue(
            StemModelManager(temp.root, FakeTransport(next), { 10_000_000_000L })
                .install(updated) is InstallResult.Installed,
        )
        assertEquals("the v1 partial should be gone", 0L, manager.resumeOffset(model))
    }

    // ── local import ────────────────────────────────────────────────────

    /**
     * A recognised file becomes a model record carrying that model's channel
     * order, not the enum's.
     *
     * Tested on the conversion rather than by importing a real file: the
     * smallest known model is 136 MB, and writing that to disk to check a
     * field mapping would be a slow test of the wrong thing.
     */
    @Test
    fun `a recognised file converts with its own stem order`() {
        val known = KnownModels.byId("htdemucs-6s")!!
        val converted = known.toStemModel(url = StemModelManager.LOCAL_URL, sha = "ab".repeat(32))

        assertEquals(known.outputOrder, converted.outputOrder)
        assertEquals(known.stems, converted.stems)
        assertEquals(known.segmentFrames, converted.segmentFrames)
        assertEquals(known.modelSampleRate, converted.modelSampleRate)
        assertEquals(StemModelManager.LOCAL_URL, converted.url)
        // An imported model is not fetchable, so it must not claim to be
        // installable from its url.
        assertFalse(converted.installable(34, listOf("arm64-v8a")))
    }

    /**
     * The order has to survive being written to the record and read back.
     * A set would round-trip the membership and lose the mapping, which is the
     * bug that makes a vocal stem contain drums.
     */
    @Test
    fun `the record round-trips the channel order`() = runBlocking {
        val ordered = model.copy(
            stems = Stem.SIX,
            outputOrder = listOf(
                Stem.DRUMS, Stem.BASS, Stem.OTHER, Stem.VOCALS, Stem.GUITAR, Stem.PIANO,
            ),
        )
        val manager = manager(FakeTransport(payload))
        assertTrue(manager.install(ordered) is InstallResult.Installed)

        val back = manager.installed()!!
        assertEquals(ordered.outputOrder, back.outputOrder)
        assertEquals(Stem.DRUMS, back.outputOrder.first())
    }

    /** The order the model emits, not the order the enum declares. */
    @Test
    fun `known models carry demucs channel order`() {
        val six = KnownModels.byId("htdemucs-6s")!!
        assertEquals(
            listOf(Stem.DRUMS, Stem.BASS, Stem.OTHER, Stem.VOCALS, Stem.GUITAR, Stem.PIANO),
            six.outputOrder,
        )
        assertEquals(Stem.SIX, six.stems)

        val four = KnownModels.byId("htdemucs")!!
        assertEquals(
            listOf(Stem.DRUMS, Stem.BASS, Stem.OTHER, Stem.VOCALS),
            four.outputOrder,
        )
        assertEquals(Stem.FOUR, four.stems)

        // The trap this exists to avoid: alphabetical or enum order would put
        // vocals first, and every vocal stem would contain drums.
        assertTrue(six.outputOrder.first() != Stem.VOCALS)
        assertEquals(343_980, six.segmentFrames)
        assertEquals(44_100, six.modelSampleRate)
    }

    @Test
    fun `an unrecognised file is refused rather than guessed at`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        val result = manager.importLocal({ ByteArray(1234).inputStream() })

        assertTrue("got $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("Not a model"))
        assertNull(manager.installed())
        // No debris.
        assertEquals(0L, manager.installedBytes())
    }

    @Test
    fun `an empty file is refused`() = runBlocking {
        val manager = manager(FakeTransport(payload))
        val result = manager.importLocal({ ByteArray(0).inputStream() })
        assertTrue("got $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).reason.contains("empty"))
        assertEquals(0L, manager.installedBytes())
    }

    /** Recognition is by hash first, then size — and never by file name. */
    @Test
    fun `recognition prefers the hash and falls back to size`() {
        val known = KnownModels.byId("htdemucs-6s")!!

        val exact = KnownModels.recognise(known.sha256, known.sizeBytes)
        assertEquals(known.id, exact?.model?.id)
        assertTrue(exact!!.exact)

        // A re-export changes the bytes but not the model we know how to drive.
        val bySize = KnownModels.recognise("0".repeat(64), known.sizeBytes)
        assertEquals(known.id, bySize?.model?.id)
        assertFalse("a size match is not an exact match", bySize!!.exact)

        assertNull(KnownModels.recognise("0".repeat(64), 12345L))
    }

    /** Every entry has to be usable, or it should not be in the list. */
    @Test
    fun `every known model is complete and distinct`() {
        assertTrue(KnownModels.ALL.isNotEmpty())
        assertEquals(
            "ids must be unique",
            KnownModels.ALL.size,
            KnownModels.ALL.map { it.id }.toSet().size,
        )
        assertEquals(
            "sizes must be unique or size fallback is ambiguous",
            KnownModels.ALL.size,
            KnownModels.ALL.map { it.sizeBytes }.toSet().size,
        )
        for (model in KnownModels.ALL) {
            assertEquals("${model.id} hash length", 64, model.sha256.length)
            assertTrue("${model.id} size", model.sizeBytes > 0)
            assertTrue("${model.id} segment", model.segmentFrames > 0)
            assertTrue("${model.id} rate", model.modelSampleRate > 0)
            assertTrue("${model.id} stems", model.outputOrder.isNotEmpty())
            assertEquals(
                "${model.id} lists a stem twice",
                model.outputOrder.size,
                model.outputOrder.toSet().size,
            )
        }
    }

    @Test
    fun `byte sizes read the way a person would say them`() {
        assertEquals("512 B", StemModelManager.formatBytes(512))
        assertEquals("2 kB", StemModelManager.formatBytes(2_000))
        assertEquals("331 MB", StemModelManager.formatBytes(331_000_000))
        assertEquals("1.3 GB", StemModelManager.formatBytes(1_260_000_000))
    }
}
