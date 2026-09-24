package xyz.gianlu.librespot.core;

import com.spotify.login5v3.Credentials;
import com.spotify.login5v3.Login5;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
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
 * nothing the session doesn't already have. Login5Api fills in the client id
 * and solves the hashcash challenge.
 *
 * Same public surface as the original — getToken(String...) / get(String) and
 * StoredToken's public fields — because DealerClient, ApiClient and the rest
 * call it. A login5 token is not scoped per request the way keymaster's were,
 * so one token serves every scope until it expires.
 */
public final class TokenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenProvider.class);
    private static final int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private StoredToken token;

    TokenProvider(Session session) {
        this.session = session;
    }

    public synchronized StoredToken getToken(String... scopes) throws IOException, MercuryClient.MercuryException {
        if (scopes.length == 0) throw new IllegalArgumentException();
        if (token != null && !token.expired()) return token;

        Login5.LoginRequest request = Login5.LoginRequest.newBuilder()
                .setStoredCredential(Credentials.StoredCredential.newBuilder()
                        .setUsername(session.username())
                        .setData(session.apWelcome().getReusableAuthCredentials())
                        .build())
                .build();

        Login5.LoginResponse response;
        try {
            response = new Login5Api(session).login5(request);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("login5 challenge could not be solved", ex);
        }

        if (!response.hasOk()) {
            throw new IOException("login5 refused the token request: " + response.getError()
                    + " (" + response.getErrorValue() + ")");
        }

        Login5.LoginOk ok = response.getOk();
        token = new StoredToken(ok.getAccessTokenExpiresIn(), ok.getAccessToken(), scopes);
        LOGGER.debug("Updated token via login5, expires in {} s", ok.getAccessTokenExpiresIn());
        return token;
    }

    public String get(String scope) throws IOException, MercuryClient.MercuryException {
        return getToken(scope).accessToken;
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
