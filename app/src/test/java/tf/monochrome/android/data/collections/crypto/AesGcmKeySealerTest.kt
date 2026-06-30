package tf.monochrome.android.data.collections.crypto

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmKeySealerTest {

    @Test
    fun sealSerializesAsVersionedIvCiphertextString() {
        val sealer = AesGcmKeySealer(StaticCollectionMasterKeyBackend())
        val rawKey = ByteArray(32) { (it + 1).toByte() }

        val sealed = sealer.seal(rawKey)

        assertTrue(sealed.startsWith("v1:"))
        assertEquals(3, sealed.split(':').size)
        assertArrayEquals(rawKey, sealer.unseal(sealed))
    }

    @Test
    fun rejectsMalformedSealedStrings() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmKeySealer.parse("v1:not-valid")
        }
    }

    @Test
    fun authenticationFailureBecomesCollectionKeyUnavailable() {
        val sealer = AesGcmKeySealer(StaticCollectionMasterKeyBackend())
        val rawKey = ByteArray(32) { (it + 7).toByte() }
        val sealed = sealer.seal(rawKey)
        val parts = sealed.split(':').toMutableList()
        parts[2] = parts[2].replaceLastBase64UrlCharacter()
        val tampered = parts.joinToString(":")

        assertThrows(CollectionKeyUnavailableException::class.java) {
            sealer.unseal(tampered)
        }
    }

    @Test
    fun legacyBase64KeysStillDecode() {
        val sealer = AesGcmKeySealer(StaticCollectionMasterKeyBackend())
        val rawKey = ByteArray(32) { (it * 2).toByte() }
        val legacy = Base64.getEncoder().encodeToString(rawKey)

        assertArrayEquals(rawKey, sealer.unsealOrDecodeLegacyKey(legacy))
    }

    private fun String.replaceLastBase64UrlCharacter(): String {
        val replacement = if (last() == 'A') 'B' else 'A'
        return dropLast(1) + replacement
    }
}

private class StaticCollectionMasterKeyBackend : CollectionMasterKeyBackend {
    private val key = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
    var resetCount: Int = 0
        private set

    override fun getOrCreateKey(): SecretKey = key

    override fun resetInvalidatedKey() {
        resetCount++
    }
}
