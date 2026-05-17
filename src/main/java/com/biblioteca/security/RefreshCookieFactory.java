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
 * SameSite — en prod el frontend (Vercel) y la API (servidor propio) viven en
 * dominios distintos, así que la cookie tiene que ser SameSite=None para que
 * el navegador la mande en el POST /api/auth/refresh cross-site. None exige
 * Secure=true, que ya se activa bajo perfil prod. En dev queda SameSite=Lax
 * (Chrome rechaza None sin Secure sobre http://localhost).
 *
 * Path=/api/auth — limita el scope para que la cookie no viaje en cada
 * request de negocio (menor superficie, menos bytes en cable).
 */
@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "refreshToken";
    private static final String PATH = "/api/auth";

    private final boolean secure;
    private final String sameSite;
    private final long refreshExpirationMs;

    public RefreshCookieFactory(Environment env,
                                @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        this.secure = prod;
        this.sameSite = prod ? "None" : "Lax";
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public ResponseCookie build(String rawToken) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
    }

    /** Cookie that, when set in the response, instructs the browser to delete the refresh cookie. */
    public ResponseCookie buildDeletion() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH)
                .maxAge(0)
                .build();
    }
}
