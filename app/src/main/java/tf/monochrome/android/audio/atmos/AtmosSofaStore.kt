package tf.monochrome.android.audio.atmos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A listener's .sofa made ready for the render, once.
 *
 * A file is read into a small set of floats ([AtmosNative.nativePrepareSofa]:
 * the renderer's table, and the file's own measurement for each speaker of
 * the 15.1.12 room), and that set is what a pipeline is given. Reading takes
 * a moment for an ordinary file and seconds, with hundreds of megabytes held
 * meanwhile, for one measured every degree (the Aachen KEMAR is 163 MB), so
 * the set is kept on disk beside the cache and a file is read once, not at
 * every start of the app and never on a playback thread.
 *
 * Call it off the main and audio threads.
 */
@Singleton
class AtmosSofaStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.cacheDir, "sofa-sets")

    /** The set for the .sofa at [path], or null when it cannot be read as an HRTF. */
    @Synchronized
    fun prepared(path: String): FloatArray? {
        val file = File(path)
        if (!file.isFile) return null
        val kept = File(dir, keptName(path, file.length(), file.lastModified()))
        read(kept)?.let { return it }
        if (!AtmosNative.isAvailable) return null
        val started = System.nanoTime()
        val set = runCatching { AtmosNative.nativePrepareSofa(path) }.getOrNull() ?: return null
        android.util.Log.i(
            TAG,
            "read ${file.name} (${file.length() / (1024 * 1024)} MB) in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        write(kept, set)
        return set
    }

    private fun read(kept: File): FloatArray? = runCatching {
        if (!kept.isFile || kept.length() == 0L || kept.length() % 4 != 0L) return null
        val bytes = kept.readBytes()
        FloatArray(bytes.size / 4).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
    }.getOrNull()

    private fun write(kept: File, set: FloatArray) {
        runCatching {
            dir.mkdirs()
            // Sets of files that have since been replaced or deleted are not kept for ever.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            val bytes = ByteBuffer.allocate(set.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(set)
            val part = File(dir, kept.name + ".part")
            part.writeBytes(bytes.array())
            if (!part.renameTo(kept)) part.delete()
        }
    }

    internal companion object {
        private const val TAG = "AtmosSofaStore"
        private const val KEEP = 24

        /**
         * A kept set's name: the file it was read from, as it was then. A file
         * that is replaced has another length or time, and is read again. The
         * [FORMAT] goes up when what the reading makes changes.
         */
        const val FORMAT = 1
        fun keptName(path: String, length: Long, modified: Long): String =
            "${Integer.toHexString(path.hashCode())}-$length-$modified-v$FORMAT.set"
    }
}
