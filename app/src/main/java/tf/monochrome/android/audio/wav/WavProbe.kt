package tf.monochrome.android.audio.wav

/**
 * What a WAV file's `fmt ` chunk says, read from the head of the file.
 *
 * Exists for one fact Media3 1.5's WAV extractor does not check: whether a
 * WAVE_FORMAT_EXTENSIBLE file holds integer PCM or IEEE float. The extractor
 * maps every extensible file to integer PCM of its bit depth
 * (WavUtil.getPcmEncodingForType), so a 32-bit float WAV wrapped as
 * extensible — the way most DAWs and editors write one — had its float bits
 * read as 32-bit integers and played as full-scale static. The sub-format GUID
 * that says which it is sits in the extension of the `fmt ` chunk; its first
 * two bytes are the plain format tag (1 = PCM, 3 = float).
 *
 * Pure, for the tests: bytes in, facts out.
 */
data class WavFormatInfo(
    /** wFormatTag: 1 PCM, 3 IEEE float, 0xFFFE extensible, … */
    val formatTag: Int,
    /** For extensible files, the sub-format's tag (1 PCM, 3 float); else [formatTag]. */
    val effectiveTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
) {
    val isExtensible: Boolean get() = formatTag == TAG_EXTENSIBLE
    val isFloat: Boolean get() = effectiveTag == TAG_FLOAT

    /** The case Media3 gets wrong: extensible, holding float. */
    val isExtensibleFloat: Boolean get() = isExtensible && isFloat

    companion object {
        const val TAG_PCM = 0x0001
        const val TAG_FLOAT = 0x0003
        const val TAG_EXTENSIBLE = 0xFFFE
    }
}

object WavProbe {

    /**
     * Parses the RIFF/RF64 header in [bytes] (the first [length] bytes of the
     * file) up to the `fmt ` chunk. Null when this is not a WAV file or the
     * chunk is not within [length].
     */
    fun readFormat(bytes: ByteArray, length: Int = bytes.size): WavFormatInfo? {
        if (length < 12) return null
        val riff = fourCc(bytes, 0)
        if ((riff != "RIFF" && riff != "RF64") || fourCc(bytes, 8) != "WAVE") return null
        var pos = 12
        while (pos + 8 <= length) {
            val id = fourCc(bytes, pos)
            val size = u32(bytes, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (size < 16 || body + 16 > length) return null
                val tag = u16(bytes, body)
                val channels = u16(bytes, body + 2)
                val rate = u32(bytes, body + 4).toInt()
                val bits = u16(bytes, body + 14)
                var effective = tag
                // WAVEFORMATEXTENSIBLE: cbSize(2) validBits(2) channelMask(4)
                // then the 16-byte SubFormat GUID, tag in its first two bytes.
                if (tag == WavFormatInfo.TAG_EXTENSIBLE && size >= 40 && body + 26 <= length) {
                    effective = u16(bytes, body + 24)
                }
                return WavFormatInfo(tag, effective, channels, rate, bits)
            }
            // Chunks are padded to an even length; the pad is not in the size.
            val next = body + size + (size and 1L)
            if (next > Int.MAX_VALUE || next <= pos) return null
            pos = next.toInt()
        }
        return null
    }

    private fun fourCc(b: ByteArray, at: Int): String =
        String(b, at, 4, Charsets.US_ASCII)

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
