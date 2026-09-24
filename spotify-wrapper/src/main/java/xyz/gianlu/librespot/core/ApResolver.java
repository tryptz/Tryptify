package xyz.gianlu.librespot.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tryptify's replacement for librespot-java 1.6.5's ApResolver, which is
 * stripped out of libs/librespot-player-stripped-1.6.5.jar so this one takes
 * its place (see spotify-wrapper/build.gradle.kts).
 *
 * The original handed Session one access point picked at random from
 * apresolve's list, and Session connects to it exactly once: no second
 * address, no second port. The list mixes ports 4070, 443 and 80, and a
 * network that only lets real HTTP out on port 80 — a carrier or VPN proxy —
 * refuses Spotify's protocol there. Picking a :80 entry then failed the whole
 * sign-in with ECONNREFUSED while the other five addresses would have worked.
 *
 * {@link #getRandomAccesspoint()} now tries the list in order of preference —
 * port 4070, then 443, then 80, shuffled within each so load still spreads —
 * with a short TCP connect to each, and returns the first that answers. That
 * is what the Spotify clients do. Session still makes its own connection
 * afterwards; the probe only decides where.
 *
 * The list is fetched over HTTPS, not the original's plain HTTP, for the same
 * reason. Dealer picks are unchanged: those are WSS on 443. spclient is no
 * longer picked at all; see {@link #getRandomSpclient()}.
 */
public final class ApResolver {
    private static final String BASE_URL = "https://apresolve.spotify.com/";
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final String SPCLIENT = "spclient.wg.spotify.com:443";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApResolver.class);
    private final OkHttpClient client;
    private final Map<String, List<String>> pool = new HashMap<>(3);
    private volatile boolean poolReady = false;

    public ApResolver(OkHttpClient client) throws IOException {
        this.client = client;
        fillPool();
    }

    private void fillPool() throws IOException {
        request("accesspoint", "dealer", "spclient");
    }

    public void refreshPool() throws IOException {
        poolReady = false;
        pool.clear();
        fillPool();
    }

    private static List<String> getUrls(JsonObject body, String type) {
        JsonArray array = body.getAsJsonArray(type);
        List<String> list = new ArrayList<>(array == null ? 0 : array.size());
        if (array != null) {
            for (JsonElement element : array) list.add(element.getAsString());
        }
        return list;
    }

    private void request(String... types) throws IOException {
        if (types.length == 0) throw new IllegalArgumentException();

        StringBuilder url = new StringBuilder(BASE_URL + "?");
        for (int i = 0; i < types.length; i++) {
            if (i != 0) url.append("&");
            url.append("type=").append(types[i]);
        }

        Request request = new Request.Builder().url(url.toString()).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("No body");

            JsonObject obj = JsonParser.parseReader(body.charStream()).getAsJsonObject();
            Map<String, List<String>> map = new HashMap<>();
            for (String type : types) map.put(type, getUrls(obj, type));

            synchronized (pool) {
                pool.putAll(map);
                poolReady = true;
                pool.notifyAll();
            }

            LOGGER.info("Loaded aps into pool: " + pool);
        }
    }

    private void waitForPool() {
        if (!poolReady) {
            synchronized (pool) {
                try {
                    while (!poolReady) pool.wait();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    private List<String> urlsOf(String type) {
        waitForPool();
        List<String> urls = pool.get(type);
        if (urls == null || urls.isEmpty()) throw new IllegalStateException("No " + type + " in the pool");
        return urls;
    }

    private String getRandomOf(String type) {
        List<String> urls = urlsOf(type);
        return urls.get(ThreadLocalRandom.current().nextInt(urls.size()));
    }

    public String getRandomDealer() {
        return getRandomOf("dealer");
    }

    /**
     * Always spclient.wg.spotify.com, not a pick from apresolve's list: the
     * individual spclient hosts it hands out answer some requests with 500s.
     * Upstream librespot-java made the same change after 1.6.5 (5981fb5).
     */
    public String getRandomSpclient() {
        return SPCLIENT;
    }

    /**
     * The first access point that accepts a TCP connection, in order of port
     * preference. If none does, the most preferred one, so Session's own
     * connect fails with the real error rather than this method inventing one.
     */
    public String getRandomAccesspoint() {
        List<String> candidates = byPreference(urlsOf("accesspoint"));
        for (String candidate : candidates) {
            if (reachable(candidate)) {
                LOGGER.info("Using access point " + candidate);
                return candidate;
            }
            LOGGER.warn("Access point " + candidate + " unreachable, trying the next");
        }
        LOGGER.warn("No access point answered; trying " + candidates.get(0) + " anyway");
        return candidates.get(0);
    }

    /** 4070 first, then 443, then 80 and anything else; shuffled within each. */
    static List<String> byPreference(List<String> urls) {
        List<String> shuffled = new ArrayList<>(urls);
        Collections.shuffle(shuffled);
        List<String> ordered = new ArrayList<>(shuffled.size());
        for (int rank = 0; rank < 3; rank++) {
            for (String url : shuffled) {
                if (rankOf(url) == rank) ordered.add(url);
            }
        }
        return ordered;
    }

    private static int rankOf(String url) {
        if (url.endsWith(":4070")) return 0;
        if (url.endsWith(":443")) return 1;
        return 2;
    }

    private static boolean reachable(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) return false;
        String host = hostPort.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException ex) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
