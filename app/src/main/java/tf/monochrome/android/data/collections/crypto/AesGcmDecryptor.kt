package tf.monochrome.android.data.collections.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AesGcmDecryptor @Inject constructor() {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
    }

    /**
     * Decrypt data encrypted with AES-256-GCM.
     * Expected format: IV (12 bytes) + ciphertext + tag (16 bytes)
     */
    fun decrypt(encryptedData: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == 32) { "AES-256 requires a 32-byte key" }
        require(encryptedData.size > IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            "Encrypted data too short"
        }

        val iv = encryptedData.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = encryptedData.copyOfRange(IV_LENGTH_BYTES, encryptedData.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Decrypt a base64-encoded string.
     */
    fun decryptBase64(base64Data: String, base64Key: String): ByteArray {
        val encryptedBytes = Base64.getMimeDecoder().decode(base64Data)
        val keyBytes = Base64.getMimeDecoder().decode(base64Key)
        return decrypt(encryptedBytes, keyBytes)
    }

    /**
     * Decrypt a URL - returns the decrypted URL as a string.
     * Plaintext URLs are accepted, but encrypted-looking values must decrypt
     * successfully so AES-GCM authentication failures are not masked.
     */
    fun decryptUrl(encryptedUrl: String, key: String): String {
        if (encryptedUrl.looksLikePlainUrl()) return encryptedUrl

        val decrypted = decryptBase64(encryptedUrl, key)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun String.looksLikePlainUrl(): Boolean {
        val lower = trimStart().lowercase()
        return lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
    }
}
