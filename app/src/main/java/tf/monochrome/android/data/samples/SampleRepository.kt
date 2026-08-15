// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.samples

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.sampler.PcmDecoder
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.WavCodec
import tf.monochrome.android.data.db.dao.SampleDao
import tf.monochrome.android.data.db.entity.SampleEntity
import tf.monochrome.android.domain.patterns.CaptureSource
import tf.monochrome.android.domain.patterns.SampleCategory
import tf.monochrome.android.domain.patterns.SampleRef
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sample library: metadata in Room, audio on disk.
 *
 * The split is the whole point. A library of a few hundred one-shots is tens
 * of megabytes of PCM, and holding that in RAM to show a list of names would
 * be absurd — so Room holds only what the browser draws, the WAV stays on
 * disk until a channel actually needs it, and the pre-rendered peak file lets
 * a scrolling list draw two hundred waveform thumbnails without opening a
 * single audio file.
 *
 * Everything here is `suspend` and hops to IO. Nothing on this class may be
 * called from the audio thread.
 */
@Singleton
class SampleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SampleDao,
) {

    /** `files/sampler/audio` and `files/sampler/waveforms`. */
    private val audioDir: File by lazy {
        File(context.filesDir, "sampler/audio").apply { mkdirs() }
    }

    private val waveformDir: File by lazy {
        File(context.filesDir, "sampler/waveforms").apply { mkdirs() }
    }

    // ── reads ───────────────────────────────────────────────────────────

    fun observeAll(): Flow<List<SampleRef>> = dao.observeAll().map { it.map(::toRef) }

    fun observeByCategory(category: SampleCategory): Flow<List<SampleRef>> =
        dao.observeByCategory(category.id).map { it.map(::toRef) }

    fun search(query: String): Flow<List<SampleRef>> =
        dao.search(query.trim().lowercase()).map { it.map(::toRef) }

    fun count(): Flow<Int> = dao.count()

    suspend fun byId(id: Long): SampleRef? = withContext(Dispatchers.IO) {
        dao.byId(id)?.let(::toRef)
    }

    suspend fun byIds(ids: Collection<Long>): Map<Long, SampleRef> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyMap() else dao.byIds(ids.toList()).associate { it.id to toRef(it) }
    }

    /**
     * Reads a sample's PCM back off disk.
     *
     * Called when a channel needs a sound resident in the engine, never
     * speculatively — this is the only path that pulls audio into memory, and
     * it is deliberately explicit so it is obvious where the cost is paid.
     */
    suspend fun loadAudio(ref: SampleRef): WavCodec.Audio? = withContext(Dispatchers.IO) {
        val file = File(ref.filePath)
        if (!file.exists()) {
            Log.w(TAG, "sample ${ref.id} missing on disk: ${ref.filePath}")
            return@withContext null
        }
        runCatching { WavCodec.read(file) }
            .onFailure { Log.e(TAG, "could not read ${ref.filePath}", it) }
            .getOrNull()
    }

    /** Reads the cached peak file, or null when it is missing or unreadable. */
    suspend fun loadWaveform(ref: SampleRef): FloatArray? = withContext(Dispatchers.IO) {
        val path = ref.waveformPath ?: return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = input.readInt()
                if (count <= 0 || count > MAX_PEAK_FLOATS) return@use FloatArray(0)
                FloatArray(count) { input.readFloat() }
            }
        }.getOrNull()
    }

    // ── writes ──────────────────────────────────────────────────────────

    /**
     * Writes a captured buffer to disk and records it.
     *
     * Returns the stored sample, or null if the write failed — in which case
     * nothing has been left behind: the row is only inserted once the audio is
     * safely on disk, so a failed save can never leave the library pointing at
     * a file that is not there.
     */
    suspend fun save(
        buffer: SampleEdits.Buffer,
        name: String,
        category: SampleCategory,
        captureSource: CaptureSource? = null,
        sourceTrackTitle: String? = null,
        sourceArtist: String? = null,
        sourceTimestampMs: Long? = null,
        bpm: Float? = null,
        musicalKey: String? = null,
        tags: List<String> = emptyList(),
    ): SampleRef? = withContext(Dispatchers.IO) {
        if (buffer.frames < SampleEdits.MIN_FRAMES) return@withContext null

        val safeName = sanitize(name).ifBlank { "Sample" }
        val stamp = System.currentTimeMillis()
        val audioFile = File(audioDir, "${stamp}_${safeName.take(40)}.wav")

        val written = runCatching {
            WavCodec.write(audioFile, buffer.left, buffer.right, buffer.sampleRate)
        }
        if (written.isFailure) {
            Log.e(TAG, "failed writing $audioFile", written.exceptionOrNull())
            audioFile.delete()
            return@withContext null
        }

        val waveformFile = File(waveformDir, "${stamp}_${safeName.take(40)}.peaks")
        // A missing thumbnail is cosmetic — the browser falls back to drawing
        // nothing — so a failure here must not fail the save.
        val waveformPath = runCatching {
            writePeaks(waveformFile, SampleEdits.peaks(buffer))
            waveformFile.absolutePath
        }.getOrNull()

        val entity = SampleEntity(
            name = name.trim().ifBlank { "Sample" },
            nameKey = name.trim().lowercase(),
            filePath = audioFile.absolutePath,
            durationMs = buffer.durationMs,
            sampleRate = buffer.sampleRate,
            channelCount = if (buffer.right == null) 1 else 2,
            frameCount = buffer.frames.toLong(),
            category = category.id,
            tags = tags.joinToString(","),
            sourceTrackTitle = sourceTrackTitle,
            sourceArtist = sourceArtist,
            sourceTimestampMs = sourceTimestampMs,
            captureSource = captureSource?.id,
            bpm = bpm,
            musicalKey = musicalKey,
            waveformPath = waveformPath,
            createdAt = stamp,
        )
        val id = dao.upsert(entity)
        toRef(entity.copy(id = id))
    }

    /**
     * Imports an existing audio file. Anything the platform can decode is
     * accepted and re-encoded into the store's own WAV, so the library owns
     * one format and a sample cannot break because the user moved or deleted
     * the file they imported it from.
     */
    suspend fun import(
        uri: Uri,
        name: String,
        category: SampleCategory = SampleCategory.USER,
    ): SampleRef? = withContext(Dispatchers.IO) {
        val decoded = runCatching { PcmDecoder.decode(context, uri) }
            .onFailure { Log.e(TAG, "import failed for $uri", it) }
            .getOrNull() ?: return@withContext null
        save(
            buffer = SampleEdits.Buffer(decoded.left, decoded.right, decoded.sampleRate),
            name = name,
            category = category,
        )
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        val existing = dao.byId(id) ?: return@withContext
        val trimmed = name.trim().ifBlank { existing.name }
        dao.update(existing.copy(name = trimmed, nameKey = trimmed.lowercase()))
    }

    suspend fun setCategory(id: Long, category: SampleCategory) = withContext(Dispatchers.IO) {
        dao.byId(id)?.let { dao.update(it.copy(category = category.id)) }
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        dao.byId(id)?.let { dao.update(it.copy(favorite = favorite)) }
    }

    suspend fun setGain(id: Long, gain: Float) = withContext(Dispatchers.IO) {
        dao.byId(id)?.let { dao.update(it.copy(gain = gain.coerceIn(0f, 4f))) }
    }

    /**
     * Deletes a sample: its files, its row, and its references.
     *
     * Channels that used it are emptied rather than deleted — see
     * [SampleDao.detachFromChannels] for why a cascade here would be
     * destructive. The order matters: detach first, so no pattern can be
     * loaded pointing at a row that is already gone.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val existing = dao.byId(id) ?: return@withContext
        dao.detachFromChannels(id)
        dao.delete(id)
        runCatching { File(existing.filePath).delete() }
        existing.waveformPath?.let { runCatching { File(it).delete() } }
    }

    /**
     * Removes store files that no row points at.
     *
     * A crash between writing the WAV and inserting the row leaves an orphan;
     * without a sweep those accumulate silently in the user's storage.
     */
    suspend fun pruneOrphans(): Int = withContext(Dispatchers.IO) {
        val audioPaths = dao.allAudioPaths().toHashSet()
        val waveformPaths = dao.allWaveformPaths().toHashSet()
        var removed = 0
        audioDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in audioPaths && file.delete()) removed += 1
        }
        waveformDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in waveformPaths && file.delete()) removed += 1
        }
        removed
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun writePeaks(target: File, peaks: FloatArray) {
        target.parentFile?.mkdirs()
        DataOutputStream(target.outputStream().buffered()).use { out ->
            out.writeInt(peaks.size)
            for (value in peaks) out.writeFloat(value)
        }
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").replace(Regex("\\s+"), "_")

    private fun toRef(entity: SampleEntity) = SampleRef(
        id = entity.id,
        name = entity.name,
        filePath = entity.filePath,
        durationMs = entity.durationMs,
        sampleRate = entity.sampleRate,
        channelCount = entity.channelCount,
        frameCount = entity.frameCount,
        category = SampleCategory.fromId(entity.category),
        tags = entity.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        sourceTrackTitle = entity.sourceTrackTitle,
        sourceArtist = entity.sourceArtist,
        sourceTimestampMs = entity.sourceTimestampMs,
        captureSource = entity.captureSource?.let(CaptureSource::fromId),
        bpm = entity.bpm,
        musicalKey = entity.musicalKey,
        gain = entity.gain,
        waveformPath = entity.waveformPath,
        favorite = entity.favorite,
        createdAt = entity.createdAt,
    )

    companion object {
        private const val TAG = "SampleRepository"
        private const val MAX_PEAK_FLOATS = 8192
    }
}
