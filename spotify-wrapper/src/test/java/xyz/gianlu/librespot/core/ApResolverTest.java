package xyz.gianlu.librespot.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ApResolverTest {

    // The list apresolve returned when a phone's sign-in failed on
    // ap-gew4.spotify.com:80 with ECONNREFUSED.
    private static final List<String> POOL = Arrays.asList(
            "ap-guc3.spotify.com:4070",
            "ap-guc3.spotify.com:443",
            "ap-guc3.spotify.com:80",
            "ap-gae2.spotify.com:4070",
            "ap-gew1.spotify.com:443",
            "ap-gew4.spotify.com:80");

    @Test
    public void port80IsOnlyEverTheLastResort() {
        for (int i = 0; i < 50; i++) {
            List<String> ordered = ApResolver.byPreference(POOL);
            assertEquals(POOL.size(), ordered.size());
            assertTrue(ordered.containsAll(POOL));
            assertTrue(ordered.get(0).endsWith(":4070"));
            assertTrue(ordered.get(1).endsWith(":4070"));
            assertTrue(ordered.get(2).endsWith(":443"));
            assertTrue(ordered.get(3).endsWith(":443"));
            assertTrue(ordered.get(4).endsWith(":80"));
            assertTrue(ordered.get(5).endsWith(":80"));
        }
    }
}
