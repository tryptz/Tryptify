// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * RAR (RAR4 and RAR5) through libarchive's readers
 * (`cpp/archive/rar_reader.cpp`, `third_party/libarchive`), for
 * [StyleArchive]. Only regular files are listed; folders, links and
 * encrypted files are passed over natively. What to keep and how much of it
 * is [StyleArchive]'s to decide, as for a ZIP.
 */
internal object RarNative {
    @Volatile private var attempted = false
    @Volatile private var available = false

    fun ensureLoaded(): Boolean {
        if (attempted) return available
        synchronized(this) {
            if (attempted) return available
            available = try {
                System.loadLibrary("monochrome_archive")
                true
            } catch (error: Throwable) {
                runCatching { android.util.Log.w(TAG, "RAR unavailable: ${error.message}") }
                false
            }
            attempted = true
        }
        return available
    }

    /** A RAR open for reading, file by file; closed once, by [close]. */
    class Reader(archive: File) : Closeable {
        private var handle = nativeOpen(archive.path)
        private val size = LongArray(1)

        init {
            if (handle == 0L) throw IOException("out of memory")
            nativeError(handle)?.let { message ->
                close()
                throw IOException(message)
            }
        }

        /** The unpacked size the archive declares for the current file; -1 when it does not. */
        var declaredSize: Long = -1
            private set

        /** The next file's path inside the archive; null at the end. Throws on a damaged archive. */
        fun next(): String? {
            check(handle != 0L)
            val name = nativeNext(handle, size)
            if (name == null) {
                nativeError(handle)?.let { throw IOException(it) }
                return null
            }
            declaredSize = size[0]
            return String(name, Charsets.UTF_8)
        }

        /** The current file's bytes. Closing it leaves the archive open. */
        fun stream(): InputStream = object : InputStream() {
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
                if (len == 0) return 0
                check(handle != 0L)
                val got = nativeRead(handle, b, off, len)
                return when {
                    got > 0 -> got
                    got == 0 -> -1
                    else -> throw IOException(nativeError(handle) ?: "unreadable archive")
                }
            }
        }

        override fun close() {
            val open = handle
            handle = 0L
            if (open != 0L) nativeClose(open)
        }
    }

    private const val TAG = "RarNative"

    @JvmStatic private external fun nativeOpen(path: String): Long
    @JvmStatic private external fun nativeNext(handle: Long, sizeOut: LongArray): ByteArray?
    @JvmStatic private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeError(handle: Long): String?
    @JvmStatic private external fun nativeClose(handle: Long)
}
