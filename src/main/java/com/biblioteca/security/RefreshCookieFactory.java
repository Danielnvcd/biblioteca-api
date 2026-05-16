package com.biblioteca.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * Builds the Set-Cookie value for the refresh token.
 *
 * SameSite=Strict — the browser will never send this cookie on cross-site
 * navigations, mitigating CSRF on the /api/auth/refresh endpoint.
 *
 * Secure — required by browsers for SameSite=None, and best-practice for
 * httpOnly auth cookies; only enabled under the "prod" profile because dev
 * runs over http://localhost.
 *
 * Path=/api/auth — limits scope so the cookie isn't sent on every business
 * request (smaller exposure window, less data on the wire).
 */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";
    private static final String PATH = "/api/auth";

    private final boolean secure;
    private final long refreshExpirationMs;

    public RefreshCookieFactory(Environment env,
                                @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.secure = Arrays.asList(env.getActiveProfiles()).contains("prod");
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public ResponseCookie build(String rawToken) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
    }

    /** Cookie that, when set in the response, instructs the browser to delete the refresh cookie. */
    public ResponseCookie buildDeletion() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(0)
                .build();
    }
}
