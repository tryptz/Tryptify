// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Fetching bytes, kept behind an interface.
 *
 * Not for ceremony: everything interesting about installing a model — resuming
 * a partial download, verifying it, refusing to install a corrupt one — is
 * logic that should be tested, and none of it should need a network to test.
 * The Ktor implementation is a dozen lines; the part worth being careful about
 * is on the other side of this boundary.
 */
interface ModelTransport {

    suspend fun fetchText(url: String): String

    /**
     * Streams [url] from [offset] into [sink], calling [onBytes] with the
     * running total written *in this call*.
     *
     * Returns the number of bytes written. Implementations must honour
     * [offset] with a range request, and must not write anything before the
     * offset — a server that ignores the range header and restarts from zero
     * would otherwise corrupt a resumed file, which is the classic way
     * resumable downloads produce a file of the right length and the wrong
     * contents.
     */
    suspend fun download(
        url: String,
        offset: Long,
        sink: OutputStream,
        onBytes: (Long) -> Unit,
    ): Long
}

/** Where an install got to. */
sealed interface InstallResult {
    data class Installed(val model: StemModel, val file: File) : InstallResult
    data class Failed(val reason: String, val recoverable: Boolean) : InstallResult
}

data class InstallProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val stage: String,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * Downloads, verifies and installs stem models.
 *
 * The rule the whole class is built around is that **a model is either
 * installed and verified or it is not present at all.** There is no state where
 * a half-written file looks usable, because the failure that produces is the
 * worst kind: inference runs, produces noise, and nothing points at the
 * download that went wrong three days earlier.
 *
 * So bytes land in a `.part` file, the hash is checked while it is still a
 * `.part` file, and only a file that matched its manifest hash is renamed into
 * place. The record of what is installed is written last, which means a crash
 * at any point leaves either "nothing installed" or "installed and correct" —
 * never "installed and wrong".
 */
class StemModelManager(
    private val root: File,
    private val transport: ModelTransport,
    /** Free space on the volume holding [root]. Injected so it can be faked. */
    private val freeBytes: () -> Long,
) {

    /**
     * Headroom demanded on top of the archive itself.
     *
     * A download that fills the disk to the last byte leaves a device that
     * cannot write anything at all, which is a worse outcome than refusing the
     * download. 64 MB is enough for the extraction and for the rest of the
     * system to keep functioning.
     */
    private val headroomBytes = 64L * 1024 * 1024

    private val recordFile: File get() = File(root, "installed.json")

    fun modelFile(model: StemModel): File = File(root, "${model.id}-${model.version}.bin")

    private fun partFile(model: StemModel): File = File(root, "${model.id}-${model.version}.part")

    // ── state ───────────────────────────────────────────────────────────

    /** The installed model, or null. Cheap: reads one small file. */
    fun installed(): StemModel? {
        val record = recordFile.takeIf { it.isFile }?.readText() ?: return null
        val model = StemModelCatalog.parse(record)?.models?.firstOrNull() ?: return null
        val file = modelFile(model)
        // A record without its file, or with a file of the wrong length, is a
        // half-finished install or a user who cleared app data. Either way it
        // is not installed, and saying so is what makes the UI offer the
        // download again instead of failing at inference time.
        if (!file.isFile || file.length() != model.sizeBytes) return null
        return model
    }

    fun isInstalled(model: StemModel): Boolean = installed()?.let {
        it.id == model.id && it.sha256 == model.sha256
    } == true

    /** Bytes already fetched for [model], so the UI can say "resuming". */
    fun resumeOffset(model: StemModel): Long {
        val part = partFile(model)
        if (!part.isFile) return 0L
        val length = part.length()
        // A part file longer than the finished article cannot be a prefix of
        // it. Something else wrote there, or the manifest changed under us.
        return if (length in 1 until model.sizeBytes) length else 0L
    }

    fun installedBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    // ── the catalogue ───────────────────────────────────────────────────

    suspend fun fetchCatalog(manifestUrl: String): Result<StemModelCatalog> = runCatching {
        val text = transport.fetchText(manifestUrl)
        StemModelCatalog.parse(text) ?: error("Manifest could not be parsed")
    }

    // ── install ─────────────────────────────────────────────────────────

    suspend fun install(
        model: StemModel,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult {
        if (isInstalled(model)) return InstallResult.Installed(model, modelFile(model))

        if (!root.isDirectory && !root.mkdirs()) {
            return InstallResult.Failed("Could not create the model directory", recoverable = false)
        }

        val offset = resumeOffset(model)
        val part = partFile(model)
        if (offset == 0L && part.exists()) part.delete()

        // Only the remainder has to fit — a resumed download does not need
        // room for the bytes already on disk.
        val needed = model.sizeBytes - offset + headroomBytes
        val free = freeBytes()
        if (free < needed) {
            return InstallResult.Failed(
                "Needs ${formatBytes(needed)} free, ${formatBytes(free)} available",
                recoverable = true,
            )
        }

        onProgress(
            InstallProgress(
                offset,
                model.sizeBytes,
                if (offset > 0L) "Resuming download" else "Downloading",
            ),
        )

        val downloaded = runCatching {
            // Append when resuming, truncate when starting over. `File
            // .outputStream()` is always the truncating one, so using it here
            // would wipe the partial download this method exists to keep.
            java.io.FileOutputStream(part, offset > 0L).use { out ->
                transport.download(model.url, offset, out) { written ->
                    onProgress(
                        InstallProgress(offset + written, model.sizeBytes, "Downloading"),
                    )
                }
            }
        }

        downloaded.exceptionOrNull()?.let { error ->
            // The part file stays. A dropped connection is the normal case and
            // throwing away 200 MB because a train went into a tunnel would be
            // hostile.
            if (error is kotlinx.coroutines.CancellationException) throw error
            return InstallResult.Failed(
                error.message ?: "Download failed",
                recoverable = true,
            )
        }

        coroutineContext.ensureActive()

        if (part.length() != model.sizeBytes) {
            val got = part.length()
            part.delete()
            return InstallResult.Failed(
                "Expected ${formatBytes(model.sizeBytes)}, got ${formatBytes(got)}",
                recoverable = true,
            )
        }

        onProgress(InstallProgress(model.sizeBytes, model.sizeBytes, "Verifying"))
        val digest = sha256(part) { done ->
            onProgress(InstallProgress(done, model.sizeBytes, "Verifying"))
        }

        if (!digest.equals(model.sha256, ignoreCase = true)) {
            // A hash mismatch means these bytes are not the model, and keeping
            // them would mean resuming into the same wrong file forever. This
            // is the one case where deleting the partial download is the kind
            // thing to do.
            part.delete()
            return InstallResult.Failed("Checksum did not match — download discarded", recoverable = true)
        }

        val target = modelFile(model)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return InstallResult.Failed("Could not move the model into place", recoverable = false)
        }

        // Written last, on purpose. Until this exists nothing considers the
        // model installed, so a crash one line earlier leaves an unreferenced
        // file rather than a claim that cannot be honoured.
        recordFile.writeText(recordJson(model))

        // Sweep up the previous version and any stale part files. Without this
        // every update doubles the footprint on disk and nothing ever reclaims
        // it, because only the current version is named in the record.
        root.listFiles()?.forEach { file ->
            if (file != target && file != recordFile) file.delete()
        }

        onProgress(InstallProgress(model.sizeBytes, model.sizeBytes, "Ready"))
        return InstallResult.Installed(model, target)
    }

    /** Removes the model and its record. Safe to call when nothing is installed. */
    fun uninstall() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Throws away a partial download without touching an installed model. */
    fun discardPartial(model: StemModel) {
        partFile(model).delete()
    }

    /**
     * Re-hashes the installed file.
     *
     * Not run at startup — hashing hundreds of megabytes on every launch would
     * cost more than it catches. Offered as an explicit action for when
     * something looks wrong.
     */
    suspend fun verifyInstalled(onProgress: (Float) -> Unit = {}): Boolean {
        val model = installed() ?: return false
        val file = modelFile(model)
        val digest = sha256(file) { done ->
            onProgress(if (model.sizeBytes > 0) done.toFloat() / model.sizeBytes else 0f)
        }
        return digest.equals(model.sha256, ignoreCase = true)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private suspend fun sha256(file: File, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                done += read
                onProgress(done)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recordJson(model: StemModel): String = buildString {
        append("""{"schemaVersion":1,"models":[{""")
        append(""""id":${quote(model.id)},""")
        append(""""name":${quote(model.name)},""")
        append(""""version":${quote(model.version)},""")
        append(""""format":${quote(model.format.id)},""")
        append(""""backend":${quote(model.backend.name)},""")
        append(""""url":${quote(model.url)},""")
        append(""""sha256":${quote(model.sha256)},""")
        append(""""sizeBytes":${model.sizeBytes},""")
        append(""""license":${quote(model.license.id)},""")
        append(""""sourceModel":${quote(model.sourceModel)},""")
        model.attribution?.let { append(""""attribution":${quote(it)},""") }
        append(""""stems":[${model.stems.joinToString(",") { quote(it.id) }}],""")
        append(""""modelSampleRate":${model.modelSampleRate},""")
        append(""""segmentFrames":${model.segmentFrames},""")
        append(""""minimumAndroid":${model.minimumAndroid},""")
        append(""""abi":${quote(model.abi)}""")
        append("}]}")
    }

    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000L -> "%.0f kB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
