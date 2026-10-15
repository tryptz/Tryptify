package tf.monochrome.android.audio.mp4

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs a Media3 extractor over a real file on the JVM, as ProgressiveMediaPeriod
 * would: reads until it asks to seek, reopens the input there, and records the
 * seek map and every sample time it outputs. No Android classes involved, so
 * no device is needed to see whether a file can be seeked.
 */
internal class ExtractorHarness(file: File, private val extractor: Extractor) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    var seekMap: SeekMap? = null
        private set
    val sampleTimesUs = ArrayList<Long>()

    private val reader = object : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = raf.read(buffer, offset, length)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
    }

    private val track = object : TrackOutput {
        private val scratch = ByteArray(64 * 1024)
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw java.io.EOFException()
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            sampleTimesUs += timeUs
        }
    }

    init {
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { this@ExtractorHarness.seekMap = seekMap }
        })
    }

    private fun inputAt(position: Long): ExtractorInput {
        raf.seek(position)
        return DefaultExtractorInput(reader, position, raf.length())
    }

    /** Reads from [position] until the extractor has output [samples] samples, or the end. */
    fun readFrom(position: Long, samples: Int = Int.MAX_VALUE) {
        var input = inputAt(position)
        val holder = PositionHolder()
        val target = sampleTimesUs.size + samples
        var steps = 0
        while (sampleTimesUs.size < target && steps++ < 100_000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return
                Extractor.RESULT_SEEK -> input = inputAt(holder.position)
            }
        }
    }

    /** What ProgressiveMediaPeriod does to seek: ask the map, tell the extractor, read on. */
    fun seekTo(timeUs: Long, samples: Int = 1) {
        val point = seekMap!!.getSeekPoints(timeUs).first
        sampleTimesUs.clear()
        extractor.seek(point.position, timeUs)
        readFrom(point.position, samples)
    }

    override fun close() = raf.close()
}
