package tf.monochrome.android.data.collections.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AesGcmKeySealer internal constructor(
    private val keyBackend: CollectionMasterKeyBackend,
) {

    @Inject
    constructor() : this(AndroidKeystoreCollectionMasterKeyBackend(DEFAULT_KEY_ALIAS))

    fun seal(rawKey: ByteArray): String {
        return try {
            sealWithCurrentKey(rawKey)
        } catch (e: KeyPermanentlyInvalidatedException) {
            resetInvalidatedKey()
            throw CollectionKeyUnavailableException(KEY_INVALIDATED_MESSAGE, e)
        }
    }

    fun unseal(serialized: String): ByteArray {
        return try {
            val sealed = parse(serialized)
            unsealWithCurrentKey(sealed.iv, sealed.ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            resetInvalidatedKey()
            throw CollectionKeyUnavailableException(KEY_INVALIDATED_MESSAGE, e)
        } catch (e: AEADBadTagException) {
            throw CollectionKeyUnavailableException(KEY_AUTHENTICATION_FAILED_MESSAGE, e)
        }
    }

    fun unsealOrDecodeLegacyKey(serialized: String): ByteArray {
        return if (isSealed(serialized)) {
            unseal(serialized)
        } else {
            Base64.getMimeDecoder().decode(serialized)
        }
    }

    private fun sealWithCurrentKey(rawKey: ByteArray): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keyBackend.getOrCreateKey())
        return serialize(cipher.iv, cipher.doFinal(rawKey))
    }

    private fun unsealWithCurrentKey(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyBackend.getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    private fun resetInvalidatedKey() {
        keyBackend.resetInvalidatedKey()
        keyBackend.getOrCreateKey()
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "tryptify_collection_master_key"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val VERSION = "v1"

        const val KEY_INVALIDATED_MESSAGE =
            "This encrypted collection can no longer be opened because Android invalidated the device key. Please re-download the collection."
        const val KEY_AUTHENTICATION_FAILED_MESSAGE =
            "Collection key authentication failed. Please re-download the collection."

        fun isSealed(serialized: String): Boolean = serialized.startsWith("$VERSION:")

        internal fun serialize(iv: ByteArray, ciphertext: ByteArray): String {
            require(iv.size == IV_LENGTH_BYTES) { "AES-GCM sealed keys require a 12-byte IV" }
            require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return listOf(
                VERSION,
                encoder.encodeToString(iv),
                encoder.encodeToString(ciphertext),
            ).joinToString(":")
        }

        internal fun parse(serialized: String): SerializedSealedCollectionKey {
            val parts = serialized.split(':')
            require(parts.size == 3 && parts[0] == VERSION) {
                "Malformed sealed collection key"
            }
            val decoder = Base64.getUrlDecoder()
            val iv = runCatching { decoder.decode(parts[1]) }
                .getOrElse { throw IllegalArgumentException("Malformed sealed collection key IV", it) }
            val ciphertext = runCatching { decoder.decode(parts[2]) }
                .getOrElse { throw IllegalArgumentException("Malformed sealed collection key ciphertext", it) }
            require(iv.size == IV_LENGTH_BYTES) { "Malformed sealed collection key IV" }
            require(ciphertext.isNotEmpty()) { "Malformed sealed collection key ciphertext" }
            return SerializedSealedCollectionKey(iv = iv, ciphertext = ciphertext)
        }
    }
}

internal data class SerializedSealedCollectionKey(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal interface CollectionMasterKeyBackend {
    fun getOrCreateKey(): SecretKey
    fun resetInvalidatedKey()
}

private class AndroidKeystoreCollectionMasterKeyBackend(
    private val keyAlias: String,
) : CollectionMasterKeyBackend {
    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        return existing ?: generateMasterKey()
    }

    override fun resetInvalidatedKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun generateMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
