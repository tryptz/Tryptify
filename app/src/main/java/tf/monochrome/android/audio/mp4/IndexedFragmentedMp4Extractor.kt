package tf.monochrome.android.audio.mp4

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import tf.monochrome.android.data.local.tags.Boxes
import tf.monochrome.android.data.local.tags.string4
import tf.monochrome.android.data.local.tags.u32
import tf.monochrome.android.data.local.tags.u64

/**
 * [FragmentedMp4Extractor], made able to seek in a fragmented file that has no
 * index.
 *
 * Apple's files are fragmented MP4 — a `moov`, then `moof` + `mdat` pairs —
 * and carry no `sidx`. Media3 seeks a fragmented file only by its `sidx`;
 * without one it declares the file unseekable with no known length, so an ALAC
 * file from Apple would not move when the seek bar was dragged.
 *
 * So before the delegate reads anything, this walks the file's top-level boxes
 * once, reading only `moov` and each `moof` (a few hundred bytes) and hopping
 * over the audio, and notes where each fragment starts and the time it starts
 * at (its `tfdt`, or the running sum of its samples). That is exactly what a
 * `sidx` would have said, so the delegate's unseekable map is replaced by a
 * [ChunkIndex] built from it, and the delegate seeks from it the way it seeks
 * from a `sidx`: to a fragment's `moof`, then on to the time asked for.
 *
 * A file with a `sidx` is left to the delegate as it was, and so is one whose
 * length is unknown, or that this cannot make sense of: the worst case is the
 * behaviour before, never a file that will not play.
 */
@OptIn(UnstableApi::class)
class IndexedFragmentedMp4Extractor(private val delegate: FragmentedMp4Extractor) : Extractor {

    private var scanning = true
    private var index: ChunkIndex? = null
    private val scan = FragmentScan()
    private val header = ByteArray(16)

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                val built = index
                output.seekMap(if (!seekMap.isSeekable && built != null) built else seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (scanning) {
            val result = scan(input, seekPosition)
            if (result != CONTINUE) return result
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        // A reload from the top (a retry) scans again; any other seek comes
        // after the scan, from the index it built.
        if (scanning && position == 0L) scan.reset() else scanning = false
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    /**
     * Walks top-level boxes from where [input] is. Returns [CONTINUE] when the
     * delegate may read from here, or a result to hand back (a hop over audio,
     * or the jump back to the start once the scan is done).
     */
    private fun scan(input: ExtractorInput, holder: PositionHolder): Int {
        val length = input.length
        if (length == C.LENGTH_UNSET.toLong()) return finish(input, holder, built = null)
        while (true) {
            val at = input.position
            if (at + 8 > length) return finish(input, holder, scan.build(length))
            input.readFully(header, 0, 8)
            var size = u32(header, 0)
            val type = string4(header, 4)
            var headerSize = 8
            if (size == 1L) {
                input.readFully(header, 8, 8)
                size = u64(header, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = length - at
            }
            if (size < headerSize || at + size > length) return finish(input, holder, scan.build(length))
            val payload = size - headerSize
            when (type) {
                // An index of its own: the delegate needs no help.
                "sidx" -> return finish(input, holder, built = null)
                "moov", "moof" -> {
                    if (payload > MAX_HEADER_BOX) return finish(input, holder, built = null)
                    val bytes = ByteArray(payload.toInt())
                    input.readFully(bytes, 0, bytes.size)
                    if (type == "moov") scan.moov(bytes) else scan.moof(at, bytes)
                }
                else -> {
                    // Audio, mostly. A short hop is read through; a long one
                    // reopens the input past it.
                    if (payload <= READ_THROUGH) {
                        input.skipFully(payload.toInt())
                    } else {
                        holder.position = at + size
                        return Extractor.RESULT_SEEK
                    }
                }
            }
        }
    }

    private fun finish(input: ExtractorInput, holder: PositionHolder, built: ChunkIndex?): Int {
        scanning = false
        index = built
        if (built != null) {
            Log.i(TAG, "fragmented MP4 without an index: ${built.length} fragments indexed, " +
                "${built.durationUs / 1000} ms, now seekable")
        }
        // The delegate starts from the top, as it would have without the scan.
        if (input.position == 0L) return CONTINUE
        holder.position = 0L
        return Extractor.RESULT_SEEK
    }

    private companion object {
        const val TAG = "IndexedFragMp4"
        const val CONTINUE = Int.MIN_VALUE

        /** A moov or moof past this is not a music file's. */
        const val MAX_HEADER_BOX = 16L * 1024 * 1024

        /** Hops shorter than this are read through rather than reopened past. */
        const val READ_THROUGH = 256L * 1024
    }
}

/**
 * The fragments of one audio track, as their `moof`s describe them: where each
 * starts in the file, and when. Kept apart from the extractor so it can be
 * tested on bytes alone.
 */
internal class FragmentScan {
    private var trackId = -1L
    private var timescale = 0L
    private var defaultDuration = 0L
    private val offsets = ArrayList<Long>()
    private val startTicks = ArrayList<Long>()
    private val durationTicks = ArrayList<Long>()
    private var nextTicks = 0L

    fun reset() {
        trackId = -1
        timescale = 0
        defaultDuration = 0
        offsets.clear()
        startTicks.clear()
        durationTicks.clear()
        nextTicks = 0
    }

    /** The audio track's id, timescale and default sample duration, from `moov`. */
    fun moov(b: ByteArray) {
        val top = Boxes(b, 0, b.size)
        for (trak in top.all("trak")) {
            val trakBoxes = Boxes(b, trak.payload, trak.end)
            val mdia = trakBoxes.find("mdia") ?: continue
            val mdiaBoxes = Boxes(b, mdia.payload, mdia.end)
            val hdlr = mdiaBoxes.find("hdlr") ?: continue
            if (string4(b, hdlr.payload + 8) != "soun") continue
            val tkhd = trakBoxes.find("tkhd") ?: continue
            trackId = if (b[tkhd.payload].toInt() == 1) u32(b, tkhd.payload + 20) else u32(b, tkhd.payload + 12)
            val mdhd = mdiaBoxes.find("mdhd") ?: continue
            timescale = if (b[mdhd.payload].toInt() == 1) u32(b, mdhd.payload + 20) else u32(b, mdhd.payload + 12)
            break
        }
        val mvex = top.find("mvex") ?: return
        for (trex in Boxes(b, mvex.payload, mvex.end).all("trex")) {
            // version/flags(4) track_ID(4) sample_description_index(4) default_sample_duration(4)
            if (u32(b, trex.payload + 4) == trackId) defaultDuration = u32(b, trex.payload + 12)
        }
    }

    /** One fragment: its start from `tfdt` (or the running sum), its length from `trun`. */
    fun moof(offset: Long, b: ByteArray) {
        if (trackId < 0) return
        for (traf in Boxes(b, 0, b.size).all("traf")) {
            val boxes = Boxes(b, traf.payload, traf.end)
            val tfhd = boxes.find("tfhd") ?: continue
            val flags = u32(b, tfhd.payload) and 0xFFFFFF
            if (u32(b, tfhd.payload + 4) != trackId) continue
            var at = tfhd.payload + 8
            if (flags and 0x01 != 0L) at += 8 // base_data_offset
            if (flags and 0x02 != 0L) at += 4 // sample_description_index
            val sampleDuration = if (flags and 0x08 != 0L) u32(b, at) else defaultDuration
            val start = boxes.find("tfdt")?.let { tfdt ->
                if (b[tfdt.payload].toInt() == 1) u64(b, tfdt.payload + 4) else u32(b, tfdt.payload + 4)
            } ?: nextTicks
            var total = 0L
            for (trun in boxes.all("trun")) total += trunDuration(b, trun.payload, trun.end, sampleDuration)
            offsets += offset
            startTicks += start
            durationTicks += total
            nextTicks = start + total
            return
        }
    }

    /** The sum of a `trun`'s sample durations: its own, or [defaultDuration] each. */
    private fun trunDuration(b: ByteArray, payload: Int, end: Int, defaultDuration: Long): Long {
        val flags = u32(b, payload) and 0xFFFFFF
        val count = u32(b, payload + 4)
        if (flags and 0x100 == 0L) return count * defaultDuration
        var at = payload + 8
        if (flags and 0x01 != 0L) at += 4 // data_offset
        if (flags and 0x04 != 0L) at += 4 // first_sample_flags
        val stride = 4 * listOf(0x100L, 0x200L, 0x400L, 0x800L).count { flags and it != 0L }
        var total = 0L
        for (i in 0 until count) {
            if (at + 4 > end) break
            total += u32(b, at)
            at += stride
        }
        return total
    }

    /** The index, or null with nothing to index. [length] closes the last fragment. */
    fun build(length: Long): ChunkIndex? {
        val n = offsets.size
        if (n == 0 || timescale <= 0) return null
        val sizes = IntArray(n) { i ->
            val end = if (i + 1 < n) offsets[i + 1] else length
            (end - offsets[i]).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
        val times = LongArray(n) { ticksToUs(startTicks[it]) }
        val durations = LongArray(n) { i ->
            // A fragment lasts until the next starts; the last, as its samples say.
            val ticks = if (i + 1 < n) startTicks[i + 1] - startTicks[i] else durationTicks[i]
            ticksToUs(ticks.coerceAtLeast(0))
        }
        return ChunkIndex(sizes, offsets.toLongArray(), durations, times)
    }

    private fun ticksToUs(ticks: Long): Long = ticks * 1_000_000L / timescale
}
