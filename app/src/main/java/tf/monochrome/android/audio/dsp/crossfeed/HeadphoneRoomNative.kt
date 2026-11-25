// SPDX-License-Identifier: GPL-3.0-or-later
package tf.monochrome.android.audio.dsp.crossfeed

import tf.monochrome.android.audio.dsp.DspNativeLoader

/**
 * The Crossfeed page's Room in native code (`dsp/headphone_room.h`): the
 * mixer's Binaural Room on its own, with a listener's HRTF and an LFE.
 * A handle is a `HeadphoneRoom*`; 0 is none.
 */
internal object HeadphoneRoomNative {
    init {
        DspNativeLoader.ensureLoaded()
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)

    /** Builds the room for [sampleRate]. Allocates for a moment. */
    @JvmStatic external fun nativePrepare(handle: Long, sampleRate: Int, maxBlockSize: Int)

    /** One of [RoomSettings.params]'s, by its index. Audio thread. */
    @JvmStatic external fun nativeSetParameter(handle: Long, index: Int, value: Float)

    /**
     * The ears the room is heard through: a set [tf.monochrome.android.audio.atmos.AtmosSofaStore]
     * made, or null for the built-in KU100. False when [packed] is not a set.
     * Builds the room's filters again: never on the audio thread.
     */
    @JvmStatic external fun nativeSetSofa(handle: Long, packed: FloatArray?): Boolean

    /** How many samples late the output is. */
    @JvmStatic external fun nativeLatency(handle: Long): Int

    /** Forgets what it has heard. Audio thread. */
    @JvmStatic external fun nativeReset(handle: Long)

    /** The two channels, [frames] long, in place. Audio thread. */
    @JvmStatic external fun nativeProcess(handle: Long, left: FloatArray, right: FloatArray, frames: Int)
}

/**
 * What [CrossfeedEffect] needs of a room, so its routing can be held by a
 * test without the native library.
 */
internal interface RoomEngine {
    fun prepare(sampleRate: Int)
    fun setParameter(index: Int, value: Float)
    fun setSofa(packed: FloatArray?): Boolean
    fun reset()
    fun process(left: FloatArray, right: FloatArray, frames: Int)
    fun release()
}

/** [RoomEngine] over [HeadphoneRoomNative]. */
internal class NativeRoomEngine private constructor(private var handle: Long) : RoomEngine {
    override fun prepare(sampleRate: Int) {
        if (handle != 0L) HeadphoneRoomNative.nativePrepare(handle, sampleRate, MAX_BLOCK)
    }

    override fun setParameter(index: Int, value: Float) {
        if (handle != 0L) HeadphoneRoomNative.nativeSetParameter(handle, index, value)
    }

    override fun setSofa(packed: FloatArray?): Boolean =
        handle != 0L && HeadphoneRoomNative.nativeSetSofa(handle, packed)

    override fun reset() {
        if (handle != 0L) HeadphoneRoomNative.nativeReset(handle)
    }

    override fun process(left: FloatArray, right: FloatArray, frames: Int) {
        if (handle != 0L) HeadphoneRoomNative.nativeProcess(handle, left, right, frames)
    }

    override fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) HeadphoneRoomNative.nativeDestroy(h)
    }

    companion object {
        /** The most the player hands over at once; the room takes any length. */
        private const val MAX_BLOCK = 8192

        /** A room, or null where the native library is not there (a unit test). */
        fun createOrNull(): RoomEngine? = runCatching {
            val h = HeadphoneRoomNative.nativeCreate()
            if (h == 0L) null else NativeRoomEngine(h)
        }.getOrNull()
    }
}
