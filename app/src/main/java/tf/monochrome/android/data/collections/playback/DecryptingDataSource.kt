package tf.monochrome.android.data.collections.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import tf.monochrome.android.data.collections.crypto.AesGcmDecryptor
import tf.monochrome.android.data.collections.crypto.AesGcmKeySealer
import tf.monochrome.android.data.collections.crypto.CollectionKeyUnavailableException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A DataSource that decrypts AES-256-GCM encrypted audio streams.
 * Wraps an upstream DataSource, reads the full encrypted payload,
 * decrypts it, and serves the plaintext audio data.
 */
@OptIn(UnstableApi::class)
class DecryptingDataSource(
    private val upstream: DataSource,
    private val encryptionKey: String,
    private val decryptor: AesGcmDecryptor,
    private val keySealer: AesGcmKeySealer,
) : DataSource {

    private var decryptedStream: InputStream? = null
    private var bytesRemaining: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            // Open upstream and read all encrypted data
            upstream.open(dataSpec)
            val encryptedData = readAllBytes(upstream)
            upstream.close()

            // Decrypt. New imports store v1-sealed collection keys; legacy
            // imports stored the raw base64 AES key and remain supported.
            val keyBytes = keySealer.unsealOrDecodeLegacyKey(encryptionKey)
            val decryptedData = decryptor.decrypt(encryptedData, keyBytes)

            // Handle position/length from dataSpec
            val offset = dataSpec.position.toInt()
            val length = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length.toInt()
            } else {
                decryptedData.size - offset
            }

            decryptedStream = ByteArrayInputStream(decryptedData, offset, length)
            bytesRemaining = length.toLong()

            return bytesRemaining
        } catch (e: CollectionKeyUnavailableException) {
            throw IOException(e.message ?: "Collection key unavailable", e)
        } catch (e: Exception) {
            throw IOException("Failed to decrypt audio data", e)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = decryptedStream ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = stream.read(buffer, offset, toRead)

        if (bytesRead == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }

        bytesRemaining -= bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        decryptedStream?.close()
        decryptedStream = null
        bytesRemaining = 0
    }

    private fun readAllBytes(source: DataSource): ByteArray {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        var bytesRead: Int
        while (source.read(buffer, 0, buffer.size).also { bytesRead = it } != C.RESULT_END_OF_INPUT) {
            output.write(buffer, 0, bytesRead)
        }
        return output.toByteArray()
    }

    @OptIn(UnstableApi::class)
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val encryptionKey: String,
        private val decryptor: AesGcmDecryptor,
        private val keySealer: AesGcmKeySealer,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return DecryptingDataSource(
                upstream = upstreamFactory.createDataSource(),
                encryptionKey = encryptionKey,
                decryptor = decryptor,
                keySealer = keySealer,
            )
        }
    }
}
