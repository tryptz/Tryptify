package tf.monochrome.android.data.downloads

/**
 * The container a downloaded file arrived in, read from its first bytes, and
 * the extension and MIME type it is saved under.
 *
 * Decided from the bytes rather than from the quality asked for, because the
 * service decides what it sends: TIDAL's lossy tiers are AAC in an MP4
 * container (.m4a), Qobuz's and Deezer's are MP3, and a lossless request can
 * still come back lossy. Naming an AAC file .mp3 breaks MediaStore and every
 * other player that trusts the extension.
 *
 * Kept free of Android types so it can be unit tested.
 */
enum class DownloadFormat(val extension: String, val mimeType: String) {
    FLAC("flac", "audio/flac"),
    /** MP4 audio: AAC from TIDAL, ALAC/AAC/E-AC-3 from Apple. */
    M4A("m4a", "audio/mp4"),
    MP3("mp3", "audio/mpeg");

    companion object {
        /**
         * The format [header] (a file's first bytes; 12 are enough) begins.
         * Anything unrecognised is MP3, which is what a non-FLAC download
         * always was before MP4 was told apart.
         */
        fun sniff(header: ByteArray): DownloadFormat = when {
            header.startsWith("fLaC") -> FLAC
            // ISO BMFF: a box size, then the "ftyp" box type at offset 4.
            header.size >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> M4A
            else -> MP3
        }

        private fun ByteArray.startsWith(magic: String): Boolean =
            size >= magic.length && copyOfRange(0, magic.length).contentEquals(magic.toByteArray())
    }
}

/**
 * FLAC metadata repair that tag writers need.
 *
 * The last metadata block of a FLAC file says so in the top bit of its
 * header. A file whose last block leaves it off still decodes — players find
 * the first audio frame by its sync code — but JAudioTagger reads that frame,
 * 0xFF..., as one more block of "type 127" and refuses the file, so the
 * download stays untagged and the library files it under "Unknown Album".
 * TrypT HiFi's DASH-to-FLAC remux could produce exactly that.
 *
 * Kept free of Android types so it can be unit tested.
 */
object FlacMetadata {
    /**
     * The offset of the header byte of the metadata block that should be
     * marked last but is not: the block straight after which [head] (a FLAC
     * file's first bytes) reaches audio. Null when the file is fine, is not
     * FLAC, or [head] ends before the audio can be seen.
     */
    fun unmarkedLastBlock(head: ByteArray): Int? {
        if (head.size < 8 || !head.copyOfRange(0, 4).contentEquals("fLaC".toByteArray())) return null
        var at = 4
        while (at + 4 <= head.size) {
            if (head[at].toInt() and 0x80 != 0) return null
            val length = ((head[at + 1].toInt() and 0xFF) shl 16) or
                ((head[at + 2].toInt() and 0xFF) shl 8) or
                (head[at + 3].toInt() and 0xFF)
            val next = at + 4 + length
            if (next + 2 > head.size) return null
            // A FLAC frame starts with the 14-bit sync code 11111111 111110xx.
            if (head[next].toInt() and 0xFF == 0xFF && head[next + 1].toInt() and 0xFE == 0xF8) return at
            at = next
        }
        return null
    }
}
