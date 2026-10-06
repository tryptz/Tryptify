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
