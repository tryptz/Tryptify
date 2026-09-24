package xyz.gianlu.librespot.core;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.spotify.login5v3.ClientInfoOuterClass;
import com.spotify.login5v3.Credentials;
import com.spotify.login5v3.Hashcash;
import com.spotify.login5v3.Login5;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.dealer.ApiClient;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Tryptify's replacement for librespot-java 1.6.5's TokenProvider, which is
 * stripped out of libs/librespot-player-stripped-1.6.5.jar so this one takes
 * its place (see spotify-wrapper/build.gradle.kts).
 *
 * The original asked Mercury's keymaster endpoint
 * (hm://keymaster/token/authenticated) for access tokens. Spotify has retired
 * it: it answers 403, and because Session.authenticate() connects the dealer
 * right after logging in — which needs a token — every login failed with
 * "MercuryException: status: 403" even though the credentials were accepted.
 *
 * This one gets tokens from login5 (https://login5.spotify.com/v3/login), the
 * way current Spotify clients and librespot (Rust) do, through the Login5Api
 * that 1.6.5 already ships but never calls. It presents the reusable
 * credential the access point handed back at login (APWelcome), so it needs
 * nothing the session doesn't already have.
 *
 * The request is built here rather than through 1.6.5's Login5Api, because
 * Login5Api hardcodes librespot's own desktop client id. login5 only honours
 * a reusable credential under the client id it was minted for, and Tryptify's
 * credentials come from a login with Tryptify's own OAuth token — so under
 * librespot's id every one of them, fresh or stored, came back
 * INVALID_CREDENTIALS. {@link #setClientId} sets the id to present; the
 * hashcash challenge login5 answers the first request with is solved below,
 * the same way Login5Api solves it.
 *
 * Same public surface as the original — getToken(String...) / get(String) and
 * StoredToken's public fields — because DealerClient, ApiClient and the rest
 * call it. A login5 token is not scoped per request the way keymaster's were,
 * so one token serves every scope until it expires.
 */
public final class TokenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenProvider.class);
    private static final int TOKEN_EXPIRE_THRESHOLD = 10;
    private static final String LOGIN5_URL = "https://login5.spotify.com/v3/login";
    /** librespot's desktop client id; what Login5Api always sends. */
    private static final String LIBRESPOT_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    private static volatile String clientId = LIBRESPOT_CLIENT_ID;
    private final Session session;
    private StoredToken token;

    TokenProvider(Session session) {
        this.session = session;
    }

    public synchronized StoredToken getToken(String... scopes) throws IOException, MercuryClient.MercuryException {
        if (scopes.length == 0) throw new IllegalArgumentException();
        if (token != null && !token.expired()) return token;

        Login5.LoginRequest request = Login5.LoginRequest.newBuilder()
                .setClientInfo(ClientInfoOuterClass.ClientInfo.newBuilder()
                        .setClientId(clientId)
                        .setDeviceId(session.deviceId())
                        .build())
                .setStoredCredential(Credentials.StoredCredential.newBuilder()
                        .setUsername(session.username())
                        .setData(session.apWelcome().getReusableAuthCredentials())
                        .build())
                .build();

        Login5.LoginResponse response = send(request);
        if (response.hasChallenges() && response.getChallenges().getChallengesCount() > 0) {
            try {
                response = send(request.toBuilder()
                        .setLoginContext(response.getLoginContext())
                        .setChallengeSolutions(solve(response))
                        .build());
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("login5 challenge could not be solved", ex);
            }
        }

        if (!response.hasOk()) throw new Login5Exception(response.getError(), response.getErrorValue());

        Login5.LoginOk ok = response.getOk();
        token = new StoredToken(ok.getAccessTokenExpiresIn(), ok.getAccessToken(), scopes);
        LOGGER.debug("Updated token via login5, expires in {} s", ok.getAccessTokenExpiresIn());
        return token;
    }

    /**
     * The OAuth client id the reusable credentials were minted under — the
     * one whose access token signed the session in. Set before connecting.
     */
    public static void setClientId(String id) {
        clientId = id == null || id.isEmpty() ? LIBRESPOT_CLIENT_ID : id;
    }

    private Login5.LoginResponse send(Login5.LoginRequest request) throws IOException {
        Request http = new Request.Builder()
                .url(LOGIN5_URL)
                .post(ApiClient.protoBody(request))
                .build();
        try (Response response = session.client().newCall(http).execute()) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("login5: no body (HTTP " + response.code() + ")");
            if (!response.isSuccessful()) throw new IOException("login5: HTTP " + response.code());
            return Login5.LoginResponse.parseFrom(body.bytes());
        }
    }

    /**
     * login5's hashcash: find a 16-byte suffix whose SHA-1, after the prefix,
     * ends in at least {@code length} zero bits. Both 8-byte halves of the
     * suffix start from the last 8 bytes of SHA-1(login context) and count up
     * together — the same search Login5Api and librespot (Rust) run.
     */
    private static Login5.ChallengeSolutions solve(Login5.LoginResponse response) throws NoSuchAlgorithmException {
        Hashcash.HashcashChallenge challenge = response.getChallenges().getChallenges(0).getHashcash();
        byte[] prefix = challenge.getPrefix().toByteArray();
        byte[] seed = MessageDigest.getInstance("SHA1").digest(response.getLoginContext().toByteArray());

        byte[] suffix = new byte[16];
        System.arraycopy(seed, 12, suffix, 0, 8);
        MessageDigest sha1 = MessageDigest.getInstance("SHA1");
        long startedAt = System.nanoTime();
        while (true) {
            sha1.reset();
            sha1.update(prefix);
            sha1.update(suffix);
            if (trailingZeroBits(sha1.digest()) >= challenge.getLength()) break;
            increment(suffix, 7);
            increment(suffix, 15);
        }
        long elapsed = System.nanoTime() - startedAt;

        return Login5.ChallengeSolutions.newBuilder()
                .addSolutions(Login5.ChallengeSolution.newBuilder()
                        .setHashcash(Hashcash.HashcashSolution.newBuilder()
                                .setSuffix(ByteString.copyFrom(suffix))
                                .setDuration(Duration.newBuilder()
                                        .setSeconds(elapsed / 1_000_000_000L)
                                        .setNanos((int) (elapsed % 1_000_000_000L))
                                        .build())
                                .build())
                        .build())
                .build();
    }

    static int trailingZeroBits(byte[] digest) {
        int zeros = 0;
        for (int i = digest.length - 1; i >= 0; i--) {
            if (digest[i] == 0) {
                zeros += 8;
            } else {
                return zeros + Integer.numberOfTrailingZeros(digest[i] & 0xff);
            }
        }
        return zeros;
    }

    /** Big-endian increment of the 8-byte counter ending at {@code last}. */
    static void increment(byte[] bytes, int last) {
        for (int i = last; i > last - 8; i--) {
            if (++bytes[i] != 0) return;
        }
    }

    public String get(String scope) throws IOException, MercuryClient.MercuryException {
        return getToken(scope).accessToken;
    }

    /**
     * login5 answered, and said no. Typed, rather than a bare IOException, so a
     * caller can tell INVALID_CREDENTIALS — the reusable credential is no good
     * and a fresh login might be — from a network failure, where it isn't.
     */
    public static final class Login5Exception extends IOException {
        public final Login5.LoginError error;

        Login5Exception(Login5.LoginError error, int errorValue) {
            super("login5 refused the token request: " + error + " (" + errorValue + ")");
            this.error = error;
        }
    }

    public static class StoredToken {
        public final int expiresIn;
        public final String accessToken;
        public final String[] scopes;
        public final long timestamp;

        private StoredToken(int expiresIn, String accessToken, String[] scopes) {
            this.timestamp = TimeProvider.currentTimeMillis();
            this.expiresIn = expiresIn;
            this.accessToken = accessToken;
            this.scopes = scopes;
        }

        public boolean expired() {
            return timestamp + (expiresIn - TOKEN_EXPIRE_THRESHOLD) * 1000L < TimeProvider.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "StoredToken{expiresIn=" + expiresIn + ", scopes=" + Arrays.toString(scopes)
                    + ", timestamp=" + timestamp + '}';
        }

        /** login5 tokens are not narrowed per scope. */
        public boolean hasScope(String scope) {
            return true;
        }

        public boolean hasScopes(String[] sc) {
            return true;
        }
    }
}
