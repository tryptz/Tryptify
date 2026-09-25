/*
 * Copyright 2022 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.core;

import com.google.protobuf.ByteString;
import com.spotify.login5v3.Credentials;
import com.spotify.login5v3.Login5;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.mercury.MercuryClient;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

/**
 * Replaces librespot-java 1.6.5's TokenProvider, which is stripped out of
 * libs/librespot-player-stripped-1.6.5.jar.
 *
 * 1.6.5 fetches every access token from hm://keymaster/token/authenticated.
 * Spotify retired keymaster, so each request is refused with a 403, and every
 * caller fails with it: the dealer websocket, track metadata and
 * storage-resolve. The session logs in fine and then no track can load.
 *
 * This is upstream's fix from the librespot-java dev branch, which was never
 * released to Maven: exchange the session's reusable credential for a token at
 * login5.spotify.com. A login5 token is not scoped, so the scope arguments are
 * accepted for binary compatibility with 1.6.5's callers (ApiClient and
 * DealerClient call get(String)) and otherwise ignored.
 */
public final class TokenProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenProvider.class);
    private static final int TOKEN_EXPIRE_THRESHOLD = 10;
    private final Session session;
    private StoredToken token = null;

    TokenProvider(Session session) {
        this.session = session;
    }

    public synchronized StoredToken getToken(String... scopes) throws IOException, MercuryClient.MercuryException {
        if (token != null && !token.expired()) return token;

        LOGGER.debug("Token expired or missing, requesting a new one from login5. {oldToken: {}}", token);

        Login5.LoginResponse resp;
        try {
            resp = new Login5Api(session).login5(Login5.LoginRequest.newBuilder()
                    .setStoredCredential(Credentials.StoredCredential.newBuilder()
                            .setUsername(session.username())
                            .setData(session.apWelcome().getReusableAuthCredentials())
                            .build())
                    .build());
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }

        if (!resp.hasOk()) throw new TokenException(resp.getError().getNumber());

        Login5.LoginOk ok = resp.getOk();
        token = new StoredToken(ok.getAccessToken(), ok.getAccessTokenExpiresIn());
        LOGGER.debug("Updated token successfully! {newToken: {}}", token);
        return token;
    }

    public String get(String scope) throws IOException, MercuryClient.MercuryException {
        return getToken(scope).accessToken;
    }

    /**
     * Login5 refused the stored credential. An IOException so that 1.6.5's
     * callers, which only expect IOException or MercuryException, handle it.
     */
    public static final class TokenException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;

        private TokenException(int code) {
            super("login5 refused the stored credential, error " + code);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    public static final class StoredToken {
        public final int expiresIn;
        public final String accessToken;
        public final long timestamp;

        private StoredToken(String accessToken, int expiresIn) {
            this.timestamp = System.currentTimeMillis();
            this.expiresIn = expiresIn;
            this.accessToken = accessToken;
        }

        public boolean expired() {
            return timestamp + (expiresIn - TOKEN_EXPIRE_THRESHOLD) * 1000L < System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "StoredToken{expiresIn=" + expiresIn + ", timestamp=" + timestamp + '}';
        }
    }
}
