package tf.monochrome.android.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicIdGeneratorTest {

    @Test
    fun sameInputAlwaysProducesSameId() {
        val first = DeterministicIdGenerator.longFromStableText("Artist - Track")
        val second = DeterministicIdGenerator.longFromStableText("Artist - Track")

        assertEquals(first, second)
    }

    @Test
    fun normalizesWhitespaceAndCase() {
        assertEquals(
            DeterministicIdGenerator.longFromStableText("  Artist - Track  "),
            DeterministicIdGenerator.longFromStableText("artist - track"),
        )
    }

    @Test
    fun differentInputProducesDifferentId() {
        assertNotEquals(
            DeterministicIdGenerator.longFromStableText("artist - track one"),
            DeterministicIdGenerator.longFromStableText("artist - track two"),
        )
    }

    @Test
    fun byteBufferImplementationMatchesGoldenVector() {
        val normalized = "golden vector".toByteArray(Charsets.UTF_8)
        val expected = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(normalized))
            .order(ByteOrder.BIG_ENDIAN)
            .long

        assertEquals(expected, DeterministicIdGenerator.longFromStableText("Golden Vector"))
    }

    @Test
    fun positiveIdClearsSignBit() {
        assertTrue(DeterministicIdGenerator.positiveLongFromStableText("anything") >= 0L)
    }
}
