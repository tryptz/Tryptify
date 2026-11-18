package tf.monochrome.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.CompactCurveSerializer
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.domain.model.Headphone
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Headphone measurements kept on the phone, so the headphone list and every
 * AutoEQ fit work with no connection (`HeadphoneMeasurementDownloader` fills
 * it, [HeadphoneRepository] reads it first).
 *
 * Kept under `noBackupFilesDir`: tens of megabytes anyone can fetch again have
 * no place in a backup, and it is not a cache the system may empty while the
 * listener counts on it. One gzipped file per measurement, written whole and
 * renamed into place, so a run that stops mid-file leaves nothing half-written
 * and the next run carries on from the files that are there.
 *
 * What is stored is the parsed curve (`EqDataParser`'s points), not the file:
 * an AutoEq result is 42 KB of eleven columns of which the fit reads one.
 */
@Singleton
class HeadphoneOfflineStore(private val root: File) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "headphone_measurements"))

    /**
     * One measurement. AutoEq publishes one file, [curve]; squig.link one per
     * ear, [left] and [right], each with the sample that answered ("L" or
     * "L1"), as `SquiglinkApi.fetchMeasurementChannel` reports it. All empty
     * marks a measurement whose files are not published, so a later run does
     * not ask again.
     */
    @Serializable
    data class StoredMeasurement(
        @Serializable(with = CompactCurveSerializer::class)
        val curve: List<FrequencyPoint> = emptyList(),
        val left: StoredChannel? = null,
        val right: StoredChannel? = null,
    ) {
        /** The curve the single-file fetch gives: squig.link's left ear, else its right. */
        val single: List<FrequencyPoint>?
            get() = curve.ifEmpty { left?.curve ?: right?.curve }?.takeIf { it.isNotEmpty() }

        fun channel(channel: String): StoredChannel? = when (channel) {
            "L" -> left
            "R" -> right
            else -> null
        }?.takeIf { it.curve.isNotEmpty() }
    }

    @Serializable
    data class StoredChannel(
        val sample: String,
        @Serializable(with = CompactCurveSerializer::class)
        val curve: List<FrequencyPoint>,
    )

    /** What is kept, for the download dialog. */
    data class Summary(val measurements: Int, val bytes: Long, val savedAt: Long?) {
        val isEmpty: Boolean get() = measurements == 0
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val curves = File(root, "curves")
    private val indexFile = File(root, "index.json.gz")

    /** The file name for [m]: its source and file, hashed, so any name is a safe file name. */
    fun keyOf(m: AutoEqMeasurement): String {
        val id = listOf(m.target, m.host, m.path, m.fileName).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray(Charsets.UTF_8))
        // By hand rather than String.format, which follows the phone's locale:
        // a key must never change, or the files saved under it are orphaned.
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(hex[(b.toInt() shr 4) and 0xF])
                append(hex[b.toInt() and 0xF])
            }
        }
    }

    private fun fileOf(m: AutoEqMeasurement) = File(curves, keyOf(m) + ".gz")

    fun has(m: AutoEqMeasurement): Boolean = fileOf(m).isFile

    fun read(m: AutoEqMeasurement): StoredMeasurement? {
        val file = fileOf(m)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(StoredMeasurement.serializer(), readGz(file))
        } catch (_: Exception) {
            null
        }
    }

    fun write(m: AutoEqMeasurement, stored: StoredMeasurement) {
        writeGz(fileOf(m), json.encodeToString(StoredMeasurement.serializer(), stored))
    }

    /** The headphone list as last saved, or null if none is. */
    fun readIndex(): List<Headphone>? {
        if (!indexFile.isFile) return null
        return try {
            json.decodeFromString(ListSerializer(Headphone.serializer()), readGz(indexFile))
        } catch (_: Exception) {
            null
        }
    }

    fun writeIndex(headphones: List<Headphone>) {
        writeGz(indexFile, json.encodeToString(ListSerializer(Headphone.serializer()), headphones))
    }

    /**
     * The saved list's headphones with only the measurements [keep] picks, the
     * ones without any left out: one source's part of the list, for when that
     * source cannot be reached.
     */
    fun indexPart(keep: (AutoEqMeasurement) -> Boolean): List<Headphone> =
        readIndex().orEmpty().mapNotNull { hp ->
            val ms = hp.measurements.filter(keep)
            if (ms.isEmpty()) null else hp.copy(measurements = ms)
        }

    fun summary(): Summary {
        val files = curves.listFiles { f -> f.isFile && f.name.endsWith(".gz") }.orEmpty()
        val bytes = files.sumOf { it.length() } + (if (indexFile.isFile) indexFile.length() else 0L)
        val savedAt = indexFile.takeIf { it.isFile }?.lastModified()
        return Summary(measurements = files.size, bytes = bytes, savedAt = savedAt)
    }

    fun clear() {
        root.deleteRecursively()
    }

    private fun readGz(file: File): String =
        GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun writeGz(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("Could not save ${file.name}")
            }
        }
    }
}
