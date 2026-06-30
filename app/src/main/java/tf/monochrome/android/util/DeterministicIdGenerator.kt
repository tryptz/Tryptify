package tf.monochrome.android.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object DeterministicIdGenerator {

    fun longFromStableText(input: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.trim().lowercase().toByteArray(Charsets.UTF_8))

        return ByteBuffer.wrap(digest)
            .order(ByteOrder.BIG_ENDIAN)
            .long
    }

    fun positiveLongFromStableText(input: String): Long {
        return longFromStableText(input) and Long.MAX_VALUE
    }
}
