package tf.monochrome.android.data.cache

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Undoes Deezer's file encryption, as bytes stream past.
 *
 * `/api/deezer/download` hands back a signed link straight onto Deezer's CDN,
 * and the CDN serves every full-length file "striped": the file is cut into
 * 2048-byte blocks and every third one — blocks 0, 3, 6, … — is Blowfish-CBC
 * encrypted, with a fixed IV and a key derived from the track id. The rest is
 * plain. Written to disk as it arrives, the file starts with ciphertext where
 * "fLaC" should be, and nothing can play it.
 *
 * The output is the same length as the input, so Content-Length and download
 * progress still hold. A trailing block shorter than 2048 bytes is never
 * encrypted and passes through as is.
 *
 * Pure JVM (javax.crypto), so it is unit tested without a device.
 */
class DeezerStripeDecryptor(trackId: Long) {

    private val key = SecretKeySpec(keyFor(trackId), "Blowfish")
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val block = ByteArray(BLOCK)
    private var filled = 0
    private var index = 0L

    /** Feed [len] bytes from [data]; whole decrypted blocks go to [sink]. */
    fun feed(data: ByteArray, off: Int, len: Int, sink: (ByteArray, Int, Int) -> Unit) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val n = minOf(BLOCK - filled, end - pos)
            System.arraycopy(data, pos, block, filled, n)
            filled += n
            pos += n
            if (filled == BLOCK) {
                if (index % 3 == 0L) {
                    cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(IV))
                    cipher.doFinal(block, 0, BLOCK, block, 0)
                }
                sink(block, 0, BLOCK)
                index++
                filled = 0
            }
        }
    }

    /** Flush the short tail block, which is never encrypted. */
    fun finish(sink: (ByteArray, Int, Int) -> Unit) {
        if (filled > 0) sink(block, 0, filled)
        filled = 0
    }

    companion object {
        const val BLOCK = 2048
        private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        private val SECRET = "g4el58wc0zvf9na1".toByteArray()

        /** md5 of the id as hex; key[i] = hex[i] xor hex[i+16] xor secret[i]. */
        internal fun keyFor(trackId: Long): ByteArray {
            val hex = MessageDigest.getInstance("MD5").digest(trackId.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return ByteArray(16) { i -> (hex[i].code xor hex[i + 16].code xor SECRET[i].toInt()).toByte() }
        }
    }
}
