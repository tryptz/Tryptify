package xyz.gianlu.librespot.core;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TokenProviderTest {

    @Test
    public void countsZeroBitsFromTheEndOfTheDigest() {
        assertEquals(0, TokenProvider.trailingZeroBits(new byte[]{0, 0, 1}));
        assertEquals(10, TokenProvider.trailingZeroBits(new byte[]{7, 4, 0}));
        assertEquals(16, TokenProvider.trailingZeroBits(new byte[]{1, 0, 0}));
        assertEquals(7, TokenProvider.trailingZeroBits(new byte[]{0, (byte) 0x80}));
        assertEquals(24, TokenProvider.trailingZeroBits(new byte[]{0, 0, 0}));
    }

    @Test
    public void incrementCarriesWithinItsOwnEightBytes() {
        byte[] suffix = new byte[16];
        suffix[7] = (byte) 0xff;
        suffix[6] = (byte) 0xff;
        TokenProvider.increment(suffix, 7);
        byte[] expected = new byte[16];
        expected[5] = 1;
        assertArrayEquals(expected, suffix);

        // The second counter never carries into the first.
        byte[] high = new byte[16];
        for (int i = 8; i < 16; i++) high[i] = (byte) 0xff;
        TokenProvider.increment(high, 15);
        assertArrayEquals(new byte[16], high);
    }
}
