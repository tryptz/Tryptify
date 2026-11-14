package tf.monochrome.android.audio.atmos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tf.monochrome.android.domain.model.AtmosRoom
import tf.monochrome.android.domain.model.CustomRoom
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.RoomKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rooms the headphone render can play in besides its own: measured ones,
 * kept as files, and the one the listener describes.
 *
 * A measured room comes as a SOFA file of tens of megabytes that takes
 * seconds to read. It is read once, when it is added ([addMeasured]), and
 * only what the render needs of it is kept (a `.room` file under a megabyte:
 * the prepared response and what the file says of the room). Playback never
 * sees the SOFA.
 */
@Singleton
class AtmosRoomStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "rooms")

    private val _current = MutableStateFlow<AtmosRoom?>(null)

    /** The room in use; null for the built-in one. The Atmos page draws it. */
    val current: StateFlow<AtmosRoom?> = _current.asStateFlow()

    // The last room resolved, by what it was resolved from.
    private var resolvedKey: String? = null
    private var resolved: AtmosRoom? = null

    /** The measured rooms that have been added, by name. */
    fun measured(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * Reads a room out of a SOFA file's [sofa] bytes and keeps it as [name].
     * Null when the file holds no room the render can use (an HRTF set, an
     * anechoic measurement, a file the reader cannot open).
     */
    suspend fun addMeasured(name: String, sofa: ByteArray): File? = withContext(Dispatchers.Default) {
        if (!AtmosNative.isAvailable) return@withContext null
        val packed = runCatching { AtmosNative.nativePrepareRoomSofa(sofa) }.getOrNull()
        if (AtmosRoom.of(packed) == null) return@withContext null
        val file = File(dir, fileName(name))
        runCatching {
            dir.mkdirs()
            val bytes = ByteBuffer.allocate(packed!!.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(packed)
            file.writeBytes(bytes.array())
            file
        }.getOrNull()
    }

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    /**
     * The room [profile] asks for, made ready: null for the built-in one, and
     * for a measured room whose file is gone or a custom one that cannot be
     * worked out (the render then plays its own). Slow the first time for a
     * custom room; remembered after.
     */
    suspend fun resolve(profile: RendererProfile): AtmosRoom? = withContext(Dispatchers.Default) {
        val key = when (profile.roomKind) {
            RoomKind.BUILT_IN -> null
            RoomKind.MEASURED -> profile.roomFile?.let { "file:$it" }
            RoomKind.CUSTOM -> "custom:${profile.customRoom.clamped()}"
        }
        synchronized(this@AtmosRoomStore) {
            if (key == resolvedKey) return@withContext resolved.also { _current.value = it }
        }
        val room = when {
            key == null -> null
            profile.roomKind == RoomKind.MEASURED -> read(File(profile.roomFile!!))
            else -> build(profile.customRoom.clamped())
        }
        synchronized(this@AtmosRoomStore) {
            resolvedKey = key
            resolved = room
        }
        _current.value = room
        room
    }

    private fun read(file: File): AtmosRoom? = runCatching {
        val bytes = file.readBytes()
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        AtmosRoom.of(floats)
    }.getOrNull()

    private fun build(room: CustomRoom): AtmosRoom? {
        if (!AtmosNative.isAvailable) return null
        return AtmosRoom.of(
            runCatching {
                AtmosNative.nativeBuildBoxRoom(room.width, room.depth, room.height, room.damping)
            }.getOrNull()
        )
    }

    companion object {
        const val EXTENSION = ".room"

        /** A file name for a room called [name]: its letters and digits, nothing a path could use. */
        fun fileName(name: String): String {
            val base = name.removeSuffix(".sofa").removeSuffix(".SOFA")
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' || it == '.') it else '_' }
                .joinToString("").trim().trim('.').take(80).ifEmpty { "room" }
            return base + EXTENSION
        }

        /** What a room's file is shown as. */
        fun displayName(file: File): String = file.name.removeSuffix(EXTENSION)
    }
}
